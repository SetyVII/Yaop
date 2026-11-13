package org.yaop.ui.editor

import org.yaop.ui.common.ImagePrivacy
import org.yaop.ui.common.ImageSurface
import org.yaop.ui.common.PrivacyScope
import org.yaop.ui.common.LocalImageSurface
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.YaopApp
import org.yaop.R
import org.yaop.core.model.AssetMeta
import org.yaop.core.model.Block
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.CoverSpec
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.ObjectIcon
import org.yaop.core.model.ObjectType
import org.yaop.core.model.PropertyDefinition
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.getWhiteboardData
import org.yaop.core.model.toPlainText
import org.yaop.core.model.toTextSpan
import org.yaop.core.model.whiteboard.WhiteboardNodeContent
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.common.ObjectCard
import org.yaop.ui.common.TypeBadge
import org.yaop.ui.common.localizedName
import org.yaop.ui.common.extractAudioDurationSeconds
import org.yaop.ui.common.extractImageMime
import org.yaop.ui.common.extractImageSize
import org.yaop.ui.common.formatAudioMimeShort
import org.yaop.ui.common.formatFileSize
import org.yaop.ui.common.formatMimeShort
import org.yaop.ui.common.rememberDecodedThumbnail
import org.yaop.ui.common.resolveAudioAssetId
import org.yaop.ui.common.resolveImageAssetId
import org.yaop.ui.common.resolvePdfAssetId
import org.yaop.ui.editor.WebLinkHeroCard
import org.yaop.ui.theme.AppColors
import org.yaop.ui.whiteboard.WhiteboardScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class ObjectDetailTab {
    CONTENT,
    PROPERTIES,
    REFERENCES,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ObjectDetailScreen(
    objectId: String,
    repository: PkmRepository,
    onBack: () -> Unit,
    onNavigateToObject: (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val objState by repository.observeObject(objectId).collectAsState(initial = repository.getCachedObject(objectId))
    val allTypes by repository.getAllTypes().collectAsState(initial = repository.getCachedTypes())
    val allActiveObjects by repository.getAllActiveObjects().collectAsState(initial = repository.getCachedAllActiveObjects())
    val backlinks by repository.getBacklinks(objectId).collectAsState(initial = emptyList())
    val objectAssets by repository.getAssetsForObject(objectId).collectAsState(initial = emptyList())

    var activeTab by remember { mutableStateOf(ObjectDetailTab.CONTENT) }
    var showBottomSheet by remember { mutableStateOf(value = false) }
    var undoStack by remember { mutableStateOf<List<List<Block>>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<List<Block>>>(emptyList()) }

    fun handleExitObject() {
        val currentObj = objState
        if (currentObj != null) {
            val isOrphanImage = (currentObj.typeId == "image" || currentObj.typeId == "type_image") &&
                resolveImageAssetId(currentObj, objectAssets).isNullOrBlank()
            val isOrphanAudio = (currentObj.typeId == "audio" || currentObj.typeId == "type_audio") &&
                resolveAudioAssetId(currentObj, objectAssets).isNullOrBlank()
            if (isOrphanImage || isOrphanAudio) {
                coroutineScope.launch {
                    repository.hardDelete(currentObj.id)
                }
            }
        }
        onBack()
    }

    val toolbarController = rememberEditorToolbarController()
    // "Show images in this note": temporary, forgotten when leaving the note.
    var noteImagesRevealed by remember(objectId) { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalEditorToolbarController provides toolbarController,
        LocalImageSurface provides ImageSurface(inObject = true, noteRevealed = noteImagesRevealed),
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(org.yaop.ui.theme.AppBackgroundBrush.Background),
    ) {
        val obj = objState
        if (obj == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = AppColors.Accent,
                    strokeWidth = 2.5.dp,
                )
            }
        } else {
            val currentType: ObjectType? = allTypes.find { type -> type.id == obj.typeId }

            val combinedPropertyDefs = remember(currentType, obj.customPropertyDefs) {
                val base = currentType?.defaultProperties.orEmpty()
                val custom = obj.customPropertyDefs
                (base + custom).distinctBy { it.id }
            }

            val outgoingObjects = remember(obj, allActiveObjects, activeTab) {
                if (activeTab != ObjectDetailTab.REFERENCES) {
                    emptyList()
                } else {
                    val outgoingObjectIds = extractOutgoingObjectIds(obj, allActiveObjects)
                    allActiveObjects.filter { it.id in outgoingObjectIds }
                }
            }

            val attachmentBlocks = remember(obj.blocks) {
                obj.blocks.filter { it is Block.Image || it is Block.Audio || it is Block.Pdf }
            }

            var pendingSaveJob by remember(objectId) { mutableStateOf<Job?>(null) }
            // Non-recomposing reference so keystrokes in title/summary/blocks never trigger full-screen recomposition
            val latestUnsavedObjectRef = remember(objectId) { arrayOf<PkmObject?>(null) }

            // Sync external metadata changes (properties, icon, cover, pin) from Room into latestUnsavedObjectRef
            // without ever reverting title/summary/blocks back to a stale snapshot while typing.
            LaunchedEffect(obj) {
                val unsaved = latestUnsavedObjectRef[0]
                if (unsaved != null) {
                    if (pendingSaveJob?.isActive != true &&
                        obj.blocks == unsaved.blocks &&
                        obj.title == unsaved.title &&
                        obj.summary == unsaved.summary
                    ) {
                        latestUnsavedObjectRef[0] = null
                    } else {
                        latestUnsavedObjectRef[0] = unsaved.copy(
                            typeId = obj.typeId,
                            icon = obj.icon,
                            cover = obj.cover,
                            properties = obj.properties,
                            customPropertyDefs = obj.customPropertyDefs,
                            isPinned = obj.isPinned,
                        )
                    }
                }
            }

            fun flushUnsavedNow() {
                if (pendingSaveJob?.isActive == true) {
                    pendingSaveJob?.cancel()
                    pendingSaveJob = null
                    latestUnsavedObjectRef[0]?.let { unsaved ->
                        coroutineScope.launch {
                            repository.saveObject(unsaved)
                        }
                    }
                }
            }

            DisposableEffect(objectId) {
                repository.activeEditingObjectId = objectId
                onDispose {
                    if (repository.activeEditingObjectId == objectId) {
                        repository.activeEditingObjectId = null
                    }
                    flushUnsavedNow()
                }
            }

            fun scheduleDebouncedSave(updatedObj: PkmObject) {
                latestUnsavedObjectRef[0] = updatedObj
                pendingSaveJob?.cancel()
                pendingSaveJob = coroutineScope.launch {
                    delay(1500L)
                    repository.saveObject(updatedObj)
                }
            }

            val isWhiteboard = obj.typeId == CanonicalTypeIds.WHITEBOARD

            if (isWhiteboard && activeTab == ObjectDetailTab.CONTENT) {
                WhiteboardScreen(
                    objectId = objectId,
                    repository = repository,
                    onBack = { handleExitObject() },
                    onNavigateToObject = onNavigateToObject,
                    activeTab = activeTab,
                    onTabSelected = { activeTab = it },
                    onOpenOptions = { showBottomSheet = true },
                )
            } else {
                val density = LocalDensity.current
                val imeVisible = WindowInsets.imeAnimationTarget.getBottom(density) > 0
                val focusManager = LocalFocusManager.current
                val keyboardController = LocalSoftwareKeyboardController.current

                val isToolbarVisible = toolbarController.visible &&
                    (imeVisible || toolbarController.forceVisibleWithoutIme || toolbarController.panelType != EditorKeyboardPanelType.NONE)
                val isEditing = imeVisible || toolbarController.visible

                val toolbarHeight = when {
                    !isToolbarVisible -> 0.dp
                    toolbarController.panelType != EditorKeyboardPanelType.NONE -> 48.dp + toolbarController.lastImeHeightDp
                    toolbarController.dynamicRowContent != null -> 92.dp
                    else -> 52.dp
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .imePadding()
                        .padding(bottom = toolbarHeight),
                ) {
                    ObjectDetailTopBar(
                        activeTab = activeTab,
                        onTabSelected = {
                            flushUnsavedNow()
                            activeTab = it
                        },
                        onBack = {
                            flushUnsavedNow()
                            handleExitObject()
                        },
                        onOpenOptions = {
                            flushUnsavedNow()
                            showBottomSheet = true
                        },
                        isEditing = isEditing,
                        onDoneEditing = {
                            flushUnsavedNow()
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            toolbarController.clear()
                        },
                        currentType = currentType,
                        allTypes = allTypes,
                        onSelectObjectType = { newTypeId ->
                            val base = latestUnsavedObjectRef[0] ?: obj
                            latestUnsavedObjectRef[0] = null
                            pendingSaveJob?.cancel()
                            coroutineScope.launch {
                                repository.saveObject(base.copy(typeId = newTypeId))
                            }
                        },
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        when (activeTab) {
                            ObjectDetailTab.CONTENT -> {
                                ObjectContentTab(
                                    obj = obj,
                                    currentType = currentType,
                                    objectAssets = objectAssets,
                                    repository = repository,
                                    toolbarController = toolbarController,
                                    onTitleChange = { newTitle ->
                                        val base = latestUnsavedObjectRef[0] ?: obj
                                        scheduleDebouncedSave(base.copy(title = newTitle.toTextSpan()))
                                    },
                                    onSummaryChange = { newSummary ->
                                        val base = latestUnsavedObjectRef[0] ?: obj
                                        scheduleDebouncedSave(base.copy(summary = newSummary.toTextSpan()))
                                    },
                                    onBlocksChange = { newBlocks ->
                                        val base = latestUnsavedObjectRef[0] ?: obj
                                        scheduleDebouncedSave(base.copy(blocks = newBlocks))
                                    },
                                    onNavigateToObject = onNavigateToObject,
                                    onSelectObjectType = { newTypeId ->
                                        val base = latestUnsavedObjectRef[0] ?: obj
                                        latestUnsavedObjectRef[0] = null
                                        pendingSaveJob?.cancel()
                                        coroutineScope.launch {
                                            repository.saveObject(base.copy(typeId = newTypeId))
                                        }
                                    },
                                )
                            }

                            ObjectDetailTab.PROPERTIES -> {
                                ObjectPropertiesTab(
                                    obj = obj,
                                    currentType = currentType,
                                    propertyDefs = combinedPropertyDefs,
                                    onPropertyChange = { propId, newValue ->
                                        coroutineScope.launch {
                                            val newProps = obj.properties.toMutableMap()
                                            newProps[propId] = newValue
                                            repository.saveObject(obj.copy(properties = newProps))
                                        }
                                    },
                                    onAddCustomProperty = { newDef ->
                                        coroutineScope.launch {
                                            val updatedCustom = (obj.customPropertyDefs + newDef).distinctBy { it.id }
                                            repository.saveObject(obj.copy(customPropertyDefs = updatedCustom))
                                        }
                                    },
                                )
                            }

                            ObjectDetailTab.REFERENCES -> {
                                ObjectReferencesTab(
                                    outgoingObjects = outgoingObjects,
                                    backlinks = backlinks,
                                    attachmentBlocks = attachmentBlocks,
                                    objectAssets = objectAssets,
                                    allActiveObjects = allActiveObjects,
                                    allTypes = allTypes,
                                    onNavigateToObject = onNavigateToObject,
                                )
                            }
                        }
                    }
                }

                // Contextual editing toolbar pinned above the software keyboard. The object type
                // selector lives in the top bar while editing, so the old duplicate type bar
                // (which fought with the IME and caused flicker) is no longer rendered here.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    EditorToolbarRail(
                        controller = toolbarController,
                    )
                }
            }
        }

        BackHandler {
            handleExitObject()
        }

        val currentObj = objState
        if (showBottomSheet && currentObj != null) {
            val currentType: ObjectType? = allTypes.find { type -> type.id == currentObj.typeId }
            val combinedDefs = remember(currentType, currentObj.customPropertyDefs) {
                val base = currentType?.defaultProperties.orEmpty()
                val custom = currentObj.customPropertyDefs
                (base + custom).distinctBy { it.id }
            }

            EditorBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                showDescription = currentObj.summary.isNotEmpty(),
                onToggleDescription = { enabled ->
                    if (!enabled && currentObj.summary.isNotEmpty()) {
                        coroutineScope.launch {
                            repository.saveObject(currentObj.copy(summary = emptyList()))
                        }
                    } else if (enabled && currentObj.summary.isEmpty()) {
                        coroutineScope.launch {
                            repository.saveObject(currentObj.copy(summary = "".toTextSpan()))
                        }
                    }
                },
                currentEmoji = currentObj.icon?.emoji,
                onSelectEmoji = { emoji ->
                    coroutineScope.launch {
                        repository.saveObject(currentObj.copy(icon = ObjectIcon(emoji = emoji)))
                    }
                },
                hasCover = currentObj.cover != null,
                canHaveCover = currentObj.typeId != "image" &&
                    currentObj.typeId != "type_image" &&
                    currentObj.typeId != "audio" &&
                    currentObj.typeId != "type_audio",
                onToggleCover = {
                    coroutineScope.launch {
                        val newCover = if (currentObj.cover == null) CoverSpec(assetId = "cover_gradient") else null
                        repository.saveObject(currentObj.copy(cover = newCover))
                    }
                },
                propertiesCount = combinedDefs.size,
                onOpenProperties = {
                    activeTab = ObjectDetailTab.PROPERTIES
                },
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                onUndo = {
                    if (undoStack.isNotEmpty()) {
                        val prev = undoStack.last()
                        undoStack = undoStack.dropLast(1)
                        redoStack = redoStack + listOf(currentObj.blocks)
                        coroutineScope.launch {
                            repository.saveObject(currentObj.copy(blocks = prev))
                        }
                    }
                },
                onRedo = {
                    if (redoStack.isNotEmpty()) {
                        val next = redoStack.last()
                        redoStack = redoStack.dropLast(1)
                        undoStack = undoStack + listOf(currentObj.blocks)
                        coroutineScope.launch {
                            repository.saveObject(currentObj.copy(blocks = next))
                        }
                    }
                },
                isPinned = currentObj.isPinned,
                onTogglePin = {
                    coroutineScope.launch {
                        repository.saveObject(currentObj.copy(isPinned = !currentObj.isPinned))
                    }
                },
                onMoveToTrash = {
                    coroutineScope.launch {
                        repository.moveToTrash(currentObj.id)
                        onBack()
                    }
                },
                noteImagesRevealed = noteImagesRevealed,
                onToggleNoteImages = if (ImagePrivacy.isPrivate && ImagePrivacy.scope == PrivacyScope.ALL) {
                    { noteImagesRevealed = !noteImagesRevealed }
                } else {
                    null
                },
            )
        }
    }
    }
}

private val INLINE_MENTION_REGEX = Regex("""@\[([^]]+)]|\[\[([^]]+)]]""")

private fun extractOutgoingObjectIds(
    obj: PkmObject,
    allActiveObjects: List<PkmObject>,
): List<String> {
    val ids = mutableSetOf<String>()
    val titleIndex = allActiveObjects.associateBy { it.plainTitle.trim().lowercase() }

    fun resolveByTitle(rawTitle: String): String? {
        val clean = rawTitle.substringAfterLast('/').trim().lowercase()
        return titleIndex[clean]?.id
    }

    obj.properties.forEach { (key, value) ->
        when (value) {
            is PropertyValue.ObjectRelation -> ids.addAll(value.targetObjectIds)
            is PropertyValue.MultiSelect -> {
                if (key == "tags" || key == "prop_tags") {
                    value.selectedOptionIds.forEach { tagIdOrName ->
                        val resolved = allActiveObjects.find {
                            it.id == tagIdOrName || it.plainTitle.equals(tagIdOrName, ignoreCase = true)
                        }?.id ?: tagIdOrName
                        ids.add(resolved)
                    }
                }
            }
            is PropertyValue.RichText -> value.spans.filterIsInstance<InlineSpan.Mention>().forEach {
                ids.add(it.targetObjectId)
            }
            else -> Unit
        }
    }

    fun scanSpans(spans: List<InlineSpan>) {
        spans.forEach { span ->
            when (span) {
                is InlineSpan.Mention -> ids.add(span.targetObjectId)
                is InlineSpan.Text -> {
                    INLINE_MENTION_REGEX.findAll(span.text).forEach { match ->
                        val token = (match.groups[1]?.value ?: match.groups[2]?.value).orEmpty()
                        resolveByTitle(token)?.let { ids.add(it) }
                    }
                }
                else -> Unit
            }
        }
    }

    obj.blocks.forEach { block ->
        when (block) {
            is Block.Paragraph -> scanSpans(block.spans)
            is Block.Heading -> scanSpans(block.spans)
            is Block.Todo -> scanSpans(block.spans)
            is Block.BulletList -> scanSpans(block.spans)
            is Block.NumberedList -> scanSpans(block.spans)
            is Block.AlphabeticList -> scanSpans(block.spans)
            is Block.RomanList -> scanSpans(block.spans)
            is Block.Quote -> scanSpans(block.spans)
            is Block.Callout -> scanSpans(block.spans)
            is Block.Toggle -> scanSpans(block.spans)
            is Block.ObjectEmbed -> ids.add(block.targetObjectId)
            is Block.Audio -> {
                if (block.assetId.isNotBlank()) {
                    allActiveObjects.find {
                        (it.typeId == "audio" || it.typeId == "type_audio") &&
                            resolveAudioAssetId(it) == block.assetId
                    }?.let { ids.add(it.id) }
                }
            }
            is Block.Image -> {
                if (block.assetId.isNotBlank()) {
                    allActiveObjects.find {
                        (it.typeId == "image" || it.typeId == "type_image") &&
                            resolveImageAssetId(it) == block.assetId
                    }?.let { ids.add(it.id) }
                }
            }
            is Block.Canvas -> {
                block.data.nodes.forEach { node ->
                    (node.content as? WhiteboardNodeContent.ObjectCard)?.let { card ->
                        ids.add(card.objectId)
                    }
                }
            }
            else -> Unit
        }
    }

    if (obj.typeId == CanonicalTypeIds.WHITEBOARD) {
        obj.getWhiteboardData().nodes.forEach { node ->
            (node.content as? WhiteboardNodeContent.ObjectCard)?.let { card ->
                ids.add(card.objectId)
            }
        }
    }

    return ids.filter { id -> id.isNotBlank() && (id != obj.id) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObjectContentTab(
    obj: PkmObject,
    currentType: ObjectType?,
    objectAssets: List<AssetMeta> = emptyList(),
    repository: PkmRepository,
    toolbarController: EditorToolbarController,
    onTitleChange: (String) -> Unit,
    onSummaryChange: (String) -> Unit,
    onBlocksChange: (List<Block>) -> Unit,
    onNavigateToObject: (String) -> Unit,
    onSelectObjectType: (String) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val canonicalType = obj.typeId
    val isImageObject = canonicalType == "image" || currentType?.id == "image" || obj.typeId == "type_image"
    val isAudioObject = canonicalType == "audio" || currentType?.id == "audio" || obj.typeId == "type_audio"
    val isWebLinkObject = canonicalType == "web" || currentType?.id == "web" || obj.typeId == "type_weblink"
    val assetId = resolveImageAssetId(obj, objectAssets)
    val audioAssetId = resolveAudioAssetId(obj, objectAssets)
    val imageBitmap = if (!assetId.isNullOrBlank()) rememberDecodedImageForPreview(assetId) else null
    var isZoomOpen by remember { mutableStateOf(false) }
    var showReplaceAudioSheet by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.imeAnimationTarget.getBottom(density) > 0
    var isTitleFocused by remember { mutableStateOf(false) }
    var isDescriptionFocused by remember { mutableStateOf(false) }

    val isEditorFocused = isTitleFocused || isDescriptionFocused || toolbarController.visible
    val isEditing = imeVisible || isEditorFocused

    val heroState = rememberEditorHeroState(isEditing = isEditing)
    val titleBringIntoViewRequester = remember { BringIntoViewRequester() }
    val descriptionBringIntoViewRequester = remember { BringIntoViewRequester() }

    if (showReplaceAudioSheet) {
        CreateAudioObjectSheet(
            initialTitle = obj.plainTitle,
            showTitleField = false,
            onDismiss = { showReplaceAudioSheet = false },
            onAudioReady = { bytes, durationSeconds, mime, fileName, _ ->
                showReplaceAudioSheet = false
                coroutineScope.launch {
                    val newAssetId = UUID.randomUUID().toString()
                    val resolvedFileName = fileName?.takeIf { it.isNotBlank() } ?: "rec_$newAssetId.m4a"
                    val mediaStore = (context.applicationContext as YaopApp).mediaStore
                    withContext(Dispatchers.IO) {
                        mediaStore.saveMedia(newAssetId, bytes, mime)
                    }
                    repository.saveAsset(
                        AssetMeta(
                            id = newAssetId,
                            objectId = obj.id,
                            mediaObjectId = obj.id,
                            name = resolvedFileName,
                            mimeType = mime,
                            sizeBytes = bytes.size.toLong(),
                            durationSeconds = durationSeconds,
                        ),
                    )
                    val updatedProps = obj.properties.toMutableMap()
                    updatedProps["asset_id"] = PropertyValue.RichText(newAssetId.toTextSpan())
                    updatedProps["file_name"] = PropertyValue.RichText(resolvedFileName.toTextSpan())
                    updatedProps["mime"] = PropertyValue.RichText(mime.toTextSpan())
                    updatedProps["size"] = PropertyValue.Number(bytes.size.toDouble())
                    updatedProps["duration"] = PropertyValue.Number(durationSeconds.toDouble())
                    // User-assigned title always wins
                    val hasCustomTitle = obj.plainTitle.isNotBlank() &&
                        obj.plainTitle != "Untitled" &&
                        obj.plainTitle != "Sin título" &&
                        obj.plainTitle != "Título" &&
                        !obj.plainTitle.startsWith("Audio recording (") &&
                        !obj.plainTitle.startsWith("Grabación de audio (")
                    val updatedTitle = if (hasCustomTitle) obj.title else resolvedFileName.toTextSpan()
                    repository.saveObject(obj.copy(properties = updatedProps, title = updatedTitle))
                }
            },
        )
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                if (bytes != null) {
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val newAssetId = UUID.randomUUID().toString()
                    val fileName = uri.lastPathSegment ?: newAssetId
                    val mediaStore = (context.applicationContext as YaopApp).mediaStore
                    withContext(Dispatchers.IO) {
                        mediaStore.saveMedia(newAssetId, bytes, mime)
                    }
                    repository.saveAsset(
                        AssetMeta(
                            id = newAssetId,
                            objectId = obj.id,
                            mediaObjectId = obj.id,
                            name = fileName,
                            mimeType = mime,
                            sizeBytes = bytes.size.toLong(),
                        ),
                    )
                    val updatedProps = obj.properties.toMutableMap()
                    updatedProps["asset_id"] = PropertyValue.RichText(newAssetId.toTextSpan())
                    updatedProps["file_name"] = PropertyValue.RichText(fileName.toTextSpan())
                    updatedProps["mime"] = PropertyValue.RichText(mime.toTextSpan())
                    updatedProps["size"] = PropertyValue.Number(bytes.size.toDouble())
                    // User-assigned title always wins
                    val hasCustomTitle = obj.plainTitle.isNotBlank() &&
                        obj.plainTitle != "Untitled" &&
                        obj.plainTitle != "Título" &&
                        !obj.plainTitle.matches(Regex("""^\d+$"""))
                    val updatedTitle = if (hasCustomTitle) obj.title else fileName.toTextSpan()
                    repository.saveObject(obj.copy(properties = updatedProps, title = updatedTitle))
                }
            }
        }
    }

    if (isZoomOpen && !assetId.isNullOrBlank()) {
        FullScreenImagePreviewDialog(
            assetId = assetId,
            caption = obj.plainTitle,
            onDismiss = { isZoomOpen = false },
        )
    }

    val rawTitle = remember(obj.title) { obj.title.joinToString("") { (it as? InlineSpan.Text)?.text.orEmpty() } }
    var titleText by remember(obj.id) { mutableStateOf(rawTitle) }
    val showDescription = obj.summary.isNotEmpty()
    var summaryText by remember(obj.id) { mutableStateOf(obj.plainSummary) }
    val titleFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var didCheckInitialFocus by remember(obj.id) { mutableStateOf(false) }

    LaunchedEffect(rawTitle) {
        if (!isTitleFocused && rawTitle != titleText) {
            titleText = rawTitle
        }
    }

    LaunchedEffect(obj.plainSummary) {
        if (!isDescriptionFocused && obj.plainSummary != summaryText) {
            summaryText = obj.plainSummary
        }
    }

    // Auto-focus title only when opening a brand-new empty object
    LaunchedEffect(obj.id) {
        if (!didCheckInitialFocus) {
            didCheckInitialFocus = true
            val isNewEmptyObject = rawTitle.isBlank() &&
                (obj.blocks.isEmpty() || (obj.blocks.size == 1 && BlockOperations.extractPlainText(obj.blocks.first()).isBlank()))
            if (isNewEmptyObject) {
                runCatching { titleFocusRequester.requestFocus() }
            }
        }
    }

    BlockListEditor(
        blocks = obj.blocks,
        onBlocksChange = onBlocksChange,
        repository = repository,
        onNavigateToObject = onNavigateToObject,
        onSelectObjectType = onSelectObjectType,
        ownerObjectId = obj.id,
        modifier = Modifier.fillMaxSize(),
        useLazyLayout = true,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 48.dp),
        headerContent = {
            if (canonicalType == "habit") {
                org.yaop.ui.habits.HabitDetailPanel(habit = obj, repository = repository)
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Hero Image preview placed at the very top EXCLUSIVELY for Image objects
            // Adheres to EditorCoverPolicy: automatically collapses during editing to preserve focus
            if (isImageObject) {
                CollapsibleImageHero(
                    heroState = heroState,
                    imageBitmap = imageBitmap,
                    assetId = assetId,
                    title = obj.plainTitle,
                    obj = obj,
                    onZoom = { isZoomOpen = true },
                    onReplace = {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Hero Audio Player placed at the very top EXCLUSIVELY for Audio objects
            if (isAudioObject) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AppColors.Surface, RoundedCornerShape(16.dp))
                        .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(16.dp)),
                ) {
                    if (!audioAssetId.isNullOrBlank()) {
                        val audioDuration = extractAudioDurationSeconds(obj)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AudioPlayerRow(
                                assetId = audioAssetId,
                                fallbackDurationSeconds = audioDuration,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val mimeShort = formatAudioMimeShort(extractImageMime(obj)) ?: "M4A"
                                val fileSize = formatFileSize(extractImageSize(obj))
                                val metaText = listOfNotNull(
                                    formatDurationSeconds(audioDuration),
                                    mimeShort,
                                    fileSize,
                                ).joinToString(" • ")

                                Text(
                                    text = metaText,
                                    fontSize = 11.sp,
                                    color = AppColors.TextTertiary,
                                    maxLines = 1,
                                )

                                Text(
                                    text = stringResource(R.string.audio_replace),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppColors.Accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { showReplaceAudioSheet = true }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showReplaceAudioSheet = true }
                                .padding(vertical = 32.dp, horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.AccentSubtle),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = AppColors.Accent,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Text(
                                text = stringResource(R.string.audio_tap_to_record_or_upload),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppColors.TextPrimary,
                            )
                            Text(
                                text = stringResource(R.string.audio_add_first),
                                fontSize = 12.sp,
                                color = AppColors.Accent,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
            }

            // Hero WebLink preview placed at the very top EXCLUSIVELY for WebLink objects
            if (isWebLinkObject) {
                WebLinkHeroCard(
                    obj = obj,
                    onObjectUpdated = { updated ->
                        coroutineScope.launch {
                            repository.saveObject(updated)
                        }
                    },
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Notes/Books/Tasks/etc. only render cover if explicitly set, never image, audio, or weblink objects
            // Adheres to EditorCoverPolicy: automatically collapses to compact 36dp strip during editing
            if (!isImageObject && !isAudioObject && !isWebLinkObject && obj.cover != null) {
                val coverAssetId = obj.cover.assetId.takeIf { !it.startsWith("cover_") }
                CollapsibleCoverHero(
                    heroState = heroState,
                    coverAssetId = coverAssetId,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (currentType != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeBadge(type = currentType)
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!obj.icon?.emoji.isNullOrBlank()) {
                    // Icon tile: translucent surface, rounded corners; title beside it.
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(AppColors.Surface, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(text = obj.icon.emoji, fontSize = 28.sp) }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                BasicTextField(
                    value = titleText,
                    onValueChange = { next ->
                        if (next.contains('\n')) {
                            val cleaned = next.replace("\n", "")
                            titleText = cleaned
                            onTitleChange(cleaned)
                            focusManager.moveFocus(FocusDirection.Down)
                        } else {
                            titleText = next
                            onTitleChange(next)
                        }
                    },
                    textStyle = TextStyle(
                        fontFamily = org.yaop.ui.theme.AppFonts.InterTight,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextPrimary,
                        letterSpacing = (-0.4).sp,
                    ),
                    cursorBrush = SolidColor(AppColors.Accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(titleFocusRequester)
                        .bringIntoViewRequester(titleBringIntoViewRequester)
                        .onFocusChanged { focusState ->
                            isTitleFocused = focusState.isFocused
                            if (focusState.isFocused) {
                                coroutineScope.launch {
                                    delay(120L)
                                    runCatching { titleBringIntoViewRequester.bringIntoView() }
                                }
                            }
                        },
                    decorationBox = { inner ->
                        if (titleText.isEmpty()) {
                            Text(
                                text = stringResource(R.string.title_placeholder),
                                fontFamily = org.yaop.ui.theme.AppFonts.InterTight,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.TextTertiary,
                                letterSpacing = (-0.5).sp,
                            )
                        }
                        inner()
                    },
                )
            }

            if (showDescription) {
                Spacer(modifier = Modifier.height(8.dp))
                BasicTextField(
                    value = summaryText,
                    onValueChange = {
                        summaryText = it
                        onSummaryChange(it)
                        coroutineScope.launch {
                            runCatching { descriptionBringIntoViewRequester.bringIntoView() }
                        }
                    },
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        color = AppColors.TextSecondary,
                    ),
                    cursorBrush = SolidColor(AppColors.Accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(descriptionBringIntoViewRequester)
                        .onFocusChanged { focusState ->
                            isDescriptionFocused = focusState.isFocused
                            if (focusState.isFocused) {
                                coroutineScope.launch {
                                    delay(120L)
                                    runCatching { descriptionBringIntoViewRequester.bringIntoView() }
                                }
                            }
                        },
                    decorationBox = { inner ->
                        if (summaryText.isEmpty()) {
                            Text(
                                text = stringResource(R.string.description_placeholder),
                                fontSize = 15.sp,
                                color = AppColors.TextTertiary,
                            )
                        }
                        inner()
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        },
    )
}

@Composable
private fun ObjectPropertiesTab(
    obj: PkmObject,
    currentType: ObjectType?,
    propertyDefs: List<PropertyDefinition>,
    onPropertyChange: (String, PropertyValue) -> Unit,
    onAddCustomProperty: (PropertyDefinition) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 40.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!obj.icon?.emoji.isNullOrBlank()) {
                Text(text = obj.icon.emoji, fontSize = 24.sp)
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = obj.plainTitle.ifBlank { stringResource(R.string.title_placeholder) },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                )
                if (currentType != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    TypeBadge(type = currentType)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        PropertiesSection(
            propertyDefs = propertyDefs,
            properties = obj.properties,
            onPropertyChange = onPropertyChange,
            onAddCustomProperty = onAddCustomProperty,
        )

        Spacer(modifier = Modifier.height(60.dp))
    }
}

@Composable
private fun ObjectReferencesTab(
    outgoingObjects: List<PkmObject>,
    backlinks: List<PkmObject>,
    attachmentBlocks: List<Block>,
    objectAssets: List<AssetMeta>,
    allActiveObjects: List<PkmObject>,
    allTypes: List<ObjectType>,
    onNavigateToObject: (String) -> Unit,
) {
    val assetsById = remember(objectAssets) { objectAssets.associateBy { it.id } }
    val imageFallbackLabel = stringResource(R.string.block_type_image)
    val audioFallbackLabel = stringResource(R.string.block_type_audio)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 40.dp),
    ) {
        // 1. Outgoing references
        Text(
            text = stringResource(R.string.section_outgoing_references, outgoingObjects.size),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextSecondary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (outgoingObjects.isEmpty()) {
            Text(
                text = stringResource(R.string.no_outgoing_references),
                fontSize = 13.sp,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                outgoingObjects.forEach { outObj ->
                    val outType = allTypes.find { type -> type.id == outObj.typeId }
                    ObjectCard(
                        obj = outObj,
                        type = outType,
                        onClick = { onNavigateToObject(outObj.id) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 2. Incoming references / Backlinks
        Text(
            text = stringResource(R.string.section_incoming_references, backlinks.size),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextSecondary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (backlinks.isEmpty()) {
            Text(
                text = stringResource(R.string.no_incoming_references),
                fontSize = 13.sp,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                backlinks.forEach { referringObj ->
                    val refType = allTypes.find { type -> type.id == referringObj.typeId }
                    ObjectCard(
                        obj = referringObj,
                        type = refType,
                        onClick = { onNavigateToObject(referringObj.id) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Attachments / Media
        Text(
            text = stringResource(R.string.section_attachments, attachmentBlocks.size),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextSecondary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (attachmentBlocks.isEmpty()) {
            Text(
                text = stringResource(R.string.no_attachments),
                fontSize = 13.sp,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                attachmentBlocks.forEach { attBlock ->
                    val linkedMediaObj = when (attBlock) {
                        is Block.Image -> allActiveObjects.find {
                            (it.typeId == "image" || it.typeId == "type_image") &&
                                resolveImageAssetId(it) == attBlock.assetId
                        }
                        is Block.Audio -> allActiveObjects.find {
                            (it.typeId == "audio" || it.typeId == "type_audio") &&
                                resolveAudioAssetId(it) == attBlock.assetId
                        }
                        else -> null
                    }
                    val (iconEmoji, labelText) = when (attBlock) {
                        is Block.Image -> {
                            val assetName = linkedMediaObj?.plainTitle?.takeIf { it.isNotBlank() }
                                ?: assetsById[attBlock.assetId]?.name
                                ?: attBlock.caption.toPlainText().takeIf { it.isNotBlank() }
                                ?: imageFallbackLabel
                            "🖼️" to assetName
                        }
                        is Block.Audio -> {
                            val assetName = linkedMediaObj?.plainTitle?.takeIf { it.isNotBlank() }
                                ?: assetsById[attBlock.assetId]?.name
                                ?: audioFallbackLabel
                            "🎙️" to "$assetName (${formatDurationSeconds(attBlock.durationSeconds)})"
                        }
                        is Block.Pdf -> "📄" to attBlock.fileName
                        else -> "📎" to attBlock.id
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppColors.Surface, RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp))
                            .then(
                                if (linkedMediaObj != null) {
                                    Modifier.clickable { onNavigateToObject(linkedMediaObj.id) }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = iconEmoji, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = labelText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.TextPrimary,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(60.dp))
    }
}

@Composable
private fun ObjectDetailTopBar(
    activeTab: ObjectDetailTab,
    onTabSelected: (ObjectDetailTab) -> Unit,
    onBack: () -> Unit,
    onOpenOptions: () -> Unit,
    isEditing: Boolean = false,
    onDoneEditing: (() -> Unit)? = null,
    currentType: ObjectType? = null,
    allTypes: List<ObjectType> = emptyList(),
    onSelectObjectType: ((String) -> Unit)? = null,
) {
    var showTypeDropdown by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(AppColors.Surface, CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = AppColors.TextPrimary,
                modifier = Modifier.size(20.dp),
            )
        }

        if (isEditing && currentType != null) {
            val canonicalId = currentType.id
            val typeEmoji = when (canonicalId) {
                DefaultTypes.NOTE.id -> "📝"
                DefaultTypes.TASK.id -> "✅"
                DefaultTypes.DAILY.id -> "📅"
                DefaultTypes.BOOK.id -> "📚"
                DefaultTypes.PERSON.id -> "👤"
                DefaultTypes.PROJECT.id -> "🔨"
                DefaultTypes.WEBLINK.id -> "🔖"
                CanonicalTypeIds.WHITEBOARD -> "🎨"
                "image" -> "🖼️"
                "audio" -> "🎙️"
                "pdf" -> "📄"
                else -> "📄"
            }

            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(AppColors.Surface, RoundedCornerShape(20.dp))
                        .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(20.dp))
                        .clickable(enabled = onSelectObjectType != null) { showTypeDropdown = true }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = typeEmoji, fontSize = 13.sp)
                    Text(
                        text = currentType.localizedName(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextPrimary,
                    )
                    if (onSelectObjectType != null) {
                        Text(text = "▾", fontSize = 11.sp, color = AppColors.TextSecondary)
                    }
                }

                DropdownMenu(
                    expanded = showTypeDropdown,
                    onDismissRequest = { showTypeDropdown = false },
                    modifier = Modifier.background(AppColors.SurfaceElevated),
                ) {
                    allTypes.forEach { type ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val emoji = when (type.id) {
                                        DefaultTypes.NOTE.id -> "📝"
                                        DefaultTypes.TASK.id -> "✅"
                                        DefaultTypes.DAILY.id -> "📅"
                                        DefaultTypes.BOOK.id -> "📚"
                                        DefaultTypes.PERSON.id -> "👤"
                                        DefaultTypes.PROJECT.id -> "🔨"
                                        DefaultTypes.WEBLINK.id -> "🔖"
                                        CanonicalTypeIds.WHITEBOARD -> "🎨"
                                        "image" -> "🖼️"
                                        "audio" -> "🎙️"
                                        "pdf" -> "📄"
                                        else -> "📄"
                                    }
                                    Text(text = emoji, fontSize = 14.sp)
                                    Text(text = type.localizedName(), color = AppColors.TextPrimary)
                                }
                            },
                            onClick = {
                                showTypeDropdown = false
                                onSelectObjectType?.invoke(type.id)
                            },
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(AppColors.Surface, RoundedCornerShape(24.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(24.dp))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ObjectPillTab(
                    title = stringResource(R.string.tab_content),
                    icon = Icons.AutoMirrored.Filled.Notes,
                    isSelected = activeTab == ObjectDetailTab.CONTENT,
                    onClick = { onTabSelected(ObjectDetailTab.CONTENT) },
                )
                ObjectPillTab(
                    title = stringResource(R.string.tab_properties),
                    icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                    isSelected = activeTab == ObjectDetailTab.PROPERTIES,
                    onClick = { onTabSelected(ObjectDetailTab.PROPERTIES) },
                )
                ObjectPillTab(
                    title = stringResource(R.string.tab_references),
                    icon = Icons.Default.Link,
                    isSelected = activeTab == ObjectDetailTab.REFERENCES,
                    onClick = { onTabSelected(ObjectDetailTab.REFERENCES) },
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(AppColors.Surface, CircleShape)
                    .clickable(onClick = onOpenOptions),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.MoreHoriz,
                    contentDescription = stringResource(R.string.options),
                    tint = AppColors.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (isEditing && onDoneEditing != null) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(AppColors.Ice, CircleShape)
                        .clickable(onClick = onDoneEditing),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.action_done),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ObjectPillTab(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
