package org.yaop.core.sync

import org.yaop.core.model.BlockAnchor
import org.yaop.core.model.HybridLogicalClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client half of spec/merge.md (yaop-sync/1): the SAME wire JSON algebra as the Go
 * reference (`server/internal/sync/{stamp,merge}.go`) and the C++ port. `stamp` runs on
 * every local save; `merge` resolves two wire versions of one object deterministically
 * and symmetrically (merge(a,b) == merge(b,a)), so every client converges to the same
 * state whatever the arrival order — the blind /3 server never merges anything.
 *
 * `yaop-sync/1` adds `block_anchor` (spec/merge.md §block_anchor): every top-level
 * block records where it sits relative to its predecessor; the merged order renders as an
 * RGA forest over the closed union of anchor maps, which makes merge associative
 * (merge(merge(a,b),c) == merge(a,merge(b,c))), verified by spec/fixtures/merge_assoc.
 */
object MergeContract {

    data class Meta(
        val fieldHlc: Map<String, String> = emptyMap(),
        val blockHlc: Map<String, String> = emptyMap(),
        val deletedBlocks: Map<String, String> = emptyMap(),
        val blockAnchor: Map<String, BlockAnchor> = emptyMap(),
    )

    data class StampResult(
        val meta: Meta,
        val changed: Boolean,
        /** The stamped object's HLC: `now` when something changed, prev's otherwise. */
        val hlc: String,
    )

    private const val PROP_BLOCKS = "blocks"
    private const val PROP_PREFIX = "p:"
    /** field_hlc key that stamps the object tombstone `trashed_at` (spec/merge.md §Merge 2b). */
    const val FIELD_TRASHED = "trashed_at"

    /** Canonical JSON: sorted keys, numbers normalized through Double. */
    fun canon(value: Any?): String = buildString { appendCanon(this, value) }

    private fun appendCanon(sb: StringBuilder, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> sb.append("null")
            is JSONObject -> {
                sb.append('{')
                value.keys().asSequence().sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    sb.append(JSONObject.quote(k)).append(':')
                    appendCanon(sb, value.opt(k))
                }
                sb.append('}')
            }
            is JSONArray -> {
                sb.append('[')
                for (i in 0 until value.length()) {
                    if (i > 0) sb.append(',')
                    appendCanon(sb, value.opt(i))
                }
                sb.append(']')
            }
            is Map<*, *> -> appendCanon(sb, JSONObject(value))
            is Collection<*> -> appendCanon(sb, JSONArray(value))
            is Number -> {
                val d = value.toDouble()
                sb.append(if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString())
            }
            is Boolean -> sb.append(value.toString())
            else -> sb.append(JSONObject.quote(value.toString()))
        }
    }

    // ------------------------------------------------------------------
    // Block access on the wire
    // ------------------------------------------------------------------

    /**
     * Top-level blocks of an object as generic JSON objects, flattening legacy nested
     * groups ({type:group, children:[…]}) into flat group_id members (doc/3) — the same
     * normalization the Go reference applies before merging, whatever format each side
     * sent.
     */
    fun wireBlocks(props: JSONObject?): List<JSONObject> {
        val arr = props?.optJSONArray(PROP_BLOCKS) ?: return emptyList()
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        return flattenGroups(list)
    }

    private fun flattenGroups(list: List<JSONObject>): List<JSONObject> {
        val nested = list.any { it.optString("type") == "group" }
        if (!nested) return list
        val out = ArrayList<JSONObject>(list.size)
        for (b in list) {
            if (b.optString("type") != "group") {
                out.add(b)
                continue
            }
            val gid = b.optString("id")
            val kids = b.optJSONArray("children")
            if (kids == null || kids.length() == 0) {
                val p = JSONObject()
                for (k in b.keys()) {
                    if (k != "children") p.put(k, b.get(k))
                }
                p.put("type", "paragraph")
                p.put("group_id", gid)
                out.add(p)
                continue
            }
            for (i in 0 until kids.length()) {
                val child = kids.optJSONObject(i) ?: continue
                val c = JSONObject()
                for (k in child.keys()) c.put(k, child.get(k))
                c.put("group_id", gid)
                out.add(c)
            }
        }
        return out
    }

    private fun blockId(b: JSONObject): String = b.optString("id")

    private fun blockIds(blocks: List<JSONObject>): List<String> = blocks.map { blockId(it) }

    private fun stampOf(m: Map<String, String>?, key: String, fallback: String): String =
        m?.get(key)?.takeIf { it.isNotEmpty() } ?: fallback

    /** true when side a wins over side b for a value guarded by stamps ha/hb. */
    private fun pick(ha: String, hb: String, va: Any?, vb: Any?): Boolean {
        val c = HybridLogicalClock.compare(ha, hb)
        if (c != 0) return c > 0
        return canon(va) >= canon(vb)
    }

    private fun maxHlc(a: String, b: String): String =
        if (HybridLogicalClock.compare(a, b) >= 0) a else b

    // ------------------------------------------------------------------
    // Stamp (the client runs this on every save, spec/merge.md §Stamp)
    // ------------------------------------------------------------------

    fun stamp(prev: JSONObject?, next: JSONObject, prevMeta: Meta, now: String): StampResult {
        val field = prevMeta.fieldHlc.toMutableMap()
        val blockH = prevMeta.blockHlc.toMutableMap()
        val deleted = prevMeta.deletedBlocks.toMutableMap()
        var changed = false
        fun mark(m: MutableMap<String, String>, k: String) {
            m[k] = now
            changed = true
        }

        // title is merged together with title_runs (spec/object.md): one field, one stamp.
        val prevTitlePair = JSONArray().put(prev?.opt("title") ?: JSONObject.NULL)
            .put(prev?.opt("title_runs") ?: JSONObject.NULL)
        val nextTitlePair = JSONArray().put(next.opt("title") ?: JSONObject.NULL)
            .put(next.opt("title_runs") ?: JSONObject.NULL)
        if (prev == null || canon(prevTitlePair) != canon(nextTitlePair)) mark(field, "title")
        if (prev == null || canon(prev.opt("type_id")) != canon(next.opt("type_id"))) mark(field, "type_id")
        // trashed_at (the object's tombstone): going to the trash or restoring stamps it.
        if (prev?.optString(FIELD_TRASHED).orEmpty() != next.optString(FIELD_TRASHED)) mark(field, FIELD_TRASHED)

        // Block anchors (yaop-sync/1): copy prev's anchor map, then re-anchor every
        // block whose predecessor changed (new blocks included; a new block's first
        // predecessor "" is itself an anchoring event). Tombstones keep their entry.
        val anchor = HashMap<String, BlockAnchor>()
        if (prev != null) {
            for ((id, a) in prevMeta.blockAnchor) anchor[id] = a
        }
        val prevBlocks = wireBlocks(prev?.optJSONObject("properties"))
        val nextBlocks = wireBlocks(next.optJSONObject("properties"))
        val prevPred = HashMap<String, String>()
        for ((i, b) in prevBlocks.withIndex()) {
            prevPred[blockId(b)] = if (i == 0) "" else blockId(prevBlocks[i - 1])
        }
        for ((i, b) in nextBlocks.withIndex()) {
            val id = blockId(b)
            val pred = if (i == 0) "" else blockId(nextBlocks[i - 1])
            if (!prevPred.containsKey(id) || prevPred[id] != pred) {
                anchor[id] = BlockAnchor(pred, now)
            }
        }

        val prevProps = prev?.optJSONObject("properties") ?: JSONObject()
        val nextProps = next.optJSONObject("properties") ?: JSONObject()
        val keys = (prevProps.keys().asSequence() + nextProps.keys().asSequence()).toMutableSet()
        keys.remove(PROP_BLOCKS)
        for (k in keys) {
            val prevHad = prevProps.has(k)
            val nextHas = nextProps.has(k)
            if (prevHad != nextHas || canon(prevProps.opt(k)) != canon(nextProps.opt(k))) mark(field, PROP_PREFIX + k)
        }

        val prevById = HashMap<String, JSONObject>()
        for (b in prevBlocks) prevById[blockId(b)] = b
        val nextIds = HashSet<String>()
        for (b in nextBlocks) {
            val id = blockId(b)
            nextIds += id
            val old = prevById[id]
            if (old == null || canon(old) != canon(b)) {
                mark(blockH, id)
                deleted.remove(id)
            }
        }
        for (b in prevBlocks) {
            val id = blockId(b)
            if (id !in nextIds) {
                mark(deleted, id)
                blockH.remove(id)
            }
        }
        if (canon(JSONArray(blockIds(prevBlocks))) != canon(JSONArray(blockIds(nextBlocks))) &&
            (prev != null || nextBlocks.isNotEmpty())
        ) {
            mark(field, "order")
        }

        return StampResult(
            Meta(field, blockH, deleted, anchor),
            changed,
            if (changed) now else (prev?.optString("hlc") ?: ""),
        )
    }

    // ------------------------------------------------------------------
    // Merge (deterministic and symmetric, spec/merge.md §Merge)
    // ------------------------------------------------------------------

    /**
     * Merges two wire versions of the same object. Pure function of the pair — the result
     * never depends on which side came first or on the grouping of a three-way merge.
     */
    fun merge(aIn: JSONObject, bIn: JSONObject): JSONObject {
        val a = JSONObject(aIn.toString())
        val b = JSONObject(bIn.toString())
        val out = JSONObject(a.toString())

        val ah = a.optString("hlc")
        val bh = b.optString("hlc")
        val aField = metaFromJson(a.optJSONObject("field_hlc"))
        val bField = metaFromJson(b.optJSONObject("field_hlc"))

        // 1. title / type_id
        val htA = stampOf(aField, "title", ah)
        val htB = stampOf(bField, "title", bh)
        if (!pick(htA, htB, pairArr(a), pairArr(b))) {
            out.put("title", b.opt("title") ?: JSONObject.NULL)
            putOrRemove(out, "title_runs", b)
        }
        val hyA = stampOf(aField, "type_id", ah)
        val hyB = stampOf(bField, "type_id", bh)
        if (!pick(hyA, hyB, a.optString("type_id"), b.optString("type_id"))) {
            out.put("type_id", b.opt("type_id") ?: JSONObject.NULL)
        }

        // 2. properties (everything except blocks)
        val propsA = a.optJSONObject("properties") ?: JSONObject()
        val propsB = b.optJSONObject("properties") ?: JSONObject()
        val keys = HashSet<String>()
        for (k in propsA.keys()) keys += k
        for (k in propsB.keys()) keys += k
        for (m in listOf(aField, bField)) {
            for (k in m.keys) {
                if (k.startsWith(PROP_PREFIX) && k.length > PROP_PREFIX.length) {
                    keys += k.substring(PROP_PREFIX.length)
                }
            }
        }
        keys.remove(PROP_BLOCKS)
        val props = JSONObject()
        for (k in keys) {
            val hasA = propsA.has(k)
            val hasB = propsB.has(k)
            val va = propsA.opt(k)
            val vb = propsB.opt(k)
            val sa = aField["$PROP_PREFIX$k"]
            val sb = bField["$PROP_PREFIX$k"]
            val aKnows = hasA || sa != null
            val bKnows = hasB || sb != null
            val effSa = sa ?: ah
            val effSb = sb ?: bh
            var winnerHas = false
            var winnerVal: Any? = null
            when {
                aKnows && !bKnows -> { winnerHas = hasA; winnerVal = va }
                bKnows && !aKnows -> { winnerHas = hasB; winnerVal = vb }
                else -> if (pick(effSa, effSb, presence(hasA, va), presence(hasB, vb))) {
                    winnerHas = hasA; winnerVal = va
                } else {
                    winnerHas = hasB; winnerVal = vb
                }
            }
            if (winnerHas) props.put(k, winnerVal ?: JSONObject.NULL)
        }

        // 3. blocks: per id, candidate from the side with the greater block stamp
        //    (tie → canonically greater JSON); a tombstone at or after that stamp kills it.
        val blocksA = wireBlocks(a.optJSONObject("properties"))
        val blocksB = wireBlocks(b.optJSONObject("properties"))
        val byA = HashMap<String, JSONObject>()
        val byB = HashMap<String, JSONObject>()
        for (blk in blocksA) byA[blockId(blk)] = blk
        for (blk in blocksB) byB[blockId(blk)] = blk
        val aBlockHlc = metaFromJson(a.optJSONObject("block_hlc"))
        val bBlockHlc = metaFromJson(b.optJSONObject("block_hlc"))
        val tomb = HashMap<String, String>()
        for (m in listOf(metaFromJson(a.optJSONObject("deleted_blocks")), metaFromJson(b.optJSONObject("deleted_blocks")))) {
            for ((id, h) in m) tomb[id] = maxHlc(tomb[id] ?: "", h)
        }
        val kept = LinkedHashMap<String, JSONObject>()
        val keptHlc = HashMap<String, String>()
        for (id in unionIds(blockIds(blocksA), blockIds(blocksB))) {
            val ba = byA[id]
            val bb = byB[id]
            var cand: JSONObject? = null
            var ch = ""
            when {
                ba != null && bb == null -> { cand = ba; ch = stampOf(aBlockHlc, id, ah) }
                bb != null && ba == null -> { cand = bb; ch = stampOf(bBlockHlc, id, bh) }
                ba != null && bb != null -> {
                    val ha = stampOf(aBlockHlc, id, ah)
                    val hb = stampOf(bBlockHlc, id, bh)
                    if (pick(ha, hb, ba, bb)) { cand = ba; ch = ha } else { cand = bb; ch = hb }
                }
            }
            if (cand != null) {
                val t = tomb[id]
                if (t != null && HybridLogicalClock.compare(t, ch) >= 0) continue
                kept[id] = cand
                keptHlc[id] = ch
            }
        }

        // 4. order (anchors first; whole-sequence legacy rule only for /2 data)
        val oa = stampOf(aField, "order", ah)
        val ob = stampOf(bField, "order", bh)
        var primary = blockIds(blocksA)
        var secondary = blockIds(blocksB)
        val primaryIsA = pick(oa, ob, primary, secondary)
        if (!primaryIsA) {
            val tmp = primary; primary = secondary; secondary = tmp
        }
        val anchors = mergeBlockAnchors(
            anchorsFromJson(a.optJSONObject("block_anchor")),
            anchorsFromJson(b.optJSONObject("block_anchor")),
        )
        val order: List<String> = if (anchorComplete(anchors, kept)) {
            renderAnchorOrder(anchors, kept)
        } else {
            orderLegacy(primary, secondary, kept, keptHlc)
        }
        val mergedBlocks = JSONArray()
        for (id in order) mergedBlocks.put(kept[id])
        if (blocksA.isNotEmpty() || blocksB.isNotEmpty() ||
            (a.optJSONObject("properties")?.has(PROP_BLOCKS) == true) ||
            (b.optJSONObject("properties")?.has(PROP_BLOCKS) == true)
        ) {
            props.put(PROP_BLOCKS, mergedBlocks)
        }
        out.put("properties", props)

        // 5. meta
        putMeta(out, "field_hlc", maxMerge(aField, bField))
        putMeta(out, "block_hlc", keptHlc)
        val deletedOut = HashMap<String, String>()
        for ((id, h) in tomb) {
            if (!kept.containsKey(id)) deletedOut[id] = h
        }
        putMeta(out, "deleted_blocks", deletedOut)
        putAnchors(out, "block_anchor", anchors)

        // 6. rest
        out.put("hlc", maxHlc(ah, bh).takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
        if (out.isNull("hlc")) out.remove("hlc")
        out.put("created_at", minNonEmpty(a.optString("created_at"), b.optString("created_at")))
        // 2b. trashed_at: LWW by field_hlc.trashed_at with the property participation rule, so a
        // later restore beats an earlier trash and an unaware edit never resurrects (spec/merge.md).
        run {
            val va = a.optString(FIELD_TRASHED)
            val vb = b.optString(FIELD_TRASHED)
            val hasA = va.isNotEmpty()
            val hasB = vb.isNotEmpty()
            val sa = aField[FIELD_TRASHED]
            val sb = bField[FIELD_TRASHED]
            val aKnows = hasA || sa != null
            val bKnows = hasB || sb != null
            val winner = when {
                aKnows && !bKnows -> va
                bKnows && !aKnows -> vb
                aKnows && bKnows ->
                    if (pick(sa ?: ah, sb ?: bh, presence(hasA, va), presence(hasB, vb))) va else vb
                else -> ""
            }
            if (winner.isNotEmpty()) out.put(FIELD_TRASHED, winner) else out.remove(FIELD_TRASHED)
        }
        if (primaryIsA) {
            putOrRemove(out, "content", a)
        } else {
            putOrRemove(out, "content", b)
        }
        out.put("updated_at", if (primaryIsA) a.optString("updated_at") else b.optString("updated_at"))
        // schema_version merges by MAX (spec/object.md): associative, commutative, idempotent.
        val schema = maxOf(a.optInt("schema_version", 0), b.optInt("schema_version", 0))
        if (schema > 0) out.put("schema_version", schema) else out.remove("schema_version")
        // References are derived from the merged content, never unioned.
        out.put("links", JSONArray(deriveLinks(out)))
        val propertyLinks = filterPresent(
            unionSorted(
                stringsOf(a.optJSONArray("property_links")),
                stringsOf(b.optJSONArray("property_links")),
            ),
            out.optJSONObject("properties"),
        )
        if (propertyLinks.isNotEmpty()) out.put("property_links", JSONArray(propertyLinks)) else out.remove("property_links")
        return out
    }

    private fun pairArr(o: JSONObject): JSONArray =
        JSONArray().put(o.opt("title") ?: JSONObject.NULL).put(o.opt("title_runs") ?: JSONObject.NULL)

    private fun putOrRemove(out: JSONObject, key: String, src: JSONObject) {
        if (src.has(key) && !src.isNull(key) && src.optString(key).isNotEmpty()) {
            out.put(key, src.opt(key))
        } else {
            out.remove(key)
        }
    }

    private fun presence(has: Boolean, v: Any?): Any? = if (!has) null else JSONArray(listOf(v ?: JSONObject.NULL))

    private fun unionIds(a: List<String>, b: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (list in listOf(a, b)) {
            for (id in list) {
                if (id.isNotEmpty() && seen.add(id)) out += id
            }
        }
        return out
    }

    private fun unionSorted(a: List<String>, b: List<String>): List<String> {
        val ids = (unionIds(a, b)).sorted()
        return ids
    }

    private fun stringsOf(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    private fun minNonEmpty(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        a < b -> a
        else -> b
    }

    private fun putMeta(out: JSONObject, key: String, m: Map<String, String>) {
        if (m.isEmpty()) {
            out.remove(key)
        } else {
            out.put(key, metaToJson(m))
        }
    }

    fun metaToJson(m: Map<String, String>): JSONObject = JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }

    fun metaFromJson(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotEmpty() }
    }

    fun anchorsFromJson(o: JSONObject?): Map<String, BlockAnchor> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, BlockAnchor>()
        for (k in o.keys()) {
            val e = o.optJSONObject(k) ?: continue
            if (e.optString("hlc").isNotEmpty()) out[k] = BlockAnchor(e.optString("after"), e.optString("hlc"))
        }
        return out
    }

    private fun putAnchors(out: JSONObject, key: String, m: Map<String, BlockAnchor>) {
        if (m.isEmpty()) {
            out.remove(key)
        } else {
            val o = JSONObject()
            for ((id, a) in m) o.put(id, JSONObject().put("after", a.after).put("hlc", a.hlc))
            out.put(key, o)
        }
    }

    private fun maxMerge(a: Map<String, String>, b: Map<String, String>): Map<String, String> {
        if (a.isEmpty() && b.isEmpty()) return emptyMap()
        val out = HashMap<String, String>(a)
        for ((k, v) in b) out[k] = maxHlc(out[k] ?: "", v)
        return out
    }

    // ------------------------------------------------------------------
    // Block anchors (yaop-sync/1, spec/merge.md): associative ordering
    // ------------------------------------------------------------------

    /**
     * Unions two anchor maps. For each block id the anchoring event with the greater HLC
     * wins (tie → canonically greater "after"). The inputs form a closed, per-key set, so
     * successive merges reduce to the per-key max: the result never depends on the merge
     * grouping. That is what makes merge associative.
     */
    fun mergeBlockAnchors(a: Map<String, BlockAnchor>, b: Map<String, BlockAnchor>): Map<String, BlockAnchor> {
        if (a.isEmpty() && b.isEmpty()) return emptyMap()
        val out = HashMap<String, BlockAnchor>(a)
        for ((id, y) in b) {
            val x = out[id]
            if (x == null) {
                out[id] = y
            } else if (anchorLess(x, y)) {
                out[id] = y
            }
        }
        return out
    }

    /** x's anchoring event loses to y's: greater HLC wins, ties fall back to the canonically greater "after". */
    private fun anchorLess(x: BlockAnchor, y: BlockAnchor): Boolean {
        val c = HybridLogicalClock.compare(x.hlc, y.hlc)
        if (c != 0) return c < 0
        return canon(x.after) < canon(y.after)
    }

    /** Every kept block knows its anchor? One /2 side without anchors sends the merge down the legacy path. */
    private fun anchorComplete(anchors: Map<String, BlockAnchor>, kept: Map<String, JSONObject>): Boolean {
        for (id in kept.keys) {
            if (!anchors.containsKey(id)) return false
        }
        return true
    }

    /**
     * Renders the merged block order as an RGA forest:
     *   - the parent of a block is the block it was last anchored after; unknown parents root it;
     *   - tombstones keep their node (dead blocks hold their slot for their followers);
     *   - siblings under one anchor sort newest anchoring first (RGA: newer inserts stick
     *     closer to the anchor), tie by canonically greater id; roots sort the same way;
     *   - anchor cycles (concurrent moves pointing at each other) break by re-rooting the
     *     smallest-stamped member of the cycle (tie: canonically smallest id).
     *
     * The render is a pure function of the merged anchor map plus the alive set, so its
     * output does not depend on how states were grouped.
     */
    private fun renderAnchorOrder(anchors: Map<String, BlockAnchor>, alive: Map<String, JSONObject>): List<String> {
        if (alive.isEmpty()) return emptyList()
        val nodeSet = LinkedHashSet<String>()
        nodeSet.addAll(anchors.keys)
        nodeSet.addAll(alive.keys)
        val parent = HashMap<String, String>()
        val children = HashMap<String, MutableList<String>>()
        val roots = ArrayList<String>()
        for (id in nodeSet) {
            val an = anchors[id]
            var p = ""
            if (an != null && an.after.isNotEmpty() && nodeSet.contains(an.after)) {
                p = an.after
            }
            parent[id] = p
            if (p.isEmpty()) {
                roots += id
            } else {
                children.getOrPut(p) { ArrayList() }.add(id)
            }
        }
        breakCycles(parent, children, roots, anchors)
        val out = ArrayList<String>(alive.size)
        fun walk(id: String) {
            if (alive.containsKey(id)) out += id
            for (child in byAnc(children[id] ?: emptyList(), anchors)) walk(child)
        }
        for (id in byAnc(roots, anchors)) walk(id)
        return out
    }

    /** Siblings and roots sort newest anchoring first (RGA: newer inserts stick to the anchor); ties by canonically greater id. */
    private fun byAnc(ids: List<String>, anchors: Map<String, BlockAnchor>): List<String> =
        ids.sortedWith { x, y ->
            val hx = anchors[x]?.hlc ?: ""
            val hy = anchors[y]?.hlc ?: ""
            val c = HybridLogicalClock.compare(hx, hy)
            if (c != 0) {
                -c
            } else {
                -canon(x).compareTo(canon(y))
            }
        }

    /**
     * Walks the parent pointers and re-roots, for every cycle, its smallest-stamped member
     * (tie: canonically smallest id). Cycles in a parent-pointer graph are disjoint, so
     * each resolves on its own. The scan restarts in sorted key order after each break,
     * keeping the whole step deterministic.
     */
    private fun breakCycles(
        parent: HashMap<String, String>,
        children: HashMap<String, MutableList<String>>,
        roots: MutableList<String>,
        anchors: Map<String, BlockAnchor>,
    ) {
        while (true) {
            val keys = parent.keys.sorted()
            var broke = false
            for (start in keys) {
                if (parent[start].orEmpty().isEmpty()) continue
                val path = ArrayList<String>()
                val idx = HashMap<String, Int>()
                var cur = start
                var cycle: List<String>? = null
                while (!parent[cur].orEmpty().isEmpty()) {
                    val at = idx[cur]
                    if (at != null) {
                        cycle = path.subList(at, path.size)
                        break
                    }
                    idx[cur] = path.size
                    path += cur
                    cur = parent[cur]!!
                }
                if (cycle == null) continue
                var worst = cycle[0]
                for (id in cycle.subList(1, cycle.size)) {
                    if (anchorEventLess(id, worst, anchors)) worst = id
                }
                val oldParent = parent[worst]
                if (oldParent != null) {
                    children[oldParent]?.remove(worst)
                }
                parent[worst] = ""
                roots += worst
                broke = true
                break
            }
            if (!broke) return
        }
    }

    /** Compares two nodes by their anchoring event (HLC, then canonically smaller id first). */
    private fun anchorEventLess(a: String, b: String, anchors: Map<String, BlockAnchor>): Boolean {
        val ha = anchors[a]?.hlc ?: ""
        val hb = anchors[b]?.hlc ?: ""
        val c = HybridLogicalClock.compare(ha, hb)
        if (c != 0) return c < 0
        return canon(a) < canon(b)
    }

    /**
     * The yaop-sync/1 whole-sequence rule, kept only for data that does not carry
     * anchors yet: the reordered side with the greater "order" stamp provides the base
     * sequence; blocks only the other side knows are inserted after their closest
     * predecessor there and, at the same anchor, newer inserts stay closer to it.
     * NOT associative across three states in general.
     */
    private fun orderLegacy(
        primary: List<String>,
        secondary: List<String>,
        kept: Map<String, JSONObject>,
        keptHlc: Map<String, String>,
    ): List<String> {
        val order = ArrayList<String>()
        val inOrder = HashSet<String>()
        for (id in primary) {
            if (kept.containsKey(id) && !inOrder.contains(id)) {
                order += id
                inOrder += id
            }
        }
        val inSecondary = HashSet(secondary)
        for ((i, id) in secondary.withIndex()) {
            if (!kept.containsKey(id) || inOrder.contains(id)) continue
            var pos = 0
            for (j in i - 1 downTo 0) {
                val idx = order.indexOf(secondary[j])
                if (idx >= 0) {
                    pos = idx + 1
                    break
                }
            }
            // Concurrent inserts at the same anchor (RGA): the newer one stays closer to the anchor.
            while (pos < order.size && !inSecondary.contains(order[pos]) &&
                HybridLogicalClock.compare(keptHlc[order[pos]] ?: "", keptHlc[id] ?: "") > 0
            ) {
                pos++
            }
            order.add(pos, id)
            inOrder += id
        }
        return order
    }

    /** Keeps the property ids that still appear as a value (or inside an array) of props. */
    private fun filterPresent(ids: List<String>, props: JSONObject?): List<String> {
        if (ids.isEmpty()) return emptyList()
        val present = HashSet<String>()
        fun mark(s: String) {
            if (s.isEmpty()) return
            present += s
            present += tagKey(s) // tag names reference their tag object
            if (s.length >= 10 && s[4] == '-' && s[7] == '-') {
                present += "daily-" + s.substring(0, 10) // date properties reference their journal day
            }
        }
        val p = props ?: JSONObject()
        for (k in p.keys()) {
            if (k == PROP_BLOCKS) continue
            when (val v = p.get(k)) {
                is String -> mark(v)
                is JSONArray -> for (i in 0 until v.length()) {
                    val s = v.optString(i, "")
                    if (s.isNotEmpty()) mark(s)
                }
            }
        }
        return ids.filter { present.contains(it) || present.contains(it.removePrefix("tag_")) }.sorted()
    }

    /** `links` rule of spec/object.md on the wire object (same algorithm as Go DeriveLinks). */
    fun deriveLinks(wire: JSONObject): List<String> {
        val self = wire.optString("id")
        val out = sortedSetOf<String>()
        fun add(id: String?) {
            if (!id.isNullOrEmpty() && id != self) out += id
        }
        fun walkRuns(runs: JSONArray?) {
            if (runs == null) return
            for (i in 0 until runs.length()) {
                val r = runs.optJSONObject(i) ?: continue
                val oid = r.optString("object_id")
                if (oid.isNotEmpty()) { add(oid); continue }
                val tagId = r.optString("tag_id")
                if (tagId.isNotEmpty()) { add(tagId); continue }
                val tagName = r.optString("tag_name")
                if (tagName.isNotEmpty()) { add(tagKey(tagName)); continue }
                val date = r.optString("date")
                if (date.length >= 10) add("daily-" + date.substring(0, 10))
            }
        }
        fun wireBlocksOfRaw(arr: JSONArray?): List<JSONObject> {
            if (arr == null) return emptyList()
            return flattenGroups((0 until arr.length()).mapNotNull { arr.optJSONObject(it) })
        }
        fun walkBlocks(blocks: List<JSONObject>) {
            for (b in blocks) {
                walkRuns(b.optJSONArray("runs"))
                when (b.optString("type")) {
                    "embed", "bookmark" -> add(b.optString("object_id"))
                    "image", "audio", "pdf" -> b.optString("asset_id").takeIf { it.isNotEmpty() }?.let { add("media_$it") }
                }
                if (b.has("children")) walkBlocks(wireBlocksOfRaw(b.optJSONArray("children")))
            }
        }
        walkRuns(wire.optJSONArray("title_runs"))
        walkBlocks(wireBlocks(wire.optJSONObject("properties")))
        return out.toList()
    }

    /** Canonical tag object id for a tag name (spec/object.md); same as Go TagKey and C++ tagKey. */
    fun tagKey(name: String): String {
        val n = name.trim().removePrefix("#").trim().lowercase().replace(" ", "_")
        return "tag_" + n.filter { it.isLetterOrDigit() || it == '_' }
    }
}
