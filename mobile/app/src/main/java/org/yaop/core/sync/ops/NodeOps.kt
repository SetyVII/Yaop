package org.yaop.core.sync.ops

import org.json.JSONObject
import java.io.File

/**
 * `derived` artifacts and `node_request`s (spec/sync.md §Artefactos derived/, spec §23).
 * Both are consumed through small sinks so the storage can change (files today, a table later)
 * and so a device that is not the trust node can simply keep requests without acting on them.
 */

/** A derived artifact (thumbnail, PDF text, OCR…), stored under the blind `derived/<objectId>/<kind>` id. */
class DerivedArtifact(val kind: String, val sourceHash: String, val producer: String, val data: JSONObject) {
    companion object {
        /** Null when the payload does not follow the manifest of the spec (version 1). */
        fun parse(payload: JSONObject): DerivedArtifact? {
            if (payload.optInt("v", 0) != 1) return null
            val kind = payload.optString("kind")
            val hash = payload.optString("source_hash")
            if (kind.isBlank() || hash.isBlank() || !payload.has("data")) return null
            val data = payload.optJSONObject("data") ?: JSONObject().put("value", payload.get("data"))
            return DerivedArtifact(kind, hash, payload.optString("producer"), data)
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("v", 1).put("kind", kind).put("source_hash", sourceHash).put("producer", producer).put("data", data)
}

interface DerivedSink {
    fun put(wireId: String, artifact: DerivedArtifact)
    fun get(wireId: String): DerivedArtifact?
}

/** One JSON file per artifact; the blind wire id is a safe file name. */
class FileDerivedSink(private val dir: File) : DerivedSink {
    private fun file(wireId: String): File {
        require(wireId.matches(Regex("[0-9a-f]{1,64}"))) { "unexpected wire id" }
        return File(dir, "$wireId.json")
    }

    override fun put(wireId: String, artifact: DerivedArtifact) {
        dir.mkdirs()
        file(wireId).writeText(artifact.toJson().toString())
    }

    override fun get(wireId: String): DerivedArtifact? =
        runCatching { DerivedArtifact.parse(JSONObject(file(wireId).readText())) }.getOrNull()
}

class DerivedOpHandler(private val sink: DerivedSink) : RemoteOpHandler {
    override val kinds = setOf("derived")
    override suspend fun handle(op: DecodedOp): OpResult {
        val artifact = DerivedArtifact.parse(op.payload) ?: return OpResult.SKIPPED
        // Last writer wins: ops arrive in seq order, a newer producer simply overwrites.
        sink.put(op.op.wireId, artifact)
        return OpResult.APPLIED
    }
}

/** A request addressed to the trust node: "process this object first" (spec §23.3). */
class NodeRequest(val objectId: String, val kind: String) {
    companion object {
        fun parse(payload: JSONObject): NodeRequest? {
            if (payload.optInt("v", 0) != 1) return null
            val id = payload.optString("object_id")
            val kind = payload.optString("kind")
            return if (id.isBlank() || kind.isBlank()) null else NodeRequest(id, kind)
        }
    }

    fun toJson(): JSONObject = JSONObject().put("v", 1).put("object_id", objectId).put("kind", kind)
}

interface NodeRequestSink {
    fun enqueue(request: NodeRequest)
}

/** Only the trust node acts on requests ; any other device just sees them go by. */
class NodeRequestOpHandler(private val sink: NodeRequestSink?) : RemoteOpHandler {
    override val kinds = setOf("node_request")
    override suspend fun handle(op: DecodedOp): OpResult {
        val request = NodeRequest.parse(op.payload) ?: return OpResult.SKIPPED
        sink?.enqueue(request) ?: return OpResult.SKIPPED
        return OpResult.APPLIED
    }
}
