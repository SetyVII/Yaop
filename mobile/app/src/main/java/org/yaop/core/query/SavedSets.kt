package org.yaop.core.query

import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.PkmObject
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved sets (spec/query.md): objects of type `set` whose wire properties hold
 * `query`, `views` and `active_view`. The mobile model keeps them in [PkmObject.wireProps]
 * (raw passthrough), so reading and writing them never goes through the generic property mapping.
 */
object SavedSets {
    val VIEW_KINDS = listOf("gallery", "table", "list")

    fun isSet(obj: PkmObject): Boolean = obj.typeId == CanonicalTypeIds.SET

    private fun raw(obj: PkmObject): JSONObject =
        obj.wireProps?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    fun defaultViews(): JSONArray = JSONArray()
        .put(JSONObject().put("id", "gallery").put("kind", "gallery").put("cover", true))
        .put(JSONObject().put("id", "table").put("kind", "table").put("columns", JSONArray(listOf("@title", "@type", "@updated_at"))))
        .put(JSONObject().put("id", "list").put("kind", "list"))

    fun queryOf(obj: PkmObject): JSONObject =
        raw(obj).optJSONObject("query") ?: JSONObject().put("version", 1)

    fun viewsOf(obj: PkmObject): JSONArray =
        raw(obj).optJSONArray("views")?.takeIf { it.length() > 0 } ?: defaultViews()

    /** Kind of the open view (`gallery` when unknown). */
    fun activeKindOf(obj: PkmObject): String {
        val active = raw(obj).optString("active_view")
        val views = viewsOf(obj)
        for (i in 0 until views.length()) {
            val v = views.optJSONObject(i) ?: continue
            if (v.optString("id") == active) return v.optString("kind").takeIf { it in VIEW_KINDS } ?: "list"
        }
        return views.optJSONObject(0)?.optString("kind")?.takeIf { it in VIEW_KINDS } ?: "gallery"
    }

    fun withQuery(obj: PkmObject, query: JSONObject): PkmObject {
        val r = raw(obj)
        query.put("version", 1)
        r.put("query", query)
        if (!r.has("views")) r.put("views", defaultViews())
        if (!r.has("active_view")) r.put("active_view", "gallery")
        return obj.copy(wireProps = r.toString())
    }

    fun withActiveKind(obj: PkmObject, kind: String): PkmObject {
        val r = raw(obj)
        val views = r.optJSONArray("views")?.takeIf { it.length() > 0 } ?: defaultViews()
        var id: String? = null
        for (i in 0 until views.length()) {
            val v = views.optJSONObject(i) ?: continue
            if (v.optString("kind") == kind) { id = v.optString("id"); break }
        }
        if (id == null) {
            id = kind
            views.put(JSONObject().put("id", kind).put("kind", kind))
        }
        r.put("views", views)
        r.put("active_view", id)
        return obj.copy(wireProps = r.toString())
    }
}
