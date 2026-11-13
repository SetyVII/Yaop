package org.yaop.core.pairing

import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * `tls_fp` of the pairing QR: SHA-256 (lowercase hex) of the server certificate's SPKI DER.
 * Plain-http servers (local dev/LAN) have no certificate: the QR carries [NONE] and the guest
 * skips the check; an https server with a mismatching fingerprint aborts the pairing.
 */
object TlsFingerprint {

    val NONE: String = "0".repeat(64)

    /** Fingerprint of the server at [serverUrl]; [NONE] for http; null if unreachable. */
    fun of(serverUrl: String): String? {
        if (!serverUrl.startsWith("https://")) return NONE
        return try {
            val conn = URL(serverUrl.trimEnd('/') + "/health").openConnection() as HttpsURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.connect()
            val spki = conn.serverCertificates.first().publicKey.encoded
            conn.disconnect()
            MessageDigest.getInstance("SHA-256").digest(spki).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** True when the scanned QR's fingerprint is acceptable for this server. */
    fun matches(serverUrl: String, qrFp: String): Boolean {
        if (!serverUrl.startsWith("https://")) return true
        return of(serverUrl) == qrFp
    }
}
