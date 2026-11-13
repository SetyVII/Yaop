package org.yaop.core.crypto.vault

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

/**
 * AEAD XChaCha20-Poly1305 (spec/crypto.md §AEAD, draft-irtf-cfrg-xchacha-03):
 *
 * `subkey = HChaCha20(key, nonce[0:16])`, then ChaCha20-Poly1305 (RFC 8439) with
 * `key = subkey` and `nonce = 0x00000000 || nonce[16:24]`.
 *
 * The nonce is 24 RANDOM bytes per sealing (no counter: 192 bits make collisions
 * negligible and a reused nonce would break Poly1305). The AAD binds the exact blob
 * identity ("<domain>/<wire_id>", chunk form for assets). Byte-exact against the official
 * draft vectors in `spec/fixtures/crypto/aead_xchacha20poly1305.json`; the Go reference is
 * `server/internal/simclient/aead.go`.
 */
object Aead {

    const val NONCE_BYTES = 24
    const val KEY_BYTES = 32
    const val TAG_BYTES = 16

    /** A seal result: the fresh random nonce plus ciphertext||tag. */
    data class Sealed(val nonce: ByteArray, val ciphertext: ByteArray)

    /** Encrypts [plaintext] under [key], binding [aad]. Fresh random 24-byte nonce. */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0), random: SecureRandom = SecureRandom()): Sealed {
        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)
        return Sealed(nonce, sealWithNonce(key, nonce, plaintext, aad))
    }

    /** Deterministic sealing with a caller-chosen nonce (only for the fixture vectors). */
    fun sealWithNonce(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == KEY_BYTES) { "aead key must be $KEY_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "xchacha nonce must be $NONCE_BYTES bytes" }
        val subkey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val innerNonce = ByteArray(4) + nonce.copyOfRange(16, NONCE_BYTES)
        return chacha20Poly1305Seal(subkey, innerNonce, plaintext, aad)
    }

    /** Authenticated decryption; null when key/AAD/nonce or ciphertext bytes do not match. */
    fun open(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (key.size != KEY_BYTES || nonce.size != NONCE_BYTES || ciphertextWithTag.size < TAG_BYTES) return null
        val subkey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val innerNonce = ByteArray(4) + nonce.copyOfRange(16, NONCE_BYTES)
        return chacha20Poly1305Open(subkey, innerNonce, ciphertextWithTag, aad)
    }

    // -----------------------------------------------------------------
    // RFC 8439 ChaCha20-Poly1305 over BouncyCastle's low-level engine.
    // No provider registration: the classpath artifact is used directly,
    // the Android platform's repackaged provider is never touched.
    // -----------------------------------------------------------------

    private fun chacha20Poly1305Seal(key: ByteArray, nonce12: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val aead = ChaCha20Poly1305()
        aead.init(true, AEADParameters(KeyParameter(key), 128, nonce12, aad))
        val out = ByteArray(aead.getOutputSize(plaintext.size))
        var n = aead.processBytes(plaintext, 0, plaintext.size, out, 0)
        n += aead.doFinal(out, n)
        return out.copyOf(n)
    }

    private fun chacha20Poly1305Open(key: ByteArray, nonce12: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray? = try {
        val aead = ChaCha20Poly1305()
        aead.init(false, AEADParameters(KeyParameter(key), 128, nonce12, aad))
        val out = ByteArray(aead.getOutputSize(ct.size))
        var n = aead.processBytes(ct, 0, ct.size, out, 0)
        n += aead.doFinal(out, n)
        out.copyOf(n)
    } catch (e: InvalidCipherTextException) {
        null
    }

    // -----------------------------------------------------------------
    // HChaCha20 (draft-irtf-cfrg-xchacha-03 §2.2): the key-derivation half
    // of XChaCha20. Implemented by hand so the construction is exactly the
    // draft's (BouncyCastle ships no public HChaCha20 API); the official
    // §2.2.1 vector in the fixtures pins the output.
    // -----------------------------------------------------------------

    private val SIGMA = intArrayOf(0x61707865.toInt(), 0x3320646e.toInt(), 0x79622d32.toInt(), 0x6b206574.toInt())

    fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == KEY_BYTES && nonce16.size == 16) { "hchacha20: bad key/nonce size" }
        val st = IntArray(16)
        for (i in 0 until 4) st[i] = SIGMA[i]
        for (i in 0 until 8) st[4 + i] = leInt(key, i * 4)
        for (i in 0 until 4) st[12 + i] = leInt(nonce16, i * 4)
        val x = st.copyOf()
        for (round in 0 until 10) {
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 1, 5, 9, 13)
            quarterRound(x, 2, 6, 10, 14)
            quarterRound(x, 3, 7, 11, 15)
            quarterRound(x, 0, 5, 10, 15)
            quarterRound(x, 1, 6, 11, 12)
            quarterRound(x, 2, 7, 8, 13)
            quarterRound(x, 3, 4, 9, 14)
        }
        // Output without re-adding the initial state (x/crypto chacha20.HChaCha20): the
        // official draft §2.2.1 vector pins this exactly; the block function's feed-forward
        // belongs to the keystream, not to the KDF half.
        val out = ByteArray(32)
        for (i in 0 until 4) putLeInt(out, i * 4, x[i])
        for (i in 0 until 4) putLeInt(out, 16 + i * 4, x[12 + i])
        return out
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun putLeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
