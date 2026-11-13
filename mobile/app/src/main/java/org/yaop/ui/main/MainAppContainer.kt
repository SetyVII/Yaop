package org.yaop.ui.main

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.yaop.R
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectIcon
import org.yaop.core.model.toTextSpan
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.common.createNewAudioObjectFromBytes
import org.yaop.ui.common.createNewImageObjectFromUri
import org.yaop.ui.common.localizedName
import org.yaop.ui.daily.DailyScreen
import org.yaop.ui.editor.CreateAudioObjectSheet
import org.yaop.ui.editor.CreateWebLinkSheet
import org.yaop.ui.editor.ObjectDetailScreen
import org.yaop.ui.home.HomeScreen
import org.yaop.ui.spaces.CreateObjectDialog
import org.yaop.ui.spaces.SpacesScreen
import org.yaop.ui.tablet.TabletDualPaneScaffold
import org.yaop.ui.tablet.rememberTabletWindowInfo
import org.yaop.ui.tasks.TasksScreen
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors
import org.yaop.ui.theme.AppMotion
import org.yaop.ui.theme.LocalNavAnimatedScope
import org.yaop.ui.theme.LocalSharedOrigin
import org.yaop.ui.theme.LocalSharedTransitionScope
import org.yaop.ui.theme.SharedOriginRegistry
import org.yaop.ui.theme.pressScale
import org.yaop.ui.theme.sharedContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs

enum class NavigationTab(val index: Int) {
    HOME(0),
    DAILY(1),
    TASKS(3),
    SEARCH(4),
}

/** Key used when a freshly created object grows out of the dock's (+) button. */
private const val SHARED_KEY_NEW_OBJECT = "dock:new"

/** Dock slot of the central (+) button. */
private const val PLUS_SLOT = 2

private fun tabForSlot(slot: Int): NavigationTab? = NavigationTab.entries.firstOrNull { it.index == slot }

/** One entry of the navigation stack as seen by the top-level AnimatedContent. */
private data class NavEntry(
    val objectId: String?,
    val depth: Int,
    /** Shared-element key of the element that opened this entry (container transform). */
    val sharedKey: String?,
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MainAppContainer(
    repository: PkmRepository,
    initialObjectId: String? = null,
) {
    val tabletWindowInfo = rememberTabletWindowInfo()
    val isTablet = tabletWindowInfo.isTablet
    var selectedTabletObjectId by remember { mutableStateOf(initialObjectId) }

    val coroutineScope = rememberCoroutineScope()
    var currentTab by remember { mutableStateOf(NavigationTab.HOME) }
    // Navigation stack: opening a reference pushes; back pops. Navigating from
    // note A to note B and pressing back now returns to A instead of home.
    var navigationStack by remember { mutableStateOf(listOfNotNull(initialObjectId)) }
    // Parallel to navigationStack: shared-element key that launched each entry (or null).
    var navigationKeys by remember { mutableStateOf(navigationStack.map { null as String? }) }
    var showQuickCreateDialog by remember { mutableStateOf(value = false) }
    var quickCreateInitialTypeId by remember { mutableStateOf(DefaultTypes.NOTE.id) }
    val radialState = remember { RadialMenuState() }
    run {
        val captureCtx = androidx.compose.ui.platform.LocalContext.current
        val capturePrefs = remember { CaptureStyle.prefs(captureCtx) }
        androidx.compose.runtime.DisposableEffect(capturePrefs) {
            radialState.cardsStyle = CaptureStyle.isCards(capturePrefs)
            val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
                radialState.cardsStyle = CaptureStyle.isCards(p)
            }
            capturePrefs.registerOnSharedPreferenceChangeListener(l)
            onDispose { capturePrefs.unregisterOnSharedPreferenceChangeListener(l) }
        }
    }
    val sharedOrigin = remember { SharedOriginRegistry() }

    fun push(objectId: String) {
        navigationKeys = navigationKeys + sharedOrigin.consume()
        navigationStack = navigationStack + objectId
    }

    fun pop() {
        navigationStack = navigationStack.dropLast(1)
        navigationKeys = navigationKeys.dropLast(1)
    }

    LaunchedEffect(initialObjectId) {
        if (!initialObjectId.isNullOrBlank()) {
            if (isTablet) {
                selectedTabletObjectId = initialObjectId
            } else if (!navigationStack.contains(initialObjectId)) {
                push(initialObjectId)
            }
        }
    }

    BackHandler(
        enabled = if (isTablet) {
            selectedTabletObjectId != null || currentTab != NavigationTab.HOME
        } else {
            navigationStack.isNotEmpty() || radialState.isOpen || currentTab != NavigationTab.HOME
        }
    ) {
        if (isTablet) {
            if (selectedTabletObjectId != null) {
                selectedTabletObjectId = null
            } else if (currentTab != NavigationTab.HOME) {
                currentTab = NavigationTab.HOME
            }
        } else {
            if (radialState.isOpen) {
                radialState.close()
            } else if (navigationStack.isNotEmpty()) {
                pop()
            } else if (currentTab != NavigationTab.HOME) {
                currentTab = NavigationTab.HOME
            }
        }
    }

    val context = LocalContext.current
    var pendingImageTitle by remember { mutableStateOf("") }
    var pendingAudioTitle by remember { mutableStateOf("") }
    var pendingWebLinkTitle by remember { mutableStateOf("") }
    var showQuickAudioSheet by remember { mutableStateOf(false) }
    var showQuickWebLinkSheet by remember { mutableStateOf(false) }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        radialState.close()
        if (uri != null) {
            coroutineScope.launch {
                val newObj = createNewImageObjectFromUri(context, repository, uri, pendingImageTitle)
                if (newObj != null) {
                    if (isTablet) {
                        selectedTabletObjectId = newObj.id
                    } else {
                        sharedOrigin.pendingKey = SHARED_KEY_NEW_OBJECT
                        push(newObj.id)
                    }
                }
            }
        }
        pendingImageTitle = ""
    }

    if (showQuickAudioSheet) {
        CreateAudioObjectSheet(
            initialTitle = pendingAudioTitle,
            showTitleField = true,
            onDismiss = {
                showQuickAudioSheet = false
                pendingAudioTitle = ""
            },
            onAudioReady = { bytes, durationSeconds, mime, fileName, customTitle ->
                showQuickAudioSheet = false
                val effectiveTitle = customTitle.ifBlank { pendingAudioTitle }
                pendingAudioTitle = ""
                coroutineScope.launch {
                    val newObj = createNewAudioObjectFromBytes(
                        context = context,
                        repository = repository,
                        bytes = bytes,
                        durationSeconds = durationSeconds,
                        mime = mime,
                        customTitle = effectiveTitle,
                        rawFileName = fileName,
                    )
                    if (newObj != null) {
                        if (isTablet) {
                            selectedTabletObjectId = newObj.id
                        } else {
                            sharedOrigin.pendingKey = SHARED_KEY_NEW_OBJECT
                            push(newObj.id)
                        }
                    }
                }
            },
        )
    }

    if (showQuickWebLinkSheet) {
        CreateWebLinkSheet(
            initialTitle = pendingWebLinkTitle,
            onDismiss = {
                showQuickWebLinkSheet = false
                pendingWebLinkTitle = ""
            },
            onWebLinkCreated = { newObj ->
                showQuickWebLinkSheet = false
                pendingWebLinkTitle = ""
                if (isTablet) {
                    selectedTabletObjectId = newObj.id
                } else {
                    sharedOrigin.pendingKey = SHARED_KEY_NEW_OBJECT
                    push(newObj.id)
                }
            },
        )
    }

    val allTypes by repository.getAllTypes()
        .collectAsState(initial = repository.getCachedTypes().ifEmpty { DefaultTypes.ALL })

    LaunchedEffect(Unit) {
        repository.syncDefaultTypes()
    }

    fun createAndOpenObject(typeId: String, title: String = "", emoji: String? = null) {
        val canonicalType = typeId
        if (canonicalType == DefaultTypes.IMAGE.id) {
            // An Image object CANNOT be created without an image. Prompt the user immediately.
            pendingImageTitle = title
            imagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
            return
        }
        if (canonicalType == DefaultTypes.AUDIO.id) {
            // An Audio object CANNOT be created without audio. Prompt the user immediately.
            radialState.close()
            pendingAudioTitle = title
            showQuickAudioSheet = true
            return
        }
        if (canonicalType == DefaultTypes.WEBLINK.id) {
            // A WebLink object CANNOT be created without a link. Prompt the user immediately.
            radialState.close()
            pendingWebLinkTitle = title
            showQuickWebLinkSheet = true
            return
        }

        coroutineScope.launch {
            val newId = UUID.randomUUID().toString()
            val type = allTypes.find { it.id == typeId }
            val finalEmoji = emoji ?: type?.icon?.emoji ?: "📝"
            val initialBlocks = if (canonicalType == DefaultTypes.WHITEBOARD.id) {
                listOf(org.yaop.core.model.Block.Canvas(id = UUID.randomUUID().toString(), data = org.yaop.core.model.whiteboard.WhiteboardData(id = newId)))
            } else {
                emptyList()
            }
            val newObj = PkmObject(
                id = newId,
                typeId = typeId,
                title = if (title.isNotBlank()) title.toTextSpan() else emptyList(),
                icon = ObjectIcon(emoji = finalEmoji),
                blocks = initialBlocks,
            )
            repository.saveObject(newObj)
            radialState.close()
            if (isTablet) {
                selectedTabletObjectId = newId
            } else {
                sharedOrigin.pendingKey = SHARED_KEY_NEW_OBJECT
                push(newId)
            }
        }
    }

    // Radial menu content -------------------------------------------------------------------
    val quickItems = listOf(
        RadialItem(DefaultTypes.NOTE.id, "📝", stringResource(R.string.quick_action_note), TypePalette.ColorIce),
        RadialItem(DefaultTypes.TASK.id, "✅", stringResource(R.string.quick_action_task), TypePalette.ColorRed),
        RadialItem(DefaultTypes.WEBLINK.id, "🌐", stringResource(R.string.quick_action_link), TypePalette.ColorTeal),
        RadialItem(DefaultTypes.IMAGE.id, "🖼️", stringResource(R.string.quick_action_image), TypePalette.ColorPurple),
        RadialItem(DefaultTypes.AUDIO.id, "🎙️", stringResource(R.string.quick_action_audio), TypePalette.ColorPink),
        RadialItem(DefaultTypes.HABIT.id, "🌱", stringResource(R.string.quick_action_habit), TypePalette.ColorLime),
    )
    val wheelItems = allTypes
        .filter { type ->
            val canonical = type.id
            canonical != DefaultTypes.DAILY.id && canonical != DefaultTypes.TAG.id
        }
        .map { type ->
            RadialItem(
                typeId = type.id,
                emoji = type.icon.emoji ?: "📄",
                label = type.localizedName(),
                color = type.colorHex.toTypeColor(AppColors.TextSecondary),
            )
        }

    fun onRadialPick(item: RadialItem) {
        val typeId = item.typeId
        if (typeId == null) {
            radialState.close()
            quickCreateInitialTypeId = DefaultTypes.NOTE.id
            showQuickCreateDialog = true
        } else {
            createAndOpenObject(typeId = typeId)
        }
    }

    val saveableStateHolder = rememberSaveableStateHolder()

    if (isTablet) {
        TabletDualPaneScaffold(
            repository = repository,
            currentTab = currentTab,
            onTabSelected = { currentTab = it },
            selectedObjectId = selectedTabletObjectId,
            onSelectObject = { selectedTabletObjectId = it },
            onCreateObject = { typeId -> createAndOpenObject(typeId) },
            onQuickDrawing = { createAndOpenObject(DefaultTypes.WHITEBOARD.id) },
            windowInfo = tabletWindowInfo,
        )
    } else {
        CompositionLocalProvider(LocalSharedOrigin provides sharedOrigin) {
            SharedTransitionLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .background(org.yaop.ui.theme.AppBackgroundBrush.Background),
            ) {
                val entry = NavEntry(
                    objectId = navigationStack.lastOrNull(),
                    depth = navigationStack.size,
                    sharedKey = navigationKeys.lastOrNull(),
                )
                AnimatedContent(
                    targetState = entry,
                    contentKey = { it.objectId to it.depth },
                    transitionSpec = {
                        val isPush = targetState.depth > initialState.depth
                        val sharedKey = if (isPush) targetState.sharedKey else initialState.sharedKey
                        if (sharedKey != null) {
                            // Container transform: the shared bounds do the motion, the rest cross-fades.
                            fadeIn(tween(AppMotion.FADE_MS)) togetherWith fadeOut(tween(AppMotion.FADE_MS))
                        } else if (isPush) {
                            (
                                slideInHorizontally(AppMotion.offsetGentle) { w -> w / 4 } +
                                    fadeIn(tween(AppMotion.FADE_MS))
                                ) togetherWith (
                                scaleOut(AppMotion.gentle(), targetScale = 0.96f) +
                                    fadeOut(tween(AppMotion.FADE_MS))
                                )
                        } else {
                            (
                                scaleIn(AppMotion.gentle(), initialScale = 0.96f) +
                                    fadeIn(tween(AppMotion.FADE_MS))
                                ) togetherWith (
                                slideOutHorizontally(AppMotion.offsetGentle) { w -> w / 4 } +
                                    fadeOut(tween(AppMotion.FADE_FAST_MS))
                                )
                        }.using(SizeTransform(clip = false))
                    },
                    label = "NavigationTransition",
                ) { navEntry ->
                    CompositionLocalProvider(
                        LocalSharedTransitionScope provides this@SharedTransitionLayout,
                        LocalNavAnimatedScope provides this,
                    ) {
                        val objectId = navEntry.objectId
                        // Guard: an exiting screen must not pop the stack a second time.
                        val popIfCurrent: () -> Unit = {
                            if (navigationStack.size == navEntry.depth && navigationStack.lastOrNull() == objectId) pop()
                        }
                        if (objectId == null) {
                            saveableStateHolder.SaveableStateProvider("tabs") {
                                TabsHost(
                                    repository = repository,
                                    currentTab = currentTab,
                                    onTabSelected = {
                                        radialState.close()
                                        currentTab = it
                                    },
                                    radialState = radialState,
                                    quickItems = quickItems,
                                    wheelItems = wheelItems,
                                    onRadialPick = ::onRadialPick,
                                    onNavigateToObject = ::push,
                                )
                            }
                        } else {
                            saveableStateHolder.SaveableStateProvider("obj:$objectId:${navEntry.depth}") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .sharedContainer(navEntry.sharedKey)
                                        .background(org.yaop.ui.theme.AppBackgroundBrush.Background),
                                ) {
                                    val dailyDate = if (objectId.startsWith("daily-")) {
                                        runCatching { LocalDate.parse(objectId.removePrefix("daily-")) }.getOrNull()
                                    } else null

                                    if (dailyDate != null) {
                                        DailyScreen(
                                            repository = repository,
                                            initialDate = dailyDate,
                                            onNavigateToObject = ::push,
                                            onBack = popIfCurrent,
                                        )
                                    } else {
                                        ObjectDetailScreen(
                                            objectId = objectId,
                                            repository = repository,
                                            onBack = popIfCurrent,
                                        ) { targetId -> push(targetId) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQuickCreateDialog) {
        CreateObjectDialog(
            types = allTypes,
            initialTypeId = quickCreateInitialTypeId,
            onDismiss = { showQuickCreateDialog = false },
        ) { typeId, title, emoji ->
            createAndOpenObject(typeId = typeId, title = title, emoji = emoji)
            showQuickCreateDialog = false
        }
    }
}

@Composable
private fun TabsHost(
    repository: PkmRepository,
    currentTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    radialState: RadialMenuState,
    quickItems: List<RadialItem>,
    wheelItems: List<RadialItem>,
    onRadialPick: (RadialItem) -> Unit,
    onNavigateToObject: (String) -> Unit,
) {
    val tabStateHolder: SaveableStateHolder = rememberSaveableStateHolder()
    var searchInitialTypeId by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    // Keep visited primary tabs' animation wrappers warm so switching (e.g. Home <-> Daily)
    // stays smooth, while the actual screen content is disposed once fully hidden (see below).
    val warmTabs = remember { mutableStateSetOf(currentTab) }
    LaunchedEffect(currentTab) {
        warmTabs.add(currentTab)
    }

    Box(modifier = Modifier.fillMaxSize().background(org.yaop.ui.theme.AppBackgroundBrush.Background)) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                FloatingPillDock(
                    currentTab = currentTab,
                    onTabSelected = { tab ->
                        if (tab != currentTab) {
                            focusManager.clearFocus()
                        }
                        if (tab == NavigationTab.SEARCH) searchInitialTypeId = null
                        onTabSelected(tab)
                    },
                    radialState = radialState,
                    onWheelRelease = { index ->
                        val item = wheelItems.getOrNull(index)
                        if (radialState.cardsStyle) return@FloatingPillDock
                        if (item != null) onRadialPick(item) else radialState.close()
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()) // content runs under the floating pill
                    .consumeWindowInsets(innerPadding),
            ) {
                for (tab in NavigationTab.entries) {
                    if (tab in warmTabs) {
                        key(tab) {
                            val isSelected = tab == currentTab
                            val tabAlpha by animateFloatAsState(
                                targetValue = if (isSelected) 1f else 0f,
                                animationSpec = tween(
                                    if (isSelected) AppMotion.FADE_MS else AppMotion.FADE_FAST_MS,
                                ),
                                label = "TabAlpha_${tab.name}",
                            )
                            val slideFraction by animateFloatAsState(
                                targetValue = when {
                                    isSelected -> 0f
                                    tab.index < currentTab.index -> -0.12f
                                    else -> 0.12f
                                },
                                animationSpec = AppMotion.snappy(),
                                label = "TabSlide_${tab.name}",
                            )
                            val isVisible = isSelected || tabAlpha > 0.005f

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (isSelected) 1f else 0f)
                                    .layout { measurable, constraints ->
                                        if (!isVisible) {
                                            layout(0, 0) {}
                                        } else {
                                            val placeable = measurable.measure(constraints)
                                            layout(placeable.width, placeable.height) {
                                                placeable.place(0, 0)
                                            }
                                        }
                                    }
                                    .graphicsLayer {
                                        alpha = tabAlpha
                                        translationX = size.width * slideFraction
                                    },
                            ) {
                                // Keep the outgoing screen composed only while its fade-out animation
                                // is still visible. Once fully hidden, dispose it so Home/Daily/Tasks/
                                // Search do not keep collecting flows and recomposing in the background.
                                if (isVisible) {
                                    tabStateHolder.SaveableStateProvider(tab.name) {
                                        when (tab) {
                                            NavigationTab.HOME -> HomeScreen(
                                                repository = repository,
                                                onNavigateToObject = onNavigateToObject,
                                                onOpenTasks = { onTabSelected(NavigationTab.TASKS) },
                                                onOpenSearch = {
                                                    searchInitialTypeId = null
                                                    onTabSelected(NavigationTab.SEARCH)
                                                },
                                                onOpenImages = {
                                                    searchInitialTypeId = DefaultTypes.IMAGE.id
                                                    onTabSelected(NavigationTab.SEARCH)
                                                },
                                            )
                                            NavigationTab.SEARCH -> SpacesScreen(
                                                repository = repository,
                                                onNavigateToObject = onNavigateToObject,
                                                initialFocusSearch = searchInitialTypeId == null,
                                                initialSelectedTypeId = searchInitialTypeId,
                                                isActiveTab = isSelected,
                                            )
                                            NavigationTab.DAILY -> DailyScreen(
                                                repository = repository,
                                                onNavigateToObject = onNavigateToObject,
                                                onBack = { onTabSelected(NavigationTab.HOME) },
                                            )
                                            NavigationTab.TASKS -> TasksScreen(
                                                repository = repository,
                                                onNavigateToObject = onNavigateToObject,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Radial quick capture (fan on tap, full wheel on hold + drag), anchored on the dock (+).
        RadialQuickCaptureOverlay(
            state = radialState,
            quickItems = quickItems,
            wheelItems = wheelItems,
            onPick = onRadialPick,
        )
    }
}

/**
 * Floating pill dock.
 * - Tap a slot: the droplet travels there with a bouncy spring and stretches with its speed.
 * - Drag anywhere on the dock: the droplet follows the finger in real time (scrubbing), the
 *   hovered icon lights up with a haptic tick per slot, release selects the hovered tab.
 * - (+): tap opens the radial fan; hold opens the full type wheel, drag to a type, release.
 */
@Composable
fun FloatingPillDock(
    currentTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    radialState: RadialMenuState,
    onWheelRelease: (hoveredIndex: Int) -> Unit,
) {
    val tabCount = 5 // 0: Home, 1: Daily, 2: Center +, 3: Tasks, 4: Search
    val selectedIndex = currentTab.index
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(34.dp))
                .background(Color(0xFF0B1A4A), RoundedCornerShape(34.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(34.dp))
                .padding(horizontal = 10.dp),
        ) {
            val slotWidthPx = constraints.maxWidth / tabCount.toFloat()
            val indicatorSize = 44.dp
            val indicatorPx = with(density) { indicatorSize.toPx() }
            fun slotCenter(slot: Int) = slotWidthPx * (slot + 0.5f)

            // Droplet center X in px. Animatable so taps (bouncy) and drags (tracking) share it.
            val dropletX = remember { Animatable(slotCenter(selectedIndex)) }
            var isScrubbing by remember { mutableStateOf(false) }
            var hoveredSlot by remember { mutableIntStateOf(selectedIndex) }

            LaunchedEffect(selectedIndex, slotWidthPx) {
                if (!isScrubbing) {
                    hoveredSlot = selectedIndex
                    val currentX = dropletX.value
                    val targetX = slotCenter(selectedIndex)
                    val distanceSlots = (abs(targetX - currentX) / slotWidthPx.coerceAtLeast(1f)).coerceAtLeast(1f)
                    // Distance-adaptive spring: 1-slot jumps (e.g. Home <-> Daily) use higher stiffness
                    // so the droplet feels crisp and lively instead of sluggish, while multi-slot jumps
                    // retain the full fluid bouncy glide.
                    val spec = if (distanceSlots <= 1.5f) {
                        androidx.compose.animation.core.spring<Float>(dampingRatio = 0.75f, stiffness = 650f)
                    } else {
                        AppMotion.bouncy<Float>()
                    }
                    dropletX.animateTo(targetX, spec)
                }
            }

            val lift by animateFloatAsState(
                targetValue = if (isScrubbing) 1f else 0f,
                animationSpec = AppMotion.snappy(),
                label = "DropletLift",
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(slotWidthPx, selectedIndex) {
                        fun clampX(x: Float) = x.coerceIn(slotCenter(0), slotCenter(tabCount - 1))
                        fun slotAt(x: Float) = (x / slotWidthPx).toInt().coerceIn(0, tabCount - 1)
                        var lastX = slotCenter(selectedIndex)
                        detectHorizontalDragGestures(
                            onDragStart = { start ->
                                isScrubbing = true
                                radialState.close()
                                lastX = clampX(start.x)
                                hoveredSlot = slotAt(lastX)
                                scope.launch { dropletX.animateTo(lastX, AppMotion.tracking()) }
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                lastX = clampX(change.position.x)
                                scope.launch { dropletX.animateTo(lastX, AppMotion.tracking()) }
                                val slot = slotAt(lastX)
                                if (slot != hoveredSlot) {
                                    hoveredSlot = slot
                                    if (slot != PLUS_SLOT) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onDragEnd = {
                                isScrubbing = false
                                val slot = if (hoveredSlot == PLUS_SLOT) {
                                    if (lastX < slotCenter(PLUS_SLOT)) PLUS_SLOT - 1 else PLUS_SLOT + 1
                                } else {
                                    hoveredSlot
                                }
                                val tab = tabForSlot(slot)
                                hoveredSlot = slot
                                if (tab != null && tab != currentTab) {
                                    onTabSelected(tab)
                                } else {
                                    scope.launch { dropletX.animateTo(slotCenter(selectedIndex), AppMotion.bouncy()) }
                                }
                            },
                            onDragCancel = {
                                isScrubbing = false
                                hoveredSlot = selectedIndex
                                scope.launch { dropletX.animateTo(slotCenter(selectedIndex), AppMotion.bouncy()) }
                            },
                        )
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val litSlot = if (isScrubbing) hoveredSlot else selectedIndex

                DockTouchSlot(
                    icon = Icons.Default.Home,
                    contentDescription = stringResource(R.string.dock_tab_home),
                    label = stringResource(R.string.dock_tab_home),
                    isSelected = litSlot == NavigationTab.HOME.index,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTabSelected(NavigationTab.HOME)
                    },
                    modifier = Modifier.weight(1f),
                )

                DockTouchSlot(
                    icon = Icons.Default.CalendarToday,
                    contentDescription = stringResource(R.string.dock_tab_daily),
                    label = stringResource(R.string.dock_tab_daily),
                    isSelected = litSlot == NavigationTab.DAILY.index,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTabSelected(NavigationTab.DAILY)
                    },
                    modifier = Modifier.weight(1f),
                )

                // Slot 2: Central (+) — tap = radial fan, hold + drag = full type wheel.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    DockPlusButton(
                        radialState = radialState,
                        onWheelRelease = onWheelRelease,
                    )
                }

                DockTouchSlot(
                    icon = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.dock_tab_tasks),
                    label = stringResource(R.string.dock_tab_tasks),
                    isSelected = litSlot == NavigationTab.TASKS.index,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTabSelected(NavigationTab.TASKS)
                    },
                    modifier = Modifier.weight(1f),
                )

                DockTouchSlot(
                    icon = Icons.Default.Search,
                    contentDescription = stringResource(R.string.dock_tab_search),
                    label = stringResource(R.string.dock_tab_search),
                    isSelected = litSlot == NavigationTab.SEARCH.index,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTabSelected(NavigationTab.SEARCH)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DockPlusButton(
    radialState: RadialMenuState,
    onWheelRelease: (hoveredIndex: Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val currentOnWheelRelease by rememberUpdatedState(onWheelRelease)
    var buttonOrigin by remember { mutableStateOf(Offset.Zero) }
    val isOpen = radialState.isOpen
    val plusRotation by animateFloatAsState(
        targetValue = if (isOpen) 45f else 0f,
        animationSpec = AppMotion.bouncy(),
        label = "PlusRotation",
    )
    val centralScale by animateFloatAsState(
        targetValue = when (radialState.mode) {
            RadialMode.WHEEL -> 0.9f
            RadialMode.FAN -> 1.08f
            RadialMode.CLOSED -> 1f
        },
        animationSpec = AppMotion.bouncy(),
        label = "CentralButtonScale",
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = centralScale
                scaleY = centralScale
            }
            .sharedContainer(SHARED_KEY_NEW_OBJECT, CircleShape)
            .clip(CircleShape)
            .background(AppColors.Accent, CircleShape)
            .onGloballyPositioned { coords ->
                buttonOrigin = coords.positionInRoot()
                radialState.anchor = coords.boundsInRoot().center
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var finishedEarly = false
                    var released = false
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        released = waitForUpOrCancellation() != null
                        finishedEarly = true
                    }
                    if (finishedEarly) {
                        // Tap (or cancelled because the dock started scrubbing).
                        if (released) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (radialState.isOpen) radialState.close() else radialState.openFan()
                        }
                        return@awaitEachGesture
                    }
                    // Long press: open the wheel and track the finger until release.
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    radialState.openWheel()
                    radialState.updatePointer(buttonOrigin + down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            currentOnWheelRelease(radialState.hoveredIndex)
                            return@awaitEachGesture
                        }
                        change.consume()
                        if (radialState.updatePointer(buttonOrigin + change.position) && radialState.hoveredIndex >= 0) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    radialState.close()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = stringResource(R.string.dock_quick_capture),
            tint = AppColors.AccentInk,
            modifier = Modifier
                .size(24.dp)
                .rotate(plusRotation),
        )
    }
}

@Composable
private fun DockTouchSlot(
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    label: String? = null,
    tintOverride: Color? = null,
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.15f else 1.0f,
        animationSpec = AppMotion.bouncy(),
        label = "IconScale",
    )
    val interactionSource = remember { MutableInteractionSource() }

    val tint = tintOverride ?: if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier
                    .size(22.dp)
                    .pressScale(interactionSource, pressedScale = 0.85f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            )
            if (label != null) {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    color = tint,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
