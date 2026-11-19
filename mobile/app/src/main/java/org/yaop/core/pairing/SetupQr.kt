package org.yaop.core.pairing

import org.json.JSONObject

/**
 * First-device setup QR (spec/crypto.md §First device): the server operator shows it so this
 * device connects without typing the master token. Strict: version 1, exactly these keys,
 * non-empty URL and token, 64 lowercase hex for `tls_fp` ([TlsFingerprint.NONE] for http).
 */
data class SetupQr(val serverUrl: String, val tlsFp: String, val token: String) {
    companion object {
        private val KEYS = setOf("v", "server_url", "tls_fp", "token")
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun decode(raw: String): SetupQr? = try {
            val o = JSONObject(raw.trim())
            val keys = o.keys().asSequence().toSet()
            val url = o.optString("server_url")
            val fp = o.optString("tls_fp")
            val token = o.optString("token")
            if (keys != KEYS || o.optInt("v", -1) != 1 || o.opt("v") !is Int) null
            else if (url.isBlank() || token.isBlank() || !HEX64.matches(fp)) null
            else SetupQr(url.trim().trimEnd('/'), fp, token)
        } catch (e: Exception) {
            null
        }
    }
}
