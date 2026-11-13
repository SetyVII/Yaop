package org.yaop.core.crypto.vault

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.security.SecureRandom

/**
 * The envelope (spec/crypto.md §Envelope): every blob that leaves the device — operation,
 * object, snapshot, chunk, derived artifact, wrapped VK — travels as
 *
 * `version(1) | key_epoch(u32 BE) | nonce(24) | ciphertext+tag`
 *
 * - version `0x01`: payload stored as-is (always valid in any domain).
 * - version `0x02`: payload was zstd-compressed (production level 3) BEFORE encrypting.
 *
 * `key_epoch` travels in the clear (the server orders epochs with it) but is protected:
 * opening with the subkey of another epoch fails authentication. The AAD authenticates the
 * exact blob identity: `"<domain>/<wire_id>"`, and for asset chunks
 * `"assets/<wire_id>/chunk/<n>"` (n in decimal ASCII).
 *
 * Production rule (spec/crypto.md): JSON payloads of kinds obj|type|asset_meta|derived|
 * snapshot wrap as `0x02`; media chunks (binaries, already compressed) as `0x01`.
 * Zstd bytes are never compared across library versions — 0x02 fixtures verify roundtrip.
 */
object Envelope {

    const val VERSION_RAW: Byte = 0x01
    const val VERSION_ZSTD: Byte = 0x02
    const val ZSTD_LEVEL = 3

    const val HEADER_LEN = 1 + 4 + Aead.NONCE_BYTES

    /** Seals [payload] into a full envelope with the domain AAD `"<domain>/<wire_id>"`. */
    fun seal(
        domainKey: ByteArray,
        keyEpoch: Long,
        domain: String,
        wireId: String,
        payload: ByteArray,
        compress: Boolean,
        nonce: ByteArray? = null,
        random: SecureRandom = SecureRandom(),
    ): ByteArray = sealWithAad(
        domainKey, keyEpoch, aad(domain, wireId), payload, compress, nonce, random,
    )

    /** Seals [payload] with an explicit AAD (asset chunks use the chunk AAD form). */
    fun sealWithAad(
        domainKey: ByteArray,
        keyEpoch: Long,
        aadBytes: ByteArray,
        payload: ByteArray,
        compress: Boolean,
        nonce: ByteArray? = null,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        var version = VERSION_RAW
        var plain = payload
        if (compress) {
            plain = Zstd.compress(payload, ZSTD_LEVEL)
            version = VERSION_ZSTD
        }
        val fixed = ByteArray(Aead.NONCE_BYTES)
        if (nonce != null) {
            require(nonce.size == Aead.NONCE_BYTES) { "envelope: nonce must be ${Aead.NONCE_BYTES} bytes" }
            nonce.copyInto(fixed)
        } else {
            random.nextBytes(fixed)
        }
        val ct = Aead.sealWithNonce(domainKey, fixed, plain, aadBytes)
        val out = ByteArray(HEADER_LEN + ct.size)
        out[0] = version
        putEpoch(out, 1, keyEpoch)
        fixed.copyInto(out, 5)
        ct.copyInto(out, HEADER_LEN)
        return out
    }

    /** Opens an envelope sealed with the domain AAD: authenticates, decrypts, decompresses. */
    fun open(domainKey: ByteArray, envelope: ByteArray, domain: String, wireId: String): Opened? =
        openWithAad(domainKey, envelope, aad(domain, wireId))

    /** Opens an envelope sealed with an explicit AAD (failures → null, never a best effort). */
    fun openWithAad(domainKey: ByteArray, envelope: ByteArray, aadBytes: ByteArray): Opened? {
        if (envelope.size < HEADER_LEN + Aead.TAG_BYTES) return null
        val version = envelope[0]
        if (version != VERSION_RAW && version != VERSION_ZSTD) return null
        val keyEpoch = epochOf(envelope)
        val nonce = envelope.copyOfRange(5, 5 + Aead.NONCE_BYTES)
        val ct = envelope.copyOfRange(HEADER_LEN, envelope.size)
        val plain = Aead.open(domainKey, nonce, ct, aadBytes) ?: return null
        return if (version == VERSION_ZSTD) {
            // Streamed decompression so unknown frame sizes never need a capacity guess.
            // A tampered version byte (raw data claimed as zstd) fails the magic number here.
            val payload = try {
                ZstdInputStream(ByteArrayInputStream(plain)).use { it.readBytes() }
            } catch (e: Exception) {
                return null
            }
            Opened(payload, keyEpoch, version)
        } else {
            Opened(plain, keyEpoch, version)
        }
    }

    data class Opened(val payload: ByteArray, val keyEpoch: Long, val version: Byte)

    /** AAD of a domain blob: `"<domain>/<wire_id>"`. */
    fun aad(domain: String, wireId: String): ByteArray = "$domain/$wireId".toByteArray(Charsets.UTF_8)

    /** AAD of an asset chunk: `"assets/<wire_id>/chunk/<n>"`. */
    fun chunkAad(wireId: String, chunkIndex: Int): ByteArray =
        "${CryptoDomains.ASSETS}/$wireId/chunk/$chunkIndex".toByteArray(Charsets.UTF_8)

    fun epochOf(envelope: ByteArray): Long {
        require(envelope.size >= 5)
        var v = 0L
        for (i in 1..4) v = (v shl 8) or (envelope[i].toLong() and 0xFF)
        return v
    }

    private fun putEpoch(out: ByteArray, offset: Int, epoch: Long) {
        for (i in 0 until 4) {
            out[offset + i] = ((epoch ushr (8 * (3 - i))) and 0xFF).toByte()
        }
    }
}
