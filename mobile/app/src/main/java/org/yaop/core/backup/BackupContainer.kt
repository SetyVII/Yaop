package org.yaop.core.backup

import org.yaop.core.crypto.vault.CryptoDomains
import org.yaop.core.crypto.vault.Envelope
import org.yaop.core.crypto.vault.VaultKey
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * `.yaop` backup container (spec/crypto.md §Backup). Streaming on both sides so a large
 * vault with media never has to sit in memory. Port of server/internal/simclient/backup.go.
 */
object BackupContainer {

    const val MAGIC = "YBK1"
    const val ASSET_CHUNK = 4 shl 20

    const val KIND_OBJ = 1
    const val KIND_TYPE = 2
    const val KIND_ASSET = 3
    const val KIND_END = 0xFF

    class Record(
        val kind: Int,
        val id: String,
        val n: Long = 0,
        val total: Long = 0,
        val data: ByteArray = ByteArray(0),
    )

    class BackupException(message: String) : Exception(message)

    private fun wireId(i: Long) = "backup/$i"

    private fun encode(r: Record): ByteArray {
        val id = r.id.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(11 + id.size + r.data.size)
        buf.put(r.kind.toByte()).putShort(id.size.toShort()).put(id)
        buf.putInt(r.n.toInt()).putInt(r.total.toInt()).put(r.data)
        return buf.array()
    }

    private fun decode(p: ByteArray): Record {
        if (p.size < 11) throw BackupException("short record")
        val buf = ByteBuffer.wrap(p)
        val kind = buf.get().toInt() and 0xFF
        val idLen = buf.short.toInt() and 0xFFFF
        if (p.size < 3 + idLen + 8) throw BackupException("short record id")
        val id = ByteArray(idLen).also { buf.get(it) }
        val n = buf.int.toLong() and 0xFFFFFFFFL
        val total = buf.int.toLong() and 0xFFFFFFFFL
        val data = ByteArray(buf.remaining()).also { buf.get(it) }
        return Record(kind, String(id, Charsets.UTF_8), n, total, data)
    }

    class Writer(
        private val out: OutputStream,
        private val vk: VaultKey,
        private val compress: Boolean = true,
        private val nonce: ((Int) -> ByteArray?)? = null,
    ) {
        private val key = vk.subkey(CryptoDomains.SNAPSHOTS)
        private var index = 0
        private var closed = false

        init { out.write(MAGIC.toByteArray(Charsets.US_ASCII)) }

        fun write(r: Record) {
            check(!closed) { "backup already finished" }
            put(r)
        }

        private fun put(r: Record) {
            val comp = compress && (r.kind == KIND_OBJ || r.kind == KIND_TYPE)
            val env = Envelope.seal(
                key, vk.keyEpoch, CryptoDomains.SNAPSHOTS, wireId(index.toLong()),
                encode(r), comp, nonce?.invoke(index),
            )
            out.write(ByteBuffer.allocate(4).putInt(env.size).array())
            out.write(env)
            index++
        }

        /** Writes the END record; the file is unreadable without it (truncation guard). */
        fun finish() {
            check(!closed) { "backup already finished" }
            val count = index.toLong()
            put(Record(KIND_END, "", n = count))
            closed = true
            out.flush()
        }
    }

    /**
     * Reads and authenticates every record, calling [onRecord] for each non-END one in order.
     * Returns the key_epoch. Throws [BackupException] on wrong key, tampering or truncation.
     */
    fun read(input: InputStream, vk: VaultKey, onRecord: (Record) -> Unit): Long {
        val din = DataInputStream(input)
        val magic = ByteArray(4)
        try { din.readFully(magic) } catch (e: EOFException) { throw BackupException("bad magic") }
        if (String(magic, Charsets.US_ASCII) != MAGIC) throw BackupException("bad magic")
        val key = vk.subkey(CryptoDomains.SNAPSHOTS)
        var count = 0L
        var epoch = 0L
        while (true) {
            val len = try { din.readInt() } catch (e: EOFException) { throw BackupException("truncated (no END record)") }
            if (len < Envelope.HEADER_LEN || len > (ASSET_CHUNK + (1 shl 20))) throw BackupException("bad record length")
            val env = ByteArray(len)
            try { din.readFully(env) } catch (e: EOFException) { throw BackupException("truncated record") }
            val opened = Envelope.open(key, env, CryptoDomains.SNAPSHOTS, wireId(count))
                ?: throw BackupException("record $count does not authenticate (wrong phrase, tampered or reordered)")
            epoch = opened.keyEpoch
            val r = decode(opened.payload)
            if (r.kind == KIND_END) {
                if (r.n != count) throw BackupException("END count mismatch")
                if (input.read() != -1) throw BackupException("trailing bytes after END")
                return epoch
            }
            onRecord(r)
            count++
        }
    }
}
