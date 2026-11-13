package org.yaop.ui.editor

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import org.yaop.core.model.Block
import org.yaop.core.model.PkmObject
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.ObjectType
import java.time.LocalDate

/**
 * Visual presentation state of a block in the editor canvas.
 * Encapsulates focus, selection, typing formats, and indentation hierarchy.
 * Marked as [Immutable] so Compose can skip recomposition when state has not changed.
 */
@Immutable
data class BlockVisualState(
    val isFocused: Boolean = false,
    val isSelected: Boolean = false,
    val isSelectionMode: Boolean = false,
    val isFirstInGroup: Boolean = false,
    val isLastInGroup: Boolean = false,
    val hiddenChildrenCount: Int = 0,
    val requestedCursorOffset: Int? = null,
    /** Bumped when the editor rewrites the focused block's text (split, undo, redo). */
    val textSyncRevision: Int = 0,
    val activeTypingFormats: Set<String>? = null,
    val activeTextColor: String? = null,
    val activeBgColor: String? = null,
    val hasExplicitTextColor: Boolean = false,
    val hasExplicitBgColor: Boolean = false,
)

/**
 * Action callbacks for mutating or interacting with a block.
 */
@Stable
interface BlockActionCallbacks {
    fun onFocusGained() {}
    fun onFocusCleared() {}
    fun onCursorOffsetConsumed() {}
    fun onSelectionChange(selection: TextRange) {}
    fun onCursorMoved() {}
    fun onUpdate(updatedBlock: Block)
    fun onTextChange(newText: String) {}
    fun onSpansChange(newSpans: List<InlineSpan>, caret: Int) {}
    fun onDelete()
    fun onEnterPressed(before: String, after: String)
    fun onEnterAtOffset(caretOffset: Int) {}
    fun onBackspaceOnEmpty()
    fun onExitSpecialBlock() {}
    fun onToggleSelection() {}
    fun onLongPressSelect() {}
    fun onMoveUp() {}
    fun onMoveDown() {}
    fun onHorizontalIndentDelta(delta: Int) {}
    fun onActiveTableColumnChange(column: Int) {}
    fun onAudioRecorded(blockId: String, bytes: ByteArray, durationSeconds: Int) {}
}

/**
 * Navigation and external interactions triggered from pills, links, or attachments.
 */
@Stable
interface BlockNavigationCallbacks {
    fun onNavigateToObject(objectId: String) {}
    fun onNavigateToTag(tagId: String) {}
    fun onNavigateToDate(date: LocalDate) {}
    fun onTapInlinePill(pill: MarkdownPill) {}
    fun onTapBookmark(bookmark: Block.Bookmark) {}
    fun onTapImage(image: Block.Image) {}
    fun onOpenCanvas(canvas: Block.Canvas) {}
}

/**
 * Ambient data dependencies required by rich block renderers.
 */
@Immutable
data class BlockEnvironment(
    val allObjects: List<PkmObject> = emptyList(),
    val allTypes: List<ObjectType> = emptyList(),
    val mediaRefreshKeys: Set<String> = emptySet(),
    val tagColors: Map<String, String> = emptyMap(),
)

/**
 * Unified context grouping visual state, action callbacks, navigation, and environment.
 */
@Stable
data class BlockItemContext(
    val focusRequester: FocusRequester,
    val visualState: BlockVisualState = BlockVisualState(),
    val actions: BlockActionCallbacks,
    val navigation: BlockNavigationCallbacks = object : BlockNavigationCallbacks {},
    val environment: BlockEnvironment = BlockEnvironment(),
)
