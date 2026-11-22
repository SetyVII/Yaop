package org.yaop.core.pairing

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP side of the pairing relay (`/v1/pairing`, spec/crypto.md §Pairing). The server only
 * relays opaque bytes: public keys and the sealed VK envelope. The unguessable `pairing_id` of
 * the QR is the capability; only the holder's transfer carries its token, to let the new device
 * get one of its own (step 7).
 */
class PairingRelay(baseUrl: String) {

    private val base = baseUrl.trim().trimEnd('/')

    /** QR holder announces its ephemeral key. */
    fun create(pairingId: String, ephPubB64: String): Boolean =
        post("/v1/pairing", JSONObject().put("pairing_id", pairingId).put("eph_pub", ephPubB64).toString().toByteArray()).first == 201

    /** Joining device answers with its own ephemeral key: 202 ok, 409 another device already used the code. */
    fun join(pairingId: String, bPubB64: String): Int =
        post("/v1/pairing/$pairingId/join", JSONObject().put("b_pub", bPubB64).toString().toByteArray()).first

    /** QR holder polls for the joining device's key; null until it answers. */
    fun answer(pairingId: String): String? {
        val (code, body) = get("/v1/pairing/$pairingId/answer")
        if (code != 200) return null
        val v = JSONObject(String(body))
        return if (v.isNull("b_pub")) null else v.getString("b_pub")
    }

    /** Holder sends the sealed VK; with its own [holderToken] it also lets the guest enroll. */
    fun sendTransfer(pairingId: String, envelope: ByteArray, holderToken: String?, enrollKey: String): Boolean {
        val headers = if (holderToken.isNullOrBlank()) emptyMap()
        else mapOf("Authorization" to "Bearer $holderToken", "X-YAOP-Enroll-Key" to enrollKey)
        return request("POST", "/v1/pairing/$pairingId/transfer", envelope, "application/octet-stream", headers).first == 201
    }

    /** Joining device redeems its own server token; null when the holder offered none. */
    fun enroll(pairingId: String, deviceId: String, name: String, enrollKey: String): String? {
        val body = JSONObject().put("device_id", deviceId).put("name", name).put("enroll_key", enrollKey)
        val (code, raw) = post("/v1/pairing/$pairingId/enroll", body.toString().toByteArray())
        if (code != 200) return null
        return JSONObject(String(raw)).optString("token").takeIf { it.isNotBlank() }
    }

    /** Joining device polls for the sealed VK; null until it arrives. */
    fun fetchTransfer(pairingId: String): ByteArray? {
        val (code, body) = get("/v1/pairing/$pairingId/transfer")
        return if (code == 200) body else null
    }

    private fun post(path: String, body: ByteArray, type: String = "application/json"): Pair<Int, ByteArray> =
        request("POST", path, body, type)

    private fun get(path: String): Pair<Int, ByteArray> = request("GET", path, null, null)

    private fun request(
        method: String,
        path: String,
        body: ByteArray?,
        type: String?,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, ByteArray> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.use { it.readBytes() } ?: ByteArray(0))
        } catch (e: Exception) {
            android.util.Log.w("PairingRelay", "$method $path failed: $e")
            -1 to ByteArray(0)
        } finally {
            conn.disconnect()
        }
    }
}
