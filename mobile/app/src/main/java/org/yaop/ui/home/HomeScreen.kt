package org.yaop.ui.home

import org.yaop.ui.common.privacyImage
import org.yaop.ui.common.designCover
import androidx.compose.foundation.layout.offset
import org.yaop.ui.theme.AppLabelStyle
import org.yaop.ui.theme.AppFonts
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import org.yaop.ui.settings.HomePrefs
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.Block
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
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
import org.yaop.ui.common.extractImageMime
import org.yaop.ui.common.extractImageSize
import org.yaop.ui.common.formatFileSize
import org.yaop.ui.common.formatMimeShort
import org.yaop.ui.common.localizedName
import org.yaop.ui.common.rememberDecodedThumbnail
import org.yaop.ui.common.resolveImageAssetId
import org.yaop.ui.tasks.TaskCheckbox
import org.yaop.ui.theme.AnimatedCounter
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors
import org.yaop.ui.theme.AppMotion
import org.yaop.ui.theme.LocalSharedOrigin
import org.yaop.ui.theme.bouncyClickable
import org.yaop.ui.theme.sharedObjectBounds
import org.yaop.ui.theme.staggeredAppear
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private const val ORIGIN_DAILY = "home-daily"
private const val ORIGIN_TASK = "home-task"
private const val ORIGIN_CONTINUE = "home-continue"
private const val ORIGIN_PINNED = "home-pinned"
private const val ORIGIN_IMAGE = "home-image"
private const val ORIGIN_RECENT = "home-recent"

private const val MAX_TODAY_TASKS = 5
private const val MAX_CONTINUE = 6
private const val MAX_IMAGES = 8
private const val MAX_RECENT = 6
/** How long a just-completed task stays visible (struck through) before leaving the list. */
private const val COMPLETED_LINGER_MS = 1100L

/**
 * Home: "what do I do now?". Order (mobile/GUIDELINES.md §6):
 * greeting+date → today's journal → today's tasks → continue → pinned → images → recently created.
 */
@Composable
fun HomeScreen(
    repository: PkmRepository,
    onNavigateToObject: (String) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenImages: () -> Unit = onOpenSearch,
) {
    val coroutineScope = rememberCoroutineScope()
    val sharedOrigin = LocalSharedOrigin.current
    val configuration = LocalConfiguration.current
    val currentLocale = remember(configuration) { configuration.locales[0] ?: Locale.getDefault() }

    val allObjects by repository.getAllActiveObjects()
        .collectAsState(initial = repository.getCachedAllActiveObjects())
    val allTypes by repository.getAllTypes()
        .collectAsState(initial = repository.getCachedTypes().ifEmpty { DefaultTypes.ALL })

    val today = remember { LocalDate.now() }
    val todayKey = remember(today) { today.format(DateTimeFormatter.ISO_LOCAL_DATE) }
    val dailyId = "daily-$todayKey"

    var recentlyDone by remember { mutableStateOf(emptySet<String>()) }

    val todayDaily = remember(allObjects, todayKey) {
        allObjects.firstOrNull { obj ->
            obj.typeId == DefaultTypes.DAILY.id &&
                (obj.journalDateIso?.take(10) == todayKey || obj.id == dailyId)
        }
    }

    val allTasks = remember(allObjects) {
        allObjects.filter { it.typeId == DefaultTypes.TASK.id }
    }
    val todayTasks = remember(allTasks, recentlyDone, today) {
        allTasks.filter { task ->
            val due = task.dueDateIso?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val isDueNow = due != null && !due.isAfter(today)
            (isDueNow && !task.isTaskDone) || task.id in recentlyDone
        }.sortedWith(compareBy({ it.dueDateIso ?: "" }, { it.plainTitle.lowercase() }))
    }
    val pendingCount = todayTasks.count { !it.isTaskDone }

    val browsable = remember(allObjects) {
        allObjects.filter { obj ->
            when (obj.typeId) {
                DefaultTypes.TASK.id, DefaultTypes.DAILY.id, DefaultTypes.TAG.id -> false
                else -> !obj.isTemplate
            }
        }
    }
    // `allObjects` is already ordered by updatedAt DESC (repository cache), so filtering preserves
    // the order and we can skip an extra O(n log n) sort on every database emission.
    val continueItems = remember(browsable) {
        browsable.take(MAX_CONTINUE)
    }
    val pinnedItems = remember(allObjects) { allObjects.filter { it.isPinned } }
    val imageItems = remember(browsable) {
        browsable.filter { it.typeId == DefaultTypes.IMAGE.id }
    }
    val recentItems = remember(browsable, continueItems) {
        val shown = continueItems.map { it.id }.toSet()
        val unshown = browsable.filterNot { it.id in shown }.sortedByDescending { it.createdAt }
        if (unshown.isNotEmpty()) unshown.take(MAX_RECENT) else browsable.sortedByDescending { it.createdAt }.take(MAX_RECENT)
    }

    // Pre-index types once instead of scanning `allTypes` twice for every visible row.
    val typesById = remember(allTypes) {
        buildMap<String, ObjectType> {
            for (type in allTypes) {
                put(type.id, type)
                put(type.id, type)
            }
        }
    }

    fun typeOf(obj: PkmObject): ObjectType? =
        typesById[obj.typeId] ?: typesById[obj.typeId]

    fun open(origin: String, id: String) {
        sharedOrigin.mark(origin, id)
        onNavigateToObject(id)
    }

    fun toggleTask(task: PkmObject) {
        val nowDone = !task.isTaskDone
        coroutineScope.launch {
            repository.saveObject(task.toggleTaskStatus())
        }
        if (nowDone) {
            recentlyDone = recentlyDone + task.id
            coroutineScope.launch {
                delay(COMPLETED_LINGER_MS)
                recentlyDone = recentlyDone - task.id
            }
        } else {
            recentlyDone = recentlyDone - task.id
        }
    }

    fun addTodayTask(title: String) {
        if (title.isBlank()) return
        coroutineScope.launch {
            val now = Instant.now().toString()
            repository.saveObject(
                PkmObject(
                    id = UUID.randomUUID().toString(),
                    typeId = CanonicalTypeIds.TASK,
                    title = title.trim().toTextSpan(),
                    icon = DefaultTypes.TASK.icon,
                    createdAt = now,
                    updatedAt = now,
                    properties = mapOf(
                        PropertyKeys.STATUS to PropertyValue.Select(TaskStatus.TODO.id),
                        PropertyKeys.DUE_DATE to PropertyValue.DateRange(todayKey),
                    ),
                ),
            )
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showHabits by remember { mutableStateOf(false) }
    var showInbox by remember { mutableStateOf(false) }
    val inboxItems = remember(allObjects) { org.yaop.ui.habits.inboxOf(allObjects) }
    val todayHabits = remember(allObjects, today) { org.yaop.core.model.HabitMath.dueToday(allObjects, today) }
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(org.yaop.ui.theme.AppBackgroundBrush.Background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Greeting + date
        item(key = "header") {
            HomeHeader(today = today, locale = currentLocale, modifier = Modifier.staggeredAppear(0))
        }

        // 1b. Inbox: shared links/images and quick captures waiting to be sorted
        if (inboxItems.isNotEmpty()) {
            item(key = "inbox") {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(AppColors.Surface, RoundedCornerShape(16.dp))
                        .clickable { showInbox = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.inbox_title), Modifier.weight(1f), color = AppColors.TextPrimary, fontSize = 15.sp, fontFamily = AppFonts.InterTight)
                    Text("${inboxItems.size}", color = AppColors.Ice, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // 2. Today's journal
        item(key = "daily") {
            TodayJournalCard(
                daily = todayDaily,
                modifier = Modifier
                    .staggeredAppear(1)
                    .sharedObjectBounds(ORIGIN_DAILY, dailyId),
                onClick = { open(ORIGIN_DAILY, dailyId) },
            )
        }

        // 2b. Habits due today (rings), Settings › Inicio can turn it off
        if (HomePrefs.showHabits && todayHabits.isNotEmpty()) {
            item(key = "habits") {
                org.yaop.ui.habits.HabitsStrip(
                    habits = todayHabits, today = today, repository = repository,
                    onSeeAll = { showHabits = true }, modifier = Modifier.staggeredAppear(2),
                )
            }
        }

        // 3. Today's tasks
        item(key = "tasks") {
            TodayTasksCard(
                tasks = todayTasks.take(MAX_TODAY_TASKS),
                pendingCount = pendingCount,
                hiddenCount = (todayTasks.size - MAX_TODAY_TASKS).coerceAtLeast(0),
                today = today,
                modifier = Modifier.staggeredAppear(2),
                onToggle = ::toggleTask,
                onOpenTask = { open(ORIGIN_TASK, it.id) },
                onAdd = ::addTodayTask,
                onSeeAll = onOpenTasks,
            )
        }

        // 4. Continue
        if (HomePrefs.showContinue && continueItems.isNotEmpty()) {
            item(key = "continue") {
                Column(modifier = Modifier.staggeredAppear(3)) {
                    SectionHeader(title = stringResource(R.string.home_section_continue))
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        continueItems.forEach { obj ->
                            RecentRow(
                                obj = obj,
                                type = typeOf(obj),
                                modifier = Modifier.sharedObjectBounds(ORIGIN_CONTINUE, obj.id, RoundedCornerShape(14.dp)),
                                onClick = { open(ORIGIN_CONTINUE, obj.id) },
                            )
                        }
                    }
                }
            }
        }

        // 5. Pinned
        if (HomePrefs.showPinned && pinnedItems.isNotEmpty()) {
            item(key = "pinned") {
                Column(modifier = Modifier.staggeredAppear(4)) {
                    SectionHeader(title = stringResource(R.string.home_section_pinned), count = pinnedItems.size)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pinnedItems, key = { it.id }, contentType = { "pinned" }) { obj ->
                            PinnedChip(
                                obj = obj,
                                type = typeOf(obj),
                                modifier = Modifier
                                    .sharedObjectBounds(ORIGIN_PINNED, obj.id, RoundedCornerShape(12.dp)),
                                onClick = { open(ORIGIN_PINNED, obj.id) },
                            )
                        }
                    }
                }
            }
        }

        // 6. Visual Images Gallery strip + direct link to full 4K Gallery view
        if (HomePrefs.showImages && imageItems.isNotEmpty()) {
            item(key = "images") {
                Column(modifier = Modifier.staggeredAppear(5)) {
                    SectionHeader(
                        title = stringResource(R.string.home_section_images),
                        count = imageItems.size,
                        actionLabel = stringResource(R.string.home_open_gallery),
                        onAction = onOpenImages,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(
                            items = imageItems.take(MAX_IMAGES),
                            key = { "img-${it.id}" },
                            contentType = { "image" },
                        ) { obj ->
                            HomeImagePreviewCard(
                                obj = obj,
                                modifier = Modifier
                                    .sharedObjectBounds(ORIGIN_IMAGE, obj.id, RoundedCornerShape(14.dp)),
                                onClick = { open(ORIGIN_IMAGE, obj.id) },
                            )
                        }
                    }
                }
            }
        }

        // 7. Recently created (with real thumbnails for image / cover objects)
        if (HomePrefs.showRecent && recentItems.isNotEmpty()) {
            item(key = "recent-header") {
                SectionHeader(
                    title = stringResource(R.string.home_section_recent_created),
                    actionLabel = stringResource(R.string.home_see_all),
                    onAction = onOpenSearch,
                    modifier = Modifier.staggeredAppear(6),
                )
            }
            itemsIndexed(
                items = recentItems,
                key = { _, obj -> "recent-${obj.id}" },
                contentType = { _, _ -> "recent" },
            ) { _, obj ->
                RecentRow(
                    obj = obj,
                    type = typeOf(obj),
                    modifier = Modifier
                        .sharedObjectBounds(ORIGIN_RECENT, obj.id, RoundedCornerShape(14.dp)),
                    onClick = { open(ORIGIN_RECENT, obj.id) },
                )
            }
        }
        item(key = "customize") {
            Text(
                text = stringResource(R.string.home_customize),
                fontFamily = AppFonts.InterTight,
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showSettings = true }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
    if (showInbox) androidx.compose.ui.window.Dialog(
        onDismissRequest = { showInbox = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        org.yaop.ui.habits.InboxScreen(
            all = allObjects, repository = repository,
            onOpen = { showInbox = false; open(ORIGIN_CONTINUE, it) }, onClose = { showInbox = false },
        )
    }
    if (showHabits) androidx.compose.ui.window.Dialog(
        onDismissRequest = { showHabits = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        org.yaop.ui.habits.HabitsListScreen(
            all = allObjects, repository = repository,
            onOpen = { showHabits = false; open(ORIGIN_CONTINUE, it) }, onClose = { showHabits = false },
        )
    }
    if (showSettings) org.yaop.ui.settings.VaultSheet(onDismiss = { showSettings = false })
}

// ---------------------------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomeHeader(today: LocalDate, locale: Locale, modifier: Modifier = Modifier) {
    val hour = remember { LocalTime.now().hour }
    val greeting = when (hour) {
        in 5..11 -> stringResource(R.string.home_greeting_morning)
        in 12..19 -> stringResource(R.string.home_greeting_afternoon)
        else -> stringResource(R.string.home_greeting_evening)
    }
    val dateText = remember(today, locale) {
        today.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", locale)).replaceFirstChar { it.uppercase(locale) }
    }
    val lastSpace = greeting.lastIndexOf(' ')
    val title = remember(greeting) {
        buildAnnotatedString {
            append(if (lastSpace > 0) greeting.substring(0, lastSpace + 1) else "")
            withStyle(SpanStyle(fontFamily = AppFonts.Doto, fontWeight = FontWeight.ExtraBold, color = AppColors.Ice)) {
                append(if (lastSpace > 0) greeting.substring(lastSpace + 1) else greeting)
            }
            append(".")
        }
    }
    Column(modifier = modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = dateText,
                fontFamily = AppFonts.InterTight,
                fontSize = 14.sp,
                color = AppColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            org.yaop.ui.settings.SyncStatusChip(onlyProblems = true)
            Spacer(Modifier.width(8.dp))
            org.yaop.ui.common.ImagePrivacyButton()
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            fontFamily = AppFonts.InterTight,
            fontSize = 34.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.7).sp,
            color = AppColors.TextPrimary,
        )
    }
}

@Composable
private fun TodayJournalCard(
    daily: PkmObject?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = remember(daily) { daily?.previewText().orEmpty() }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(onClick = onClick)
            .clip(shape)
            .background(AppColors.Surface, shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_today_journal_short),
                fontFamily = AppFonts.InterTight,
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.home_write),
                fontFamily = AppFonts.InterTight,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Ice,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = preview.ifBlank { stringResource(R.string.home_journal_hint) },
            fontFamily = AppFonts.InterTight,
            fontSize = 16.sp,
            lineHeight = 23.sp,
            color = if (preview.isBlank()) AppColors.TextSecondary else AppColors.TextPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TodayTasksCard(
    tasks: List<PkmObject>,
    pendingCount: Int,
    hiddenCount: Int,
    today: LocalDate,
    onToggle: (PkmObject) -> Unit,
    onOpenTask: (PkmObject) -> Unit,
    onAdd: (String) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.Surface, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_section_today).uppercase(),
                style = AppLabelStyle,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0x24FFFFFF), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 1.dp),
            ) {
                AnimatedCounter(count = pendingCount, color = AppColors.TextPrimary, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.home_see_all).uppercase(),
                style = AppLabelStyle,
                color = AppColors.TextPrimary,
                modifier = Modifier
                    .bouncyClickable(onClick = onSeeAll)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        Spacer(Modifier.height(6.dp))

        AnimatedVisibility(
            visible = tasks.isEmpty(),
            enter = fadeIn(tween(AppMotion.FADE_MS)) + expandVertically(AppMotion.gentle()),
            exit = fadeOut(tween(AppMotion.FADE_FAST_MS)) + shrinkVertically(AppMotion.gentle()),
        ) {
            Text(
                text = "✨ " + stringResource(R.string.home_tasks_empty),
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Column {
            tasks.forEach { task ->
                androidx.compose.runtime.key(task.id) {
                    HomeTaskRow(
                        task = task,
                        isOverdue = task.dueDateIso?.take(10)
                            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                            ?.isBefore(today) == true,
                        onToggle = { onToggle(task) },
                        onOpen = { onOpenTask(task) },
                    )
                }
            }
        }

        if (hiddenCount > 0) {
            Text(
                text = stringResource(R.string.home_tasks_more, hiddenCount),
                fontSize = 12.sp,
                color = AppColors.TextTertiary,
                modifier = Modifier
                    .bouncyClickable(onClick = onSeeAll)
                    .padding(start = 34.dp, top = 2.dp, bottom = 4.dp),
            )
        }

        QuickAddTaskField(onAdd = onAdd)
    }
}

@Composable
private fun HomeTaskRow(
    task: PkmObject,
    isOverdue: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val done = task.isTaskDone
    val titleColor by animateColorAsState(
        targetValue = if (done) AppColors.TextTertiary else AppColors.TextPrimary,
        animationSpec = AppMotion.snappy(),
        label = "HomeTaskTitleColor",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sharedObjectBounds(ORIGIN_TASK, task.id, RoundedCornerShape(10.dp))
            .bouncyClickable(haptic = false, pressedScale = 0.98f, onClick = onOpen)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskCheckbox(isDone = done, onToggle = onToggle)
        Spacer(Modifier.width(12.dp))
        Text(
            text = task.title.toPlainText().ifBlank { stringResource(R.string.untitled_object) },
            fontSize = 14.sp,
            color = titleColor,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isOverdue && !done) {
            Text(
                text = stringResource(R.string.tasks_overdue),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.Danger,
            )
        }
    }
}

@Composable
private fun QuickAddTaskField(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(1.5.dp, AppColors.BorderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f).padding(vertical = 7.dp)) {
            if (text.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_add_task_hint),
                    fontSize = 14.sp,
                    color = AppColors.TextTertiary,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    onAdd(text)
                    text = ""
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = AppLabelStyle,
            color = AppColors.TextSecondary,
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            AnimatedCounter(count = count, color = AppColors.TextTertiary, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel.uppercase(),
                style = AppLabelStyle,
                color = AppColors.TextPrimary,
                modifier = Modifier
                    .bouncyClickable(onClick = onAction)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ContinueCard(
    obj: PkmObject,
    type: ObjectType?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(AppMotion.ContainerCorner)
    val assetId = remember(obj.id, obj.updatedAt, obj.cover) { resolveObjectPreviewAssetId(obj) }
    val thumbnail = if (!assetId.isNullOrBlank()) rememberDecodedThumbnail(assetId, targetMaxSidePx = 320) else null

    Column(
        modifier = modifier
            .width(156.dp)
            .height(126.dp)
            .bouncyClickable(onClick = onClick)
            .clip(shape)
            .background(AppColors.Surface, shape)
    ) {
        if (thumbnail != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .background(AppColors.SurfaceElevated),
            ) {
                Image(
                    bitmap = thumbnail.bitmap.asImageBitmap(),
                    contentDescription = obj.plainTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().privacyImage(),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = obj.title.toPlainText().ifBlank { stringResource(R.string.untitled_object) },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = relativeTime(obj.updatedAt),
                    fontSize = 11.sp,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                )
            }
        } else {
            val cover = org.yaop.ui.theme.AppTypeColors.of(obj.typeId)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .designCover(cover)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    EmojiTile(emoji = obj.emojiOr(type), colorHex = null, size = 32)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = (type?.localizedName() ?: "").uppercase(),
                        style = AppLabelStyle,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = obj.title.toPlainText().ifBlank { stringResource(R.string.untitled_object) },
                    fontFamily = AppFonts.InterTight,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = relativeTime(obj.updatedAt),
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PinnedChip(
    obj: PkmObject,
    type: ObjectType?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val assetId = remember(obj.id, obj.updatedAt, obj.cover) { resolveObjectPreviewAssetId(obj) }
    val thumbnail = if (!assetId.isNullOrBlank()) rememberDecodedThumbnail(assetId, targetMaxSidePx = 160) else null

    Row(
        modifier = modifier
            .bouncyClickable(onClick = onClick)
            .clip(shape)
            .background(AppColors.Surface, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.bitmap.asImageBitmap(),
                contentDescription = obj.plainTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp)).privacyImage()
            )
        } else {
            Text(text = obj.emojiOr(type), fontSize = 15.sp)
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = obj.title.toPlainText().ifBlank { stringResource(R.string.untitled_object) },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(IntrinsicWidthCap),
        )
    }
}

private val IntrinsicWidthCap = 140.dp

@Composable
private fun HomeImagePreviewCard(
    obj: PkmObject,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val assetId = remember(obj.id, obj.updatedAt) { resolveImageAssetId(obj) }
    val thumbnail = if (!assetId.isNullOrBlank()) rememberDecodedThumbnail(assetId, targetMaxSidePx = 320) else null
    val mimeShort = formatMimeShort(extractImageMime(obj))
    val is4K = (thumbnail?.originalWidth ?: 0) >= 3800 || (thumbnail?.originalHeight ?: 0) >= 2100

    Box(
        modifier = modifier
            .size(width = 132.dp, height = 132.dp)
            .bouncyClickable(onClick = onClick)
            .clip(shape)
            .background(AppColors.SurfaceElevated, shape)
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.bitmap.asImageBitmap(),
                contentDescription = obj.plainTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().privacyImage(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = stringResource(R.string.object_type_image),
                tint = AppColors.TextTertiary.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.Center),
            )
        }

        // Top format / 4K badge
        val badgeText = if (is4K) stringResource(R.string.image_format_resolution_4k) else mimeShort
        if (!badgeText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (is4K) TypePalette.ColorPurple.copy(alpha = 0.85f)
                        else Color.Black.copy(alpha = 0.6f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    text = badgeText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }

        // Bottom scrim with title. A solid scrim is cheaper to blend than a full-width gradient
        // on low-end GPUs while keeping the title readable over any thumbnail.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = obj.title.toPlainText().ifBlank { stringResource(R.string.untitled_object) },
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecentRow(
    obj: PkmObject,
    type: ObjectType?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val isImage = obj.typeId == DefaultTypes.IMAGE.id
    val assetId = remember(obj.id, obj.updatedAt, obj.cover) { resolveObjectPreviewAssetId(obj) }
    val thumbnail = if (!assetId.isNullOrBlank()) rememberDecodedThumbnail(assetId, targetMaxSidePx = 320) else null
    val localizedType = type?.localizedName()
    val subtitle = remember(obj.id, obj.updatedAt, localizedType, isImage) {
        val parts = mutableListOf<String>()
        if (!localizedType.isNullOrBlank()) parts += localizedType
        if (isImage) {
            formatMimeShort(extractImageMime(obj))?.let { parts += it }
            formatFileSize(extractImageSize(obj))?.let { parts += it }
        }
        parts.joinToString(" · ")
    }
    val timeLabel = relativeTime(obj.createdAt)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.98f, onClick = onClick)
            .clip(shape)
            .background(AppColors.Surface, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.SurfaceElevated, RoundedCornerShape(10.dp))
            ) {
                Image(
                    bitmap = thumbnail.bitmap.asImageBitmap(),
                    contentDescription = obj.plainTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().privacyImage(),
                )
            }
        } else {
            EmojiTile(emoji = obj.emojiOr(type), colorHex = type?.colorHex, size = 40)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = obj.title.toPlainText().ifBlank { stringResource(R.string.untitled_object) },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(subtitle.takeIf { it.isNotBlank() }, timeLabel.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                fontSize = 12.sp,
                color = AppColors.TextTertiary,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun EmojiTile(emoji: String, colorHex: String?, size: Int) {
    val tint = remember(colorHex) {
        runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
            .getOrDefault(AppColors.TextSecondary)
    }
    val shape = RoundedCornerShape((size * 0.3f).dp)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.16f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = (size * 0.5f).sp)
    }
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

/**
 * Resolves a previewable image asset ID for an object:
 * - For `image` objects, returns their underlying media asset ID.
 * - For objects with a cover (`CoverSpec`), resolves the cover's asset ID (stripping `media_` if linked to an image object).
 */
private fun resolveObjectPreviewAssetId(obj: PkmObject): String? {
    val direct = resolveImageAssetId(obj)
    if (!direct.isNullOrBlank()) return direct
    val coverId = obj.cover?.assetId?.takeIf { it.isNotBlank() && !it.startsWith("cover_") } ?: return null
    return coverId.removePrefix("media_")
}

private fun PkmObject.emojiOr(type: ObjectType?): String =
    icon?.emoji ?: type?.icon?.emoji ?: ObjectIcon().emoji ?: "📄"

private fun PkmObject.previewText(): String {
    val fromSummary = summary.toPlainText()
    if (fromSummary.isNotBlank()) return fromSummary
    return blocks.asSequence()
        .mapNotNull { it.textSpans()?.toPlainText()?.trim() }
        .filter { it.isNotEmpty() }
        .take(3)
        .joinToString(" · ")
}

private fun Block.textSpans(): List<InlineSpan>? = when (this) {
    is Block.Paragraph -> spans
    is Block.Heading -> spans
    is Block.Todo -> spans
    is Block.BulletList -> spans
    is Block.NumberedList -> spans
    is Block.AlphabeticList -> spans
    is Block.RomanList -> spans
    is Block.Toggle -> spans
    is Block.Quote -> spans
    is Block.Callout -> spans
    else -> null
}

@Composable
private fun relativeTime(iso: String): String {
    // Recomputed per composition; DateUtils formats using the device locale.
    return remember(iso) {
        runCatching {
            val millis = Instant.parse(iso).toEpochMilli()
            DateUtils.getRelativeTimeSpanString(
                millis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            ).toString()
        }.getOrDefault("")
    }
}
