package org.yaop.ui.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import org.yaop.R
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.PropertyKeys
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.TaskStatus
import org.yaop.core.model.cycleTaskPriority
import org.yaop.core.model.toggleTaskStatus
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.common.SubtleSwipeActionBox
import org.yaop.ui.theme.AppColors
import org.yaop.ui.theme.LocalSharedOrigin
import org.yaop.ui.theme.bouncyClickable
import org.yaop.ui.theme.sharedObjectBounds
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Task property ids (see DefaultTypes.TASK.defaultProperties).
private const val PROPERTY_STATUS = PropertyKeys.STATUS
private const val PROPERTY_PRIORITY = PropertyKeys.PRIORITY

private val STATUS_TODO = TaskStatus.TODO.id
private val STATUS_IN_PROGRESS = TaskStatus.IN_PROGRESS.id
private val STATUS_DONE = TaskStatus.DONE.id

private enum class TaskStatusFilter {
    ALL,
    TODO,
    IN_PROGRESS,
    DONE,
}

private data class TaskGroups(
    val overdue: List<PkmObject> = emptyList(),
    val today: List<PkmObject> = emptyList(),
    val upcoming: List<PkmObject> = emptyList(),
    val noDate: List<PkmObject> = emptyList(),
    val completed: List<PkmObject> = emptyList(),
) {
    val totalCount: Int
        get() = overdue.size + today.size + upcoming.size + noDate.size + completed.size
}

/**
 * Tasks overview: local in-memory filtering by status, grouped by due date
 * (overdue / today / upcoming / no date / completed). Follows the app pattern of
 * collecting repository Flows directly, without a ViewModel.
 */
@Composable
fun TasksScreen(
    repository: PkmRepository,
    onNavigateToObject: (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val currentLocale = remember(configuration) {
        configuration.locales[0] ?: Locale.getDefault()
    }
    val dateFormatter = remember(currentLocale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentLocale)
    }

    var statusFilter by remember { mutableStateOf(TaskStatusFilter.ALL) }
    var completedExpanded by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }

    val allTasks by repository.getObjectsByType(DefaultTypes.TASK.id)
        .collectAsState(initial = repository.getCachedObjectsByType(DefaultTypes.TASK.id))

    val today = remember { LocalDate.now() }
    val groups = remember(allTasks, statusFilter, today) {
        buildTaskGroups(allTasks, statusFilter, today)
    }

    val onToggleStatus: (PkmObject) -> Unit = { taskObj ->
        coroutineScope.launch {
            repository.saveObject(taskObj.toggleTaskStatus())
        }
    }

    val onCyclePriority: (PkmObject) -> Unit = { taskObj ->
        coroutineScope.launch {
            repository.saveObject(taskObj.cycleTaskPriority())
        }
    }

    val overdueTitle = stringResource(R.string.tasks_overdue)
    val todayTitle = stringResource(R.string.today)
    val upcomingTitle = stringResource(R.string.tasks_upcoming)
    val noDateTitle = stringResource(R.string.tasks_no_date)
    val doneTitle = stringResource(R.string.tasks_filter_done)

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(org.yaop.ui.theme.AppBackgroundBrush.Background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "tasks-title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                org.yaop.ui.common.ScreenTitle(stringResource(R.string.type_tasks), Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = {
                    showFilters = !showFilters
                    if (!showFilters) statusFilter = TaskStatusFilter.ALL
                }) {
                    Text(
                        text = "⋯ " + stringResource(R.string.tasks_show),
                        color = if (showFilters) org.yaop.ui.theme.AppColors.Ice else org.yaop.ui.theme.AppColors.TextSecondary,
                    )
                }
                org.yaop.ui.common.ImagePrivacyButton()
            }
        }

        if (showFilters) item(key = "tasks-filter") {
            Spacer(modifier = Modifier.height(6.dp))
            StatusFilterRow(
                selected = statusFilter,
                onSelect = { statusFilter = it },
            )
        }

        if (groups.totalCount == 0) {
            item(key = "tasks-empty") {
                Spacer(modifier = Modifier.height(8.dp))
                TasksEmptyState(isFiltered = statusFilter != TaskStatusFilter.ALL)
            }
        } else {
            if (groups.overdue.isNotEmpty()) {
                taskGroupSection(
                    keyPrefix = "overdue",
                    title = overdueTitle,
                    icon = Icons.Default.AccessTime,
                    iconTint = AppColors.Danger,
                    tasks = groups.overdue,
                    isOverdueGroup = true,
                    dateFormatter = dateFormatter,
                    onToggleStatus = onToggleStatus,
                    onCyclePriority = onCyclePriority,
                    onNavigateToObject = onNavigateToObject,
                )
            }

            if (groups.today.isNotEmpty()) {
                taskGroupSection(
                    keyPrefix = "today",
                    title = todayTitle,
                    icon = Icons.Default.CalendarMonth,
                    iconTint = AppColors.Warning,
                    tasks = groups.today,
                    isOverdueGroup = false,
                    dateFormatter = dateFormatter,
                    onToggleStatus = onToggleStatus,
                    onCyclePriority = onCyclePriority,
                    onNavigateToObject = onNavigateToObject,
                )
            }

            if (groups.upcoming.isNotEmpty()) {
                taskGroupSection(
                    keyPrefix = "upcoming",
                    title = upcomingTitle,
                    icon = Icons.Default.Event,
                    iconTint = AppColors.TextSecondary,
                    tasks = groups.upcoming,
                    isOverdueGroup = false,
                    dateFormatter = dateFormatter,
                    onToggleStatus = onToggleStatus,
                    onCyclePriority = onCyclePriority,
                    onNavigateToObject = onNavigateToObject,
                )
            }

            if (groups.noDate.isNotEmpty()) {
                taskGroupSection(
                    keyPrefix = "no-date",
                    title = noDateTitle,
                    icon = Icons.Default.EventBusy,
                    iconTint = AppColors.TextTertiary,
                    tasks = groups.noDate,
                    isOverdueGroup = false,
                    dateFormatter = dateFormatter,
                    onToggleStatus = onToggleStatus,
                    onCyclePriority = onCyclePriority,
                    onNavigateToObject = onNavigateToObject,
                )
            }

            if (groups.completed.isNotEmpty()) {
                taskGroupSection(
                    keyPrefix = "completed",
                    title = doneTitle,
                    icon = Icons.Default.CheckCircle,
                    iconTint = AppColors.Success,
                    tasks = groups.completed,
                    isOverdueGroup = false,
                    dateFormatter = dateFormatter,
                    collapsible = true,
                    expanded = completedExpanded || statusFilter == TaskStatusFilter.DONE,
                    onToggleExpanded = { completedExpanded = !completedExpanded },
                    onToggleStatus = onToggleStatus,
                    onCyclePriority = onCyclePriority,
                    onNavigateToObject = onNavigateToObject,
                )
            }
        }

        item(key = "tasks-bottom-spacer") {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

/**
 * Groups tasks in memory (no extra queries): completed tasks last (collapsible),
 * the rest split by due date. Within each group: soonest due date first, then title.
 */
private fun buildTaskGroups(
    tasks: List<PkmObject>,
    filter: TaskStatusFilter,
    today: LocalDate,
): TaskGroups {
    val overdue = mutableListOf<PkmObject>()
    val todayTasks = mutableListOf<PkmObject>()
    val upcoming = mutableListOf<PkmObject>()
    val noDate = mutableListOf<PkmObject>()
    val completed = mutableListOf<PkmObject>()

    tasks.forEach { task ->
        val status = task.taskStatus
        val matchesFilter = when (filter) {
            TaskStatusFilter.ALL -> true
            TaskStatusFilter.TODO -> status == STATUS_TODO
            TaskStatusFilter.IN_PROGRESS -> status == STATUS_IN_PROGRESS
            TaskStatusFilter.DONE -> status == STATUS_DONE
        }
        if (!matchesFilter) return@forEach

        if (filter == TaskStatusFilter.DONE || status == STATUS_DONE) {
            completed += task
            return@forEach
        }

        val dueDate = task.dueDateOrNull()
        when {
            dueDate == null -> noDate += task
            dueDate < today -> overdue += task
            dueDate == today -> todayTasks += task
            else -> upcoming += task
        }
    }

    val taskOrder = compareBy<PkmObject>(
        { it.dueDateOrNull() ?: LocalDate.MAX },
        { it.plainTitle.lowercase() },
    )

    return TaskGroups(
        overdue = overdue.sortedWith(taskOrder),
        today = todayTasks.sortedWith(taskOrder),
        upcoming = upcoming.sortedWith(taskOrder),
        noDate = noDate.sortedWith(taskOrder),
        completed = completed.sortedWith(taskOrder),
    )
}

private fun PkmObject.dueDateOrNull(): LocalDate? =
    dueDateIso?.let { startDate -> runCatching { LocalDate.parse(startDate) }.getOrNull() }

private fun String.toOptionColor(fallback: Color): Color =
    runCatching { Color(toColorInt()) }.getOrDefault(fallback)

@Composable
private fun StatusFilterRow(
    selected: TaskStatusFilter,
    onSelect: (TaskStatusFilter) -> Unit,
) {
    val statusOptions = remember {
        DefaultTypes.TASK.defaultProperties.find { it.id == PROPERTY_STATUS }?.options.orEmpty()
    }
    val openStatusFilters = remember(statusOptions) {
        statusOptions.mapNotNull { option ->
            when (option.id) {
                STATUS_TODO -> option to TaskStatusFilter.TODO
                STATUS_IN_PROGRESS -> option to TaskStatusFilter.IN_PROGRESS
                else -> null
            }
        }
    }
    val doneOption = remember(statusOptions) { statusOptions.find { it.id == STATUS_DONE } }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            StatusFilterChip(
                label = stringResource(R.string.filter_all),
                isSelected = selected == TaskStatusFilter.ALL,
                accent = AppColors.Accent,
                onClick = { onSelect(TaskStatusFilter.ALL) },
            )
        }

        items(openStatusFilters, key = { it.first.id }) { (option, filter) ->
            val localizedLabel = when (option.id) {
                STATUS_TODO -> stringResource(R.string.tasks_filter_todo)
                STATUS_IN_PROGRESS -> stringResource(R.string.tasks_filter_in_progress)
                else -> option.label
            }
            StatusFilterChip(
                label = localizedLabel,
                isSelected = selected == filter,
                accent = option.colorHex.toOptionColor(AppColors.TextSecondary),
                onClick = { onSelect(filter) },
            )
        }

        item {
            StatusFilterChip(
                label = stringResource(R.string.tasks_filter_done),
                isSelected = selected == TaskStatusFilter.DONE,
                accent = doneOption?.colorHex?.toOptionColor(AppColors.Success) ?: AppColors.Success,
                onClick = { onSelect(TaskStatusFilter.DONE) },
            )
        }
    }
}

@Composable
private fun StatusFilterChip(
    label: String,
    isSelected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                color = if (isSelected) accent else AppColors.Surface,
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = if (isSelected) Color.Transparent else AppColors.BorderSubtle,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) AppColors.Background else AppColors.TextSecondary,
        )
    }
}

private fun LazyListScope.taskGroupSection(
    keyPrefix: String,
    title: String,
    icon: ImageVector,
    iconTint: Color,
    tasks: List<PkmObject>,
    isOverdueGroup: Boolean,
    dateFormatter: DateTimeFormatter,
    collapsible: Boolean = false,
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {},
    onToggleStatus: (PkmObject) -> Unit,
    onCyclePriority: (PkmObject) -> Unit,
    onNavigateToObject: (String) -> Unit,
) {
    item(key = "$keyPrefix-header") {
        TaskGroupHeader(
            title = title,
            icon = icon,
            iconTint = iconTint,
            count = tasks.size,
            collapsible = collapsible,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            modifier = Modifier.animateItem(),
        )
    }

    if (!collapsible || expanded) {
        items(
            items = tasks,
            key = { task -> "$keyPrefix-${task.id}" },
            contentType = { "task" },
        ) { task ->
            TaskRow(
                task = task,
                isOverdue = isOverdueGroup,
                dateFormatter = dateFormatter,
                onToggleStatus = { onToggleStatus(task) },
                onCyclePriority = { onCyclePriority(task) },
                onClick = { onNavigateToObject(task.id) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun TaskGroupHeader(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    count: Int,
    collapsible: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (collapsible) Modifier.clickable(onClick = onToggleExpanded) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconTint,
            modifier = Modifier.size(14.dp),
        )

        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$title ($count)",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = iconTint,
        )

        if (collapsible) {
            Spacer(modifier = Modifier.weight(1f))
            val chevronRotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                label = "TaskGroupChevronRotation",
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(chevronRotation),
            )
        }
    }
}

@Composable
private fun TaskRow(
    task: PkmObject,
    isOverdue: Boolean,
    dateFormatter: DateTimeFormatter,
    onToggleStatus: () -> Unit,
    onCyclePriority: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDone = task.isTaskDone
    val dueDate = task.dueDateOrNull()
    val priorityId = task.taskPriority
    val sharedOrigin = LocalSharedOrigin.current

    SubtleSwipeActionBox(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        onSwipeRight = onToggleStatus,
        swipeRightIcon = if (isDone) Icons.Default.RadioButtonUnchecked else Icons.Default.Check,
        swipeRightColor = if (isDone) AppColors.TextSecondary else AppColors.Accent,
        swipeRightBackground = if (isDone) AppColors.SurfaceElevated else AppColors.Accent.copy(alpha = 0.16f),
        swipeRightLabel = if (isDone) stringResource(R.string.action_swipe_uncomplete) else stringResource(R.string.action_swipe_complete),
        onSwipeLeft = onCyclePriority,
        swipeLeftIcon = Icons.Default.Flag,
        swipeLeftColor = AppColors.TextSecondary,
        swipeLeftBackground = AppColors.SurfaceElevated,
        swipeLeftLabel = stringResource(R.string.action_swipe_priority),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sharedObjectBounds("tasks", task.id, RoundedCornerShape(12.dp))
                .bouncyClickable(pressedScale = 0.98f) {
                    sharedOrigin.mark("tasks", task.id)
                    onClick()
                }
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Surface, RoundedCornerShape(12.dp))
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskCheckbox(isDone = isDone, onToggle = onToggleStatus)

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.plainTitle,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isDone) AppColors.TextTertiary else AppColors.TextPrimary,
                    textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (priorityId != null || dueDate != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        priorityId?.let { PriorityChip(priorityId = it) }
                        dueDate?.let {
                            DueDateChip(
                                dueDate = it,
                                isOverdue = isOverdue && !isDone,
                                dateFormatter = dateFormatter,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TaskCheckbox(
    isDone: Boolean,
    onToggle: () -> Unit,
) {
    val fillColor by animateColorAsState(
        targetValue = if (isDone) AppColors.Accent else Color.Transparent,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "TaskCheckboxFillColor",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isDone) 0.dp else 1.5.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "TaskCheckboxBorderWidth",
    )

    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(fillColor, CircleShape)
            .border(width = borderWidth, color = AppColors.BorderHover, shape = CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = isDone,
            enter = scaleIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            ),
            exit = scaleOut(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            ),
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.action_done),
                tint = AppColors.Background,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun PriorityChip(priorityId: String) {
    val option = remember(priorityId) {
        DefaultTypes.TASK.defaultProperties
            .find { it.id == PROPERTY_PRIORITY }
            ?.options
            ?.find { it.id == priorityId }
    } ?: return

    val color = option.colorHex.toOptionColor(AppColors.TextSecondary)
    val label = when (option.id) {
        "low" -> stringResource(R.string.tasks_priority_low)
        "medium" -> stringResource(R.string.tasks_priority_medium)
        "high" -> stringResource(R.string.tasks_priority_high)
        else -> option.label
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

@Composable
private fun DueDateChip(
    dueDate: LocalDate,
    isOverdue: Boolean,
    dateFormatter: DateTimeFormatter,
) {
    val isToday = remember(dueDate) { dueDate == LocalDate.now() }
    val color = when {
        isOverdue -> AppColors.Danger
        isToday -> AppColors.Warning
        else -> AppColors.TextSecondary
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.AccessTime,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(11.dp),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = dueDate.format(dateFormatter),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

@Composable
private fun TasksEmptyState(isFiltered: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(AppColors.Surface, CircleShape)
                    .border(1.dp, AppColors.BorderSubtle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = AppColors.TextTertiary,
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isFiltered) {
                    stringResource(R.string.no_matches_found)
                } else {
                    stringResource(R.string.no_objects_yet)
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
            )
        }
    }
}
