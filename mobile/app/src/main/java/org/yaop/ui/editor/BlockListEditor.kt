package org.yaop.ui.editor

import org.yaop.ui.theme.AppMotion
import androidx.compose.animation.core.tween
import org.yaop.ui.common.privacyImage
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.YaopApp
import org.yaop.R
import org.yaop.core.model.AssetMeta
import org.yaop.core.model.Block
import org.yaop.core.model.BlockTypeKeys
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.HeadingLevel
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.ObjectIcon
import org.yaop.core.model.ObjectType
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.RichSpanEngine
import org.yaop.core.model.refreshMentionLabels
import org.yaop.core.model.SpanColorPalette
import org.yaop.core.model.SpannedBlock
import org.yaop.core.model.toPlainText
import org.yaop.core.model.toTextSpan
import org.yaop.core.model.toVisibleString
import org.yaop.core.repository.PkmRepository
import org.yaop.core.sync.SyncContractCodec
import org.yaop.ui.common.createNewAudioObjectFromBytes
import org.yaop.ui.common.createNewImageObjectFromUri
import org.yaop.ui.common.extractUrlFromText
import org.yaop.ui.common.extractAudioDurationFromBytes
import org.yaop.ui.common.extractAudioDurationSeconds
import org.yaop.ui.common.rememberDecodedThumbnail
import org.yaop.ui.common.resolveAudioAssetId
import org.yaop.ui.common.resolveImageAssetId
import org.yaop.ui.common.resolvePdfAssetId
import org.yaop.ui.editor.CreateWebLinkSheet
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val BLOCK_TAG_REGEX = Regex("""(?<=^|[\s(\[])#([a-zA-Z0-9_\u0080-\uFFFF]+)""")
/** `@` suggestion bar limits: a short list that narrows while typing (full list in the `⌄` sheet). */
private const val MENTION_MAX_TYPES = 6
private const val MENTION_MAX_OBJECTS = 10

private val LIVE_MENTION_QUERY_REGEX = Regex("""(?<=^|[\s(\[])@([a-zA-Z0-9_/\-.#\u0080-\uFFFF ]*)$""")
private val LIVE_TAG_QUERY_REGEX = Regex("""(?<=^|[\s(\[])#([a-zA-Z0-9_\u0080-\uFFFF]*)$""")

/**
 * Scans every block once for structured [InlineSpan.Tag] nodes and inline `#tag` tokens.
 * Kept outside the composable so it can run on [Dispatchers.Default] without touching Compose state.
 */
private fun computeInferredBlockTagNames(
    blocks: List<Block>,
    directTagNames: List<String>,
): List<String> {
    val hasAnyHash = blocks.any { block ->
        val spans = BlockOperations.extractSpans(block)
        spans.any { it is InlineSpan.Tag || (it is InlineSpan.Text && it.text.contains('#')) }
    }
    if (!hasAnyHash) return emptyList()

    val directLower = directTagNames.map { it.lowercase() }.toSet()
    val found = mutableListOf<String>()
    fun addTagCandidate(rawName: String) {
        val name = rawName.removePrefix("#").trim()
        if (name.isNotBlank() &&
            name.lowercase() !in directLower &&
            found.none { it.equals(name, ignoreCase = true) }
        ) {
            found.add(name)
        }
    }
    blocks.forEach { block ->
        val spans = BlockOperations.extractSpans(block)
        spans.forEach { span ->
            when (span) {
                is InlineSpan.Tag -> addTagCandidate(span.tagName)
                is InlineSpan.Text -> {
                    if (span.text.contains('#')) {
                        BLOCK_TAG_REGEX.findAll(span.text).forEach { match ->
                            addTagCandidate(match.groupValues[1])
                        }
                    }
                }
                else -> Unit
            }
        }
    }
    return found
}

/**
 * Modular block-based WYSIWYG editor for YAOP.
 * Implements structured [InlineSpan] runs, flat `indentLevel` nesting for `Toggle` and `Group`,
 * Dynamic Island single-row toolbar modes (Pattern A), Keyboard-Replacement Panels (Pattern C),
 * Universal Object/Link Selector Sheet (Pattern E), and Compact Preview Popups on tap (Pattern F).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BlockListEditor(
    blocks: List<Block>,
    onBlocksChange: (List<Block>) -> Unit,
    modifier: Modifier = Modifier,
    repository: PkmRepository? = null,
    onNavigateToObject: ((String) -> Unit)? = null,
    onSelectObjectType: ((String) -> Unit)? = null,
    ownerObjectId: String? = null,
    onNavigateToDate: ((LocalDate) -> Unit)? = null,
    useLazyLayout: Boolean = false,
    headerContent: (@Composable () -> Unit)? = null,
    lazyListState: LazyListState? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var focusedBlockId by remember { mutableStateOf<String?>(null) }
    var targetFocusBlockId by remember { mutableStateOf<String?>(null) }
    var targetCursorOffset by remember { mutableStateOf<Int?>(null) }
    var focusedSelection by remember { mutableStateOf<TextRange?>(null) }
    var activeTypingFormats by remember { mutableStateOf<Set<String>?>(null) }
    var activeTextColor by remember { mutableStateOf<String?>(null) }
    var activeBgColor by remember { mutableStateOf<String?>(null) }
    var hasExplicitTextColor by remember { mutableStateOf(false) }
    var hasExplicitBgColor by remember { mutableStateOf(false) }
    var isBgColorSubmode by remember { mutableStateOf(false) }
    var highlightedBlockId by remember { mutableStateOf<String?>(null) }
    var activeTableColumn by remember { mutableIntStateOf(0) }
    // Forces the focused BasicTextField to accept a text rewrite made by the editor itself
    // (Enter split, undo, redo) instead of treating it as a stale parent snapshot.
    var textSyncRevision by remember { mutableIntStateOf(0) }

    // Dynamic Island toolbar mode & Pattern C keyboard-slot panel state
    var explicitToolbarMode by remember { mutableStateOf<EditorToolbarMode?>(null) }
    var activeKeyboardPanel by remember { mutableStateOf(EditorKeyboardPanelType.NONE) }
    var slashFilterQuery by remember { mutableStateOf("") }
    var liveMentionQuery by remember { mutableStateOf<String?>(null) }
    var liveTagQuery by remember { mutableStateOf<String?>(null) }
    var pastedUrlCandidate by remember { mutableStateOf<String?>(null) }

    // Multi-block selection state & undoable delete notice
    val selectedBlockIds = remember { mutableStateSetOf<String>() }
    var deletedBackupBlocks by remember { mutableStateOf<List<Block>?>(null) }

    // Burst-grouped Undo / Redo stacks (~800ms window for typing)
    val undoStack = remember { mutableStateListOf<List<Block>>() }
    val redoStack = remember { mutableStateListOf<List<Block>>() }
    var lastUndoSnapshotMs by remember { mutableLongStateOf(0L) }

    // Pattern E & F sheets/popups
    var showSlashSheet by remember { mutableStateOf(value = false) }
    var showUniversalSelectorSheet by remember { mutableStateOf(value = false) }
    var isSelectorLinkMode by remember { mutableStateOf(value = false) }
    var selectorInitialQuery by remember { mutableStateOf("") }
    var selectorInitialTypeId by remember { mutableStateOf<String?>(null) }
    var showTagSheet by remember { mutableStateOf(value = false) }
    var isHeaderTagPicker by remember { mutableStateOf(value = false) }
    var tagInitialQuery by remember { mutableStateOf("") }
    var activeInlinePreview by remember { mutableStateOf<InlineElementPreviewData?>(null) }
    var fullScreenPreviewImage by remember { mutableStateOf<Block.Image?>(null) }
    var activeInlineCanvasBlock by remember { mutableStateOf<Block.Canvas?>(null) }
    var pendingMentionMediaTitle by remember { mutableStateOf<String?>(null) }
    var showMentionAudioSheet by remember { mutableStateOf(false) }
    var showMentionWebLinkSheet by remember { mutableStateOf(false) }

    var pendingMediaBlockId by remember { mutableStateOf<String?>(null) }
    val importedAssetIds = remember { mutableStateSetOf<String>() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val appContext = context.applicationContext as YaopApp
    val mediaStore = appContext.mediaStore
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboard.current

    val allActiveObjects by (
        repository?.getAllActiveObjects()?.collectAsState(initial = repository.getCachedAllActiveObjects())
            ?: remember { mutableStateOf(emptyList()) }
        )
    val allTypes by (
        repository?.getAllTypes()?.collectAsState(initial = repository.getCachedTypes().ifEmpty { DefaultTypes.ALL })
            ?: remember { mutableStateOf(DefaultTypes.ALL) }
        )

    val ownerObject = remember(ownerObjectId, allActiveObjects) {
        ownerObjectId?.let { id -> allActiveObjects.find { it.id == id } }
    }

    val allTags = remember(allActiveObjects) {
        allActiveObjects.filter { obj -> obj.typeId == DefaultTypes.TAG.id }
    }

    val tagColors = remember(allTags) {
        buildMap {
            for (tag in allTags) {
                val hex = tag.icon?.colorHex
                if (!hex.isNullOrBlank()) {
                    val key = tag.plainTitle.trim().lowercase()
                    if (key.isNotEmpty()) {
                        put(key, hex)
                    }
                }
            }
        }
    }

    var pendingBlocksChangeJob by remember { mutableStateOf<Job?>(null) }
    var pendingLocalSyncJob by remember { mutableStateOf<Job?>(null) }
    var currentBlocks by remember(ownerObjectId) {
        mutableStateOf(
            blocks.ifEmpty {
                listOf(Block.Paragraph(UUID.randomUUID().toString(), emptyList()))
            },
        )
    }
    // Non-recomposing live references so typing bursts don't recompose the entire BlockListEditor on every keystroke
    val liveBlocksRef = remember(ownerObjectId) { arrayOf(currentBlocks) }
    val liveSelectionRef = remember(ownerObjectId) { arrayOf<TextRange?>(null) }

    fun flushPendingLocalTyping(): List<Block> {
        if (pendingLocalSyncJob?.isActive == true) {
            pendingLocalSyncJob?.cancel()
            pendingLocalSyncJob = null
        }
        val latest = liveBlocksRef[0]
        if (currentBlocks != latest) {
            currentBlocks = latest
        }
        val latestSel = liveSelectionRef[0]
        if (latestSel != null && focusedSelection != latestSel) {
            focusedSelection = latestSel
        }
        return latest
    }

    LaunchedEffect(blocks) {
        if (
            blocks.isNotEmpty() &&
            blocks != liveBlocksRef[0] &&
            pendingBlocksChangeJob?.isActive != true &&
            pendingLocalSyncJob?.isActive != true
        ) {
            liveBlocksRef[0] = blocks
            currentBlocks = blocks
        }
    }

    DisposableEffect(ownerObjectId) {
        onDispose {
            pendingLocalSyncJob?.cancel()
            if (pendingBlocksChangeJob?.isActive == true) {
                pendingBlocksChangeJob?.cancel()
                onBlocksChange(liveBlocksRef[0])
            }
        }
    }

    val visibleBlockItems = remember(currentBlocks) {
        BlockOperations.computeVisibleBlocks(currentBlocks)
    }

    // Lazy layout state is hoisted so focus requests can scroll off-screen target blocks into view
    // before their item is composed (otherwise the per-item focus effect would never run).
    val listState = lazyListState ?: rememberLazyListState()
    LaunchedEffect(targetFocusBlockId, useLazyLayout) {
        val targetId = targetFocusBlockId ?: return@LaunchedEffect
        if (useLazyLayout) {
            val index = visibleBlockItems.indexOfFirst { it.block.id == targetId }
            if (index >= 0) {
                val headerOffset = (if (headerContent != null) 1 else 0) +
                    (if (ownerObjectId != null) 1 else 0)
                listState.animateScrollToItem(index + headerOffset)
            }
        }
    }

    // Auto-clear 4-second delete notice
    LaunchedEffect(deletedBackupBlocks) {
        if (deletedBackupBlocks != null) {
            delay(4000L)
            deletedBackupBlocks = null
            if (explicitToolbarMode == EditorToolbarMode.NOTICE) {
                explicitToolbarMode = null
            }
        }
    }

    // 1. Direct (own) tags assigned to the object via properties["tags"]
    val directTagNames = remember(ownerObject, allTags) {
        val rawIdsOrNames = (
            (ownerObject?.properties?.get("tags") ?: ownerObject?.properties?.get("prop_tags"))
                as? PropertyValue.MultiSelect
            )?.selectedOptionIds.orEmpty()
        rawIdsOrNames.mapNotNull { idOrName ->
            val tagObj = allTags.find {
                it.id == idOrName || it.plainTitle.equals(idOrName, ignoreCase = true)
            }
            (tagObj?.plainTitle ?: idOrName).trim().takeIf { it.isNotBlank() }
        }.distinctBy { it.lowercase() }
    }

    // 2. Inferred tags from all blocks inside this object (both structured InlineSpan.Tag and #tag text).
    // Computed off the main thread and debounced so typing bursts never scan every block on the UI thread.
    val inferredBlockTagNames by produceState(
        initialValue = emptyList<String>(),
        currentBlocks,
        directTagNames,
    ) {
        value = withContext(Dispatchers.Default) {
            delay(250L)
            computeInferredBlockTagNames(currentBlocks, directTagNames)
        }
    }

    /**
     * Commits block updates, renumbering list items and recording a burst-grouped Undo snapshot.
     * When [deferUiRecomposition] is true (normal plain-text keystrokes), updates [liveBlocksRef]
     * immediately in memory and batches the Compose state update (`currentBlocks`) by 180ms so typing
     * never triggers full-editor or toolbar recomposition on every character.
     */
    fun commitBlocks(
        updated: List<Block>,
        isStructural: Boolean = false,
        deferUiRecomposition: Boolean = false,
    ) {
        val previousBlocks = liveBlocksRef[0]
        val now = System.currentTimeMillis()
        if (isStructural || (now - lastUndoSnapshotMs) > 800L) {
            if (undoStack.lastOrNull() != previousBlocks) {
                undoStack.add(previousBlocks)
                if (undoStack.size > 50) {
                    undoStack.removeAt(0)
                }
            }
            lastUndoSnapshotMs = now
        }
        redoStack.clear()
        val safeUpdated = updated.ifEmpty {
            listOf(Block.Paragraph(UUID.randomUUID().toString(), emptyList()))
        }
        val finalBlocks = if (isStructural) {
            // Renumbering is only required when the list structure changes (insert/delete/move/type
            // conversion). Plain text edits never alter list numbering, so skip the O(blocks) pass
            // on every keystroke even when the document contains numbered lists.
            BlockOperations.renumberLists(safeUpdated)
        } else {
            safeUpdated
        }
        liveBlocksRef[0] = finalBlocks
        if (isStructural || !deferUiRecomposition) {
            pendingLocalSyncJob?.cancel()
            pendingLocalSyncJob = null
            currentBlocks = finalBlocks
        } else {
            pendingLocalSyncJob?.cancel()
            pendingLocalSyncJob = coroutineScope.launch {
                delay(180L)
                val latest = liveBlocksRef[0]
                if (currentBlocks != latest) {
                    currentBlocks = latest
                }
                val latestSel = liveSelectionRef[0]
                if (latestSel != null && focusedSelection != latestSel) {
                    focusedSelection = latestSel
                }
            }
        }
        if (isStructural) {
            pendingBlocksChangeJob?.cancel()
            onBlocksChange(finalBlocks)
        } else {
            pendingBlocksChangeJob?.cancel()
            pendingBlocksChangeJob = coroutineScope.launch {
                delay(1200L)
                onBlocksChange(liveBlocksRef[0])
            }
        }
    }

    /**
     * Undo/redo replaces the whole block list while the keyboard is still open. The focused
     * BasicTextField keeps its own local value, so we explicitly request the restored text to be
     * applied and place the caret at the end of that block. Without this the field would keep
     * showing the pre-undo text until focus changes.
     */
    fun syncFocusedTextAfterHistoryChange(restoredBlocks: List<Block>) {
        val focusedId = focusedBlockId ?: return
        val restored = restoredBlocks.find { it.id == focusedId }
        if (restored != null) {
            textSyncRevision++
            targetCursorOffset = BlockOperations.extractPlainText(restored).length
        } else {
            focusedBlockId = null
        }
    }

    fun performUndo() {
        flushPendingLocalTyping()
        if (undoStack.isNotEmpty()) {
            val prev = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(liveBlocksRef[0])
            val renumbered = BlockOperations.renumberLists(prev)
            liveBlocksRef[0] = renumbered
            currentBlocks = renumbered
            onBlocksChange(renumbered)
            syncFocusedTextAfterHistoryChange(renumbered)
        }
    }

    fun performRedo() {
        flushPendingLocalTyping()
        if (redoStack.isNotEmpty()) {
            val next = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(liveBlocksRef[0])
            val renumbered = BlockOperations.renumberLists(next)
            liveBlocksRef[0] = renumbered
            currentBlocks = renumbered
            onBlocksChange(renumbered)
            syncFocusedTextAfterHistoryChange(renumbered)
        }
    }

    fun toggleObjectDirectTag(tagName: String, colorHex: String) {
        val targetOwner = ownerObject ?: return
        coroutineScope.launch {
            val tagObj = repository?.getOrCreateTagObject(tagName, colorHex)
            val currentList = (
                (targetOwner.properties["tags"] ?: targetOwner.properties["prop_tags"])
                    as? PropertyValue.MultiSelect
                )?.selectedOptionIds.orEmpty()
            val isCurrentlySelected = currentList.any { entry ->
                entry.equals(tagName, ignoreCase = true) ||
                    (tagObj != null && entry == tagObj.id) ||
                    allTags.any { it.id == entry && it.plainTitle.equals(tagName, ignoreCase = true) }
            }
            val updatedList = if (isCurrentlySelected) {
                currentList.filterNot { entry ->
                    entry.equals(tagName, ignoreCase = true) ||
                        (tagObj != null && entry == tagObj.id) ||
                        allTags.any { it.id == entry && it.plainTitle.equals(tagName, ignoreCase = true) }
                }
            } else {
                currentList + tagName
            }
            val newProps = targetOwner.properties.toMutableMap()
            newProps.remove("prop_tags")
            newProps["tags"] = PropertyValue.MultiSelect(updatedList.distinct())
            repository?.saveObject(targetOwner.copy(properties = newProps))
        }
    }

    fun navigateToTagByName(tagName: String) {
        val cleanName = tagName.removePrefix("#").trim()
        if (cleanName.isBlank()) return
        val existing = allTags.find {
            it.plainTitle.equals(cleanName, ignoreCase = true) ||
                it.plainTitle.replace(" ", "_").equals(cleanName, ignoreCase = true)
        }
        if (existing != null) {
            onNavigateToObject?.invoke(existing.id)
        } else if (repository != null && onNavigateToObject != null) {
            coroutineScope.launch {
                val created = repository.getOrCreateTagObject(
                    cleanName,
                    TagPalette.getColorHex(cleanName.hashCode()),
                )
                onNavigateToObject(created.id)
            }
        }
    }

    fun removeBlockById(blockId: String?) {
        if (blockId == null) return
        commitBlocks(currentBlocks.filterNot { it.id == blockId }, isStructural = true)
    }

    fun importMediaIntoBlock(blockId: String, uri: Uri, fallbackMime: String) {
        coroutineScope.launch {
            val block = currentBlocks.find { it.id == blockId }
            val assetId = when (block) {
                is Block.Image -> block.assetId
                is Block.Audio -> block.assetId
                is Block.Pdf -> block.assetId
                else -> null
            }
            if (assetId == null) return@launch
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null) {
                removeBlockById(blockId)
                return@launch
            }
            val mime = appContext.contentResolver.getType(uri) ?: fallbackMime
            withContext(Dispatchers.IO) { mediaStore.saveMedia(assetId, bytes, mime) }
            importedAssetIds.add(assetId)
            val audioDuration = if (block is Block.Audio || mime.startsWith("audio/")) {
                withContext(Dispatchers.IO) {
                    extractAudioDurationFromBytes(appContext, bytes, fallbackSeconds = 1)
                }
            } else {
                null
            }
            repository?.saveAsset(
                AssetMeta(
                    id = assetId,
                    objectId = ownerObjectId,
                    name = uri.lastPathSegment ?: assetId,
                    mimeType = mime,
                    sizeBytes = bytes.size.toLong(),
                    durationSeconds = audioDuration,
                ),
            )
            if (block is Block.Audio && audioDuration != null) {
                val idx = currentBlocks.indexOfFirst { it.id == blockId }
                if (idx != -1) {
                    val updated = currentBlocks.toMutableList()
                    updated[idx] = block.copy(durationSeconds = audioDuration)
                    commitBlocks(updated, isStructural = true)
                }
            } else if (block is Block.Pdf) {
                val pageCount = readPdfPageCount(appContext, bytes) ?: 1
                val idx = currentBlocks.indexOfFirst { it.id == blockId }
                if (idx != -1) {
                    val updated = currentBlocks.toMutableList()
                    updated[idx] = block.copy(pageCount = pageCount)
                    commitBlocks(updated, isStructural = true)
                }
            }
        }
    }

    fun onAudioRecordedIntoBlock(blockId: String, bytes: ByteArray, durationSeconds: Int) {
        coroutineScope.launch {
            val idx = currentBlocks.indexOfFirst { it.id == blockId }
            if (idx == -1) return@launch
            val assetId = UUID.randomUUID().toString()
            withContext(Dispatchers.IO) { mediaStore.saveMedia(assetId, bytes, "audio/mp4") }
            importedAssetIds.add(assetId)
            val localizedRecordingName = appContext.getString(
                R.string.media_audio_recording_name,
                formatDurationSeconds(durationSeconds),
            )
            repository?.saveAsset(
                AssetMeta(
                    id = assetId,
                    objectId = ownerObjectId,
                    name = localizedRecordingName,
                    mimeType = "audio/mp4",
                    sizeBytes = bytes.size.toLong(),
                    durationSeconds = durationSeconds,
                ),
            )
            val block = currentBlocks[idx]
            if (block is Block.Audio) {
                val updated = currentBlocks.toMutableList()
                updated[idx] = block.copy(assetId = assetId, durationSeconds = durationSeconds)
                commitBlocks(updated, isStructural = true)
            }
        }
    }

    fun insertRecordedAudioPanel(bytes: ByteArray, durationSeconds: Int) {
        coroutineScope.launch {
            val assetId = UUID.randomUUID().toString()
            withContext(Dispatchers.IO) { mediaStore.saveMedia(assetId, bytes, "audio/mp4") }
            importedAssetIds.add(assetId)
            val localizedRecordingName = appContext.getString(
                R.string.media_audio_recording_name,
                formatDurationSeconds(durationSeconds),
            )
            repository?.saveAsset(
                AssetMeta(
                    id = assetId,
                    objectId = ownerObjectId,
                    name = localizedRecordingName,
                    mimeType = "audio/mp4",
                    sizeBytes = bytes.size.toLong(),
                    durationSeconds = durationSeconds,
                ),
            )
            val focusIdx = focusedBlockId?.let { id -> currentBlocks.indexOfFirst { it.id == id } }
            val audioBlock = Block.Audio(
                id = UUID.randomUUID().toString(),
                assetId = assetId,
                durationSeconds = durationSeconds,
                indentLevel = 0,
            )
            val updated = currentBlocks.toMutableList()
            if (focusIdx != null && focusIdx in updated.indices &&
                updated[focusIdx] is Block.Paragraph &&
                BlockOperations.extractPlainText(updated[focusIdx]).isBlank()
            ) {
                updated[focusIdx] = audioBlock
            } else if (focusIdx != null && focusIdx in updated.indices) {
                updated.add(focusIdx + 1, audioBlock)
            } else {
                updated.add(audioBlock)
            }
            commitBlocks(updated, isStructural = true)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val blockId = pendingMediaBlockId
        pendingMediaBlockId = null
        if (uri == null) {
            removeBlockById(blockId)
        } else {
            blockId?.let { importMediaIntoBlock(it, uri, "image/*") }
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val blockId = pendingMediaBlockId
        pendingMediaBlockId = null
        if (uri == null) {
            removeBlockById(blockId)
        } else {
            blockId?.let { importMediaIntoBlock(it, uri, "application/pdf") }
        }
    }

    fun insertMediaBlock(kind: String) {
        if (kind == "audio") {
            activeKeyboardPanel = EditorKeyboardPanelType.AUDIO
            keyboardController?.hide()
            return
        }
        val assetId = UUID.randomUUID().toString()
        val focusIdx = focusedBlockId?.let { id -> currentBlocks.indexOfFirst { it.id == id } }
        val newBlock: Block = when (kind) {
            "image" -> Block.Image(UUID.randomUUID().toString(), assetId = assetId, indentLevel = 0)
            else -> Block.Pdf(UUID.randomUUID().toString(), assetId = assetId, indentLevel = 0)
        }
        val updated = currentBlocks.toMutableList()
        if (focusIdx != null && focusIdx != -1 &&
            updated[focusIdx] is Block.Paragraph &&
            BlockOperations.extractPlainText(updated[focusIdx]).isBlank()
        ) {
            updated[focusIdx] = newBlock
        } else if (focusIdx != null && focusIdx != -1) {
            updated.add(focusIdx + 1, newBlock)
        } else {
            updated.add(newBlock)
        }
        commitBlocks(updated, isStructural = true)
        when (kind) {
            "image" -> {
                pendingMediaBlockId = newBlock.id
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
            "pdf" -> {
                pendingMediaBlockId = newBlock.id
                pdfPicker.launch(arrayOf("application/pdf"))
            }
        }
    }

    fun selectOrConvertBlockType(selectedType: String) {
        flushPendingLocalTyping()
        activeKeyboardPanel = EditorKeyboardPanelType.NONE
        explicitToolbarMode = null
        slashFilterQuery = ""
        if (selectedType == "image" || selectedType == "audio" || selectedType == "pdf") {
            insertMediaBlock(selectedType)
            return
        }
        if (selectedType == "embed") {
            isSelectorLinkMode = false
            selectorInitialQuery = ""
            showUniversalSelectorSheet = true
            return
        }
        if (selectedType == "bookmark") {
            val targetId = focusedBlockId
            val current = currentBlocks.find { it.id == targetId }
            val text = current?.let { BlockOperations.extractPlainText(it) }.orEmpty()
            val url = extractUrlFromText(text)
            if (url == null) {
                // A WebLink/Bookmark CANNOT exist without a link: prompt for link first!
                showMentionWebLinkSheet = true
                return
            }
        }
        val targetId = focusedBlockId
        if (targetId != null) {
            val idx = currentBlocks.indexOfFirst { it.id == targetId }
            if (idx != -1) {
                val current = currentBlocks[idx]
                // Strip any leading `/filter` text if present before converting
                val cleanedCurrent = if (BlockOperations.extractPlainText(current).trim().startsWith("/")) {
                    BlockOperations.withSpans(current, emptyList())
                } else {
                    current
                }
                val converted = BlockOperations.convertBlockType(cleanedCurrent, selectedType)
                val updated = currentBlocks.toMutableList()
                updated[idx] = converted
                commitBlocks(updated, isStructural = true)
                targetFocusBlockId = converted.id
            }
        } else {
            val newBlock = BlockOperations.convertBlockType(
                Block.Paragraph(UUID.randomUUID().toString(), emptyList()),
                selectedType,
            )
            commitBlocks(currentBlocks + newBlock, isStructural = true)
            targetFocusBlockId = newBlock.id
        }
    }

    val localizedTypeAliases = rememberLocalizedTypeAliases()
    // Type ids ordered by their most recently updated object: `@` suggests recent types first.
    val recentTypeIds = remember(allActiveObjects) {
        allActiveObjects.sortedByDescending { it.updatedAt }.map { it.typeId }.distinct()
    }

    /**
     * Autocompletes `@tipo/` in the currently focused block when the user taps a type filter chip
     * in the Dynamic Island mention bar, keeping the software keyboard focused.
     */
    fun applyMentionTypePrefix(type: ObjectType) {
        flushPendingLocalTyping()
        val localizedKeyword = preferredTypeMentionKeyword(type, localizedTypeAliases)
        val prefixText = "@$localizedKeyword/"
        val targetId = focusedBlockId ?: currentBlocks.lastOrNull()?.id ?: return
        val idx = currentBlocks.indexOfFirst { it.id == targetId }
        if (idx == -1) return
        val current = currentBlocks[idx]
        val currentSpans = BlockOperations.extractSpans(current)
        val visible = currentSpans.toVisibleString()
        val match = LIVE_MENTION_QUERY_REGEX.find(visible)
        val (prefixSpans, tailSpans) = if (match != null) {
            val (before, after) = RichSpanEngine.splitAt(currentSpans, match.range.first)
            val (_, tailAfterQuery) = RichSpanEngine.splitAt(after, match.value.length)
            (before + listOf(InlineSpan.Text(prefixText))) to tailAfterQuery
        } else {
            val caret = (focusedSelection?.start ?: visible.length).coerceIn(0, visible.length)
            val (before, after) = RichSpanEngine.splitAt(currentSpans, caret)
            (before + listOf(InlineSpan.Text(prefixText))) to after
        }
        val newSpans = RichSpanEngine.coalesce(prefixSpans + tailSpans)
        val updated = currentBlocks.toMutableList()
        updated[idx] = BlockOperations.withSpans(current, newSpans)
        commitBlocks(updated)
        liveMentionQuery = prefixText.removePrefix("@")
        explicitToolbarMode = EditorToolbarMode.RESULTS_MENTION
        targetCursorOffset = RichSpanEngine.coalesce(prefixSpans).toVisibleString().length
        targetFocusBlockId = current.id
    }

    /**
     * Inserts a structured [InlineSpan.Mention] (or replaces trailing `@query`) in the focused block.
     * - When [selectedObj] is an Audio object, always inserts/converts to a full-width [Block.Audio].
     * - When [selectedObj] is an Image or PDF object and the current line only contains the `@query`
     *   (not in the middle of a sentence), inserts a full visual [Block.Image] or [Block.Pdf].
     */
    fun applySelectedMention(selectedObj: PkmObject) {
        flushPendingLocalTyping()
        val canonicalType = selectedObj.typeId
        val isAudioObj = canonicalType == DefaultTypes.AUDIO.id
        val isImageObj = canonicalType == DefaultTypes.IMAGE.id
        val isPdfObj = canonicalType == DefaultTypes.PDF.id
        val audioAssetId = if (isAudioObj) resolveAudioAssetId(selectedObj) else null
        val imageAssetId = if (isImageObj) resolveImageAssetId(selectedObj) else null
        val pdfAssetId = if (isPdfObj) resolvePdfAssetId(selectedObj) else null

        if (isAudioObj && !audioAssetId.isNullOrBlank()) {
            val audioDuration = extractAudioDurationSeconds(selectedObj).coerceAtLeast(1)
            val targetId = focusedBlockId ?: currentBlocks.lastOrNull()?.id
            val idx = if (targetId != null) currentBlocks.indexOfFirst { it.id == targetId } else -1
            if (idx == -1) {
                val audioBlock = Block.Audio(
                    id = UUID.randomUUID().toString(),
                    assetId = audioAssetId,
                    durationSeconds = audioDuration,
                    caption = selectedObj.title,
                    indentLevel = 0,
                )
                val nextParagraph = Block.Paragraph(
                    id = UUID.randomUUID().toString(),
                    spans = emptyList(),
                )
                commitBlocks(currentBlocks + listOf(audioBlock, nextParagraph), isStructural = true)
                liveMentionQuery = null
                explicitToolbarMode = null
                targetCursorOffset = 0
                targetFocusBlockId = nextParagraph.id
                return
            }

            val current = currentBlocks[idx]
            val currentSpans = BlockOperations.extractSpans(current)
            val visible = currentSpans.toVisibleString()
            val match = LIVE_MENTION_QUERY_REGEX.find(visible)
            val remainingSpans = if (match != null) {
                val (before, after) = RichSpanEngine.splitAt(currentSpans, match.range.first)
                val (_, tailAfterQuery) = RichSpanEngine.splitAt(after, match.value.length)
                RichSpanEngine.coalesce(before + tailAfterQuery)
            } else {
                currentSpans
            }
            val remainingText = remainingSpans.toVisibleString().trim()
            val updated = currentBlocks.toMutableList()
            val canReplaceCurrentBlock = remainingText.isEmpty() && BlockOperations.withSpans(current, remainingSpans) !== current

            if (canReplaceCurrentBlock) {
                val audioBlock = Block.Audio(
                    id = current.id,
                    assetId = audioAssetId,
                    durationSeconds = audioDuration,
                    caption = selectedObj.title,
                    indentLevel = current.indentLevel,
                    groupId = current.groupId,
                    blockColor = current.blockColor,
                )
                updated[idx] = audioBlock
                val focusNextId: String
                if (idx == updated.lastIndex) {
                    val nextParagraph = Block.Paragraph(
                        id = UUID.randomUUID().toString(),
                        spans = emptyList(),
                        indentLevel = current.indentLevel,
                    )
                    updated.add(nextParagraph)
                    focusNextId = nextParagraph.id
                } else {
                    focusNextId = updated[idx + 1].id
                }
                commitBlocks(updated, isStructural = true)
                liveMentionQuery = null
                explicitToolbarMode = null
                targetCursorOffset = 0
                targetFocusBlockId = focusNextId
            } else {
                val cleanedCurrent = BlockOperations.withSpans(current, remainingSpans)
                if (cleanedCurrent !== current) {
                    updated[idx] = cleanedCurrent
                }
                val audioBlock = Block.Audio(
                    id = UUID.randomUUID().toString(),
                    assetId = audioAssetId,
                    durationSeconds = audioDuration,
                    caption = selectedObj.title,
                    indentLevel = current.indentLevel,
                    groupId = current.groupId,
                    blockColor = current.blockColor,
                )
                updated.add(idx + 1, audioBlock)
                val focusNextId: String
                if (idx + 1 == updated.lastIndex) {
                    val nextParagraph = Block.Paragraph(
                        id = UUID.randomUUID().toString(),
                        spans = emptyList(),
                        indentLevel = current.indentLevel,
                    )
                    updated.add(nextParagraph)
                    focusNextId = nextParagraph.id
                } else {
                    focusNextId = updated[idx + 2].id
                }
                commitBlocks(updated, isStructural = true)
                liveMentionQuery = null
                explicitToolbarMode = null
                targetCursorOffset = 0
                targetFocusBlockId = focusNextId
            }
            return
        }

        // Smart visual block insertion for Image / PDF when the current line only had the @query
        if ((isImageObj && !imageAssetId.isNullOrBlank()) || (isPdfObj && !pdfAssetId.isNullOrBlank())) {
            val targetId = focusedBlockId ?: currentBlocks.lastOrNull()?.id
            val idx = if (targetId != null) currentBlocks.indexOfFirst { it.id == targetId } else -1
            val current = if (idx != -1) currentBlocks[idx] else null
            val currentSpans = current?.let { BlockOperations.extractSpans(it) }.orEmpty()
            val visible = currentSpans.toVisibleString()
            val match = LIVE_MENTION_QUERY_REGEX.find(visible)
            val remainingSpans = if (match != null) {
                val (before, after) = RichSpanEngine.splitAt(currentSpans, match.range.first)
                val (_, tailAfterQuery) = RichSpanEngine.splitAt(after, match.value.length)
                RichSpanEngine.coalesce(before + tailAfterQuery)
            } else {
                currentSpans
            }
            val remainingText = remainingSpans.toVisibleString().trim()
            val canReplaceLineWithMediaBlock = current != null &&
                remainingText.isEmpty() &&
                BlockOperations.withSpans(current, remainingSpans) !== current

            if (idx == -1 || canReplaceLineWithMediaBlock) {
                val baseId = current?.id ?: UUID.randomUUID().toString()
                val baseIndent = current?.indentLevel ?: 0
                val baseGroup = current?.groupId
                val baseColor = current?.blockColor
                val mediaBlock: Block = if (isImageObj && !imageAssetId.isNullOrBlank()) {
                    Block.Image(
                        id = baseId,
                        assetId = imageAssetId,
                        caption = selectedObj.title,
                        indentLevel = baseIndent,
                        groupId = baseGroup,
                        blockColor = baseColor,
                    )
                } else {
                    val fileNameProp = (selectedObj.property("file_name") as? PropertyValue.RichText)?.spans?.toPlainText()
                    val pdfPageCount = selectedObj.blocks.filterIsInstance<Block.Pdf>().firstOrNull()?.pageCount ?: 1
                    Block.Pdf(
                        id = baseId,
                        assetId = pdfAssetId.orEmpty(),
                        fileName = fileNameProp?.takeIf { it.isNotBlank() } ?: selectedObj.plainTitle,
                        pageCount = pdfPageCount,
                        indentLevel = baseIndent,
                        groupId = baseGroup,
                        blockColor = baseColor,
                    )
                }
                if (idx == -1) {
                    val nextParagraph = Block.Paragraph(UUID.randomUUID().toString(), emptyList())
                    commitBlocks(currentBlocks + listOf(mediaBlock, nextParagraph), isStructural = true)
                    liveMentionQuery = null
                    explicitToolbarMode = null
                    targetCursorOffset = 0
                    targetFocusBlockId = nextParagraph.id
                } else {
                    val updated = currentBlocks.toMutableList()
                    updated[idx] = mediaBlock
                    val focusNextId: String
                    if (idx == updated.lastIndex) {
                        val nextParagraph = Block.Paragraph(
                            id = UUID.randomUUID().toString(),
                            spans = emptyList(),
                            indentLevel = baseIndent,
                        )
                        updated.add(nextParagraph)
                        focusNextId = nextParagraph.id
                    } else {
                        focusNextId = updated[idx + 1].id
                    }
                    commitBlocks(updated, isStructural = true)
                    liveMentionQuery = null
                    explicitToolbarMode = null
                    targetCursorOffset = 0
                    targetFocusBlockId = focusNextId
                }
                return
            }
        }

        val mentionLabel = selectedObj.plainTitle.trim().ifBlank {
            appContext.getString(R.string.untitled_object)
        }
        val mentionSpan = InlineSpan.Mention(
            targetObjectId = selectedObj.id,
            fallbackText = mentionLabel,
        )
        val targetId = focusedBlockId ?: currentBlocks.lastOrNull()?.id
        val idx = if (targetId != null) currentBlocks.indexOfFirst { it.id == targetId } else -1
        if (idx == -1) {
            val newBlock = Block.Paragraph(
                id = UUID.randomUUID().toString(),
                spans = listOf(mentionSpan, InlineSpan.Text(" ")),
            )
            commitBlocks(currentBlocks + newBlock, isStructural = true)
            liveMentionQuery = null
            explicitToolbarMode = null
            targetCursorOffset = newBlock.spans.toVisibleString().length
            targetFocusBlockId = newBlock.id
            return
        }

        val current = currentBlocks[idx]
        val currentSpans = BlockOperations.extractSpans(current)
        val visible = currentSpans.toVisibleString()
        val match = LIVE_MENTION_QUERY_REGEX.find(visible)
        val (prefixSpans, tailSpans) = if (match != null) {
            val (before, after) = RichSpanEngine.splitAt(currentSpans, match.range.first)
            val (_, tailAfterQuery) = RichSpanEngine.splitAt(after, match.value.length)
            (before + listOf(mentionSpan, InlineSpan.Text(" "))) to tailAfterQuery
        } else {
            val sel = focusedSelection
            val selStart = minOf(sel?.start ?: visible.length, sel?.end ?: visible.length).coerceIn(0, visible.length)
            val selEnd = maxOf(sel?.start ?: visible.length, sel?.end ?: visible.length).coerceIn(0, visible.length)
            val (before, afterStart) = RichSpanEngine.splitAt(currentSpans, selStart)
            val (_, after) = RichSpanEngine.splitAt(afterStart, selEnd - selStart)
            (before + listOf(mentionSpan, InlineSpan.Text(" "))) to after
        }
        val newSpans = RichSpanEngine.coalesce(prefixSpans + tailSpans)
        val updatedCurrent = BlockOperations.withSpans(current, newSpans)
        val updated = currentBlocks.toMutableList()
        if (updatedCurrent === current) {
            // Current block does not hold inline spans (e.g. Divider / Image / Table); insert a Paragraph right after it
            val nextParagraph = Block.Paragraph(
                id = UUID.randomUUID().toString(),
                spans = listOf(mentionSpan, InlineSpan.Text(" ")),
            )
            updated.add(idx + 1, nextParagraph)
            commitBlocks(updated, isStructural = true)
            liveMentionQuery = null
            explicitToolbarMode = null
            targetCursorOffset = nextParagraph.spans.toVisibleString().length
            targetFocusBlockId = nextParagraph.id
        } else {
            updated[idx] = updatedCurrent
            commitBlocks(updated, isStructural = true)
            liveMentionQuery = null
            explicitToolbarMode = null
            targetCursorOffset = RichSpanEngine.coalesce(prefixSpans).toVisibleString().length
            targetFocusBlockId = current.id
        }
    }

    /**
     * Inserts a structured [InlineSpan.Tag] (or replaces trailing `#query`) in the focused block.
     */
    fun applySelectedInlineTag(tagName: String, colorHex: String? = null) {
        flushPendingLocalTyping()
        val cleanName = tagName.removePrefix("#").trim()
        if (cleanName.isBlank()) return
        coroutineScope.launch {
            repository?.getOrCreateTagObject(
                cleanName,
                colorHex ?: TagPalette.getColorHex(cleanName.hashCode()),
            )
        }
        val targetId = focusedBlockId ?: currentBlocks.lastOrNull()?.id ?: return
        val idx = currentBlocks.indexOfFirst { it.id == targetId }
        if (idx == -1) return

        val current = currentBlocks[idx]
        val currentSpans = BlockOperations.extractSpans(current)
        val visible = currentSpans.toVisibleString()
        val match = LIVE_TAG_QUERY_REGEX.find(visible)
        val newSpans = if (match != null) {
            val (before, after) = RichSpanEngine.splitAt(currentSpans, match.range.first)
            val (_, tailAfterQuery) = RichSpanEngine.splitAt(after, match.value.length)
            RichSpanEngine.coalesce(
                before + listOf(
                    InlineSpan.Tag(cleanName),
                    InlineSpan.Text(" "),
                ) + tailAfterQuery,
            )
        } else {
            val caret = (focusedSelection?.start ?: visible.length).coerceIn(0, visible.length)
            val (before, after) = RichSpanEngine.splitAt(currentSpans, caret)
            RichSpanEngine.coalesce(
                before + listOf(
                    InlineSpan.Tag(cleanName),
                    InlineSpan.Text(" "),
                ) + after,
            )
        }
        val updated = currentBlocks.toMutableList()
        updated[idx] = BlockOperations.withSpans(current, newSpans)
        commitBlocks(updated, isStructural = true)
        liveTagQuery = null
        explicitToolbarMode = null
        targetCursorOffset = newSpans.toVisibleString().length
        targetFocusBlockId = current.id
    }

    /**
     * Inserts a structured [InlineSpan.Date] (or replaces trailing `@query`) in the focused block.
     */
    fun applySelectedInlineDate(isoDate: String) {
        flushPendingLocalTyping()
        val targetId = focusedBlockId ?: currentBlocks.lastOrNull()?.id ?: return
        val idx = currentBlocks.indexOfFirst { it.id == targetId }
        if (idx == -1) return

        val current = currentBlocks[idx]
        val currentSpans = BlockOperations.extractSpans(current)
        val visible = currentSpans.toVisibleString()
        val match = LIVE_MENTION_QUERY_REGEX.find(visible)
        val newSpans = if (match != null) {
            val (before, after) = RichSpanEngine.splitAt(currentSpans, match.range.first)
            val (_, tailAfterQuery) = RichSpanEngine.splitAt(after, match.value.length)
            RichSpanEngine.coalesce(
                before + listOf(
                    InlineSpan.Date(isoDate),
                    InlineSpan.Text(" "),
                ) + tailAfterQuery,
            )
        } else {
            val caret = (focusedSelection?.start ?: visible.length).coerceIn(0, visible.length)
            val (before, after) = RichSpanEngine.splitAt(currentSpans, caret)
            RichSpanEngine.coalesce(
                before + listOf(
                    InlineSpan.Date(isoDate),
                    InlineSpan.Text(" "),
                ) + after,
            )
        }
        val updated = currentBlocks.toMutableList()
        updated[idx] = BlockOperations.withSpans(current, newSpans)
        commitBlocks(updated, isStructural = true)
        liveMentionQuery = null
        explicitToolbarMode = null
        activeKeyboardPanel = EditorKeyboardPanelType.NONE
        targetCursorOffset = newSpans.toVisibleString().length
        targetFocusBlockId = current.id
    }

    /**
     * Applies a web URL link to the current selection or inserts it at the caret.
     */
    fun applySelectedLinkUrl(url: String) {
        flushPendingLocalTyping()
        val targetId = focusedBlockId ?: currentBlocks.lastOrNull()?.id ?: return
        val idx = currentBlocks.indexOfFirst { it.id == targetId }
        if (idx == -1) return
        val current = currentBlocks[idx]
        val currentSpans = BlockOperations.extractSpans(current)
        val visible = currentSpans.toVisibleString()
        val sel = focusedSelection
        val start = minOf(sel?.start ?: visible.length, sel?.end ?: visible.length).coerceIn(0, visible.length)
        val end = maxOf(sel?.start ?: visible.length, sel?.end ?: visible.length).coerceIn(0, visible.length)
        val newSpans = if (start < end) {
            RichSpanEngine.applyLinkToRange(currentSpans, start, end, url)
        } else {
            val (before, after) = RichSpanEngine.splitAt(currentSpans, start)
            val domainLabel = runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
            RichSpanEngine.coalesce(
                before + listOf(
                    InlineSpan.Text(text = domainLabel, linkUrl = url),
                    InlineSpan.Text(text = " "),
                ) + after,
            )
        }
        val updated = currentBlocks.toMutableList()
        updated[idx] = BlockOperations.withSpans(current, newSpans)
        commitBlocks(updated, isStructural = true)
        explicitToolbarMode = null
    }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    // Pattern F: Compact Preview Popup on tap for @mention, #tag, link, or Bookmark
    activeInlinePreview?.let { previewData ->
        InlineElementPreviewPopup(
            preview = previewData,
            onDismiss = { activeInlinePreview = null },
        )
    }

    // Full-screen pinch-to-zoom image dialog
    fullScreenPreviewImage?.let { imgBlock ->
        FullScreenImagePreviewDialog(
            assetId = imgBlock.assetId,
            caption = imgBlock.caption.toPlainText(),
            refreshKey = imgBlock.assetId in importedAssetIds,
            onDismiss = { fullScreenPreviewImage = null },
        )
    }

    // Inline Whiteboard Canvas editor dialog
    activeInlineCanvasBlock?.let { canvasBlock ->
        if (repository != null) {
            InlineCanvasEditorDialog(
                initialData = canvasBlock.data,
                onSaveData = { updatedData ->
                    val idx = currentBlocks.indexOfFirst { it.id == canvasBlock.id }
                    if (idx != -1) {
                        val updated = currentBlocks.toMutableList()
                        val newBlock = canvasBlock.copy(data = updatedData)
                        updated[idx] = newBlock
                        commitBlocks(updated, isStructural = false)
                        activeInlineCanvasBlock = newBlock
                    }
                },
                onDismiss = { activeInlineCanvasBlock = null },
                allObjects = allActiveObjects,
                allTypes = allTypes,
                repository = repository,
                onNavigateToObject = { targetId ->
                    activeInlineCanvasBlock = null
                    onNavigateToObject?.invoke(targetId)
                },
            )
        }
    }

    // Slash menu fallback sheet
    if (showSlashSheet) {
        SlashMenuBottomSheet(
            onDismiss = { showSlashSheet = false },
            onSelect = { selectedType ->
                showSlashSheet = false
                selectOrConvertBlockType(selectedType)
            },
        )
    }

    val mentionImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val customTitle = pendingMentionMediaTitle
        pendingMentionMediaTitle = null
        val repo = repository
        if (uri != null && repo != null) {
            coroutineScope.launch {
                val created = createNewImageObjectFromUri(
                    context = appContext,
                    repository = repo,
                    uri = uri,
                    customTitle = customTitle,
                )
                if (created != null) {
                    applySelectedMention(created)
                }
            }
        }
    }

    if (showMentionWebLinkSheet) {
        CreateWebLinkSheet(
            initialTitle = pendingMentionMediaTitle.orEmpty(),
            onDismiss = {
                showMentionWebLinkSheet = false
                pendingMentionMediaTitle = null
            },
            onWebLinkCreated = { created ->
                showMentionWebLinkSheet = false
                val targetId = focusedBlockId
                val targetIdx = if (targetId != null) currentBlocks.indexOfFirst { it.id == targetId } else -1
                val url = created.weblinkUrl.orEmpty()
                if (targetIdx != -1) {
                    val updated = currentBlocks.toMutableList()
                    val existing = updated[targetIdx]
                    updated[targetIdx] = Block.Bookmark(
                        id = existing.id,
                        url = url,
                        title = created.plainTitle,
                        domain = (created.property("site_name") as? PropertyValue.RichText)?.spans?.toPlainText().orEmpty(),
                        targetObjectId = created.id,
                        thumbnailUrl = created.blocks.filterIsInstance<Block.Bookmark>().firstOrNull()?.thumbnailUrl,
                        indentLevel = existing.indentLevel,
                        groupId = existing.groupId,
                        blockColor = existing.blockColor,
                    )
                    commitBlocks(updated, isStructural = true)
                } else {
                    applySelectedMention(created)
                }
                pendingMentionMediaTitle = null
            },
        )
    }

    if (showMentionAudioSheet) {
        CreateAudioObjectSheet(
            initialTitle = pendingMentionMediaTitle.orEmpty(),
            showTitleField = true,
            onDismiss = {
                showMentionAudioSheet = false
                pendingMentionMediaTitle = null
            },
            onAudioReady = { bytes, durationSeconds, mime, fileName, customTitle ->
                showMentionAudioSheet = false
                val effectiveTitle = customTitle.ifBlank { pendingMentionMediaTitle.orEmpty() }
                pendingMentionMediaTitle = null
                val repo = repository ?: return@CreateAudioObjectSheet
                coroutineScope.launch {
                    val created = createNewAudioObjectFromBytes(
                        context = appContext,
                        repository = repo,
                        bytes = bytes,
                        durationSeconds = durationSeconds,
                        mime = mime,
                        customTitle = effectiveTitle,
                        rawFileName = fileName,
                    )
                    if (created != null) {
                        applySelectedMention(created)
                    }
                }
            },
        )
    }

    // Pattern E: Half-height Universal Object & Link Selector Sheet
    if (showUniversalSelectorSheet) {
        UniversalObjectLinkSelectorSheet(
            objects = allActiveObjects,
            allTypes = allTypes,
            initialQuery = selectorInitialQuery,
            initialTypeId = selectorInitialTypeId,
            isLinkMode = isSelectorLinkMode,
            onDismiss = {
                showUniversalSelectorSheet = false
                selectorInitialQuery = ""
                selectorInitialTypeId = null
            },
            onSelectObject = { selectedObj ->
                applySelectedMention(selectedObj)
                showUniversalSelectorSheet = false
                selectorInitialQuery = ""
                selectorInitialTypeId = null
            },
            onSelectUrl = { selectedUrl ->
                applySelectedLinkUrl(selectedUrl)
                showUniversalSelectorSheet = false
                selectorInitialQuery = ""
                selectorInitialTypeId = null
            },
            onCreateNew = { typeId: String, title: String, emoji: String ->
                val canonical = typeId
                showUniversalSelectorSheet = false
                selectorInitialQuery = ""
                selectorInitialTypeId = null
                if (canonical == DefaultTypes.IMAGE.id) {
                    pendingMentionMediaTitle = title
                    mentionImagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                } else if (canonical == DefaultTypes.AUDIO.id) {
                    pendingMentionMediaTitle = title
                    showMentionAudioSheet = true
                } else if (canonical == DefaultTypes.WEBLINK.id) {
                    pendingMentionMediaTitle = title
                    showMentionWebLinkSheet = true
                } else {
                    val newObj = PkmObject(
                        id = UUID.randomUUID().toString(),
                        typeId = canonical,
                        title = title.toTextSpan(),
                        icon = if (emoji.isNotBlank()) ObjectIcon(emoji = emoji) else null,
                    )
                    coroutineScope.launch {
                        repository?.saveObject(newObj)
                    }
                    applySelectedMention(newObj)
                }
            },
        )
    }

    // Tag Selection BottomSheet (# or top Tags Header)
    if (showTagSheet) {
        val targetId = focusedBlockId
        val currentBlock = currentBlocks.find { it.id == targetId }
        val currentText = currentBlock?.let { BlockOperations.extractPlainText(it) } ?: ""
        val activeBlockTags = remember(currentBlock, currentText, isHeaderTagPicker, directTagNames) {
            if (isHeaderTagPicker) {
                directTagNames
            } else {
                val spanTags = currentBlock?.let { BlockOperations.extractSpans(it) }
                    .orEmpty()
                    .filterIsInstance<InlineSpan.Tag>()
                    .map { it.tagName }
                val regexTags = BLOCK_TAG_REGEX.findAll(currentText).map { it.groupValues[1] }.toList()
                (spanTags + regexTags).distinctBy { it.lowercase() }
            }
        }

        TagSelectionBottomSheet(
            allTags = allTags,
            activeBlockTags = activeBlockTags,
            initialQuery = tagInitialQuery,
            onDismiss = {
                showTagSheet = false
                isHeaderTagPicker = false
                tagInitialQuery = ""
            },
            onToggleTag = { name, colorHex ->
                if (isHeaderTagPicker) {
                    toggleObjectDirectTag(name, colorHex)
                    return@TagSelectionBottomSheet
                }
                applySelectedInlineTag(name, colorHex)
            },
            onCreateTag = { name, colorHex ->
                if (isHeaderTagPicker) {
                    toggleObjectDirectTag(name, colorHex)
                    showTagSheet = false
                    isHeaderTagPicker = false
                    tagInitialQuery = ""
                    return@TagSelectionBottomSheet
                }
                applySelectedInlineTag(name, colorHex)
                showTagSheet = false
                isHeaderTagPicker = false
                tagInitialQuery = ""
            },
        )
    }

    // Stabilize environment objects so saving the currently edited object (ownerObjectId)
    // does not invalidate BlockEnvironment and recompose every unfocused BlockItemView.
    var stableEnvObjects by remember { mutableStateOf(allActiveObjects) }
    LaunchedEffect(allActiveObjects, ownerObjectId) {
        val prev = stableEnvObjects
        val changed = prev.size != allActiveObjects.size ||
            allActiveObjects.indices.any { i ->
                val a = prev[i]
                val b = allActiveObjects[i]
                if (a.id != b.id) {
                    true
                } else if (a.id == ownerObjectId) {
                    a.plainTitle != b.plainTitle || a.icon != b.icon || a.typeId != b.typeId
                } else {
                    a.updatedAt != b.updatedAt || a.revision != b.revision
                }
            }
        if (changed) {
            stableEnvObjects = allActiveObjects
        }
    }

    // Mention pills show the target's current title after a rename (see refreshMentionLabels).
    // Display only: the refreshed label is persisted with the next edit of this object.
    LaunchedEffect(stableEnvObjects, blocks) {
        if (pendingBlocksChangeJob?.isActive == true || pendingLocalSyncJob?.isActive == true) return@LaunchedEffect
        val live = liveBlocksRef[0]
        val refreshed = refreshMentionLabels(live) { id -> stableEnvObjects.find { it.id == id }?.plainTitle }
        if (refreshed !== live) {
            liveBlocksRef[0] = refreshed
            currentBlocks = refreshed
            textSyncRevision++
        }
    }

    val blockEnvironment = remember(stableEnvObjects, allTypes, importedAssetIds.size, tagColors) {
        BlockEnvironment(
            allObjects = stableEnvObjects,
            allTypes = allTypes,
            mediaRefreshKeys = importedAssetIds.toSet(),
            tagColors = tagColors,
        )
    }

    @Composable
    fun TagsHeader() {
        ObjectTagsHeaderRow(
            directTagNames = directTagNames,
            inferredBlockTagNames = inferredBlockTagNames,
            allTags = allTags,
            onOpenTagPicker = {
                isHeaderTagPicker = true
                tagInitialQuery = ""
                showTagSheet = true
            },
            onNavigateToDirectTag = { tagName ->
                navigateToTagByName(tagName)
            },
            onFocusBlockWithTag = { tagName ->
                val cleanTag = tagName.removePrefix("#").trim()
                val tagPattern = Regex(
                    """(?<=^|[\s(\[])#${Regex.escape(cleanTag)}(?=\s|$|[.,!?;:)\]])""",
                    RegexOption.IGNORE_CASE,
                )
                var targetId: String? = null
                val updatedBlocks = currentBlocks.toMutableList()
                var blocksChanged = false
                for (i in updatedBlocks.indices) {
                    val b = updatedBlocks[i]
                    val spans = BlockOperations.extractSpans(b)
                    val hasTag = spans.any { it is InlineSpan.Tag && it.tagName.equals(cleanTag, ignoreCase = true) } ||
                        tagPattern.containsMatchIn(BlockOperations.extractPlainText(b))
                    if (hasTag) {
                        targetId = b.id
                        // Ensure any preceding collapsed Toggle hiding this block is expanded
                        for (j in i - 1 downTo 0) {
                            val prev = updatedBlocks[j]
                            if (prev is Block.Toggle && !prev.isExpanded && prev.indentLevel < b.indentLevel) {
                                updatedBlocks[j] = prev.copy(isExpanded = true)
                                blocksChanged = true
                            }
                        }
                        break
                    }
                }
                if (blocksChanged) {
                    commitBlocks(updatedBlocks, isStructural = true)
                }
                if (targetId != null) {
                    focusedBlockId = targetId
                    highlightedBlockId = targetId
                }
            },
            onRemoveDirectTag = { tagName ->
                val tagObj = allTags.find { it.plainTitle.equals(tagName, ignoreCase = true) }
                val hex = tagObj?.icon?.colorHex ?: TagPalette.getColorHex(tagName.hashCode())
                toggleObjectDirectTag(tagName, hex)
            },
        )
        Spacer(modifier = Modifier.height(10.dp))
    }

    @Composable
    fun AddBlockBox() {
        // Tap empty space below last block to focus last block (if empty) or append a new paragraph
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (selectedBlockIds.isNotEmpty()) {
                        selectedBlockIds.clear()
                        explicitToolbarMode = null
                        return@clickable
                    }
                    val lastBlock = currentBlocks.lastOrNull()
                    if (lastBlock is Block.Paragraph && BlockOperations.extractPlainText(lastBlock).isEmpty()) {
                        targetCursorOffset = 0
                        targetFocusBlockId = lastBlock.id
                    } else {
                        val newBlock = Block.Paragraph(UUID.randomUUID().toString(), emptyList())
                        commitBlocks(currentBlocks + newBlock, isStructural = true)
                        targetCursorOffset = 0
                        targetFocusBlockId = newBlock.id
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.Surface, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.action_add_block),
                    tint = AppColors.Accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.action_add_block),
                    fontSize = 13.sp,
                    color = AppColors.Accent,
                )
            }
        }
    }

    @Composable
    fun BlockRow(visibleItem: VisibleBlockEntry) {
        val block = visibleItem.block
        val index = visibleItem.realIndex
        key(block.id) {
            val requester = remember(block.id) { FocusRequester() }
            val bringIntoViewRequester = remember(block.id) { BringIntoViewRequester() }

            LaunchedEffect(targetFocusBlockId) {
                if (targetFocusBlockId == block.id) {
                    // Wait for the item to be attached, then hand focus over in the same frame.
                    // The previous field stays focused until this request succeeds, so the IME is
                    // never left without an owner (which used to close and reopen the keyboard).
                    var attempts = 0
                    while (attempts < 12 && focusedBlockId != block.id) {
                        withFrameNanos { }
                        runCatching { requester.requestFocus() }
                        attempts++
                    }
                    if (focusedBlockId == block.id) {
                        runCatching { bringIntoViewRequester.bringIntoView() }
                        keyboardController?.show()
                    }
                    targetFocusBlockId = null
                }
            }

            LaunchedEffect(highlightedBlockId) {
                if (highlightedBlockId == block.id) {
                    runCatching { bringIntoViewRequester.bringIntoView() }
                    delay(1800)
                    if (highlightedBlockId == block.id) {
                        highlightedBlockId = null
                    }
                }
            }

            val isBlockFocused = focusedBlockId == block.id
            LaunchedEffect(isBlockFocused, targetCursorOffset) {
                if (isBlockFocused) {
                    delay(100L)
                    runCatching { bringIntoViewRequester.bringIntoView() }
                }
            }

            val isBlockSelected = block.id in selectedBlockIds
            val isSelectionModeActive = selectedBlockIds.isNotEmpty()

            val itemVisualState = remember(
                isBlockFocused,
                isBlockSelected,
                isSelectionModeActive,
                visibleItem.isFirstInGroup,
                visibleItem.isLastInGroup,
                visibleItem.hiddenChildrenCount,
                if (isBlockFocused || targetFocusBlockId == block.id) targetCursorOffset else null,
                if (isBlockFocused) textSyncRevision else 0,
                if (isBlockFocused) activeTypingFormats else null,
                if (isBlockFocused) activeTextColor else null,
                if (isBlockFocused) activeBgColor else null,
                if (isBlockFocused) hasExplicitTextColor else false,
                if (isBlockFocused) hasExplicitBgColor else false,
            ) {
                BlockVisualState(
                    isFocused = isBlockFocused,
                    isSelected = isBlockSelected,
                    isSelectionMode = isSelectionModeActive,
                    isFirstInGroup = visibleItem.isFirstInGroup,
                    isLastInGroup = visibleItem.isLastInGroup,
                    hiddenChildrenCount = visibleItem.hiddenChildrenCount,
                    requestedCursorOffset = if (isBlockFocused || targetFocusBlockId == block.id) {
                        targetCursorOffset
                    } else {
                        null
                    },
                    textSyncRevision = if (isBlockFocused) textSyncRevision else 0,
                    activeTypingFormats = if (isBlockFocused) activeTypingFormats else null,
                    activeTextColor = if (isBlockFocused) activeTextColor else null,
                    activeBgColor = if (isBlockFocused) activeBgColor else null,
                    hasExplicitTextColor = if (isBlockFocused) hasExplicitTextColor else false,
                    hasExplicitBgColor = if (isBlockFocused) hasExplicitBgColor else false,
                )
            }

            val itemActions = remember(block.id) {
                object : BlockActionCallbacks {
                    override fun onFocusGained() {
                        if (focusedBlockId != block.id) {
                            flushPendingLocalTyping()
                            activeTypingFormats = null
                            hasExplicitTextColor = false
                            hasExplicitBgColor = false
                            activeTextColor = null
                            activeBgColor = null
                            liveMentionQuery = null
                            liveTagQuery = null
                            pastedUrlCandidate = null
                            if (explicitToolbarMode != EditorToolbarMode.SELECTION) {
                                explicitToolbarMode = null
                            }
                        }
                        focusedBlockId = block.id
                        if (activeKeyboardPanel != EditorKeyboardPanelType.NONE) {
                            activeKeyboardPanel = EditorKeyboardPanelType.NONE
                        }
                    }

                    override fun onFocusCleared() {}

                    override fun onSelectionChange(selection: TextRange) {
                        if (block.id == focusedBlockId) {
                            liveSelectionRef[0] = selection
                            val prev = focusedSelection
                            val shouldUpdateImmediately = prev == null ||
                                !selection.collapsed ||
                                !prev.collapsed ||
                                explicitToolbarMode == EditorToolbarMode.FORMAT ||
                                explicitToolbarMode == EditorToolbarMode.COLOR ||
                                pendingLocalSyncJob?.isActive != true
                            if (shouldUpdateImmediately && focusedSelection != selection) {
                                focusedSelection = selection
                            }
                        }
                    }

                    override fun onCursorOffsetConsumed() {
                        targetCursorOffset = null
                    }

                    override fun onCursorMoved() {
                        if (focusedBlockId == block.id) {
                            activeTypingFormats = null
                            hasExplicitTextColor = false
                            hasExplicitBgColor = false
                        }
                    }

                    override fun onAudioRecorded(blockId: String, bytes: ByteArray, durationSeconds: Int) {
                        onAudioRecordedIntoBlock(blockId, bytes, durationSeconds)
                    }

                    override fun onToggleSelection() {
                        flushPendingLocalTyping()
                        if (block.id in selectedBlockIds) {
                            selectedBlockIds.remove(block.id)
                            if (selectedBlockIds.isEmpty() && explicitToolbarMode == EditorToolbarMode.SELECTION) {
                                explicitToolbarMode = null
                            }
                        } else {
                            selectedBlockIds.add(block.id)
                            explicitToolbarMode = EditorToolbarMode.SELECTION
                        }
                    }

                    override fun onLongPressSelect() {
                        flushPendingLocalTyping()
                        if (block.id !in selectedBlockIds) {
                            selectedBlockIds.add(block.id)
                        }
                        explicitToolbarMode = EditorToolbarMode.SELECTION
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }

                    override fun onMoveUp() {
                        val currentBlocksSnapshot = flushPendingLocalTyping()
                        val targetIds = if (selectedBlockIds.isNotEmpty() && block.id in selectedBlockIds) {
                            selectedBlockIds.toSet()
                        } else {
                            setOf(block.id)
                        }
                        commitBlocks(BlockOperations.moveBlocks(currentBlocksSnapshot, targetIds, -1), isStructural = true)
                    }

                    override fun onMoveDown() {
                        val currentBlocksSnapshot = flushPendingLocalTyping()
                        val targetIds = if (selectedBlockIds.isNotEmpty() && block.id in selectedBlockIds) {
                            selectedBlockIds.toSet()
                        } else {
                            setOf(block.id)
                        }
                        commitBlocks(BlockOperations.moveBlocks(currentBlocksSnapshot, targetIds, 1), isStructural = true)
                    }

                    override fun onHorizontalIndentDelta(delta: Int) {
                        val currentBlocksSnapshot = flushPendingLocalTyping()
                        val targetIds = if (selectedBlockIds.isNotEmpty() && block.id in selectedBlockIds) {
                            selectedBlockIds.toSet()
                        } else {
                            setOf(block.id)
                        }
                        commitBlocks(BlockOperations.indentBlocks(currentBlocksSnapshot, targetIds, delta), isStructural = true)
                    }

                    override fun onUpdate(updatedBlock: Block) {
                        val updated = flushPendingLocalTyping().toMutableList()
                        val idx = updated.indexOfFirst { it.id == block.id }
                        if (idx != -1) {
                            updated[idx] = updatedBlock
                            commitBlocks(updated, isStructural = true)
                        }
                    }

                    override fun onTextChange(newText: String) {
                        val updated = liveBlocksRef[0].toMutableList()
                        val idx = updated.indexOfFirst { it.id == block.id }
                        if (idx != -1) {
                            updated[idx] = BlockOperations.withText(updated[idx], newText)
                            commitBlocks(updated, deferUiRecomposition = true)
                        }
                    }

                    override fun onSpansChange(newSpans: List<InlineSpan>, caret: Int) {
                        val currentBlocksSnapshot = liveBlocksRef[0]
                        val idx = currentBlocksSnapshot.indexOfFirst { it.id == block.id }
                        if (idx == -1) return
                        val currentBlock = currentBlocksSnapshot[idx]

                        // Fast path: a single unstyled Text run (the overwhelming majority of
                        // keystrokes). Reading its text is O(1) instead of copying the whole block.
                        val fastNewText = newSpans.singleOrNull()?.let { span ->
                            (span as? InlineSpan.Text)?.takeIf {
                                it.marks.isEmpty() && it.linkUrl == null &&
                                    it.textColor == null && it.backgroundColor == null
                            }?.text
                        }
                        val newText = fastNewText ?: newSpans.toVisibleString()
                        val oldText = if (fastNewText != null) {
                            // Read the existing plain run directly from the block instead of
                            // normalizing/copying the whole span list.
                            val oldSpans = (currentBlock as? SpannedBlock)?.spans
                            oldSpans?.singleOrNull()?.let { span ->
                                (span as? InlineSpan.Text)?.takeIf {
                                    it.marks.isEmpty() && it.linkUrl == null &&
                                        it.textColor == null && it.backgroundColor == null
                                }?.text
                            } ?: BlockOperations.extractPlainText(currentBlock)
                        } else {
                            BlockOperations.extractPlainText(currentBlock)
                        }

                        // 1. Implicit escape on double space "  " when in @mention or #tag live mode
                        if ((liveMentionQuery != null || liveTagQuery != null) && newText.endsWith("  ")) {
                            liveMentionQuery = null
                            liveTagQuery = null
                            explicitToolbarMode = null
                            val updated = currentBlocksSnapshot.toMutableList()
                            updated[idx] = BlockOperations.withSpans(currentBlock, newSpans)
                            commitBlocks(updated)
                            return
                        }

                        // Fast path for ordinary typing: no trigger characters, no active
                        // mention/tag/slash mode and no possible block-prefix shortcut.
                        val hasTrigger = newText.indexOf('@') >= 0 ||
                            newText.indexOf('#') >= 0 ||
                            newText.startsWith("/") ||
                            newText.endsWith("  ") ||
                            (newText.length - oldText.length >= 8 &&
                                newText.trim().let {
                                    it.startsWith("https://", ignoreCase = true) ||
                                        it.startsWith("http://", ignoreCase = true)
                                })
                        val hasActiveMode = liveMentionQuery != null ||
                            liveTagQuery != null ||
                            explicitToolbarMode == EditorToolbarMode.BLOCK_TYPE ||
                            explicitToolbarMode == EditorToolbarMode.PASTE_URL ||
                            explicitToolbarMode == EditorToolbarMode.RESULTS_MENTION ||
                            explicitToolbarMode == EditorToolbarMode.RESULTS_TAG
                        if (!hasTrigger && !hasActiveMode && newText.length > 6) {
                            val updated = currentBlocksSnapshot.toMutableList()
                            updated[idx] = BlockOperations.withSpans(currentBlock, newSpans)
                            commitBlocks(updated, deferUiRecomposition = true)
                            return
                        }

                        var modeChanged = false

                        // 2. Detect standalone URL paste
                        val deltaLen = newText.length - oldText.length
                        if (deltaLen >= 8) {
                            val trimmed = newText.trim()
                            if ((trimmed.startsWith("https://", ignoreCase = true) ||
                                    trimmed.startsWith("http://", ignoreCase = true)) &&
                                !trimmed.contains(' ')
                            ) {
                                pastedUrlCandidate = trimmed
                                explicitToolbarMode = EditorToolbarMode.PASTE_URL
                                modeChanged = true
                            }
                        }

                        // 3. Check `/query` slash filter in the Dynamic Island toolbar (keeps keyboard open!)
                        if (currentBlock is Block.Paragraph && newText.startsWith("/") && !newText.contains(' ')) {
                            slashFilterQuery = newText.removePrefix("/")
                            explicitToolbarMode = EditorToolbarMode.BLOCK_TYPE
                            modeChanged = true
                        } else if (explicitToolbarMode == EditorToolbarMode.BLOCK_TYPE && !newText.startsWith("/")) {
                            slashFilterQuery = ""
                            explicitToolbarMode = null
                            modeChanged = true
                        }

                        // 4. Check live `@query` mention trigger in the Dynamic Island toolbar (fast-path when '@' absent)
                        val mentionMatch = if (newText.indexOf('@') >= 0) {
                            LIVE_MENTION_QUERY_REGEX.find(newText)
                        } else {
                            null
                        }
                        if (mentionMatch != null && !mentionMatch.groupValues[1].endsWith("  ")) {
                            liveMentionQuery = mentionMatch.groupValues[1]
                            liveTagQuery = null
                            if (explicitToolbarMode != EditorToolbarMode.CREATE_OBJECT_AS) {
                                explicitToolbarMode = EditorToolbarMode.RESULTS_MENTION
                            }
                            modeChanged = true
                        } else if (liveMentionQuery != null) {
                            liveMentionQuery = null
                            if (explicitToolbarMode == EditorToolbarMode.RESULTS_MENTION ||
                                explicitToolbarMode == EditorToolbarMode.CREATE_OBJECT_AS
                            ) {
                                explicitToolbarMode = null
                            }
                            modeChanged = true
                        }

                        // 5. Check live `#query` tag trigger in the Dynamic Island toolbar (fast-path when '#' absent)
                        val tagMatch = if (newText.indexOf('#') >= 0) {
                            LIVE_TAG_QUERY_REGEX.find(newText)
                        } else {
                            null
                        }
                        if (tagMatch != null && !newText.startsWith("##")) {
                            liveTagQuery = tagMatch.groupValues[1]
                            liveMentionQuery = null
                            explicitToolbarMode = EditorToolbarMode.RESULTS_TAG
                            modeChanged = true
                        } else if (liveTagQuery != null) {
                            liveTagQuery = null
                            if (explicitToolbarMode == EditorToolbarMode.RESULTS_TAG) {
                                explicitToolbarMode = null
                            }
                            modeChanged = true
                        }

                        // 6. Check block-start Markdown prefix shortcuts (`# `, `[] `, `- `, `1. `, `a. `, `i. `, `> `, `! `, `>> `, `▶ `, `---`, ```` ``` ````, `$$`)
                        val shortcut = if (newText.length > oldText.length && newText.length <= 6) {
                            DefaultMarkdownShortcutHandler.processPrefix(currentBlock, newText)
                        } else {
                            null
                        }
                        if (shortcut != null) {
                            when (shortcut) {
                                is MarkdownShortcutResult.TransformBlock -> {
                                    val updated = currentBlocksSnapshot.toMutableList()
                                    updated[idx] = shortcut.newBlock
                                    commitBlocks(updated, isStructural = true)
                                    targetCursorOffset = BlockOperations.extractPlainText(shortcut.newBlock).length
                                    if (shortcut.newBlock is Block.Code || shortcut.newBlock is Block.Math) {
                                        targetFocusBlockId = shortcut.newBlock.id
                                    }
                                }
                                is MarkdownShortcutResult.InsertDivider -> {
                                    val updated = currentBlocksSnapshot.toMutableList()
                                    updated[idx] = shortcut.dividerBlock
                                    updated.add(idx + 1, shortcut.nextParagraph)
                                    commitBlocks(updated, isStructural = true)
                                    targetCursorOffset = 0
                                    targetFocusBlockId = shortcut.nextParagraph.id
                                }
                            }
                        } else {
                            val updated = currentBlocksSnapshot.toMutableList()
                            updated[idx] = BlockOperations.withSpans(currentBlock, newSpans)
                            commitBlocks(updated, deferUiRecomposition = !modeChanged)
                        }
                    }

                    override fun onDelete() {
                        val currentBlocksSnapshot = flushPendingLocalTyping()
                        val idx = currentBlocksSnapshot.indexOfFirst { it.id == block.id }
                        if (idx != -1) {
                            deletedBackupBlocks = currentBlocksSnapshot
                            val updated = currentBlocksSnapshot.toMutableList()
                            updated.removeAt(idx)
                            commitBlocks(updated, isStructural = true)
                            explicitToolbarMode = EditorToolbarMode.NOTICE
                            if (focusedBlockId == block.id) {
                                // Keep the keyboard alive by moving real focus to the neighbour,
                                // not just the visual highlight.
                                val nextFocus = liveBlocksRef[0]
                                    .getOrNull((idx - 1).coerceAtLeast(0))
                                focusedBlockId = nextFocus?.id
                                targetFocusBlockId = nextFocus?.id
                                targetCursorOffset = nextFocus
                                    ?.let { BlockOperations.extractPlainText(it).length }
                                    ?: 0
                            }
                        }
                    }

                    override fun onEnterAtOffset(caretOffset: Int) {
                        val currentBlocksSnapshot = flushPendingLocalTyping()
                        val idx = currentBlocksSnapshot.indexOfFirst { it.id == block.id }
                        if (idx != -1) {
                            val currentBlock = currentBlocksSnapshot[idx]
                            when (val result = BlockOperations.splitOnEnterAtOffset(currentBlock, caretOffset)) {
                                is BlockSplitResult.ExitList -> {
                                    val updated = currentBlocksSnapshot.toMutableList()
                                    updated[idx] = result.convertedBlock
                                    commitBlocks(updated, isStructural = true)
                                    textSyncRevision++
                                    targetCursorOffset = 0
                                    targetFocusBlockId = result.convertedBlock.id
                                }
                                is BlockSplitResult.Split -> {
                                    val updated = currentBlocksSnapshot.toMutableList()
                                    updated[idx] = result.updatedCurrent
                                    updated.add(idx + 1, result.newNextBlock)
                                    commitBlocks(updated, isStructural = true)
                                    // The current block keeps focus until the new one is attached;
                                    // force it to accept the truncated text right now.
                                    textSyncRevision++
                                    targetCursorOffset = 0
                                    targetFocusBlockId = result.newNextBlock.id
                                }
                            }
                        }
                    }

                    override fun onEnterPressed(before: String, after: String) {
                        val currentBlocksSnapshot = flushPendingLocalTyping()
                        val idx = currentBlocksSnapshot.indexOfFirst { it.id == block.id }
                        if (idx != -1) {
                            val currentBlock = currentBlocksSnapshot[idx]
                            when (val result = BlockOperations.splitOnEnter(currentBlock, before, after)) {
                                is BlockSplitResult.ExitList -> {
                                    val updated = currentBlocksSnapshot.toMutableList()
                                    updated[idx] = result.convertedBlock
                                    commitBlocks(updated, isStructural = true)
                                    textSyncRevision++
                                    targetCursorOffset = 0
                                    targetFocusBlockId = result.convertedBlock.id
                                }
                                is BlockSplitResult.Split -> {
                                    val updated = currentBlocksSnapshot.toMutableList()
                                    updated[idx] = result.updatedCurrent
                                    updated.add(idx + 1, result.newNextBlock)
                                    commitBlocks(updated, isStructural = true)
                                    textSyncRevision++
                                    targetCursorOffset = 0
                                    targetFocusBlockId = result.newNextBlock.id
                                }
                            }
                        }
                    }

                    override fun onBackspaceOnEmpty() {
                        val currentBlocksSnapshot = flushPendingLocalTyping()
                        val idx = currentBlocksSnapshot.indexOfFirst { it.id == block.id }
                        if (idx != -1) {
                            when (val result = BlockOperations.handleBackspaceOnEmpty(currentBlocksSnapshot, idx)) {
                                is BlockBackspaceResult.DemoteToParagraph -> {
                                    val updated = currentBlocksSnapshot.toMutableList()
                                    if (idx in updated.indices) {
                                        updated[idx] = result.demotedBlock
                                        commitBlocks(updated, isStructural = true)
                                        targetCursorOffset = 0
                                        targetFocusBlockId = result.demotedBlock.id
                                    }
                                }
                                is BlockBackspaceResult.DeleteEmptyBlock -> {
                                    commitBlocks(result.updatedBlocks, isStructural = true)
                                    targetCursorOffset = result.cursorOffset
                                    targetFocusBlockId = result.previousBlockId
                                }
                                is BlockBackspaceResult.MergeWithPrevious -> {
                                    commitBlocks(result.updatedBlocks, isStructural = true)
                                    targetCursorOffset = result.cursorOffset
                                    targetFocusBlockId = result.previousBlockId
                                }
                                BlockBackspaceResult.NoOp -> {}
                            }
                        }
                    }

                    override fun onExitSpecialBlock() {
                        val nextParagraph = Block.Paragraph(
                            id = UUID.randomUUID().toString(),
                            spans = emptyList(),
                        )
                        val currentBlocksSnapshot = flushPendingLocalTyping()
                        val idx = currentBlocksSnapshot.indexOfFirst { it.id == block.id }
                        if (idx != -1) {
                            val updated = currentBlocksSnapshot.toMutableList()
                            updated.add(idx + 1, nextParagraph)
                            commitBlocks(updated, isStructural = true)
                            targetCursorOffset = 0
                            targetFocusBlockId = nextParagraph.id
                        }
                    }

                    override fun onActiveTableColumnChange(column: Int) {
                        activeTableColumn = column
                    }
                }
            }

            val itemNavigation = remember(block.id) {
                object : BlockNavigationCallbacks {
                    override fun onNavigateToObject(objectId: String) {
                        onNavigateToObject?.invoke(objectId)
                    }

                    override fun onNavigateToTag(tagId: String) {
                        navigateToTagByName(tagId)
                    }

                    override fun onNavigateToDate(date: LocalDate) {
                        onNavigateToDate?.invoke(date)
                    }

                    override fun onTapImage(image: Block.Image) {
                        fullScreenPreviewImage = image
                    }

                    override fun onOpenCanvas(canvas: Block.Canvas) {
                        activeInlineCanvasBlock = canvas
                    }

                    override fun onTapBookmark(bookmark: Block.Bookmark) {
                        val cleanBmUrl = bookmark.url.trimEnd('/')
                        val matchingObj = allActiveObjects.find { obj ->
                            obj.weblinkUrl?.trimEnd('/') == cleanBmUrl
                        }
                        activeInlinePreview = InlineElementPreviewData.WebLink(
                            url = bookmark.url,
                            title = bookmark.title.ifBlank { bookmark.url },
                            description = bookmark.description,
                            linkedObject = matchingObj,
                            onOpenBrowser = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(bookmark.url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                            onOpenInApp = matchingObj?.let { obj ->
                                { onNavigateToObject?.invoke(obj.id) }
                            },
                            onRemoveLink = {
                                removeBlockById(bookmark.id)
                            },
                        )
                    }

                    override fun onTapInlinePill(pill: MarkdownPill) {
                        when (pill.kind) {
                            MarkdownPillKind.MENTION, MarkdownPillKind.WIKILINK -> {
                                val resolvedObj = resolveInlinePillObject(pill.target, allActiveObjects, allTypes)
                                val resolvedType = allTypes.find { it.id == resolvedObj?.typeId }
                                activeInlinePreview = InlineElementPreviewData.ObjectMention(
                                    obj = resolvedObj,
                                    type = resolvedType,
                                    fallbackLabel = pill.label.removePrefix("@"),
                                    onOpen = {
                                        if (resolvedObj != null) {
                                            onNavigateToObject?.invoke(resolvedObj.id)
                                        }
                                    },
                                    onRemove = {
                                        val currentBlocksSnapshot = flushPendingLocalTyping()
                                        val idx = currentBlocksSnapshot.indexOfFirst { it.id == block.id }
                                        if (idx != -1) {
                                            val currentBlock = currentBlocksSnapshot[idx]
                                            val spans = BlockOperations.extractSpans(currentBlock)
                                            val (before, after) = RichSpanEngine.splitAt(spans, pill.transformedRange.first)
                                            val (_, tail) = RichSpanEngine.splitAt(
                                                after,
                                                pill.transformedRange.last - pill.transformedRange.first + 1,
                                            )
                                            val updated = currentBlocksSnapshot.toMutableList()
                                            updated[idx] = BlockOperations.withSpans(
                                                currentBlock,
                                                RichSpanEngine.coalesce(before + tail),
                                            )
                                            commitBlocks(updated, isStructural = true)
                                        }
                                    },
                                )
                            }
                            MarkdownPillKind.TAG -> {
                                val cleanTag = pill.target.removePrefix("#")
                                val tagObj = allTags.find { it.plainTitle.equals(cleanTag, ignoreCase = true) }
                                activeInlinePreview = InlineElementPreviewData.TagPill(
                                    tagName = cleanTag,
                                    tagObj = tagObj,
                                    onOpen = { navigateToTagByName(cleanTag) },
                                    onRemove = {
                                        val currentBlocksSnapshot = flushPendingLocalTyping()
                                        val idx = currentBlocksSnapshot.indexOfFirst { it.id == block.id }
                                        if (idx != -1) {
                                            val currentBlock = currentBlocksSnapshot[idx]
                                            val spans = BlockOperations.extractSpans(currentBlock)
                                            val (before, after) = RichSpanEngine.splitAt(spans, pill.transformedRange.first)
                                            val (_, tail) = RichSpanEngine.splitAt(
                                                after,
                                                pill.transformedRange.last - pill.transformedRange.first + 1,
                                            )
                                            val updated = currentBlocksSnapshot.toMutableList()
                                            updated[idx] = BlockOperations.withSpans(
                                                currentBlock,
                                                RichSpanEngine.coalesce(before + tail),
                                            )
                                            commitBlocks(updated, isStructural = true)
                                        }
                                    },
                                )
                            }
                            MarkdownPillKind.LINK -> {
                                val url = pill.target
                                val cleanUrl = url.trimEnd('/')
                                val matchingObj = allActiveObjects.find { obj ->
                                    obj.weblinkUrl?.trimEnd('/') == cleanUrl
                                }
                                val currentBlocksSnapshot = flushPendingLocalTyping()
                                val idx = currentBlocksSnapshot.indexOfFirst { it.id == block.id }
                                val currentBlock = if (idx != -1) currentBlocksSnapshot[idx] else block
                                activeInlinePreview = InlineElementPreviewData.WebLink(
                                    url = url,
                                    title = BlockOperations.extractSpans(currentBlock).toVisibleString()
                                        .substring(
                                            pill.transformedRange.first.coerceAtLeast(0),
                                            (pill.transformedRange.last + 1).coerceAtMost(
                                                BlockOperations.extractSpans(currentBlock).toVisibleString().length,
                                            ),
                                        ),
                                    linkedObject = matchingObj,
                                    onOpenBrowser = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                    },
                                    onOpenInApp = matchingObj?.let { obj ->
                                        { onNavigateToObject?.invoke(obj.id) }
                                    },
                                    onEditLink = {
                                        focusedBlockId = block.id
                                        focusedSelection = TextRange(
                                            pill.transformedRange.first,
                                            pill.transformedRange.last + 1,
                                        )
                                        isSelectorLinkMode = true
                                        selectorInitialQuery = url
                                        showUniversalSelectorSheet = true
                                    },
                                    onRemoveLink = {
                                        val snapshot = flushPendingLocalTyping()
                                        val i = snapshot.indexOfFirst { it.id == block.id }
                                        if (i != -1) {
                                            val b = snapshot[i]
                                            val spans = BlockOperations.extractSpans(b)
                                            val unlinked = RichSpanEngine.applyLinkToRange(
                                                spans,
                                                pill.transformedRange.first,
                                                pill.transformedRange.last + 1,
                                                null,
                                            )
                                            val updated = snapshot.toMutableList()
                                            updated[i] = BlockOperations.withSpans(b, unlinked)
                                            commitBlocks(updated, isStructural = true)
                                        }
                                    },
                                )
                            }
                            MarkdownPillKind.DATE -> {
                                val cleanIso = pill.target.removePrefix("@").trim().take(10)
                                val dailyId = "daily-$cleanIso"
                                val dailyObj = allActiveObjects.find { it.id == dailyId || it.journalDateIso == cleanIso }
                                val mentionsCount = allActiveObjects.count { o ->
                                    (o.id != dailyId) && (
                                        o.properties.any { (_, v) -> (v as? PropertyValue.DateRange)?.startDate == cleanIso } ||
                                        o.blocks.any { b ->
                                            BlockOperations.extractSpans(b).any { s ->
                                                (s is InlineSpan.Date && s.isoDate.removePrefix("@").trim().take(10) == cleanIso) ||
                                                (s is InlineSpan.Mention && (s.targetObjectId == dailyId || s.targetObjectId == cleanIso))
                                            }
                                        }
                                    )
                                }
                                activeInlinePreview = InlineElementPreviewData.DatePill(
                                    isoDate = cleanIso,
                                    dailyObject = dailyObj,
                                    mentionCount = mentionsCount,
                                    onOpen = {
                                        val parsed = runCatching { LocalDate.parse(cleanIso) }.getOrNull()
                                        if (parsed != null && onNavigateToDate != null) {
                                            onNavigateToDate.invoke(parsed)
                                        } else {
                                            onNavigateToObject?.invoke(dailyId)
                                        }
                                    },
                                    onRemove = {
                                        val currentBlocksSnapshot = flushPendingLocalTyping()
                                        val idx = currentBlocksSnapshot.indexOfFirst { it.id == block.id }
                                        if (idx != -1) {
                                            val currentBlock = currentBlocksSnapshot[idx]
                                            val spans = BlockOperations.extractSpans(currentBlock)
                                            val (before, after) = RichSpanEngine.splitAt(spans, pill.transformedRange.first)
                                            val (_, tail) = RichSpanEngine.splitAt(
                                                after,
                                                pill.transformedRange.last - pill.transformedRange.first + 1,
                                            )
                                            val updated = currentBlocksSnapshot.toMutableList()
                                            updated[idx] = BlockOperations.withSpans(
                                                currentBlock,
                                                RichSpanEngine.coalesce(before + tail),
                                            )
                                            commitBlocks(updated, isStructural = true)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            val itemContext = remember(itemVisualState, itemActions, itemNavigation, blockEnvironment) {
                BlockItemContext(
                    focusRequester = requester,
                    visualState = itemVisualState,
                    actions = itemActions,
                    navigation = itemNavigation,
                    environment = blockEnvironment,
                )
            }

            val isBlockHighlighted = highlightedBlockId == block.id
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(bringIntoViewRequester)
                    .then(
                        if (isBlockHighlighted) {
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppColors.Accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                                .border(
                                    BorderStroke(1.dp, AppColors.Accent.copy(alpha = 0.55f)),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                BlockItemView(
                    block = block,
                    context = itemContext,
                )
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (useLazyLayout) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (headerContent != null) {
                    item(key = "editor-header") {
                        headerContent()
                    }
                }
                if (ownerObjectId != null) {
                    item(key = "editor-tags") {
                        TagsHeader()
                    }
                }
                items(
                    items = visibleBlockItems,
                    key = { it.block.id },
                    contentType = { it.block::class },
                ) { item ->
                    // A block inserted while editing (Enter, paste, a toggle opening) fades in.
                    // Rows reached by scrolling and the first layout never animate; no placement
                    // animation, so typing and drag stay on their fast paths.
                    Box(
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(AppMotion.FADE_MS),
                            placementSpec = null,
                            fadeOutSpec = null,
                        ),
                    ) {
                        BlockRow(item)
                    }
                }
                item(key = "editor-add-block") {
                    AddBlockBox()
                }
            }
        } else {
            if (ownerObjectId != null) {
                TagsHeader()
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                visibleBlockItems.forEach { BlockRow(it) }
                AddBlockBox()
            }
        }

        // Contextual editing toolbar (Dynamic Island single-row + Pattern C panels)
        val toolbarController = LocalEditorToolbarController.current
        val focusedBlock = remember(currentBlocks, focusedBlockId) {
            focusedBlockId?.let { id -> currentBlocks.find { it.id == id } }
        }
        val toolbarContext = remember(focusedBlock) { EditorToolbarContext.forBlock(focusedBlock) }
        val toolbarStrings = editorToolbarStrings()

        // Derived state: only invalidates the composition when the collapsed/expanded nature of the
        // selection changes, not on every caret movement or keystroke.
        val hasNonCollapsedSelection by remember {
            derivedStateOf { focusedSelection?.let { !it.collapsed } == true }
        }
        val effectiveMode = remember(
            explicitToolbarMode,
            selectedBlockIds.size,
            deletedBackupBlocks,
            hasNonCollapsedSelection,
            toolbarContext,
        ) {
            when {
                deletedBackupBlocks != null && explicitToolbarMode == EditorToolbarMode.NOTICE -> EditorToolbarMode.NOTICE
                selectedBlockIds.isNotEmpty() -> EditorToolbarMode.SELECTION
                explicitToolbarMode != null -> explicitToolbarMode!!
                hasNonCollapsedSelection && toolbarContext == EditorToolbarContext.TEXT -> EditorToolbarMode.FORMAT
                toolbarContext == EditorToolbarContext.CODE -> EditorToolbarMode.CODE
                toolbarContext == EditorToolbarContext.MATH -> EditorToolbarMode.MATH
                toolbarContext == EditorToolbarContext.TABLE -> EditorToolbarMode.TABLE
                else -> EditorToolbarMode.NORMAL
            }
        }

        // Callbacks read the latest block/selection state lazily at invocation time. Keeping them
        // stable avoids rebuilding the whole toolbar action graph on every keystroke or caret move.
        val toolbarCallbacks = remember(toolbarStrings, clipboard, coroutineScope) {
            fun updateFocusedBlock(
                requestFocusAfter: Boolean = false,
                isStructural: Boolean = true,
                transform: (Block) -> Block,
            ) {
                val activeList = flushPendingLocalTyping()
                val idx = activeList.indexOfFirst { it.id == focusedBlockId }
                if (idx !in activeList.indices) return
                val updated = activeList.toMutableList()
                val nextBlock = transform(activeList[idx])
                updated[idx] = nextBlock
                commitBlocks(updated, isStructural = isStructural)
                if (requestFocusAfter) {
                    targetFocusBlockId = nextBlock.id
                }
            }

            EditorToolbarCallbacks(
                onToggleHeading = {
                    updateFocusedBlock { block ->
                        val spans = BlockOperations.extractSpans(block)
                        val indent = block.indentLevel
                        when (block) {
                            is Block.Heading -> when (block.level) {
                                HeadingLevel.H1 -> Block.Heading(block.id, HeadingLevel.H2, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                                HeadingLevel.H2 -> Block.Heading(block.id, HeadingLevel.H3, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                                HeadingLevel.H3 -> Block.Heading(block.id, HeadingLevel.H4, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                                HeadingLevel.H4 -> Block.Paragraph(block.id, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                            }
                            else -> Block.Heading(block.id, HeadingLevel.H1, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        }
                    }
                },
                onToggleTodo = {
                    updateFocusedBlock { block ->
                        val spans = BlockOperations.extractSpans(block)
                        val indent = block.indentLevel
                        if (block is Block.Todo) {
                            Block.Paragraph(block.id, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        } else {
                            Block.Todo(block.id, checked = false, spans = spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        }
                    }
                },
                onToggleQuote = {
                    updateFocusedBlock { block ->
                        val spans = BlockOperations.extractSpans(block)
                        val indent = block.indentLevel
                        if (block is Block.Quote) {
                            Block.Paragraph(block.id, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        } else {
                            Block.Quote(block.id, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        }
                    }
                },
                onToggleCodeBlock = {
                    updateFocusedBlock { block ->
                        if (block is Block.Code) {
                            Block.Paragraph(block.id, block.code.toTextSpan(), indentLevel = 0, groupId = block.groupId, blockColor = block.blockColor)
                        } else {
                            Block.Code(
                                id = block.id,
                                code = BlockOperations.extractPlainText(block),
                                language = "javascript",
                                indentLevel = 0,
                                groupId = block.groupId,
                                blockColor = block.blockColor,
                            )
                        }
                    }
                },
                onToggleBulletList = {
                    updateFocusedBlock { block ->
                        val spans = BlockOperations.extractSpans(block)
                        val indent = block.indentLevel
                        if (block is Block.BulletList) {
                            Block.Paragraph(block.id, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        } else {
                            Block.BulletList(block.id, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        }
                    }
                },
                onToggleNumberedList = {
                    updateFocusedBlock { block ->
                        val spans = BlockOperations.extractSpans(block)
                        val indent = block.indentLevel
                        if (block is Block.NumberedList) {
                            Block.Paragraph(block.id, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        } else {
                            Block.NumberedList(block.id, number = 1, spans = spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        }
                    }
                },
                onToggleAlphabeticList = {
                    updateFocusedBlock { block ->
                        val spans = BlockOperations.extractSpans(block)
                        val indent = block.indentLevel
                        if (block is Block.AlphabeticList) {
                            Block.Paragraph(block.id, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        } else {
                            Block.AlphabeticList(block.id, number = 1, spans = spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        }
                    }
                },
                onToggleRomanList = {
                    updateFocusedBlock { block ->
                        val spans = BlockOperations.extractSpans(block)
                        val indent = block.indentLevel
                        if (block is Block.RomanList) {
                            Block.Paragraph(block.id, spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        } else {
                            Block.RomanList(block.id, number = 1, spans = spans, indentLevel = indent, groupId = block.groupId, blockColor = block.blockColor)
                        }
                    }
                },
                onOutdent = {
                    updateFocusedBlock(requestFocusAfter = false) { block ->
                        if (block.indentLevel > 0) {
                            BlockOperations.withIndentLevel(block, block.indentLevel - 1)
                        } else {
                            block
                        }
                    }
                },
                onIndent = {
                    updateFocusedBlock(requestFocusAfter = false) { block ->
                        BlockOperations.withIndentLevel(block, block.indentLevel + 1)
                    }
                },
                onInsertLineBreak = {
                    updateFocusedBlock(requestFocusAfter = false, isStructural = false) { current ->
                        val spans = BlockOperations.extractSpans(current)
                        val visible = spans.toVisibleString()
                        val caret = (focusedSelection?.start ?: visible.length).coerceIn(0, visible.length)
                        val (before, after) = RichSpanEngine.splitAt(spans, caret)
                        val merged = RichSpanEngine.coalesce(before + listOf(InlineSpan.Text("\n")) + after)
                        targetCursorOffset = caret + 1
                        BlockOperations.withSpans(current, merged)
                    }
                },
                onToggleFormat = { tokenId, _, _ ->
                    val activeList = flushPendingLocalTyping()
                    val idx = activeList.indexOfFirst { it.id == focusedBlockId }
                    if (idx in activeList.indices) {
                        val current = activeList[idx]
                        val spans = BlockOperations.extractSpans(current)
                        val visible = spans.toVisibleString()
                        val selection = liveSelectionRef[0] ?: focusedSelection
                        val start = minOf(
                            (selection?.start ?: visible.length).coerceIn(0, visible.length),
                            (selection?.end ?: visible.length).coerceIn(0, visible.length),
                        )
                        val end = maxOf(
                            (selection?.start ?: visible.length).coerceIn(0, visible.length),
                            (selection?.end ?: visible.length).coerceIn(0, visible.length),
                        )
                        if (start < end) {
                            val toggled = RichSpanEngine.toggleMarkInRange(spans, start, end, tokenId)
                            updateFocusedBlock(requestFocusAfter = false) {
                                BlockOperations.withSpans(it, toggled)
                            }
                        } else {
                            val probeStart = if (start > 0) start - 1 else 0
                            val probeEnd = if (start > 0) start else 1.coerceAtMost(visible.length)
                            val naturalMarksAtCursor = if (probeStart < probeEnd && visible.isNotEmpty()) {
                                RichSpanEngine.activeMarksInRange(spans, probeStart, probeEnd)
                            } else {
                                emptySet()
                            }
                            val currentTokens = activeTypingFormats ?: naturalMarksAtCursor
                            val isCurrentlyActive = tokenId in currentTokens
                            activeTypingFormats = if (isCurrentlyActive) {
                                currentTokens - tokenId
                            } else {
                                currentTokens + tokenId
                            }
                        }
                    }
                },
                onOpenSlash = {
                    activeKeyboardPanel = EditorKeyboardPanelType.BLOCKS
                    keyboardController?.hide()
                },
                onOpenMention = {
                    isSelectorLinkMode = false
                    selectorInitialQuery = ""
                    showUniversalSelectorSheet = true
                },
                onOpenTag = {
                    isHeaderTagPicker = false
                    tagInitialQuery = ""
                    showTagSheet = true
                },
                onOpenCalendar = {
                    activeKeyboardPanel = EditorKeyboardPanelType.CALENDAR
                    keyboardController?.hide()
                },
                onOpenLinkPicker = {
                    isSelectorLinkMode = true
                    selectorInitialQuery = ""
                    showUniversalSelectorSheet = true
                },
                onOpenBlockTypeMode = {
                    explicitToolbarMode = EditorToolbarMode.BLOCK_TYPE
                },
                onOpenFormatMode = {
                    explicitToolbarMode = EditorToolbarMode.FORMAT
                },
                onOpenColorMode = {
                    isBgColorSubmode = false
                    explicitToolbarMode = EditorToolbarMode.COLOR
                },
                onUndo = ::performUndo,
                onRedo = ::performRedo,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                onExitSpecialBlock = {
                    val idx = currentBlocks.indexOfFirst { it.id == focusedBlockId }
                    if (idx in currentBlocks.indices) {
                        val nextParagraph = Block.Paragraph(UUID.randomUUID().toString(), emptyList())
                        val updated = currentBlocks.toMutableList()
                        updated.add(idx + 1, nextParagraph)
                        commitBlocks(updated, isStructural = true)
                        targetCursorOffset = 0
                        targetFocusBlockId = nextParagraph.id
                    }
                },
                onInsertMathSnippet = { snippet ->
                    updateFocusedBlock(requestFocusAfter = false) { block ->
                        if (block is Block.Math) block.copy(latex = block.latex + snippet) else block
                    }
                },
                onInsertCodeSnippet = { snippet ->
                    updateFocusedBlock(requestFocusAfter = false) { block ->
                        if (block is Block.Code) block.copy(code = block.code + snippet) else block
                    }
                },
                onCopyCode = {
                    val codeBlock = currentBlocks.firstOrNull { it.id == focusedBlockId } as? Block.Code
                    if (codeBlock != null) {
                        coroutineScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", codeBlock.code)))
                        }
                    }
                },
                onTableAddColumn = {
                    updateFocusedBlock(requestFocusAfter = false) { block ->
                        if (block is Block.Table) {
                            block.copy(
                                rows = block.rows.map { it + "" },
                                alignments = block.alignments + "left",
                            )
                        } else block
                    }
                },
                onTableAddRow = {
                    updateFocusedBlock(requestFocusAfter = false) { block ->
                        if (block is Block.Table) {
                            val colCount = block.rows.firstOrNull()?.size ?: 2
                            block.copy(rows = block.rows + listOf(List(colCount) { "" }))
                        } else block
                    }
                },
                onTableDeleteColumn = {
                    updateFocusedBlock(requestFocusAfter = false) { block ->
                        if (block is Block.Table && (block.rows.firstOrNull()?.size ?: 0) > 1) {
                            block.copy(
                                rows = block.rows.map { if (it.size > 1) it.dropLast(1) else it },
                                alignments = if (block.alignments.size > 1) block.alignments.dropLast(1) else block.alignments,
                            )
                        } else block
                    }
                },
                onTableDeleteRow = {
                    updateFocusedBlock(requestFocusAfter = false) { block ->
                        if (block is Block.Table && block.rows.size > 1) block.copy(rows = block.rows.dropLast(1)) else block
                    }
                },
                onTableAlign = { align ->
                    updateFocusedBlock(requestFocusAfter = false) { block ->
                        if (block is Block.Table) {
                            val colCount = block.rows.firstOrNull()?.size ?: 2
                            val currentAligns = (0 until colCount).map { c ->
                                block.alignments.getOrNull(c) ?: "left"
                            }.toMutableList()
                            val targetCol = activeTableColumn.coerceIn(0, (colCount - 1).coerceAtLeast(0))
                            if (targetCol in currentAligns.indices) {
                                currentAligns[targetCol] = align
                            }
                            block.copy(alignments = currentAligns)
                        } else block
                    }
                },
                onTableClear = {
                    updateFocusedBlock(requestFocusAfter = false) { block ->
                        if (block is Block.Table) block.copy(rows = block.rows.map { row -> row.map { "" } }) else block
                    }
                },
            )
        }

        // Detect active inline formatting at current caret/selection + active typing modes + block type.
        // Derived state only invalidates the composition when the resulting token set actually changes,
        // so moving the caret inside a styled run no longer recomposes the whole editor.
        val activeTokenIds by remember(focusedBlockId, activeTypingFormats) {
            derivedStateOf {
                val block = focusedBlockId?.let { id -> currentBlocks.find { it.id == id } }
                buildSet {
                    if (activeTypingFormats != null) {
                        addAll(activeTypingFormats!!)
                    } else if (block != null) {
                        val spans = BlockOperations.extractSpans(block)
                        val visible = spans.toVisibleString()
                        val sel = focusedSelection
                        if (sel != null && visible.isNotEmpty()) {
                            val s = minOf(sel.start, sel.end).coerceIn(0, visible.length)
                            val e = maxOf(sel.start, sel.end).coerceIn(0, visible.length)
                            val probeStart = if (s < e) s else (s - 1).coerceAtLeast(0)
                            val probeEnd = if (s < e) e else s.coerceAtLeast(1)
                            if (probeStart < probeEnd) {
                                addAll(RichSpanEngine.activeMarksInRange(spans, probeStart, probeEnd))
                            }
                        }
                    }
                    when (block) {
                        is Block.BulletList -> add(BlockTypeKeys.BULLET)
                        is Block.NumberedList -> add(BlockTypeKeys.NUMBERED)
                        is Block.AlphabeticList -> add(BlockTypeKeys.ALPHABETIC)
                        is Block.RomanList -> add(BlockTypeKeys.ROMAN)
                        is Block.Todo -> add(BlockTypeKeys.TODO)
                        is Block.Quote -> add(BlockTypeKeys.QUOTE)
                        is Block.Code -> {
                            add(BlockTypeKeys.CODE)
                            add("code_block")
                        }
                        else -> Unit
                    }
                }
            }
        }

        val toolbarActions = remember(toolbarContext, toolbarCallbacks, toolbarStrings, activeTokenIds) {
            buildEditorToolbarActions(toolbarContext, toolbarStrings, toolbarCallbacks, activeTokenIds)
        }

        // Active color detection for the current selection / caret. Same derived-state treatment:
        // caret movement inside a uniformly colored run does not recompose the editor.
        val activeRangeColors by remember(
            focusedBlockId,
            activeTextColor,
            activeBgColor,
            hasExplicitTextColor,
            hasExplicitBgColor,
        ) {
            derivedStateOf {
                val block = focusedBlockId?.let { id -> currentBlocks.find { it.id == id } }
                val naturalColors = if (block != null) {
                    val spans = BlockOperations.extractSpans(block)
                    val visible = spans.toVisibleString()
                    val sel = focusedSelection
                    if (sel != null && visible.isNotEmpty()) {
                        val s = minOf(sel.start, sel.end).coerceIn(0, visible.length)
                        val e = maxOf(sel.start, sel.end).coerceIn(0, visible.length)
                        RichSpanEngine.colorsInRange(spans, s, e)
                    } else {
                        null to null
                    }
                } else {
                    null to null
                }

                val effectiveTCol = if (hasExplicitTextColor) activeTextColor else naturalColors.first
                val effectiveBCol = if (hasExplicitBgColor) activeBgColor else naturalColors.second
                effectiveTCol to effectiveBCol
            }
        }
        val activeRangeTextColor = activeRangeColors.first
        val activeRangeBgColor = activeRangeColors.second

        // Build Dynamic Island single-row composable for specialized modes
        val dynamicRowComposable: (@Composable () -> Unit)? = when (effectiveMode) {
            EditorToolbarMode.NORMAL,
            EditorToolbarMode.CODE,
            EditorToolbarMode.MATH,
            EditorToolbarMode.TABLE,
            -> null

            EditorToolbarMode.FORMAT -> {
                {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "back_normal",
                                title = stringResource(R.string.back),
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                onClick = { explicitToolbarMode = EditorToolbarMode.NORMAL },
                            ),
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "fmt_line_break",
                                title = stringResource(R.string.action_line_break),
                                icon = Icons.AutoMirrored.Filled.KeyboardReturn,
                                tint = AppColors.Accent,
                                onClick = toolbarCallbacks.onInsertLineBreak,
                            ),
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "fmt_bold",
                                title = stringResource(R.string.action_bold),
                                icon = Icons.Filled.FormatBold,
                                isActive = "bold" in activeTokenIds,
                                onClick = { toolbarCallbacks.onToggleFormat("bold", "", "") },
                            ),
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "fmt_italic",
                                title = stringResource(R.string.action_italic),
                                icon = Icons.Filled.FormatItalic,
                                isActive = "italic" in activeTokenIds,
                                onClick = { toolbarCallbacks.onToggleFormat("italic", "", "") },
                            ),
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "fmt_underline",
                                title = stringResource(R.string.action_underline),
                                icon = Icons.Filled.FormatUnderlined,
                                isActive = "underline" in activeTokenIds,
                                onClick = { toolbarCallbacks.onToggleFormat("underline", "", "") },
                            ),
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "fmt_strike",
                                title = stringResource(R.string.action_strikethrough),
                                icon = Icons.Filled.FormatStrikethrough,
                                isActive = "strike" in activeTokenIds,
                                onClick = { toolbarCallbacks.onToggleFormat("strike", "", "") },
                            ),
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "fmt_code",
                                title = stringResource(R.string.action_inline_code),
                                icon = Icons.Filled.Code,
                                isActive = "inline_code" in activeTokenIds,
                                onClick = { toolbarCallbacks.onToggleFormat("inline_code", "", "") },
                            ),
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "fmt_text_color",
                                title = stringResource(R.string.action_text_color),
                                icon = Icons.Filled.FormatColorText,
                                tint = activeRangeTextColor?.let { EditorColorPalette.resolveTextColor(it) } ?: AppColors.Accent,
                                isActive = activeRangeTextColor != null,
                                onClick = {
                                    isBgColorSubmode = false
                                    explicitToolbarMode = EditorToolbarMode.COLOR
                                },
                            ),
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "fmt_bg_color",
                                title = stringResource(R.string.action_bg_color),
                                icon = Icons.Filled.FormatColorFill,
                                tint = activeRangeBgColor?.let { EditorColorPalette.resolveTextColor(it) } ?: AppColors.TextSecondary,
                                isActive = activeRangeBgColor != null || "highlight" in activeTokenIds,
                                onClick = {
                                    isBgColorSubmode = true
                                    explicitToolbarMode = EditorToolbarMode.COLOR
                                },
                            ),
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "fmt_link",
                                title = stringResource(R.string.action_link),
                                icon = Icons.Filled.Link,
                                tint = AppColors.Accent,
                                onClick = toolbarCallbacks.onOpenLinkPicker,
                            ),
                        )
                    }
                }
            }

            EditorToolbarMode.COLOR -> {
                {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "back_fmt",
                                title = stringResource(R.string.back),
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                onClick = { explicitToolbarMode = EditorToolbarMode.FORMAT },
                            ),
                        )
                        ToolbarChip(
                            label = stringResource(R.string.action_text_color),
                            leadingIcon = Icons.Filled.FormatColorText,
                            isAccent = !isBgColorSubmode,
                            isActive = !isBgColorSubmode,
                            onClick = { isBgColorSubmode = false },
                        )
                        ToolbarChip(
                            label = stringResource(R.string.action_bg_color),
                            leadingIcon = Icons.Filled.FormatColorFill,
                            isAccent = isBgColorSubmode,
                            isActive = isBgColorSubmode,
                            onClick = { isBgColorSubmode = true },
                        )
                        ToolbarChip(
                            label = stringResource(R.string.action_clear_color),
                            onClick = {
                                val idx = currentBlocks.indexOfFirst { it.id == focusedBlockId }
                                if (idx in currentBlocks.indices) {
                                    val b = currentBlocks[idx]
                                    val spans = BlockOperations.extractSpans(b)
                                    val visible = spans.toVisibleString()
                                    val sel = focusedSelection
                                    val s = minOf(sel?.start ?: 0, sel?.end ?: 0).coerceIn(0, visible.length)
                                    val e = maxOf(sel?.start ?: 0, sel?.end ?: 0).coerceIn(0, visible.length)
                                    if (s < e) {
                                        val nextSpans = RichSpanEngine.applyColorToRange(spans, s, e, null, isBgColorSubmode)
                                        val updated = currentBlocks.toMutableList()
                                        updated[idx] = BlockOperations.withSpans(b, nextSpans)
                                        commitBlocks(updated, isStructural = true)
                                    } else {
                                        activeTextColor = null
                                        hasExplicitTextColor = true
                                        activeBgColor = null
                                        hasExplicitBgColor = true
                                    }
                                }
                            },
                        )
                        SpanColorPalette.ALL_NAMED.forEach { colorName ->
                            val textColor = EditorColorPalette.resolveTextColor(colorName) ?: AppColors.Accent
                            val bgColor = EditorColorPalette.resolveBackgroundColor(colorName) ?: AppColors.SurfaceElevated
                            val isCurrentColorActive = if (isBgColorSubmode) {
                                activeRangeBgColor.equals(colorName, ignoreCase = true)
                            } else {
                                activeRangeTextColor.equals(colorName, ignoreCase = true)
                            }

                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isBgColorSubmode) bgColor.copy(alpha = 0.85f) else AppColors.SurfaceElevated,
                                        CircleShape,
                                    )
                                    .border(
                                        width = if (isCurrentColorActive) 2.dp else 1.dp,
                                        color = if (isCurrentColorActive) AppColors.Accent else if (isBgColorSubmode) textColor.copy(alpha = 0.6f) else textColor.copy(alpha = 0.5f),
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        val idx = currentBlocks.indexOfFirst { it.id == focusedBlockId }
                                        if (idx in currentBlocks.indices) {
                                            val b = currentBlocks[idx]
                                            val spans = BlockOperations.extractSpans(b)
                                            val visible = spans.toVisibleString()
                                            val sel = focusedSelection
                                            val s = minOf(sel?.start ?: 0, sel?.end ?: 0).coerceIn(0, visible.length)
                                            val e = maxOf(sel?.start ?: 0, sel?.end ?: 0).coerceIn(0, visible.length)
                                            if (s < e) {
                                                val targetColor = if (isCurrentColorActive) null else colorName
                                                val nextSpans = RichSpanEngine.applyColorToRange(
                                                    spans,
                                                    s,
                                                    e,
                                                    targetColor,
                                                    isBgColorSubmode,
                                                )
                                                val updated = currentBlocks.toMutableList()
                                                updated[idx] = BlockOperations.withSpans(b, nextSpans)
                                                commitBlocks(updated, isStructural = true)
                                            } else if (isBgColorSubmode) {
                                                if (isCurrentColorActive) {
                                                    activeBgColor = null
                                                    hasExplicitBgColor = true
                                                } else {
                                                    activeBgColor = colorName
                                                    hasExplicitBgColor = true
                                                }
                                                activeTextColor = null
                                                hasExplicitTextColor = true
                                            } else {
                                                if (isCurrentColorActive) {
                                                    activeTextColor = null
                                                    hasExplicitTextColor = true
                                                } else {
                                                    activeTextColor = colorName
                                                    hasExplicitTextColor = true
                                                }
                                                activeBgColor = null
                                                hasExplicitBgColor = true
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isBgColorSubmode) {
                                    if (isCurrentColorActive) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "A",
                                        color = textColor,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            EditorToolbarMode.BLOCK_TYPE -> {
                {
                    val typeChips = listOf(
                        Triple(BlockTypeKeys.PARAGRAPH, "¶", stringResource(R.string.block_type_paragraph)),
                        Triple(BlockTypeKeys.H1, "H1", stringResource(R.string.block_type_h1)),
                        Triple(BlockTypeKeys.H2, "H2", stringResource(R.string.block_type_h2)),
                        Triple(BlockTypeKeys.H3, "H3", stringResource(R.string.block_type_h3)),
                        Triple(BlockTypeKeys.H4, "H4", stringResource(R.string.block_type_h4)),
                        Triple(BlockTypeKeys.BULLET, "•", stringResource(R.string.block_type_bullet)),
                        Triple(BlockTypeKeys.NUMBERED, "1.", stringResource(R.string.block_type_numbered)),
                        Triple(BlockTypeKeys.ALPHABETIC, "a.", stringResource(R.string.block_type_alphabetic)),
                        Triple(BlockTypeKeys.ROMAN, "Ⅰ.", stringResource(R.string.block_type_roman)),
                        Triple(BlockTypeKeys.TODO, "☑", stringResource(R.string.block_type_todo)),
                        Triple(BlockTypeKeys.TOGGLE, "▶", stringResource(R.string.block_type_toggle)),
                        Triple(BlockTypeKeys.QUOTE, "❝", stringResource(R.string.block_type_quote)),
                        Triple(BlockTypeKeys.CALLOUT, "💡", stringResource(R.string.block_type_callout)),
                        Triple(BlockTypeKeys.GROUP, "▣", stringResource(R.string.block_type_group)),
                        Triple(BlockTypeKeys.CODE, "{ }", stringResource(R.string.block_type_code)),
                        Triple(BlockTypeKeys.MATH, "∑", stringResource(R.string.block_type_math)),
                        Triple(BlockTypeKeys.TABLE, "⊞", stringResource(R.string.block_type_table)),
                        Triple(BlockTypeKeys.DIVIDER, "—", stringResource(R.string.block_type_divider)),
                        Triple(BlockTypeKeys.BOOKMARK, "🔖", stringResource(R.string.block_type_bookmark)),
                    ).filter { (id, _, label) ->
                        slashFilterQuery.isBlank() ||
                            id.contains(slashFilterQuery, ignoreCase = true) ||
                            label.contains(slashFilterQuery, ignoreCase = true)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "back_block_type",
                                title = stringResource(R.string.back),
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                onClick = {
                                    slashFilterQuery = ""
                                    explicitToolbarMode = null
                                },
                            ),
                        )
                        typeChips.forEach { (id, glyph, label) ->
                            ToolbarChip(
                                label = "$glyph $label",
                                onClick = { selectOrConvertBlockType(id) },
                            )
                        }
                    }
                }
            }

            EditorToolbarMode.RESULTS_MENTION -> {
                {
                    val q = liveMentionQuery.orEmpty().trim()
                    val today = LocalDate.now()
                    val todayLabel = stringResource(R.string.date_today)
                    val tomorrowLabel = stringResource(R.string.date_tomorrow)
                    val yesterdayLabel = stringResource(R.string.date_yesterday)
                    val untitledLabel = stringResource(R.string.untitled_object)

                    val parsed = remember(q, allTypes, localizedTypeAliases) {
                        parseMentionQuery(q, allTypes, localizedTypeAliases)
                    }
                    val matchingTypes = remember(q, parsed, allTypes, localizedTypeAliases, recentTypeIds) {
                        if (parsed.hasTypeSlash) {
                            emptyList()
                        } else {
                            matchingTypesForMentionPrefix(q, allTypes, localizedTypeAliases, recentTypeIds)
                                .take(MENTION_MAX_TYPES)
                        }
                    }

                    val allMatchingObjects = remember(q, parsed, allActiveObjects, allTags) {
                        if (parsed.hasTypeSlash && parsed.resolvedType != null) {
                            val targetCanon = parsed.resolvedType.id
                            allActiveObjects
                                .filter { obj ->
                                    obj.typeId == targetCanon &&
                                        matchesObjectMentionQuery(obj, parsed.nameQuery, allTags)
                                }
                                .sortedByDescending { it.updatedAt }
                        } else if (q.isBlank()) {
                            allActiveObjects.sortedByDescending { it.updatedAt }
                        } else {
                            allActiveObjects
                                .filter { obj -> matchesObjectMentionQuery(obj, parsed.rawQuery, allTags) }
                                .sortedByDescending { it.updatedAt }
                        }
                    }
                    val matchingObjects = remember(allMatchingObjects) {
                        allMatchingObjects.take(MENTION_MAX_OBJECTS)
                    }
                    val effectiveCreateName = remember(parsed, q) {
                        if (parsed.hasTypeSlash && parsed.resolvedType != null) parsed.nameQuery else q
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "back_mention",
                                title = stringResource(R.string.back),
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                onClick = {
                                    liveMentionQuery = null
                                    explicitToolbarMode = null
                                },
                            ),
                        )

                        // Active @tipo/ filter badge + "Gallery (N)" / "View all (N)" quick sheet trigger
                        if (parsed.hasTypeSlash && parsed.resolvedType != null) {
                            val resolvedType = parsed.resolvedType
                            val isImageType = resolvedType.id == DefaultTypes.IMAGE.id
                            val openSheetLabel = if (isImageType) {
                                stringResource(R.string.mention_open_gallery, allMatchingObjects.size)
                            } else {
                                stringResource(R.string.mention_open_list, allMatchingObjects.size)
                            }
                            ToolbarChip(
                                label = openSheetLabel,
                                leadingEmoji = resolvedType.icon.emoji ?: "🔍",
                                isAccent = true,
                                isActive = true,
                                onClick = {
                                    isSelectorLinkMode = false
                                    selectorInitialTypeId = resolvedType.id
                                    selectorInitialQuery = parsed.nameQuery
                                    showUniversalSelectorSheet = true
                                },
                            )
                        } else {
                            // @tipo/ filter chips so user can filter by type even if they don't remember the object name
                            for (type in matchingTypes) {
                                val localizedKeyword = preferredTypeMentionKeyword(type, localizedTypeAliases)
                                ToolbarChip(
                                    label = "@$localizedKeyword/",
                                    leadingEmoji = type.icon.emoji ?: "📄",
                                    isAccent = true,
                                    onClick = { applyMentionTypePrefix(type) },
                                )
                            }
                        }

                        // Matching objects (with real image thumbnails and audio durations!)
                        for (obj in matchingObjects) {
                            val canonType = obj.typeId
                            when (canonType) {
                                DefaultTypes.IMAGE.id -> {
                                    MentionImageToolbarChip(
                                        obj = obj,
                                        allTags = allTags,
                                        untitledLabel = untitledLabel,
                                        onClick = { applySelectedMention(obj) },
                                    )
                                }
                                DefaultTypes.AUDIO.id -> {
                                    val dur = formatDurationSeconds(extractAudioDurationSeconds(obj).coerceAtLeast(1))
                                    val titleText = obj.plainTitle.ifBlank { untitledLabel }
                                    ToolbarChip(
                                        label = "$titleText ($dur)",
                                        leadingEmoji = "🎙️",
                                        onClick = { applySelectedMention(obj) },
                                    )
                                }
                                else -> {
                                    val emoji = obj.icon?.emoji
                                        ?: allTypes.find { it.id == canonType }?.icon?.emoji
                                        ?: "📄"
                                    ToolbarChip(
                                        label = obj.plainTitle.ifBlank { untitledLabel },
                                        leadingEmoji = emoji,
                                        onClick = { applySelectedMention(obj) },
                                    )
                                }
                            }
                        }

                        if (!parsed.hasTypeSlash) {
                            // Date chips driven by localized R.string.date_* resources (accent-insensitive)
                            val normQ = q.stripMentionAccents()
                            if (normQ.isBlank() ||
                                todayLabel.stripMentionAccents().startsWith(normQ) ||
                                "today".startsWith(normQ)
                            ) {
                                ToolbarChip(
                                    label = todayLabel,
                                    leadingEmoji = "📅",
                                    isAccent = true,
                                    onClick = {
                                        applySelectedInlineDate(today.format(DateTimeFormatter.ISO_LOCAL_DATE))
                                    },
                                )
                            }
                            if (normQ.isBlank() ||
                                tomorrowLabel.stripMentionAccents().startsWith(normQ) ||
                                "tomorrow".startsWith(normQ)
                            ) {
                                ToolbarChip(
                                    label = tomorrowLabel,
                                    leadingEmoji = "📅",
                                    onClick = {
                                        applySelectedInlineDate(today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))
                                    },
                                )
                            }
                            if (normQ.isNotBlank() &&
                                (yesterdayLabel.stripMentionAccents().startsWith(normQ) ||
                                    "yesterday".startsWith(normQ))
                            ) {
                                ToolbarChip(
                                    label = yesterdayLabel,
                                    leadingEmoji = "📅",
                                    onClick = {
                                        applySelectedInlineDate(today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))
                                    },
                                )
                            }
                        }

                        if (effectiveCreateName.isNotBlank()) {
                            ToolbarChip(
                                label = parsed.resolvedType?.let { type ->
                                    stringResource(R.string.mention_create_in_type, effectiveCreateName, type.name)
                                } ?: stringResource(R.string.mention_create_named, effectiveCreateName),
                                isAccent = true,
                                isActive = allMatchingObjects.isEmpty(),
                                onClick = {
                                    val preselectedType = parsed.resolvedType
                                    if (preselectedType != null) {
                                        val canonical = preselectedType.id
                                        if (canonical == DefaultTypes.IMAGE.id) {
                                            pendingMentionMediaTitle = effectiveCreateName
                                            mentionImagePicker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                            )
                                        } else if (canonical == DefaultTypes.AUDIO.id) {
                                            pendingMentionMediaTitle = effectiveCreateName
                                            showMentionAudioSheet = true
                                        } else {
                                            val newObj = PkmObject(
                                                id = UUID.randomUUID().toString(),
                                                typeId = canonical,
                                                title = effectiveCreateName.toTextSpan(),
                                                icon = preselectedType.icon,
                                            )
                                            coroutineScope.launch {
                                                repository?.saveObject(newObj)
                                            }
                                            applySelectedMention(newObj)
                                        }
                                    } else {
                                        explicitToolbarMode = EditorToolbarMode.CREATE_OBJECT_AS
                                    }
                                },
                            )
                        }

                        // `⌄` button to open Pattern E Half-height Universal Object Selector Sheet
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "more_mentions",
                                title = stringResource(R.string.action_more_results),
                                icon = Icons.Filled.KeyboardArrowDown,
                                tint = AppColors.Accent,
                                onClick = {
                                    isSelectorLinkMode = false
                                    selectorInitialTypeId = parsed.resolvedType?.id
                                    selectorInitialQuery = if (parsed.hasTypeSlash && parsed.resolvedType != null) {
                                        parsed.nameQuery
                                    } else {
                                        q
                                    }
                                    showUniversalSelectorSheet = true
                                },
                            ),
                        )
                    }
                }
            }

            EditorToolbarMode.CREATE_OBJECT_AS -> {
                {
                    val rawQ = liveMentionQuery.orEmpty().trim()
                    val parsedQ = remember(rawQ, allTypes, localizedTypeAliases) {
                        parseMentionQuery(rawQ, allTypes, localizedTypeAliases)
                    }
                    val q = (if (parsedQ.hasTypeSlash && parsedQ.resolvedType != null) parsedQ.nameQuery else rawQ)
                        .ifBlank { stringResource(R.string.untitled_object) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "back_create_as",
                                title = stringResource(R.string.back),
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                onClick = { explicitToolbarMode = EditorToolbarMode.RESULTS_MENTION },
                            ),
                        )
                        Text(
                            text = stringResource(R.string.action_create_as_prefix, q),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextSecondary,
                        )
                        matchingTypesForMentionPrefix("", allTypes, localizedTypeAliases, recentTypeIds).forEach { type ->
                            ToolbarChip(
                                label = type.name,
                                leadingEmoji = type.icon.emoji ?: "📄",
                                isAccent = true,
                                onClick = {
                                    val canonical = type.id
                                    if (canonical == DefaultTypes.IMAGE.id) {
                                        pendingMentionMediaTitle = q
                                        mentionImagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    } else if (canonical == DefaultTypes.AUDIO.id) {
                                        pendingMentionMediaTitle = q
                                        showMentionAudioSheet = true
                                    } else {
                                        val newObj = PkmObject(
                                            id = UUID.randomUUID().toString(),
                                            typeId = canonical,
                                            title = q.toTextSpan(),
                                            icon = type.icon,
                                        )
                                        coroutineScope.launch {
                                            repository?.saveObject(newObj)
                                        }
                                        applySelectedMention(newObj)
                                    }
                                },
                            )
                        }
                        ToolbarChip(
                            label = stringResource(R.string.action_other_type),
                            onClick = {
                                isSelectorLinkMode = false
                                selectorInitialTypeId = parsedQ.resolvedType?.id
                                selectorInitialQuery = q
                                showUniversalSelectorSheet = true
                            },
                        )
                    }
                }
            }

            EditorToolbarMode.RESULTS_TAG -> {
                {
                    val q = liveTagQuery.orEmpty().trim()
                    val matchingTags = remember(q, allTags) {
                        if (q.isBlank()) {
                            allTags.take(6)
                        } else {
                            allTags.filter { it.plainTitle.contains(q, ignoreCase = true) }.take(6)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "back_tag",
                                title = stringResource(R.string.back),
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                onClick = {
                                    liveTagQuery = null
                                    explicitToolbarMode = null
                                },
                            ),
                        )
                        matchingTags.forEach { tagObj ->
                            val tagColor = TagPalette.getTextColor(tagObj.plainTitle, tagObj.icon?.colorHex)
                            ToolbarChip(
                                label = "#${tagObj.plainTitle}",
                                chipColor = tagColor,
                                onClick = {
                                    applySelectedInlineTag(tagObj.plainTitle, tagObj.icon?.colorHex)
                                },
                            )
                        }
                        if (q.isNotBlank() && matchingTags.none { it.plainTitle.equals(q, ignoreCase = true) }) {
                            ToolbarChip(
                                label = stringResource(R.string.action_create_tag_chip, q),
                                isAccent = true,
                                onClick = {
                                    applySelectedInlineTag(q)
                                },
                            )
                        }
                    }
                }
            }

            EditorToolbarMode.SELECTION -> {
                {
                    val selectedBlocks = currentBlocks.filter { it.id in selectedBlockIds }
                    val singleImage = selectedBlocks.singleOrNull() as? Block.Image
                    val allGrouped = selectedBlocks.isNotEmpty() && selectedBlocks.all { !it.groupId.isNullOrBlank() }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "close_selection",
                                title = stringResource(R.string.back),
                                icon = Icons.Filled.Close,
                                onClick = {
                                    selectedBlockIds.clear()
                                    explicitToolbarMode = null
                                },
                            ),
                        )
                        ToolbarChip(
                            label = stringResource(R.string.selection_count_badge, selectedBlockIds.size),
                            isAccent = true,
                            onClick = {},
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "sel_up",
                                title = stringResource(R.string.action_move_up),
                                icon = Icons.Filled.KeyboardArrowUp,
                                onClick = {
                                    commitBlocks(
                                        BlockOperations.moveBlocks(currentBlocks, selectedBlockIds.toSet(), -1),
                                        isStructural = true,
                                    )
                                },
                            ),
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "sel_down",
                                title = stringResource(R.string.action_move_down),
                                icon = Icons.Filled.KeyboardArrowDown,
                                onClick = {
                                    commitBlocks(
                                        BlockOperations.moveBlocks(currentBlocks, selectedBlockIds.toSet(), 1),
                                        isStructural = true,
                                    )
                                },
                            ),
                        )
                        if (singleImage != null) {
                            listOf(
                                0.25f to stringResource(R.string.image_width_25),
                                0.50f to stringResource(R.string.image_width_50),
                                0.75f to stringResource(R.string.image_width_75),
                                1.00f to stringResource(R.string.image_width_100),
                            ).forEach { (w, label) ->
                                ToolbarChip(
                                    label = label,
                                    isActive = singleImage.widthPercent == w,
                                    onClick = {
                                        val idx = currentBlocks.indexOfFirst { it.id == singleImage.id }
                                        if (idx in currentBlocks.indices) {
                                            val updated = currentBlocks.toMutableList()
                                            updated[idx] = singleImage.copy(widthPercent = w)
                                            commitBlocks(updated, isStructural = true)
                                        }
                                    },
                                )
                            }
                        }
                        ToolbarChip(
                            label = if (allGrouped) {
                                stringResource(R.string.action_ungroup_blocks)
                            } else {
                                stringResource(R.string.action_group_blocks)
                            },
                            leadingEmoji = "▣",
                            onClick = {
                                commitBlocks(
                                    BlockOperations.toggleGroupBlocks(currentBlocks, selectedBlockIds.toSet()),
                                    isStructural = true,
                                )
                            },
                        )
                        ToolbarChip(
                            label = stringResource(R.string.action_duplicate_block),
                            leadingIcon = Icons.Filled.ContentCopy,
                            onClick = {
                                val nextBlocks = BlockOperations.duplicateBlocks(
                                    currentBlocks,
                                    selectedBlockIds.toSet(),
                                )
                                commitBlocks(nextBlocks, isStructural = true)
                            },
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "sel_indent",
                                title = stringResource(R.string.kb_action_indent),
                                icon = Icons.AutoMirrored.Filled.FormatIndentIncrease,
                                onClick = {
                                    commitBlocks(
                                        BlockOperations.indentBlocks(currentBlocks, selectedBlockIds.toSet(), 1),
                                        isStructural = true,
                                    )
                                },
                            ),
                        )
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "sel_outdent",
                                title = stringResource(R.string.kb_action_outdent),
                                icon = Icons.AutoMirrored.Filled.FormatIndentDecrease,
                                onClick = {
                                    commitBlocks(
                                        BlockOperations.indentBlocks(currentBlocks, selectedBlockIds.toSet(), -1),
                                        isStructural = true,
                                    )
                                },
                            ),
                        )
                        ToolbarChip(
                            label = stringResource(R.string.action_copy_markdown),
                            onClick = {
                                val md = SyncContractCodec.blocksToMarkdown(selectedBlocks)
                                coroutineScope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("markdown", md)))
                                }
                                selectedBlockIds.clear()
                                explicitToolbarMode = null
                            },
                        )
                        ToolbarChip(
                            label = stringResource(R.string.action_copy_text),
                            onClick = {
                                val txt = selectedBlocks.joinToString("\n") { BlockOperations.extractPlainText(it) }
                                coroutineScope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", txt)))
                                }
                                selectedBlockIds.clear()
                                explicitToolbarMode = null
                            },
                        )
                        ToolbarChip(
                            label = stringResource(R.string.action_delete_block),
                            leadingIcon = Icons.Filled.Delete,
                            chipColor = AppColors.Danger,
                            onClick = {
                                deletedBackupBlocks = currentBlocks
                                val remaining = currentBlocks.filterNot { it.id in selectedBlockIds }
                                selectedBlockIds.clear()
                                commitBlocks(remaining, isStructural = true)
                                explicitToolbarMode = EditorToolbarMode.NOTICE
                            },
                        )
                    }
                }
            }

            EditorToolbarMode.PASTE_URL -> {
                {
                    val url = pastedUrlCandidate.orEmpty()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "dismiss_paste_url",
                                title = stringResource(R.string.back),
                                icon = Icons.Filled.Close,
                                onClick = {
                                    pastedUrlCandidate = null
                                    explicitToolbarMode = null
                                },
                            ),
                        )
                        Text(
                            text = stringResource(R.string.paste_url_prompt),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextSecondary,
                        )
                        ToolbarChip(
                            label = stringResource(R.string.action_paste_as_bookmark),
                            leadingEmoji = "🔖",
                            isAccent = true,
                            onClick = {
                                val idx = currentBlocks.indexOfFirst { it.id == focusedBlockId }
                                if (idx in currentBlocks.indices && url.isNotBlank()) {
                                    val domain = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
                                    val updated = currentBlocks.toMutableList()
                                    updated[idx] = Block.Bookmark(
                                        id = currentBlocks[idx].id,
                                        url = url,
                                        title = domain.ifBlank { url },
                                    )
                                    commitBlocks(updated, isStructural = true)
                                }
                                pastedUrlCandidate = null
                                explicitToolbarMode = null
                            },
                        )
                        ToolbarChip(
                            label = stringResource(R.string.action_paste_as_link),
                            leadingEmoji = "🔗",
                            onClick = {
                                val idx = currentBlocks.indexOfFirst { it.id == focusedBlockId }
                                if (idx in currentBlocks.indices && url.isNotBlank()) {
                                    val domain = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { url }
                                    val updated = currentBlocks.toMutableList()
                                    updated[idx] = BlockOperations.withSpans(
                                        currentBlocks[idx],
                                        listOf(InlineSpan.Text(text = domain, linkUrl = url)),
                                    )
                                    commitBlocks(updated, isStructural = true)
                                }
                                pastedUrlCandidate = null
                                explicitToolbarMode = null
                            },
                        )
                    }
                }
            }

            EditorToolbarMode.NOTICE -> {
                {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.blocks_deleted_notice),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.TextPrimary,
                        )
                        ToolbarChip(
                            label = stringResource(R.string.action_undo_delete),
                            isAccent = true,
                            onClick = {
                                deletedBackupBlocks?.let { backup ->
                                    commitBlocks(backup, isStructural = true)
                                }
                                deletedBackupBlocks = null
                                explicitToolbarMode = null
                            },
                        )
                    }
                }
            }
        }

        // Pattern C: Keyboard-Replacement Panel Composable
        val panelComposable: (@Composable (androidx.compose.ui.unit.Dp) -> Unit)? = when (activeKeyboardPanel) {
            EditorKeyboardPanelType.NONE -> null
            EditorKeyboardPanelType.BLOCKS -> {
                {
                    BlockCatalogKeyboardPanel(
                        filterQuery = slashFilterQuery,
                        onSelectBlockType = { typeId ->
                            selectOrConvertBlockType(typeId)
                        },
                        onSwitchToKeyboard = {
                            activeKeyboardPanel = EditorKeyboardPanelType.NONE
                            focusedBlockId?.let { targetFocusBlockId = it }
                            keyboardController?.show()
                        },
                    )
                }
            }
            EditorKeyboardPanelType.CALENDAR -> {
                {
                    DateCalendarKeyboardPanel(
                        onSelectIsoDate = { iso ->
                            applySelectedInlineDate(iso)
                        },
                        onSwitchToKeyboard = {
                            activeKeyboardPanel = EditorKeyboardPanelType.NONE
                            focusedBlockId?.let { targetFocusBlockId = it }
                            keyboardController?.show()
                        },
                    )
                }
            }
            EditorKeyboardPanelType.AUDIO -> {
                {
                    AudioRecorderKeyboardPanel(
                        onDiscard = {
                            activeKeyboardPanel = EditorKeyboardPanelType.NONE
                        },
                        onInsertRecording = { bytes, durationSeconds ->
                            activeKeyboardPanel = EditorKeyboardPanelType.NONE
                            insertRecordedAudioPanel(bytes, durationSeconds)
                        },
                    )
                }
            }
        }

        val forceVisibleWithoutIme = selectedBlockIds.isNotEmpty() ||
            effectiveMode == EditorToolbarMode.NOTICE ||
            activeKeyboardPanel != EditorKeyboardPanelType.NONE

        SideEffect {
            if (toolbarController != null) {
                if ((imeVisible && focusedBlockId != null) || forceVisibleWithoutIme) {
                    toolbarController.publish(
                        context = toolbarContext,
                        actions = toolbarActions,
                        mode = effectiveMode,
                        forceVisibleWithoutIme = forceVisibleWithoutIme,
                        dynamicRowContent = dynamicRowComposable,
                        panelType = activeKeyboardPanel,
                        panelContent = panelComposable,
                    )
                } else {
                    toolbarController.clear()
                }
            }
        }
        DisposableEffect(Unit) {
            onDispose { toolbarController?.clear() }
        }
    }
}

/**
 * Top tags bar shown above the block editor on every screen (Daily Note, Regular Note, etc.).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObjectTagsHeaderRow(
    directTagNames: List<String>,
    inferredBlockTagNames: List<String>,
    allTags: List<PkmObject>,
    onOpenTagPicker: () -> Unit,
    onNavigateToDirectTag: (String) -> Unit,
    onFocusBlockWithTag: (String) -> Unit,
    onRemoveDirectTag: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // "Etiquetas ≡" trigger button
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenTagPicker)
                .padding(horizontal = 4.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tags_badge),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                contentDescription = stringResource(R.string.tags_badge),
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }

        // 1. Direct / Own tags (formal badge with color dot)
        directTagNames.forEach { tagName ->
            val tagObj = allTags.find { it.plainTitle.equals(tagName, ignoreCase = true) }
            val customHex = tagObj?.icon?.colorHex
            val tagBg = TagPalette.getPillBackground(tagName, customHex)
            val tagBorder = TagPalette.getPillBorder(tagName, customHex)
            val tagText = TagPalette.getTextColor(tagName, customHex)
            val tagAccent = TagPalette.getAccentColor(tagName, customHex)

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(AppColors.Surface, RoundedCornerShape(7.dp))
                    .background(tagBg, RoundedCornerShape(7.dp))
                    .border(BorderStroke(1.dp, tagBorder), RoundedCornerShape(7.dp))
                    .clickable { onNavigateToDirectTag(tagName) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(tagAccent, CircleShape),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tagName,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tagText,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = tagText.copy(alpha = 0.65f),
                    modifier = Modifier
                        .size(13.dp)
                        .clickable { onRemoveDirectTag(tagName) },
                )
            }
        }

        // 2. Inferred tags from blocks inside this object (formal outlined style with block icon & # hierarchy)
        inferredBlockTagNames.forEach { tagName ->
            val tagObj = allTags.find { it.plainTitle.equals(tagName, ignoreCase = true) }
            val customHex = tagObj?.icon?.colorHex
            val tagBg = TagPalette.getPillBackground(tagName, customHex)
            val tagBorder = TagPalette.getPillBorder(tagName, customHex)
            val tagText = TagPalette.getTextColor(tagName, customHex)
            val tagHash = TagPalette.getHashColor(tagName, customHex)

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(AppColors.Surface, RoundedCornerShape(7.dp))
                    .background(tagBg, RoundedCornerShape(7.dp))
                    .border(BorderStroke(1.dp, tagBorder), RoundedCornerShape(7.dp))
                    .clickable { onFocusBlockWithTag(tagName) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Notes,
                    contentDescription = null,
                    tint = tagHash,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "#",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Normal,
                    color = tagHash,
                )
                Spacer(modifier = Modifier.width(1.dp))
                Text(
                    text = tagName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tagText,
                )
            }
        }
    }
}

/**
 * Compact chip with a real decoded image thumbnail for the Dynamic Island `@mention` toolbar row.
 * Lets the user visually identify an image in the horizontal bar without relying solely on filenames.
 */
@Composable
private fun MentionImageToolbarChip(
    obj: PkmObject,
    allTags: List<PkmObject>,
    untitledLabel: String,
    onClick: () -> Unit,
) {
    val assetId = resolveImageAssetId(obj)
    val thumb = if (!assetId.isNullOrBlank()) {
        rememberDecodedThumbnail(assetId, targetMaxSidePx = 128)
    } else {
        null
    }
    val tagNames = remember(obj, allTags) { resolveObjectTagNames(obj, allTags) }
    val displayTitle = remember(obj, tagNames, untitledLabel) {
        val raw = obj.plainTitle.trim()
        val firstTag = tagNames.firstOrNull()?.let { "#$it" }
        if (raw.isNotBlank() && firstTag != null) {
            "$raw · $firstTag"
        } else {
            raw.ifBlank { firstTag ?: untitledLabel }
        }
    }

    Row(
        modifier = Modifier
            .height(36.dp)
            .widthIn(max = 190.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(AppColors.Surface, RoundedCornerShape(9.dp))
            .border(BorderStroke(1.dp, AppColors.BorderSubtle), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(AppColors.SurfaceElevated, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.bitmap.asImageBitmap(),
                    contentDescription = displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(28.dp).privacyImage(),
                )
            } else {
                Text(text = "🖼️", fontSize = 13.sp)
            }
        }
        Text(
            text = displayTitle,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
