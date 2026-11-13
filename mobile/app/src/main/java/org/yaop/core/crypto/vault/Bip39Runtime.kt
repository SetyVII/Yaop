package org.yaop.core.crypto.vault

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Loads the official BIP39 wordlist from the asset `bip39_english.txt` (a byte-exact copy of
 * `spec/fixtures/crypto/bip39_english.txt`). Onboarding uses it to validate and produce the
 * recovery phrase at runtime.
 */
object Bip39Runtime {

    const val ASSET_PATH = "bip39_english.txt"

    /** Reads a BIP39 wordlist (2048 ASCII lines, LF or CRLF) from any stream. */
    fun fromStream(input: InputStream): Bip39 {
        val words = BufferedReader(InputStreamReader(input, Charsets.US_ASCII)).use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        return Bip39.fromWordlist(words)
    }

    /** Official wordlist bundled in the APK (assets/bip39_english.txt). */
    fun fromAssets(context: Context): Bip39 =
        fromStream(context.assets.open(ASSET_PATH))
}
