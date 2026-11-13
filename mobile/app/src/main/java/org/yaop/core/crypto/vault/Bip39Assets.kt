package org.yaop.core.crypto.vault

import android.content.Context
import java.security.MessageDigest

/**
 * Loads the official English BIP39 wordlist for the runtime.
 *
 * The list ships embedded in the APK (`app/src/main/assets/bip39_english.txt`, a copy of
 * `spec/fixtures/crypto/bip39_english.txt`) — never downloaded (spec/crypto.md §BIP39).
 * The documented sha256 is verified on load; a broken or tampered asset fails loudly
 * instead of silently garbling keys.
 */
object Bip39Assets {

    const val ASSET_NAME = "bip39_english.txt"
    const val WORDLIST_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"

    private var cached: Bip39? = null

    @Synchronized
    fun load(context: Context): Bip39 {
        cached?.let { return it }
        val raw = context.assets.open(ASSET_NAME).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        require(digest == WORDLIST_SHA256) { "bip39 wordlist sha256 mismatch: $digest" }
        val words = raw.toString(Charsets.US_ASCII).trim().split('\n').map { it.trim() }
        val bip39 = Bip39.fromWordlist(words)
        cached = bip39
        return bip39
    }

    /** From raw bytes (tests and any non-asset source that already holds the exact file). */
    fun fromBytes(raw: ByteArray): Bip39 {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        require(digest == WORDLIST_SHA256) { "bip39 wordlist sha256 mismatch: $digest" }
        return Bip39.fromWordlist(raw.toString(Charsets.US_ASCII).trim().split('\n').map { it.trim() })
    }
}
