package org.yaop.core.sync

import org.yaop.core.model.AssetMeta
import org.yaop.core.model.Block
import org.yaop.core.model.CalloutType
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.HeadingLevel
import org.yaop.core.model.HybridLogicalClock
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.CoverSpec
import org.yaop.core.model.ObjectIcon
import org.yaop.core.model.ObjectType
import org.yaop.core.model.PropertyDefinition
import org.yaop.core.model.SelectOption
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.RichSpanEngine
import org.yaop.core.model.TextMark
import org.yaop.core.model.toAlphabetic
import org.yaop.core.model.toPlainText
import org.yaop.core.model.toRoman
import org.yaop.core.model.toTextSpan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Bidirectional codec between [PkmObject] and the canonical server JSON wire format
 * defined in `contracts/SYNC.md` (with YAOP contract extensions for `roman_list`, `callout`,
 * media blocks, `bookmark`, named colors, and flat `group` / `toggle`).
 */
object SyncContractCodec {

    fun blocksToJsonArray(blocks: List<Block>): JsonArray = blocksToJsonElement(blocks)

    fun jsonArrayToBlocks(array: JsonArray): List<Block> = jsonElementToBlocks(array)

    fun blocksToStructuredJsonString(blocks: List<Block>): String = blocksToJsonElement(blocks).toString()

    fun structuredJsonStringToBlocks(raw: String): List<Block> =
        runCatching { jsonElementToBlocks(Json.parseToJsonElement(raw).jsonArray) }
            .getOrElse { listOf(Block.Paragraph(id = newBlockId(), spans = emptyList())) }

    /** Wire property keys with dedicated mapping (spec/object.md); never generic properties. */
    private val SPECIAL_KEYS = setOf("blocks", "pinned", "is_template", "summary", "icon", "cover", "aliases")

    /**
     * Keys of a saved set (spec/query.md): structured JSON the generic mapping must not reinterpret
     * (a list of view objects is not a relation). Skipped here, so export re-emits them raw.
     */
    val SET_KEYS = setOf("query", "views", "active_view")

    /** Generic property mapping (wire → model). Values the model cannot represent are skipped (passthrough). */
    fun parseWireProps(rawProps: JSONObject, typeId: String): Map<String, PropertyValue> {
        val mappedProps = mutableMapOf<String, PropertyValue>()
        val keys = rawProps.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            if (rawProps.isNull(rawKey) || rawKey in SPECIAL_KEYS || rawKey in SET_KEYS) continue
            val key = rawKey
            when (val v = rawProps.get(rawKey)) {
                is Boolean -> mappedProps[key] = PropertyValue.Checkbox(v)
                is Number -> mappedProps[key] = PropertyValue.Number(v.toDouble(), isRating = key == "rating")
                is JSONArray -> {
                    val items = (0 until v.length()).mapNotNull { idx -> v.optString(idx).takeIf { it.isNotBlank() } }
                    mappedProps[key] = if (key == "tags" || key == "habit_days") PropertyValue.MultiSelect(items) else PropertyValue.ObjectRelation(items)
                }
                is String -> mappedProps[key] = when (key) {
                    "date", "due_date" -> PropertyValue.DateRange(v)
                    "status" -> PropertyValue.Select(
                        if (typeId == DefaultTypes.TASK.id) DefaultTypes.normalizeStatusOption(v) else v,
                    )
                    "priority" -> PropertyValue.Select(DefaultTypes.normalizePriorityOption(v))
                    "medium" -> PropertyValue.Select(v)
                    "url" -> PropertyValue.Url(v)
                    "habit_kind", "habit_freq", "habit_slot", "habit_state" -> PropertyValue.Select(v)
                    "habit_start" -> PropertyValue.DateRange(v)
                    else -> if (key.startsWith("d:") && v == "skip") PropertyValue.Select(v) else PropertyValue.RichText(listOf(InlineSpan.Text(v)))
                }
                else -> Unit // JSON objects (e.g. date ranges with end) stay raw: passthrough
            }
        }
        return mappedProps
    }

    private fun encodeProp(value: PropertyValue): Any? = when (value) {
        is PropertyValue.RichText -> wireText(value.spans)
        is PropertyValue.Number -> value.value
        is PropertyValue.Checkbox -> value.checked
        is PropertyValue.Select -> value.selectedOptionId
        is PropertyValue.MultiSelect -> JSONArray(value.selectedOptionIds)
        is PropertyValue.DateRange -> if (value.endDate.isNullOrBlank()) value.startDate else
            JSONObject().put("start", value.startDate).put("end", value.endDate)
        is PropertyValue.ObjectRelation -> JSONArray(value.targetObjectIds)
        is PropertyValue.Url -> value.url
        is PropertyValue.Media -> value.assetId
    }

    fun iconToWire(icon: ObjectIcon?): JSONObject? {
        if (icon == null || (icon.emoji.isNullOrBlank() && icon.iconName.isNullOrBlank())) return null
        return JSONObject().apply {
            icon.emoji?.takeIf { it.isNotBlank() }?.let { put("emoji", it) }
            icon.iconName?.takeIf { it.isNotBlank() }?.let { put("icon_name", it) }
            icon.colorHex?.takeIf { it.isNotBlank() }?.let { put("color_hex", it) }
        }
    }

    fun iconFromWire(o: JSONObject?): ObjectIcon? {
        if (o == null) return null
        val icon = ObjectIcon(
            emoji = o.optString("emoji").takeIf { it.isNotBlank() },
            iconName = o.optString("icon_name").takeIf { it.isNotBlank() },
            colorHex = o.optString("color_hex").takeIf { it.isNotBlank() },
        )
        return if (icon.emoji == null && icon.iconName == null) null else icon
    }

    fun coverToWire(cover: CoverSpec?): JSONObject? {
        if (cover == null || cover.assetId.isBlank()) return null
        return JSONObject().apply {
            put("asset_id", cover.assetId)
            if (cover.cropOffsetY != 0.5f) put("crop_offset_y", wireFraction(cover.cropOffsetY))
            if (cover.isFit) put("fit", true)
        }
    }

    fun coverFromWire(o: JSONObject?): CoverSpec? {
        val assetId = o?.optString("asset_id").orEmpty()
        if (assetId.isBlank()) return null
        return CoverSpec(
            assetId = assetId,
            cropOffsetY = o!!.optDouble("crop_offset_y", 0.5).toFloat(),
            isFit = o.optBoolean("fit", false),
        )
    }

    /** title_runs only when the title carries something beyond plain text (spec/object.md). */
    private fun titleRunsOrNull(spans: List<InlineSpan>): JSONArray? {
        val norm = RichSpanEngine.upgradeLegacySpans(spans)
        val plain = norm.isEmpty() || (norm.size == 1 && norm[0].let {
            it is InlineSpan.Text && it.marks.isEmpty() && it.linkUrl == null && it.textColor == null && it.backgroundColor == null
        })
        return if (plain) null else JSONArray(spansToRunsJsonElement(norm).toString())
    }

    /** Object-ref / tag / date-property targets, the property half of spec/object.md links. */
    private fun propertyLinks(obj: PkmObject): List<String> {
        val out = sortedSetOf<String>()
        for ((rawKey, value) in obj.properties) {
            val key = rawKey
            when (value) {
                is PropertyValue.ObjectRelation -> value.targetObjectIds.filter { it.isNotBlank() }.forEach { out += it }
                is PropertyValue.MultiSelect -> if (key == "tags") value.selectedOptionIds.forEach { raw ->
                    val clean = raw.trim().removePrefix("#")
                    if (clean.isNotBlank()) out += if (clean.startsWith("tag_")) clean else MergeContract.tagKey(clean)
                }
                is PropertyValue.DateRange -> value.startDate.removePrefix("@").trim().take(10)
                    .takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }?.let { out += "daily-$it" }
                else -> Unit
            }
        }
        out.remove(obj.id)
        return out.toList()
    }

    fun toServerJson(obj: PkmObject): JSONObject {
        val normalized = DefaultTypes.normalizeObject(obj)
        val raw = normalized.wireProps?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val rawParsed = parseWireProps(raw, normalized.typeId)
        val current = normalized.properties.mapKeys { it.key }
        val propsJson = JSONObject()

        // 1. Raw keys: unchanged → exact raw value; edited → re-encoded; deleted → dropped;
        //    not representable by the model → passthrough untouched.
        for (rawKey in raw.keys().asSequence().toList()) {
            if (rawKey in SPECIAL_KEYS) continue
            val key = rawKey
            val parsed = rawParsed[key]
            val cur = current[key]
            when {
                parsed == null && cur == null -> propsJson.put(key, raw.get(rawKey))
                cur == null -> Unit
                cur == parsed -> propsJson.put(key, raw.get(rawKey))
                else -> encodeProp(cur)?.let { propsJson.put(key, it) }
            }
        }
        // 2. Properties created on this device.
        for ((key, cur) in current) {
            if (!propsJson.has(key) && !rawParsed.containsKey(key)) encodeProp(cur)?.let { propsJson.put(key, it) }
        }
        // 3. Special keys.
        if (normalized.isTemplate) propsJson.put("is_template", true)
        if (normalized.isPinned) propsJson.put("pinned", true)
        wireText(normalized.summary).takeIf { it.isNotBlank() }?.let { propsJson.put("summary", it) }
        val rawIcon = raw.optJSONObject("icon")
        if (normalized.icon != null) {
            propsJson.put("icon", if (rawIcon != null && iconFromWire(rawIcon) == normalized.icon) rawIcon else iconToWire(normalized.icon))
        }
        val rawCover = raw.optJSONObject("cover")
        if (normalized.cover != null) {
            propsJson.put("cover", if (rawCover != null && coverFromWire(rawCover) == normalized.cover) rawCover else coverToWire(normalized.cover))
        }
        if (normalized.aliases.isNotEmpty()) propsJson.put("aliases", JSONArray(normalized.aliases))
        propsJson.put("blocks", passthroughUnknownBlockKeys(blocksToStructuredJson(normalized.blocks), raw.optJSONArray("blocks")))

        val json = JSONObject().apply {
            put("id", normalized.id)
            put("type_id", normalized.typeId)
            put("title", wireText(normalized.title))
            titleRunsOrNull(normalized.title)?.let { put("title_runs", it) }
            // `content` (markdown per object) is not part of the contract (spec/object.md):
            // it is never emitted or rebuilt; if it arrives, it travels as passthrough.
            put("properties", propsJson)
            val propLinks = propertyLinks(normalized)
            if (propLinks.isNotEmpty()) put("property_links", JSONArray(propLinks))
            put("created_at", normalized.createdAt)
            put("updated_at", normalized.updatedAt)
            put("revision", normalized.revision)
            put("sync_status", "synced")
            put("hlc", normalized.hlc)
            // schema_version 1 = yaop-doc/1 + block_anchor (spec/object.md): every saved object
            // declares it; the merge takes the maximum.
            put("schema_version", SCHEMA_VERSION)
            if (!normalized.syncError.isNullOrBlank()) {
                put("sync_error", normalized.syncError)
            }
            if (normalized.incarnation.isNotBlank()) {
                put("incarnation", normalized.incarnation)
            }
            if (!normalized.trashedAt.isNullOrBlank()) {
                put("trashed_at", normalized.trashedAt)
            }
            if (normalized.fieldHlc.isNotEmpty()) put("field_hlc", MergeContract.metaToJson(normalized.fieldHlc))
            if (normalized.blockHlc.isNotEmpty()) put("block_hlc", MergeContract.metaToJson(normalized.blockHlc))
            if (normalized.deletedBlocks.isNotEmpty()) put("deleted_blocks", MergeContract.metaToJson(normalized.deletedBlocks))
            if (normalized.blockAnchor.isNotEmpty()) {
                val anchors = JSONObject()
                normalized.blockAnchor.forEach { (id, a) ->
                    anchors.put(id, JSONObject().put("after", a.after).put("hlc", a.hlc))
                }
                put("block_anchor", anchors)
            }
            if (normalized.customPropertyDefs.isNotEmpty()) {
                put("custom_property_defs", JSONArray(customPropertyDefsToWire(normalized.customPropertyDefs)))
            }
        }
        json.put("links", JSONArray(MergeContract.deriveLinks(json)))
        return json
    }

    fun fromServerJson(
        raw: JSONObject,
        existing: PkmObject? = null,
        isActivelyEditing: Boolean = false,
    ): PkmObject {
        val id = raw.optString("id", existing?.id.orEmpty())
        val typeId = raw.optString("type_id", existing?.typeId ?: DefaultTypes.NOTE.id)
        val titleStr = raw.optString("title", existing?.plainTitle.orEmpty())
        val titleRuns = raw.optJSONArray("title_runs")
        val remoteTitle: List<InlineSpan> = when {
            titleRuns != null && titleRuns.length() > 0 ->
                runsJsonElementToSpans(Json.parseToJsonElement(titleRuns.toString()) as JsonArray)
            titleStr.isNotEmpty() -> listOf(InlineSpan.Text(titleStr))
            else -> emptyList()
        }
        val contentMd = raw.optString("content", "")
        val rawProps = raw.optJSONObject("properties") ?: JSONObject()
        val mappedProps = parseWireProps(rawProps, typeId)
        val wirePropsRaw = rawProps.toString() // includes raw blocks for unknown-key passthrough

        val structuredBlocksJson = rawProps.optJSONArray("blocks")
        // Never invent blocks (a random placeholder id looks like a new block on every save);
        // the editor adds its own empty paragraph when it needs one.
        val parsedBlocks = when {
            structuredBlocksJson != null && structuredBlocksJson.length() > 0 -> structuredJsonToBlocks(structuredBlocksJson)
            contentMd.isNotBlank() -> markdownToBlocks(contentMd)
            else -> emptyList()
        }

        val remoteUpdatedAt = raw.optString("updated_at", existing?.updatedAt.orEmpty())
        val rawHlc = raw.optString("hlc").takeIf { it.isNotBlank() }
        // The wire hlc is kept VERBATIM as the object's hlc (otherwise each platform would rebase
        // it with its local clock and digests would diverge). receive() is called only for its
        // effect on the local HLC clock, so the next local stamps beat the remote one.
        val remoteHlc = if (rawHlc != null) {
            HybridLogicalClock.receive(rawHlc)
            rawHlc
        } else {
            HybridLogicalClock.receive(existing?.hlc ?: HybridLogicalClock.now())
        }

        // R3: a pending local version is never replaced, whatever the HLCs say. Its edits only
        // exist here until the push lands; the server merges both sides (spec/merge.md) and the
        // merged object comes back in a later pull, once this one is synced.
        val isLocalPendingAndNewer = existing != null &&
            (isActivelyEditing || existing.syncStatus == "pending" || existing.syncStatus == "local")

        val resolvedBlocks = if (isLocalPendingAndNewer) existing.blocks else parsedBlocks
        val resolvedTitle = if (isLocalPendingAndNewer) existing.title else remoteTitle
        val resolvedSummary = if (isLocalPendingAndNewer) {
            existing.summary
        } else {
            rawProps.optString("summary", existing?.plainSummary.orEmpty())
                .takeIf { it.isNotBlank() }
                ?.toTextSpan()
                .orEmpty()
        }
        val resolvedSyncStatus = if (isLocalPendingAndNewer) "pending" else "synced"

        // schema_version > known → explicit rejection, NEVER a silent best effort
        // (spec/object.md): an object the codecs cannot faithfully decode is not decoded.
        val wireSchemaVersion = raw.optInt("schema_version", 0)
        val resolvedSchemaVersion = if (isLocalPendingAndNewer) existing?.schemaVersion ?: 0 else wireSchemaVersion
        check(resolvedSchemaVersion <= SCHEMA_VERSION) {
            "object $id carries schema_version $wireSchemaVersion; this device only knows $SCHEMA_VERSION — update the app"
        }

        val customDefs = if (isLocalPendingAndNewer) {
            existing?.customPropertyDefs ?: parseCustomPropertyDefsFromWire(raw.optJSONArray("custom_property_defs"), existing?.customPropertyDefs)
        } else {
            parseCustomPropertyDefsFromWire(raw.optJSONArray("custom_property_defs"), existing?.customPropertyDefs)
        }
        val remoteTrashedAt = raw.optString("trashed_at").takeIf { it.isNotBlank() }
        // The wire is already merged (spec/merge.md §2b): absent trashed_at means restored. A pending
        // local version keeps its own value until its push lands (same as fieldHlc below).
        val resolvedTrashedAt = if (isLocalPendingAndNewer) existing.trashedAt else remoteTrashedAt

        return PkmObject(
            id = id,
            typeId = typeId,
            title = resolvedTitle,
            aliases = if (isLocalPendingAndNewer) existing.aliases else
                rawProps.optJSONArray("aliases")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty(),
            summary = resolvedSummary,
            icon = if (isLocalPendingAndNewer) existing.icon else iconFromWire(rawProps.optJSONObject("icon")),
            cover = if (isLocalPendingAndNewer) existing.cover else coverFromWire(rawProps.optJSONObject("cover")),
            properties = if (isLocalPendingAndNewer) existing.properties else mappedProps,
            wireProps = if (isLocalPendingAndNewer) existing.wireProps else wirePropsRaw,
            customPropertyDefs = customDefs,
            blocks = resolvedBlocks,
            isTemplate = rawProps.optBoolean("is_template", existing?.isTemplate ?: false),
            isPinned = rawProps.optBoolean("pinned", existing?.isPinned ?: false),
            createdAt = raw.optString("created_at", existing?.createdAt.orEmpty()),
            updatedAt = if (isLocalPendingAndNewer) existing.updatedAt else remoteUpdatedAt,
            hlc = if (isLocalPendingAndNewer) existing.hlc else remoteHlc,
            revision = raw.optLong("revision", existing?.revision ?: 0L),
            syncStatus = resolvedSyncStatus,
            syncError = raw.optString("sync_error").takeIf { it.isNotBlank() },
            incarnation = raw.optString("incarnation", existing?.incarnation.orEmpty()),
            trashedAt = resolvedTrashedAt,
            fieldHlc = if (isLocalPendingAndNewer) existing.fieldHlc else MergeContract.metaFromJson(raw.optJSONObject("field_hlc")),
            blockHlc = if (isLocalPendingAndNewer) existing.blockHlc else MergeContract.metaFromJson(raw.optJSONObject("block_hlc")),
            deletedBlocks = if (isLocalPendingAndNewer) existing.deletedBlocks else MergeContract.metaFromJson(raw.optJSONObject("deleted_blocks")),
            blockAnchor = if (isLocalPendingAndNewer) existing.blockAnchor else MergeContract.anchorsFromJson(raw.optJSONObject("block_anchor"))
                .mapValues { org.yaop.core.model.BlockAnchor(after = it.value.after, hlc = it.value.hlc) },
            schemaVersion = resolvedSchemaVersion,
        )
    }

    /**
     * Serializes a flat list of [Block]s to the `contracts/SYNC.md` JSON array.
     * Consecutive blocks sharing a non-null `groupId` are emitted as a single
     * `{"id": groupId, "type": "group", "children": [...]}` entry, matching legacy desktop.
     */
    fun blocksToStructuredJson(blocks: List<Block>): JSONArray =
        JSONArray(blocksToStructuredJsonString(blocks))

    fun structuredJsonToBlocks(arr: JSONArray): List<Block> =
        structuredJsonStringToBlocks(arr.toString())

    /** Flat wire blocks (spec/text.md doc/3): grouping is a `group_id` on each block. */
    fun blocksToJsonElement(blocks: List<Block>): JsonArray = buildJsonArray {
        for (block in blocks) {
            val single = singleBlockToJsonElement(block)
            val gid = block.groupId
            if (gid.isNullOrBlank()) add(single) else add(JsonObject(single + ("group_id" to JsonPrimitive(gid))))
        }
    }

    /** Block keys this codec understands; anything else is passed through untouched (spec/text.md). */
    val KNOWN_BLOCK_KEYS = setOf(
        "id", "type", "indent", "block_color", "group_id", "text", "runs", "children", "level", "number",
        "checked", "expanded", "callout_type", "emoji", "language", "latex", "display", "align", "rows",
        "object_id", "view_mode", "asset_id", "width_percent", "duration_seconds", "page_count",
        "last_read_page", "file_name", "url", "title", "description", "domain", "thumbnail_url",
        "favicon_url", "canvas_data",
    )

    /** Copies unknown keys of the last received raw blocks (by id) onto freshly exported blocks. */
    fun passthroughUnknownBlockKeys(exported: JSONArray, raw: JSONArray?): JSONArray {
        if (raw == null || raw.length() == 0) return exported
        val rawById = HashMap<String, JSONObject>()
        fun index(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                rawById[b.optString("id")] = b
                b.optJSONArray("children")?.let { index(it) }
            }
        }
        index(raw)
        for (i in 0 until exported.length()) {
            val b = exported.optJSONObject(i) ?: continue
            val r = rawById[b.optString("id")] ?: continue
            for (k in r.keys()) if (k !in KNOWN_BLOCK_KEYS && !b.has(k)) b.put(k, r.get(k))
        }
        return exported
    }

    private fun singleBlockToJsonElement(block: Block): JsonObject = buildJsonObject {
        put("id", block.id)
        // Wire indent is 1-based (1..8); YAOP indentLevel is 0-based (0..6)
        if (block.indentLevel > 0) {
            put("indent", (block.indentLevel + 1).coerceIn(1, 8))
        }
        block.blockColor?.takeIf { it.isNotBlank() }?.let { put("block_color", it) }

        when (block) {
            is Block.Paragraph -> {
                put("type", "paragraph")
                put("text", wireText(block.spans))
                put("runs", spansToRunsJsonElement(block.spans))
            }
            is Block.Heading -> {
                put("type", "heading")
                put(
                    "level",
                    when (block.level) {
                        HeadingLevel.H1 -> 1
                        HeadingLevel.H2 -> 2
                        HeadingLevel.H3 -> 3
                        HeadingLevel.H4 -> 4
                    },
                )
                put("text", wireText(block.spans))
                put("runs", spansToRunsJsonElement(block.spans))
            }
            is Block.BulletList -> {
                put("type", "bullet_list")
                put("text", wireText(block.spans))
                put("runs", spansToRunsJsonElement(block.spans))
            }
            is Block.NumberedList -> {
                put("type", "numbered_list")
                put("number", block.number)
                put("text", wireText(block.spans))
                put("runs", spansToRunsJsonElement(block.spans))
            }
            is Block.AlphabeticList -> {
                put("type", "alphabetic_list")
                put("number", block.number)
                put("text", wireText(block.spans))
                put("runs", spansToRunsJsonElement(block.spans))
            }
            is Block.RomanList -> {
                put("type", "roman_list")
                put("number", block.number)
                put("text", wireText(block.spans))
                put("runs", spansToRunsJsonElement(block.spans))
            }
            is Block.Todo -> {
                put("type", "todo")
                put("checked", block.checked)
                put("text", wireText(block.spans))
                put("runs", spansToRunsJsonElement(block.spans))
            }
            is Block.Toggle -> {
                put("type", "toggle")
                put("expanded", block.isExpanded)
                put("text", wireText(block.spans))
                put("runs", spansToRunsJsonElement(block.spans))
            }
            is Block.Quote -> {
                put("type", "quote")
                put("text", wireText(block.spans))
                put("runs", spansToRunsJsonElement(block.spans))
            }
            is Block.Callout -> {
                put("type", "callout")
                put("callout_type", block.type.name)
                put("emoji", block.emoji)
                put("text", wireText(block.spans))
                put("runs", spansToRunsJsonElement(block.spans))
            }
            is Block.Code -> {
                put("type", "code")
                put("language", block.language)
                put("text", block.code)
                put(
                    "runs",
                    buildJsonArray {
                        add(buildJsonObject { put("text", block.code) })
                    },
                )
            }
            is Block.Math -> {
                put("type", "math")
                put("text", "\uFFFC")
                put(
                    "runs",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("text", "\uFFFC")
                                put("type", "math")
                                put("latex", block.latex)
                                put("display", true)
                            },
                        )
                    },
                )
            }
            is Block.Divider -> {
                put("type", "divider")
                put("text", "\uFFFC")
                put("runs", buildJsonArray {})
            }
            is Block.Table -> {
                put("type", "table")
                put("text", "")
                put("runs", buildJsonArray {})
                if (block.alignments.isNotEmpty()) {
                    put("align", buildJsonArray { block.alignments.forEach { add(JsonPrimitive(it)) } })
                }
                put(
                    "rows",
                    buildJsonArray {
                        for (row in block.rows) {
                            add(
                                buildJsonArray {
                                    for (cell in row) {
                                        add(buildJsonObject { put("text", cell) })
                                    }
                                },
                            )
                        }
                    },
                )
            }
            is Block.ObjectEmbed -> {
                put("type", "embed")
                put("object_id", block.targetObjectId)
                put("view_mode", block.viewMode)
                put("text", block.targetObjectId)
                put("runs", buildJsonArray {})
            }
            is Block.Image -> {
                put("type", "image")
                put("asset_id", block.assetId)
                put("width_percent", wireFraction(block.widthPercent))
                put("text", wireText(block.caption))
                put("runs", spansToRunsJsonElement(block.caption))
            }
            is Block.Audio -> {
                put("type", "audio")
                put("asset_id", block.assetId)
                put("duration_seconds", block.durationSeconds)
                put("text", wireText(block.caption))
                put("runs", spansToRunsJsonElement(block.caption))
            }
            is Block.Pdf -> {
                put("type", "pdf")
                put("asset_id", block.assetId)
                put("page_count", block.pageCount)
                put("last_read_page", block.lastReadPage)
                put("file_name", block.fileName)
                put("text", block.fileName)
                put("runs", buildJsonArray {})
            }
            is Block.Bookmark -> {
                put("type", "bookmark")
                put("url", block.url)
                put("title", block.title)
                put("description", block.description)
                put("domain", block.domain)
                block.thumbnailUrl?.let { put("thumbnail_url", it) }
                block.faviconUrl?.let { put("favicon_url", it) }
                block.targetObjectId?.let { put("object_id", it) }
                put("text", block.title.ifBlank { block.url })
                put("runs", buildJsonArray {})
            }
            is Block.Canvas -> {
                put("type", "canvas")
                put("canvas_data", Json.encodeToJsonElement(org.yaop.core.model.whiteboard.WhiteboardData.serializer(), block.data))
                put("text", "Whiteboard Canvas")
                put("runs", buildJsonArray {})
            }
        }
    }

    fun jsonElementToBlocks(arr: JsonArray): List<Block> {
        val result = mutableListOf<Block>()
        for (elem in arr) {
            val item = elem as? JsonObject ?: continue
            val type = item.optStr("type", "paragraph")
            if (type == "group") { // legacy nested groups (doc/1); doc/3 is flat with group_id
                val groupId = item.optStr("id").ifBlank { newBlockId() }
                val children = item["children"] as? JsonArray
                if (children != null && children.isNotEmpty()) {
                    for (childElem in children) {
                        val childObj = childElem as? JsonObject ?: continue
                        result.add(jsonElementToSingleBlock(childObj, groupId = groupId))
                    }
                } else {
                    result.add(jsonElementToSingleBlock(item, groupId = groupId, forceType = "paragraph"))
                }
            } else {
                result.add(jsonElementToSingleBlock(item, groupId = item.optStr("group_id").ifBlank { null }))
            }
        }
        return result.ifEmpty { listOf(Block.Paragraph(id = newBlockId(), spans = emptyList())) }
    }

    private fun jsonElementToSingleBlock(
        obj: JsonObject,
        groupId: String?,
        forceType: String? = null,
    ): Block {
        val id = obj.optStr("id").ifBlank { newBlockId() }
        val type = forceType ?: obj.optStr("type", "paragraph")
        val rawIndent = obj.optIntVal("indent", 1)
        val indentLevel = (rawIndent - 1).coerceIn(0, 6)
        val blockColor = obj.optStr("block_color").takeIf { it.isNotBlank() }
        val runsArr = obj["runs"] as? JsonArray
        val rawText = obj.optStr("text", "")
        val spans = if (runsArr != null && runsArr.isNotEmpty()) {
            runsJsonElementToSpans(runsArr)
        } else {
            val stripped = rawText.removePrefix("○ ").removePrefix("✔ ").removePrefix("▶ ").removePrefix("▼ ")
            stripped.toTextSpan()
        }

        return when (type) {
            "heading" -> {
                val lvl = when (obj.optIntVal("level", 1)) {
                    1 -> HeadingLevel.H1
                    2 -> HeadingLevel.H2
                    3 -> HeadingLevel.H3
                    else -> HeadingLevel.H4
                }
                Block.Heading(id = id, level = lvl, spans = spans, indentLevel = indentLevel, groupId = groupId, blockColor = blockColor)
            }
            "bullet_list" -> Block.BulletList(id = id, spans = spans, indentLevel = indentLevel, groupId = groupId, blockColor = blockColor)
            "numbered_list" -> Block.NumberedList(id = id, number = obj.optIntVal("number", 1), spans = spans, indentLevel = indentLevel, groupId = groupId, blockColor = blockColor)
            "alphabetic_list" -> Block.AlphabeticList(id = id, number = obj.optIntVal("number", 1), spans = spans, indentLevel = indentLevel, groupId = groupId, blockColor = blockColor)
            "roman_list" -> Block.RomanList(id = id, number = obj.optIntVal("number", 1), spans = spans, indentLevel = indentLevel, groupId = groupId, blockColor = blockColor)
            "todo" -> {
                val checked = obj.optBool("checked", rawText.startsWith("✔"))
                Block.Todo(id = id, checked = checked, spans = spans, indentLevel = indentLevel, groupId = groupId, blockColor = blockColor)
            }
            "toggle" -> {
                val expanded = obj.optBool("expanded", !rawText.startsWith("▶"))
                Block.Toggle(id = id, spans = spans, isExpanded = expanded, indentLevel = indentLevel, groupId = groupId, blockColor = blockColor)
            }
            "quote" -> Block.Quote(id = id, spans = spans, indentLevel = indentLevel, groupId = groupId, blockColor = blockColor)
            "callout" -> {
                val cType = runCatching { CalloutType.valueOf(obj.optStr("callout_type", "NOTE")) }.getOrDefault(CalloutType.NOTE)
                val emoji = obj.optStr("emoji", "💡").ifBlank { "💡" }
                Block.Callout(id = id, type = cType, emoji = emoji, spans = spans, indentLevel = indentLevel, groupId = groupId, blockColor = blockColor)
            }
            "code" -> Block.Code(
                id = id,
                code = rawText.replace('\u2028', '\n'),
                language = obj.optStr("language", "kotlin").ifBlank { "kotlin" },
                indentLevel = 0,
                groupId = groupId,
                blockColor = blockColor,
            )
            "math" -> {
                val firstRun = runsArr?.firstOrNull() as? JsonObject
                val latex = firstRun?.optStr("latex").takeUnless { it.isNullOrBlank() }
                    ?: rawText.removePrefix("\uFFFC")
                Block.Math(id = id, latex = latex, indentLevel = 0, groupId = groupId, blockColor = blockColor)
            }
            "divider" -> Block.Divider(id = id, indentLevel = 0, groupId = groupId, blockColor = blockColor)
            "table" -> {
                val rowsArr = obj["rows"] as? JsonArray
                val rows = if (rowsArr != null && rowsArr.isNotEmpty()) {
                    rowsArr.map { rowElem ->
                        val rArr = rowElem as? JsonArray
                        if (rArr != null) {
                            rArr.map { cellElem ->
                                when (cellElem) {
                                    is JsonObject -> cellElem.optStr("text", "")
                                    is JsonPrimitive -> cellElem.contentOrNull.orEmpty()
                                    else -> ""
                                }
                            }
                        } else {
                            listOf("", "")
                        }
                    }
                } else {
                    listOf(listOf("", ""), listOf("", ""))
                }
                val alignArr = obj["align"] as? JsonArray
                val alignments = alignArr?.map { (it as? JsonPrimitive)?.contentOrNull ?: "left" }.orEmpty()
                Block.Table(id = id, rows = rows, alignments = alignments, indentLevel = 0, groupId = groupId, blockColor = blockColor)
            }
            "embed" -> Block.ObjectEmbed(
                id = id,
                targetObjectId = obj.optStr("object_id", rawText),
                viewMode = obj.optStr("view_mode", "card"),
                indentLevel = 0,
                groupId = groupId,
                blockColor = blockColor,
            )
            "image" -> Block.Image(
                id = id,
                assetId = obj.optStr("asset_id", ""),
                caption = spans,
                widthPercent = obj.optDoubleVal("width_percent", 1.0).toFloat().coerceIn(0.25f, 1.0f),
                indentLevel = 0,
                groupId = groupId,
                blockColor = blockColor,
            )
            "audio" -> Block.Audio(
                id = id,
                assetId = obj.optStr("asset_id", ""),
                durationSeconds = obj.optIntVal("duration_seconds", 0),
                caption = spans,
                indentLevel = 0,
                groupId = groupId,
                blockColor = blockColor,
            )
            "pdf" -> Block.Pdf(
                id = id,
                assetId = obj.optStr("asset_id", ""),
                pageCount = obj.optIntVal("page_count", 1),
                lastReadPage = obj.optIntVal("last_read_page", 1),
                fileName = obj.optStr("file_name", "document.pdf"),
                indentLevel = 0,
                groupId = groupId,
                blockColor = blockColor,
            )
            "bookmark" -> Block.Bookmark(
                id = id,
                url = obj.optStr("url", ""),
                title = obj.optStr("title", ""),
                description = obj.optStr("description", ""),
                domain = obj.optStr("domain", ""),
                thumbnailUrl = obj.optStr("thumbnail_url").takeIf { it.isNotBlank() },
                faviconUrl = obj.optStr("favicon_url").takeIf { it.isNotBlank() },
                targetObjectId = obj.optStr("object_id").takeIf { it.isNotBlank() },
                indentLevel = 0,
                groupId = groupId,
                blockColor = blockColor,
            )
            "canvas" -> {
                val canvasData = obj["canvas_data"]?.let {
                    runCatching { Json.decodeFromJsonElement(org.yaop.core.model.whiteboard.WhiteboardData.serializer(), it) }.getOrNull()
                } ?: org.yaop.core.model.whiteboard.WhiteboardData(id = id)
                Block.Canvas(id = id, data = canvasData, indentLevel = indentLevel, groupId = groupId, blockColor = blockColor)
            }
            else -> Block.Paragraph(id = id, spans = spans, indentLevel = indentLevel, groupId = groupId, blockColor = blockColor)
        }
    }

    /**
     * Wire `text` shadow of a block (spec/text.md): identical to desktop `runsToPlainText`.
     * Differs from the on-screen string only for dates (no "@" on the wire).
     */
    fun wireText(spans: List<InlineSpan>): String = buildString {
        for (span in RichSpanEngine.upgradeLegacySpans(spans)) {
            when (span) {
                is InlineSpan.Text -> append(span.text)
                is InlineSpan.Mention -> {
                    val raw = (span.fallbackText?.takeIf { it.isNotBlank() } ?: span.targetObjectId).removePrefix("@").trim()
                    append('@').append(raw.ifBlank { "mention" })
                }
                is InlineSpan.Tag -> {
                    val clean = span.tagName.removePrefix("#")
                    append(if (clean.isEmpty()) "#tag" else "#$clean")
                }
                is InlineSpan.Date -> append(span.isoDate)
                is InlineSpan.Math -> append('\uFFFC')
            }
        }
    }

    /**
     * spec/text.md: pure black/white are editor artifacts (they make text invisible in one theme),
     * dropped on import exactly like desktop `normalizeColorName`.
     */
    fun normalizeWireColor(raw: String?): String? {
        val c = raw?.trim().orEmpty()
        if (c.isEmpty()) return null
        return when (c.lowercase()) {
            "#000000", "#000", "black", "#ffffff", "#fff", "white" -> null
            else -> c
        }
    }

    /** Fractions travel rounded to 4 decimals so Float storage never fakes an edit. */
    fun wireFraction(v: Float): Double = Math.round(v.toDouble() * 10000.0) / 10000.0

    private fun spansToRunsJsonElement(spans: List<InlineSpan>): JsonArray = buildJsonArray {
        for (span in RichSpanEngine.upgradeLegacySpans(spans)) {
            add(
                buildJsonObject {
                    when (span) {
                        is InlineSpan.Text -> {
                            put("text", span.text)
                            if (TextMark.BOLD in span.marks) put("bold", true)
                            if (TextMark.ITALIC in span.marks) put("italic", true)
                            if (TextMark.UNDERLINE in span.marks) put("underline", true)
                            if (TextMark.STRIKETHROUGH in span.marks) put("strike", true)
                            if (TextMark.CODE in span.marks) put("code", true)
                            if (TextMark.HIGHLIGHT in span.marks) put("highlight", true)
                            if (!span.linkUrl.isNullOrBlank()) put("href", span.linkUrl)
                            if (!span.textColor.isNullOrBlank()) put("text_color", span.textColor)
                            if (!span.backgroundColor.isNullOrBlank()) put("bg_color", span.backgroundColor)
                        }
                        is InlineSpan.Mention -> {
                            val label = span.fallbackText?.takeIf { it.isNotBlank() } ?: span.targetObjectId
                            put("text", label)
                            put("object_id", span.targetObjectId)
                        }
                        is InlineSpan.Tag -> {
                            put("text", "#${span.tagName.removePrefix("#")}")
                            put("tag_id", span.tagId)
                            put("tag_name", span.tagName.removePrefix("#"))
                        }
                        is InlineSpan.Date -> {
                            put("text", span.isoDate)
                            put("date", span.isoDate)
                            if (span.hasTime) put("has_time", true)
                        }
                        is InlineSpan.Math -> {
                            put("text", "\uFFFC")
                            put("type", "math")
                            if (span.latex.isNotEmpty()) put("latex", span.latex)
                            if (span.display) put("display", true)
                        }
                    }
                },
            )
        }
    }

    private fun runsJsonElementToSpans(arr: JsonArray): List<InlineSpan> {
        val out = mutableListOf<InlineSpan>()
        for (elem in arr) {
            val run = elem as? JsonObject ?: continue
            val text = run.optStr("text", "")
            val objectId = run.optStr("object_id", "")
            val href = run.optStr("href", "")
            val tagId = run.optStr("tag_id", "")
            val tagName = run.optStr("tag_name", "")
            val dateIso = run.optStr("date", "")

            when {
                run.optStr("type", "") == "math" -> {
                    out.add(InlineSpan.Math(latex = run.optStr("latex", ""), display = run.optBool("display", false)))
                }
                objectId.isNotBlank() || href.startsWith("lumen://object/") -> {
                    val resolvedId = objectId.ifBlank { href.removePrefix("lumen://object/") }.ifBlank { text }
                    out.add(InlineSpan.Mention(targetObjectId = resolvedId, fallbackText = text.ifBlank { resolvedId }))
                }
                tagId.isNotBlank() || tagName.isNotBlank() -> {
                    val cleanName = tagName.ifBlank { text.removePrefix("#") }
                    out.add(InlineSpan.Tag(tagId = tagId.ifBlank { "tag_${cleanName.lowercase()}" }, tagName = cleanName))
                }
                dateIso.isNotBlank() -> {
                    out.add(InlineSpan.Date(isoDate = dateIso, hasTime = run.optBool("has_time", false)))
                }
                else -> {
                    val marks = buildSet {
                        if (run.optBool("bold")) add(TextMark.BOLD)
                        if (run.optBool("italic")) add(TextMark.ITALIC)
                        if (run.optBool("underline")) add(TextMark.UNDERLINE)
                        if (run.optBool("strike")) add(TextMark.STRIKETHROUGH)
                        if (run.optBool("code")) add(TextMark.CODE)
                        if (run.optBool("highlight")) add(TextMark.HIGHLIGHT)
                    }
                    out.add(
                        InlineSpan.Text(
                            text = text,
                            marks = marks,
                            linkUrl = href.takeIf { it.isNotBlank() },
                            textColor = normalizeWireColor(run.optStr("text_color")),
                            backgroundColor = normalizeWireColor(run.optStr("bg_color")),
                        ),
                    )
                }
            }
        }
        return RichSpanEngine.normalize(out)
    }

    private fun JsonObject.optStr(key: String, default: String = ""): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: default

    private fun JsonObject.optIntVal(key: String, default: Int = 0): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: default

    private fun JsonObject.optDoubleVal(key: String, default: Double = 0.0): Double =
        (this[key] as? JsonPrimitive)?.doubleOrNull ?: default

    private fun JsonObject.optBool(key: String, default: Boolean = false): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

    /**
     * Builds the canonical reusable media [PkmObject] for an attached [AssetMeta]
     * per `contracts/SYNC.md` ("Objetos de medio").
     */
    fun buildMediaObjectForAsset(asset: AssetMeta): PkmObject? {
        val mediaTypeId = when {
            asset.mimeType.startsWith("image/") -> DefaultTypes.IMAGE.id
            asset.mimeType == "application/pdf" -> DefaultTypes.PDF.id
            asset.mimeType.startsWith("audio/") -> DefaultTypes.AUDIO.id
            else -> return null
        }
        val defaultIcon = when (mediaTypeId) {
            DefaultTypes.IMAGE.id -> ObjectIcon(emoji = "🖼️", iconName = "image-x-generic")
            DefaultTypes.AUDIO.id -> ObjectIcon(emoji = "🎙️", iconName = "music")
            DefaultTypes.PDF.id -> ObjectIcon(emoji = "📄", iconName = "application-pdf")
            else -> null
        }
        val mediaObjectId = asset.mediaObjectId?.takeIf { it.isNotBlank() } ?: "media_${asset.id}"
        val props = buildMap<String, PropertyValue> {
            put("asset_id", PropertyValue.RichText(asset.id.toTextSpan()))
            put("file_name", PropertyValue.RichText(asset.name.toTextSpan()))
            put("mime", PropertyValue.RichText(asset.mimeType.toTextSpan()))
            put("size", PropertyValue.Number(asset.sizeBytes.toDouble()))
            asset.durationSeconds?.takeIf { it > 0 }?.let { dur ->
                put("duration", PropertyValue.Number(dur.toDouble()))
            }
            asset.objectId?.takeIf { it.isNotBlank() && it != mediaObjectId }?.let { ownerId ->
                put("source_object_id", PropertyValue.ObjectRelation(listOf(ownerId)))
            }
        }
        return PkmObject(
            id = mediaObjectId,
            typeId = mediaTypeId,
            title = asset.name.ifBlank { asset.id }.toTextSpan(),
            icon = defaultIcon,
            properties = props,
            createdAt = asset.createdAt,
            updatedAt = asset.createdAt,
        )
    }

    fun blocksToMarkdown(blocks: List<Block>): String =
        blocks.joinToString("\n\n") { block ->
            val indentPrefix = "  ".repeat(block.indentLevel.coerceAtLeast(0))
            val groupPrefix = if (!block.groupId.isNullOrBlank()) ">> " else ""
            val raw = when (block) {
                is Block.Paragraph -> "$groupPrefix${spansToMarkdown(block.spans)}"
                is Block.Heading -> {
                    val hashes = when (block.level) {
                        HeadingLevel.H1 -> "#"
                        HeadingLevel.H2 -> "##"
                        HeadingLevel.H3 -> "###"
                        HeadingLevel.H4 -> "####"
                    }
                    "$hashes ${spansToMarkdown(block.spans)}"
                }
                is Block.Todo -> "- [${if (block.checked) "x" else " "}] ${spansToMarkdown(block.spans)}"
                is Block.BulletList -> "- ${spansToMarkdown(block.spans)}"
                is Block.NumberedList -> "${block.number}. ${spansToMarkdown(block.spans)}"
                is Block.AlphabeticList -> "${block.number.toAlphabetic()}. ${spansToMarkdown(block.spans)}"
                is Block.RomanList -> "${block.number.toRoman().lowercase()}. ${spansToMarkdown(block.spans)}"
                is Block.Quote -> "> ${spansToMarkdown(block.spans)}"
                is Block.Callout -> "> [!${block.type.name}] ${block.emoji}\n> ${spansToMarkdown(block.spans)}"
                is Block.Toggle -> "${if (block.isExpanded) "▼" else "▶"} ${spansToMarkdown(block.spans)}"
                is Block.Code -> "```${block.language}\n${block.code}\n```"
                is Block.Math -> "$$\n${block.latex}\n$$"
                is Block.Divider -> "---"
                is Block.Image -> "![${block.caption.toPlainText()}](/v1/assets/${block.assetId}/content)"
                is Block.Audio -> "[Audio (${block.durationSeconds}s)](/v1/assets/${block.assetId}/content)"
                is Block.Pdf -> "[${block.fileName.ifBlank { "PDF" }}](/v1/assets/${block.assetId}/content)"
                is Block.ObjectEmbed -> "[[${block.targetObjectId}]]"
                is Block.Bookmark -> "[${block.title.ifBlank { block.url }}](${block.url})"
                is Block.Table -> block.rows.joinToString("\n") { row ->
                    "| " + row.joinToString(" | ") + " |"
                }
                is Block.Canvas -> "```whiteboard\n${block.data.nodes.size} nodes, ${block.data.connectors.size} connections\n```"
            }
            if (indentPrefix.isEmpty()) raw else raw.lines().joinToString("\n") { "$indentPrefix$it" }
        }

    fun markdownToBlocks(markdown: String): List<Block> {
        if (markdown.isBlank()) return listOf(Block.Paragraph(id = newBlockId(), spans = emptyList()))
        val lines = markdown.lines()
        val result = mutableListOf<Block>()
        var i = 0
        var numberedCounter = 1
        var alphaCounter = 1
        var romanCounter = 1
        var activeGroupId: String? = null
        while (i < lines.size) {
            val line = lines[i]
            val leadingSpaces = line.takeWhile { it == ' ' || it == '\t' }
                .sumOf { if (it == '\t') 2 else 1 }
            val indentLevel = (leadingSpaces / 2).coerceIn(0, 6)
            val trimmed = line.trim()
            if (!trimmed.startsWith(">> ")) {
                activeGroupId = null
            }
            when {
                trimmed.isEmpty() -> {
                    numberedCounter = 1
                    alphaCounter = 1
                    romanCounter = 1
                    i++
                }
                trimmed.startsWith("```") -> {
                    val lang = trimmed.removePrefix("```").trim().ifBlank { "kotlin" }
                    val codeLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        codeLines += lines[i].removePrefix("  ".repeat(indentLevel))
                        i++
                    }
                    i++ // skip closing fence
                    result += Block.Code(id = newBlockId(), language = lang, code = codeLines.joinToString("\n"), indentLevel = 0)
                }
                trimmed == "$$" -> {
                    val mathLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && lines[i].trim() != "$$") {
                        mathLines += lines[i]
                        i++
                    }
                    i++ // skip closing $$
                    result += Block.Math(id = newBlockId(), latex = mathLines.joinToString("\n"), indentLevel = 0)
                }
                trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                    result += Block.Divider(id = newBlockId(), indentLevel = 0)
                    i++
                }
                trimmed.startsWith("#### ") -> {
                    result += Block.Heading(id = newBlockId(), level = HeadingLevel.H4, spans = trimmed.removePrefix("#### ").toTextSpan(), indentLevel = indentLevel)
                    i++
                }
                trimmed.startsWith("### ") -> {
                    result += Block.Heading(id = newBlockId(), level = HeadingLevel.H3, spans = trimmed.removePrefix("### ").toTextSpan(), indentLevel = indentLevel)
                    i++
                }
                trimmed.startsWith("## ") -> {
                    result += Block.Heading(id = newBlockId(), level = HeadingLevel.H2, spans = trimmed.removePrefix("## ").toTextSpan(), indentLevel = indentLevel)
                    i++
                }
                trimmed.startsWith("# ") -> {
                    result += Block.Heading(id = newBlockId(), level = HeadingLevel.H1, spans = trimmed.removePrefix("# ").toTextSpan(), indentLevel = indentLevel)
                    i++
                }
                trimmed.startsWith("- [x] ", ignoreCase = true) -> {
                    result += Block.Todo(id = newBlockId(), checked = true, spans = trimmed.substring(6).toTextSpan(), indentLevel = indentLevel)
                    i++
                }
                trimmed.startsWith("- [ ] ") -> {
                    result += Block.Todo(id = newBlockId(), checked = false, spans = trimmed.substring(6).toTextSpan(), indentLevel = indentLevel)
                    i++
                }
                trimmed.startsWith("▶ ") || trimmed.startsWith("▼ ") -> {
                    val expanded = trimmed.startsWith("▼ ")
                    result += Block.Toggle(id = newBlockId(), spans = trimmed.substring(2).toTextSpan(), isExpanded = expanded, indentLevel = indentLevel)
                    i++
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    result += Block.BulletList(id = newBlockId(), spans = trimmed.substring(2).toTextSpan(), indentLevel = indentLevel)
                    i++
                }
                trimmed.matches(Regex("""^[ivxlcdm]+\.\s+.*""", RegexOption.IGNORE_CASE)) -> {
                    val text = trimmed.replaceFirst(Regex("""^[ivxlcdm]+\.\s+""", RegexOption.IGNORE_CASE), "")
                    result += Block.RomanList(id = newBlockId(), number = romanCounter++, spans = text.toTextSpan(), indentLevel = indentLevel)
                    i++
                }
                trimmed.matches(Regex("""^[a-zA-Z][.)]\s+.*""")) -> {
                    val text = trimmed.replaceFirst(Regex("""^[a-zA-Z][.)]\s+"""), "")
                    result += Block.AlphabeticList(id = newBlockId(), number = alphaCounter++, spans = text.toTextSpan(), indentLevel = indentLevel)
                    i++
                }
                trimmed.matches(Regex("""^\d+[.)]\s+.*""")) -> {
                    val text = trimmed.replaceFirst(Regex("""^\d+[.)]\s+"""), "")
                    result += Block.NumberedList(id = newBlockId(), number = numberedCounter++, spans = text.toTextSpan(), indentLevel = indentLevel)
                    i++
                }
                trimmed.startsWith(">> ") -> {
                    val gid = activeGroupId ?: UUID.randomUUID().toString().also { activeGroupId = it }
                    result += Block.Paragraph(id = newBlockId(), spans = trimmed.removePrefix(">> ").toTextSpan(), indentLevel = indentLevel, groupId = gid)
                    i++
                }
                trimmed.startsWith("> [!") -> {
                    val header = trimmed.removePrefix("> [!")
                    val typeStr = header.substringBefore("]").trim()
                    val emoji = header.substringAfter("]", "").trim().ifBlank { "💡" }
                    val cType = runCatching { CalloutType.valueOf(typeStr) }.getOrDefault(CalloutType.NOTE)
                    val body = if (i + 1 < lines.size && lines[i + 1].trim().startsWith("> ")) {
                        i++
                        lines[i].trim().removePrefix("> ")
                    } else {
                        ""
                    }
                    result += Block.Callout(id = newBlockId(), type = cType, emoji = emoji, spans = body.toTextSpan(), indentLevel = indentLevel)
                    i++
                }
                trimmed.startsWith("> ") -> {
                    result += Block.Quote(id = newBlockId(), spans = trimmed.removePrefix("> ").toTextSpan(), indentLevel = indentLevel)
                    i++
                }
                else -> {
                    result += Block.Paragraph(id = newBlockId(), spans = trimmed.toTextSpan(), indentLevel = indentLevel)
                    i++
                }
            }
        }
        return result.ifEmpty { listOf(Block.Paragraph(id = newBlockId(), spans = emptyList())) }
    }

    private fun newBlockId(): String = UUID.randomUUID().toString()

    private fun spansToMarkdown(spans: List<InlineSpan>): String =
        RichSpanEngine.upgradeLegacySpans(spans).joinToString("") { span ->
            when (span) {
                is InlineSpan.Text -> {
                    var text = span.text
                    if (TextMark.CODE in span.marks) text = "`$text`"
                    if (TextMark.BOLD in span.marks) text = "**$text**"
                    if (TextMark.ITALIC in span.marks) text = "*$text*"
                    if (TextMark.UNDERLINE in span.marks) text = "<u>$text</u>"
                    if (TextMark.STRIKETHROUGH in span.marks) text = "~~$text~~"
                    if (TextMark.HIGHLIGHT in span.marks) text = "==$text=="
                    if (!span.linkUrl.isNullOrBlank()) {
                        text = "[$text](${span.linkUrl})"
                    }
                    text
                }
                is InlineSpan.Mention -> "[[${span.fallbackText ?: span.targetObjectId}]]"
                is InlineSpan.Tag -> "#${span.tagName.removePrefix("#")}"
                is InlineSpan.Date -> span.isoDate
                is InlineSpan.Math -> "$" + span.latex + "$"
            }
        }

    // ------------------------------------------------------------------
    // schema_version + property/type definitions over the wire :
    // customPropertyDefs and ObjectType definitions now sync (spec/sync.md kind `type`).
    // ------------------------------------------------------------------

    /** Wire schema this codec writes and understands (spec/object.md): 1 = doc/3 + block_anchor. */
    const val SCHEMA_VERSION = 1

    private fun propertyKindToWire(kind: org.yaop.core.model.PropertyKind): String = when (kind) {
        org.yaop.core.model.PropertyKind.RICH_TEXT -> "text"
        org.yaop.core.model.PropertyKind.OBJECT_SELECT -> "object"
        org.yaop.core.model.PropertyKind.SELECT -> "select"
        org.yaop.core.model.PropertyKind.MULTI_SELECT -> "multi_select"
        org.yaop.core.model.PropertyKind.NUMBER -> "number"
        org.yaop.core.model.PropertyKind.DATE_RANGE -> "date"
        org.yaop.core.model.PropertyKind.CHECKBOX -> "boolean"
        org.yaop.core.model.PropertyKind.MEDIA_REF -> "media"
        org.yaop.core.model.PropertyKind.URL -> "url"
    }

    private fun propertyKindFromWire(kind: String?): org.yaop.core.model.PropertyKind = when (kind) {
        "text", "" -> org.yaop.core.model.PropertyKind.RICH_TEXT
        "object", "relation" -> org.yaop.core.model.PropertyKind.OBJECT_SELECT
        "select" -> org.yaop.core.model.PropertyKind.SELECT
        "multi_select", "tags" -> org.yaop.core.model.PropertyKind.MULTI_SELECT
        "number", "rating" -> org.yaop.core.model.PropertyKind.NUMBER
        "date" -> org.yaop.core.model.PropertyKind.DATE_RANGE
        "boolean", "checkbox" -> org.yaop.core.model.PropertyKind.CHECKBOX
        "media" -> org.yaop.core.model.PropertyKind.MEDIA_REF
        "url" -> org.yaop.core.model.PropertyKind.URL
        else -> org.yaop.core.model.PropertyKind.RICH_TEXT
    }

    /** One property definition as wire JSON (same shape the Go server's types use). */
    fun propertyDefToWire(def: PropertyDefinition): JSONObject = JSONObject().apply {
        put("key", def.id)
        put("name", def.name)
        put("type", propertyKindToWire(def.kind))
        if (def.isSystem) put("is_system", true)
        if (def.kind == org.yaop.core.model.PropertyKind.OBJECT_SELECT) {
            def.targetTypeId?.takeIf { it.isNotBlank() }?.let { put("target_type", it) }
            put("multiple", true)
        }
        if (def.kind == org.yaop.core.model.PropertyKind.SELECT ||
            def.kind == org.yaop.core.model.PropertyKind.MULTI_SELECT
        ) {
            put("options", JSONArray().apply {
                def.options.forEach { o ->
                    put(JSONObject().put("id", o.id).put("label", o.label).put("color", o.colorHex))
                }
            })
        }
    }

    /** Wire property def → model. Options may be plain strings (server defaults) or objects. */
    fun propertyDefFromWire(raw: JSONObject): PropertyDefinition? {
        val key = raw.optString("key").takeIf { it.isNotBlank() } ?: return null
        val name = raw.optString("name").takeIf { it.isNotBlank() } ?: key
        val kind = propertyKindFromWire(raw.optString("type"))
        val options = raw.optJSONArray("options")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                when (val el = arr.opt(i)) {
                    is String -> SelectOption(id = el, label = el)
                    is JSONObject -> SelectOption(
                        id = el.optString("id").ifBlank { el.optString("label") },
                        label = el.optString("label").ifBlank { el.optString("id") },
                        colorHex = el.optString("color").takeIf { it.isNotBlank() && !it.startsWith("--") } ?: "#7B91AE",
                    )
                    else -> null
                }
            }
        }.orEmpty()
        return PropertyDefinition(
            id = key,
            name = name,
            kind = kind,
            targetTypeId = raw.optString("target_type").takeIf { it.isNotBlank() },
            options = options,
            isSystem = raw.optBoolean("is_system", false),
            icon = raw.optString("icon").takeIf { it.isNotBlank() },
        )
    }

    /** custom_property_defs of an object (payload of the encrypted op, kind obj/asset_meta). */
    fun customPropertyDefsToWire(defs: List<PropertyDefinition>): JSONArray = JSONArray().apply {
        defs.forEach { put(propertyDefToWire(it)) }
    }

    fun parseCustomPropertyDefsFromWire(
        arr: JSONArray?,
        fallback: List<PropertyDefinition>?,
    ): List<PropertyDefinition> {
        if (arr == null || arr.length() == 0) return fallback.orEmpty()
        val parsed = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { propertyDefFromWire(it) } }
        return parsed.ifEmpty { fallback.orEmpty() }
    }

    /** Wire JSON of a type definition (kind `type`, spec/sync.md). */
    fun typeToWireJson(type: ObjectType): JSONObject {
        // Exact re-emission of what arrived (passthrough): definitions the mobile model
        // does not know (e.g. desktop-only kinds) survive a mobile round-trip untouched.
        type.wireRaw?.let { raw -> runCatching { JSONObject(raw) }.getOrNull()?.let { return it } }
        return JSONObject().apply {
            put("id", type.id)
            put("name", type.name)
            put("icon", type.icon.iconName ?: "")
            put("color", type.colorHex)
            put("properties", customPropertyDefsToWire(type.defaultProperties))
            type.defaultTemplateId?.takeIf { it.isNotBlank() }?.let { put("default_template_id", it) }
        }
    }

    /** Wire JSON of a type definition → model (with the raw wire kept for passthrough). */
    fun typeFromWireJson(raw: JSONObject): ObjectType {
        val props = raw.optJSONArray("properties") ?: JSONArray()
        val defs = (0 until props.length()).mapNotNull { props.optJSONObject(it)?.let { p -> propertyDefFromWire(p) } }
        val iconStr = raw.optString("icon")
        return ObjectType(
            id = raw.optString("id"),
            name = raw.optString("name"),
            icon = if (iconStr.isBlank()) ObjectIcon(emoji = "🏷️") else ObjectIcon(iconName = iconStr),
            colorHex = raw.optString("color").ifBlank { "#7B91AE" },
            defaultProperties = defs,
            defaultTemplateId = raw.optString("default_template_id").takeIf { it.isNotBlank() },
            wireRaw = raw.toString(),
        )
    }


}
