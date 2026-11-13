package org.yaop.ui.daily

import org.yaop.ui.common.privacyImage
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SwapVert
import org.yaop.ui.common.SubtleSwipeActionBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import org.yaop.R
import org.yaop.core.model.Block
import org.yaop.core.model.CoverSpec
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.HybridLogicalClock
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.ObjectIcon
import org.yaop.core.model.ObjectType
import org.yaop.core.model.PropertyKeys
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.TaskStatus
import org.yaop.core.model.toPlainText
import org.yaop.core.model.toTextSpan
import org.yaop.core.model.toggleTaskStatus
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.common.TypeBadge
import org.yaop.ui.common.extractAudioDurationSeconds
import org.yaop.ui.common.rememberDecodedThumbnail
import org.yaop.ui.common.resolveAudioAssetId
import org.yaop.ui.common.resolveImageAssetId
import org.yaop.ui.editor.AudioPlayerRow
import org.yaop.ui.editor.BlockListEditor
import org.yaop.ui.editor.BlockOperations
import org.yaop.ui.editor.EditorKeyboardPanelType
import org.yaop.ui.editor.EditorToolbarRail
import org.yaop.ui.editor.LocalEditorToolbarController
import org.yaop.ui.editor.rememberEditorToolbarController
import org.yaop.ui.editor.EditorBottomSheet
import org.yaop.ui.theme.AppColors
import org.yaop.ui.theme.YaopTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

enum class JournalViewTab {
    JOURNAL,
    REFERENCES,
    CREATED,
}

private val INLINE_ISO_DATE_REGEX = Regex("""\b(\d{4}-\d{2}-\d{2})\b""")

private data class DailyDateRelations(
    val relatedTasks: List<PkmObject> = emptyList(),
    val relatedDates: List<PkmObject> = emptyList(),
    val relatedObjectsAndTags: List<PkmObject> = emptyList(),
    val createdOnDateObjects: List<PkmObject> = emptyList(),
)

@Volatile
private var cachedActivityDates: Set<LocalDate> = emptySet()

@Volatile
private var cachedActivitySignature: Int = 0

private fun parseIsoLocalDateFast(raw: String): LocalDate? {
    if (raw.length < 10 || raw[4] != '-' || raw[7] != '-') return null
    val y = (raw[0] - '0') * 1000 + (raw[1] - '0') * 100 + (raw[2] - '0') * 10 + (raw[3] - '0')
    val m = (raw[5] - '0') * 10 + (raw[6] - '0')
    val d = (raw[8] - '0') * 10 + (raw[9] - '0')
    if (y !in 1970..2100 || m !in 1..12 || d !in 1..31) return null
    return try {
        LocalDate.of(y, m, d)
    } catch (_: Exception) {
        null
    }
}

fun parseCreatedInstant(obj: PkmObject): Instant? {
    val raw = obj.createdAt
    if (raw.isNotEmpty()) {
        try {
            return Instant.parse(raw)
        } catch (_: Exception) {
        }
        try {
            return java.time.OffsetDateTime.parse(raw).toInstant()
        } catch (_: Exception) {
        }
        try {
            return java.time.LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant()
        } catch (_: Exception) {
        }
    }
    return runCatching {
        val (wallMillis, _) = HybridLogicalClock.parse(obj.hlc)
        if (wallMillis > 0) Instant.ofEpochMilli(wallMillis) else null
    }.getOrNull()
}

fun isObjectCreatedOnDate(
    obj: PkmObject,
    date: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault(),
    dateIso: String = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
): Boolean {
    if (obj.createdAt.startsWith(dateIso)) {
        val instant = parseCreatedInstant(obj)
        return if (instant != null) {
            instant.atZone(zoneId).toLocalDate() == date
        } else {
            true
        }
    }
    // Check adjacent UTC date in case local timezone shifted the calendar day vs UTC ISO prefix
    val fastDate = parseIsoLocalDateFast(obj.createdAt)
    if (fastDate != null && kotlin.math.abs(ChronoUnit.DAYS.between(fastDate, date)) > 1L) {
        return false
    }
    val instant = parseCreatedInstant(obj) ?: return false
    return instant.atZone(zoneId).toLocalDate() == date
}

/**
 * Scans every object once to build the daily note relationships (tasks, date properties,
 * mentions and objects created on [selectedDate]). Pure function so it can run on a background
 * dispatcher via `produceState`.
 */
private fun computeDailyRelations(
    allActiveObjects: List<PkmObject>,
    selectedDate: LocalDate,
    formattedDateKey: String,
    currentDailyId: String?,
): DailyDateRelations {
    val tasks = mutableListOf<PkmObject>()
    val dates = mutableListOf<PkmObject>()
    val mentions = mutableListOf<PkmObject>()
    val created = mutableListOf<PkmObject>()
    val dailyTargetId = "daily-$formattedDateKey"
    val zoneId = ZoneId.systemDefault()

    for (obj in allActiveObjects) {
        val isDaily = obj.typeId == DefaultTypes.DAILY.id || obj.typeId == "type_daily"
        if (!isDaily && isObjectCreatedOnDate(obj, selectedDate, zoneId, formattedDateKey)) {
            created.add(obj)
        }
        val isCurrentDaily = obj.id == currentDailyId ||
            obj.id == dailyTargetId ||
            obj.journalDateIso == formattedDateKey
        if (isCurrentDaily) continue

        val isTask = obj.typeId == DefaultTypes.TASK.id
        val hasDateProp = obj.properties.any { (_, propValue) ->
            (propValue as? PropertyValue.DateRange)?.startDate == formattedDateKey
        }

        if (isTask) {
            val hasBlockDateMention = !hasDateProp && obj.blocks.any { block ->
                BlockOperations.extractSpans(block).any { span ->
                    (span is InlineSpan.Date && span.isoDate.removePrefix("@").trim().startsWith(formattedDateKey)) ||
                        (span is InlineSpan.Mention && (span.targetObjectId == dailyTargetId || span.targetObjectId == formattedDateKey))
                }
            }
            if (hasDateProp || hasBlockDateMention) {
                tasks.add(obj)
            }
        } else {
            if (hasDateProp) {
                dates.add(obj)
            }

            val hasTitleMention = obj.plainTitle.contains(formattedDateKey)
            val hasBlockMention = !hasTitleMention && obj.blocks.any { block ->
                BlockOperations.extractSpans(block).any { span ->
                    when (span) {
                        is InlineSpan.Date -> span.isoDate.removePrefix("@").trim().startsWith(formattedDateKey)
                        is InlineSpan.Mention -> span.targetObjectId == dailyTargetId ||
                            span.targetObjectId == formattedDateKey ||
                            span.fallbackText?.contains(formattedDateKey) == true
                        is InlineSpan.Text -> span.text.contains(formattedDateKey)
                        else -> false
                    }
                }
            }
            val hasPropMention = !hasTitleMention && !hasBlockMention && obj.properties.any { (_, propValue) ->
                when (propValue) {
                    is PropertyValue.ObjectRelation -> propValue.targetObjectIds.any {
                        it == dailyTargetId || it == formattedDateKey
                    }
                    is PropertyValue.RichText -> propValue.spans.any { span ->
                        (span is InlineSpan.Date && span.isoDate.removePrefix("@").trim().startsWith(formattedDateKey)) ||
                            (span is InlineSpan.Mention && (span.targetObjectId == dailyTargetId || span.targetObjectId == formattedDateKey))
                    }
                    else -> false
                }
            }
            if (hasTitleMention || hasBlockMention || hasPropMention) {
                mentions.add(obj)
            }
        }
    }

    return DailyDateRelations(
        relatedTasks = tasks,
        relatedDates = dates,
        relatedObjectsAndTags = mentions,
        createdOnDateObjects = created,
    )
}

fun getObjectLocalTime(obj: PkmObject, zoneId: ZoneId = ZoneId.systemDefault()): LocalTime? {
    val instant = parseCreatedInstant(obj) ?: return null
    return instant.atZone(zoneId).toLocalTime()
}

fun formatObjectTime(time: LocalTime, locale: Locale): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    return time.format(formatter)
}

fun getObjectPreviewSnippet(obj: PkmObject, mentionDateKey: String? = null): String {
    if (!mentionDateKey.isNullOrBlank()) {
        for (block in obj.blocks) {
            val spans = BlockOperations.extractSpans(block)
            val text = spans.toPlainText().trim()
            if (text.contains(mentionDateKey) || spans.any { (it is InlineSpan.Date && it.isoDate.contains(mentionDateKey)) || (it is InlineSpan.Mention && it.targetObjectId.contains(mentionDateKey)) }) {
                return text
            }
        }
    }
    if (obj.plainSummary.isNotBlank()) return obj.plainSummary
    for (block in obj.blocks) {
        val spans = BlockOperations.extractSpans(block)
        val text = spans.toPlainText().trim()
        if (text.isNotBlank()) return text
    }
    return ""
}

@Composable
fun DailyScreen(
    repository: PkmRepository,
    onNavigateToObject: (String) -> Unit,
    initialDate: LocalDate? = null,
    onBack: (() -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val currentLocale = remember(configuration) {
        configuration.locales[0] ?: Locale.getDefault()
    }
    var selectedDate by remember { mutableStateOf(initialDate ?: LocalDate.now()) }
    LaunchedEffect(initialDate) {
        if (initialDate != null) {
            selectedDate = initialDate
        }
    }
    val formattedDateKey = remember(selectedDate) { selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE) }

    val allDailyObjects by repository.getObjectsByType(DefaultTypes.DAILY.id)
        .collectAsState(initial = repository.getCachedObjectsByType(DefaultTypes.DAILY.id))
    val allActiveObjects by repository.getAllActiveObjects()
        .collectAsState(initial = repository.getCachedAllActiveObjects())
    val allTypes by repository.getAllTypes()
        .collectAsState(initial = repository.getCachedTypes().ifEmpty { DefaultTypes.ALL })

    val currentDailyObject = remember(selectedDate, allDailyObjects) {
        allDailyObjects.find { item ->
            val dateProp = item.journalDateIso
            (dateProp == formattedDateKey) || (item.id == "daily-$formattedDateKey")
        }
    }

    val datesWithActivity by produceState(
        initialValue = cachedActivityDates,
        key1 = allActiveObjects,
        key2 = allDailyObjects,
    ) {
        // Cheap revision-based signature: restarts when any object changes, but skips the full
        // block/span scan when only unrelated metadata changed.
        val sig = allActiveObjects.fold(17) { acc, obj -> 31 * acc + obj.revision.hashCode() } * 31 +
            allDailyObjects.fold(17) { acc, obj -> 31 * acc + obj.revision.hashCode() }
        if (sig == cachedActivitySignature && cachedActivityDates.isNotEmpty()) {
            value = cachedActivityDates
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val dateSet = mutableSetOf<LocalDate>()
            val defaultZone = ZoneId.systemDefault()
            // 1. Daily notes with content
            for (daily in allDailyObjects) {
                if (daily.blocks.isNotEmpty() || daily.summary.isNotEmpty()) {
                    val iso = daily.journalDateIso ?: if (daily.id.startsWith("daily-")) daily.id.removePrefix("daily-") else null
                    iso?.let(::parseIsoLocalDateFast)?.let { dateSet.add(it) }
                }
            }
            // 2. Objects created on dates, with DateRange properties, or mentioning dates
            for (obj in allActiveObjects) {
                parseIsoLocalDateFast(obj.createdAt)?.let { dateSet.add(it) }
                    ?: parseCreatedInstant(obj)?.atZone(defaultZone)?.toLocalDate()?.let { dateSet.add(it) }

                for ((_, propValue) in obj.properties) {
                    if (propValue is PropertyValue.DateRange) {
                        parseIsoLocalDateFast(propValue.startDate)?.let { dateSet.add(it) }
                    }
                }

                for (block in obj.blocks) {
                    for (span in BlockOperations.extractSpans(block)) {
                        when (span) {
                            is InlineSpan.Date -> {
                                val clean = span.isoDate.removePrefix("@").trim()
                                parseIsoLocalDateFast(clean)?.let { dateSet.add(it) }
                            }
                            is InlineSpan.Mention -> {
                                if (span.targetObjectId.startsWith("daily-")) {
                                    val clean = span.targetObjectId.removePrefix("daily-")
                                    parseIsoLocalDateFast(clean)?.let { dateSet.add(it) }
                                }
                            }
                            is InlineSpan.Text -> {
                                if (span.text.indexOf('-') >= 0) {
                                    INLINE_ISO_DATE_REGEX.findAll(span.text).forEach { m ->
                                        parseIsoLocalDateFast(m.groupValues[1])?.let { dateSet.add(it) }
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
            cachedActivityDates = dateSet
            cachedActivitySignature = sig
            dateSet
        }
    }

    // Relationship scanning is O(objects × blocks). Run it off the main thread so switching dates
    // or receiving a database emission never blocks the UI / calendar pager.
    val dailyRelations by produceState(
        initialValue = DailyDateRelations(),
        selectedDate,
        formattedDateKey,
        allActiveObjects,
        currentDailyObject?.id,
    ) {
        value = withContext(Dispatchers.Default) {
            computeDailyRelations(
                allActiveObjects = allActiveObjects,
                selectedDate = selectedDate,
                formattedDateKey = formattedDateKey,
                currentDailyId = currentDailyObject?.id,
            )
        }
    }

    val relatedTasks = dailyRelations.relatedTasks
    val relatedDates = dailyRelations.relatedDates
    val relatedObjectsAndTags = dailyRelations.relatedObjectsAndTags
    val createdOnDateObjects = dailyRelations.createdOnDateObjects

    val onQuickCreate: (String, Boolean) -> Unit = { titleText, isTask ->
        coroutineScope.launch {
            val newId = UUID.randomUUID().toString()
            val targetInstant = if (selectedDate == LocalDate.now()) {
                Instant.now()
            } else {
                selectedDate.atTime(LocalTime.now()).atZone(ZoneId.systemDefault()).toInstant()
            }
            val timestampStr = targetInstant.toString()
            val typeId = if (isTask) DefaultTypes.TASK.id else DefaultTypes.NOTE.id
            val iconEmoji = if (isTask) "☑" else "📝"
            val props = if (isTask) {
                mapOf(
                    PropertyKeys.STATUS to PropertyValue.Select(TaskStatus.TODO.id),
                    PropertyKeys.DUE_DATE to PropertyValue.DateRange(formattedDateKey),
                )
            } else {
                emptyMap()
            }
            val newObj = PkmObject(
                id = newId,
                typeId = typeId,
                title = titleText.toTextSpan(),
                icon = ObjectIcon(emoji = iconEmoji),
                createdAt = timestampStr,
                updatedAt = timestampStr,
                properties = props,
            )
            repository.saveObject(newObj)
        }
    }

    val onCreateObject: (String) -> Unit = { typeId ->
        coroutineScope.launch {
            val newId = UUID.randomUUID().toString()
            val targetInstant = if (selectedDate == LocalDate.now()) {
                Instant.now()
            } else {
                selectedDate.atTime(LocalTime.now()).atZone(ZoneId.systemDefault()).toInstant()
            }
            val timestampStr = targetInstant.toString()
            val selectedType = allTypes.find { it.id == typeId }
            val isTask = typeId == DefaultTypes.TASK.id || typeId == "type_task"
            val props = if (isTask) {
                mapOf(
                    PropertyKeys.STATUS to PropertyValue.Select(TaskStatus.TODO.id),
                    PropertyKeys.DUE_DATE to PropertyValue.DateRange(formattedDateKey),
                )
            } else {
                emptyMap()
            }
            val newObj = PkmObject(
                id = newId,
                typeId = typeId,
                title = emptyList(),
                icon = selectedType?.icon ?: ObjectIcon(emoji = "📝"),
                createdAt = timestampStr,
                updatedAt = timestampStr,
                properties = props,
            )
            repository.saveObject(newObj)
            onNavigateToObject(newId)
        }
    }

    var pendingSaveJob by remember(formattedDateKey) { mutableStateOf<Job?>(null) }
    val latestUnsavedDailyRef = remember(formattedDateKey) { arrayOf<PkmObject?>(null) }

    fun flushDailySaveNow() {
        if (pendingSaveJob?.isActive == true) {
            pendingSaveJob?.cancel()
            pendingSaveJob = null
            latestUnsavedDailyRef[0]?.let { unsaved ->
                coroutineScope.launch {
                    repository.saveObject(unsaved)
                }
            }
        }
    }

    val currentDailyId = currentDailyObject?.id ?: "daily-$formattedDateKey"
    DisposableEffect(currentDailyId) {
        repository.activeEditingObjectId = currentDailyId
        onDispose {
            if (repository.activeEditingObjectId == currentDailyId) {
                repository.activeEditingObjectId = null
            }
        }
    }

    DisposableEffect(formattedDateKey) {
        onDispose {
            flushDailySaveNow()
        }
    }

    DailyContent(
        selectedDate = selectedDate,
        onDateChange = {
            flushDailySaveNow()
            selectedDate = it
        },
        currentDailyObject = currentDailyObject,
        onCreateDailyObject = {
            coroutineScope.launch {
                val humanDate = selectedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(currentLocale))
                val newObj = PkmObject(
                    id = "daily-$formattedDateKey",
                    typeId = DefaultTypes.DAILY.id,
                    title = humanDate.toTextSpan(),
                    icon = ObjectIcon(emoji = "📅"),
                    properties = mapOf(
                        "date" to PropertyValue.DateRange(formattedDateKey),
                    ),
                )
                repository.saveObject(newObj)
            }
        },
        onUpdateBlocks = { newBlocks ->
            val base = latestUnsavedDailyRef[0]?.copy(
                properties = currentDailyObject?.properties ?: latestUnsavedDailyRef[0]!!.properties,
                icon = currentDailyObject?.icon ?: latestUnsavedDailyRef[0]!!.icon,
                cover = currentDailyObject?.cover ?: latestUnsavedDailyRef[0]!!.cover,
            ) ?: currentDailyObject
            if (base != null) {
                val updated = base.copy(blocks = newBlocks)
                latestUnsavedDailyRef[0] = updated
                pendingSaveJob?.cancel()
                pendingSaveJob = coroutineScope.launch {
                    delay(1500L)
                    repository.saveObject(updated)
                }
            }
        },
        createdOnDateObjects = createdOnDateObjects,
        relatedTasks = relatedTasks,
        relatedDates = relatedDates,
        relatedObjectsAndTags = relatedObjectsAndTags,
        allTypes = allTypes,
        datesWithActivity = datesWithActivity,
        onNavigateToObject = onNavigateToObject,
        onToggleTask = { taskObj ->
            coroutineScope.launch {
                repository.saveObject(taskObj.toggleTaskStatus())
            }
        },
        onQuickCreate = onQuickCreate,
        onCreateObject = onCreateObject,
        onBack = onBack,
        repository = repository,
    )
}

@Composable
fun DailyContent(
    selectedDate: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    currentDailyObject: PkmObject?,
    onCreateDailyObject: () -> Unit,
    onUpdateBlocks: (List<Block>) -> Unit,
    createdOnDateObjects: List<PkmObject> = emptyList(),
    relatedTasks: List<PkmObject>,
    relatedDates: List<PkmObject>,
    relatedObjectsAndTags: List<PkmObject>,
    allTypes: List<ObjectType>,
    datesWithActivity: Set<LocalDate> = emptySet(),
    onNavigateToObject: (String) -> Unit,
    onToggleTask: (PkmObject) -> Unit,
    onQuickCreate: (String, Boolean) -> Unit = { _, _ -> },
    onCreateObject: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    repository: PkmRepository? = null,
) {
    var activeTab by remember { mutableStateOf(JournalViewTab.JOURNAL) }
    var isCalendarExpanded by remember { mutableStateOf(value = false) }
    var showEditorBottomSheet by remember { mutableStateOf(value = false) }
    val coroutineScope = rememberCoroutineScope()

    // Contextual editing toolbar hosted here (same as ObjectDetailScreen):
    // the editor publishes visibility/actions, this rail renders them pinned
    // above the software keyboard.
    val toolbarController = rememberEditorToolbarController()

    val configuration = LocalConfiguration.current
    val currentLocale = remember(configuration) {
        configuration.locales[0] ?: Locale.getDefault()
    }
    val formattedDateKey = remember(selectedDate) { selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE) }

    val weekNumber = remember(selectedDate, currentLocale) {
        selectedDate[WeekFields.of(currentLocale).weekOfYear()]
    }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible && isCalendarExpanded) {
            isCalendarExpanded = false
        }
    }

    // The contextual toolbar is drawn as a bottom overlay. Reserve its height in the scroll
    // viewport; otherwise the last line being typed sits underneath the toolbar and looks
    // "covered by the keyboard" even though imePadding() already moved the content up.
    val isToolbarVisible = activeTab == JournalViewTab.JOURNAL &&
        toolbarController.visible &&
        (imeVisible || toolbarController.forceVisibleWithoutIme ||
            toolbarController.panelType != EditorKeyboardPanelType.NONE)
    val toolbarHeight = when {
        !isToolbarVisible -> 0.dp
        toolbarController.panelType != EditorKeyboardPanelType.NONE ->
            48.dp + toolbarController.lastImeHeightDp
        toolbarController.dynamicRowContent != null -> 92.dp
        else -> 52.dp
    }

    val scrollState = rememberScrollState()

    val totalReferencesCount = remember(relatedTasks, relatedObjectsAndTags, relatedDates) {
        (relatedTasks + relatedObjectsAndTags + relatedDates).distinctBy { it.id }.size
    }

    CompositionLocalProvider(
        LocalEditorToolbarController provides toolbarController,
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(org.yaop.ui.theme.AppBackgroundBrush.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .padding(bottom = toolbarHeight),
        ) {
            // Top Bar: Back, 3-Pill Switcher, More Options (...)
            // Pinned at the top outside verticalScroll so it dynamically adapts to
            // any device's status bar via statusBarsPadding() and NEVER scrolls away.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(AppColors.Surface, CircleShape)
                        .clickable { onBack?.invoke() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(AppColors.Surface, RoundedCornerShape(24.dp))
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JournalPillTab(
                        title = stringResource(R.string.tab_journal),
                        icon = Icons.Default.CalendarMonth,
                        isSelected = activeTab == JournalViewTab.JOURNAL,
                        onClick = { activeTab = JournalViewTab.JOURNAL },
                    )
                    JournalPillTab(
                        title = stringResource(R.string.tab_references),
                        icon = Icons.Default.Link,
                        isSelected = activeTab == JournalViewTab.REFERENCES,
                        badgeCount = totalReferencesCount,
                        onClick = { activeTab = JournalViewTab.REFERENCES },
                    )
                    JournalPillTab(
                        title = stringResource(R.string.tab_created),
                        icon = Icons.Default.GridView,
                        isSelected = activeTab == JournalViewTab.CREATED,
                        badgeCount = createdOnDateObjects.size,
                        onClick = { activeTab = JournalViewTab.CREATED },
                    )
                }

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(AppColors.Surface, CircleShape)
                        .clickable { if (currentDailyObject != null) showEditorBottomSheet = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.MoreHoriz,
                        contentDescription = stringResource(R.string.options),
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Scrollable Content Area: Starts below the Top Bar
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 80.dp),
            ) {
                Spacer(modifier = Modifier.height(10.dp))

                // Unified Fluid Calendar (Collapses to week strip, expands to full month without duplication)
        UnifiedJournalCalendar(
            selectedDate = selectedDate,
            onDateChange = onDateChange,
            isExpanded = isCalendarExpanded,
            onToggleExpanded = { isCalendarExpanded = !isCalendarExpanded },
            currentLocale = currentLocale,
            datesWithActivity = datesWithActivity,
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Subheader: Day of the week + "Today" badge (+ image privacy eye on the right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val dayOfWeekText = remember(selectedDate, currentLocale) {
                selectedDate.format(DateTimeFormatter.ofPattern("EEEE", currentLocale))
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(currentLocale) else it.toString() }
            }
            Text(
                text = dayOfWeekText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Accent,
            )

            if (selectedDate == LocalDate.now()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.AccentSubtle, RoundedCornerShape(12.dp))
                        .padding(horizontal = 9.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.today),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.Accent,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            org.yaop.ui.common.ImagePrivacyButton()
        }

        Spacer(modifier = Modifier.height(6.dp))

        val formattedDateLong = remember(selectedDate, currentLocale) {
            selectedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(currentLocale))
        }
        Text(
            text = formattedDateLong,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary,
            lineHeight = 36.sp,
            letterSpacing = (-0.5).sp,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.week_number, weekNumber),
            fontSize = 13.sp,
            color = AppColors.TextTertiary,
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (activeTab) {
            JournalViewTab.JOURNAL -> {
                // Compact row of today's tasks & events right above the daily note editor
                val todayTasksAndEvents = remember(relatedTasks, relatedDates) {
                    relatedTasks + relatedDates
                }
                if (todayTasksAndEvents.isNotEmpty()) {
                    val untitledLabel = stringResource(R.string.untitled_object)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.daily_tasks_and_events_header, todayTasksAndEvents.size),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextSecondary,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            todayTasksAndEvents.forEach { item ->
                                val isTask = item.typeId == DefaultTypes.TASK.id
                                val isDone = item.isTaskDone
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(AppColors.Surface, RoundedCornerShape(10.dp))
                                        .clickable {
                                            if (isTask) {
                                                onToggleTask(item)
                                            } else {
                                                onNavigateToObject(item.id)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = if (isTask) {
                                            if (isDone) "☑" else "☐"
                                        } else {
                                            item.icon?.emoji ?: "📅"
                                        },
                                        fontSize = 13.sp,
                                        color = if (isTask && isDone) AppColors.Accent else AppColors.TextPrimary,
                                    )
                                    Text(
                                        text = item.plainTitle.ifBlank { untitledLabel },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isTask && isDone) AppColors.TextTertiary else AppColors.TextPrimary,
                                        textDecoration = if (isTask && isDone) TextDecoration.LineThrough else TextDecoration.None,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                if (currentDailyObject == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppColors.Surface, RoundedCornerShape(12.dp))
                            .clickable(onClick = onCreateDailyObject),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = AppColors.TextPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.daily_note),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.TextPrimary,
                            )
                        }
                    }
                } else {
                    if (repository != null) org.yaop.ui.habits.DailyHabitsSection(selectedDate, repository)
                    BlockListEditor(
                        blocks = currentDailyObject.blocks,
                        onBlocksChange = onUpdateBlocks,
                        repository = repository,
                        onNavigateToObject = onNavigateToObject,
                        onNavigateToDate = onDateChange,
                        ownerObjectId = currentDailyObject.id,
                    )
                }

                // References Section
                val objectsAndTagsLabel = stringResource(R.string.objects_and_tags)
                val relatedDatesLabel = stringResource(R.string.related_dates)
                val relatedTasksLabel = stringResource(R.string.related_tasks)

                val activeColumns = listOfNotNull(
                    if (relatedObjectsAndTags.isNotEmpty()) Triple(objectsAndTagsLabel, Icons.Default.Link, relatedObjectsAndTags) else null,
                    if (relatedDates.isNotEmpty()) Triple(relatedDatesLabel, Icons.Default.Event, relatedDates) else null,
                    if (relatedTasks.isNotEmpty()) Triple(relatedTasksLabel, Icons.Default.CheckCircle, relatedTasks) else null,
                )

                if (activeColumns.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(30.dp))
                    Text(
                        text = stringResource(R.string.references_for_this_day),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        activeColumns.forEach { (title, icon, list) ->
                            Box(modifier = Modifier.weight(1f)) {
                                DailyReferenceColumn(
                                    title = title,
                                    icon = icon,
                                    objects = list,
                                    allTypes = allTypes,
                                    selectedDateIso = formattedDateKey,
                                    maxItems = 4,
                                    onSeeAll = { activeTab = JournalViewTab.REFERENCES },
                                    onNavigateToObject = onNavigateToObject,
                                    onToggleTask = onToggleTask,
                                )
                            }
                        }
                    }
                }
            }
            JournalViewTab.REFERENCES -> {
                DailyReferencesView(
                    selectedDate = selectedDate,
                    currentLocale = currentLocale,
                    relatedTasks = relatedTasks,
                    relatedDates = relatedDates,
                    relatedObjectsAndTags = relatedObjectsAndTags,
                    allTypes = allTypes,
                    onNavigateToObject = onNavigateToObject,
                    onToggleTask = onToggleTask,
                )
            }
            JournalViewTab.CREATED -> {
                DailyCreatedGalleryView(
                    selectedDate = selectedDate,
                    currentLocale = currentLocale,
                    createdObjects = createdOnDateObjects,
                    allTypes = allTypes,
                    onNavigateToObject = onNavigateToObject,
                    onToggleTask = onToggleTask,
                    onCreateObject = onCreateObject,
                    referencesCount = totalReferencesCount,
                    onSwitchToReferences = { activeTab = JournalViewTab.REFERENCES },
                )
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
    }

    if (showEditorBottomSheet && currentDailyObject != null) {
        val dailyNoteText = stringResource(R.string.daily_note)
        EditorBottomSheet(
            onDismissRequest = { showEditorBottomSheet = false },
            showDescription = currentDailyObject.summary.isNotEmpty(),
            onToggleDescription = {
                coroutineScope.launch {
                    val newSummary = if (currentDailyObject.summary.isEmpty()) dailyNoteText.toTextSpan() else emptyList()
                    repository?.saveObject(currentDailyObject.copy(summary = newSummary))
                }
            },
            currentEmoji = currentDailyObject.icon?.emoji,
            onSelectEmoji = { emoji ->
                coroutineScope.launch {
                    repository?.saveObject(currentDailyObject.copy(icon = ObjectIcon(emoji = emoji)))
                }
            },
            hasCover = currentDailyObject.cover != null,
            onToggleCover = {
                coroutineScope.launch {
                    val newCover = if (currentDailyObject.cover == null) CoverSpec(assetId = "cover_daily") else null
                    repository?.saveObject(currentDailyObject.copy(cover = newCover))
                }
            },
            propertiesCount = currentDailyObject.properties.size,
            onOpenProperties = {
                onNavigateToObject(currentDailyObject.id)
            },
            onUndo = {},
            onRedo = {},
            isPinned = currentDailyObject.isPinned,
            onTogglePin = {
                coroutineScope.launch {
                    repository?.saveObject(currentDailyObject.copy(isPinned = !currentDailyObject.isPinned))
                }
            },
            onMoveToTrash = {
                coroutineScope.launch {
                    repository?.moveToTrash(currentDailyObject.id)
                }
            },
        )
    }

    // Contextual editing toolbar pinned above the keyboard.
    if (activeTab == JournalViewTab.JOURNAL) {
        EditorToolbarRail(
            controller = toolbarController,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }
    }
}

@Composable
private fun JournalPillTab(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    badgeCount: Int = 0,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                color = if (isSelected) AppColors.Selection else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary,
            )
            if (badgeCount > 0) {
                Spacer(modifier = Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (isSelected) AppColors.Accent else AppColors.SurfaceElevated,
                            CircleShape,
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = badgeCount.toString(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) AppColors.Background else AppColors.Accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedJournalCalendar(
    selectedDate: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    currentLocale: Locale,
    datesWithActivity: Set<LocalDate> = emptySet(),
) {
    val coroutineScope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }

    // Week pager constants & state (2000-01-03 is a Monday)
    val baseWeekDate = remember { LocalDate.of(2000, 1, 3) }
    val totalWeeks = 10_000
    val initialWeekPage = remember {
        ChronoUnit.WEEKS.between(baseWeekDate, selectedDate.with(DayOfWeek.MONDAY)).toInt()
            .coerceIn(0, totalWeeks - 1)
    }
    val weekPagerState = rememberPagerState(
        initialPage = initialWeekPage,
        pageCount = { totalWeeks },
    )

    // Month pager constants & state
    val baseMonth = remember { YearMonth.of(2000, 1) }
    val totalMonths = 2_400
    val initialMonthPage = remember {
        ChronoUnit.MONTHS.between(baseMonth, YearMonth.from(selectedDate)).toInt()
            .coerceIn(0, totalMonths - 1)
    }
    val monthPagerState = rememberPagerState(
        initialPage = initialMonthPage,
        pageCount = { totalMonths },
    )

    // Synchronize week pager when selectedDate changes externally
    LaunchedEffect(selectedDate) {
        val targetWeekPage = ChronoUnit.WEEKS.between(baseWeekDate, selectedDate.with(DayOfWeek.MONDAY)).toInt()
        if (weekPagerState.currentPage != targetWeekPage && targetWeekPage in 0 until totalWeeks) {
            weekPagerState.animateScrollToPage(targetWeekPage)
        }
    }

    // Synchronize month pager when expanded or when selectedDate changes
    LaunchedEffect(isExpanded, selectedDate) {
        if (isExpanded) {
            val targetMonthPage = ChronoUnit.MONTHS.between(baseMonth, YearMonth.from(selectedDate)).toInt()
            if (monthPagerState.currentPage != targetMonthPage && targetMonthPage in 0 until totalMonths) {
                monthPagerState.scrollToPage(targetMonthPage)
            }
        }
    }

    // When the user swipes between weeks horizontally, update selectedDate on settled page
    LaunchedEffect(weekPagerState) {
        snapshotFlow { weekPagerState.settledPage }.collect { settledPage ->
            val pageWeekStart = baseWeekDate.plusWeeks(settledPage.toLong())
            val currentWeekStart = selectedDate.with(DayOfWeek.MONDAY)
            if (pageWeekStart != currentWeekStart) {
                val dayOfWeekOffset = (selectedDate.dayOfWeek.value - 1).toLong()
                val newDate = pageWeekStart.plusDays(dayOfWeekOffset)
                onDateChange(newDate)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.Surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        AnimatedContent(
            targetState = isExpanded,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)))
                    .togetherWith(fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)))
            },
            label = "UnifiedJournalCalendarMorph",
        ) { expanded ->
            if (expanded) {
                // Expanded Month View with Horizontal Swipe between months
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val currentDisplayedMonth = remember(monthPagerState.currentPage) {
                        baseMonth.plusMonths(monthPagerState.currentPage.toLong())
                    }

                    // Month Navigation Header: [<] Month Year [>] and Jump to Today
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.SurfaceElevated, CircleShape)
                                    .clickable {
                                        coroutineScope.launch {
                                            if (monthPagerState.currentPage > 0) {
                                                monthPagerState.animateScrollToPage(monthPagerState.currentPage - 1)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronLeft,
                                    contentDescription = stringResource(R.string.action_previous_month),
                                    tint = AppColors.TextPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            val monthYearText = remember(currentDisplayedMonth, currentLocale) {
                                val monthName = currentDisplayedMonth.month.getDisplayName(JavaTextStyle.FULL, currentLocale)
                                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(currentLocale) else it.toString() }
                                "$monthName ${currentDisplayedMonth.year}"
                            }
                            Text(
                                text = monthYearText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.TextPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.SurfaceElevated, CircleShape)
                                    .clickable {
                                        coroutineScope.launch {
                                            if (monthPagerState.currentPage < totalMonths - 1) {
                                                monthPagerState.animateScrollToPage(monthPagerState.currentPage + 1)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = stringResource(R.string.action_next_month),
                                    tint = AppColors.TextPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        // Shortcut chip: "Hoy"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppColors.AccentSubtle, RoundedCornerShape(12.dp))
                                .clickable {
                                    val todayMonthPage = ChronoUnit.MONTHS.between(baseMonth, YearMonth.from(today)).toInt()
                                    coroutineScope.launch {
                                        monthPagerState.scrollToPage(todayMonthPage)
                                    }
                                    onDateChange(today)
                                    onToggleExpanded()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.today),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.Accent,
                            )
                        }
                    }

                    // Weekday initials row (localized)
                    val firstDayOfWeek = DayOfWeek.MONDAY
                    val dayNames = remember(currentLocale) {
                        (0..6).map {
                            firstDayOfWeek.plus(it.toLong())
                                .getDisplayName(JavaTextStyle.SHORT, currentLocale)
                                .replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase(currentLocale) else ch.toString() }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        dayNames.forEach { name ->
                            Text(
                                text = name.take(2),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.TextTertiary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(36.dp),
                            )
                        }
                    }

                    // Swipeable Month Grid Pager
                    HorizontalPager(
                        state = monthPagerState,
                        modifier = Modifier.fillMaxWidth(),
                    ) { page ->
                        val browsingMonth = remember(page) { baseMonth.plusMonths(page.toLong()) }
                        val daysInMonth = browsingMonth.lengthOfMonth()
                        val firstDayOffset = (browsingMonth.atDay(1).dayOfWeek.value - 1) // 0 for Monday
                        val totalCells = ((firstDayOffset + daysInMonth + 6) / 7) * 7

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (row in 0 until totalCells / 7) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    for (col in 0 until 7) {
                                        val cellIdx = row * 7 + col
                                        val dayNum = cellIdx - firstDayOffset + 1
                                        if (dayNum in 1..daysInMonth) {
                                            val cellDate = browsingMonth.atDay(dayNum)
                                            val isSelected = cellDate == selectedDate
                                            val isToday = cellDate == today
                                            val hasActivity = datesWithActivity.contains(cellDate)

                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .then(
                                                        when {
                                                            isSelected -> Modifier
                                                                .clip(CircleShape)
                                                                .background(AppColors.Accent, CircleShape)
                                                            isToday -> Modifier
                                                                .clip(CircleShape)
                                                                .border(1.5.dp, AppColors.Accent, CircleShape)
                                                                .background(AppColors.AccentSubtle, CircleShape)
                                                            else -> Modifier
                                                        },
                                                    )
                                                    .clickable {
                                                        onDateChange(cellDate)
                                                        onToggleExpanded()
                                                    }
                                                    .drawBehind {
                                                        if (hasActivity) {
                                                            drawCircle(
                                                                color = if (isSelected) {
                                                                    AppColors.Background
                                                                } else {
                                                                    AppColors.Accent
                                                                },
                                                                radius = 2.dp.toPx(),
                                                                center = Offset(size.width / 2f, size.height - 3.dp.toPx()),
                                                            )
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = dayNum.toString(),
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                                    color = when {
                                                        isSelected -> AppColors.Background
                                                        isToday -> AppColors.Accent
                                                        else -> AppColors.TextPrimary
                                                    },
                                                )
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.size(36.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val shortWeekdayFormatter = remember(currentLocale) {
                    DateTimeFormatter.ofPattern("EEE", currentLocale)
                }
                // Collapsed Week View with Horizontal Swipe between weeks
                HorizontalPager(
                    state = weekPagerState,
                    modifier = Modifier.fillMaxWidth(),
                ) { page ->
                    val pageWeekStart = remember(page) { baseWeekDate.plusWeeks(page.toLong()) }
                    val weekDays = remember(pageWeekStart) { (0..6).map { pageWeekStart.plusDays(it.toLong()) } }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        weekDays.forEach { date ->
                            val isSelected = date == selectedDate
                            val isToday = date == today
                            val hasActivity = datesWithActivity.contains(date)

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onDateChange(date) }
                                    .padding(vertical = 4.dp, horizontal = 2.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .then(
                                            when {
                                                isSelected -> Modifier
                                                    .clip(CircleShape)
                                                    .background(AppColors.Accent, CircleShape)
                                                isToday -> Modifier
                                                    .clip(CircleShape)
                                                    .border(1.5.dp, AppColors.Accent, CircleShape)
                                                    .background(AppColors.AccentSubtle, CircleShape)
                                                else -> Modifier
                                            },
                                        )
                                        .drawBehind {
                                            if (hasActivity) {
                                                drawCircle(
                                                    color = if (isSelected) {
                                                        AppColors.Background
                                                    } else {
                                                        AppColors.Accent
                                                    },
                                                    radius = 2.dp.toPx(),
                                                    center = Offset(size.width / 2f, size.height - 3.dp.toPx()),
                                                )
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = date.dayOfMonth.toString(),
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Medium,
                                        color = when {
                                            isSelected -> AppColors.Background
                                            isToday -> AppColors.Accent
                                            else -> AppColors.TextPrimary
                                        },
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                val dayLabel = remember(date, shortWeekdayFormatter, currentLocale) {
                                    date.format(shortWeekdayFormatter).lowercase(currentLocale)
                                }
                                Text(
                                    text = dayLabel,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                                    color = when {
                                        isSelected -> AppColors.Accent
                                        isToday -> AppColors.Accent
                                        else -> AppColors.TextSecondary
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Toggle Expand/Collapse Chevron at Bottom Center
        val arrowRotation by animateFloatAsState(
            targetValue = if (isExpanded) 180f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "CalendarArrowRotation",
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleExpanded)
                .padding(top = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (isExpanded) R.string.action_collapse_calendar else R.string.action_expand_calendar,
                ),
                tint = AppColors.TextTertiary,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(arrowRotation),
            )
        }
    }
}

@Composable
private fun DailyReferenceColumn(
    title: String,
    icon: ImageVector,
    objects: List<PkmObject>,
    allTypes: List<ObjectType>,
    selectedDateIso: String? = null,
    maxItems: Int = Int.MAX_VALUE,
    onSeeAll: (() -> Unit)? = null,
    onNavigateToObject: (String) -> Unit,
    onToggleTask: (PkmObject) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Surface, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(14.dp))
            Text(
                text = "$title (${objects.size})",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        objects.take(maxItems).forEach { obj ->
            val objType = allTypes.find { type -> type.id == obj.typeId }
            val isTask = obj.typeId == DefaultTypes.TASK.id
            val isDone = obj.isTaskDone

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(AppColors.SurfaceElevated, RoundedCornerShape(6.dp))
                    .clickable { onNavigateToObject(obj.id) }
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isTask) {
                    Checkbox(
                        checked = isDone,
                        onCheckedChange = { onToggleTask(obj) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AppColors.Accent,
                            uncheckedColor = AppColors.BorderHover,
                            checkmarkColor = AppColors.Background,
                        ),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                } else if (!obj.icon?.emoji.isNullOrBlank()) {
                    Text(text = obj.icon.emoji, fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = obj.plainTitle.ifBlank { stringResource(R.string.untitled_object) },
                        fontSize = 12.sp,
                        color = if (isDone) AppColors.TextTertiary else AppColors.TextPrimary,
                        textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val snippet = remember(obj, selectedDateIso) { getObjectPreviewSnippet(obj, selectedDateIso) }
                    if (snippet.isNotBlank() && snippet != obj.plainTitle) {
                        Text(
                            text = snippet,
                            fontSize = 10.sp,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (objType != null && !isTask) {
                    Spacer(modifier = Modifier.width(4.dp))
                    TypeBadge(type = objType, modifier = Modifier.padding(0.dp))
                }
            }
        }

        if (objects.size > maxItems && onSeeAll != null) {
            Text(
                text = stringResource(R.string.home_see_all),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF151518)
@Composable
fun DailyScreenPreview() {
    YaopTheme {
        DailyContent(
            selectedDate = LocalDate.now(),
            onDateChange = {},
            currentDailyObject = null,
            onCreateDailyObject = {},
            onUpdateBlocks = {},
            createdOnDateObjects = emptyList(),
            relatedTasks = listOf(
                PkmObject(
                    typeId = DefaultTypes.TASK.id,
                    title = "Submit project proposal".toTextSpan(),
                    properties = mapOf(PropertyKeys.STATUS to PropertyValue.Select(TaskStatus.TODO.id)),
                ),
            ),
            relatedDates = emptyList(),
            relatedObjectsAndTags = emptyList(),
            allTypes = DefaultTypes.ALL,
            onNavigateToObject = {},
            onToggleTask = {},
        )
    }
}

private enum class ReferencesFilter {
    ALL,
    TASKS,
    MENTIONS,
    DATES,
}

@Composable
fun DailyReferencesView(
    selectedDate: LocalDate,
    currentLocale: Locale,
    relatedTasks: List<PkmObject>,
    relatedDates: List<PkmObject>,
    relatedObjectsAndTags: List<PkmObject>,
    allTypes: List<ObjectType>,
    onNavigateToObject: (String) -> Unit,
    onToggleTask: (PkmObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeFilter by remember { mutableStateOf(ReferencesFilter.ALL) }
    val untitledLabel = stringResource(R.string.untitled_object)

    val tasksList = remember(relatedTasks) { relatedTasks }
    val mentionsList = remember(relatedObjectsAndTags) { relatedObjectsAndTags }
    val datesList = remember(relatedDates) { relatedDates }
    val totalCount = remember(tasksList, mentionsList, datesList) {
        (tasksList + mentionsList + datesList).distinctBy { it.id }.size
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Filter pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimelineFilterChip(
                title = stringResource(R.string.references_filter_all, totalCount),
                isSelected = activeFilter == ReferencesFilter.ALL,
                onClick = { activeFilter = ReferencesFilter.ALL },
            )
            TimelineFilterChip(
                title = stringResource(R.string.references_filter_tasks, tasksList.size),
                isSelected = activeFilter == ReferencesFilter.TASKS,
                onClick = { activeFilter = ReferencesFilter.TASKS },
            )
            TimelineFilterChip(
                title = stringResource(R.string.references_filter_mentions, mentionsList.size),
                isSelected = activeFilter == ReferencesFilter.MENTIONS,
                onClick = { activeFilter = ReferencesFilter.MENTIONS },
            )
            TimelineFilterChip(
                title = stringResource(R.string.references_filter_dates, datesList.size),
                isSelected = activeFilter == ReferencesFilter.DATES,
                onClick = { activeFilter = ReferencesFilter.DATES },
            )
        }

        if (totalCount == 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.Surface, RoundedCornerShape(14.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(14.dp))
                    .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(AppColors.SurfaceElevated, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = AppColors.Accent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.references_empty_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                    )
                    Text(
                        text = stringResource(R.string.references_empty_desc),
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            val formattedDateKey = remember(selectedDate) { selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE) }
            val showTasks = (activeFilter == ReferencesFilter.ALL || activeFilter == ReferencesFilter.TASKS) && tasksList.isNotEmpty()
            val showMentions = (activeFilter == ReferencesFilter.ALL || activeFilter == ReferencesFilter.MENTIONS) && mentionsList.isNotEmpty()
            val showDates = (activeFilter == ReferencesFilter.ALL || activeFilter == ReferencesFilter.DATES) && datesList.isNotEmpty()

            if (showTasks) {
                ReferencesGroupCard(
                    title = stringResource(R.string.references_section_tasks),
                    icon = Icons.Default.CheckCircle,
                    items = tasksList,
                    allTypes = allTypes,
                    untitledLabel = untitledLabel,
                    selectedDateIso = formattedDateKey,
                    onNavigateToObject = onNavigateToObject,
                    onToggleTask = onToggleTask,
                )
            }

            if (showMentions) {
                ReferencesGroupCard(
                    title = stringResource(R.string.references_section_mentions),
                    icon = Icons.Default.Link,
                    items = mentionsList,
                    allTypes = allTypes,
                    untitledLabel = untitledLabel,
                    selectedDateIso = formattedDateKey,
                    onNavigateToObject = onNavigateToObject,
                    onToggleTask = onToggleTask,
                )
            }

            if (showDates) {
                ReferencesGroupCard(
                    title = stringResource(R.string.references_section_dates),
                    icon = Icons.Default.Event,
                    items = datesList,
                    allTypes = allTypes,
                    untitledLabel = untitledLabel,
                    selectedDateIso = formattedDateKey,
                    onNavigateToObject = onNavigateToObject,
                    onToggleTask = onToggleTask,
                )
            }
        }
    }
}

@Composable
fun ReferencesGroupCard(
    title: String,
    icon: ImageVector,
    items: List<PkmObject>,
    allTypes: List<ObjectType>,
    untitledLabel: String,
    selectedDateIso: String? = null,
    onNavigateToObject: (String) -> Unit,
    onToggleTask: (PkmObject) -> Unit,
) {
    var showAll by remember(items) { mutableStateOf(false) }
    val visibleItems = if (showAll) items else items.take(20)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AppColors.Accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "$title (${items.size})",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
            )
        }

        visibleItems.forEach { obj ->
            val objType = allTypes.find { it.id == obj.typeId }
            val isTask = obj.typeId == DefaultTypes.TASK.id
            val isDone = obj.isTaskDone
            val snippet = remember(obj, selectedDateIso) { getObjectPreviewSnippet(obj, selectedDateIso) }

            SubtleSwipeActionBox(
                modifier = Modifier.fillMaxWidth(),
                enabled = isTask,
                shape = RoundedCornerShape(8.dp),
                onSwipeRight = if (isTask) { { onToggleTask(obj) } } else null,
                swipeRightIcon = if (isDone) Icons.Default.RadioButtonUnchecked else Icons.Default.Check,
                swipeRightColor = if (isDone) AppColors.TextSecondary else AppColors.Accent,
                swipeRightBackground = if (isDone) AppColors.SurfaceElevated else AppColors.Accent.copy(alpha = 0.16f),
                swipeRightLabel = if (isDone) stringResource(R.string.action_swipe_uncomplete) else stringResource(R.string.action_swipe_complete),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.SurfaceElevated, RoundedCornerShape(8.dp))
                        .clickable { onNavigateToObject(obj.id) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isTask) {
                        Checkbox(
                            checked = isDone,
                            onCheckedChange = { onToggleTask(obj) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AppColors.Accent,
                                uncheckedColor = AppColors.BorderHover,
                                checkmarkColor = AppColors.Background,
                            ),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    } else if (!obj.icon?.emoji.isNullOrBlank()) {
                        Text(text = obj.icon.emoji, fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = obj.plainTitle.ifBlank { untitledLabel },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isTask && isDone) AppColors.TextTertiary else AppColors.TextPrimary,
                            textDecoration = if (isTask && isDone) TextDecoration.LineThrough else TextDecoration.None,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (snippet.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = snippet,
                                fontSize = 11.sp,
                                color = AppColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (objType != null && !isTask) {
                        Spacer(modifier = Modifier.width(6.dp))
                        TypeBadge(type = objType)
                    }
                }
            }
        }

        if (!showAll && items.size > 20) {
            Text(
                text = stringResource(R.string.home_see_all),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showAll = true }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

private enum class CreatedFilter {
    ALL,
    MEDIA,
    NOTES,
    TASKS,
    OTHER,
}

@Composable
fun DailyCreatedGalleryView(
    selectedDate: LocalDate,
    currentLocale: Locale,
    createdObjects: List<PkmObject>,
    allTypes: List<ObjectType>,
    onNavigateToObject: (String) -> Unit,
    onToggleTask: (PkmObject) -> Unit,
    onCreateObject: (String) -> Unit,
    referencesCount: Int = 0,
    onSwitchToReferences: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var activeFilter by remember { mutableStateOf(CreatedFilter.ALL) }
    val untitledLabel = stringResource(R.string.untitled_object)

    val mediaObjects = remember(createdObjects) {
        createdObjects.filter {
            val t = it.typeId
            t == "image" || t == "type_image" || t == "pdf" || t == "type_pdf" ||
                t == "audio" || t == "type_audio" || t == "web" || t == "type_weblink"
        }
    }
    val noteObjects = remember(createdObjects) {
        createdObjects.filter { it.typeId == DefaultTypes.NOTE.id || it.typeId == "type_note" }
    }
    val taskObjects = remember(createdObjects) {
        createdObjects.filter { it.typeId == DefaultTypes.TASK.id || it.typeId == "type_task" }
    }
    val otherObjects = remember(createdObjects) {
        createdObjects.filter {
            it !in mediaObjects && it !in noteObjects && it !in taskObjects
        }
    }

    val filteredList = remember(createdObjects, activeFilter) {
        when (activeFilter) {
            CreatedFilter.ALL -> createdObjects
            CreatedFilter.MEDIA -> mediaObjects
            CreatedFilter.NOTES -> noteObjects
            CreatedFilter.TASKS -> taskObjects
            CreatedFilter.OTHER -> otherObjects
        }
    }
    var showAll by remember(createdObjects, activeFilter) { mutableStateOf(false) }
    val displayedList = if (showAll) filteredList else filteredList.take(20)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Filter pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimelineFilterChip(
                title = stringResource(R.string.created_filter_all_items, createdObjects.size),
                isSelected = activeFilter == CreatedFilter.ALL,
                onClick = { activeFilter = CreatedFilter.ALL },
            )
            TimelineFilterChip(
                title = stringResource(R.string.created_filter_media),
                isSelected = activeFilter == CreatedFilter.MEDIA,
                onClick = { activeFilter = CreatedFilter.MEDIA },
            )
            TimelineFilterChip(
                title = stringResource(R.string.created_filter_notes),
                isSelected = activeFilter == CreatedFilter.NOTES,
                onClick = { activeFilter = CreatedFilter.NOTES },
            )
            TimelineFilterChip(
                title = stringResource(R.string.created_filter_tasks),
                isSelected = activeFilter == CreatedFilter.TASKS,
                onClick = { activeFilter = CreatedFilter.TASKS },
            )
            if (otherObjects.isNotEmpty()) {
                TimelineFilterChip(
                    title = stringResource(R.string.created_filter_other),
                    isSelected = activeFilter == CreatedFilter.OTHER,
                    onClick = { activeFilter = CreatedFilter.OTHER },
                )
            }
        }

        if (createdObjects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.Surface, RoundedCornerShape(14.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(14.dp))
                    .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(AppColors.SurfaceElevated, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = null,
                            tint = AppColors.Accent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.created_gallery_empty_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                    )
                    Text(
                        text = stringResource(R.string.created_gallery_empty_desc),
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppColors.SurfaceElevated, RoundedCornerShape(8.dp))
                                .clickable { onCreateObject(DefaultTypes.NOTE.id) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.created_new_note),
                                color = AppColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppColors.Accent.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                .clickable { onCreateObject(DefaultTypes.TASK.id) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.created_new_task),
                                color = AppColors.Accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    if (referencesCount > 0 && onSwitchToReferences != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppColors.AccentSubtle, RoundedCornerShape(10.dp))
                                .border(1.dp, AppColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .clickable { onSwitchToReferences() }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = AppColors.Accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.view_references_hint, referencesCount),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.Accent,
                            )
                        }
                    }
                }
            }
        } else {
            // Staggered 2-Column Masonry Wall
            val col1 = remember(displayedList) { displayedList.filterIndexed { i, _ -> i % 2 == 0 } }
            val col2 = remember(displayedList) { displayedList.filterIndexed { i, _ -> i % 2 != 0 } }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    col1.forEach { obj ->
                        GalleryObjectCard(
                            obj = obj,
                            allTypes = allTypes,
                            currentLocale = currentLocale,
                            untitledLabel = untitledLabel,
                            onNavigateToObject = onNavigateToObject,
                            onToggleTask = onToggleTask,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    col2.forEach { obj ->
                        GalleryObjectCard(
                            obj = obj,
                            allTypes = allTypes,
                            currentLocale = currentLocale,
                            untitledLabel = untitledLabel,
                            onNavigateToObject = onNavigateToObject,
                            onToggleTask = onToggleTask,
                        )
                    }
                }
            }

            if (!showAll && filteredList.size > 20) {
                Text(
                    text = stringResource(R.string.home_see_all),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showAll = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
fun GalleryObjectCard(
    obj: PkmObject,
    allTypes: List<ObjectType>,
    currentLocale: Locale,
    untitledLabel: String,
    onNavigateToObject: (String) -> Unit,
    onToggleTask: (PkmObject) -> Unit,
) {
    val objType = allTypes.find { it.id == obj.typeId }
    val isTask = obj.typeId == DefaultTypes.TASK.id
    val isDone = obj.isTaskDone
    val time = remember(obj) { getObjectLocalTime(obj) }
    val timeFormatted = remember(time, currentLocale) {
        time?.let { formatObjectTime(it, currentLocale) }
    }
    val snippet = remember(obj) { getObjectPreviewSnippet(obj) }
    val imageAssetId = remember(obj) { resolveImageAssetId(obj) }
    val audioAssetId = remember(obj) { resolveAudioAssetId(obj) }
    val decodedThumb = imageAssetId?.let { rememberDecodedThumbnail(it, targetMaxSidePx = 400) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Surface, RoundedCornerShape(12.dp))
            .clickable { onNavigateToObject(obj.id) },
    ) {
        // Hero Image Thumbnail if available
        if (decodedThumb != null) {
            Image(
                bitmap = decodedThumb.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 200.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).privacyImage(),
            )
        }

        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Header: Emoji / Checkbox + Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isTask) {
                    Checkbox(
                        checked = isDone,
                        onCheckedChange = { onToggleTask(obj) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AppColors.Accent,
                            uncheckedColor = AppColors.BorderHover,
                            checkmarkColor = AppColors.Background,
                        ),
                        modifier = Modifier.size(18.dp),
                    )
                } else if (!obj.icon?.emoji.isNullOrBlank()) {
                    Text(text = obj.icon.emoji, fontSize = 13.sp)
                }

                Text(
                    text = obj.plainTitle.ifBlank { untitledLabel },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isTask && isDone) AppColors.TextTertiary else AppColors.TextPrimary,
                    textDecoration = if (isTask && isDone) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            // Audio player if Audio object
            if (!audioAssetId.isNullOrBlank()) {
                AudioPlayerRow(
                    assetId = audioAssetId,
                    fallbackDurationSeconds = extractAudioDurationSeconds(obj),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Excerpt snippet
            if (snippet.isNotBlank() && decodedThumb == null && audioAssetId.isNullOrBlank()) {
                Text(
                    text = snippet,
                    fontSize = 11.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Footer: TypeBadge + subtle time tag (optional)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (objType != null) {
                    TypeBadge(type = objType)
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                if (timeFormatted != null) {
                    Text(
                        text = timeFormatted,
                        fontSize = 10.sp,
                        color = AppColors.TextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineFilterChip(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                color = if (isSelected) AppColors.Accent.copy(alpha = 0.2f) else AppColors.Surface,
                shape = RoundedCornerShape(16.dp),
            )
            .border(
                width = 1.dp,
                color = if (isSelected) AppColors.Accent else AppColors.BorderSubtle,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) AppColors.Accent else AppColors.TextSecondary,
        )
    }
}
