package org.yaop.core.sync.ops

import org.yaop.core.repository.PkmRepository
import org.yaop.core.sync.MergeContract
import org.yaop.core.sync.SyncPolicy

/** `type`: object type definitions travel as ops of their own. */
class TypeOpHandler(private val repository: PkmRepository) : RemoteOpHandler {
    override val kinds = setOf("type")
    override suspend fun handle(op: DecodedOp): OpResult {
        repository.applyTypeWire(op.payload)
        return OpResult.APPLIED
    }
}

/**
 * `obj` and `asset_meta`: full object state, merged with the local copy (spec/merge.md) and applied
 * once. [onMediaConverged] runs for `asset_meta` ops whose state needs no re-push, so the
 * caller can fetch the media bytes.
 */
class ObjectOpHandler(
    private val repository: PkmRepository,
    private val onMediaConverged: (realId: String, wireId: String, payload: org.json.JSONObject) -> Unit,
) : RemoteOpHandler {
    override val kinds = setOf("obj", "asset_meta")

    override suspend fun handle(op: DecodedOp): OpResult =
        applyState(op.payload, op.op.wireId, isAssetMeta = op.op.kind == "asset_meta")

    /** Merge a full object state into the local copy and apply it once (shared with snapshots). */
    suspend fun applyState(wire: org.json.JSONObject, wireId: String, isAssetMeta: Boolean): OpResult {
        val realId = wire.optString("id")
        if (realId.isBlank()) return OpResult.SKIPPED
        // Being edited right now: neither apply nor advance. The merge is idempotent, so re-pull later.
        if (realId == repository.activeEditingObjectId) return OpResult.PAUSE
        val local = repository.exportObjectForServer(realId)
        val merged = if (local != null) MergeContract.merge(local, wire) else wire
        // Expired tombstone (30+ days in the trash, spec §3.4): the server already has it, so keep
        // nothing locally unless a local change of ours still has to travel.
        if (SyncPolicy.trashExpired(merged.optString("trashed_at"), java.time.Instant.now()) &&
            (local == null || repository.getObject(realId)?.syncStatus == "synced")
        ) {
            if (local != null) repository.hardDelete(realId)
            return OpResult.APPLIED
        }
        val result = repository.applyMergedWire(realId, merged, wire)
        if (isAssetMeta && !result.needsPush) onMediaConverged(realId, wireId, wire)
        return OpResult.APPLIED
    }
}

/** Kinds that exist in the protocol but whose consumer is not wired yet: seen, logged, skipped. */
class PassthroughOpHandler(override val kinds: Set<String>, private val log: (String) -> Unit = {}) : RemoteOpHandler {
    override val needsPayload = false
    override suspend fun handle(op: DecodedOp): OpResult {
        log("skipping ${op.op.kind} op at seq ${op.op.seq}")
        return OpResult.SKIPPED
    }
}

/**
 * `snapshot`: either the bootstrap container of the whole vault or the snapshot of one object
 * (same state shape as `obj`). Objects of a container are merged like any other state, so
 * applying one over a vault that already has data loses nothing.
 */
class SnapshotOpHandler(
    private val repository: PkmRepository,
    private val objects: ObjectOpHandler,
) : RemoteOpHandler {
    override val kinds = setOf("snapshot")

    override suspend fun handle(op: DecodedOp): OpResult {
        val boot = BootstrapSnapshot.parse(op.payload)
            ?: return objects.applyState(op.payload, op.op.wireId, isAssetMeta = false)
        for (t in boot.types) repository.applyTypeWire(t)
        var result = OpResult.APPLIED
        for (wire in boot.objects) {
            val isMedia = wire.optString("id").startsWith("media_")
            if (objects.applyState(wire, "", isAssetMeta = isMedia) == OpResult.PAUSE) result = OpResult.PAUSE
        }
        return result
    }
}
