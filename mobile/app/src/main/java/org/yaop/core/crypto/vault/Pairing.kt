package org.yaop.core.crypto.vault

import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom
import java.util.Base64

/**
 * Device pairing (spec/crypto.md §Pairing): an enrolled device (A) adds a new one
 * (B) without the VK ever traveling in the clear.
 *
 * 1. A generates an ephemeral X25519 pair and shows the QR with the EXACT JSON
 *    `{"v":1,"server_url":…,"tls_fp":…,"eph_pub":…,"pairing_id":…}` (compact, key order fixed).
 * 2. B answers with its own ephemeral public key (transport: pending pair at the server).
 * 3. Both derive `shared = X25519(own_priv, peer_pub)`.
 * 4. Short code: 6 digits derived from HKDF("short-code") for the human to compare.
 * 5. A wraps the VK under `HKDF("vk-transfer")` in an envelope v1 with AAD
 *    `pairing/<pairing_id>`; B opens it and stays enrolled with that key epoch.
 *
 * The full transcript with fixed test keys is `spec/fixtures/crypto/pairing_transcript.json`;
 * Go reference `server/internal/simclient/pairing.go`.
 */
object Pairing {

    const val QR_VERSION = 1
    const val PAIRING_SALT = "yaop-pairing/1"

    /** The exact QR payload (all fields validated; strict, no unknown keys). */
    data class Qr(
        val version: Int,
        val serverUrl: String,
        val tlsFp: String,
        val ephPub: ByteArray,
        val pairingId: ByteArray,
    )

    /** Generates a fresh X25519 ephemeral private scalar (32 bytes). */
    fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray {
        val k = ByteArray(32)
        X25519.generatePrivateKey(random, k)
        return k
    }

    /** Public key of an X25519 private scalar. */
    fun publicKeyOf(priv: ByteArray): ByteArray {
        val pub = ByteArray(32)
        X25519.generatePublicKey(priv, 0, pub, 0)
        return pub
    }

    /** Raw X25519 shared secret; null on degenerate (all-zero) results. */
    fun sharedSecret(priv: ByteArray, peerPub: ByteArray): ByteArray? {
        val shared = ByteArray(32)
        val ok = X25519.calculateAgreement(priv, 0, peerPub, 0, shared, 0)
        return if (ok) shared else null
    }

    /** 6-digit confirmation code: `zero-pad-6(u32_be(HKDF("short-code")[0:4]) mod 10^6)`. */
    fun shortCode(shared: ByteArray): String {
        val bytes = Hkdf.derive(shared, PAIRING_SALT.toByteArray(Charsets.UTF_8), "short-code".toByteArray(Charsets.UTF_8), 4)
        val v = ((bytes[0].toLong() and 0xFF) shl 24) or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)
        return String.format("%06d", v % 1_000_000L)
    }

    /** 32-byte key that wraps the VK for transfer: HKDF(shared, "vk-transfer"). */
    fun vkTransferKey(shared: ByteArray): ByteArray =
        Hkdf.derive(shared, PAIRING_SALT.toByteArray(Charsets.UTF_8), "vk-transfer".toByteArray(Charsets.UTF_8), 32)

    /**
     * Key B redeems for its own server token (step 7): HKDF(shared, "enroll"), 32 bytes as
     * lowercase hex. Independent of [vkTransferKey], so the server seeing it learns no VK.
     */
    fun enrollKey(shared: ByteArray): String =
        hex(Hkdf.derive(shared, PAIRING_SALT.toByteArray(Charsets.UTF_8), "enroll".toByteArray(Charsets.UTF_8), 32))

    /** A wraps the vault key of [keyEpoch] for the new device (envelope 0x01, AAD pairing/<id>). */
    fun sealVk(shared: ByteArray, pairingId: String, vk: ByteArray, keyEpoch: Long, nonce: ByteArray? = null): ByteArray =
        Envelope.seal(
            domainKey = vkTransferKey(shared),
            keyEpoch = keyEpoch,
            domain = DOMAIN,
            wireId = pairingId,
            payload = vk,
            compress = false,
            nonce = nonce,
        )

    /** B unwraps the transferred VK; returns it with the epoch it was wrapped under. */
    fun openVk(shared: ByteArray, pairingId: String, envelope: ByteArray): OpenedVk? {
        val opened = Envelope.open(vkTransferKey(shared), envelope, DOMAIN, pairingId) ?: return null
        if (opened.version != Envelope.VERSION_RAW) return null
        if (opened.payload.size != VaultKey.KEY_BYTES) return null
        return OpenedVk(opened.payload, opened.keyEpoch)
    }

    data class OpenedVk(val vk: ByteArray, val keyEpoch: Long)

    /** Encodes the QR payload as the canonical compact JSON with the fixed key order. */
    fun encodeQr(qr: Qr): String {
        require(qr.version == QR_VERSION)
        val sb = StringBuilder(160)
        sb.append("{\"v\":").append(QR_VERSION)
        sb.append(",\"server_url\":").append(jsonString(qr.serverUrl))
        sb.append(",\"tls_fp\":").append(jsonString(qr.tlsFp))
        sb.append(",\"eph_pub\":").append(jsonString(encodePub(qr.ephPub)))
        sb.append(",\"pairing_id\":").append(jsonString(hex(qr.pairingId)))
        sb.append('}')
        return sb.toString()
    }

    /** Strict parse of the QR payload (version, sizes, hex formats all checked). */
    fun decodeQr(payload: String): Qr? {
        val obj = try {
            org.json.JSONObject(payload)
        } catch (e: Exception) {
            return null
        }
        val v = obj.optInt("v", -1)
        if (v != QR_VERSION) return null
        val url = obj.optString("server_url")
        val tlsFp = obj.optString("tls_fp")
        val pairingIdHex = obj.optString("pairing_id")
        if (url.isEmpty() || tlsFp.isEmpty() || pairingIdHex.isEmpty()) return null
        if (!isLowerHex(tlsFp) || tlsFp.length != 64) return null
        if (!isLowerHex(pairingIdHex) || pairingIdHex.length != 32) return null
        val ephPub = decodePub(obj.optString("eph_pub")) ?: return null
        return Qr(QR_VERSION, url, tlsFp, ephPub, unhex(pairingIdHex))
    }

    /** base64url without padding (the wire format of ephemeral public keys). */
    fun encodePub(pub: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(pub)

    fun decodePub(s: String): ByteArray? = try {
        val raw = Base64.getUrlDecoder().decode(s)
        if (raw.size == 32) raw else null
    } catch (e: Exception) {
        null
    }

    private const val DOMAIN = "pairing"

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun hex(bytes: ByteArray): String = buildString {
        for (b in bytes) {
            append("0123456789abcdef"[(b.toInt() shr 4) and 0xF])
            append("0123456789abcdef"[b.toInt() and 0xF])
        }
    }

    private fun unhex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun isLowerHex(s: String): Boolean =
        s.isNotEmpty() && s.all { (it in '0'..'9') || (it in 'a'..'f') }
}
