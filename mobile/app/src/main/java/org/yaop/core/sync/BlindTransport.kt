package org.yaop.core.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Transport for `yaop-sync/1` (spec/sync.md, blind server): EVERYTHING on the wire goes inside
 * the envelope of spec/crypto.md and ids are blind (`wire_id`) — this client never puts an id,
 * a title or a MIME type in clear on the wire.
 *
 * Semantics: idempotent `op_id` (a retry returns the original seq), ascending `seq` assigned by
 * the server, pull returns ALL later ops (no dedup), and the error schema is always
 * `{"error":{"code","message",...}}`. If the server speaks another protocol version the
 * client stops with `protocol_mismatch` (spec §16.4): it never syncs blindly.
 */
class BlindTransport(
    val baseUrl: String,
    val token: String,
    val deviceId: String,
) {

    companion object {
        const val PROTOCOL_VERSION = "1"
        const val MAX_OPS_PER_PUSH = 500
        const val PULL_LIMIT_DEFAULT = 200

        /** Kinds allowed by the contract; the server rejects the rest with 400 bad_kind. */
        val VALID_KINDS = setOf("obj", "type", "asset_meta", "derived", "snapshot", "node_request")
    }

    /** One operation as pushed (the blob is already a full envelope, in base64).
     *  [deviceIdOverride] only exists to replay the multi-device fixtures; a real client
     *  always pushes under its own device_id. */
    data class Op(
        val opId: String,
        val kind: String,
        val wireId: String,
        val keyEpoch: Long,
        val blobB64: String,
        val deviceIdOverride: String? = null,
    )

    data class PushResult(val opId: String, val seq: Long)

    data class PushResponse(val results: List<PushResult>, val headSeq: Long)

    /** One op of the log, with the `seq` the server assigned. */
    data class LoggedOp(
        val seq: Long,
        val opId: String,
        val deviceId: String,
        val kind: String,
        val wireId: String,
        val keyEpoch: Long,
        val blobB64: String,
        val receivedAt: String,
    )

    data class PullResponse(val ops: List<LoggedOp>, val headSeq: Long, val hasMore: Boolean)

    data class Status(
        val protocolVersion: String,
        val vault: String,
        val headSeq: Long,
        val compactFloor: Long,
        val bootstrapSeq: Long?,
        val deviceId: String,
    )

    data class Bootstrap(val coveredSeq: Long, val seq: Long, val blobB64: String)

    /** The exact contract error (spec/sync.md, errors); readable reason for the status chip. */
    class SyncProtoError(
        val code: String,
        val detail: String,
        val httpStatus: Int,
        val retryAfter: Long? = null,
        val compactFloor: Long? = null,
        val bootstrap: Bootstrap? = null,
        val clientProtocolVersion: String? = null,
        val serverProtocolVersion: String? = null,
    ) : Exception("$code: $detail")

    fun status(): Status {
        val (code, body) = exchangeGet("$baseUrl/v1/sync/status")
        if (code != 200) throw parseError(code, body)
        val o = JSONObject(body)
        return Status(
            protocolVersion = o.optString("protocol_version"),
            vault = o.optString("vault"),
            headSeq = o.optLong("head_seq", 0L),
            compactFloor = o.optLong("compact_floor", 0L),
            bootstrapSeq = if (o.isNull("bootstrap_seq")) null else o.optLong("bootstrap_seq", 0L),
            deviceId = o.optString("device_id"),
        )
    }

    /** POST /v1/sync/push: at most [MAX_OPS_PER_PUSH] ops; the server assigns seq per op_id. */
    fun push(ops: List<Op>): PushResponse {
        require(ops.size <= MAX_OPS_PER_PUSH) { "push carries ${ops.size} ops, max $MAX_OPS_PER_PUSH" }
        val opsArr = JSONArray()
        for (op in ops) {
            opsArr.put(
                JSONObject()
                    .put("op_id", op.opId)
                    .put("device_id", op.deviceIdOverride ?: deviceId)
                    .put("kind", op.kind)
                    .put("wire_id", op.wireId)
                    .put("key_epoch", op.keyEpoch)
                    .put("blob", op.blobB64),
            )
        }
        val (code, body) = exchangePost("$baseUrl/v1/sync/push", JSONObject().put("ops", opsArr))
        if (code != 200) throw parseError(code, body)
        val o = JSONObject(body)
        val results = ArrayList<PushResult>()
        val arr = o.optJSONArray("results") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            results += PushResult(r.getString("op_id"), r.getLong("seq"))
        }
        return PushResponse(results, o.optLong("head_seq", 0L))
    }

    /** POST /v1/sync/pull: ALL ops with seq > sinceSeq, no dedup. */
    fun pull(sinceSeq: Long, limit: Int = PULL_LIMIT_DEFAULT): PullResponse {
        val (code, body) = exchangePost("$baseUrl/v1/sync/pull", JSONObject().put("since_seq", sinceSeq).put("limit", limit))
        if (code != 200) throw parseError(code, body)
        val o = JSONObject(body)
        val ops = ArrayList<LoggedOp>()
        val arr = o.optJSONArray("ops") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            ops += LoggedOp(
                seq = e.getLong("seq"),
                opId = e.getString("op_id"),
                deviceId = e.getString("device_id"),
                kind = e.getString("kind"),
                wireId = e.getString("wire_id"),
                keyEpoch = e.getLong("key_epoch"),
                blobB64 = e.getString("blob"),
                receivedAt = e.optString("received_at"),
            )
        }
        return PullResponse(ops, o.optLong("head_seq", 0L), o.optBoolean("has_more", false))
    }

    /** GET /v1/sync/bootstrap: the vault's latest bootstrap snapshot (null if none). */
    fun bootstrap(): Bootstrap? {
        val (code, body) = exchangeGet("$baseUrl/v1/sync/bootstrap")
        return when {
            code == 404 -> null
            code != 200 -> throw parseError(code, body)
            else -> {
                val o = JSONObject(body)
                Bootstrap(o.getLong("covered_seq"), o.getLong("seq"), o.getString("blob"))
            }
        }
    }

    /** POST /v1/sync/bootstrap: declares which seq the uploaded bootstrap snapshot covers (promotes the floor). */
    fun promoteBootstrap(opId: String, coveredSeq: Long) {
        val (code, body) = exchangePost(
            "$baseUrl/v1/sync/bootstrap",
            JSONObject().put("op_id", opId).put("covered_seq", coveredSeq),
        )
        if (code != 200) throw parseError(code, body)
    }

    /**
     * Opens the canonical SSE stream (`GET /v1/sync/stream?device_id=…`, `Last-Event-ID` to
     * resume from a seq). Returns the 200 connection with the body ready for [readStreamEvent];
     * the caller owns the loop and the backoff.
     */
    fun openStream(): HttpURLConnection {
        val conn = URL("$baseUrl/v1/sync/stream?device_id=$deviceId").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 60000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "text/event-stream")
        if (conn.responseCode != 200) {
            val code = conn.responseCode
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            throw parseError(code, body)
        }
        return conn
    }

    /** Reads the next SSE `data:` event; null when the stream closes. */
    fun readStreamEvent(reader: BufferedReader): JSONObject? {
        while (true) {
            val line = reader.readLine() ?: return null
            val current = line.trim()
            if (!current.startsWith("data:")) continue
            val data = current.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            return runCatching { JSONObject(data) }.getOrNull()
        }
    }

    // ------------------------------------------------------------------
    // JSON over HttpURLConnection (same HTTP style the app already uses;
    // cero dependencias nuevas)
    // ------------------------------------------------------------------

    private fun connect(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun exchangePost(url: String, payload: JSONObject): Pair<Int, String> {
        val conn = connect(url, "POST")
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun exchangeGet(url: String): Pair<Int, String> {
        val conn = connect(url, "GET")
        return try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseError(code: Int, body: String): SyncProtoError {
        val err = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
        return SyncProtoError(
            code = err?.optString("code") ?: "http_$code",
            detail = err?.optString("message") ?: body.take(200),
            httpStatus = code,
            retryAfter = err?.optLong("retry_after", -1L)?.takeIf { it >= 0 },
            compactFloor = err?.optLong("compact_floor", -1L)?.takeIf { it >= 0 },
            bootstrap = err?.optJSONObject("bootstrap")?.let {
                Bootstrap(it.getLong("covered_seq"), it.getLong("seq"), "")
            },
            clientProtocolVersion = err?.optString("client_protocol_version")?.takeIf { it.isNotBlank() },
            serverProtocolVersion = err?.optString("server_protocol_version")?.takeIf { it.isNotBlank() },
        )
    }
}

