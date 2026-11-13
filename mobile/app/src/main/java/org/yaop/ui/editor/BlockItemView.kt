package org.yaop.ui.editor

import org.yaop.ui.common.privacyImage
import android.content.ClipData
import android.view.KeyEvent as AndroidKeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import org.yaop.ui.common.extractAudioDurationSeconds
import org.yaop.ui.common.resolveAudioAssetId
import org.yaop.ui.common.resolveImageAssetId
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import org.yaop.ui.common.SubtleSwipeActionBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.Block
import org.yaop.core.model.BlockTypeKeys
import org.yaop.core.model.PkmObject
import org.yaop.core.model.HeadingLevel
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.ObjectType
import org.yaop.core.model.RichSpanEngine
import org.yaop.core.model.toAlphabetic
import org.yaop.core.model.toPlainText
import org.yaop.core.model.toRoman
import org.yaop.core.model.toTextSpan
import org.yaop.core.model.toVisibleString
import org.yaop.ui.common.LinkPreviewCard
import org.yaop.ui.theme.AppColors
import org.yaop.ui.theme.YaopTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val BLOCK_TYPE_MENU_OPTIONS = listOf(
    BlockTypeKeys.PARAGRAPH to R.string.block_type_paragraph,
    BlockTypeKeys.H1 to R.string.block_type_h1,
    BlockTypeKeys.H2 to R.string.block_type_h2,
    BlockTypeKeys.H3 to R.string.block_type_h3,
    BlockTypeKeys.H4 to R.string.block_type_h4,
    BlockTypeKeys.TODO to R.string.block_type_todo,
    BlockTypeKeys.BULLET to R.string.block_type_bullet,
    BlockTypeKeys.NUMBERED to R.string.block_type_numbered,
    BlockTypeKeys.ALPHABETIC to R.string.block_type_alphabetic,
    BlockTypeKeys.ROMAN to R.string.block_type_roman,
    BlockTypeKeys.QUOTE to R.string.block_type_quote,
    BlockTypeKeys.CALLOUT to R.string.block_type_callout,
    BlockTypeKeys.GROUP to R.string.block_type_group,
    BlockTypeKeys.CODE to R.string.block_type_code,
    BlockTypeKeys.MATH to R.string.block_type_math,
    BlockTypeKeys.TABLE to R.string.block_type_table,
    BlockTypeKeys.DIVIDER to R.string.block_type_divider,
    BlockTypeKeys.TOGGLE to R.string.block_type_toggle,
    BlockTypeKeys.BOOKMARK to R.string.block_type_bookmark,
)

private val CODE_LANGUAGES = listOf(
    "javascript" to R.string.language_javascript,
    "typescript" to R.string.language_typescript,
    "kotlin" to R.string.language_kotlin,
    "python" to R.string.language_python,
    "java" to R.string.language_java,
    "cpp" to R.string.language_cpp,
    "html" to R.string.language_html,
    "css" to R.string.language_css,
    "json" to R.string.language_json,
    "sql" to R.string.language_sql,
    "markdown" to R.string.language_markdown,
    "shell" to R.string.language_shell,
)

private val CALLOUT_EMOJIS = listOf("💡", "⚠️", "❗", "✅", "📌", "🔥", "ℹ️")

// Shared empty render for plain unstyled text. Avoids allocating an AnnotatedString copy of the
// whole block on every keystroke when there are no pills/styles to draw.
private val EmptyMarkdownRender = MarkdownRender(
    text = AnnotatedString(""),
    offsetMapping = OffsetMapping.Identity,
    pills = emptyList(),
)

/**
 * Clean, structured entry point for rendering an editor block using a cohesive [BlockItemContext].
 * Optimizes Jetpack Compose recomposition by grouping visual state and stable callbacks.
 */
@Composable
fun BlockItemView(
    block: Block,
    context: BlockItemContext,
    modifier: Modifier = Modifier,
) {
    BlockItemView(
        block = block,
        focusRequester = context.focusRequester,
        isFocused = context.visualState.isFocused,
        onFocusGained = { context.actions.onFocusGained() },
        onFocusCleared = { context.actions.onFocusCleared() },
        onUpdate = { context.actions.onUpdate(it) },
        onTextChange = { context.actions.onTextChange(it) },
        onDelete = { context.actions.onDelete() },
        onEnterPressed = { b, a -> context.actions.onEnterPressed(b, a) },
        onBackspaceOnEmpty = { context.actions.onBackspaceOnEmpty() },
        modifier = modifier,
        onSelectionChange = { context.actions.onSelectionChange(it) },
        requestedCursorOffset = context.visualState.requestedCursorOffset,
        textSyncRevision = context.visualState.textSyncRevision,
        onCursorOffsetConsumed = { context.actions.onCursorOffsetConsumed() },
        allObjects = context.environment.allObjects,
        allTypes = context.environment.allTypes,
        onNavigateToObject = { context.navigation.onNavigateToObject(it) },
        onNavigateToTag = { context.navigation.onNavigateToTag(it) },
        onNavigateToDate = { context.navigation.onNavigateToDate(it) },
        activeTypingFormats = context.visualState.activeTypingFormats,
        activeTextColor = context.visualState.activeTextColor,
        activeBgColor = context.visualState.activeBgColor,
        hasExplicitTextColor = context.visualState.hasExplicitTextColor,
        hasExplicitBgColor = context.visualState.hasExplicitBgColor,
        onCursorMoved = { context.actions.onCursorMoved() },
        onAudioRecorded = { id, bytes, dur -> context.actions.onAudioRecorded(id, bytes, dur) },
        mediaRefreshKeys = context.environment.mediaRefreshKeys,
        onSpansChange = { spans, caret -> context.actions.onSpansChange(spans, caret) },
        onEnterAtOffset = { context.actions.onEnterAtOffset(it) },
        onExitSpecialBlock = { context.actions.onExitSpecialBlock() },
        onActiveTableColumnChange = { context.actions.onActiveTableColumnChange(it) },
        onTapInlinePill = { context.navigation.onTapInlinePill(it) },
        onTapBookmark = { context.navigation.onTapBookmark(it) },
        onTapImage = { context.navigation.onTapImage(it) },
        onOpenCanvas = { context.navigation.onOpenCanvas(it) },
        hiddenChildrenCount = context.visualState.hiddenChildrenCount,
        isFirstInGroup = context.visualState.isFirstInGroup,
        isLastInGroup = context.visualState.isLastInGroup,
        isSelectionMode = context.visualState.isSelectionMode,
        isSelected = context.visualState.isSelected,
        onToggleSelection = { context.actions.onToggleSelection() },
        onLongPressSelect = { context.actions.onLongPressSelect() },
        onMoveUp = { context.actions.onMoveUp() },
        onMoveDown = { context.actions.onMoveDown() },
        onHorizontalIndentDelta = { context.actions.onHorizontalIndentDelta(it) },
        tagColors = context.environment.tagColors,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BlockItemView(
    block: Block,
    focusRequester: FocusRequester,
    isFocused: Boolean,
    onFocusGained: () -> Unit,
    onUpdate: (Block) -> Unit,
    onTextChange: (String) -> Unit,
    onDelete: () -> Unit,
    onEnterPressed: (before: String, after: String) -> Unit,
    onBackspaceOnEmpty: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusCleared: () -> Unit = {},
    onSelectionChange: ((TextRange) -> Unit)? = null,
    requestedCursorOffset: Int? = null,
    textSyncRevision: Int = 0,
    onCursorOffsetConsumed: (() -> Unit)? = null,
    allObjects: List<PkmObject> = emptyList(),
    allTypes: List<ObjectType> = emptyList(),
    tagColors: Map<String, String> = emptyMap(),
    onNavigateToObject: ((String) -> Unit)? = null,
    onNavigateToTag: ((String) -> Unit)? = null,
    onNavigateToDate: ((java.time.LocalDate) -> Unit)? = null,
    activeTypingFormats: Set<String>? = null,
    activeTextColor: String? = null,
    activeBgColor: String? = null,
    hasExplicitTextColor: Boolean = false,
    hasExplicitBgColor: Boolean = false,
    onCursorMoved: (() -> Unit)? = null,
    onAudioRecorded: ((blockId: String, bytes: ByteArray, durationSeconds: Int) -> Unit)? = null,
    mediaRefreshKeys: Set<String> = emptySet(),
    onSpansChange: ((List<InlineSpan>, Int) -> Unit)? = null,
    onEnterAtOffset: ((Int) -> Unit)? = null,
    onExitSpecialBlock: (() -> Unit)? = null,
    onActiveTableColumnChange: ((Int) -> Unit)? = null,
    onTapInlinePill: ((MarkdownPill) -> Unit)? = null,
    onTapBookmark: ((Block.Bookmark) -> Unit)? = null,
    onTapImage: ((Block.Image) -> Unit)? = null,
    onOpenCanvas: ((Block.Canvas) -> Unit)? = null,
    hiddenChildrenCount: Int = 0,
    isFirstInGroup: Boolean = false,
    isLastInGroup: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
    onLongPressSelect: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onHorizontalIndentDelta: ((Int) -> Unit)? = null,
) {
    var showTypeMenu by remember { mutableStateOf(value = false) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    if (showTypeMenu) {
        UniversalEditorBottomSheet(
            onDismiss = { showTypeMenu = false },
            headerTitle = stringResource(R.string.options),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                BLOCK_TYPE_MENU_OPTIONS.forEach { (typeKey, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onUpdate(BlockOperations.convertBlockType(block, typeKey))
                                showTypeMenu = false
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            color = AppColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    HorizontalDivider(color = AppColors.BorderSubtle)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            onDelete()
                            showTypeMenu = false
                        }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.action_delete_block),
                        color = AppColors.Danger,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    val isGrouped = !block.groupId.isNullOrBlank()
    val groupShape = remember(isGrouped, isFirstInGroup, isLastInGroup) {
        when {
            !isGrouped -> RoundedCornerShape(8.dp)
            isFirstInGroup && isLastInGroup -> RoundedCornerShape(10.dp)
            isFirstInGroup -> RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
            isLastInGroup -> RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
            else -> RoundedCornerShape(2.dp)
        }
    }

    val customBlockBg = remember(block.blockColor) {
        EditorColorPalette.resolveBackgroundColor(block.blockColor)
    }

    val containerModifier = remember(isGrouped, isSelected, customBlockBg, groupShape) {
        var mod: Modifier = Modifier
        if (isGrouped) {
            val bg = customBlockBg ?: AppColors.Surface.copy(alpha = 0.72f)
            mod = mod
                .clip(groupShape)
                .background(bg, groupShape)
                .border(1.dp, AppColors.BorderSubtle, groupShape)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        } else if (customBlockBg != null) {
            mod = mod
                .clip(RoundedCornerShape(8.dp))
                .background(customBlockBg, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        }
        if (isSelected) {
            mod = mod
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.Accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                .border(1.dp, AppColors.Accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        }
        mod
    }

    val stepYPx = with(density) { 36.dp.toPx() }
    val stepXPx = with(density) { 28.dp.toPx() }
    val currentIsSelectionMode by rememberUpdatedState(isSelectionMode)
    val currentIsSelected by rememberUpdatedState(isSelected)
    val currentOnToggleSelection by rememberUpdatedState(onToggleSelection)
    val currentOnLongPressSelect by rememberUpdatedState(onLongPressSelect)
    val currentOnMoveUp by rememberUpdatedState(onMoveUp)
    val currentOnMoveDown by rememberUpdatedState(onMoveDown)
    val currentOnHorizontalIndentDelta by rememberUpdatedState(onHorizontalIndentDelta)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = (block.indentLevel * 18).dp,
                top = if (isGrouped && !isFirstInGroup) 0.dp else 2.dp,
                bottom = if (isGrouped && !isLastInGroup) 0.dp else 2.dp,
            )
            .then(containerModifier)
            .pointerInput(block.id, stepYPx, stepXPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val touchSlop = viewConfiguration.touchSlop

                    if (currentIsSelectionMode) {
                        down.consume()
                        var isTap = true
                        var accumY = 0f
                        var accumX = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                            event.changes.forEach { it.consume() }
                            if (change == null || !change.pressed) {
                                if (isTap) {
                                    currentOnToggleSelection?.invoke()
                                }
                                break
                            }
                            if ((change.position - down.position).getDistance() > touchSlop) {
                                isTap = false
                            }
                            if (!isTap && currentIsSelected) {
                                val delta = change.position - change.previousPosition
                                accumY += delta.y
                                accumX += delta.x
                                if (accumY <= -stepYPx) {
                                    accumY = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnMoveUp?.invoke()
                                } else if (accumY >= stepYPx) {
                                    accumY = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnMoveDown?.invoke()
                                }
                                if (accumX >= stepXPx) {
                                    accumX = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnHorizontalIndentDelta?.invoke(1)
                                } else if (accumX <= -stepXPx) {
                                    accumX = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnHorizontalIndentDelta?.invoke(-1)
                                }
                            }
                        }
                        return@awaitEachGesture
                    }

                    if (currentOnLongPressSelect == null) return@awaitEachGesture

                    var releasedOrMoved = false
                    val finishedBeforeTimeout = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                            if (change == null || !change.pressed) {
                                releasedOrMoved = true
                                break
                            }
                            if ((change.position - down.position).getDistance() > touchSlop) {
                                releasedOrMoved = true
                                break
                            }
                        }
                        true
                    }

                    if (finishedBeforeTimeout == null && !releasedOrMoved) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnLongPressSelect?.invoke()
                        var accumY = 0f
                        var accumX = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                            event.changes.forEach { it.consume() }
                            if (change == null || !change.pressed) break
                            val delta = change.position - change.previousPosition
                            accumY += delta.y
                            accumX += delta.x
                            if (accumY <= -stepYPx) {
                                accumY = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnMoveUp?.invoke()
                            } else if (accumY >= stepYPx) {
                                accumY = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnMoveDown?.invoke()
                            }
                            if (accumX >= stepXPx) {
                                accumX = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnHorizontalIndentDelta?.invoke(1)
                            } else if (accumX <= -stepXPx) {
                                accumX = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnHorizontalIndentDelta?.invoke(-1)
                            }
                        }
                    }
                }
            },
        verticalAlignment = Alignment.Top,
    ) {
        // Multi-block selection checkbox (shown only when selection mode is active)
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .padding(top = 3.dp, end = 8.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isSelected) AppColors.Accent else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    )
                    .border(
                        1.5.dp,
                        if (isSelected) AppColors.Accent else AppColors.BorderHover,
                        RoundedCornerShape(4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = AppColors.Background,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }

        // Body Representation
        Box(modifier = Modifier.weight(1f)) {
            when (block) {
                is Block.Paragraph,
                is Block.Heading,
                is Block.Todo,
                is Block.BulletList,
                is Block.NumberedList,
                is Block.AlphabeticList,
                is Block.RomanList,
                is Block.Quote,
                is Block.Callout,
                -> {
                    val blockSpans = remember(block) { BlockOperations.extractSpans(block) }
                    val plainText = remember(blockSpans) { blockSpans.toVisibleString() }
                    val trimmedUrl = remember(block, plainText) {
                        if (block is Block.Paragraph) {
                            plainText.trim().takeIf { candidate ->
                                !candidate.contains(' ') &&
                                    !candidate.contains('\n') &&
                                    (candidate.startsWith("https://", ignoreCase = true) ||
                                        candidate.startsWith("http://", ignoreCase = true))
                            }
                        } else {
                            null
                        }
                    }
                    val referencedObjects: List<PkmObject> = remember(blockSpans, plainText, allObjects, allTypes) {
                        findReferencedObjects(blockSpans, plainText, allObjects, allTypes)
                    }
                    val referencedAudioObjects = remember(referencedObjects) {
                        referencedObjects.filter {
                            (it.typeId == "audio" || it.typeId == "type_audio") &&
                                !resolveAudioAssetId(it).isNullOrBlank()
                        }
                    }
                    val referencedImageObjects = remember(referencedObjects) {
                        referencedObjects.filter {
                            (it.typeId == "image" || it.typeId == "type_image") &&
                                !resolveImageAssetId(it).isNullOrBlank()
                        }
                    }

                    val placeholderText = when (block) {
                        is Block.Heading -> stringResource(R.string.placeholder_heading, block.level.name)
                        is Block.Todo -> stringResource(R.string.placeholder_todo)
                        is Block.BulletList -> stringResource(R.string.placeholder_bullet)
                        is Block.NumberedList -> stringResource(R.string.placeholder_numbered)
                        is Block.AlphabeticList -> stringResource(R.string.placeholder_alphabetic)
                        is Block.RomanList -> stringResource(R.string.placeholder_roman)
                        is Block.Quote -> stringResource(R.string.placeholder_quote)
                        is Block.Callout -> stringResource(R.string.placeholder_callout)
                        else -> if (isGrouped) {
                            stringResource(R.string.placeholder_group)
                        } else {
                            stringResource(R.string.placeholder_default)
                        }
                    }

                    val blockTextStyle = when (block) {
                        is Block.Heading -> {
                            val (fontSize, weight) = when (block.level) {
                                HeadingLevel.H1 -> 24.sp to FontWeight.Bold
                                HeadingLevel.H2 -> 20.sp to FontWeight.Bold
                                HeadingLevel.H3 -> 17.sp to FontWeight.Bold
                                HeadingLevel.H4 -> 15.sp to FontWeight.SemiBold
                            }
                            TextStyle(
                                fontSize = fontSize,
                                fontWeight = weight,
                                color = AppColors.TextPrimary,
                                lineHeight = (fontSize.value * 1.35f).sp,
                            )
                        }
                        is Block.Todo -> TextStyle(
                            fontSize = 15.sp,
                            color = if (block.checked) AppColors.TextTertiary else AppColors.TextPrimary,
                            textDecoration = if (block.checked) TextDecoration.LineThrough else TextDecoration.None,
                            lineHeight = 22.sp,
                        )
                        is Block.Callout -> TextStyle(
                            fontSize = 14.sp,
                            color = AppColors.TextPrimary,
                            lineHeight = 21.sp,
                        )
                        else -> TextStyle(
                            fontSize = 15.sp,
                            color = AppColors.TextPrimary,
                            lineHeight = 22.sp,
                        )
                    }

                    val rowModifier = when (block) {
                        is Block.Todo -> Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp, bottom = 2.dp)
                        is Block.Quote -> Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                        is Block.Callout -> Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                customBlockBg ?: AppColors.CalloutBackground,
                                RoundedCornerShape(14.dp),
                            )
                            .border(1.dp, AppColors.CalloutBorder, RoundedCornerShape(14.dp))
                            .padding(12.dp)
                        else -> Modifier.fillMaxWidth()
                    }

                    val verticalAlign = when (block) {
                        is Block.Todo, is Block.Quote -> Alignment.CenterVertically
                        else -> Alignment.Top
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        val isTodo = block is Block.Todo
                        val isTodoChecked = if (block is Block.Todo) block.checked else false
                        val canIndentBlock = BlockOperations.canIndent(block)
                        val canSwipeBlock = !isSelectionMode && (!isFocused || isTodo)

                        val onSwipeRightAction: (() -> Unit)? = when {
                            isTodo -> { { onUpdate(block.copy(checked = !isTodoChecked)) } }
                            !isFocused && canIndentBlock -> { { onHorizontalIndentDelta?.invoke(1) } }
                            else -> null
                        }

                        val onSwipeLeftAction: (() -> Unit)? = when {
                            isTodo && block.indentLevel > 0 -> { { onHorizontalIndentDelta?.invoke(-1) } }
                            !isFocused && canIndentBlock && block.indentLevel > 0 -> { { onHorizontalIndentDelta?.invoke(-1) } }
                            else -> null
                        }

                        SubtleSwipeActionBox(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canSwipeBlock && (onSwipeRightAction != null || onSwipeLeftAction != null),
                            shape = if (block is Block.Callout) RoundedCornerShape(8.dp) else RoundedCornerShape(4.dp),
                            thresholdDp = 44.dp,
                            maxTravelDp = 64.dp,
                            edgeDeadZoneDp = 28.dp,
                            onSwipeRight = onSwipeRightAction,
                            swipeRightIcon = if (isTodo) {
                                if (isTodoChecked) Icons.Default.RadioButtonUnchecked else Icons.Default.Check
                            } else {
                                Icons.AutoMirrored.Filled.FormatIndentIncrease
                            },
                            swipeRightColor = if (isTodo && isTodoChecked) AppColors.TextSecondary else AppColors.Accent,
                            swipeRightBackground = if (isTodo && isTodoChecked) AppColors.SurfaceElevated else AppColors.Accent.copy(alpha = 0.16f),
                            swipeRightLabel = if (isTodo) {
                                if (isTodoChecked) stringResource(R.string.action_swipe_uncomplete) else stringResource(R.string.action_swipe_complete)
                            } else {
                                stringResource(R.string.action_swipe_indent)
                            },
                            onSwipeLeft = onSwipeLeftAction,
                            swipeLeftIcon = Icons.AutoMirrored.Filled.FormatIndentDecrease,
                            swipeLeftColor = AppColors.TextSecondary,
                            swipeLeftBackground = AppColors.SurfaceElevated,
                            swipeLeftLabel = stringResource(R.string.action_swipe_outdent),
                        ) {
                            Row(
                                modifier = rowModifier,
                                verticalAlignment = verticalAlign,
                            ) {
                                when (block) {
                                    is Block.Todo -> {
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .border(
                                                    width = 1.5.dp,
                                                    color = if (block.checked) AppColors.Accent else AppColors.BorderHover,
                                                    shape = RoundedCornerShape(4.dp),
                                                )
                                                .background(
                                                    color = if (block.checked) AppColors.Accent else Color.Transparent,
                                                    shape = RoundedCornerShape(4.dp),
                                                )
                                                .clickable { onUpdate(block.copy(checked = !block.checked)) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (block.checked) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = AppColors.Background,
                                                    modifier = Modifier.size(13.dp),
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                    }
                                    is Block.BulletList -> {
                                        Text(
                                            text = "•",
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AppColors.TextPrimary,
                                            modifier = Modifier.padding(top = 1.dp, end = 8.dp),
                                        )
                                    }
                                    is Block.NumberedList -> {
                                        Text(
                                            text = "${block.number}.",
                                            fontFamily = org.yaop.ui.theme.AppFonts.PlexMono,
                                            fontSize = 13.sp,
                                            color = AppColors.TextSecondary,
                                            modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                                        )
                                    }
                                    is Block.AlphabeticList -> {
                                        Text(
                                            text = "${block.number.toAlphabetic()}.",
                                            fontFamily = org.yaop.ui.theme.AppFonts.PlexMono,
                                            fontSize = 13.sp,
                                            color = AppColors.TextSecondary,
                                            modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                                        )
                                    }
                                    is Block.RomanList -> {
                                        Text(
                                            text = "${block.number.toRoman()}.",
                                            fontFamily = org.yaop.ui.theme.AppFonts.PlexMono,
                                            fontSize = 13.sp,
                                            color = AppColors.TextSecondary,
                                            modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                                        )
                                    }
                                    is Block.Quote -> {
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height(22.dp)
                                                .background(AppColors.Ice, RoundedCornerShape(2.dp)),
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    is Block.Callout -> {
                                        Text(
                                            text = block.emoji,
                                            fontSize = 18.sp,
                                            modifier = Modifier
                                                .padding(end = 8.dp)
                                                .clickable {
                                                    val idx = CALLOUT_EMOJIS.indexOf(block.emoji)
                                                    val nextEmoji = CALLOUT_EMOJIS[(idx + 1) % CALLOUT_EMOJIS.size]
                                                    onUpdate(block.copy(emoji = nextEmoji))
                                                },
                                        )
                                    }
                                    else -> Unit
                                }

                                BlockTextField(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    text = plainText,
                                    onTextChange = onTextChange,
                                    spans = blockSpans,
                                    onSpansChange = onSpansChange,
                                    placeholder = placeholderText,
                                    textStyle = blockTextStyle,
                                    focusRequester = focusRequester,
                                    onFocusGained = onFocusGained,
                                    onEnter = onEnterPressed,
                                    onEnterAtOffset = onEnterAtOffset,
                                    onBackspaceOnEmpty = onBackspaceOnEmpty,
                                    allObjects = allObjects,
                                    allTypes = allTypes,
                                    tagColors = tagColors,
                                    onNavigateToObject = onNavigateToObject,
                                    onNavigateToTag = onNavigateToTag,
                                    onNavigateToDate = onNavigateToDate,
                                    onTapInlinePill = onTapInlinePill,
                                    activeTypingFormats = activeTypingFormats,
                                    activeTextColor = activeTextColor,
                                    activeBgColor = activeBgColor,
                                    hasExplicitTextColor = hasExplicitTextColor,
                                    hasExplicitBgColor = hasExplicitBgColor,
                                    onCursorMoved = onCursorMoved,
                                    onSelectionChange = onSelectionChange,
                                    requestedCursorOffset = requestedCursorOffset,
                                    externalTextRevision = textSyncRevision,
                                    onCursorOffsetConsumed = onCursorOffsetConsumed,
                                )
                            }
                        }
                        if (trimmedUrl != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinkPreviewCard(url = trimmedUrl)
                        }
                        for (audioObj in referencedAudioObjects) {
                            val audioAssetId = resolveAudioAssetId(audioObj).orEmpty()
                            if (audioAssetId.isNotBlank()) {
                                val audioDur = extractAudioDurationSeconds(audioObj).coerceAtLeast(1)
                                Spacer(modifier = Modifier.height(6.dp))
                                AudioBlockView(
                                    block = Block.Audio(
                                        id = "${block.id}_ref_${audioObj.id}",
                                        assetId = audioAssetId,
                                        durationSeconds = audioDur,
                                        caption = audioObj.title,
                                    ),
                                    onRecordingFinished = { _, _ -> },
                                    refreshKey = audioAssetId in mediaRefreshKeys,
                                    audioTitle = audioObj.plainTitle,
                                    onOpenAudioObject = if (onNavigateToObject != null) ({ onNavigateToObject(audioObj.id) }) else null,
                                )
                            }
                        }
                        for (imageObj in referencedImageObjects) {
                            val imageAssetId = resolveImageAssetId(imageObj).orEmpty()
                            if (imageAssetId.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                ReferencedImageBlockView(
                                    imageObject = imageObj,
                                    assetId = imageAssetId,
                                    refreshKey = imageAssetId in mediaRefreshKeys,
                                    onTapImage = if (onTapImage != null) {
                                        {
                                            onTapImage(
                                                Block.Image(
                                                    id = "${block.id}_ref_${imageObj.id}",
                                                    assetId = imageAssetId,
                                                    caption = imageObj.title,
                                                ),
                                            )
                                        }
                                    } else null,
                                    onOpenImageObject = if (onNavigateToObject != null) ({ onNavigateToObject(imageObj.id) }) else null,
                                )
                            }
                        }
                    }
                }

                is Block.Bookmark -> {
                    BookmarkBlockView(
                        block = block,
                        onTap = {
                            if (onTapBookmark != null) {
                                onTapBookmark(block)
                            }
                        },
                    )
                }

                is Block.Code -> {
                    CodeBlockView(
                        block = block,
                        focusRequester = focusRequester,
                        onFocusGained = onFocusGained,
                        onUpdate = onUpdate,
                        onDelete = onDelete,
                        onExitBlock = onExitSpecialBlock,
                    )
                }

                is Block.Math -> {
                    MathBlockView(
                        block = block,
                        focusRequester = focusRequester,
                        isFocused = isFocused,
                        onFocusGained = onFocusGained,
                        onFocusCleared = onFocusCleared,
                        onUpdate = onUpdate,
                        onExitBlock = onExitSpecialBlock,
                    )
                }

                is Block.Table -> {
                    TableBlockView(
                        block = block,
                        onUpdate = onUpdate,
                        onFocusGained = onFocusGained,
                        onActiveColumnChange = onActiveTableColumnChange,
                    )
                }

                is Block.Divider -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = AppColors.BorderSubtle,
                            thickness = 1.dp,
                        )
                    }
                }

                is Block.Toggle -> {
                    ToggleBlockView(
                        block = block,
                        hiddenChildrenCount = hiddenChildrenCount,
                        focusRequester = focusRequester,
                        onFocusGained = onFocusGained,
                        onUpdate = onUpdate,
                        onTextChange = onTextChange,
                        onSpansChange = onSpansChange,
                        onEnterPressed = onEnterPressed,
                        onEnterAtOffset = onEnterAtOffset,
                        onBackspaceOnEmpty = onBackspaceOnEmpty,
                        allObjects = allObjects,
                        allTypes = allTypes,
                        tagColors = tagColors,
                        onNavigateToObject = onNavigateToObject,
                        onNavigateToTag = onNavigateToTag,
                        onNavigateToDate = onNavigateToDate,
                        onTapInlinePill = onTapInlinePill,
                        activeTypingFormats = activeTypingFormats,
                        activeTextColor = activeTextColor,
                        activeBgColor = activeBgColor,
                        hasExplicitTextColor = hasExplicitTextColor,
                        hasExplicitBgColor = hasExplicitBgColor,
                        onCursorMoved = onCursorMoved,
                        onSelectionChange = onSelectionChange,
                        requestedCursorOffset = requestedCursorOffset,
                        textSyncRevision = textSyncRevision,
                        onCursorOffsetConsumed = onCursorOffsetConsumed,
                    )
                }

                is Block.Image -> {
                    ImageBlockView(
                        block = block,
                        refreshKey = block.assetId in mediaRefreshKeys,
                        onTapImage = if (onTapImage != null) ({ onTapImage(block) }) else null,
                    )
                }

                is Block.Audio -> {
                    val linkedAudioObj = remember(block.assetId, allObjects) {
                        if (block.assetId.isBlank()) {
                            null
                        } else {
                            allObjects.find {
                                (it.typeId == "audio" || it.typeId == "type_audio") &&
                                    resolveAudioAssetId(it) == block.assetId
                            }
                        }
                    }
                    val resolvedAudioTitle = linkedAudioObj?.plainTitle?.takeIf { it.isNotBlank() }
                        ?: block.caption.toPlainText().takeIf { it.isNotBlank() }
                    val effectiveBlock = remember(block, linkedAudioObj) {
                        val objDur = linkedAudioObj?.let { extractAudioDurationSeconds(it) } ?: 0
                        if (objDur > 0 && objDur != block.durationSeconds) {
                            block.copy(durationSeconds = objDur)
                        } else {
                            block
                        }
                    }
                    AudioBlockView(
                        block = effectiveBlock,
                        onRecordingFinished = { bytes, durationSeconds ->
                            onAudioRecorded?.invoke(block.id, bytes, durationSeconds)
                        },
                        refreshKey = block.assetId in mediaRefreshKeys,
                        audioTitle = resolvedAudioTitle,
                        onOpenAudioObject = linkedAudioObj?.let { audioObj ->
                            if (onNavigateToObject != null) ({ onNavigateToObject(audioObj.id) }) else null
                        },
                    )
                }

                is Block.Pdf -> {
                    PdfBlockView(block = block, refreshKey = block.assetId in mediaRefreshKeys)
                }

                is Block.ObjectEmbed -> {
                    val targetObj = allObjects.find { obj -> obj.id == block.targetObjectId }
                    val targetType = allTypes.find { type -> type.id == targetObj?.typeId }
                    val targetAudioAssetId = targetObj?.let { resolveAudioAssetId(it) }
                    val targetImageAssetId = targetObj?.let { resolveImageAssetId(it) }
                    if (targetObj != null && !targetImageAssetId.isNullOrBlank()) {
                        ReferencedImageBlockView(
                            imageObject = targetObj,
                            assetId = targetImageAssetId,
                            refreshKey = targetImageAssetId in mediaRefreshKeys,
                            onTapImage = if (onTapImage != null) {
                                {
                                    onTapImage(
                                        Block.Image(
                                            id = block.id,
                                            assetId = targetImageAssetId,
                                            caption = targetObj.title,
                                        ),
                                    )
                                }
                            } else null,
                            onOpenImageObject = if (onNavigateToObject != null) ({ onNavigateToObject(targetObj.id) }) else null,
                        )
                    } else if (targetObj != null && !targetAudioAssetId.isNullOrBlank()) {
                        AudioBlockView(
                            block = Block.Audio(
                                id = block.id,
                                assetId = targetAudioAssetId,
                                durationSeconds = extractAudioDurationSeconds(targetObj).coerceAtLeast(1),
                                caption = targetObj.title,
                            ),
                            onRecordingFinished = { _, _ -> },
                            refreshKey = targetAudioAssetId in mediaRefreshKeys,
                            audioTitle = targetObj.plainTitle,
                            onOpenAudioObject = if (onNavigateToObject != null) ({ onNavigateToObject(targetObj.id) }) else null,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppColors.SurfaceElevated, RoundedCornerShape(8.dp))
                                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp))
                                .clickable { onNavigateToObject?.invoke(block.targetObjectId) }
                                .padding(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (targetObj?.icon?.emoji != null) {
                                    Text(text = targetObj.icon.emoji, fontSize = 16.sp)
                                } else {
                                    Icon(
                                        Icons.Default.AlternateEmail,
                                        contentDescription = null,
                                        tint = AppColors.Accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = targetObj?.plainTitle?.ifBlank { null }
                                            ?: stringResource(R.string.linked_object_prefix, "${block.targetObjectId.take(8)}…"),
                                        fontSize = 14.sp,
                                        color = AppColors.Accent,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    targetType?.let { type ->
                                        Text(
                                            text = type.name,
                                            fontSize = 11.sp,
                                            color = AppColors.TextSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is Block.Canvas -> {
                    CanvasInlineBlockView(
                        block = block,
                        onOpenCanvas = { onOpenCanvas?.invoke(block) },
                    )
                }
            }
        }
    }
}

/**
 * Inline presentation of a [Block.Canvas] on a document page.
 */
@Composable
private fun CanvasInlineBlockView(
    block: Block.Canvas,
    onOpenCanvas: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.SurfaceElevated, RoundedCornerShape(14.dp))
            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenCanvas)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "🎨", fontSize = 18.sp)
            Text(
                text = stringResource(R.string.whiteboard_inline_canvas_badge),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AppColors.Accent.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.whiteboard_nodes_and_strokes_badge,
                        block.data.nodes.size,
                        block.data.strokes.size,
                    ),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.Accent,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.Surface)
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenCanvas)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.whiteboard_open_canvas_button),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.Accent,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = stringResource(R.string.whiteboard_open_canvas_button),
                tint = AppColors.Accent,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * Bookmark card block view.
 * Tapping invokes [onTap] (which opens Pattern F preview popup with Open in browser / Remove options).
 */
@Composable
private fun BookmarkBlockView(
    block: Block.Bookmark,
    onTap: () -> Unit,
) {
    if (block.title.isBlank() && block.description.isBlank() && block.url.isNotBlank()) {
        LinkPreviewCard(
            url = block.url,
            onOpenInBrowser = { onTap() },
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.SurfaceElevated, RoundedCornerShape(12.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(12.dp))
                .clickable(onClick = onTap)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.Surface, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (!block.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = block.thumbnailUrl,
                        contentDescription = block.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().privacyImage(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = AppColors.Accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = block.title.ifBlank { block.url },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (block.description.isNotBlank()) {
                    Text(
                        text = block.description,
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = block.url,
                    fontSize = 11.sp,
                    color = AppColors.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Flat Toggle block view: children are subsequent blocks in the document with `indentLevel > block.indentLevel`.
 * When collapsed and `hiddenChildrenCount > 0`, displays a `+N` badge on the right.
 */
@Composable
private fun ToggleBlockView(
    block: Block.Toggle,
    hiddenChildrenCount: Int,
    focusRequester: FocusRequester,
    onFocusGained: () -> Unit,
    onUpdate: (Block) -> Unit,
    onTextChange: (String) -> Unit,
    onSpansChange: ((List<InlineSpan>, Int) -> Unit)?,
    onEnterPressed: (before: String, after: String) -> Unit,
    onEnterAtOffset: ((Int) -> Unit)?,
    onBackspaceOnEmpty: () -> Unit,
    allObjects: List<PkmObject>,
    allTypes: List<ObjectType>,
    tagColors: Map<String, String> = emptyMap(),
    onNavigateToObject: ((String) -> Unit)?,
    onNavigateToTag: ((String) -> Unit)?,
    onNavigateToDate: ((java.time.LocalDate) -> Unit)? = null,
    onTapInlinePill: ((MarkdownPill) -> Unit)?,
    activeTypingFormats: Set<String>?,
    activeTextColor: String?,
    activeBgColor: String?,
    hasExplicitTextColor: Boolean = false,
    hasExplicitBgColor: Boolean = false,
    onCursorMoved: (() -> Unit)? = null,
    onSelectionChange: ((TextRange) -> Unit)?,
    requestedCursorOffset: Int?,
    textSyncRevision: Int = 0,
    onCursorOffsetConsumed: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (block.isExpanded) Icons.Default.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.block_type_toggle),
            tint = AppColors.TextSecondary,
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { onUpdate(block.copy(isExpanded = !block.isExpanded)) },
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(modifier = Modifier.weight(1f)) {
            BlockTextField(
                text = block.spans.toVisibleString(),
                onTextChange = onTextChange,
                spans = block.spans,
                onSpansChange = onSpansChange,
                placeholder = stringResource(R.string.placeholder_toggle),
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary,
                ),
                focusRequester = focusRequester,
                onFocusGained = onFocusGained,
                onEnter = onEnterPressed,
                onEnterAtOffset = onEnterAtOffset,
                onBackspaceOnEmpty = onBackspaceOnEmpty,
                allObjects = allObjects,
                allTypes = allTypes,
                tagColors = tagColors,
                onNavigateToObject = onNavigateToObject,
                onNavigateToTag = onNavigateToTag,
                onNavigateToDate = onNavigateToDate,
                onTapInlinePill = onTapInlinePill,
                activeTypingFormats = activeTypingFormats,
                activeTextColor = activeTextColor,
                activeBgColor = activeBgColor,
                hasExplicitTextColor = hasExplicitTextColor,
                hasExplicitBgColor = hasExplicitBgColor,
                onCursorMoved = onCursorMoved,
                onSelectionChange = onSelectionChange,
                requestedCursorOffset = requestedCursorOffset,
                externalTextRevision = textSyncRevision,
                onCursorOffsetConsumed = onCursorOffsetConsumed,
            )
        }
        if (!block.isExpanded && hiddenChildrenCount > 0) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AppColors.SurfaceElevated, RoundedCornerShape(6.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(6.dp))
                    .clickable { onUpdate(block.copy(isExpanded = true)) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.toggle_hidden_items_badge, hiddenChildrenCount),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Accent,
                )
            }
        }
    }
}

@Composable
fun MathBlockView(
    block: Block.Math,
    focusRequester: FocusRequester,
    isFocused: Boolean,
    onFocusGained: () -> Unit,
    onUpdate: (Block) -> Unit,
    modifier: Modifier = Modifier,
    onFocusCleared: () -> Unit = {},
    onExitBlock: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current

    var isTextFieldFocused by remember { mutableStateOf(value = false) }
    var forceCollapsed by remember { mutableStateOf(value = false) }

    LaunchedEffect(isFocused) {
        if (!isFocused) {
            forceCollapsed = false
            isTextFieldFocused = false
        }
    }

    val isEditing = (isFocused || isTextFieldFocused) && !forceCollapsed

    val presentableMath = remember(block.latex) {
        LatexFormatter.formatToPresentable(block.latex)
    }

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(block.latex, TextRange(block.latex.length)))
    }
    var lastEmittedLatex by remember { mutableStateOf(block.latex) }

    LaunchedEffect(block.latex) {
        if (block.latex != lastEmittedLatex && block.latex != textFieldValue.text) {
            lastEmittedLatex = block.latex
            textFieldValue = TextFieldValue(
                text = block.latex,
                selection = TextRange(
                    textFieldValue.selection.start.coerceIn(0, block.latex.length),
                    textFieldValue.selection.end.coerceIn(0, block.latex.length),
                ),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        // 1. Presentable / Final LaTeX Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.Surface, RoundedCornerShape(14.dp))
                .border(
                    width = 1.dp,
                    color = if (isEditing) AppColors.Accent.copy(alpha = 0.5f) else AppColors.BorderSubtle,
                    shape = RoundedCornerShape(10.dp),
                )
                .clickable {
                    forceCollapsed = false
                    onFocusGained()
                    focusRequester.requestFocus()
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isEditing) {
                        Text(
                            text = stringResource(R.string.tex_preview_title),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextSecondary,
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppColors.Ice.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.tex_badge),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (presentableMath.isNotBlank()) {
                        Text(
                            text = presentableMath,
                            style = TextStyle(
                                fontFamily = FontFamily.Serif,
                                fontSize = 18.sp,
                                fontStyle = FontStyle.Italic,
                                fontWeight = FontWeight.Normal,
                                color = AppColors.TextPrimary,
                                textAlign = TextAlign.Center,
                                lineHeight = 26.sp,
                                letterSpacing = 0.5.sp,
                            ),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.empty_math_formula),
                            style = TextStyle(
                                fontFamily = FontFamily.Serif,
                                fontSize = 14.sp,
                                fontStyle = FontStyle.Italic,
                                color = AppColors.TextTertiary,
                                textAlign = TextAlign.Center,
                            ),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // 2. TeX-Code card - ONLY VISIBLE WHEN EDITING
        if (isEditing) {
            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.CodeBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.tex_code_title),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.TextSecondary,
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    forceCollapsed = true
                                    isTextFieldFocused = false
                                    focusManager.clearFocus()
                                    onFocusCleared()
                                    onExitBlock?.invoke()
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.action_done),
                                tint = AppColors.Accent,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.action_done),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppColors.Accent,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            // Exit math block when pressing Enter on an empty trailing line
                            if (onExitBlock != null &&
                                newValue.text.length > textFieldValue.text.length &&
                                newValue.text.endsWith("\n\n")
                            ) {
                                val trimmedLatex = newValue.text.trimEnd('\n')
                                lastEmittedLatex = trimmedLatex
                                textFieldValue = TextFieldValue(trimmedLatex, TextRange(trimmedLatex.length))
                                onUpdate(block.copy(latex = trimmedLatex))
                                onExitBlock()
                                return@BasicTextField
                            }
                            textFieldValue = newValue
                            if (newValue.text != lastEmittedLatex) {
                                lastEmittedLatex = newValue.text
                                onUpdate(block.copy(latex = newValue.text))
                            }
                        },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = AppColors.TextPrimary,
                        ),
                        cursorBrush = SolidColor(AppColors.Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp)
                            .focusRequester(focusRequester)
                            .onFocusChanged { state ->
                                isTextFieldFocused = state.isFocused
                                if (state.isFocused) onFocusGained()
                            },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Default,
                        ),
                        decorationBox = { innerTextField ->
                            if (textFieldValue.text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.placeholder_math),
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                        color = AppColors.TextTertiary,
                                    ),
                                )
                            }
                            innerTextField()
                        },
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { /* Learn more action */ },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = AppColors.TextSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.learn_more),
                                fontSize = 12.sp,
                                color = AppColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TableBlockView(
    block: Block.Table,
    onUpdate: (Block) -> Unit,
    modifier: Modifier = Modifier,
    onFocusGained: () -> Unit = {},
    onActiveColumnChange: ((Int) -> Unit)? = null,
) {
    var showTableMenu by remember { mutableStateOf(value = false) }
    val colCount = remember(block.rows) { block.rows.maxOfOrNull { it.size } ?: 2 }
    val useHorizontalScroll = colCount > 3

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            val tableContainerModifier = if (useHorizontalScroll) {
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            } else {
                Modifier.weight(1f)
            }

            Box(modifier = tableContainerModifier) {
                Column(
                    modifier = Modifier
                        .then(if (useHorizontalScroll) Modifier.width((colCount * 120).dp) else Modifier.fillMaxWidth())
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(6.dp)),
                ) {
                    block.rows.forEachIndexed { rowIndex, row ->
                        if (rowIndex > 0) {
                            HorizontalDivider(color = AppColors.BorderSubtle, thickness = 1.dp)
                        }
                        val isHeaderRow = rowIndex == 0
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .background(
                                    if (isHeaderRow) Color(0x24FFFFFF) else Color.Transparent,
                                ),
                        ) {
                            row.forEachIndexed { colIndex, cellText ->
                                if (colIndex > 0) {
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(AppColors.BorderSubtle),
                                    )
                                }
                                val alignSpec = block.alignments.getOrNull(colIndex) ?: "left"
                                val textAlign = when (alignSpec) {
                                    "center" -> TextAlign.Center
                                    "right" -> TextAlign.End
                                    else -> TextAlign.Start
                                }
                                var cellValue by remember(rowIndex, colIndex) { mutableStateOf(cellText) }
                                var lastEmittedCell by remember(rowIndex, colIndex) { mutableStateOf(cellText) }
                                LaunchedEffect(cellText) {
                                    if (cellText != lastEmittedCell && cellText != cellValue) {
                                        cellValue = cellText
                                        lastEmittedCell = cellText
                                    }
                                }
                                BasicTextField(
                                    value = cellValue,
                                    onValueChange = { newVal ->
                                        cellValue = newVal
                                        if (newVal != lastEmittedCell) {
                                            lastEmittedCell = newVal
                                            val newRows = block.rows.mapIndexed { rIdx, rList ->
                                                if (rIdx == rowIndex) {
                                                    rList.mapIndexed { cIdx, cVal -> if (cIdx == colIndex) newVal else cVal }
                                                } else {
                                                    rList
                                                }
                                            }
                                            onUpdate(block.copy(rows = newRows))
                                        }
                                    },
                                    textStyle = TextStyle(
                                        fontSize = 14.sp,
                                        fontWeight = if (isHeaderRow) FontWeight.SemiBold else FontWeight.Normal,
                                        color = AppColors.TextPrimary,
                                        textAlign = textAlign,
                                    ),
                                    cursorBrush = SolidColor(AppColors.Accent),
                                    modifier = Modifier
                                        .weight(1f)
                                        .widthIn(min = 84.dp)
                                        .onFocusChanged { state ->
                                            if (state.isFocused) {
                                                onFocusGained()
                                                onActiveColumnChange?.invoke(colIndex)
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    decorationBox = { innerTextField ->
                                        if (cellValue.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.placeholder_cell),
                                                style = TextStyle(
                                                    fontSize = 14.sp,
                                                    color = AppColors.TextTertiary,
                                                    textAlign = textAlign,
                                                ),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                        innerTextField()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box {
                    IconButton(
                        onClick = { showTableMenu = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreHoriz,
                            contentDescription = stringResource(R.string.table_options),
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showTableMenu,
                        onDismissRequest = { showTableMenu = false },
                        modifier = Modifier.background(AppColors.SurfaceElevated),
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_add_column), color = AppColors.TextPrimary) },
                            onClick = {
                                val newRows = block.rows.map { it + "" }
                                onUpdate(block.copy(rows = newRows))
                                showTableMenu = false
                            },
                        )
                        if ((block.rows.firstOrNull()?.size ?: 0) > 1) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete_column), color = AppColors.TextPrimary) },
                                onClick = {
                                    val newRows = block.rows.map { if (it.size > 1) it.dropLast(1) else it }
                                    onUpdate(block.copy(rows = newRows))
                                    showTableMenu = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_add_row), color = AppColors.TextPrimary) },
                            onClick = {
                                val cols = block.rows.firstOrNull()?.size ?: 2
                                val newRows = block.rows + listOf(List(cols) { "" })
                                onUpdate(block.copy(rows = newRows))
                                showTableMenu = false
                            },
                        )
                        if (block.rows.size > 1) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete_row), color = AppColors.TextPrimary) },
                                onClick = {
                                    val newRows = block.rows.dropLast(1)
                                    onUpdate(block.copy(rows = newRows))
                                    showTableMenu = false
                                },
                            )
                        }
                        HorizontalDivider(color = AppColors.BorderSubtle)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_clear_table), color = AppColors.Danger) },
                            onClick = {
                                val cols = block.rows.firstOrNull()?.size ?: 2
                                val newRows = List(block.rows.size) { List(cols) { "" } }
                                onUpdate(block.copy(rows = newRows))
                                showTableMenu = false
                            },
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.Surface, RoundedCornerShape(14.dp))
                        .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(14.dp))
                        .clickable {
                            val newRows = block.rows.map { it + "" }
                            onUpdate(block.copy(rows = newRows))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.action_add_column),
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 42.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.SurfaceElevated, RoundedCornerShape(8.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp))
                .clickable {
                    val cols = block.rows.firstOrNull()?.size ?: 2
                    val newRows = block.rows + listOf(List(cols) { "" })
                    onUpdate(block.copy(rows = newRows))
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.action_add_row),
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun CodeBlockView(
    block: Block.Code,
    focusRequester: FocusRequester,
    onFocusGained: () -> Unit,
    onUpdate: (Block) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onExitBlock: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    var showLangMenu by remember { mutableStateOf(value = false) }
    var showOptionsMenu by remember { mutableStateOf(value = false) }

    val currentLangLabel = CODE_LANGUAGES.find { it.first.equals(block.language, ignoreCase = true) }?.let {
        stringResource(it.second)
    } ?: block.language.replaceFirstChar { it.uppercase() }

    val (badgeText, badgeColor, badgeTextColor) = when (block.language.lowercase()) {
        "javascript", "js" -> Triple("JS", Color(0xFFF7DF1E), Color.Black)
        "typescript", "ts" -> Triple("TS", Color(0xFF3178C6), Color.White)
        "kotlin", "kt" -> Triple("KT", Color(0xFF7F52FF), Color.White)
        "python", "py" -> Triple("PY", Color(0xFF3776AB), Color.White)
        "html" -> Triple("HTML", Color(0xFFE34F26), Color.White)
        "css" -> Triple("CSS", Color(0xFF1572B6), Color.White)
        "json" -> Triple("JSON", Color(0xFF5A5A5A), Color.White)
        "sql" -> Triple("SQL", Color(0xFFCC292B), Color.White)
        else -> Triple(block.language.take(3).uppercase(), AppColors.BorderHover, Color.White)
    }

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(block.code, TextRange(block.code.length)))
    }
    var lastEmittedCode by remember { mutableStateOf(block.code) }

    LaunchedEffect(block.code) {
        if (block.code != lastEmittedCode && block.code != textFieldValue.text) {
            lastEmittedCode = block.code
            textFieldValue = TextFieldValue(
                text = block.code,
                selection = TextRange(
                    textFieldValue.selection.start.coerceIn(0, block.code.length),
                    textFieldValue.selection.end.coerceIn(0, block.code.length),
                ),
            )
        }
    }

    val lineCount = remember(textFieldValue.text) {
        val lines = textFieldValue.text.lines()
        if (lines.isEmpty()) 1 else lines.size
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.CodeBackground, RoundedCornerShape(10.dp))
            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showLangMenu = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeTextColor,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = currentLangLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                DropdownMenu(
                    expanded = showLangMenu,
                    onDismissRequest = { showLangMenu = false },
                    modifier = Modifier.background(AppColors.SurfaceElevated),
                ) {
                    CODE_LANGUAGES.forEach { (langKey, strRes) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(strRes), color = AppColors.TextPrimary) },
                            onClick = {
                                onUpdate(block.copy(language = langKey))
                                showLangMenu = false
                            },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", block.code)))
                        }
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.copy_code),
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Box {
                    IconButton(
                        onClick = { showOptionsMenu = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreHoriz,
                            contentDescription = stringResource(R.string.code_options),
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false },
                        modifier = Modifier.background(AppColors.SurfaceElevated),
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_clear_code), color = AppColors.TextPrimary) },
                            onClick = {
                                textFieldValue = TextFieldValue("")
                                onUpdate(block.copy(code = ""))
                                showOptionsMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete_block), color = AppColors.Danger) },
                            onClick = {
                                onDelete()
                                showOptionsMenu = false
                            },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            val gutterText = remember(lineCount) {
                (1..lineCount).joinToString("\n")
            }
            Text(
                text = gutterText,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(end = 12.dp),
            )

            BasicTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    // Exit code block when pressing Enter on an empty trailing line
                    if (onExitBlock != null &&
                        newValue.text.length > textFieldValue.text.length &&
                        newValue.text.endsWith("\n\n")
                    ) {
                        val trimmedCode = newValue.text.trimEnd('\n')
                        lastEmittedCode = trimmedCode
                        textFieldValue = TextFieldValue(trimmedCode, TextRange(trimmedCode.length))
                        onUpdate(block.copy(code = trimmedCode))
                        onExitBlock()
                        return@BasicTextField
                    }
                    textFieldValue = newValue
                    if (newValue.text != lastEmittedCode) {
                        lastEmittedCode = newValue.text
                        onUpdate(block.copy(code = newValue.text))
                    }
                },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = AppColors.TextPrimary,
                ),
                cursorBrush = SolidColor(AppColors.Accent),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) onFocusGained() },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Default,
                ),
                decorationBox = { innerTextField ->
                    if (textFieldValue.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.placeholder_code),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = AppColors.TextTertiary,
                        )
                    }
                    innerTextField()
                },
            )
        }
    }
}

/**
 * Pure WYSIWYG rich-text field backed by structured [InlineSpan] runs and [RichSpanVisualTransformation].
 * Uses 1-to-1 character offset mapping (`OffsetMapping.Identity`) so cursor movement and selection
 * are always exact while zero raw Markdown characters appear on screen.
 */
@Composable
fun BlockTextField(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    focusRequester: FocusRequester,
    onFocusGained: () -> Unit,
    modifier: Modifier = Modifier,
    spans: List<InlineSpan>? = null,
    onSpansChange: ((List<InlineSpan>, Int) -> Unit)? = null,
    onEnter: ((beforeText: String, afterText: String) -> Unit)? = null,
    onEnterAtOffset: ((Int) -> Unit)? = null,
    onBackspaceOnEmpty: (() -> Unit)? = null,
    isMultiline: Boolean = false,
    allObjects: List<PkmObject> = emptyList(),
    allTypes: List<ObjectType> = emptyList(),
    tagColors: Map<String, String> = emptyMap(),
    onNavigateToObject: ((String) -> Unit)? = null,
    onNavigateToTag: ((String) -> Unit)? = null,
    onNavigateToDate: ((java.time.LocalDate) -> Unit)? = null,
    onTapInlinePill: ((MarkdownPill) -> Unit)? = null,
    activeTypingFormats: Set<String>? = null,
    activeTextColor: String? = null,
    activeBgColor: String? = null,
    hasExplicitTextColor: Boolean = false,
    hasExplicitBgColor: Boolean = false,
    onCursorMoved: (() -> Unit)? = null,
    onSelectionChange: ((TextRange) -> Unit)? = null,
    requestedCursorOffset: Int? = null,
    externalTextRevision: Int = 0,
    onCursorOffsetConsumed: (() -> Unit)? = null,
) {
    val effectiveSpans = remember(spans, text) {
        spans ?: RichSpanEngine.fromLegacyMarkdown(text)
    }
    // Callers already pass the visible text alongside structured spans; avoid rebuilding it with
    // an O(n) `toVisibleString()` copy on every recomposition.
    val visibleText = text

    var localSpans by remember { mutableStateOf(effectiveSpans) }
    var lastEmittedText by remember { mutableStateOf(visibleText) }
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(visibleText, TextRange(visibleText.length)))
    }
    var isFieldFocused by remember { mutableStateOf(false) }
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var lastAppliedExternalRevision by remember { mutableIntStateOf(externalTextRevision) }

    val hasRichStyling = remember(localSpans, spans) {
        if (spans != null) MarkdownRenderer.hasRichStylingOrPills(localSpans) else true
    }

    val resolvedTagColors = remember(tagColors, allObjects, hasRichStyling) {
        if (!hasRichStyling) {
            emptyMap()
        } else if (tagColors.isNotEmpty()) {
            tagColors
        } else {
            buildMap {
                for (obj in allObjects) {
                    val hex = obj.icon?.colorHex
                    if (!hex.isNullOrBlank()) {
                        val key = obj.plainTitle.trim().lowercase()
                        if (key.isNotEmpty()) {
                            put(key, hex)
                        }
                    }
                }
            }
        }
    }

    val render = remember(localSpans, textFieldValue.text, spans, hasRichStyling, resolvedTagColors) {
        if (spans != null) {
            if (!hasRichStyling) {
                MarkdownRender(
                    text = AnnotatedString(textFieldValue.text),
                    offsetMapping = OffsetMapping.Identity,
                    pills = emptyList(),
                )
            } else {
                MarkdownRenderer.renderSpans(localSpans, resolvedTagColors)
            }
        } else {
            MarkdownRenderer.render(textFieldValue.text, resolvedTagColors)
        }
    }

    val currentRender by rememberUpdatedState(render)
    val currentAllObjects by rememberUpdatedState(allObjects)
    val currentAllTypes by rememberUpdatedState(allTypes)
    val currentOnNavigateToObject by rememberUpdatedState(onNavigateToObject)
    val currentOnNavigateToTag by rememberUpdatedState(onNavigateToTag)
    val currentOnNavigateToDate by rememberUpdatedState(onNavigateToDate)
    val currentOnTapInlinePill by rememberUpdatedState(onTapInlinePill)

    val visualTransformation = remember(localSpans, spans, hasRichStyling, resolvedTagColors, render.text) {
        if (spans != null) {
            if (!hasRichStyling) {
                VisualTransformation.None
            } else {
                RichSpanVisualTransformation(
                    spans = localSpans,
                    tagColors = resolvedTagColors,
                    preRenderedText = render.text,
                )
            }
        } else {
            MarkdownVisualTransformation(resolvedTagColors)
        }
    }

    LaunchedEffect(visibleText, effectiveSpans, requestedCursorOffset, externalTextRevision) {
        val targetOffset = requestedCursorOffset?.coerceIn(0, visibleText.length)
        if (targetOffset != null) {
            // Explicit programmatic caret move (new block, toolbar insert, undo/redo). Apply the
            // parent text once and let the IME continue from there.
            localSpans = effectiveSpans
            val nextSelection = TextRange(targetOffset)
            lastEmittedText = visibleText
            lastAppliedExternalRevision = externalTextRevision
            textFieldValue = TextFieldValue(
                text = visibleText,
                selection = nextSelection,
                composition = null,
            )
            onSelectionChange?.invoke(nextSelection)
            onCursorOffsetConsumed?.invoke()
        } else if (visibleText == textFieldValue.text) {
            // Parent caught up with what this field already shows; refresh styles only.
            localSpans = effectiveSpans
            lastAppliedExternalRevision = externalTextRevision
        } else if (!isFieldFocused || externalTextRevision != lastAppliedExternalRevision) {
            // External update while the field is not being edited (remote sync, block conversion),
            // or an explicit rewrite from the editor (Enter split, undo, redo). Safe to replace the
            // whole value.
            localSpans = effectiveSpans
            val previous = textFieldValue
            val nextSelection = TextRange(
                previous.selection.start.coerceIn(0, visibleText.length),
                previous.selection.end.coerceIn(0, visibleText.length),
            )
            lastEmittedText = visibleText
            lastAppliedExternalRevision = externalTextRevision
            textFieldValue = TextFieldValue(
                text = visibleText,
                selection = nextSelection,
                composition = null,
            )
            onSelectionChange?.invoke(nextSelection)
        }
        // While focused, the IME owns the text. The parent snapshot can be older than the field
        // (typing is batched by 180 ms), so applying it here would delete recent keystrokes and
        // corrupt words. Wait until the parent catches up, the field loses focus, or the editor
        // explicitly bumps [externalTextRevision].
    }

    // The IME reports Backspace as an InputConnection edit command, not as a Compose key event.
    // Wrap the connection so a Backspace at offset 0 can demote/delete the block even when the
    // field is empty (where there is no text change and onValueChange would never fire).
    val currentValue by rememberUpdatedState(textFieldValue)
    val currentIsFieldFocused by rememberUpdatedState(isFieldFocused)
    val currentOnFocusGained by rememberUpdatedState(onFocusGained)
    val currentOnBackspaceOnEmpty by rememberUpdatedState(onBackspaceOnEmpty)
    val backspaceInterceptor = remember {
        BackspaceTextInputInterceptor(
            shouldHandleBackspace = {
                val value = currentValue
                currentOnBackspaceOnEmpty != null &&
                    value.composition == null &&
                    value.selection.collapsed &&
                    value.selection.start == 0
            },
            onBackspace = { currentOnBackspaceOnEmpty?.invoke() },
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                // Hardware keyboards (and some IMEs) deliver Backspace as a real key event.
                val isBackspaceKeyDown = event.type == KeyEventType.KeyDown && event.key == Key.Backspace
                val isCaretAtStart = textFieldValue.selection.collapsed && textFieldValue.selection.start == 0
                if (isBackspaceKeyDown && isCaretAtStart && onBackspaceOnEmpty != null) {
                    onBackspaceOnEmpty()
                    true
                } else {
                    false
                }
            }
            // Taps on an empty block's padding/placeholder never reach the clickable below (the
            // BasicTextField swallows them), so such a block could not be focused. Request focus
            // only for plain TAPS: requesting it on touch-down broke the swipe-to-indent gesture,
            // because focusing the block disables SubtleSwipeActionBox mid-gesture and cancels
            // the swipe.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var isTap = true
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                        if (change == null || !change.pressed) break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            isTap = false
                            break
                        }
                    }
                    if (isTap && !currentIsFieldFocused) {
                        runCatching { focusRequester.requestFocus() }
                        currentOnFocusGained()
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                // Only request focus when the tap lands on the surrounding padding. The inner
                // BasicTextField handles taps on the text itself and places the native IME caret
                // exactly where the user tapped; never force the caret to the end here.
                focusRequester.requestFocus()
                onFocusGained()
            },
    ) {
        InterceptPlatformTextInput(interceptor = backspaceInterceptor) {
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val oldText = textFieldValue.text
                val oldSelection = textFieldValue.selection
                // Fast newline detection for the common single-character insertion (Enter key).
                // Only fall back to counting newlines for multi-character edits (paste, IME commit).
                val insertedNewline = if (newValue.text.length == oldText.length + 1) {
                    val caret = newValue.selection.start
                    caret in 1..newValue.text.length && newValue.text[caret - 1] == '\n'
                } else {
                    newValue.text.count { it == '\n' } > oldText.count { it == '\n' }
                }
                val hasNewlineAdded = (!isMultiline) && (onEnter != null || onEnterAtOffset != null) &&
                    insertedNewline
                if (hasNewlineAdded) {
                    val insertedAt = (newValue.selection.start - 1).coerceIn(0, oldText.length)
                    val before = oldText.substring(0, insertedAt)
                    val after = oldText.substring(insertedAt)
                    lastEmittedText = before
                    textFieldValue = TextFieldValue(
                        text = before,
                        selection = TextRange(before.length),
                        composition = null,
                    )
                    if (onEnterAtOffset != null) {
                        onEnterAtOffset.invoke(insertedAt)
                    } else {
                        onEnter?.invoke(before, after)
                    }
                    return@BasicTextField
                }

                if (spans != null && onSpansChange != null) {
                    if (newValue.text != oldText) {
                        val (updatedSpans, nextCaret) = RichSpanEngine.applyTextEdit(
                            oldSpans = localSpans,
                            oldText = oldText,
                            newText = newValue.text,
                            newCaret = newValue.selection.start,
                            activeFormats = activeTypingFormats,
                            activeTextColor = activeTextColor,
                            activeBgColor = activeBgColor,
                            hasExplicitTextColor = hasExplicitTextColor,
                            hasExplicitBgColor = hasExplicitBgColor,
                        )
                        localSpans = updatedSpans
                        val updatedVisible = updatedSpans.toVisibleString()
                        if (updatedVisible == newValue.text) {
                            // Preserve newValue (including IME composition & exact selection) untouched
                            // so Android's InputMethodManager never restarts or stutters while typing!
                            lastEmittedText = newValue.text
                            textFieldValue = newValue
                            onSpansChange(updatedSpans, newValue.selection.start)
                            if (newValue.selection != oldSelection) {
                                onSelectionChange?.invoke(newValue.selection)
                            }
                        } else {
                            // Only differs when an atomic pill (Mention, Tag, Date) was deleted as a unit
                            val safeSelection = TextRange(nextCaret.coerceIn(0, updatedVisible.length))
                            lastEmittedText = updatedVisible
                            textFieldValue = TextFieldValue(
                                text = updatedVisible,
                                selection = safeSelection,
                                composition = null,
                            )
                            onSpansChange(updatedSpans, safeSelection.start)
                            onSelectionChange?.invoke(safeSelection)
                        }
                    } else {
                        textFieldValue = newValue
                        if (newValue.selection != oldSelection) {
                            onSelectionChange?.invoke(newValue.selection)
                            onCursorMoved?.invoke()
                        }
                    }
                    return@BasicTextField
                }

                lastEmittedText = newValue.text
                textFieldValue = newValue
                if (newValue.text != oldText) {
                    onTextChange(newValue.text)
                }
                if (newValue.selection != oldSelection) {
                    onSelectionChange?.invoke(newValue.selection)
                }
            },
            onTextLayout = { textLayoutResult = it },
            textStyle = textStyle.copy(
                lineHeight = if (textStyle.lineHeight.isSpecified && textStyle.lineHeight.value > 0) {
                    textStyle.lineHeight
                } else {
                    (textStyle.fontSize.value * 1.45f).sp
                },
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            cursorBrush = SolidColor(AppColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                // Empty blocks have a zero-height text layout; without a minimum the tap lands
                // outside the field and the block never receives focus (keyboard stays closed).
                .heightIn(min = 24.dp)
                .padding(horizontal = 3.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    isFieldFocused = state.isFocused
                    if (state.isFocused) {
                        onFocusGained()
                        onSelectionChange?.invoke(textFieldValue.selection)
                    }
                }
                .drawBehind {
                    val layout = textLayoutResult ?: return@drawBehind
                    if (render.pills.isEmpty()) return@drawBehind
                    val textLen = layout.layoutInput.text.length
                    if (textLen == 0) return@drawBehind

                    val defaultCornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    val tagCornerRadius = CornerRadius(5.5.dp.toPx(), 5.5.dp.toPx())
                    val defaultHPad = 4.5.dp.toPx()
                    val tagHPad = 3.75.dp.toPx()
                    val minPillGapPx = 2.5.dp.toPx()
                    val defaultVPad = 2.dp.toPx()
                    val defaultBorderWidth = 1.dp.toPx()
                    val tagBorderWidth = 0.9.dp.toPx()

                    for (pillIdx in render.pills.indices) {
                        val pill = render.pills[pillIdx]
                        val start = pill.transformedRange.first
                        val end = pill.transformedRange.last + 1
                        if (start >= end || start >= textLen) continue

                        val clampedEnd = end.coerceAtMost(textLen)
                        val lineStart = layout.getLineForOffset(start)
                        val lineEnd = layout.getLineForOffset((clampedEnd - 1).coerceAtMost(textLen - 1))
                        val isTagPill = pill.kind == MarkdownPillKind.TAG
                        val desiredHPad = if (isTagPill) tagHPad else defaultHPad

                        for (line in lineStart..lineEnd) {
                            val left = if (line == lineStart) {
                                layout.getHorizontalPosition(start, true)
                            } else {
                                layout.getLineLeft(line)
                            }
                            val right = if (line == lineEnd) {
                                layout.getHorizontalPosition(clampedEnd, true)
                            } else {
                                layout.getLineRight(line)
                            }
                            val textLeft = minOf(left, right)
                            val textRight = maxOf(left, right)

                            var leftPad = desiredHPad
                            var rightPad = desiredHPad

                            // Prevent overlapping the preceding pill on the same line
                            if (line == lineStart && pillIdx > 0) {
                                val prevPill = render.pills[pillIdx - 1]
                                val prevEnd = (prevPill.transformedRange.last + 1).coerceIn(0, textLen)
                                if (prevEnd <= start && prevEnd > 0) {
                                    val prevEndLine = layout.getLineForOffset(prevEnd - 1)
                                    if (prevEndLine == line) {
                                        val prevRight = layout.getHorizontalPosition(prevEnd, true)
                                        val gap = textLeft - prevRight
                                        if (gap >= 0f && gap < desiredHPad * 2f + minPillGapPx) {
                                            leftPad = ((gap - minPillGapPx) * 0.5f).coerceAtLeast(1.dp.toPx())
                                        }
                                    }
                                }
                            }

                            // Prevent overlapping the following pill on the same line
                            if (line == lineEnd && pillIdx < render.pills.lastIndex) {
                                val nextPill = render.pills[pillIdx + 1]
                                val nextStart = nextPill.transformedRange.first.coerceIn(0, textLen)
                                if (nextStart >= clampedEnd && nextStart < textLen) {
                                    val nextStartLine = layout.getLineForOffset(nextStart)
                                    if (nextStartLine == line) {
                                        val nextLeft = layout.getHorizontalPosition(nextStart, true)
                                        val gap = nextLeft - textRight
                                        if (gap >= 0f && gap < desiredHPad * 2f + minPillGapPx) {
                                            rightPad = ((gap - minPillGapPx) * 0.5f).coerceAtLeast(1.dp.toPx())
                                        }
                                    }
                                }
                            }

                            val lineTop = layout.getLineTop(line)
                            val lineBottom = layout.getLineBottom(line)
                            val lineHeight = lineBottom - lineTop
                            val verticalInset = if (isTagPill) {
                                (lineHeight * 0.115f).coerceIn(1.75.dp.toPx(), 3.5.dp.toPx())
                            } else {
                                defaultVPad
                            }

                            val rectLeft = textLeft - leftPad
                            val rectRight = textRight + rightPad
                            val rectTop = lineTop + verticalInset
                            val rectBottom = lineBottom - verticalInset

                            val rectWidth = rectRight - rectLeft
                            val rectHeight = rectBottom - rectTop
                            if (rectWidth <= 0f || rectHeight <= 0f) continue

                            val activeRadius = if (isTagPill) tagCornerRadius else defaultCornerRadius
                            val activeStroke = if (isTagPill) tagBorderWidth else defaultBorderWidth

                            if (isTagPill) {
                                // Subtle dark backing so the formal tag tint remains pure inside colored blocks or callouts
                                drawRoundRect(
                                    color = AppColors.Surface.copy(alpha = 0.72f),
                                    topLeft = Offset(rectLeft, rectTop),
                                    size = Size(rectWidth, rectHeight),
                                    cornerRadius = activeRadius,
                                )
                            }

                            drawRoundRect(
                                color = pill.backgroundColor,
                                topLeft = Offset(rectLeft, rectTop),
                                size = Size(rectWidth, rectHeight),
                                cornerRadius = activeRadius,
                            )
                            pill.borderColor?.let { borderColor ->
                                drawRoundRect(
                                    color = borderColor,
                                    topLeft = Offset(rectLeft, rectTop),
                                    size = Size(rectWidth, rectHeight),
                                    cornerRadius = activeRadius,
                                    style = Stroke(width = activeStroke),
                                )
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val pills = currentRender.pills
                        if (pills.isEmpty()) return@awaitEachGesture
                        val layout = textLayoutResult ?: return@awaitEachGesture
                        val padPx = 8.dp.toPx()
                        val hitPill = findHitPill(layout, pills, down.position, padPx)
                            ?: return@awaitEachGesture
                        down.consume()

                        var isTap = true
                        var finished = false
                        while (!finished) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                            if (change == null) {
                                isTap = false
                                finished = true
                            } else if (!change.pressed) {
                                finished = true
                                isTap = isTap &&
                                    (change.uptimeMillis - down.uptimeMillis) <
                                    viewConfiguration.longPressTimeoutMillis &&
                                    findHitPill(layout, currentRender.pills, change.position, padPx * 1.5f) == hitPill
                                if (isTap) {
                                    change.consume()
                                }
                            } else if ((change.position - down.position).getDistance() >
                                viewConfiguration.touchSlop
                            ) {
                                isTap = false
                            }
                        }
                        if (!isTap) return@awaitEachGesture

                        val tapHandler = currentOnTapInlinePill
                        if (tapHandler != null) {
                            tapHandler.invoke(hitPill)
                        } else {
                            when (hitPill.kind) {
                                MarkdownPillKind.TAG -> {
                                    val navTag = currentOnNavigateToTag
                                    if (navTag != null) {
                                        navTag.invoke(hitPill.target)
                                    } else {
                                        val tagObj = currentAllObjects.find { obj ->
                                            obj.plainTitle.equals(hitPill.target, ignoreCase = true) ||
                                                obj.plainTitle.replace(" ", "_").equals(hitPill.target, ignoreCase = true)
                                        }
                                        if (tagObj != null) {
                                            currentOnNavigateToObject?.invoke(tagObj.id)
                                        }
                                    }
                                }
                                MarkdownPillKind.MENTION, MarkdownPillKind.WIKILINK -> {
                                    val targetObj = resolveInlinePillObject(hitPill.target, currentAllObjects, currentAllTypes)
                                    if (targetObj != null) {
                                        currentOnNavigateToObject?.invoke(targetObj.id)
                                    }
                                }
                                MarkdownPillKind.DATE -> {
                                    val isoDate = hitPill.target.removePrefix("@").trim().take(10)
                                    val parsed = runCatching { java.time.LocalDate.parse(isoDate) }.getOrNull()
                                    if (parsed != null && currentOnNavigateToDate != null) {
                                        currentOnNavigateToDate?.invoke(parsed)
                                    } else {
                                        currentOnNavigateToObject?.invoke("daily-$isoDate")
                                    }
                                }
                                MarkdownPillKind.LINK -> Unit
                            }
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    val isBackspaceKeyDown = (event.type == KeyEventType.KeyDown) && (event.key == Key.Backspace)
                    val isCaretAtStart = textFieldValue.selection.collapsed && (textFieldValue.selection.start == 0)
                    if (isBackspaceKeyDown && isCaretAtStart && (onBackspaceOnEmpty != null)) {
                        onBackspaceOnEmpty()
                        return@onPreviewKeyEvent true
                    }
                    val isEnter = (event.type == KeyEventType.KeyDown) && (event.key == Key.Enter)
                    if (isEnter && !event.isShiftPressed && !isMultiline &&
                        (onEnter != null || onEnterAtOffset != null)
                    ) {
                        val caret = textFieldValue.selection.start
                            .coerceIn(0, textFieldValue.text.length)
                        if (onEnterAtOffset != null) {
                            onEnterAtOffset.invoke(caret)
                        } else {
                            onEnter?.invoke(
                                textFieldValue.text.substring(0, caret),
                                textFieldValue.text.substring(caret),
                            )
                        }
                        return@onPreviewKeyEvent true
                    }
                    val isShiftEnter = (event.type == KeyEventType.KeyDown) &&
                        (event.key == Key.Enter) && event.isShiftPressed
                    if (isShiftEnter) {
                        val spans = localSpans
                        val visible = spans.toVisibleString()
                        val caret = textFieldValue.selection.start.coerceIn(0, visible.length)
                        val (before, after) = RichSpanEngine.splitAt(spans, caret)
                        val merged = RichSpanEngine.coalesce(before + listOf(InlineSpan.Text("\n")) + after)
                        val newText = merged.toVisibleString()
                        localSpans = merged
                        lastEmittedText = newText
                        textFieldValue = TextFieldValue(newText, TextRange(caret + 1))
                        onSpansChange?.invoke(merged, caret + 1)
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = true,
                // Enter means "next block" in this editor. Using ImeAction.Next lets the IME keep
                // the keyboard open while focus moves, instead of inserting a newline that we
                // immediately roll back (which restarted the input session and made the keyboard
                // blink closed/open). Code/Math keep the default multiline behavior.
                imeAction = if (!isMultiline && (onEnter != null || onEnterAtOffset != null)) {
                    ImeAction.Next
                } else {
                    ImeAction.Default
                },
            ),
            keyboardActions = KeyboardActions(
                onNext = {
                    if (!isMultiline && (onEnter != null || onEnterAtOffset != null)) {
                        val caret = textFieldValue.selection.start
                            .coerceIn(0, textFieldValue.text.length)
                        if (onEnterAtOffset != null) {
                            onEnterAtOffset.invoke(caret)
                        } else {
                            onEnter?.invoke(
                                textFieldValue.text.substring(0, caret),
                                textFieldValue.text.substring(caret),
                            )
                        }
                    }
                },
            ),
            decorationBox = { innerTextField ->
                if (textFieldValue.text.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                runCatching { focusRequester.requestFocus() }
                                onFocusGained()
                            },
                    ) {
                        Text(
                            text = placeholder,
                            style = textStyle.copy(color = AppColors.TextTertiary),
                        )
                    }
                }
                innerTextField()
            },
        )
        }
    }
}

/**
 * Wraps the platform InputConnection to detect a Backspace that produces no text change because
 * the caret is already at offset 0. Compose only calls `onValueChange` when the text/selection
 * actually changes, so an empty block would otherwise never receive the delete.
 */
private class BackspaceTextInputInterceptor(
    private val shouldHandleBackspace: () -> Boolean,
    private val onBackspace: () -> Unit,
) : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        nextHandler.startInputMethod(
            PlatformTextInputMethodRequest { outAttributes: EditorInfo ->
                BackspaceAwareInputConnection(
                    delegate = request.createInputConnection(outAttributes),
                    shouldHandleBackspace = shouldHandleBackspace,
                    onBackspace = onBackspace,
                )
            },
        )
    }
}

private class BackspaceAwareInputConnection(
    delegate: InputConnection,
    private val shouldHandleBackspace: () -> Boolean,
    private val onBackspace: () -> Unit,
) : InputConnectionWrapper(delegate, false) {
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0 && shouldHandleBackspace()) {
            onBackspace()
            return true
        }
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0 && shouldHandleBackspace()) {
            onBackspace()
            return true
        }
        return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    override fun sendKeyEvent(event: AndroidKeyEvent): Boolean {
        if (event.action == AndroidKeyEvent.ACTION_DOWN &&
            event.keyCode == AndroidKeyEvent.KEYCODE_DEL &&
            shouldHandleBackspace()
        ) {
            onBackspace()
            return true
        }
        return super.sendKeyEvent(event)
    }
}

private fun findHitPill(
    layout: TextLayoutResult,
    pills: List<MarkdownPill>,
    position: Offset,
    paddingPx: Float,
): MarkdownPill? {
    if (pills.isEmpty()) return null
    val textLen = layout.layoutInput.text.length
    if (textLen == 0) return null

    for (pill in pills) {
        val start = pill.transformedRange.first
        val end = pill.transformedRange.last + 1
        if (start >= end || start >= textLen) continue

        val lineStart = layout.getLineForOffset(start)
        val lineEnd = layout.getLineForOffset((end - 1).coerceAtMost(textLen - 1))

        for (line in lineStart..lineEnd) {
            val left = if (line == lineStart) {
                layout.getHorizontalPosition(start, true)
            } else {
                layout.getLineLeft(line)
            }
            val right = if (line == lineEnd) {
                layout.getHorizontalPosition(end, true)
            } else {
                layout.getLineRight(line)
            }
            val lineTop = layout.getLineTop(line)
            val lineBottom = layout.getLineBottom(line)

            val rectLeft = minOf(left, right) - paddingPx
            val rectRight = maxOf(left, right) + paddingPx
            val rectTop = lineTop - paddingPx * 0.5f
            val rectBottom = lineBottom + paddingPx * 0.5f

            if (position.x in rectLeft..rectRight && position.y in rectTop..rectBottom) {
                return pill
            }
        }
    }
    return null
}

internal fun resolveInlinePillObject(
    rawTarget: String,
    allObjects: List<PkmObject>,
    allTypes: List<ObjectType>,
    tagColors: Map<String, String> = emptyMap(),
): PkmObject? {
    val clean = rawTarget.trim().removePrefix("@").removePrefix("#").trim()
    // Check direct object ID match first (from structured InlineSpan.Mention or raw target)
    allObjects.find { it.id == rawTarget || it.id == clean }?.let { return it }

    val targetParts = clean.split('/')
    if (targetParts.size >= 2) {
        val wantedType = targetParts.first().trim().replace(" ", "")
        val wantedName = targetParts.drop(1).joinToString("/").trim()
        val matchingTypeObjects = allObjects.filter { obj ->
            val objTypeName = allTypes.find { it.id == obj.typeId }?.name.orEmpty()
            objTypeName.replace(" ", "").equals(wantedType, ignoreCase = true) ||
                obj.typeId.replace("type_", "").replace("_", "").equals(wantedType, ignoreCase = true)
        }
        return matchingTypeObjects.find { it.plainTitle.equals(wantedName, ignoreCase = true) }
            ?: allObjects.find { it.plainTitle.equals(wantedName, ignoreCase = true) }
            ?: matchingTypeObjects.find {
                it.plainTitle.startsWith(wantedName.take(4), ignoreCase = true) ||
                    wantedName.startsWith(it.plainTitle.take(4), ignoreCase = true)
            }
            ?: matchingTypeObjects.firstOrNull()
    }
    return allObjects.find {
        it.plainTitle.equals(clean, ignoreCase = true) ||
            it.plainTitle.replace(" ", "_").equals(clean, ignoreCase = true)
    } ?: allObjects.find {
        clean.length >= 3 && it.plainTitle.contains(clean, ignoreCase = true)
    }
}

private val BRACKET_MENTION_REGEX = Regex("""@\[([^\]]+)]|\[\[([^\]]+)]]""")
private val WORD_MENTION_REGEX = Regex("""(?<=^|[\s(\[])@([a-zA-Z0-9_\u00C0-\u017F\-]+)""")

private fun isWordBoundaryBeforeAt(text: String, atIndex: Int): Boolean {
    if (atIndex <= 0) return true
    val prev = text[atIndex - 1]
    return prev.isWhitespace() || prev == '(' || prev == '['
}

private fun isWordBoundaryAfterMention(text: String, endIndex: Int): Boolean {
    if (endIndex >= text.length) return true
    val next = text[endIndex]
    return next.isWhitespace() || next == ')' || next == ']' || next == '!' ||
        next == '?' || next == ',' || next == '.' || next == ';' || next == ':'
}

/**
 * Robustly extracts all [PkmObject]s referenced inside a block (via structured [InlineSpan.Mention],
 * rendered pills, `@token`, or `@ObjectName` in plain text).
 */
internal fun findReferencedObjects(
    blockSpans: List<InlineSpan>,
    plainText: String,
    allObjects: List<PkmObject>,
    allTypes: List<ObjectType>,
    tagColors: Map<String, String> = emptyMap(),
): List<PkmObject> {
    val hasAt = plainText.indexOf('@') >= 0
    val hasWiki = plainText.contains("[[")
    val hasMentionSpan = blockSpans.any { it is InlineSpan.Mention }
    if (!hasAt && !hasWiki && !hasMentionSpan) {
        return emptyList()
    }
    val results = mutableListOf<PkmObject>()
    val seenIds = mutableSetOf<String>()

    // 1. Structured InlineSpan.Mention spans
    if (hasMentionSpan) {
        for (span in blockSpans) {
            if (span is InlineSpan.Mention) {
                val matched = allObjects.find { it.id == span.targetObjectId }
                    ?: resolveInlinePillObject(span.fallbackText ?: span.targetObjectId, allObjects, allTypes)
                if (matched != null && seenIds.add(matched.id)) {
                    results.add(matched)
                }
            }
        }
    }

    // 2. Bracketed mention tokens @[...] or [[...]]
    if (hasWiki || plainText.contains("@[")) {
        BRACKET_MENTION_REGEX.findAll(plainText).forEach { match ->
            val target = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
            val matched = resolveInlinePillObject(target, allObjects, allTypes)
            if (matched != null && seenIds.add(matched.id)) {
                results.add(matched)
            }
        }
    }

    if (hasAt) {
        // 3. Word-based @mentions (e.g. @1000032463 or @Nayuta)
        WORD_MENTION_REGEX.findAll(plainText).forEach { match ->
            val token = match.groupValues[1].trim()
            val matched = resolveInlinePillObject(token, allObjects, allTypes)
            if (matched != null && seenIds.add(matched.id)) {
                results.add(matched)
            }
        }

        // 4. Multi-word object titles directly matching "@title" in text without compiling Regex per object
        for (obj in allObjects) {
            val title = obj.plainTitle.trim()
            if (title.length >= 2 && !seenIds.contains(obj.id)) {
                var searchFrom = 0
                while (searchFrom < plainText.length) {
                    val atIdx = plainText.indexOf('@', searchFrom)
                    if (atIdx < 0 || atIdx + 1 + title.length > plainText.length) break
                    if (
                        isWordBoundaryBeforeAt(plainText, atIdx) &&
                        plainText.regionMatches(atIdx + 1, title, 0, title.length, ignoreCase = true) &&
                        isWordBoundaryAfterMention(plainText, atIdx + 1 + title.length)
                    ) {
                        if (seenIds.add(obj.id)) {
                            results.add(obj)
                        }
                        break
                    }
                    searchFrom = atIdx + 1
                }
            }
        }
    }

    return results
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0D0E)
@Composable
fun MarkdownBlocksPreview() {
    YaopTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val blocks = listOf(
                Block.Paragraph("1", "Texto".toTextSpan()),
                Block.Heading("2", HeadingLevel.H1, "Encabezado 1".toTextSpan()),
                Block.Heading("3", HeadingLevel.H2, "Encabezado 2".toTextSpan()),
                Block.Heading("4", HeadingLevel.H3, "Encabezado 3".toTextSpan()),
                Block.NumberedList("5", 1, "Lista numerada".toTextSpan()),
                Block.AlphabeticList("5b", 1, "Alphabetic list".toTextSpan()),
                Block.BulletList("6", "Bullet list".toTextSpan()),
                Block.RomanList("7", 1, "Lista Romana".toTextSpan()),
                Block.Toggle(
                    "8",
                    "Toggle".toTextSpan(),
                    isExpanded = true,
                ),
                Block.Todo("9", checked = false, "Tareas".toTextSpan()),
                Block.Quote("10", "Bloque de citas".toTextSpan()),
                Block.Math("11", latex = ""),
                Block.Table("12"),
                Block.Code("13", code = "", language = "javascript"),
            )

            blocks.forEach { b ->
                val requester = remember { FocusRequester() }
                BlockItemView(
                    block = b,
                    focusRequester = requester,
                    isFocused = false,
                    onFocusGained = {},
                    onUpdate = {},
                    onTextChange = {},
                    onDelete = {},
                    onEnterPressed = { _, _ -> },
                    onBackspaceOnEmpty = {},
                )
            }
        }
    }
}
