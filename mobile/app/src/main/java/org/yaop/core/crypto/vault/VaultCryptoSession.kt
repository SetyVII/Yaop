package org.yaop.core.crypto.vault

import java.util.Base64

/**
 * Vault crypto session for one VK: caches the 7 domain subkeys and seals/opens ops and
 * chunks exactly as the protocol carries them (spec/sync.md): kind → domain, JSON compressed
 * with zstd level 3 (envelope 0x02), raw binary chunks (0x01) with the chunk AAD, and blind
 * `wire_id`s for everything.
 */
class VaultCryptoSession(val vaultKey: VaultKey) {

    private val subkeys = HashMap<String, ByteArray>()

    private fun subkey(domain: String): ByteArray =
        synchronized(subkeys) { subkeys.getOrPut(domain) { vaultKey.subkey(domain) } }

    val idsKey: ByteArray get() = subkey(CryptoDomains.IDS)

    /** Blind wire_id of a real id (HMAC with the ids subkey of THIS epoch). */
    fun wireId(realId: String): String = IdBlinder.wireId(idsKey, realId)

    /** wire_id del artefacto derivado compuesto `derived/<objectId>/<kind>` (spec §23.3). */
    fun derivedWireId(objectId: String, kind: String): String = IdBlinder.derivedWireId(idsKey, objectId, kind)

    /** Crypto domain of each kind (spec/sync.md). */
    fun domainFor(kind: String): String = when (kind) {
        "obj", "type", "asset_meta" -> CryptoDomains.OBJECTS
        "derived" -> CryptoDomains.DERIVED
        "snapshot" -> CryptoDomains.SNAPSHOTS
        "node_request" -> CryptoDomains.OPS
        else -> throw IllegalArgumentException("unknown kind $kind")
    }

    /** Is this kind's payload compressed on the wire? (spec/crypto.md, envelope). */
    fun compresses(kind: String): Boolean = kind in COMPRESSED_KINDS

    /**
     * Seals an op payload (JSON): the envelope declares the vault's current key_epoch and
     * the AAD `<domain>/<wire_id>`; compressed JSON uses version 0x02.
     */
    fun sealOp(kind: String, wireId: String, payload: ByteArray, compress: Boolean = compresses(kind)): ByteArray =
        Envelope.seal(
            domainKey = subkey(domainFor(kind)),
            keyEpoch = vaultKey.keyEpoch,
            domain = domainFor(kind),
            wireId = wireId,
            payload = payload,
            compress = compress,
        )

    fun openOp(kind: String, wireId: String, envelope: ByteArray): Envelope.Opened? =
        Envelope.open(subkey(domainFor(kind)), envelope, domainFor(kind), wireId)

    /**
     * Encrypted media chunk (spec/sync.md, assets): domain `assets`, ALWAYS version 0x01
     * (media is already compressed), AAD `assets/<media_wire_id>/chunk/<n>`, random nonce.
     * Every chunk stands alone: resuming works by chunk ranges.
     */
    fun sealChunk(mediaWireId: String, chunkIndex: Int, plain: ByteArray): ByteArray =
        Envelope.sealWithAad(
            domainKey = subkey(CryptoDomains.ASSETS),
            keyEpoch = vaultKey.keyEpoch,
            aadBytes = Envelope.chunkAad(mediaWireId, chunkIndex),
            payload = plain,
            compress = false,
        )

    fun openChunk(mediaWireId: String, chunkIndex: Int, envelope: ByteArray): ByteArray? =
        Envelope.openWithAad(subkey(CryptoDomains.ASSETS), envelope, Envelope.chunkAad(mediaWireId, chunkIndex))?.payload

    /** Chunk de miniatura (mismo mecanismo, dominio `thumbs`). */
    fun sealThumbChunk(mediaWireId: String, chunkIndex: Int, plain: ByteArray): ByteArray =
        Envelope.sealWithAad(
            domainKey = subkey(CryptoDomains.THUMBS),
            keyEpoch = vaultKey.keyEpoch,
            aadBytes = Envelope.chunkAad(mediaWireId, chunkIndex),
            payload = plain,
            compress = false,
        )

    fun openThumbChunk(mediaWireId: String, chunkIndex: Int, envelope: ByteArray): ByteArray? =
        Envelope.openWithAad(subkey(CryptoDomains.THUMBS), envelope, Envelope.chunkAad(mediaWireId, chunkIndex))?.payload

    companion object {
        /** Kinds whose JSON payload is compressed with zstd level 3 before sealing. */
        private val COMPRESSED_KINDS = setOf("obj", "type", "asset_meta", "derived", "snapshot")

        /** Padded standard base64 (Go encoding/base64.StdEncoding): the op blob format. */
        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    }
}
