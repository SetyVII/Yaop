package org.yaop.ui.tablet

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.daily.DailyScreen
import org.yaop.ui.editor.ObjectDetailScreen
import org.yaop.ui.home.HomeScreen
import org.yaop.ui.main.NavigationTab
import org.yaop.ui.spaces.SpacesScreen
import org.yaop.ui.tasks.TasksScreen
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors
import org.yaop.ui.theme.AppMotion
import org.yaop.ui.theme.pressScale
import java.time.LocalDate

/**
 * Master-Detail Dual Pane Container for Tablet.
 * Adapts seamlessly between Landscape (Side-by-Side) and Portrait orientations.
 */
@Composable
fun TabletDualPaneScaffold(
    repository: PkmRepository,
    currentTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    selectedObjectId: String?,
    onSelectObject: (String?) -> Unit,
    onCreateObject: (typeId: String) -> Unit,
    onQuickDrawing: () -> Unit,
    windowInfo: TabletWindowInfo,
    modifier: Modifier = Modifier,
) {
    var isDetailFullscreen by remember { mutableStateOf(false) }
    var isInspectorOpen by remember { mutableStateOf(false) }
    val masterSaveableStateHolder = rememberSaveableStateHolder()
    val detailSaveableStateHolder = rememberSaveableStateHolder()

    val allTypes by repository.getAllTypes().collectAsState(initial = DefaultTypes.ALL)

    // Direct observation of only the active object prevents database-wide recomposition during typing
    val activeObject by remember(selectedObjectId) {
        if (selectedObjectId != null) {
            repository.observeObject(selectedObjectId)
        } else {
            flowOf(null)
        }
    }.collectAsState(initial = null)

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(org.yaop.ui.theme.AppBackgroundBrush.Background),
    ) {
        // Left Column: Tablet Navigation Rail. Keep it visible at all times so the user can
        // always jump to Home/Daily/Tasks/Search without pressing the detail back button first.
        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally(AppMotion.offsetGentle) { -it } + fadeIn(),
            exit = slideOutHorizontally(AppMotion.offsetGentle) { -it } + fadeOut(),
        ) {
            TabletNavigationRail(
                currentTab = currentTab,
                onTabSelected = { tab ->
                    // Selecting a destination from the rail must also close the open detail;
                    // otherwise the rail appears to do nothing while an object is open.
                    onSelectObject(null)
                    onTabSelected(tab)
                },
                onCreateObject = onCreateObject,
                onQuickDrawing = onQuickDrawing,
                isDetailExpanded = isDetailFullscreen,
                onToggleExpandDetail = { isDetailFullscreen = !isDetailFullscreen },
            )
        }

        if (windowInfo.useDualPane) {
            // Tablet Landscape: Dual-Pane Master & Detail
            Row(modifier = Modifier.fillMaxSize()) {
                // Master Pane (Home, Tasks, Spaces, Daily)
                AnimatedVisibility(
                    visible = !isDetailFullscreen,
                    enter = slideInHorizontally(AppMotion.offsetGentle) { -it } + fadeIn(),
                    exit = slideOutHorizontally(AppMotion.offsetGentle) { -it } + fadeOut(),
                    modifier = Modifier
                        .widthIn(min = 360.dp, max = 440.dp)
                        .fillMaxHeight(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(org.yaop.ui.theme.AppBackgroundBrush.Background),
                    ) {
                        TabletMasterPaneContent(
                            currentTab = currentTab,
                            repository = repository,
                            onTabSelected = onTabSelected,
                            onSelectObject = onSelectObject,
                            saveableStateHolder = masterSaveableStateHolder,
                        )
                    }
                }

                if (!isDetailFullscreen) {
                    VerticalDivider(
                        color = AppColors.BorderSubtle,
                        thickness = 1.dp,
                    )
                }

                // Detail Pane (Active Document or Empty Placeholder)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(org.yaop.ui.theme.AppBackgroundBrush.Background),
                ) {
                    AnimatedContent(
                        targetState = selectedObjectId,
                        transitionSpec = {
                            (fadeIn(tween(AppMotion.FADE_MS)) + slideInHorizontally(AppMotion.offsetGentle) { it / 8 })
                                .togetherWith(fadeOut(tween(AppMotion.FADE_FAST_MS)))
                        },
                        label = "TabletDetailContentTransition",
                        modifier = Modifier.fillMaxSize(),
                    ) { detailId ->
                        if (detailId != null) {
                            detailSaveableStateHolder.SaveableStateProvider(detailId) {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    // Editor Document Column
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                    ) {
                                        // Tablet Top Bar for Detail Pane
                                        TabletDetailTopBar(
                                            activeObject = activeObject,
                                            isFullscreen = isDetailFullscreen,
                                            isInspectorOpen = isInspectorOpen,
                                            onToggleFullscreen = { isDetailFullscreen = !isDetailFullscreen },
                                            onToggleInspector = { isInspectorOpen = !isInspectorOpen },
                                            onClose = { onSelectObject(null) },
                                            onQuickDrawing = onQuickDrawing,
                                        )

                                        // Document content centered with maximum reading width
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 16.dp),
                                            contentAlignment = Alignment.TopCenter,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .widthIn(max = 840.dp),
                                            ) {
                                                val dailyDate = if (detailId.startsWith("daily-")) {
                                                    runCatching { LocalDate.parse(detailId.removePrefix("daily-")) }.getOrNull()
                                                } else null

                                                if (dailyDate != null) {
                                                    DailyScreen(
                                                        repository = repository,
                                                        initialDate = dailyDate,
                                                        onNavigateToObject = { onSelectObject(it) },
                                                        onBack = { onSelectObject(null) },
                                                    )
                                                } else {
                                                    ObjectDetailScreen(
                                                        objectId = detailId,
                                                        repository = repository,
                                                        onBack = { onSelectObject(null) },
                                                        onNavigateToObject = { onSelectObject(it) },
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Optional Right Inspector Drawer
                                    AnimatedVisibility(
                                        visible = isInspectorOpen,
                                        enter = slideInHorizontally(AppMotion.offsetGentle) { it } + fadeIn(),
                                        exit = slideOutHorizontally(AppMotion.offsetGentle) { it } + fadeOut(),
                                    ) {
                                        val allObjectsForInspector by repository.getAllActiveObjects().collectAsState(initial = emptyList())
                                        TabletInspectorPanel(
                                            activeObject = activeObject,
                                            allObjects = allObjectsForInspector,
                                            allTypes = allTypes,
                                            onCloseInspector = { isInspectorOpen = false },
                                            onNavigateToObject = { onSelectObject(it) },
                                        )
                                    }
                                }
                            }
                        } else {
                            // Empty selection placeholder on tablet landscape
                            TabletEmptyDetailPlaceholder(
                                onCreateNote = { onCreateObject(DefaultTypes.NOTE.id) },
                                onCreateTask = { onCreateObject(DefaultTypes.TASK.id) },
                                onQuickDrawing = onQuickDrawing,
                            )
                        }
                    }
                }
            }
        } else {
            // Tablet Portrait: Focused Single Pane with comfortable margins
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(org.yaop.ui.theme.AppBackgroundBrush.Background),
                contentAlignment = Alignment.TopCenter,
            ) {
                AnimatedContent(
                    targetState = selectedObjectId,
                    transitionSpec = {
                        val forward = targetState != null
                        (fadeIn(tween(AppMotion.FADE_MS)) + slideInHorizontally(AppMotion.offsetGentle) { if (forward) it / 6 else -it / 6 })
                            .togetherWith(fadeOut(tween(AppMotion.FADE_FAST_MS)))
                    },
                    label = "TabletPortraitDetailTransition",
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) { portraitDetailId ->
                    if (portraitDetailId != null) {
                        detailSaveableStateHolder.SaveableStateProvider(portraitDetailId) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .widthIn(max = 840.dp),
                            ) {
                                val dailyDate = if (portraitDetailId.startsWith("daily-")) {
                                    runCatching { LocalDate.parse(portraitDetailId.removePrefix("daily-")) }.getOrNull()
                                } else null

                                if (dailyDate != null) {
                                    DailyScreen(
                                        repository = repository,
                                        initialDate = dailyDate,
                                        onNavigateToObject = { onSelectObject(it) },
                                        onBack = { onSelectObject(null) },
                                    )
                                } else {
                                    ObjectDetailScreen(
                                        objectId = portraitDetailId,
                                        repository = repository,
                                        onBack = { onSelectObject(null) },
                                        onNavigateToObject = { onSelectObject(it) },
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .widthIn(max = 840.dp),
                        ) {
                            TabletMasterPaneContent(
                                currentTab = currentTab,
                                repository = repository,
                                onTabSelected = onTabSelected,
                                onSelectObject = onSelectObject,
                                saveableStateHolder = masterSaveableStateHolder,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Top action bar for the tablet detail editor pane.
 */
@Composable
private fun TabletDetailTopBar(
    activeObject: PkmObject?,
    isFullscreen: Boolean,
    isInspectorOpen: Boolean,
    onToggleFullscreen: () -> Unit,
    onToggleInspector: () -> Unit,
    onClose: () -> Unit,
    onQuickDrawing: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface)
            .border(width = 1.dp, color = AppColors.BorderSubtle)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left side: Title / Breadcrumb
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Text(
                text = activeObject?.icon?.emoji ?: "📄",
                fontSize = 16.sp,
            )
            Text(
                text = activeObject?.plainTitle ?: "",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Right side: Quick Drawing, Inspector, Fullscreen, Close
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Quick Brush / Drawing tool
            IconButton(
                onClick = onQuickDrawing,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(TypePalette.ColorPurple.copy(alpha = 0.15f)),
            ) {
                Icon(
                    imageVector = Icons.Default.Brush,
                    contentDescription = stringResource(R.string.tablet_quick_sketch),
                    tint = TypePalette.ColorPurple,
                    modifier = Modifier.size(18.dp),
                )
            }

            // Inspector Panel toggle
            IconButton(
                onClick = onToggleInspector,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isInspectorOpen) AppColors.Accent.copy(alpha = 0.2f) else AppColors.SurfaceElevated),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.tablet_inspector),
                    tint = if (isInspectorOpen) AppColors.Accent else AppColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }

            // Fullscreen / Split toggle
            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AppColors.SurfaceElevated),
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Default.Splitscreen else Icons.Default.Fullscreen,
                    contentDescription = stringResource(
                        if (isFullscreen) R.string.tablet_exit_fullscreen else R.string.tablet_fullscreen
                    ),
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }

            // Close Pane
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AppColors.SurfaceElevated),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.tablet_close_pane),
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Placeholder displayed on Tablet Landscape when no note or item is currently opened.
 */
@Composable
private fun TabletEmptyDetailPlaceholder(
    onCreateNote: () -> Unit,
    onCreateTask: () -> Unit,
    onQuickDrawing: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(AppColors.SurfaceElevated)
                    .border(1.dp, AppColors.BorderSubtle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✨", fontSize = 36.sp)
            }

            Text(
                text = stringResource(R.string.tablet_no_selection_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary,
                textAlign = TextAlign.Center,
            )

            Text(
                text = stringResource(R.string.tablet_no_selection_subtitle),
                fontSize = 14.sp,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onCreateNote,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.pressScale(),
                ) {
                    Icon(Icons.Default.Notes, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.tablet_action_new_note), fontSize = 13.sp)
                }

                Button(
                    onClick = onQuickDrawing,
                    colors = ButtonDefaults.buttonColors(containerColor = TypePalette.ColorPurple),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.pressScale(),
                ) {
                    Icon(Icons.Default.Brush, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.tablet_action_new_drawing), fontSize = 13.sp)
                }

                Button(
                    onClick = onCreateTask,
                    colors = ButtonDefaults.buttonColors(containerColor = TypePalette.ColorRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.pressScale(),
                ) {
                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.tablet_action_new_task), fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * Renders the master pane tab content with smooth animated transitions and state preservation.
 */
@Composable
private fun TabletMasterPaneContent(
    currentTab: NavigationTab,
    repository: PkmRepository,
    onTabSelected: (NavigationTab) -> Unit,
    onSelectObject: (String?) -> Unit,
    saveableStateHolder: SaveableStateHolder,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = currentTab,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            (slideInHorizontally(AppMotion.offsetGentle) { if (forward) it / 6 else -it / 6 } +
                fadeIn(tween(AppMotion.FADE_MS)))
                .togetherWith(
                    slideOutHorizontally(AppMotion.offsetGentle) { if (forward) -it / 6 else it / 6 } +
                        fadeOut(tween(AppMotion.FADE_FAST_MS))
                )
        },
        label = "TabletMasterPaneTabTransition",
        modifier = modifier.fillMaxSize(),
    ) { tab ->
        saveableStateHolder.SaveableStateProvider(tab.name) {
            when (tab) {
                NavigationTab.HOME -> HomeScreen(
                    repository = repository,
                    onNavigateToObject = onSelectObject,
                    onOpenTasks = { onTabSelected(NavigationTab.TASKS) },
                    onOpenSearch = { onTabSelected(NavigationTab.SEARCH) },
                    onOpenImages = { onTabSelected(NavigationTab.SEARCH) },
                )
                NavigationTab.DAILY -> DailyScreen(
                    repository = repository,
                    onNavigateToObject = onSelectObject,
                    onBack = { onTabSelected(NavigationTab.HOME) },
                )
                NavigationTab.TASKS -> TasksScreen(
                    repository = repository,
                    onNavigateToObject = onSelectObject,
                )
                NavigationTab.SEARCH -> SpacesScreen(
                    repository = repository,
                    onNavigateToObject = onSelectObject,
                )
            }
        }
    }
}
