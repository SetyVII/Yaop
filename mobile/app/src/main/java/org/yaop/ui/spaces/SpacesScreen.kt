package org.yaop.ui.spaces

import org.yaop.ui.common.DesignModalBottomSheet
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import org.yaop.R
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectIcon
import org.yaop.core.model.ObjectType
import org.yaop.core.model.toTextSpan
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.common.ObjectCard
import org.yaop.ui.common.createNewAudioObjectFromBytes
import org.yaop.ui.common.createNewImageObjectFromUri
import org.yaop.ui.common.localizedName
import org.yaop.ui.editor.CreateAudioObjectSheet
import org.yaop.ui.editor.CreateWebLinkSheet
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors
import org.yaop.ui.theme.AppMotion
import org.yaop.ui.theme.LocalSharedOrigin
import org.yaop.ui.theme.sharedObjectBounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

enum class ImageViewMode {
    GALLERY,
    LIST,
}

enum class HomeSectionMode {
    SPACE,
    TYPES,
    SETS,
}

@Composable
fun SpacesScreen(
    repository: PkmRepository,
    onNavigateToObject: (String) -> Unit,
    initialFocusSearch: Boolean = false,
    initialSelectedTypeId: String? = null,
    isActiveTab: Boolean = true,
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val sharedOrigin = LocalSharedOrigin.current
    val openFromSpaces: (String) -> Unit = { targetId ->
        sharedOrigin.mark("spaces", targetId)
        onNavigateToObject(targetId)
    }
    val uiPrefs = remember(context) { context.getSharedPreferences("yaop_ui_prefs", Context.MODE_PRIVATE) }
    var imageViewMode by remember {
        mutableStateOf(
            runCatching {
                val saved = uiPrefs.getString("image_view_mode", ImageViewMode.GALLERY.name)
                ImageViewMode.valueOf(saved ?: ImageViewMode.GALLERY.name)
            }.getOrDefault(ImageViewMode.GALLERY)
        )
    }

    var sectionMode by remember { mutableStateOf(HomeSectionMode.SPACE) }
    var selectedTypeId by remember { mutableStateOf(initialSelectedTypeId) }
    var showTypeFilters by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(value = false) }
    var dialogInitialTypeId by remember { mutableStateOf(DefaultTypes.NOTE.id) }

    var pendingImageTitle by remember { mutableStateOf("") }
    var pendingAudioTitle by remember { mutableStateOf("") }
    var pendingWebLinkTitle by remember { mutableStateOf("") }
    var showCreateAudioSheet by remember { mutableStateOf(false) }
    var showCreateWebLinkSheet by remember { mutableStateOf(false) }
    val imagePickerForCreation = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        showCreateDialog = false
        if (uri != null) {
            coroutineScope.launch {
                val created = createNewImageObjectFromUri(context, repository, uri, pendingImageTitle)
                if (created != null) {
                    onNavigateToObject(created.id)
                }
            }
        }
        pendingImageTitle = ""
    }

    if (showCreateWebLinkSheet) {
        CreateWebLinkSheet(
            initialTitle = pendingWebLinkTitle,
            onDismiss = {
                showCreateWebLinkSheet = false
                pendingWebLinkTitle = ""
            },
            onWebLinkCreated = { created ->
                showCreateWebLinkSheet = false
                pendingWebLinkTitle = ""
                onNavigateToObject(created.id)
            },
        )
    }

    if (showCreateAudioSheet) {
        CreateAudioObjectSheet(
            initialTitle = pendingAudioTitle,
            showTitleField = true,
            onDismiss = {
                showCreateAudioSheet = false
                pendingAudioTitle = ""
            },
            onAudioReady = { bytes, durationSeconds, mime, fileName, customTitle ->
                showCreateAudioSheet = false
                val effectiveTitle = customTitle.ifBlank { pendingAudioTitle }
                pendingAudioTitle = ""
                coroutineScope.launch {
                    val created = createNewAudioObjectFromBytes(
                        context = context,
                        repository = repository,
                        bytes = bytes,
                        durationSeconds = durationSeconds,
                        mime = mime,
                        customTitle = effectiveTitle,
                        rawFileName = fileName,
                    )
                    if (created != null) {
                        onNavigateToObject(created.id)
                    }
                }
            },
        )
    }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(initialFocusSearch, initialSelectedTypeId, isActiveTab) {
        if (!isActiveTab) return@LaunchedEffect
        if (initialSelectedTypeId != null) {
            sectionMode = HomeSectionMode.SPACE
            selectedTypeId = initialSelectedTypeId
            searchQuery = ""
        } else if (initialFocusSearch) {
            sectionMode = HomeSectionMode.SPACE
            // Allow the horizontal tab transition to settle before triggering IME keyboard insets
            delay(AppMotion.FADE_MS.toLong())
            runCatching { searchFocusRequester.requestFocus() }
        } else {
            searchQuery = ""
        }
    }

    val allTypes by repository.getAllTypes()
        .collectAsState(initial = repository.getCachedTypes().ifEmpty { DefaultTypes.ALL })
    val allActiveObjects by repository.getAllActiveObjects()
        .collectAsState(initial = repository.getCachedAllActiveObjects())
    val trashedObjects by repository.getTrashObjects().collectAsState(initial = emptyList())

    val typeCounts = remember(allActiveObjects) {
        val counts = mutableMapOf<String, Int>()
        for (obj in allActiveObjects) {
            val canonical = obj.typeId
            counts[canonical] = (counts[canonical] ?: 0) + 1
            if (obj.typeId != canonical) {
                counts[obj.typeId] = (counts[obj.typeId] ?: 0) + 1
            }
        }
        counts
    }

    val pinnedObjects = remember(allActiveObjects) {
        allActiveObjects.filter { it.isPinned }
    }

    val primordialOrder = remember { DefaultTypes.PRIMORDIAL.map { type -> type.id } }
    val (primordialTypes, customTypes) = remember(allTypes) {
        val primordial = allTypes.asSequence()
            .filter { t -> primordialOrder.contains(t.id) }
            .sortedBy { primordialOrder.indexOf(it.id) }
            .toList()
        val custom = allTypes.filterNot { t -> primordialOrder.contains(t.id) || t.id == DefaultTypes.SET.id }
        primordial to custom
    }

    // Debounce FTS queries so fast typing does not fire one SQLite search per keystroke.
    val searchedObjects by produceState<List<PkmObject>?>(
        initialValue = null,
        searchQuery,
    ) {
        if (searchQuery.isBlank()) {
            value = null
            return@produceState
        }
        delay(180L)
        repository.search(searchQuery).collect { value = it }
    }

    val objects = remember(searchQuery, selectedTypeId, allActiveObjects, searchedObjects) {
        val typeFilter = selectedTypeId
        when {
            searchQuery.isNotBlank() -> searchedObjects.orEmpty()
            typeFilter != null -> {
                val canonical = typeFilter
                allActiveObjects.filter { it.typeId == canonical }
            }
            else -> allActiveObjects
        }
    }

    fun triggerQuickCreateForType(typeId: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val canonical = typeId
        when (canonical) {
            DefaultTypes.IMAGE.id -> {
                pendingImageTitle = ""
                imagePickerForCreation.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
            DefaultTypes.AUDIO.id -> {
                pendingAudioTitle = ""
                showCreateAudioSheet = true
            }
            DefaultTypes.WEBLINK.id -> {
                pendingWebLinkTitle = ""
                showCreateWebLinkSheet = true
            }
            else -> {
                dialogInitialTypeId = typeId
                showCreateDialog = true
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(org.yaop.ui.theme.AppBackgroundBrush.Background),
    ) {
        val isTablet = maxWidth >= 600.dp
        val horizontalPad = if (isTablet) 24.dp else 16.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPad, vertical = 10.dp),
        ) {
            if (initialFocusSearch) {
                // Dedicated Search & Types Tab Mode (Dock Slot 4)
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        if (it.isNotBlank()) sectionMode = HomeSectionMode.SPACE
                    },
                    placeholder = {
                        Text(
                            stringResource(R.string.search_spaces_placeholder),
                            fontSize = 13.sp,
                            color = AppColors.TextTertiary,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.search_action),
                            tint = AppColors.Ice,
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_close),
                                    tint = AppColors.TextSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .focusRequester(searchFocusRequester),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = AppColors.Surface,
                        unfocusedContainerColor = AppColors.Surface,
                        focusedBorderColor = AppColors.Ice,
                        unfocusedBorderColor = AppColors.BorderSubtle,
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary,
                    ),
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Segmented Switcher [ Espacio | Tipos ] + Mosaic/List Toggle (Types lives inside Search/Explore)
            HomeTopBar(
                totalCount = allActiveObjects.size,
                sectionMode = sectionMode,
                onSectionModeChange = { newMode ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    sectionMode = newMode
                },
                imageViewMode = imageViewMode,
                onToggleViewMode = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val next = if (imageViewMode == ImageViewMode.GALLERY) ImageViewMode.LIST else ImageViewMode.GALLERY
                    imageViewMode = next
                    uiPrefs.edit().putString("image_view_mode", next.name).apply()
                },
                onResetFilter = {
                    selectedTypeId = null
                    sectionMode = HomeSectionMode.SPACE
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            AnimatedContent(
                targetState = if (searchQuery.isNotBlank()) HomeSectionMode.SPACE else sectionMode,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(180)))
                        .togetherWith(fadeOut(animationSpec = tween(140)))
                },
                label = "HomeSectionTransition",
                modifier = Modifier.fillMaxSize(),
            ) { activeSection ->
                when (activeSection) {
                    HomeSectionMode.SETS -> {
                        SetsView(
                            repository = repository,
                            allTypes = allTypes,
                            onOpenObject = openFromSpaces,
                        )
                    }
                    HomeSectionMode.TYPES -> {
                        TypesCatalogView(
                            primordialTypes = primordialTypes,
                            customTypes = customTypes,
                            typeCounts = typeCounts,
                            isTablet = isTablet,
                            onSelectType = { type ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTypeId = type.id
                                sectionMode = HomeSectionMode.SPACE
                            },
                            onQuickCreateType = { type ->
                                triggerQuickCreateForType(type.id)
                            },
                            onCreateType = { name, color ->
                                coroutineScope.launch {
                                    repository.saveType(
                                        ObjectType(
                                            id = "custom_" + java.util.UUID.randomUUID().toString().take(8),
                                            name = name,
                                            icon = ObjectIcon(emoji = "🏷️"),
                                            colorHex = color,
                                        ),
                                    )
                                }
                            },
                        )
                    }

                    HomeSectionMode.SPACE -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Compact Search layout: type chips live behind "Filters"
                            Text(
                                text = (if (showTypeFilters || selectedTypeId != null) "▾ " else "▸ ") + stringResource(R.string.search_filters),
                                fontSize = 13.sp,
                                color = if (selectedTypeId != null) AppColors.Ice else AppColors.TextSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showTypeFilters = !showTypeFilters; if (!showTypeFilters) selectedTypeId = null }
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                            )
                            if (showTypeFilters || selectedTypeId != null) LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                item {
                                    val isAllSelected = selectedTypeId == null
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                color = if (isAllSelected) AppColors.AccentSubtle else AppColors.Surface,
                                                shape = RoundedCornerShape(10.dp),
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isAllSelected) AppColors.Accent.copy(alpha = 0.6f) else AppColors.BorderSubtle,
                                                shape = RoundedCornerShape(10.dp),
                                            )
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                selectedTypeId = null
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.filter_all),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isAllSelected) AppColors.Accent else AppColors.TextSecondary,
                                            )
                                            Text(
                                                text = "${allActiveObjects.size}",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isAllSelected) AppColors.Accent else AppColors.TextTertiary,
                                            )
                                        }
                                    }
                                }

                                items(primordialTypes, key = { it.id }) { type ->
                                    val canonical = type.id
                                    TypeFilterChip(
                                        type = type,
                                        count = typeCounts[canonical] ?: typeCounts[type.id] ?: 0,
                                        isSelected = selectedTypeId == type.id,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            selectedTypeId = if (selectedTypeId == type.id) null else type.id
                                        },
                                    )
                                }

                                if (customTypes.isNotEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .height(18.dp)
                                                .width(1.dp)
                                                .background(AppColors.BorderSubtle),
                                        )
                                    }

                                    items(customTypes, key = { it.id }) { type ->
                                        val canonical = type.id
                                        TypeFilterChip(
                                            type = type,
                                            count = typeCounts[canonical] ?: typeCounts[type.id] ?: 0,
                                            isSelected = selectedTypeId == type.id,
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                selectedTypeId = if (selectedTypeId == type.id) null else type.id
                                            },
                                        )
                                    }
                                }

                                item(key = TRASH_FILTER_ID) {
                                    TrashFilterChip(
                                        count = trashedObjects.size,
                                        isSelected = selectedTypeId == TRASH_FILTER_ID,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            selectedTypeId = if (selectedTypeId == TRASH_FILTER_ID) null else TRASH_FILTER_ID
                                        },
                                    )
                                }
                            }

                            val isImageFiltered = selectedTypeId == DefaultTypes.IMAGE.id || selectedTypeId == "image" || selectedTypeId == "type_image"
                            val isAudioFiltered = selectedTypeId == DefaultTypes.AUDIO.id || selectedTypeId == "audio" || selectedTypeId == "type_audio"

                            Spacer(modifier = Modifier.height(12.dp))

                            if (selectedTypeId == TRASH_FILTER_ID) {
                                TrashView(
                                    trashed = trashedObjects,
                                    allTypes = allTypes,
                                    searchQuery = searchQuery,
                                    repository = repository,
                                )
                            } else {

                            // Pinned Strip (Only when viewing All in Space mode with no search query)
                            if (selectedTypeId == null && searchQuery.isBlank() && pinnedObjects.isNotEmpty()) {
                                PinnedObjectsStrip(
                                    pinnedObjects = pinnedObjects,
                                    allTypes = allTypes,
                                    onNavigateToObject = onNavigateToObject,
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                            }

                            // Contextual Header for Special Media Views (Audio / Image) or General Feed
                            if (isAudioFiltered && objects.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.home_objects_count, objects.size),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppColors.TextSecondary,
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(AppColors.AccentSubtle, RoundedCornerShape(8.dp))
                                            .border(1.dp, AppColors.Accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                            .clickable {
                                                pendingAudioTitle = ""
                                                showCreateAudioSheet = true
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = stringResource(R.string.audio_add_first),
                                                tint = AppColors.Accent,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Text(
                                                text = stringResource(R.string.audio_add_first),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = AppColors.Accent,
                                            )
                                        }
                                    }
                                }
                            } else if (isImageFiltered && objects.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (imageViewMode == ImageViewMode.GALLERY) R.string.view_mode_gallery else R.string.view_mode_list
                                        ) + " • " + stringResource(R.string.home_objects_count, objects.size),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppColors.TextSecondary,
                                    )

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(AppColors.AccentSubtle, RoundedCornerShape(8.dp))
                                            .border(1.dp, AppColors.Accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                            .clickable {
                                                imagePickerForCreation.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                )
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = stringResource(R.string.image_add_first),
                                                tint = AppColors.Accent,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Text(
                                                text = stringResource(R.string.image_add_first),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = AppColors.Accent,
                                            )
                                        }
                                    }
                                }
                            } else if (objects.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val selectedTypeObj = allTypes.find { it.id == selectedTypeId }
                                    Text(
                                        text = selectedTypeObj?.localizedName() ?: stringResource(R.string.home_section_recent),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppColors.TextSecondary,
                                    )
                                    Text(
                                        text = stringResource(
                                            if (imageViewMode == ImageViewMode.GALLERY) R.string.home_view_mosaic else R.string.home_view_list
                                        ),
                                        fontSize = 11.sp,
                                        color = AppColors.TextTertiary,
                                    )
                                }
                            }

                            if (objects.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = 60.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(text = "🌱", fontSize = 36.sp)
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = if (searchQuery.isNotBlank()) {
                                                stringResource(R.string.no_matches_found)
                                            } else if (isImageFiltered) {
                                                stringResource(R.string.image_empty_gallery)
                                            } else if (isAudioFiltered) {
                                                stringResource(R.string.audio_empty_gallery)
                                            } else {
                                                stringResource(R.string.no_objects_yet)
                                            },
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = AppColors.TextSecondary,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = stringResource(R.string.tap_to_create_hint),
                                            fontSize = 12.sp,
                                            color = AppColors.TextTertiary,
                                        )

                                        if (isImageFiltered && searchQuery.isBlank()) {
                                            Spacer(modifier = Modifier.height(14.dp))
                                            Button(
                                                onClick = {
                                                    imagePickerForCreation.launch(
                                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                                    )
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = AppColors.Accent,
                                                    contentColor = AppColors.Background,
                                                ),
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.image_add_first),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                            }
                                        } else if (isAudioFiltered && searchQuery.isBlank()) {
                                            Spacer(modifier = Modifier.height(14.dp))
                                            Button(
                                                onClick = {
                                                    pendingAudioTitle = ""
                                                    showCreateAudioSheet = true
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = AppColors.Accent,
                                                    contentColor = AppColors.Background,
                                                ),
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.audio_add_first),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                            }
                                        }
                                    }
                                }
                            } else if (isImageFiltered && imageViewMode == ImageViewMode.GALLERY) {
                                // Dedicated Staggered Masonry 4K Gallery for Images
                                LazyVerticalStaggeredGrid(
                                    columns = StaggeredGridCells.Adaptive(minSize = if (isTablet) 210.dp else 155.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalItemSpacing = 10.dp,
                                    contentPadding = PaddingValues(bottom = 120.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(objects, key = { it.id }) { obj ->
                                        val objType = allTypes.find { type -> type.id == obj.typeId }
                                        ImageGalleryCard(
                                            obj = obj,
                                            type = objType,
                                            onClick = { openFromSpaces(obj.id) },
                                            modifier = Modifier
                                                .animateItem()
                                                .sharedObjectBounds("spaces", obj.id, RoundedCornerShape(12.dp)),
                                        )
                                    }
                                }
                            } else if (isImageFiltered && imageViewMode == ImageViewMode.LIST) {
                                if (isTablet) {
                                    LazyVerticalGrid(
                                        columns = GridCells.Adaptive(minSize = 320.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(bottom = 120.dp),
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        items(objects, key = { it.id }) { obj ->
                                            val objType = allTypes.find { type -> type.id == obj.typeId }
                                            ImageListItem(
                                                obj = obj,
                                                type = objType,
                                                onClick = { openFromSpaces(obj.id) },
                                                modifier = Modifier
                                                    .animateItem()
                                                    .sharedObjectBounds("spaces", obj.id, RoundedCornerShape(12.dp)),
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(bottom = 120.dp),
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        items(objects, key = { it.id }) { obj ->
                                            val objType = allTypes.find { type -> type.id == obj.typeId }
                                            ImageListItem(
                                                obj = obj,
                                                type = objType,
                                                onClick = { openFromSpaces(obj.id) },
                                                modifier = Modifier
                                                    .animateItem()
                                                    .sharedObjectBounds("spaces", obj.id, RoundedCornerShape(12.dp)),
                                            )
                                        }
                                    }
                                }
                            } else if (imageViewMode == ImageViewMode.GALLERY && !isAudioFiltered) {
                                // Unified Bento / Staggered Mosaic Feed:
                                // Image objects render as visual masonry cards, Audios span full width on phone for waveform playback, and Notes/Bookmarks form an adaptive grid!
                                LazyVerticalStaggeredGrid(
                                    columns = StaggeredGridCells.Adaptive(minSize = if (isTablet) 240.dp else 160.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalItemSpacing = 10.dp,
                                    contentPadding = PaddingValues(bottom = 120.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(
                                        items = objects,
                                        key = { it.id },
                                        span = { obj ->
                                            val canonical = obj.typeId
                                            if (!isTablet && canonical == DefaultTypes.AUDIO.id) {
                                                StaggeredGridItemSpan.FullLine
                                            } else {
                                                StaggeredGridItemSpan.SingleLane
                                            }
                                        },
                                    ) { obj ->
                                        val objType = allTypes.find { type -> type.id == obj.typeId }
                                        val canonical = obj.typeId
                                        if (canonical == DefaultTypes.IMAGE.id) {
                                            ImageGalleryCard(
                                                obj = obj,
                                                type = objType,
                                                onClick = { openFromSpaces(obj.id) },
                                                modifier = Modifier
                                                    .animateItem()
                                                    .sharedObjectBounds("spaces", obj.id, RoundedCornerShape(12.dp)),
                                            )
                                        } else {
                                            ObjectCard(
                                                obj = obj,
                                                type = objType,
                                                onClick = { openFromSpaces(obj.id) },
                                                modifier = Modifier
                                                    .animateItem()
                                                    .sharedObjectBounds("spaces", obj.id, RoundedCornerShape(12.dp)),
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Clean List Mode (2-column adaptive grid on Tablets, 1-column on Phones)
                                if (isTablet) {
                                    LazyVerticalGrid(
                                        columns = GridCells.Adaptive(minSize = 320.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(bottom = 120.dp),
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        items(objects, key = { it.id }) { obj ->
                                            val objType = allTypes.find { type -> type.id == obj.typeId }
                                            ObjectCard(
                                                obj = obj,
                                                type = objType,
                                                onClick = { openFromSpaces(obj.id) },
                                                modifier = Modifier
                                                    .animateItem()
                                                    .sharedObjectBounds("spaces", obj.id, RoundedCornerShape(12.dp)),
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(bottom = 120.dp),
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        items(objects, key = { it.id }) { obj ->
                                            val objType = allTypes.find { type -> type.id == obj.typeId }
                                            ObjectCard(
                                                obj = obj,
                                                type = objType,
                                                onClick = { openFromSpaces(obj.id) },
                                                modifier = Modifier
                                                    .animateItem()
                                                    .sharedObjectBounds("spaces", obj.id, RoundedCornerShape(12.dp)),
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
        }

        if (showCreateDialog) {
            CreateObjectDialog(
                types = allTypes,
                initialTypeId = selectedTypeId?.takeIf { it != TRASH_FILTER_ID } ?: dialogInitialTypeId,
                onDismiss = { showCreateDialog = false },
            ) { typeId, title, emoji ->
                showCreateDialog = false
                val canonical = typeId
                if (canonical == DefaultTypes.IMAGE.id) {
                    // An image object needs an image: open the photo picker directly
                    pendingImageTitle = title
                    imagePickerForCreation.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                } else if (canonical == DefaultTypes.AUDIO.id) {
                    // An audio object needs audio: open the recorder / picker sheet directly
                    pendingAudioTitle = title
                    showCreateAudioSheet = true
                } else if (canonical == DefaultTypes.WEBLINK.id) {
                    // A web link needs a link: open the web link sheet directly
                    pendingWebLinkTitle = title
                    showCreateWebLinkSheet = true
                } else {
                    coroutineScope.launch {
                        val newId = UUID.randomUUID().toString()
                        val initialBlocks = if (canonical == DefaultTypes.WHITEBOARD.id) {
                            listOf(org.yaop.core.model.Block.Canvas(id = UUID.randomUUID().toString(), data = org.yaop.core.model.whiteboard.WhiteboardData(id = newId)))
                        } else {
                            emptyList()
                        }
                        val newObj = PkmObject(
                            id = newId,
                            typeId = typeId,
                            title = title.toTextSpan(),
                            icon = ObjectIcon(emoji = emoji),
                            blocks = initialBlocks,
                        )
                        repository.saveObject(newObj)
                        onNavigateToObject(newId)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    totalCount: Int,
    sectionMode: HomeSectionMode,
    onSectionModeChange: (HomeSectionMode) -> Unit,
    imageViewMode: ImageViewMode,
    onToggleViewMode: () -> Unit,
    onResetFilter: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: Floating Vault / Space Pill
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(AppColors.SurfaceElevated, RoundedCornerShape(20.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(20.dp))
                .clickable(onClick = onResetFilter)
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(AppColors.Accent, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "F",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = AppColors.Background,
                )
            }
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.Surface, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "$totalCount",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextSecondary,
                )
            }
        }

        // Right: Segmented Switcher Pill [ Espacio | Tipos ] + Mosaic/List Toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppColors.SurfaceElevated, RoundedCornerShape(20.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(20.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((mode, label) in listOf(
                    HomeSectionMode.SPACE to R.string.home_tab_space,
                    HomeSectionMode.TYPES to R.string.home_tab_types,
                    HomeSectionMode.SETS to R.string.home_tab_sets,
                )) {
                    val selected = sectionMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) AppColors.Selection else Color.Transparent,
                                RoundedCornerShape(16.dp),
                            )
                            .clickable { onSectionModeChange(mode) }
                            .padding(horizontal = 11.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = stringResource(label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) AppColors.TextPrimary else AppColors.TextSecondary,
                        )
                    }
                }
            }

            if (sectionMode == HomeSectionMode.SPACE) {
                val isMosaic = imageViewMode == ImageViewMode.GALLERY
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (isMosaic) AppColors.AccentSubtle else AppColors.SurfaceElevated,
                            CircleShape,
                        )
                        .border(
                            1.dp,
                            if (isMosaic) AppColors.Accent.copy(alpha = 0.45f) else AppColors.BorderSubtle,
                            CircleShape,
                        )
                        .clickable(onClick = onToggleViewMode),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isMosaic) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = stringResource(
                            if (isMosaic) R.string.home_view_mosaic else R.string.home_view_list
                        ),
                        tint = if (isMosaic) AppColors.Accent else AppColors.TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PinnedObjectsStrip(
    pinnedObjects: List<PkmObject>,
    allTypes: List<ObjectType>,
    onNavigateToObject: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "📌 " + stringResource(R.string.home_section_pinned),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.TextSecondary,
            )
            Text(
                text = stringResource(R.string.home_objects_count, pinnedObjects.size),
                fontSize = 11.sp,
                color = AppColors.TextTertiary,
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pinnedObjects, key = { "pin_${it.id}" }) { obj ->
                val objType = allTypes.find { t -> t.id == obj.typeId }
                val typeColor = runCatching {
                    Color((objType?.colorHex ?: "#FFB522").toColorInt())
                }.getOrDefault(AppColors.Accent)

                Row(
                    modifier = Modifier
                        .widthIn(min = 165.dp, max = 230.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.Surface, RoundedCornerShape(12.dp))
                        .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(12.dp))
                        .clickable { onNavigateToObject(obj.id) }
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(typeColor.copy(alpha = 0.20f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = obj.icon?.emoji?.takeIf { it.isNotBlank() }
                                ?: objType?.icon?.emoji
                                ?: "📌",
                            fontSize = 14.sp,
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = obj.plainTitle.ifBlank { stringResource(R.string.untitled_object) },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (objType != null) {
                            Text(
                                text = objType.localizedName(),
                                fontSize = 10.sp,
                                color = AppColors.TextSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypesCatalogView(
    primordialTypes: List<ObjectType>,
    customTypes: List<ObjectType>,
    typeCounts: Map<String, Int>,
    isTablet: Boolean,
    onSelectType: (ObjectType) -> Unit,
    onQuickCreateType: (ObjectType) -> Unit,
    onCreateType: (String, String) -> Unit,
) {
    var showNewType by remember { mutableStateOf(false) }
    if (showNewType) NewTypeDialog(onDismiss = { showNewType = false }, onCreate = { n, c -> showNewType = false; onCreateType(n, c) })
    LazyVerticalGrid(
        columns = if (isTablet) GridCells.Adaptive(minSize = 280.dp) else GridCells.Fixed(1),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 120.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "+ " + stringResource(R.string.new_type),
                color = AppColors.Ice,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showNewType = true }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_section_primordial_types),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextSecondary,
                )
                Text(
                    text = stringResource(R.string.home_types_tap_hint),
                    fontSize = 11.sp,
                    color = AppColors.TextTertiary,
                )
            }
        }

        items(primordialTypes, key = { "cat_prim_${it.id}" }) { type ->
            val canonical = type.id
            val count = typeCounts[canonical] ?: typeCounts[type.id] ?: 0
            TypeDirectoryRow(
                type = type,
                count = count,
                onClick = { onSelectType(type) },
                onQuickAdd = { onQuickCreateType(type) },
            )
        }

        if (customTypes.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_section_standard_types),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextSecondary,
                    )
                    Text(
                        text = "${customTypes.size}",
                        fontSize = 11.sp,
                        color = AppColors.TextTertiary,
                    )
                }
            }

            items(customTypes, key = { "cat_cust_${it.id}" }) { type ->
                val canonical = type.id
                val count = typeCounts[canonical] ?: typeCounts[type.id] ?: 0
                TypeDirectoryRow(
                    type = type,
                    count = count,
                    onClick = { onSelectType(type) },
                    onQuickAdd = { onQuickCreateType(type) },
                )
            }
        }
    }
}

@Composable
private fun TypeDirectoryRow(
    type: ObjectType,
    count: Int,
    onClick: () -> Unit,
    onQuickAdd: () -> Unit,
) {
    val typeColor = runCatching { Color(type.colorHex.toColorInt()) }
        .getOrDefault(AppColors.Accent)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(typeColor.copy(alpha = 0.22f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = type.icon.emoji?.takeIf { it.isNotBlank() } ?: "📝",
                    fontSize = 14.sp,
                )
            }

            Text(
                text = type.localizedName(),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "$count",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
            )

            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(AppColors.SurfaceElevated, RoundedCornerShape(7.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(7.dp))
                    .clickable(onClick = onQuickAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.create_object_action, type.localizedName()),
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun TypeFilterChip(
    type: ObjectType,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val typeColor = runCatching { Color(type.colorHex.toColorInt()) }
        .getOrDefault(AppColors.Accent)

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.03f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "ChipScale",
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(
                color = if (isSelected) typeColor.copy(alpha = 0.22f) else AppColors.Surface,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = if (isSelected) typeColor.copy(alpha = 0.7f) else AppColors.BorderSubtle,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!type.icon.emoji.isNullOrBlank()) {
                Text(text = type.icon.emoji, fontSize = 12.sp)
            }
            Text(
                text = type.localizedName(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary,
            )
            if (count > 0) {
                Text(
                    text = "$count",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) typeColor else AppColors.TextTertiary,
                )
            }
        }
    }
}

private val PRESET_CREATE_EMOJIS = listOf(
    "📝", "📅", "📚", "✅", "💡", "📁", "🌐", "🎯",
    "🚀", "🧠", "⭐", "📌", "🎨", "🔬", "💻", "🔥",
    "👤", "📖", "🏷️", "💼", "✨", "🎵", "📷", "☕",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateObjectDialog(
    types: List<ObjectType>,
    initialTypeId: String,
    onDismiss: () -> Unit,
    initialTitle: String = "",
    onCreate: (typeId: String, title: String, emoji: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTypeId by remember { mutableStateOf(initialTypeId) }
    var titleQuery by remember { mutableStateOf(initialTitle) }
    val selectedType = remember(types, selectedTypeId) {
        types.find { it.id == selectedTypeId } ?: types.firstOrNull() ?: DefaultTypes.NOTE
    }
    var currentEmoji by remember(selectedType) { mutableStateOf(selectedType.icon.emoji ?: "📝") }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val untitledDefault = stringResource(R.string.untitled_object)

    // Filter types efficiently: if user typed something that matches a type name, prioritize it; otherwise show all
    val filteredTypes = remember(types, titleQuery) {
        val q = titleQuery.trim()
        if (q.isBlank()) {
            types
        } else {
            val matchingTypes = types.filter { it.name.contains(q, ignoreCase = true) }
            if (matchingTypes.isNotEmpty()) matchingTypes else types
        }
    }

    fun submitCreate(typeId: String = selectedTypeId) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val finalTitle = titleQuery.trim().ifBlank { untitledDefault }
        val targetType = types.find { it.id == typeId } ?: selectedType
        val finalEmoji = if (typeId == selectedTypeId) currentEmoji else (targetType.icon.emoji ?: "📝")
        onCreate(typeId, finalTitle, finalEmoji)
    }

    DesignModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppColors.BorderHover, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Header: Title & Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.dialog_create_object_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary,
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // 2. Single Smart Title/Filter Input Card (Unified, no competing inputs)
            val typeColor = runCatching { Color(selectedType.colorHex.toColorInt()) }
                .getOrDefault(AppColors.Accent)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.Surface, RoundedCornerShape(14.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Clickable Emoji Picker Avatar
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(typeColor.copy(alpha = 0.18f), CircleShape)
                            .border(1.dp, typeColor.copy(alpha = 0.45f), CircleShape)
                            .clickable { showEmojiPicker = !showEmojiPicker },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = currentEmoji, fontSize = 20.sp)
                    }

                    // Unified Title input with IME Done
                    BasicTextField(
                        value = titleQuery,
                        onValueChange = { titleQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = AppColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        cursorBrush = SolidColor(AppColors.Accent),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { submitCreate() },
                        ),
                        decorationBox = { innerTextField ->
                            if (titleQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.placeholder_title_example),
                                    fontSize = 14.sp,
                                    color = AppColors.TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        },
                        modifier = Modifier.weight(1f),
                    )

                    if (titleQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { titleQuery = "" },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = AppColors.TextTertiary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            // Collapsible Emoji Quick Selector
            AnimatedVisibility(
                visible = showEmojiPicker,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    items(PRESET_CREATE_EMOJIS, key = { it }) { em ->
                        val isCurrent = em == currentEmoji
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isCurrent) AppColors.AccentSubtle else AppColors.Surface,
                                    CircleShape,
                                )
                                .border(
                                    1.dp,
                                    if (isCurrent) AppColors.Accent else AppColors.BorderSubtle,
                                    CircleShape,
                                )
                                .clickable {
                                    currentEmoji = em
                                    showEmojiPicker = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = em, fontSize = 16.sp)
                        }
                    }
                }
            }

            // Subtitle with total available count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.label_select_type),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextSecondary,
                )

                Text(
                    text = "${filteredTypes.size}",
                    fontSize = 11.sp,
                    color = AppColors.TextTertiary,
                )
            }

            // 3. Virtualized Grid: Uses weight(1f, fill = false) so it smoothly contracts when keyboard opens!
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 240.dp),
            ) {
                items(
                    items = filteredTypes,
                    key = { it.id },
                    contentType = { "type_chip" },
                ) { t ->
                    val isSel = t.id == selectedTypeId
                    val itemColor = runCatching { Color(t.colorHex.toColorInt()) }
                        .getOrDefault(AppColors.Accent)

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSel) AppColors.AccentSubtle else AppColors.Surface,
                                RoundedCornerShape(10.dp),
                            )
                            .border(
                                if (isSel) 1.5.dp else 1.dp,
                                if (isSel) AppColors.Accent else AppColors.BorderSubtle,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable {
                                selectedTypeId = t.id
                                currentEmoji = t.icon.emoji ?: "📝"
                                // Direct 1-tap creation: instant, zero-friction PKM flow
                                submitCreate(t.id)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(itemColor.copy(alpha = 0.22f), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = t.icon.emoji ?: "📝",
                                    fontSize = 13.sp,
                                )
                            }

                            Text(
                                text = t.localizedName(),
                                fontSize = 13.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSel) AppColors.Accent else AppColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )

                            if (isSel) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AppColors.Accent,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 4. Fixed-height Action Button: Guaranteed 48dp, NEVER squished by keyboard
            Button(
                onClick = { submitCreate() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Accent,
                    contentColor = AppColors.Background,
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.create_object_action, selectedType.localizedName()),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewTypeDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val colors = listOf("#6EAAFF", "#86E8A8", "#FFD666", "#FFB38A", "#FF8FB3", "#C2B2FF", "#7AF0D0")
    var color by remember { mutableStateOf(colors[0]) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_type)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.new_type_hint)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(c)))
                                .border(if (c == color) 2.dp else 0.dp, Color.White, CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim(), color) }) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
