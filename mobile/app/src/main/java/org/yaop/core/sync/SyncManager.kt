package org.yaop.core.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.yaop.core.crypto.EncryptedMediaStore
import org.yaop.core.crypto.vault.Aead
import org.yaop.core.crypto.vault.Envelope
import org.yaop.core.crypto.vault.VaultCryptoSession
import org.yaop.core.database.OutboxOpEntity
import org.yaop.core.model.HybridLogicalClock
import org.yaop.core.repository.PkmRepository
import org.yaop.core.sync.ops.DerivedOpHandler
import org.yaop.core.sync.ops.DerivedSink
import org.yaop.core.sync.ops.FileDerivedSink
import org.yaop.core.sync.ops.NodeRequestOpHandler
import org.yaop.core.sync.ops.ObjectOpHandler
import org.yaop.core.sync.ops.OpResult
import org.yaop.core.sync.ops.BootstrapSnapshot
import org.yaop.core.sync.ops.PassthroughOpHandler
import org.yaop.core.sync.ops.SnapshotOpHandler
import org.yaop.core.sync.ops.RemoteOp
import org.yaop.core.sync.ops.RemoteOpPipeline
import org.yaop.core.sync.ops.RemoteOpRegistry
import org.yaop.core.sync.ops.TypeOpHandler
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.ceil
import kotlin.random.Random

private const val TAG = "SyncManager"
private const val KEY_SERVER_VAULT = "server_vault_id"
private val OP_ID_HEX32 = Regex("^[0-9a-f]{32}$")
private const val PREFS_NAME = "yaop_sync_prefs"
private const val KEY_SERVER_URL = "server_url"
private const val KEY_BEARER_TOKEN = "bearer_token"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_ENROLLED_FOR = "device_token_for"

// No server by default: the app is local-first. The user sets one in Settings or by pairing.
private const val DEFAULT_SERVER_URL = ""
private const val DEFAULT_BEARER_TOKEN = ""

/** Stable per-install device id (also the HLC node source, spec/merge.md). */
private fun deviceIdFor(prefs: SharedPreferences): String {
    var id = prefs.getString(KEY_DEVICE_ID, null)
    if (id.isNullOrBlank()) {
        id = "android-" + UUID.randomUUID().toString().take(8)
        prefs.edit().putString(KEY_DEVICE_ID, id).commit()
    }
    return id
}

fun deviceIdFor(context: Context): String =
    deviceIdFor(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

private fun migrateServerUrl(url: String?): String = url?.trim().orEmpty()

data class SyncState(
    val isSyncing: Boolean = false,
    val lastSyncTime: Long = 0L,
    val lastError: String? = null,
    /** Readable reason of the last failure (spec §22.6): protocol_mismatch, unauthorized, … */
    val lastErrorCode: String? = null,
    val serverUrl: String = DEFAULT_SERVER_URL,
    val isConnected: Boolean = false,
    val lastPushedCount: Int = 0,
    val lastPulledCount: Int = 0,
    /** Ops selladas esperando reintento de push (spec/sync.md outbox). */
    val pendingUploads: Int = 0,
    /** A local VK exists (false = no vault yet: onboarding not done, nothing syncs). */
    val vaultReady: Boolean = false,
    /** The server speaks yaop-sync/1 (false = protocol_mismatch: never sync blindly). */
    val protocolOk: Boolean = true,
    /** Protocol version reported by the server (for diagnostics in the chip). */
    val serverProtocolVersion: String? = null,
)

/**
 * Reconnect delay for the live stream : 1 s doubling up to 60 s, ±20 % jitter so several
 * devices do not reconnect in lockstep after a server restart. [jitter01] is in [0, 1).
 */
fun liveBackoffMs(attempt: Int, jitter01: Double): Long {
    val base = minOf(60_000.0, 1000.0 * Math.pow(2.0, attempt.coerceIn(0, 16).toDouble()))
    return (base * (0.8 + 0.4 * jitter01.coerceIn(0.0, 1.0))).toLong()
}

/** Plain asset chunk: 4 MiB (spec/sync.md, assets); the last one is smaller. */
private const val ASSET_PLAIN_CHUNK: Int = 4 * 1024 * 1024
/** Envelope header (29) + Poly1305 tag (16): extra bytes of EVERY encrypted chunk. */
private val ENVELOPE_OVERHEAD_BYTES = Envelope.HEADER_LEN + Aead.TAG_BYTES

/**
 * `yaop-sync/1` client (spec/sync.md): EVERYTHING that leaves the device travels in a
 * spec/crypto.md envelope (XChaCha20-Poly1305 AEAD per domain) and ids are blind
 * (`wire_id` = HMAC with the ids subkey). The server only sees opaque blobs: no titles, MIME
 * types, file names or real ids.
 *
 * - Push: pending objects are sealed into a persistent outbox (`outbox_ops`) and pushed in
 *   batches of ≤ 500; the idempotent `op_id` makes any retry safe.
 * - Pull: persistent `since_seq` cursor per vault; each op is decrypted and merged LOCALLY
 *   (MergeContract.merge with block_anchor, spec/merge.md), the result is applied once and,
 *   if it differs from the remote one, the merged state travels again on the next push (the
 *   merge is deterministic and symmetric: both sides converge to the same wire).
 * - Handshake `/v1/sync/status`: if the server speaks another protocol the chip shows it and
 *   NOTHING syncs (spec §16.4: never blindly).
 * - Without a local VK (onboarding not done) the manager stays idle with a visible state.
 */
class SyncManager(
    private val context: Context,
    private val repository: PkmRepository,
    private val mediaStore: EncryptedMediaStore,
    private val scope: CoroutineScope,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _syncState = MutableStateFlow(
        SyncState(
            serverUrl = migrateServerUrl(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL))
        )
    )
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    val serverUrl: String
        get() = migrateServerUrl(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL))

    val bearerToken: String
        get() = prefs.getString(KEY_BEARER_TOKEN, DEFAULT_BEARER_TOKEN) ?: DEFAULT_BEARER_TOKEN

    val deviceId: String
        get() = deviceIdFor(prefs)

    init {
        // The chip must not claim "no vault" just because no sync ran yet.
        scope.launch {
            if (repository.vaultKeyStore.hasVaultKey()) _syncState.value = _syncState.value.copy(vaultReady = true)
        }
        // Test connectivity, initial sync, and start real-time live streaming
        scope.launch {
            checkHealth()
            syncNow()
            startLiveStream()
        }
    }

    fun updateConfig(url: String, token: String) {
        prefs.edit()
            .putString(KEY_SERVER_URL, url.trim().trimEnd('/'))
            .putString(KEY_BEARER_TOKEN, token.trim())
            .remove(KEY_ENROLLED_FOR) // a newly pasted token may be the master: enroll again
            .apply()
        _syncState.value = _syncState.value.copy(
            serverUrl = url.trim().trimEnd('/')
        )
        scope.launch {
            checkHealth()
            syncNow()
            startLiveStream()
        }
    }

    private val syncMutex = Mutex()
    @Volatile private var resyncRequested = false
    private var pushDebounceJob: Job? = null
    private var pullDebounceJob: Job? = null
    private var liveStreamJob: Job? = null

    private fun transport(): BlindTransport = BlindTransport(serverUrl, bearerToken, deviceId)

    /** Updates the state with a readable reason (spec §22.6). */
    private fun failState(code: String, detail: String) {
        _syncState.value = _syncState.value.copy(
            isSyncing = false,
            lastError = detail.takeIf { it.isNotBlank() } ?: code,
            lastErrorCode = code,
        )
    }

    /**
     * Direct asset upload for tests and callers that pass a MIME: under /3 the MIME and the
     * file name live ONLY inside the encrypted `media_<asset>` object (spec/sync.md §Assets).
     */
    fun uploadAssetIfMissing(assetId: String, mimeType: String = "image/jpeg", name: String = assetId): Boolean {
        // keeping the old signature for callers; under /v1 the wire object drives the upload
        return false
    }

    /**
     * Uploads an asset AS ENCRYPTED CHUNKS over the resumable upload (spec/sync.md, assets):
     * splits the plaintext into 4 MiB chunks, each in its own envelope (domain assets, AAD
     * `assets/<asset_wire_id>/chunk/<n>`, version 0x01), and pushes the ciphertext stream with
     * Upload-Offset. MIME type and name do NOT travel (only inside the encrypted media_* object).
     * Server-side dedup by ciphertext hash is the server's business (spec §15.4).
     */
    private fun uploadBlindAssetChunks(session: VaultCryptoSession, assetId: String, mediaWireId: String): Boolean {
        if (assetId.isBlank() || mediaStore.hasMedia(assetId) == false) return false
        val bytes = mediaStore.readMediaAny(assetId) ?: return false
        val chunkCount = ceil(bytes.size.toDouble() / ASSET_PLAIN_CHUNK).toInt().coerceAtLeast(1)
        val chunks = (0 until chunkCount).map { i ->
            val from = i * ASSET_PLAIN_CHUNK
            val to = minOf(bytes.size, from + ASSET_PLAIN_CHUNK)
            session.sealChunk(mediaWireId, i, bytes.copyOfRange(from, to))
        }
        val total = chunks.sumOf { it.size.toLong() }

        // Resumable upload by CHUNK INDEX (server: Upload-Offset = chunk n, Upload-Length = N chunks).
        val encodedWireId = java.net.URLEncoder.encode(mediaWireId, "UTF-8").replace("+", "%20")
        val uploadUrl = "$serverUrl/v1/assets/$encodedWireId/upload"
        var next = uploadStatusNextChunk(uploadUrl, chunkCount) ?: return false
        var guard = 0
        while (next < chunkCount && guard++ < chunkCount * 4 + 8) {
            val part = chunks[next]
            val conn = URL(uploadUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.requestMethod = "PUT"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $bearerToken")
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setRequestProperty("Upload-Offset", next.toString())
                conn.setRequestProperty("Upload-Length", chunkCount.toString())
                conn.setFixedLengthStreamingMode(part.size)
                conn.outputStream.use { it.write(part) }
                val code = conn.responseCode
                val hdr = conn.getHeaderField("Upload-Offset")?.toIntOrNull()
                when {
                    code == 201 -> next = chunkCount // complete
                    code in 200..299 && hdr != null -> next = hdr
                    code == 409 && hdr != null -> next = hdr // resync with the server
                    else -> {
                        Log.w(TAG, "Blind upload of $mediaWireId stopped at chunk $next: HTTP $code")
                        return false
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        val done = next >= chunkCount
        if (done) Log.i(TAG, "Uploaded $chunkCount encrypted chunk(s) of $mediaWireId ($total cipher bytes)")
        return done
    }

    /** Next chunk the server expects (chunkCount = already complete); null if it cannot be asked
     *  or a different stream already lives under this id. */
    private fun uploadStatusNextChunk(uploadUrl: String, chunkCount: Int): Int? {
        val conn = URL(uploadUrl).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
            if (conn.responseCode !in 200..299) return null
            val body = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val declared = body.optInt("total_chunks", -1)
            when {
                body.optBoolean("complete") -> chunkCount
                declared > 0 && declared != chunkCount -> null
                else -> body.optInt("next_chunk", 0)
            }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads an asset on demand. Content on the server is ALWAYS encrypted and only the
     * manifest inside the media_* object allows rebuilding it, so this returns false when that
     * object has not arrived yet (it never stores garbage).
     */
    fun downloadAssetIfMissing(assetId: String, mimeType: String = "image/jpeg"): Boolean {
        if (mediaStore.hasMedia(assetId)) return true
        // One download per asset: several views ask for the same image at once.
        val lock = assetDownloadLocks.getOrPut(assetId) { Any() }
        return synchronized(lock) { downloadAssetLocked(assetId) }
    }

    private val assetDownloadLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun downloadAssetLocked(assetId: String): Boolean {
        if (mediaStore.hasMedia(assetId)) return true
        // Called from IO workers of the UI (never the main thread): resolve the media object's
        // manifest and pull the sealed chunks. Without the media object there is nothing to open.
        return kotlinx.coroutines.runBlocking {
            val session = repository.vaultSession() ?: return@runBlocking false
            val wire = repository.exportObjectForServer("media_$assetId") ?: return@runBlocking false
            val wireId = session.wireId("media_$assetId")
            val manifest = assetManifestOf(wire, wireId) ?: return@runBlocking false
            downloadBlindAsset(session, assetId, wireId, manifest)
        }
    }

    /** Asset manifest inside the media_* object (spec/sync.md, assets). */
    data class AssetManifest(val wireId: String, val chunks: Int, val chunkSize: Int, val totalPlainSize: Long)

    /** [fallbackWireId] = blind id of the media object: the id the sealed chunks live under. */
    private fun assetManifestOf(mediaWire: JSONObject, fallbackWireId: String): AssetManifest? {
        val props = mediaWire.optJSONObject("properties") ?: return null
        val wireId = props.optString("asset_wire_id", "").takeIf { it.isNotBlank() } ?: fallbackWireId
        val chunks = props.optInt("chunks", 0)
        if (chunks <= 0) return null
        return AssetManifest(
            wireId = wireId,
            chunks = chunks,
            chunkSize = props.optInt("chunk_size", ASSET_PLAIN_CHUNK),
            totalPlainSize = props.optLong("total_plain_size", 0L),
        )
    }

    /**
     * Downloads encrypted chunk by chunk on demand (Range over the ciphertext stream, falling
     * back to the full body if the server ignores Range), rebuilds the plaintext and stores it
     * encrypted locally. The requested Range is ALWAYS aligned to encrypted chunk boundaries.
     */
    suspend fun downloadBlindAsset(session: VaultCryptoSession, assetId: String, mediaWireId: String, manifest: AssetManifest): Boolean =
        withContext(Dispatchers.IO) {
            if (mediaStore.hasMedia(assetId)) return@withContext true
            // cipher boundaries from the manifest: overhead + min(chunkSize, rest of plain)
            val sizes = ArrayList<Int>(manifest.chunks)
            var remaining = manifest.totalPlainSize
            for (i in 0 until manifest.chunks) {
                val plain = minOf(manifest.chunkSize.toLong(), remaining).toInt()
                remaining -= plain
                sizes.add(ENVELOPE_OVERHEAD_BYTES + plain)
            }
            val boundaries = ArrayList<Long>(sizes.size + 1)
            var acc = 0L
            for (s in sizes) { boundaries.add(acc); acc += s }
            boundaries.add(acc)
            val encodedWireId = java.net.URLEncoder.encode(mediaWireId, "UTF-8").replace("+", "%20")
            val url = URL("$serverUrl/v1/assets/$encodedWireId/content")

            val plaintext = ByteArray(manifest.totalPlainSize.toInt())
            var filled = 0
            for (i in 0 until manifest.chunks) {
                val start = boundaries[i]
                val endExclusive = boundaries[i + 1] - 1 // Range header es inclusivo
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 10000
                    conn.readTimeout = 60000
                    conn.setRequestProperty("Authorization", "Bearer $bearerToken")
                    conn.setRequestProperty("Range", "bytes=$start-$endExclusive")
                    val code = conn.responseCode
                    val bytes: ByteArray
                    when (code) {
                        206 -> {
                            bytes = conn.inputStream.use { it.readBytes() }
                            if (bytes.size.toLong() != boundaries[i + 1] - start) {
                                Log.w(TAG, "Range mismatch on chunk $i of $mediaWireId")
                                return@withContext false
                            }
                        }
                        200 -> {
                            // server ignored Range: whole stream, split locally
                            val all = conn.inputStream.use { it.readBytes() }
                            if (all.size.toLong() != boundaries.last()) return@withContext false
                            val off = boundaries[i]
                            bytes = all.copyOfRange(off.toInt(), (off + sizes[i]).toInt())
                        }
                        else -> {
                            Log.d(TAG, "Asset $mediaWireId chunk $i not found (HTTP $code)")
                            return@withContext false
                        }
                    }
                    val plain = session.openChunk(mediaWireId, i, bytes)
                        ?: return@withContext false.also { Log.w(TAG, "Chunk $i of $mediaWireId failed auth") }
                    plain.copyInto(plaintext, filled)
                    filled += plain.size
                } finally {
                    conn.disconnect()
                }
            }
            if (filled == plaintext.size) {
                val mime = mediaWireForAsset(assetId)?.let { m -> assetMimeOf(m) } ?: "application/octet-stream"
                mediaStore.saveMedia(assetId, plaintext, mime)
                Log.i(TAG, "Downloaded ${manifest.chunks} chunk(s) of $mediaWireId for $assetId")
                true
            } else false
        }

    private suspend fun mediaWireForAsset(assetId: String): JSONObject? =
        repository.exportObjectForServer("media_$assetId")

    private fun assetMimeOf(mediaWire: JSONObject): String =
        mediaWire.optJSONObject("properties")?.optString("mime", "")?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

    /**
     * Connects to the canonical SSE stream `/v1/sync/stream` with the usual backoff.
     * Any op from ANOTHER device schedules a debounced pull.
     */
    fun startLiveStream() {
        liveStreamJob?.cancel()
        liveStreamJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                var reader: BufferedReader? = null
                var conn: HttpURLConnection? = null
                try {
                    val t = transport()
                    conn = t.openStream()
                    Log.i(TAG, "Live /v1 stream connected to $serverUrl after $attempt retries")
                    attempt = 0
                    // Events sent while the stream was down are lost: catch up .
                    schedulePullDebounced(300L)
                    reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    while (isActive) {
                        val event = t.readStreamEvent(reader) ?: break
                        val kind = event.optString("event", "op")
                        if (kind == "ping") continue
                        val fromDevice = event.optString("device_id", event.optString("sender_id"))
                        if (fromDevice.isBlank() || fromDevice != deviceId) {
                            schedulePullDebounced(1500L)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Live stream dropped: ${e.message}")
                } finally {
                    runCatching { reader?.close() }
                    conn?.disconnect()
                }
                val wait = liveBackoffMs(attempt++, Random.nextDouble())
                Log.d(TAG, "Live stream reconnecting in ${wait}ms (retry $attempt)")
                delay(wait)
            }
        }
    }

    /**
     * Network is back : sync now and reconnect the live stream instead of waiting for the
     * backoff or the next event. Called from the default-network callback.
     */
    fun onNetworkAvailable() {
        schedulePullDebounced(500L)
        startLiveStream()
    }

    /**
     * Schedules a debounced pull sync in response to real-time events.
     */
    fun schedulePullDebounced(debounceMs: Long = 1500L) {
        pullDebounceJob?.cancel()
        pullDebounceJob = scope.launch {
            delay(debounceMs)
            syncNow()
        }
    }

    /**
     * Schedules a debounced push sync. Multiple rapid edits coalesce into a single sync.
     * While an object is actively being typed into, pushes are held back to preserve peak editor performance.
     */
    fun schedulePush(debounceMs: Long = 2000L) {
        val effectiveDebounce = if (repository.activeEditingObjectId != null) {
            maxOf(debounceMs, 6000L)
        } else {
            debounceMs
        }
        pushDebounceJob?.cancel()
        pushDebounceJob = scope.launch {
            delay(effectiveDebounce)
            syncNow()
        }
    }

    enum class AuthCheck { OK, BAD_TOKEN, UNREACHABLE }

    /**
     * Authenticated handshake probe (spec/sync.md): checks the token AND the server protocol.
     * 426/404/etc → UNREACHABLE: the server does not speak our protocol (never sync blindly).
     */
    suspend fun testConnection(url: String, token: String): AuthCheck = withContext(Dispatchers.IO) {
        try {
            val t = BlindTransport(url.trim().trimEnd('/'), token.trim(), "probe")
            t.status()
            AuthCheck.OK
        } catch (e: BlindTransport.SyncProtoError) {
            when (e.httpStatus) {
                401, 403 -> AuthCheck.BAD_TOKEN
                else -> AuthCheck.UNREACHABLE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connection test failed: ${e.message}")
            AuthCheck.UNREACHABLE
        }
    }

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val endpoint = URL("$serverUrl/health")
            val conn = endpoint.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"

            val code = conn.responseCode
            val connected = code == 200
            conn.disconnect()

            _syncState.value = _syncState.value.copy(isConnected = connected)
            connected
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed: ${e.message}")
            _syncState.value = _syncState.value.copy(isConnected = false)
            false
        }
    }

    /**
     * Exchanges the pasted master token for this device's own token . 200 → store the
     * device token and forget the master; 403 → it already is a device token; other → older server.
     */
    private fun ensureDeviceToken() {
        val url = serverUrl
        if (bearerToken.isBlank() || prefs.getString(KEY_ENROLLED_FOR, null) == url) return
        try {
            val conn = URL("$url/v1/devices/enroll").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("device_id", deviceId).put("name", android.os.Build.MODEL ?: "Android")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            when (conn.responseCode) {
                200 -> {
                    val token = JSONObject(conn.inputStream.bufferedReader().readText()).optString("token")
                    if (token.isNotBlank()) {
                        prefs.edit().putString(KEY_BEARER_TOKEN, token).putString(KEY_ENROLLED_FOR, url).commit()
                        Log.i(TAG, "Device enrolled; master token replaced by a device token")
                    }
                }
                403 -> prefs.edit().putString(KEY_ENROLLED_FOR, url).apply() // already a device token
                else -> Unit
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Device enrollment skipped: ${e.message}")
        }
    }

    /**
     * One full sync round:
     *  1. vault crypto session (no VK -> visible "no vault" state, nothing travels);
     *  2. handshake /v1/sync/status (other protocol -> visible error, nothing travels);
     *  3. seal pending objects + dirty types into outbox_ops (idempotent op_id);
     *  4. upload pending assets AS ENCRYPTED CHUNKS (before pushing their asset_meta);
     *  5. push batches of ≤ 500 from the outbox (success: delete + markSynced with sealHlc);
     *  6. incremental pull with a persistent cursor: decrypt -> merge(local, remote) ->
     *     apply ONCE -> persist the cursor per op (object being edited: do NOT apply or advance).
     */
    suspend fun syncNow(): Boolean = withContext(Dispatchers.IO) {
        if (!syncMutex.tryLock()) {
            // A sync is running: run once more when it ends, so edits saved meanwhile are pushed.
            resyncRequested = true
            return@withContext false
        }
        resyncRequested = false
        if (serverUrl.isBlank()) { syncMutex.unlock(); return@withContext false } // local-only
        ensureDeviceToken()

        // 1. No vault key, nothing to sync.
        val session = repository.vaultSession()
        if (session == null) {
            // A stored key that cannot be unwrapped is NOT "no vault": say so, instead of sending
            // the user to onboarding when the vault is there (e.g. the Keystore key was reset).
            val stored = repository.vaultKeyStore.hasVaultKey()
            _syncState.value = _syncState.value.copy(
                isSyncing = false, vaultReady = stored, pendingUploads = 0,
                lastError = if (stored) "vault key cannot be opened on this device" else null,
                lastErrorCode = if (stored) "vault_unreadable" else null,
            )
            syncMutex.unlock()
            return@withContext false
        }
        _syncState.value = _syncState.value.copy(isSyncing = true, vaultReady = true, lastError = null, lastErrorCode = null)
        Log.i(TAG, "Starting /v1 sync with $serverUrl for device $deviceId (epoch ${session.vaultKey.keyEpoch})")

        try {
            val t = transport()

            // 2. Protocol handshake (spec §16.4): never sync blindly.
            val status = try {
                t.status()
            } catch (e: BlindTransport.SyncProtoError) {
                failState(e.code, e.detail)
                return@withContext false
            }
            if (status.protocolVersion != BlindTransport.PROTOCOL_VERSION) {
                failState(
                    "protocol_mismatch",
                    "server protocol=${status.protocolVersion}, client=${BlindTransport.PROTOCOL_VERSION}",
                )
                _syncState.value = _syncState.value.copy(
                    protocolOk = false, serverProtocolVersion = status.protocolVersion,
                )
                return@withContext false
            }
            _syncState.value = _syncState.value.copy(
                protocolOk = true, serverProtocolVersion = status.protocolVersion, isConnected = true,
            )

            // The server's log is behind our cursor: it was reset or regenerated. Restart from 0
            // and resend everything, or this device would never converge with the new log.
            val knownVault = prefs.getString(KEY_SERVER_VAULT, null)
            if (SyncPolicy.needsFullResend(knownVault, status.vault, repository.vaultLastSeq(), status.headSeq)) {
                Log.w(TAG, "Server ${status.vault} (known: $knownVault) head ${status.headSeq}, cursor ${repository.vaultLastSeq()}: resetting and resending everything")
                repository.setVaultLastSeq(0L)
                repository.markEverythingForResend()
                prefs.edit().putString(KEY_SERVER_VAULT, status.vault).apply()
            }

            // 3. Seal pending objects + dirty types into the persistent outbox.
            sealPendingToOutbox(session)
            val pendingTypes = repository.takeDirtyTypes()
            for (type in pendingTypes) {
                val wire = SyncContractCodec.typeToWireJson(type)
                val wireId = session.wireId(type.id)
                val blob = session.sealOp("type", wireId, wire.toString().toByteArray(Charsets.UTF_8))
                repository.upsertOutboxOp(
                    OutboxOpEntity(
                        opId = newOpId(),
                        kind = "type",
                        realId = type.id,
                        wireId = wireId,
                        keyEpoch = session.vaultKey.keyEpoch,
                        blob = Base64.getEncoder().encodeToString(blob),
                        createdAt = System.currentTimeMillis(),
                        attempts = 0,
                        lastError = null,
                        sealHlc = "",
                    )
                )
            }

            // 4. Pending assets: upload encrypted chunks BEFORE pushing their asset_meta.
            for (op in repository.outboxOps()) {
                if (op.kind != "asset_meta") continue
                uploadBlindAssetChunks(session, op.realId.removePrefix("media_"), op.wireId)
            }

            // 5. Push in batches of MAX_OPS from the outbox (oldest first).
            var pushedCount = 0
            pushLoop@ while (true) {
                val rows = repository.outboxOps().sortedBy { it.createdAt }
                if (rows.isEmpty()) break
                val batch = rows.take(BlindTransport.MAX_OPS_PER_PUSH)
                val sealed = batch.mapNotNull { row ->
                    runCatching { BlindTransport.Op(row.opId, row.kind, row.wireId, row.keyEpoch, row.blob) }
                        .getOrNull()
                }
                if (sealed.isEmpty()) break
                val typesInBatch = pendingTypes.filter { ty -> batch.any { it.kind == "type" && it.realId == ty.id } }
                try {
                    val resp = t.push(sealed)
                    for (r in resp.results) {
                        val row = batch.firstOrNull { it.opId == r.opId } ?: continue
                        repository.deleteOutboxOps(listOf(row.opId))
                        if (row.kind != "type") repository.markSynced(row.realId, row.sealHlc.takeIf { it.isNotBlank() })
                    }
                    pushedCount += resp.results.size
                    Log.i(TAG, "Push batch: ${resp.results.size}/${batch.size} applied, headSeq ${resp.headSeq}")
                    if (resp.results.size < batch.size) {
                        // ops not applied (e.g. identical dedup): retry with backoff next round
                        for (row in batch) {
                            if (resp.results.none { it.opId == row.opId }) repository.markOutboxOpFailed(row.opId, "not applied")
                        }
                        break@pushLoop
                    }
                } catch (e: BlindTransport.SyncProtoError) {
                    for (row in batch) repository.markOutboxOpFailed(row.opId, "${e.code}: ${e.detail}")
                    repository.restoreDirtyTypes(typesInBatch)
                    when (e.code) {
                        "rate_limited" -> Log.w(TAG, "Push rate limited; backoff ${e.retryAfter}s")
                        else -> Log.w(TAG, "Push failed: ${e.code}: ${e.detail}")
                    }
                    failState(e.code, e.detail)
                    break@pushLoop
                } catch (e: Exception) {
                    repository.restoreDirtyTypes(typesInBatch)
                    Log.w(TAG, "Push error: ${e.message}")
                    failState("network", e.message ?: "network error")
                    break@pushLoop
                }
            }

            // 6. Pull incremental: cursor persistente del vault.
            var pulledCount = 0
            var since = repository.vaultLastSeq()
            if (SyncPolicy.shouldStartFromBootstrap(since, status.bootstrapSeq, status.compactFloor)) {
                consumeBootstrap(t, session)?.let { since = it }
            }
            var lastHead = status.headSeq
            var pages = 0
            pullLoop@ while (pages++ < 50) {
                val resp = try {
                    t.pull(since)
                } catch (e: BlindTransport.SyncProtoError) {
                    if (e.code == "oplog_compacted") {
                        // Behind the compaction floor: restart from the bootstrap snapshot, then keep pulling.
                        val resumed = consumeBootstrap(t, session)
                        if (resumed != null && resumed > since) { since = resumed; continue@pullLoop }
                        failState("oplog_compacted", "sync from $since unavailable and no usable bootstrap")
                    } else {
                        failState(e.code, e.detail)
                    }
                    break@pullLoop
                } catch (e: Exception) {
                    failState("network", e.message ?: "network error")
                    break@pullLoop
                }
                lastHead = resp.headSeq
                for (op in resp.ops) {
                    if (!applyPulledOp(session, op)) {
                        // Object being edited: do NOT apply or advance the cursor; it syncs again later.
                        Log.i(TAG, "Pull paused at seq ${op.seq}: object actively being edited")
                        break@pullLoop
                    }
                    since = maxOf(since, op.seq)
                    repository.setVaultLastSeq(since)
                    pulledCount++
                }
                if (!resp.hasMore) break@pullLoop
            }

            produceBootstrapIfDue(t, session, since, lastHead, status.bootstrapSeq)
            val pending = repository.outboxCount()
            if (pending == 0) {
                val purged = repository.purgeExpiredTrash()
                if (purged > 0) Log.i(TAG, "Trash retention: purged $purged objects older than 30 days")
            }
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                isConnected = true,
                lastSyncTime = System.currentTimeMillis(),
                lastPushedCount = pushedCount,
                lastPulledCount = pulledCount,
                pendingUploads = pending,
                lastError = null,
                lastErrorCode = null,
            )
            Log.i(TAG, "/v1 sync done: pushed $pushedCount, pulled $pulledCount, pending $pending, cursor $since")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}", e)
            failState("internal", e.message ?: "Unknown sync error")
            false
        } finally {
            syncMutex.unlock()
            if (resyncRequested) schedulePush(500L)
        }
    }

    /**
     * Seals every pending object into outbox_ops (:obj, media_* → :asset_meta with the asset
     * manifest in props). New op_id per sealing; REPLACE by (kind, real_id) keeps ONLY the
     * latest state of each object.
     */
    /** spec/sync.md: op_id = 32 lowercase hex chars. */
    private fun newOpId(): String = UUID.randomUUID().toString().replace("-", "")
    private suspend fun sealPendingToOutbox(session: VaultCryptoSession) {
        val pendingObjects = repository.getAllObjectsSnapshot()
            .filter { it.syncStatus != "synced" }
        for (obj in pendingObjects) {
            val kind = if (obj.id.startsWith("media_")) "asset_meta" else "obj"
            val wire = repository.exportObjectForServer(obj.id) ?: continue
            if (kind == "asset_meta") enrichWithAssetManifest(session, wire, obj.id, obj.id.removePrefix("media_"))
            val wireId = session.wireId(obj.id)
            val blob = session.sealOp(kind, wireId, wire.toString().toByteArray(Charsets.UTF_8))
            repository.upsertOutboxOp(
                OutboxOpEntity(
                    opId = newOpId(),
                    kind = kind,
                    realId = obj.id,
                    wireId = wireId,
                    keyEpoch = session.vaultKey.keyEpoch,
                    blob = Base64.getEncoder().encodeToString(blob),
                    createdAt = System.currentTimeMillis(),
                    attempts = 0,
                    lastError = null,
                    sealHlc = obj.hlc,
                )
            )
        }
    }

    /** Adds the asset's encryptable manifest to the wire props of the media_* object. */
    private fun enrichWithAssetManifest(session: VaultCryptoSession, wire: JSONObject, mediaObjectId: String, assetId: String) {
        val size = mediaStore.readMediaAny(assetId)?.size?.toLong() ?: return // sin bytes locales no declaramos chunks
        val props = wire.optJSONObject("properties") ?: JSONObject().also { wire.put("properties", it) }
        if (!props.has("asset_wire_id")) {
            props.put("asset_wire_id", session.wireId(mediaObjectId))
            props.put("chunks", ceil(size.toDouble() / ASSET_PLAIN_CHUNK).toInt().coerceAtLeast(1))
            props.put("chunk_size", ASSET_PLAIN_CHUNK)
            props.put("total_plain_size", size)
        }
    }

    /** The ops this client understands. New kinds are added here, never in the pull loop. */
    /** Derived artifacts (thumbnails, PDF text…) the node produced, one file per blind id. */
    val derivedSink: DerivedSink by lazy { FileDerivedSink(java.io.File(context.filesDir, "derived")) }

    private val opRegistry: RemoteOpRegistry by lazy { buildOpRegistry() }

    private fun buildOpRegistry(): RemoteOpRegistry {
        val objects = ObjectOpHandler(repository) { realId, wireId, payload ->
            val manifest = assetManifestOf(payload, wireId)
            val assetId = realId.removePrefix("media_")
            val session = currentSession
            if (manifest != null && assetId.isNotBlank() && session != null) {
                // Objects of a bootstrap container carry no op wire_id: derive the blind id.
                val mediaWireId = wireId.ifBlank { session.wireId(realId) }
                scope.launch(Dispatchers.IO) { downloadBlindAsset(session, assetId, mediaWireId, manifest) }
            }
        }
        return RemoteOpRegistry(
            listOf(
                objects,
                TypeOpHandler(repository),
                SnapshotOpHandler(repository, objects),
                DerivedOpHandler(derivedSink),
                // This client is not the trust node: requests are seen and skipped (a future trusted node wires a sink).
                NodeRequestOpHandler(null),
            ),
        )
    }

    @Volatile private var currentSession: VaultCryptoSession? = null

    /**
     * Applies one op of the pull through the registry. Returns false to PAUSE the pull without
     * advancing the cursor. Our own ops are already applied locally and are skipped.
     */
    private suspend fun applyPulledOp(session: VaultCryptoSession, op: BlindTransport.LoggedOp): Boolean {
        if (op.deviceId == deviceId) return true
        currentSession = session
        val blob = try {
            Base64.getDecoder().decode(op.blobB64)
        } catch (e: Exception) {
            Log.w(TAG, "Bad base64 blob at seq ${op.seq}; skipping")
            return true
        }
        return pipelineFor(session).apply(RemoteOp(op.seq, op.kind, op.wireId, op.deviceId, blob)) != OpResult.PAUSE
    }

    private fun pipelineFor(session: VaultCryptoSession) = RemoteOpPipeline(opRegistry, { kind, wireId, bytes ->
        session.openOp(kind, wireId, bytes)?.payload
    }, { Log.w(TAG, it) })

    /**
     * Starts this device from the server's bootstrap snapshot (new device, or behind the compaction
     * floor) instead of replaying the log. Returns the seq to resume the pull from, or null.
     */
    private suspend fun consumeBootstrap(t: BlindTransport, session: VaultCryptoSession): Long? {
        val boot = t.bootstrap() ?: return null
        val blob = Base64.getDecoder().decode(boot.blobB64)
        val op = RemoteOp(boot.seq, "snapshot", session.wireId(BootstrapSnapshot.BLIND_INPUT), "", blob)
        if (pipelineFor(session).apply(op) == OpResult.PAUSE) return null
        repository.setVaultLastSeq(boot.coveredSeq)
        Log.i(TAG, "Started from bootstrap snapshot: covered_seq ${boot.coveredSeq}")
        return boot.coveredSeq
    }

    /** Uploads a bootstrap snapshot when this device holds the converged state and the log has grown. */
    private suspend fun produceBootstrapIfDue(
        t: BlindTransport, session: VaultCryptoSession, cursor: Long, head: Long, bootstrapSeq: Long?,
    ) {
        if (!SyncPolicy.shouldProduceBootstrap(cursor, head, bootstrapSeq, repository.outboxCount())) return
        try {
            val objects = repository.getAllObjectsSnapshot().mapNotNull { repository.exportObjectForServer(it.id) }
            val types = repository.getAllTypes().first().map { SyncContractCodec.typeToWireJson(it) }
            val payload = BootstrapSnapshot.build(session.vaultKey.keyEpoch, head, objects, types)
            val wireId = session.wireId(BootstrapSnapshot.BLIND_INPUT)
            val blob = session.sealOp("snapshot", wireId, payload.toString().toByteArray(Charsets.UTF_8))
            val opId = newOpId()
            t.push(listOf(BlindTransport.Op(opId, "snapshot", wireId, session.vaultKey.keyEpoch, Base64.getEncoder().encodeToString(blob))))
            t.promoteBootstrap(opId, head)
            Log.i(TAG, "Uploaded bootstrap snapshot covering seq $head (${objects.size} objects)")
        } catch (e: Exception) {
            Log.w(TAG, "Bootstrap upload skipped: ${e.message}")
        }
    }
}
