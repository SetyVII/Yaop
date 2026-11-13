package org.yaop.core.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Replays every json fixture in spec/fixtures/sync against a local `com.sun.net.httpserver` server
 * (zero new dependencies) implementing the abstract OpLog semantics of
 * `server/internal/simclient/protocol.go` — seq assignment, op_id idempotency, bootstrap
 * compaction, exact error schema — while the REAL BlindTransport HTTP client talks to it.
 *
 * This mirrors what the second-round instrumented run will do against the actual blind server
 * server: the fixtures are the same, only the transport underneath changes.
 */
object SyncProtoFixtureServerRunner {

    fun runFixtureSteps(specDir: File) {
        val files = File(specDir, "sync").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        org.junit.Assert.assertTrue("no syncproto fixtures", files.isNotEmpty())
        val failures = ArrayList<String>()
        for (f in files) {
            try {
                runOne(f)
            } catch (t: Throwable) {
                failures += "${f.name}: ${t.message}"
            }
        }
        org.junit.Assert.assertTrue("syncproto fixture steps failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun runOne(f: File) {
        val fx = JSONObject(f.readText())
        val log = LocalOpLog()
        val server = LocalSyncProtoServer(log)
        server.start()
        try {
            val transport = BlindTransport(
                baseUrl = "http://127.0.0.1:${server.port()}",
                token = "fixture-token",
                deviceId = "dev_tablet",
            )
            val status0 = transport.status()
            org.junit.Assert.assertEquals("protocol_version is exposed", "1", status0.protocolVersion)
            for (step in 0 until fx.getJSONArray("steps").length()) {
                val s = fx.getJSONArray("steps").getJSONObject(step)
                val label = "${f.name} step $step (${s.optString("action")})"
                when (s.getString("action")) {
                    "push" -> {
                        val ops = opsFromJson(s.getJSONArray("ops"))
                        if (s.has("expect_error")) {
                            val err = runCatching { transport.push(ops) }.exceptionOrNull()
                            org.junit.Assert.assertTrue("$label: expected error", err is BlindTransport.SyncProtoError)
                            checkError(label, err as BlindTransport.SyncProtoError, s.getJSONObject("expect_error"))
                        } else {
                            val expect = s.getJSONObject("expect")
                            val got = transport.push(ops)
                            val wantResults = expect.getJSONArray("results")
                            org.junit.Assert.assertEquals("$label results size", wantResults.length(), got.results.size)
                            for (i in 0 until wantResults.length()) {
                                org.junit.Assert.assertEquals("$label[$i].op_id", wantResults.getJSONObject(i).getString("op_id"), got.results[i].opId)
                                org.junit.Assert.assertEquals("$label[$i].seq", wantResults.getJSONObject(i).getLong("seq"), got.results[i].seq)
                            }
                            org.junit.Assert.assertEquals("$label head_seq", expect.getLong("head_seq"), got.headSeq)
                        }
                    }
                    "pull" -> {
                        val since = s.getLong("since_seq")
                        if (s.has("expect_error")) {
                            val err = runCatching { transport.pull(since) }.exceptionOrNull()
                            org.junit.Assert.assertTrue("$label: expected error", err is BlindTransport.SyncProtoError)
                            checkError(label, err as BlindTransport.SyncProtoError, s.getJSONObject("expect_error"))
                        } else {
                            val expect = s.getJSONObject("expect")
                            val got = transport.pull(since)
                            org.junit.Assert.assertEquals("$label seqs", longsOf(expect.getJSONArray("seqs")).joinToString(), got.ops.map { it.seq }.joinToString())
                            org.junit.Assert.assertEquals("$label op_ids", stringsOf(expect.getJSONArray("op_ids")).joinToString(), got.ops.map { it.opId }.joinToString())
                            org.junit.Assert.assertEquals("$label head_seq", expect.getLong("head_seq"), got.headSeq)
                            org.junit.Assert.assertEquals("$label has_more", expect.optBoolean("has_more", false), got.hasMore)
                        }
                    }
                    "status" -> {
                        val expect = s.getJSONObject("expect")
                        val got = transport.status()
                        org.junit.Assert.assertEquals("$label protocol_version", expect.getString("protocol_version"), got.protocolVersion)
                        org.junit.Assert.assertEquals("$label vault", expect.getString("vault"), got.vault)
                        org.junit.Assert.assertEquals("$label head_seq", expect.getLong("head_seq"), got.headSeq)
                        org.junit.Assert.assertEquals("$label compact_floor", expect.getLong("compact_floor"), got.compactFloor)
                        if (expect.isNull("bootstrap_seq")) {
                            org.junit.Assert.assertNull("$label bootstrap_seq", got.bootstrapSeq)
                        } else {
                            org.junit.Assert.assertEquals("$label bootstrap_seq", expect.getLong("bootstrap_seq"), got.bootstrapSeq)
                        }
                        org.junit.Assert.assertEquals("$label device_id", expect.getString("device_id"), got.deviceId)
                    }
                    "promote_bootstrap" -> {
                        val resp = server.postJson(
                            "/test/promote_bootstrap",
                            JSONObject().put("op_id", s.getString("op_id")).put("covered_seq", s.getLong("covered_seq")),
                        )
                        org.junit.Assert.assertEquals("$label promote ok", true, resp.optBoolean("ok"))
                    }
                    "compact" -> {
                        val resp = server.postJson("/test/compact", JSONObject())
                        org.junit.Assert.assertEquals("$label compact ok", true, resp.optBoolean("ok"))
                    }
                    else -> org.junit.Assert.fail("$label: unknown action ${s.getString("action")}")
                }
            }
        } finally {
            server.stop()
        }
    }

    private fun opsFromJson(arr: JSONArray): List<BlindTransport.Op> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BlindTransport.Op(
                opId = o.getString("op_id"),
                kind = o.getString("kind"),
                wireId = o.getString("wire_id"),
                keyEpoch = o.getLong("key_epoch"),
                blobB64 = o.getString("blob"),
                deviceIdOverride = o.optString("device_id").takeIf { it.isNotEmpty() },
            )
        }

    private fun checkError(label: String, err: BlindTransport.SyncProtoError, expect: JSONObject) {
        org.junit.Assert.assertEquals("$label code", expect.getString("code"), err.code)
        expect.optJSONObject("bootstrap")?.let { boot ->
            org.junit.Assert.assertEquals("$label covered_seq", boot.getLong("covered_seq"), err.bootstrap?.coveredSeq ?: -1L)
            org.junit.Assert.assertEquals("$label bootstrap seq", boot.getLong("seq"), err.bootstrap?.seq ?: -1L)
        }
        expect.optInt("http_status", -1).takeIf { it > 0 }?.let {
            org.junit.Assert.assertEquals("$label http_status", it, err.httpStatus)
        }
    }

    private fun longsOf(a: JSONArray): List<Long> = (0 until a.length()).map { a.getLong(it) }
    private fun stringsOf(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

}

/**
 * The abstract per-vault log (executable model of spec/sync.md, port of
 * `simclient/protocol.go`): the local test server derives its responses from this —
 * seq assignment, op_id idempotency, pull semantics, bootstrap promotion and compaction.
 */
class LocalOpLog {
    var headSeq = 0L
    private val ops = ArrayList<BlindTransport.LoggedOp>()
    private val byOpId = HashMap<String, Long>()
    var compactFloor = 0L
    var bootstrapSeq = 0L

    data class PushErr(val code: String, val message: String)

    fun push(opsIn: List<BlindTransport.Op>): Pair<List<BlindTransport.PushResult>?, PushErr?> {
        if (opsIn.size > 500) return null to PushErr("bad_request", "push carries ${opsIn.size} ops, max 500")
        val results = ArrayList<BlindTransport.PushResult>()
        for (op in opsIn) {
            if (op.kind !in setOf("obj", "type", "asset_meta", "derived", "snapshot", "node_request")) {
                return null to PushErr("bad_kind", "unknown kind \"${op.kind}\"")
            }
            if (op.opId.length != 32) return null to PushErr("bad_request", "op_id must be 32 hex chars")
            val seen = byOpId[op.opId]
            if (seen != null) {
                results += BlindTransport.PushResult(op.opId, seen) // idempotent
                continue
            }
            headSeq += 1
            val logged = BlindTransport.LoggedOp(
                seq = headSeq,
                opId = op.opId,
                deviceId = op.deviceIdOverride ?: "",
                kind = op.kind,
                wireId = op.wireId,
                keyEpoch = op.keyEpoch,
                blobB64 = op.blobB64,
                receivedAt = receivedAtFor(headSeq),
            )
            ops += logged
            byOpId[op.opId] = headSeq
            results += BlindTransport.PushResult(op.opId, headSeq)
        }
        return results to null
    }

    fun promoteBootstrap(opId: String, coveredSeq: Long): PushErr? {
        val seq = byOpId[opId] ?: return PushErr("not_found", "op $opId not in the log")
        if (coveredSeq > seq) return PushErr("bad_request", "covered_seq $coveredSeq exceeds the snapshot seq $seq")
        if (coveredSeq < compactFloor) return PushErr("bad_request", "covered_seq $coveredSeq would lower the floor $compactFloor")
        compactFloor = coveredSeq
        bootstrapSeq = seq
        return null
    }

    fun compact(): Int {
        val kept = ArrayList<BlindTransport.LoggedOp>()
        var removed = 0
        for (op in ops) {
            if (op.seq <= compactFloor && bootstrapSeq != 0L && op.seq < bootstrapSeq) {
                removed++
                continue
            }
            kept += op
        }
        ops.clear()
        ops.addAll(kept)
        return removed
    }

    fun pull(sinceSeq: Long, limitIn: Int): Triple<List<BlindTransport.LoggedOp>?, Map<String, Any?>?, Long> {
        val limit = when {
            limitIn <= 0 -> 200
            limitIn > 1000 -> 1000
            else -> limitIn
        }
        if (sinceSeq < compactFloor) {
            val err = mapOf(
                "code" to "oplog_compacted",
                "message" to "sync from seq $sinceSeq not available; bootstrap first",
                "compact_floor" to compactFloor,
                "bootstrap" to mapOf("covered_seq" to compactFloor, "seq" to bootstrapSeq),
            )
            return Triple(null, err, headSeq)
        }
        val out = ArrayList<BlindTransport.LoggedOp>()
        for (op in ops) {
            if (op.seq > sinceSeq && op.seq <= headSeq) {
                out += op
                if (out.size >= limit) break
            }
        }
        out.sortBy { it.seq }
        var hasMore = false
        if (out.size == limit && out.isNotEmpty() && out.last().seq < headSeq) hasMore = true
        return Triple(out, null, headSeq)
    }

    private fun receivedAtFor(seq: Long): String =
        String.format("2026-11-09T12:%02d:%02dZ", ((seq - 1) / 60 % 60).toInt(), ((seq - 1) % 60).toInt())
}

/** Local HTTP server over the JDK's com.sun.net.httpserver: the /v1 wire endpoints + the
 *  fixture-only state actions (promote_bootstrap / compact), which the real server drives
 *  internally . */
class LocalSyncProtoServer(private val log: LocalOpLog) {
    private val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newSingleThreadExecutor()

    init {
        server.executor = executor
        server.createContext("/v1/sync/push") { ex -> handle(ex) { body ->
            val opsJson = body.getJSONArray("ops")
            val ops = (0 until opsJson.length()).map { i ->
                val o = opsJson.getJSONObject(i)
                BlindTransport.Op(
                    opId = o.getString("op_id"),
                    kind = o.getString("kind"),
                    wireId = o.getString("wire_id"),
                    keyEpoch = o.getLong("key_epoch"),
                    blobB64 = o.getString("blob"),
                    deviceIdOverride = o.optString("device_id").takeIf { it.isNotEmpty() },
                )
            }
            val (results, err) = log.push(ops)
            if (err != null) {
                errorBody(ex, 400, err.code, err.message, null)
            } else {
                val resArr = JSONArray()
                results!!.forEach { resArr.put(JSONObject().put("op_id", it.opId).put("seq", it.seq)) }
                respond(ex, 200, JSONObject().put("results", resArr).put("head_seq", log.headSeq))
            }
        } }
        server.createContext("/v1/sync/pull") { ex -> handle(ex) { body ->
            val since = body.optLong("since_seq", 0L)
            val limit = body.optInt("limit", 200)
            val (ops, err, head) = log.pull(since, limit)
            if (err != null) {
                val errObj = JSONObject()
                    .put("code", err["code"])
                    .put("message", err["message"])
                    .put("compact_floor", err["compact_floor"])
                    .put("bootstrap", JSONObject().put("covered_seq", (err["bootstrap"] as Map<*, *>)["covered_seq"]).put("seq", (err["bootstrap"] as Map<*, *>)["seq"]))
                respond(ex, 410, JSONObject().put("error", errObj))
            } else {
                val opsArr = JSONArray()
                ops!!.forEach {
                    opsArr.put(
                        JSONObject()
                            .put("seq", it.seq)
                            .put("op_id", it.opId)
                            .put("device_id", it.deviceId)
                            .put("kind", it.kind)
                            .put("wire_id", it.wireId)
                            .put("key_epoch", it.keyEpoch)
                            .put("blob", it.blobB64)
                            .put("received_at", it.receivedAt),
                    )
                }
                var hasMore = false
                val limitEff = when { limit <= 0 -> 200; limit > 1000 -> 1000; else -> limit }
                if (ops.size == limitEff && ops.isNotEmpty() && ops.last().seq < head) hasMore = true
                respond(ex, 200, JSONObject().put("ops", opsArr).put("head_seq", head).put("has_more", hasMore))
            }
        } }
        server.createContext("/v1/sync/status") { ex ->
            val status = JSONObject()
                .put("protocol_version", "1")
                .put("vault", "vault_test")
                .put("head_seq", log.headSeq)
                .put("compact_floor", log.compactFloor)
                .put("bootstrap_seq", if (log.bootstrapSeq == 0L) JSONObject.NULL else log.bootstrapSeq)
                .put("device_id", deviceOf(ex) ?: "dev_tablet")
            respond(ex, 200, status)
        }
        server.createContext("/test/promote_bootstrap") { ex -> handlePost(ex) { body ->
            val err = log.promoteBootstrap(body.getString("op_id"), body.getLong("covered_seq"))
            if (err != null) errorBody(ex, 400, err.code, err.message, null) else respond(ex, 200, JSONObject().put("ok", true))
        } }
        server.createContext("/test/compact") { ex -> handlePost(ex) {
            log.compact()
            respond(ex, 200, JSONObject().put("ok", true))
        } }
    }

    fun port(): Int = server.address.port

    fun start() = server.start()

    fun stop() {
        server.stop(0)
        executor.shutdown()
    }

    fun postJson(path: String, body: JSONObject): JSONObject {
        val conn = java.net.URL("http://127.0.0.1:${port()}$path").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun deviceOf(ex: com.sun.net.httpserver.HttpExchange): String? =
        ex.requestURI.query?.split("&")?.firstOrNull { it.startsWith("device_id=") }?.substringAfter("=")

    private fun errorBody(
        ex: com.sun.net.httpserver.HttpExchange,
        status: Int,
        code: String,
        message: String,
        extra: JSONObject?,
    ) {
        val err = JSONObject().put("code", code).put("message", message)
        if (extra != null) {
            for (k in extra.keys()) err.put(k, extra.get(k))
        }
        respond(ex, status, JSONObject().put("error", err))
    }

    private fun handlePost(ex: com.sun.net.httpserver.HttpExchange, block: (JSONObject) -> Unit) {
        if (ex.requestMethod != "POST") {
            respond(ex, 405, JSONObject().put("error", JSONObject().put("code", "bad_request").put("message", "POST only")))
            return
        }
        val body = JSONObject(String(ex.requestBody.readBytes(), Charsets.UTF_8))
        block(body)
    }

    private fun handle(ex: com.sun.net.httpserver.HttpExchange, block: (JSONObject) -> Unit) = handlePost(ex, block)

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        val os: OutputStream = ex.responseBody
        os.use { it.write(bytes) }
        ex.close()
    }
}
