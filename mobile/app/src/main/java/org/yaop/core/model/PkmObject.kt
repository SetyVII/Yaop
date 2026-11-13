package org.yaop.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Hybrid Logical Clock (spec/merge.md): `<13-digit wall>-<4 hex counter>-<8 hex node>`.
 * `node` = first 8 hex of SHA-256(device_id); set once at startup via [setNodeFromDevice].
 */
object HybridLogicalClock {
    private val lock = Any()
    private var lastWallMillis: Long = 0L
    private var counter: Int = 0
    @Volatile
    var node: String = ""
        private set

    fun nodeFor(deviceId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    fun setNodeFromDevice(deviceId: String) {
        node = nodeFor(deviceId)
    }

    fun now(currentWallMillis: Long = System.currentTimeMillis()): String = synchronized(lock) {
        if (currentWallMillis > lastWallMillis) {
            lastWallMillis = currentWallMillis
            counter = 0
        } else {
            counter = (counter + 1).coerceAtMost(0xFFFF)
        }
        format(lastWallMillis, counter)
    }

    fun receive(remoteHlc: String, currentWallMillis: Long = System.currentTimeMillis()): String = synchronized(lock) {
        val (remoteWall, remoteCounter) = parse(remoteHlc)
        when {
            currentWallMillis > lastWallMillis && currentWallMillis > remoteWall -> {
                lastWallMillis = currentWallMillis
                counter = 0
            }
            lastWallMillis == remoteWall -> {
                counter = (maxOf(counter, remoteCounter) + 1).coerceAtMost(0xFFFF)
            }
            remoteWall > lastWallMillis -> {
                lastWallMillis = remoteWall
                counter = (remoteCounter + 1).coerceAtMost(0xFFFF)
            }
            else -> {
                counter = (counter + 1).coerceAtMost(0xFFFF)
            }
        }
        format(lastWallMillis, counter)
    }

    /** (wall, counter); accepts `<wall>`, `<wall>-<counter>` and `<wall>-<counter>-<node>`. */
    fun parse(hlc: String): Pair<Long, Int> {
        val parts = hlc.trim().split('-', limit = 3)
        val wall = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val cnt = parts.getOrNull(1)?.let { it.toIntOrNull(16) ?: it.toIntOrNull() } ?: 0
        return wall to cnt
    }

    private fun nodeOf(hlc: String): String = hlc.trim().split('-', limit = 3).getOrNull(2).orEmpty()

    /** Total order of spec/merge.md: wall, counter, node; "" is the lowest. */
    fun compare(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return -1
        if (b.isEmpty()) return 1
        val (aw, ac) = parse(a)
        val (bw, bc) = parse(b)
        if (aw != bw) return aw.compareTo(bw).coerceIn(-1, 1)
        if (ac != bc) return ac.compareTo(bc).coerceIn(-1, 1)
        return nodeOf(a).compareTo(nodeOf(b)).coerceIn(-1, 1)
    }

    private fun format(wallMillis: Long, counterValue: Int): String {
        val base = "%013d-%04x".format(java.util.Locale.US, wallMillis, counterValue)
        return if (node.isEmpty()) base else "$base-$node"
    }
}

@Serializable
data class ObjectIcon(
    val emoji: String? = null,
    val iconName: String? = null,
    val colorHex: String? = null,
)

@Serializable
data class CoverSpec(
    val assetId: String,
    val cropOffsetY: Float = 0.5f,
    val isFit: Boolean = false,
)

/**
 * Anchor of a top-level block (spec/merge.md, block_anchor): `after` is the id of the block
 * that preceded it in the last layout its author saw ("" = it was first) and `hlc` stamps that
 * anchoring event. Tombstones keep their entry.
 */
@Serializable
data class BlockAnchor(
    val after: String = "",
    val hlc: String = "",
)

@Serializable
data class PkmObject(
    val id: String = UUID.randomUUID().toString(),
    val typeId: String,
    val title: List<InlineSpan> = emptyList(),
    val aliases: List<String> = emptyList(),
    val summary: List<InlineSpan> = emptyList(),
    val icon: ObjectIcon? = null,
    val cover: CoverSpec? = null,
    val properties: Map<String, PropertyValue> = emptyMap(),
    val customPropertyDefs: List<PropertyDefinition> = emptyList(),
    val blocks: List<Block> = emptyList(),
    val isTemplate: Boolean = false,
    val isPinned: Boolean = false,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = Instant.now().toString(),
    val hlc: String = HybridLogicalClock.now(),
    val revision: Long = 0L,
    val syncStatus: String = "local",
    val syncError: String? = null,
    val incarnation: String = "",
    val trashedAt: String? = null,
    /** Per-field / per-block stamps (spec/merge.md). Keys: "title", "type_id", "order", "p:<key>". */
    val fieldHlc: Map<String, String> = emptyMap(),
    val blockHlc: Map<String, String> = emptyMap(),
    val deletedBlocks: Map<String, String> = emptyMap(),
    /**
     * Raw wire `properties` (minus blocks) as last received. Export re-emits every value the
     * mobile model did not change byte-for-byte (spec/object.md passthrough).
     */
    val wireProps: String? = null,
    /** Top-level block anchors (spec/merge.md): they travel inside the object's encrypted blob. */
    val blockAnchor: Map<String, BlockAnchor> = emptyMap(),
    /**
     * Wire version of the object (spec/object.md): 1 = yaop-doc/1 with `block_anchor`.
     * The client writes 1 on every object it saves; a higher one than it knows is rejected.
     */
    val schemaVersion: Int = 0,
) {
    val plainTitle: String get() = title.toPlainText().ifBlank { "Untitled" }
    val plainSummary: String get() = summary.toPlainText()

    /** Looks up a property by its wire key (e.g. `"status"`, `"due_date"`). */
    fun property(key: String): PropertyValue? = properties[key]

    val taskStatus: String
        get() = taskStatusEnum.id

    val isTaskDone: Boolean
        get() = taskStatusEnum.isDone

    val dueDateIso: String?
        get() = (property(PropertyKeys.DUE_DATE) as? PropertyValue.DateRange)?.startDate?.takeIf { it.isNotBlank() }

    val journalDateIso: String?
        get() = (property(PropertyKeys.DATE) as? PropertyValue.DateRange)?.startDate?.takeIf { it.isNotBlank() }

    val taskPriority: String?
        get() = (property(PropertyKeys.PRIORITY) as? PropertyValue.Select)?.selectedOptionId
            ?.let { DefaultTypes.normalizePriorityOption(it) }

    val isReviewed: Boolean
        get() = (property(PropertyKeys.REVIEWED) as? PropertyValue.Checkbox)?.checked == true

    val isBookmark: Boolean
        get() = (property(PropertyKeys.IS_BOOKMARK) as? PropertyValue.Checkbox)?.checked == true

    val weblinkUrl: String?
        get() = (property(PropertyKeys.URL) as? PropertyValue.Url)?.url
            ?: (property(PropertyKeys.URL) as? PropertyValue.RichText)?.spans?.toPlainText()
            ?: blocks.filterIsInstance<Block.Bookmark>().firstOrNull()?.url
}

@Serializable
data class ObjectType(
    val id: String,
    val name: String,
    val icon: ObjectIcon,
    val colorHex: String,
    val defaultProperties: List<PropertyDefinition> = emptyList(),
    val defaultTemplateId: String? = null,
    /**
     * Raw wire JSON of the type as it arrived (kind `type`, spec/sync.md): re-emitted byte for
     * byte so property definitions the mobile model does not know are not lost.
     */
    val wireRaw: String? = null,
)

@Serializable
data class AssetMeta(
    val id: String = UUID.randomUUID().toString(),
    val objectId: String? = null,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: String = Instant.now().toString(),
    val isPinned: Boolean = false,
    val mediaObjectId: String? = null,
    val durationSeconds: Int? = null,
)

/**
 * Built-in default types (spec/object.md).
 */
object DefaultTypes {
    fun normalizeStatusOption(optionId: String?): String =
        TaskStatus.fromId(optionId).id

    fun normalizePriorityOption(optionId: String?): String? =
        TaskPriority.fromId(optionId)?.id

    fun normalizeObject(obj: PkmObject): PkmObject {
        val canonicalTypeId = obj.typeId
        val normalizedProps = buildMap<String, PropertyValue> {
            for ((rawKey, rawVal) in obj.properties) {
                val key = rawKey
                val normalizedVal = when {
                    canonicalTypeId == CanonicalTypeIds.TASK && key == PropertyKeys.STATUS && rawVal is PropertyValue.Select -> {
                        PropertyValue.Select(normalizeStatusOption(rawVal.selectedOptionId))
                    }
                    canonicalTypeId == CanonicalTypeIds.TASK && key == PropertyKeys.PRIORITY && rawVal is PropertyValue.Select -> {
                        PropertyValue.Select(normalizePriorityOption(rawVal.selectedOptionId))
                    }
                    else -> rawVal
                }
                put(key, normalizedVal)
            }
        }
        return if (normalizedProps == obj.properties) obj else obj.copy(properties = normalizedProps)
    }

    val NOTE = ObjectType(
        id = CanonicalTypeIds.NOTE,
        name = "Note",
        icon = ObjectIcon(emoji = "📝", iconName = "text-x-generic"),
        colorHex = "#2AA7EE", // --color-ice
        defaultProperties = listOf(
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val DAILY = ObjectType(
        id = CanonicalTypeIds.JOURNAL,
        name = "Daily Note",
        icon = ObjectIcon(emoji = "📅", iconName = "view-calendar-day"),
        colorHex = "#5DD400", // --color-lime
        defaultProperties = listOf(
            PropertyDefinition(PropertyKeys.DATE, "Date", PropertyKind.DATE_RANGE, isSystem = true),
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val BOOK = ObjectType(
        id = CanonicalTypeIds.BOOK,
        name = "Book",
        icon = ObjectIcon(emoji = "📚", iconName = "accessories-dictionary"),
        colorHex = "#FFB522", // --color-orange
        defaultProperties = listOf(
            PropertyDefinition(PropertyKeys.AUTHOR, "Author", PropertyKind.RICH_TEXT),
            PropertyDefinition(
                PropertyKeys.STATUS,
                "Status",
                PropertyKind.SELECT,
                options = listOf(
                    SelectOption("to_read", "To Read", "#F55522"),
                    SelectOption("reading", "Reading", "#FFB522"),
                    SelectOption("finished", "Finished", "#5DD400"),
                ),
            ),
            PropertyDefinition(
                PropertyKeys.MEDIUM,
                "Format",
                PropertyKind.SELECT,
                options = listOf(
                    SelectOption("paperback", "Paperback", "#2AA7EE"),
                    SelectOption("hardcover", "Hardcover", "#3E58EB"),
                    SelectOption("ebook", "E-Book", "#0FC8BA"),
                    SelectOption("audiobook", "Audiobook", "#AB50CC"),
                ),
            ),
            PropertyDefinition(PropertyKeys.RATING, "Rating", PropertyKind.NUMBER),
            PropertyDefinition(PropertyKeys.RECOMMENDED_BY, "Recommended By", PropertyKind.RICH_TEXT),
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val TASK = ObjectType(
        id = CanonicalTypeIds.TASK,
        name = "Task",
        icon = ObjectIcon(emoji = "✅", iconName = "circle-check"),
        colorHex = "#F55522", // --color-red
        defaultProperties = listOf(
            PropertyDefinition(
                PropertyKeys.STATUS,
                "Status",
                PropertyKind.SELECT,
                options = listOf(
                    SelectOption(TaskStatus.TODO.id, "To Do", "#F55522"),
                    SelectOption(TaskStatus.IN_PROGRESS.id, "In Progress", "#FFB522"),
                    SelectOption(TaskStatus.DONE.id, "Done", "#5DD400"),
                ),
            ),
            PropertyDefinition(PropertyKeys.DUE_DATE, "Due Date", PropertyKind.DATE_RANGE),
            PropertyDefinition(
                PropertyKeys.PRIORITY,
                "Priority",
                PropertyKind.SELECT,
                options = listOf(
                    SelectOption(TaskPriority.LOW.id, "Low", "#8E8E93"),
                    SelectOption(TaskPriority.MEDIUM.id, "Medium", "#FFB522"),
                    SelectOption(TaskPriority.HIGH.id, "High", "#F55522"),
                ),
            ),
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val PERSON = ObjectType(
        id = CanonicalTypeIds.PERSON,
        name = "Person",
        icon = ObjectIcon(emoji = "👤", iconName = "avatar-default"),
        colorHex = "#E51CA0", // --color-pink
        defaultProperties = listOf(
            PropertyDefinition(PropertyKeys.EMAIL, "Email", PropertyKind.RICH_TEXT),
            PropertyDefinition(PropertyKeys.ORGANIZATION, "Organization", PropertyKind.RICH_TEXT),
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val PROJECT = ObjectType(
        id = CanonicalTypeIds.PROJECT,
        name = "Project",
        icon = ObjectIcon(emoji = "📁", iconName = "folder"),
        colorHex = "#3E58EB", // --color-cobalt
        defaultProperties = listOf(
            PropertyDefinition(
                PropertyKeys.STATUS,
                "Status",
                PropertyKind.SELECT,
                options = listOf(
                    SelectOption("active", "Active", "#5DD400"),
                    SelectOption("on_hold", "On Hold", "#FFB522"),
                    SelectOption("completed", "Completed", "#8E8E93"),
                ),
            ),
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val HABIT = ObjectType(
        id = CanonicalTypeIds.HABIT,
        name = "Habit",
        icon = ObjectIcon(emoji = "🌱", iconName = "habit"),
        colorHex = "#86E8A8",
        defaultProperties = listOf(
            PropertyDefinition(HabitKeys.KIND, "Kind", PropertyKind.SELECT, options = listOf(
                SelectOption("check", "Yes/No"), SelectOption("count", "Count"), SelectOption("time", "Minutes"))),
            PropertyDefinition(HabitKeys.GOAL, "Goal", PropertyKind.NUMBER),
            PropertyDefinition(HabitKeys.UNIT, "Unit", PropertyKind.RICH_TEXT),
            PropertyDefinition(HabitKeys.FREQ, "Frequency", PropertyKind.SELECT, options = listOf(
                SelectOption("daily", "Daily"), SelectOption("weekdays", "Specific days"),
                SelectOption("per_week", "Per week"), SelectOption("per_month", "Per month"))),
            PropertyDefinition(HabitKeys.SLOT, "Time of day", PropertyKind.SELECT, options = listOf(
                SelectOption("morning", "Morning"), SelectOption("afternoon", "Afternoon"),
                SelectOption("evening", "Evening"), SelectOption("any", "Any"))),
            PropertyDefinition(HabitKeys.STATE, "State", PropertyKind.SELECT, options = listOf(
                SelectOption("active", "Active"), SelectOption("paused", "Paused"), SelectOption("archived", "Archived"))),
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val WEBLINK = ObjectType(
        id = CanonicalTypeIds.WEB,
        name = "Web Link",
        icon = ObjectIcon(emoji = "🌐", iconName = "web-browser"),
        colorHex = "#0FC8BA", // --color-teal
        defaultProperties = listOf(
            PropertyDefinition(PropertyKeys.URL, "URL", PropertyKind.URL, isSystem = true),
            PropertyDefinition(PropertyKeys.SITE_NAME, "Site Name", PropertyKind.RICH_TEXT),
            PropertyDefinition(PropertyKeys.REVIEWED, "Reviewed", PropertyKind.CHECKBOX),
            PropertyDefinition(PropertyKeys.IS_BOOKMARK, "Bookmark", PropertyKind.CHECKBOX),
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val IMAGE = ObjectType(
        id = CanonicalTypeIds.IMAGE,
        name = "Image",
        icon = ObjectIcon(emoji = "🖼️", iconName = "image-x-generic"),
        colorHex = "#AB50CC", // --color-purple
        defaultProperties = listOf(
            PropertyDefinition(PropertyKeys.ASSET_ID, "Asset ID", PropertyKind.RICH_TEXT, isSystem = true),
            PropertyDefinition(PropertyKeys.FILE_NAME, "File Name", PropertyKind.RICH_TEXT, isSystem = true),
            PropertyDefinition(PropertyKeys.MIME, "MIME Type", PropertyKind.RICH_TEXT, isSystem = true),
            PropertyDefinition(PropertyKeys.SIZE, "Size", PropertyKind.NUMBER, isSystem = true),
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val PDF = ObjectType(
        id = CanonicalTypeIds.PDF,
        name = "PDF",
        icon = ObjectIcon(emoji = "📄", iconName = "application-pdf"),
        colorHex = "#ECD91B", // --color-yellow
        defaultProperties = listOf(
            PropertyDefinition(PropertyKeys.ASSET_ID, "Asset ID", PropertyKind.RICH_TEXT, isSystem = true),
            PropertyDefinition(PropertyKeys.FILE_NAME, "File Name", PropertyKind.RICH_TEXT, isSystem = true),
            PropertyDefinition(PropertyKeys.MIME, "MIME Type", PropertyKind.RICH_TEXT, isSystem = true),
            PropertyDefinition(PropertyKeys.SIZE, "Size", PropertyKind.NUMBER, isSystem = true),
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val AUDIO = ObjectType(
        id = CanonicalTypeIds.AUDIO,
        name = "Audio",
        icon = ObjectIcon(emoji = "🎙️", iconName = "music"),
        colorHex = "#E51CA0", // --color-pink
        defaultProperties = listOf(
            PropertyDefinition(PropertyKeys.ASSET_ID, "Asset ID", PropertyKind.RICH_TEXT, isSystem = true),
            PropertyDefinition(PropertyKeys.FILE_NAME, "File Name", PropertyKind.RICH_TEXT, isSystem = true),
            PropertyDefinition(PropertyKeys.MIME, "MIME Type", PropertyKind.RICH_TEXT, isSystem = true),
            PropertyDefinition(PropertyKeys.DURATION, "Duration", PropertyKind.NUMBER, isSystem = true),
            PropertyDefinition(PropertyKeys.SIZE, "Size", PropertyKind.NUMBER, isSystem = true),
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val COLLECTION = ObjectType(
        id = CanonicalTypeIds.COLLECTION,
        name = "Collection",
        icon = ObjectIcon(emoji = "📑", iconName = "view-list-details"),
        colorHex = "#3E58EB", // --color-cobalt
        defaultProperties = listOf(
            PropertyDefinition(PropertyKeys.ITEM_TYPE_ID, "Item Type", PropertyKind.SELECT),
            PropertyDefinition(PropertyKeys.ITEMS, "Items", PropertyKind.OBJECT_SELECT),
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val TAG = ObjectType(
        id = CanonicalTypeIds.TAG,
        name = "Tag",
        icon = ObjectIcon(emoji = "🏷️", iconName = "tag"),
        colorHex = "#0FC8BA", // --color-teal
        defaultProperties = listOf(
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    val WHITEBOARD = ObjectType(
        id = CanonicalTypeIds.WHITEBOARD,
        name = "Whiteboard",
        icon = ObjectIcon(emoji = "🎨", iconName = "canvas"),
        colorHex = "#3E58EB", // --color-cobalt
        defaultProperties = listOf(
            PropertyDefinition(PropertyKeys.TAGS, "Tags", PropertyKind.MULTI_SELECT, isSystem = true),
        ),
    )

    /** Saved set (spec/query.md): its query and views live in properties query/views/active_view. */
    val SET = ObjectType(
        id = CanonicalTypeIds.SET,
        name = "Set",
        icon = ObjectIcon(emoji = "🔎", iconName = "filter"),
        colorHex = "#C9F2A6",
        defaultProperties = emptyList(),
    )

    /** Types the app manages itself (not offered as a type to create objects from). */
    val SYSTEM = listOf(SET)

    /**
     * Primordial objects (fixed Capacities types):
     * Daily Note (Journal), Web Link, Image, PDF (Documents), Audio, and Tag.
     */
    val PRIMORDIAL = listOf(DAILY, WEBLINK, IMAGE, PDF, AUDIO, TAG)

    /**
     * Standard / user configurable types.
     */
    val STANDARD = listOf(NOTE, BOOK, TASK, PERSON, PROJECT, HABIT, COLLECTION, WHITEBOARD)

    val ALL = PRIMORDIAL + STANDARD + SYSTEM

    fun isPrimordial(typeId: String): Boolean = PRIMORDIAL.any { it.id == typeId }

}

object TagPalette {
    // 10 semantic content colors (--color-*)
    val PALETTE = listOf(
        "#FFB522", // --color-orange
        "#ECD91B", // --color-yellow
        "#F55522", // --color-red
        "#E51CA0", // --color-pink
        "#AB50CC", // --color-purple
        "#3E58EB", // --color-cobalt
        "#2AA7EE", // --color-ice
        "#0FC8BA", // --color-teal
        "#5DD400", // --color-lime
        "#8E8E93", // --color-grey
    )

    fun getColorHex(index: Int): String {
        val positiveIndex = kotlin.math.abs(index)
        return PALETTE[positiveIndex % PALETTE.size]
    }
}

/**
 * Whiteboard domain accessors on [PkmObject].
 */
fun PkmObject.getWhiteboardData(): org.yaop.core.model.whiteboard.WhiteboardData {
    val canvasBlock = blocks.filterIsInstance<Block.Canvas>().firstOrNull()
    return canvasBlock?.data ?: org.yaop.core.model.whiteboard.WhiteboardData(id = id)
}

fun PkmObject.withWhiteboardData(data: org.yaop.core.model.whiteboard.WhiteboardData): PkmObject {
    val existing = blocks.filterIsInstance<Block.Canvas>().firstOrNull()
    val updated = (existing ?: Block.Canvas(id = java.util.UUID.randomUUID().toString())).copy(data = data)
    val remaining = blocks.filterNot { it is Block.Canvas }
    return copy(blocks = listOf(updated) + remaining)
}
