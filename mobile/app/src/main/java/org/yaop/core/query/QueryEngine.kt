package org.yaop.core.query

import org.yaop.core.sync.MergeContract
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Local query engine, port of the reference evaluator (server/internal/simclient/query.go) of
 * spec/query.md (yaop-query/1). Works on wire JSON objects with type ids already normalized.
 * Pure: no database, no Android. The contract is spec/fixtures/query.
 */
object QueryEngine {

    class QueryException(val code: String) : Exception("query: $code")

    /** One group of a grouped result; [key] null is the "null" group (empty values). */
    data class Group(val key: String?, val ids: List<String>)

    /** [ids] for a flat result, [groups] when the query has `group`. */
    data class Result(val ids: List<String> = emptyList(), val groups: List<Group>? = null)

    private val KNOWN_OPS = setOf(
        "eq", "neq", "contains", "not_contains", "starts_with", "gt", "gte", "lt", "lte",
        "empty", "not_empty", "is_true", "is_false", "has_any", "has_all", "has_none",
        "before", "after", "on", "between",
    )

    /** Evaluates [query] over [objects]; [today] is the device's local day, `YYYY-MM-DD`. */
    fun evaluate(query: JSONObject, objects: List<JSONObject>, today: String): Result {
        lowerMemo.set(HashMap())
        try {
            return evaluateInner(query, objects, today)
        } finally {
            lowerMemo.set(null)
        }
    }

    private fun evaluateInner(query: JSONObject, objects: List<JSONObject>, today: String): Result {
        val version = (query.opt("version") as? Number)?.toDouble()
        if (version != 1.0) throw QueryException("unsupported_version")
        val match = str(query.opt("match"), "all")
        val filters = query.optJSONArray("filters") ?: JSONArray()
        validateGroup(match, filters)
        val sorts = query.optJSONArray("sort")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        for (s in sorts) {
            val d = str(s.opt("dir"), "asc")
            if (d != "asc" && d != "desc") throw QueryException("unknown_dir")
        }
        val order = str(query.opt("order"), "sorted")
        if (order != "sorted" && order != "random") throw QueryException("unknown_order")

        val types = query.optJSONArray("types")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() }.orEmpty()
        var hits = objects.filter { o ->
            str(o.opt("trashed_at"), "").isEmpty() &&
                o.optJSONObject("properties")?.opt("is_template") != true &&
                (types.isEmpty() || str(o.opt("type_id"), "") in types) &&
                evalGroup(match, filters, o, today)
        }

        hits = if (order == "random") {
            val seed = str(query.opt("random_seed"), "")
            hits.sortedBy { sha256Hex(seed + ":" + str(it.opt("id"), "")) }
        } else {
            val criteria = sorts.ifEmpty { listOf(JSONObject().put("prop", "@updated_at").put("dir", "desc")) }
            val props = criteria.map { str(it.opt("prop"), "") }
            val desc = criteria.map { str(it.opt("dir"), "asc") == "desc" }
            // Decorate once: each object's keys are computed a single time, not per comparison.
            val rows = hits.map { o -> Triple(o, str(o.opt("id"), ""), props.map { sortKey(fieldValue(o, it)) }) }
            val cmp = Comparator<Triple<JSONObject, String, List<SortKey>>> { a, b ->
                for (i in props.indices) {
                    val c = compareSortKeys(a.third[i], b.third[i], desc[i])
                    if (c != 0) return@Comparator c
                }
                comparePoints(a.second, b.second)
            }
            val k = (query.opt("limit") as? Number)?.toInt() ?: 0
            if (k > 0 && k < rows.size / 4) {
                // Top-k: the order is total (ties end on @id), so a bounded heap gives exactly the
                // first k of the full sort in O(n log k).
                val heap = java.util.PriorityQueue(k + 1, cmp.reversed())
                for (r in rows) {
                    heap.add(r)
                    if (heap.size > k) heap.poll()
                }
                heap.toList().sortedWith(cmp).map { it.first }
            } else {
                rows.sortedWith(cmp).map { it.first }
            }
        }
        val limit = (query.opt("limit") as? Number)?.toInt() ?: 0
        if (limit > 0 && limit < hits.size) hits = hits.subList(0, limit)

        val group = query.optJSONObject("group") ?: return Result(ids = hits.map { str(it.opt("id"), "") })
        return Result(groups = groupHits(hits, group))
    }

    // ---- filters ----

    private fun validateGroup(match: String, filters: JSONArray) {
        if (match != "all" && match != "any") throw QueryException("unknown_match")
        for (i in 0 until filters.length()) {
            val f = filters.optJSONObject(i) ?: JSONObject()
            val sub = f.optJSONArray("filters")
            if (sub != null) {
                validateGroup(str(f.opt("match"), "all"), sub)
            } else if (str(f.opt("op"), "") !in KNOWN_OPS) {
                throw QueryException("unknown_op")
            }
        }
    }

    private fun evalGroup(match: String, filters: JSONArray, o: JSONObject, today: String): Boolean {
        if (filters.length() == 0) return true
        for (i in 0 until filters.length()) {
            val f = filters.optJSONObject(i) ?: JSONObject()
            val sub = f.optJSONArray("filters")
            val ok = if (sub != null) evalGroup(str(f.opt("match"), "all"), sub, o, today) else evalCond(f, o, today)
            if (match == "any" && ok) return true
            if (match == "all" && !ok) return false
        }
        return match == "all"
    }

    /** Value of [prop] on [o]; null when absent. */
    fun fieldValue(o: JSONObject, prop: String): Any? {
        val props = o.optJSONObject("properties")
        val v: Any? = when (prop) {
            "@id" -> o.opt("id")
            "@type" -> o.opt("type_id")
            "@title" -> o.opt("title")
            "@created_at" -> o.opt("created_at")
            "@updated_at" -> o.opt("updated_at")
            "@pinned" -> props?.opt("pinned") == true
            "@refs" -> {
                val set = sortedSetOf<String>(Comparator { a, b -> comparePoints(a, b) })
                for (k in listOf("links", "property_links")) {
                    val arr = o.optJSONArray(k) ?: continue
                    for (i in 0 until arr.length()) (arr.opt(i) as? String)?.let(set::add)
                }
                JSONArray(set.toList())
            }
            "blocks" -> null
            else -> props?.opt(prop)
        }
        return if (v == JSONObject.NULL) null else v
    }

    private fun isEmpty(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> true
        is String -> v.isEmpty()
        is JSONArray -> v.length() == 0
        is JSONObject -> v.length() == 0
        else -> false
    }

    /** Simple per-code-point lowercase mapping (spec/query.md §Valores). */
    fun lower(s: String): String {
        // ASCII fast path: nothing to map, or plain ASCII mapping (identical to the per-code-point one).
        var ascii = true
        var hasUpper = false
        for (ch in s) {
            if (ch.code >= 0x80) { ascii = false; break }
            if (ch in 'A'..'Z') hasUpper = true
        }
        if (ascii) {
            if (!hasUpper) return s
            val out = CharArray(s.length) { i -> val c = s[i]; if (c in 'A'..'Z') (c + 32) else c }
            return String(out)
        }
        // BMP chars map 1:1 (simple mapping keeps the length); only surrogate pairs need code points.
        var hasSurrogate = false
        for (ch in s) if (Character.isSurrogate(ch)) { hasSurrogate = true; break }
        if (!hasSurrogate) {
            val out = CharArray(s.length) { i -> Character.toLowerCase(s[i]) }
            return String(out)
        }
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            sb.appendCodePoint(Character.toLowerCase(cp))
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Per-evaluation memo of [lower]: a title is filtered and sorted in the same run. */
    private val lowerMemo = ThreadLocal<HashMap<String, String>?>()

    private fun lowerCached(s: String): String {
        val memo = lowerMemo.get() ?: return lower(s)
        return memo.getOrPut(s) { lower(s) }
    }

    private fun asList(v: Any?): List<Any?> = when {
        isEmpty(v) -> emptyList()
        v is JSONArray -> (0 until v.length()).map { v.opt(it) }
        else -> listOf(v)
    }

    private fun jsonEqual(a: Any?, b: Any?): Boolean {
        if (a is Number) return b is Number && a.toDouble() == b.toDouble()
        return MergeContract.canon(a) == MergeContract.canon(b)
    }

    private fun day(x: Any?): String? {
        val s = x as? String ?: return null
        if (s.length < 10) return null
        val d = s.substring(0, 10)
        return runCatching { LocalDate.parse(d); d }.getOrNull()
    }

    /** [s, e] of a date value, or null when it is not a date. */
    private fun dateInterval(v: Any?): Pair<String, String>? {
        if (v is JSONObject) {
            val s = day(v.opt("start")) ?: return null
            return s to (day(v.opt("end")) ?: s)
        }
        val s = day(v) ?: return null
        return s to s
    }

    private fun resolveDate(v: Any?, today: String): String? {
        val s = v as? String ?: return null
        if (s.startsWith("today")) {
            val t = runCatching { LocalDate.parse(today) }.getOrNull() ?: return null
            val rest = s.substring("today".length)
            val n = if (rest.isEmpty()) 0 else rest.toIntOrNull() ?: return null
            return t.plusDays(n.toLong()).toString()
        }
        return dateInterval(s)?.first
    }

    private fun evalCond(c: JSONObject, o: JSONObject, today: String): Boolean {
        val v = fieldValue(o, str(c.opt("prop"), ""))
        val want = c.opt("value").let { if (it == JSONObject.NULL) null else it }
        return when (val op = str(c.opt("op"), "")) {
            "eq" -> if (v is JSONArray) (0 until v.length()).any { jsonEqual(v.opt(it), want) } else v != null && jsonEqual(v, want)
            "neq" -> !evalCond(JSONObject().put("prop", c.opt("prop")).put("op", "eq").put("value", want ?: JSONObject.NULL), o, today)
            "contains", "not_contains", "starts_with" -> {
                val w = want as? String ?: ""
                val lw = lower(w)
                val hit = v is String && if (op == "starts_with") lowerCached(v).startsWith(lw) else lowerCached(v).contains(lw)
                if (op == "not_contains") !hit else hit
            }
            "gt", "gte", "lt", "lte" -> {
                val x = (v as? Number)?.toDouble() ?: return false
                val y = (want as? Number)?.toDouble() ?: return false
                when (op) { "gt" -> x > y; "gte" -> x >= y; "lt" -> x < y; else -> x <= y }
            }
            "empty" -> isEmpty(v)
            "not_empty" -> !isEmpty(v)
            "is_true" -> v == true
            "is_false" -> v != true
            "has_any", "has_all", "has_none" -> {
                val have = asList(v).mapNotNull { it as? String }.toSet()
                val wants = (want as? JSONArray)?.let { a -> (0 until a.length()).map { a.opt(it) } }.orEmpty()
                val count = wants.count { it is String && it in have }
                when (op) { "has_any" -> count > 0; "has_all" -> wants.isNotEmpty() && count == wants.size; else -> count == 0 }
            }
            "before", "after", "on" -> {
                val (s, e) = dateInterval(v) ?: return false
                val d = resolveDate(want, today) ?: return false
                when (op) { "before" -> e < d; "after" -> s > d; else -> s <= d && d <= e }
            }
            "between" -> {
                val (s, e) = dateInterval(v) ?: return false
                val pair = want as? JSONArray ?: return false
                if (pair.length() != 2) return false
                val a = resolveDate(pair.opt(0), today) ?: return false
                val b = resolveDate(pair.opt(1), today) ?: return false
                s <= b && e >= a
            }
            else -> false
        }
    }

    // ---- order ----

    /** Compares by Unicode code point (UTF-16 surrogates must not sort before U+E000..U+FFFF). */
    fun comparePoints(a: String, b: String): Int {
        // UTF-16 unit order equals code-point order except that surrogates (U+D800..DFFF) must
        // sort after U+E000..U+FFFF: remap both ranges at the first difference.
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            val u = a[i].code
            val v = b[i].code
            if (u != v) return fixUnit(u).compareTo(fixUnit(v))
        }
        return a.length.compareTo(b.length)
    }

    private fun fixUnit(c: Int): Int = when {
        c >= 0xE000 -> c - 0x800
        c >= 0xD800 -> c + 0x2000
        else -> c
    }

    /** rank: 0 boolean, 1 number, 2 string; -1 empty. */
    private data class SortKey(val rank: Int, val bool: Boolean = false, val num: Double = 0.0, val text: String = "")

    private fun sortKey(v: Any?): SortKey {
        if (isEmpty(v)) return SortKey(-1)
        return when (v) {
            is JSONArray -> sortKey(v.opt(0))
            is JSONObject -> (v.opt("start") as? String)?.let { SortKey(2, text = lowerCached(it)) } ?: SortKey(2, text = lower(MergeContract.canon(v)))
            is Boolean -> SortKey(0, bool = v)
            is Number -> SortKey(1, num = v.toDouble())
            is String -> SortKey(2, text = lowerCached(v))
            else -> SortKey(2, text = lower(MergeContract.canon(v)))
        }
    }

    private fun compareKeys(a: SortKey, b: SortKey): Int {
        if (a.rank != b.rank) return a.rank.compareTo(b.rank)
        return when (a.rank) {
            0 -> a.bool.compareTo(b.bool)
            1 -> a.num.compareTo(b.num)
            else -> comparePoints(a.text, b.text)
        }
    }

    private fun compareSortKeys(ka: SortKey, kb: SortKey, desc: Boolean): Int {
        if (ka.rank < 0 || kb.rank < 0) {
            return when {
                ka.rank < 0 && kb.rank < 0 -> 0
                ka.rank < 0 -> 1
                else -> -1
            }
        }
        val c = compareKeys(ka, kb)
        return if (desc) -c else c
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ---- groups ----

    /** Canonical JSON text of a number as Go encodes it: integers without ".0". */
    private fun numberText(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun groupKeyOf(v: Any?): String = when (v) {
        is String -> v
        is Boolean -> if (v) "true" else "false"
        is Number -> numberText(v.toDouble())
        is JSONObject -> dateInterval(v)?.first ?: MergeContract.canon(v)
        else -> MergeContract.canon(v)
    }

    private class Bucket(val key: String, var sk: SortKey) {
        val ids = mutableListOf<String>()
        val seen = mutableSetOf<String>()
    }

    private fun groupHits(hits: List<JSONObject>, g: JSONObject): List<Group> {
        val prop = str(g.opt("prop"), "")
        val buckets = linkedMapOf<String, Bucket>()
        val nullIds = mutableListOf<String>()
        for (o in hits) {
            val id = str(o.opt("id"), "")
            val elems = asList(fieldValue(o, prop))
            if (elems.isEmpty()) {
                nullIds += id
                continue
            }
            for (el in elems) {
                val k = groupKeyOf(el)
                val sk = sortKey(el)
                val b = buckets.getOrPut(k) { Bucket(k, sk) }
                if (compareKeys(sk, b.sk) < 0) b.sk = sk
                if (b.seen.add(id)) b.ids += id
            }
        }
        val out = mutableListOf<Group>()
        val used = mutableSetOf<String>()
        g.optJSONArray("order")?.let { order ->
            for (i in 0 until order.length()) {
                val k = order.opt(i) as? String ?: continue
                val b = buckets[k] ?: continue
                if (used.add(k)) out += Group(k, b.ids)
            }
        }
        buckets.values.filter { it.key !in used }
            .sortedWith { a, b -> compareKeys(a.sk, b.sk).takeIf { it != 0 } ?: comparePoints(a.key, b.key) }
            .forEach { out += Group(it.key, it.ids) }
        if (nullIds.isNotEmpty()) out += Group(null, nullIds)
        return out
    }

    private fun str(v: Any?, def: String): String = (v as? String)?.takeIf { it.isNotEmpty() } ?: def
}
