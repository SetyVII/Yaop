package org.yaop.core.model

/**
 * Canonical property keys used across YAOP models, server sync wire formats,
 * database mapping, and editor forms.
 */
object PropertyKeys {
    const val STATUS = "status"
    const val DUE_DATE = "due_date"
    const val PRIORITY = "priority"
    const val DATE = "date"
    const val TAGS = "tags"
    const val AUTHOR = "author"
    const val MEDIUM = "medium"
    const val RATING = "rating"
    const val RECOMMENDED_BY = "recommended_by"
    const val EMAIL = "email"
    const val ORGANIZATION = "organization"
    const val URL = "url"
    const val SITE_NAME = "site_name"
    const val REVIEWED = "reviewed"
    const val IS_BOOKMARK = "is_bookmark"
    const val ASSET_ID = "asset_id"
    const val FILE_NAME = "file_name"
    const val MIME = "mime"
    const val SIZE = "size"
    const val DURATION = "duration"
    const val ITEM_TYPE_ID = "item_type_id"
    const val ITEMS = "items"
}

/**
 * Canonical type IDs matching the server PKM schema and primordial/standard definitions.
 */
object CanonicalTypeIds {
    const val NOTE = "note"
    const val JOURNAL = "journal"
    const val BOOK = "book"
    const val TASK = "task"
    const val PERSON = "person"
    const val PROJECT = "project"
    const val WEB = "web"
    const val IMAGE = "image"
    const val PDF = "pdf"
    const val AUDIO = "audio"
    const val COLLECTION = "collection"
    const val TAG = "tag"
    const val WHITEBOARD = "whiteboard"
    const val SET = "set"
    const val HABIT = "habit"
}

/**
 * Strongly-typed task execution states.
 */
enum class TaskStatus(val id: String) {
    TODO("todo"),
    IN_PROGRESS("in_progress"),
    DONE("done");

    val isDone: Boolean get() = this == DONE

    fun toggled(): TaskStatus = if (isDone) TODO else DONE

    companion object {
        fun fromId(raw: String?): TaskStatus = when (raw?.lowercase()?.trim()) {
            "opt_done", "done", "hecho", "completada", "completed" -> DONE
            "opt_in_progress", "in_progress", "doing", "en_progreso" -> IN_PROGRESS
            "opt_todo", "todo", null, "" -> TODO
            else -> TODO
        }
    }
}

/**
 * Strongly-typed task priority levels.
 */
enum class TaskPriority(val id: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun fromId(raw: String?): TaskPriority? = when (raw?.lowercase()?.trim()) {
            "opt_low", "low", "baja" -> LOW
            "opt_medium", "medium", "media" -> MEDIUM
            "opt_high", "high", "alta" -> HIGH
            null, "" -> null
            else -> null
        }
    }
}

/**
 * Object synchronization state.
 */
enum class SyncStatus(val id: String) {
    LOCAL("local"),
    SYNCED("synced"),
    SYNCING("syncing"),
    ERROR("error");

    companion object {
        fun fromId(raw: String?): SyncStatus = when (raw?.lowercase()?.trim()) {
            "synced" -> SYNCED
            "syncing" -> SYNCING
            "error" -> ERROR
            else -> LOCAL
        }
    }
}

/**
 * Domain extensions on [PkmObject] for cleaner, type-safe property manipulation.
 */
val PkmObject.taskStatusEnum: TaskStatus
    get() {
        val raw = (property(PropertyKeys.STATUS) as? PropertyValue.Select)?.selectedOptionId
        return TaskStatus.fromId(raw)
    }

fun PkmObject.withTaskStatus(status: TaskStatus): PkmObject {
    val updatedProps = properties.toMutableMap()
    updatedProps[PropertyKeys.STATUS] = PropertyValue.Select(status.id)
    return copy(properties = updatedProps)
}

fun PkmObject.toggleTaskStatus(): PkmObject =
    withTaskStatus(taskStatusEnum.toggled())

fun PkmObject.cycleTaskPriority(): PkmObject {
    val current = TaskPriority.fromId(taskPriority)
    val next = when (current) {
        null -> TaskPriority.LOW
        TaskPriority.LOW -> TaskPriority.MEDIUM
        TaskPriority.MEDIUM -> TaskPriority.HIGH
        TaskPriority.HIGH -> null
    }
    val updatedProps = properties.toMutableMap()
    if (next != null) {
        updatedProps[PropertyKeys.PRIORITY] = PropertyValue.Select(next.id)
    } else {
        updatedProps.remove(PropertyKeys.PRIORITY)
    }
    return copy(properties = updatedProps)
}

fun PkmObject.withProperty(key: String, value: PropertyValue): PkmObject {
    val updatedProps = properties.toMutableMap()
    updatedProps[key] = value
    return copy(properties = updatedProps)
}
