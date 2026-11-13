package org.yaop.core.backup

import org.yaop.core.backup.BackupContainer.ASSET_CHUNK
import org.yaop.core.backup.BackupContainer.KIND_ASSET
import org.yaop.core.backup.BackupContainer.KIND_OBJ
import org.yaop.core.backup.BackupContainer.KIND_TYPE
import org.yaop.core.crypto.AssetStorage
import org.yaop.core.crypto.vault.VaultKey
import org.yaop.core.repository.PkmRepository
import org.yaop.core.sync.MergeContract
import org.yaop.core.sync.SyncContractCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Full encrypted backup and restore (spec §15.6, plan backup): the whole vault plus its assets
 * in one `.yaop` file that opens with the phrase alone, no server needed.
 */
class BackupManager(
    private val repository: PkmRepository,
    private val media: AssetStorage,
) {
    data class Summary(val objects: Int, val types: Int, val assets: Int)

    suspend fun export(out: OutputStream, vk: VaultKey): Summary = withContext(Dispatchers.IO) {
        val w = BackupContainer.Writer(out, vk)
        var objects = 0; var types = 0; var assets = 0
        for (type in repository.getAllTypes().first()) {
            val wire = SyncContractCodec.typeToWireJson(type)
            w.write(BackupContainer.Record(KIND_TYPE, type.id, data = wire.toString().toByteArray(Charsets.UTF_8)))
            types++
        }
        for (obj in repository.getAllActiveObjectsSnapshot()) {
            val wire = repository.exportObjectForServer(obj.id) ?: continue
            w.write(BackupContainer.Record(KIND_OBJ, obj.id, data = wire.toString().toByteArray(Charsets.UTF_8)))
            objects++
            if (!obj.id.startsWith("media_")) continue
            val assetId = obj.id.removePrefix("media_")
            val bytes = media.readMediaAny(assetId) ?: continue
            val total = maxOf(1, (bytes.size + ASSET_CHUNK - 1) / ASSET_CHUNK)
            for (i in 0 until total) {
                val chunk = bytes.copyOfRange(i * ASSET_CHUNK, minOf(bytes.size, (i + 1) * ASSET_CHUNK))
                w.write(BackupContainer.Record(KIND_ASSET, assetId, i.toLong(), total.toLong(), chunk))
            }
            assets++
        }
        w.finish()
        Summary(objects, types, assets)
    }

    /**
     * Restores into the current vault by merging, never overwriting: restoring twice, or over
     * a vault that already has data, loses nothing. Everything is queued to re-upload.
     */
    suspend fun restore(input: InputStream, vk: VaultKey): Summary = withContext(Dispatchers.IO) {
        var objects = 0; var types = 0
        val mimes = HashMap<String, String>()
        val pending = HashMap<String, java.io.ByteArrayOutputStream>()
        val assetIds = HashSet<String>()
        BackupContainer.read(input, vk) { r ->
            when (r.kind) {
                KIND_TYPE -> {
                    runBlocking { repository.applyTypeWire(JSONObject(String(r.data, Charsets.UTF_8))) }
                    types++
                }
                KIND_OBJ -> {
                    val remote = JSONObject(String(r.data, Charsets.UTF_8))
                    runBlocking {
                        val local = repository.exportObjectForServer(r.id)
                        val merged = if (local != null) MergeContract.merge(local, remote) else remote
                        repository.applyMergedWire(r.id, merged, remote)
                    }
                    objects++
                    if (r.id.startsWith("media_")) {
                        mimes[r.id.removePrefix("media_")] =
                            remote.optJSONObject("properties")?.optString("mime", "")?.takeIf { it.isNotBlank() }
                                ?: "application/octet-stream"
                    }
                }
                KIND_ASSET -> {
                    val buf = pending.getOrPut(r.id) { java.io.ByteArrayOutputStream() }
                    buf.write(r.data)
                    if (r.n + 1 >= r.total) {
                        media.saveMedia(r.id, buf.toByteArray(), mimes[r.id] ?: "application/octet-stream")
                        pending.remove(r.id)
                        assetIds.add(r.id)
                    }
                }
            }
        }
        repository.markEverythingForResend()
        Summary(objects, types, assetIds.size)
    }
}
