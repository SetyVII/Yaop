package org.yaop.core.sync.ops

import org.json.JSONObject

/** One operation as delivered by the pull or the live stream, before any decoding. */
class RemoteOp(
    val seq: Long,
    val kind: String,
    val wireId: String,
    val deviceId: String,
    val blob: ByteArray,
)

/** An operation whose envelope was opened and whose payload parsed. */
class DecodedOp(val op: RemoteOp, val payload: JSONObject)

/** What the sync cycle must do after a handler ran. */
enum class OpResult {
    /** Handled: advance the cursor. */
    APPLIED,

    /** Not applicable or unreadable: advance the cursor anyway (local state is never lost). */
    SKIPPED,

    /** Stop the pull WITHOUT advancing the cursor; the op is delivered again later (merge is idempotent). */
    PAUSE,
}

/**
 * Extension point of the sync cycle: one handler per operation kind (spec/sync.md).
 * A new kind is a new class registered in [RemoteOpRegistry]; the pull loop is never edited.
 */
interface RemoteOpHandler {
    /** The kinds this handler owns. A kind belongs to exactly one handler. */
    val kinds: Set<String>

    /** False when the handler only needs to see the op go by (nothing is decrypted for it). */
    val needsPayload: Boolean get() = true

    suspend fun handle(op: DecodedOp): OpResult
}

/** Opens the sealed blob of an op (the only place that knows about envelopes and keys). */
fun interface OpOpener {
    /** The decrypted payload bytes, or null if the blob cannot be opened (wrong epoch, corruption). */
    fun open(kind: String, wireId: String, blob: ByteArray): ByteArray?
}

/** Kind → handler. Registering two handlers for one kind is a programming error and fails loudly. */
class RemoteOpRegistry(handlers: List<RemoteOpHandler>) {
    private val byKind: Map<String, RemoteOpHandler>

    init {
        val map = HashMap<String, RemoteOpHandler>()
        for (h in handlers) for (k in h.kinds) {
            require(map.put(k, h) == null) { "two handlers for op kind '$k'" }
        }
        byKind = map
    }

    fun handlerFor(kind: String): RemoteOpHandler? = byKind[kind]
    val kinds: Set<String> get() = byKind.keys
}

/**
 * Decode-then-dispatch for every incoming op. Decryption and parsing live here once; handlers
 * only ever see a [DecodedOp].
 */
class RemoteOpPipeline(
    private val registry: RemoteOpRegistry,
    private val opener: OpOpener,
    private val log: (String) -> Unit = {},
) {
    suspend fun apply(op: RemoteOp): OpResult {
        val handler = registry.handlerFor(op.kind)
        if (handler == null) {
            log("no handler for op kind '${op.kind}' at seq ${op.seq}; skipping")
            return OpResult.SKIPPED
        }
        if (!handler.needsPayload) return handler.handle(DecodedOp(op, JSONObject()))
        val plain = opener.open(op.kind, op.wireId, op.blob)
        if (plain == null) {
            // Wrong epoch or corruption: skip and advance so one bad op never stalls the vault.
            log("undecryptable ${op.kind} op at seq ${op.seq} (wire_id ${op.wireId}); skipping")
            return OpResult.SKIPPED
        }
        val payload = try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            log("non-JSON payload in op at seq ${op.seq}; skipping")
            return OpResult.SKIPPED
        }
        return handler.handle(DecodedOp(op, payload))
    }
}
