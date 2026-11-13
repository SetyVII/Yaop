package org.yaop.core.sync.ops

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bootstrap snapshot container (spec/sync.md §Snapshot de bootstrap): the full state of the vault
 * as of `covered_seq`, so a new device (or one behind the compaction floor) does not replay the log.
 * Pure: no network, no database.
 */
object BootstrapSnapshot {
    const val VERSION = 1
    /** The blind id this container is stored under is `wireId("bootstrap")`. */
    const val BLIND_INPUT = "bootstrap"

    class Parsed(val keyEpoch: Long, val coveredSeq: Long, val objects: List<JSONObject>, val types: List<JSONObject>)

    fun build(keyEpoch: Long, coveredSeq: Long, objects: List<JSONObject>, types: List<JSONObject>): JSONObject =
        JSONObject()
            .put("v", VERSION)
            .put("key_epoch", keyEpoch)
            .put("covered_seq", coveredSeq)
            .put("objects", JSONArray(objects))
            .put("types", JSONArray(types))

    /** Null when [payload] is not a bootstrap container (e.g. the snapshot of a single object). */
    fun parse(payload: JSONObject): Parsed? {
        val objs = payload.optJSONArray("objects") ?: return null
        if (payload.optInt("v", 0) != VERSION) return null
        val types = payload.optJSONArray("types") ?: JSONArray()
        return Parsed(
            keyEpoch = payload.optLong("key_epoch", 1L),
            coveredSeq = payload.optLong("covered_seq", 0L),
            objects = (0 until objs.length()).map { objs.getJSONObject(it) },
            types = (0 until types.length()).map { types.getJSONObject(it) },
        )
    }
}
