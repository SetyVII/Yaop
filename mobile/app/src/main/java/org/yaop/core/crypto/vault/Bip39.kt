package org.yaop.core.crypto.vault

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * BIP39 recovery phrase (spec/crypto.md §BIP39): 24 words / 256-bit entropy.
 *
 * The phrase **is** the Vault Key: the 32 bytes of entropy (never the PBKDF2 seed)
 * are the VK material. There is NO passphrase — the standard seed
 * `PBKDF2-HMAC-SHA512(mnemonic, salt="mnemonic", 2048 iter, 64 bytes)` exists only for
 * compatibility checks. Words must match byte-for-byte (the list is ASCII); a bad
 * checksum or an unknown word is an error, never a best effort.
 *
 * Same algorithm as the Go reference (`server/internal/simclient/bip39.go`) and the
 * desktop port; fixtures `spec/fixtures/crypto/bip39.json`.
 */
class Bip39 private constructor(val words: List<String>) {

    private val index: Map<String, Int>

    init {
        require(words.size == WORDLIST_WORDS) { "wordlist must have exactly $WORDLIST_WORDS sorted unique words" }
        val idx = HashMap<String, Int>(WORDS)
        for (i in words.indices) {
            if (i > 0 && words[i - 1] >= words[i]) {
                throw IllegalArgumentException("wordlist must have exactly $WORDS sorted unique words")
            }
            if (idx.put(words[i], i) != null) {
                throw IllegalArgumentException("wordlist must have exactly $WORDS sorted unique words")
            }
        }
        index = idx
    }

    /** Decodes a 24-word phrase into its 256-bit entropy, validating the checksum byte. */
    fun mnemonicToEntropy(phrase: String): ByteArray {
        val parts = phrase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size != WORDS) {
            throw Bip39Exception("phrase must have $WORDS words, has ${parts.size}")
        }
        val bits = BooleanArray(WORDS * 11)
        for (i in parts.indices) {
            val wordIdx = index[parts[i]] ?: throw Bip39Exception("word ${i + 1} (\"${parts[i]}\") not in the list")
            for (k in 10 downTo 0) {
                bits[i * 11 + (10 - k)] = (wordIdx shr k) and 1 == 1
            }
        }
        val entropy = ByteArray(ENTROPY_BYTES)
        for (i in 0 until ENTROPY_BYTES * 8) {
            if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (0x80 shr (i % 8))).toByte()
        }
        var checksum = 0
        for (i in 0 until 8) {
            if (bits[ENTROPY_BYTES * 8 + i]) checksum = checksum or (0x80 shr i)
        }
        val sum = MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xFF
        if (sum != checksum) {
            throw Bip39Exception(String.format("checksum mismatch: got %02x want %02x", checksum, sum))
        }
        return entropy
    }

    /** Encodes 256-bit entropy into its 24-word phrase. */
    fun entropyToMnemonic(entropy: ByteArray): String {
        if (entropy.size != ENTROPY_BYTES) {
            throw Bip39Exception("entropy must be $ENTROPY_BYTES bytes, has ${entropy.size}")
        }
        val payload = entropy + MessageDigest.getInstance("SHA-256").digest(entropy)[0]
        val sb = StringBuilder()
        for (i in 0 until WORDS) {
            var wordIdx = 0
            for (k in 0 until 11) {
                val bitPos = i * 11 + k
                if (payload[bitPos / 8].toInt() and (0x80 shr (bitPos % 8)) != 0) {
                    wordIdx = wordIdx or (1 shl (10 - k))
                }
            }
            if (i > 0) sb.append(' ')
            sb.append(words[wordIdx])
        }
        return sb.toString()
    }

    companion object {
        /** The official BIP39 English wordlist is exactly 2048 sorted unique words. */
        const val WORDLIST_WORDS = 2048
        const val WORDS = 24
        const val ENTROPY_BYTES = 32

        /** Builds the codec from the official English list (2048 lines, ASCII). */
        fun fromWordlist(words: List<String>): Bip39 = Bip39(words)

        /**
         * Standard BIP39 seed for compatibility checks only (the vault NEVER uses it as
         * key material — the VK is the entropy itself).
         */
        fun seed(phrase: String): ByteArray {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = PBEKeySpec(phrase.toCharArray(), "mnemonic".toByteArray(Charsets.UTF_8), 2048, 64 * 8)
            return factory.generateSecret(spec).encoded
        }
    }
}

/** Recovery phrase validation failure, classified for the onboarding UI. */
class Bip39Exception(reason: String) : IllegalArgumentException("bip39: $reason")
