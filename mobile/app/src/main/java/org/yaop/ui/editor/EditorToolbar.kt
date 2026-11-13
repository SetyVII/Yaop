package org.yaop.ui.editor

import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.Block
import org.yaop.core.model.BlockTypeKeys
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectType
import org.yaop.core.model.SpanColorPalette
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** Which specialized toolbar context applies to the currently focused block. */
enum class EditorToolbarContext {
    TEXT,
    MATH,
    CODE,
    TABLE,
    ;

    companion object {
        fun forBlock(block: Block?): EditorToolbarContext = when (block) {
            is Block.Math -> MATH
            is Block.Code -> CODE
            is Block.Table -> TABLE
            else -> TEXT
        }
    }
}

/**
 * Dynamic Island single-row toolbar modes.
 */
enum class EditorToolbarMode {
    NORMAL,
    FORMAT,
    COLOR,
    RESULTS_MENTION,
    CREATE_OBJECT_AS,
    RESULTS_TAG,
    BLOCK_TYPE,
    CODE,
    MATH,
    TABLE,
    SELECTION,
    PASTE_URL,
    NOTICE,
}

/**
 * Pattern C: Keyboard-replacement panels that occupy the exact height of the software keyboard.
 */
enum class EditorKeyboardPanelType {
    NONE,
    BLOCKS,
    CALENDAR,
    AUDIO,
}

/** Logical grouping used by the overflow sheet. */
enum class EditorToolbarSection {
    STYLE,
    LISTS,
    OBJECTS,
    BLOCKS,
    MATH,
    CODE,
    TABLE,
}

data class EditorToolbarAction(
    val id: String,
    val title: String,
    val icon: ImageVector? = null,
    val glyph: String? = null,
    val section: EditorToolbarSection = EditorToolbarSection.STYLE,
    val primary: Boolean = false,
    val tint: Color? = null,
    val onClick: () -> Unit = {},
    val isActive: Boolean = false,
    val submenu: List<EditorToolbarAction>? = null,
)

data class EditorToolbarCallbacks(
    val onOutdent: () -> Unit = {},
    val onToggleHeading: () -> Unit = {},
    val onToggleTodo: () -> Unit = {},
    val onToggleQuote: () -> Unit = {},
    val onToggleCodeBlock: () -> Unit = {},
    val onToggleBulletList: () -> Unit = {},
    val onToggleNumberedList: () -> Unit = {},
    val onToggleAlphabeticList: () -> Unit = {},
    val onToggleRomanList: () -> Unit = {},
    val onIndent: () -> Unit = {},
    val onInsertLineBreak: () -> Unit = {},
    val onToggleFormat: (tokenId: String, prefix: String, suffix: String) -> Unit = { _, _, _ -> },
    val onOpenSlash: () -> Unit = {},
    val onOpenMention: () -> Unit = {},
    val onOpenTag: () -> Unit = {},
    val onOpenCalendar: () -> Unit = {},
    val onOpenLinkPicker: () -> Unit = {},
    val onOpenBlockTypeMode: () -> Unit = {},
    val onOpenFormatMode: () -> Unit = {},
    val onOpenColorMode: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val onExitSpecialBlock: () -> Unit = {},
    val onInsertMathSnippet: (String) -> Unit = {},
    val onInsertCodeSnippet: (String) -> Unit = {},
    val onCopyCode: () -> Unit = {},
    val onTableAddColumn: () -> Unit = {},
    val onTableAddRow: () -> Unit = {},
    val onTableDeleteColumn: () -> Unit = {},
    val onTableDeleteRow: () -> Unit = {},
    val onTableAlign: (String) -> Unit = {},
    val onTableClear: () -> Unit = {},
)

internal data class EditorToolbarStrings(
    val heading: String,
    val bullet: String,
    val numbered: String,
    val alphabetic: String,
    val roman: String,
    val todo: String,
    val quote: String,
    val bold: String,
    val italic: String,
    val underline: String,
    val inlineCode: String,
    val codeBlock: String,
    val strike: String,
    val highlight: String,
    val colorMenu: String,
    val link: String,
    val insertDate: String,
    val insertBlock: String,
    val mention: String,
    val tag: String,
    val wikilink: String,
    val undo: String,
    val redo: String,
    val exitBlock: String,
    val mathFraction: String,
    val mathExponent: String,
    val mathSubscript: String,
    val mathSqrt: String,
    val mathSum: String,
    val mathIntegral: String,
    val mathPi: String,
    val mathInfinity: String,
    val codeIndent: String,
    val codeBraces: String,
    val codeParens: String,
    val codeBrackets: String,
    val codeQuotes: String,
    val codeArrow: String,
    val copyCode: String,
    val tableAddCol: String,
    val tableAddRow: String,
    val tableDelCol: String,
    val tableDelRow: String,
    val tableAlignLeft: String,
    val tableAlignCenter: String,
    val tableAlignRight: String,
    val tableClear: String,
    val indent: String,
    val outdent: String,
    val lineBreak: String,
    val overflowTitle: String,
    val hideKeyboard: String,
)

@Composable
internal fun editorToolbarStrings(): EditorToolbarStrings = EditorToolbarStrings(
    heading = stringResource(R.string.kb_action_toggle_heading),
    bullet = stringResource(R.string.action_bullet_list),
    numbered = stringResource(R.string.action_numbered_list),
    alphabetic = stringResource(R.string.block_type_alphabetic),
    roman = stringResource(R.string.action_roman_list),
    todo = stringResource(R.string.kb_action_toggle_todo),
    quote = stringResource(R.string.kb_action_toggle_quote),
    bold = stringResource(R.string.action_bold),
    italic = stringResource(R.string.action_italic),
    underline = stringResource(R.string.action_underline),
    inlineCode = stringResource(R.string.action_inline_code),
    codeBlock = stringResource(R.string.block_type_code),
    strike = stringResource(R.string.action_strikethrough),
    highlight = stringResource(R.string.action_highlight),
    colorMenu = stringResource(R.string.action_color_menu),
    link = stringResource(R.string.action_link),
    insertDate = stringResource(R.string.action_insert_date),
    insertBlock = stringResource(R.string.kb_action_insert_block),
    mention = stringResource(R.string.kb_action_mention_object),
    tag = stringResource(R.string.kb_action_tag),
    wikilink = stringResource(R.string.action_wikilink),
    undo = stringResource(R.string.action_undo),
    redo = stringResource(R.string.action_redo),
    exitBlock = stringResource(R.string.action_exit_block),
    mathFraction = stringResource(R.string.math_fraction),
    mathExponent = stringResource(R.string.math_exponent),
    mathSubscript = stringResource(R.string.math_subscript),
    mathSqrt = stringResource(R.string.math_sqrt),
    mathSum = stringResource(R.string.math_sum),
    mathIntegral = stringResource(R.string.math_integral),
    mathPi = stringResource(R.string.math_pi),
    mathInfinity = stringResource(R.string.math_infinity),
    codeIndent = stringResource(R.string.code_indent),
    codeBraces = stringResource(R.string.code_curly_braces),
    codeParens = stringResource(R.string.code_parentheses),
    codeBrackets = stringResource(R.string.code_brackets),
    codeQuotes = stringResource(R.string.code_quotes),
    codeArrow = stringResource(R.string.code_arrow),
    copyCode = stringResource(R.string.copy_code),
    tableAddCol = stringResource(R.string.table_add_col),
    tableAddRow = stringResource(R.string.table_add_row),
    tableDelCol = stringResource(R.string.table_del_col),
    tableDelRow = stringResource(R.string.table_del_row),
    tableAlignLeft = stringResource(R.string.table_align_left),
    tableAlignCenter = stringResource(R.string.table_align_center),
    tableAlignRight = stringResource(R.string.table_align_right),
    tableClear = stringResource(R.string.action_clear_table),
    indent = stringResource(R.string.kb_action_indent),
    outdent = stringResource(R.string.kb_action_outdent),
    lineBreak = stringResource(R.string.action_line_break),
    overflowTitle = stringResource(R.string.toolbar_overflow),
    hideKeyboard = stringResource(R.string.kb_action_hide_keyboard),
)

/**
 * Mutable publisher shared between the editor and the host screen.
 * Supports both declarative [EditorToolbarAction] lists and Dynamic Island modes + Pattern C panels.
 */
@Stable
class EditorToolbarController internal constructor() {
    var visible by mutableStateOf(false)
        internal set
    var forceVisibleWithoutIme by mutableStateOf(false)
        internal set
    var context by mutableStateOf(EditorToolbarContext.TEXT)
        internal set
    var mode by mutableStateOf(EditorToolbarMode.NORMAL)
        internal set
    var actions by mutableStateOf<List<EditorToolbarAction>>(emptyList())
        internal set
    var dynamicRowContent by mutableStateOf<(@Composable () -> Unit)?>(null)
        internal set
    var panelType by mutableStateOf(EditorKeyboardPanelType.NONE)
        internal set
    var panelContent by mutableStateOf<(@Composable (Dp) -> Unit)?>(null)
        internal set
    var lastImeHeightDp by mutableStateOf(280.dp)
        internal set

    fun publish(
        context: EditorToolbarContext,
        actions: List<EditorToolbarAction>,
        mode: EditorToolbarMode = EditorToolbarMode.NORMAL,
        forceVisibleWithoutIme: Boolean = false,
        dynamicRowContent: (@Composable () -> Unit)? = null,
        panelType: EditorKeyboardPanelType = EditorKeyboardPanelType.NONE,
        panelContent: (@Composable (Dp) -> Unit)? = null,
    ) {
        if (this.context != context) this.context = context
        if (this.actions != actions) this.actions = actions
        if (this.mode != mode) this.mode = mode
        if (this.forceVisibleWithoutIme != forceVisibleWithoutIme) this.forceVisibleWithoutIme = forceVisibleWithoutIme
        if (this.dynamicRowContent !== dynamicRowContent) this.dynamicRowContent = dynamicRowContent
        if (this.panelType != panelType) this.panelType = panelType
        if (this.panelContent !== panelContent) this.panelContent = panelContent
        if (!visible) visible = true
    }

    fun clear() {
        if (!visible && actions.isEmpty() && !forceVisibleWithoutIme && panelType == EditorKeyboardPanelType.NONE) return
        visible = false
        forceVisibleWithoutIme = false
        actions = emptyList()
        context = EditorToolbarContext.TEXT
        mode = EditorToolbarMode.NORMAL
        dynamicRowContent = null
        panelType = EditorKeyboardPanelType.NONE
        panelContent = null
    }
}

@Composable
fun rememberEditorToolbarController(): EditorToolbarController = remember { EditorToolbarController() }

val LocalEditorToolbarController = compositionLocalOf<EditorToolbarController?> { null }

@Composable
internal fun rememberImeVisible(): Boolean {
    val density = LocalDensity.current
    return WindowInsets.ime.getBottom(density) > 0
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberImeTargetVisible(): Boolean {
    val density = LocalDensity.current
    return WindowInsets.imeAnimationTarget.getBottom(density) > 0
}

/** Declarative toolbar content for the given context. */
internal fun buildEditorToolbarActions(
    context: EditorToolbarContext,
    strings: EditorToolbarStrings,
    callbacks: EditorToolbarCallbacks,
    activeTokenIds: Set<String> = emptySet(),
): List<EditorToolbarAction> = when (context) {
    EditorToolbarContext.TEXT -> buildList {
        add(
            EditorToolbarAction(
                id = "insert_block",
                title = strings.insertBlock,
                icon = Icons.Filled.Add,
                section = EditorToolbarSection.BLOCKS,
                primary = true,
                tint = AppColors.Accent,
                onClick = callbacks.onOpenSlash,
            ),
        )
        add(
            EditorToolbarAction(
                id = "block_type",
                title = strings.heading,
                glyph = "¶",
                section = EditorToolbarSection.BLOCKS,
                primary = true,
                onClick = callbacks.onOpenBlockTypeMode,
            ),
        )
        add(
            EditorToolbarAction(
                id = "format_mode",
                title = strings.bold,
                glyph = "Aa",
                section = EditorToolbarSection.STYLE,
                primary = true,
                isActive = activeTokenIds.intersect(listOf("bold", "italic", "underline", "strike", "inline_code", "highlight")).isNotEmpty(),
                onClick = callbacks.onOpenFormatMode,
            ),
        )
        add(
            EditorToolbarAction(
                id = "mention",
                title = strings.mention,
                icon = Icons.Filled.AlternateEmail,
                section = EditorToolbarSection.OBJECTS,
                primary = true,
                tint = AppColors.Accent,
                onClick = callbacks.onOpenMention,
            ),
        )
        add(
            EditorToolbarAction(
                id = "tag",
                title = strings.tag,
                icon = Icons.AutoMirrored.Filled.Label,
                section = EditorToolbarSection.OBJECTS,
                primary = true,
                tint = AppColors.Accent,
                onClick = callbacks.onOpenTag,
            ),
        )
        add(
            EditorToolbarAction(
                id = "date",
                title = strings.insertDate,
                icon = Icons.Filled.CalendarMonth,
                section = EditorToolbarSection.OBJECTS,
                primary = true,
                tint = AppColors.Accent,
                onClick = callbacks.onOpenCalendar,
            ),
        )
        add(
            EditorToolbarAction(
                id = "list",
                title = strings.bullet,
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                section = EditorToolbarSection.LISTS,
                primary = true,
                isActive = activeTokenIds.intersect(listOf(BlockTypeKeys.BULLET, BlockTypeKeys.NUMBERED, BlockTypeKeys.ALPHABETIC, BlockTypeKeys.ROMAN)).isNotEmpty(),
                submenu = listOf(
                    EditorToolbarAction(
                        id = BlockTypeKeys.BULLET,
                        title = strings.bullet,
                        icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                        section = EditorToolbarSection.LISTS,
                        isActive = BlockTypeKeys.BULLET in activeTokenIds,
                        onClick = callbacks.onToggleBulletList,
                    ),
                    EditorToolbarAction(
                        id = BlockTypeKeys.NUMBERED,
                        title = strings.numbered,
                        icon = Icons.Filled.FormatListNumbered,
                        section = EditorToolbarSection.LISTS,
                        isActive = BlockTypeKeys.NUMBERED in activeTokenIds,
                        onClick = callbacks.onToggleNumberedList,
                    ),
                    EditorToolbarAction(
                        id = BlockTypeKeys.ALPHABETIC,
                        title = strings.alphabetic,
                        glyph = "a.",
                        section = EditorToolbarSection.LISTS,
                        isActive = BlockTypeKeys.ALPHABETIC in activeTokenIds,
                        onClick = callbacks.onToggleAlphabeticList,
                    ),
                    EditorToolbarAction(
                        id = BlockTypeKeys.ROMAN,
                        title = strings.roman,
                        glyph = "Ⅰ.",
                        section = EditorToolbarSection.LISTS,
                        isActive = BlockTypeKeys.ROMAN in activeTokenIds,
                        onClick = callbacks.onToggleRomanList,
                    ),
                ),
                onClick = callbacks.onToggleBulletList,
            ),
        )
        add(
            EditorToolbarAction(
                id = BlockTypeKeys.TODO,
                title = strings.todo,
                icon = Icons.Filled.CheckCircle,
                section = EditorToolbarSection.LISTS,
                primary = true,
                isActive = BlockTypeKeys.TODO in activeTokenIds,
                onClick = callbacks.onToggleTodo,
            ),
        )
        add(
            EditorToolbarAction(
                id = "line_break",
                title = strings.lineBreak,
                icon = Icons.AutoMirrored.Filled.KeyboardReturn,
                section = EditorToolbarSection.STYLE,
                primary = true,
                tint = AppColors.Accent,
                onClick = callbacks.onInsertLineBreak,
            ),
        )
        add(
            EditorToolbarAction(
                id = "indent",
                title = strings.indent,
                icon = Icons.AutoMirrored.Filled.FormatIndentIncrease,
                section = EditorToolbarSection.LISTS,
                primary = true,
                onClick = callbacks.onIndent,
            ),
        )
        add(
            EditorToolbarAction(
                id = "outdent",
                title = strings.outdent,
                icon = Icons.AutoMirrored.Filled.FormatIndentDecrease,
                section = EditorToolbarSection.LISTS,
                primary = true,
                onClick = callbacks.onOutdent,
            ),
        )
        add(
            EditorToolbarAction(
                id = "undo",
                title = strings.undo,
                icon = Icons.AutoMirrored.Filled.Undo,
                section = EditorToolbarSection.STYLE,
                primary = true,
                tint = if (callbacks.canUndo) AppColors.TextPrimary else AppColors.TextTertiary,
                onClick = callbacks.onUndo,
            ),
        )
        add(
            EditorToolbarAction(
                id = "redo",
                title = strings.redo,
                icon = Icons.AutoMirrored.Filled.Redo,
                section = EditorToolbarSection.STYLE,
                primary = true,
                tint = if (callbacks.canRedo) AppColors.TextPrimary else AppColors.TextTertiary,
                onClick = callbacks.onRedo,
            ),
        )
        add(
            EditorToolbarAction(
                id = "bold",
                title = strings.bold,
                icon = Icons.Filled.FormatBold,
                section = EditorToolbarSection.STYLE,
                primary = false,
                isActive = "bold" in activeTokenIds,
                onClick = { callbacks.onToggleFormat("bold", "**", "**") },
            ),
        )
        add(
            EditorToolbarAction(
                id = "italic",
                title = strings.italic,
                icon = Icons.Filled.FormatItalic,
                section = EditorToolbarSection.STYLE,
                primary = false,
                isActive = "italic" in activeTokenIds,
                onClick = { callbacks.onToggleFormat("italic", "*", "*") },
            ),
        )
        add(
            EditorToolbarAction(
                id = "underline",
                title = strings.underline,
                icon = Icons.Filled.FormatUnderlined,
                section = EditorToolbarSection.STYLE,
                primary = false,
                isActive = "underline" in activeTokenIds,
                onClick = { callbacks.onToggleFormat("underline", "<u>", "</u>") },
            ),
        )
        add(
            EditorToolbarAction(
                id = "strike",
                title = strings.strike,
                icon = Icons.Filled.FormatStrikethrough,
                section = EditorToolbarSection.STYLE,
                primary = false,
                isActive = "strike" in activeTokenIds,
                onClick = { callbacks.onToggleFormat("strike", "~~", "~~") },
            ),
        )
        add(
            EditorToolbarAction(
                id = "inline_code",
                title = strings.inlineCode,
                icon = Icons.Filled.Code,
                section = EditorToolbarSection.STYLE,
                primary = false,
                isActive = "inline_code" in activeTokenIds,
                onClick = { callbacks.onToggleFormat("inline_code", "`", "`") },
            ),
        )
        add(
            EditorToolbarAction(
                id = "highlight",
                title = strings.highlight,
                icon = Icons.Filled.FormatColorFill,
                section = EditorToolbarSection.STYLE,
                primary = false,
                isActive = "highlight" in activeTokenIds,
                onClick = { callbacks.onToggleFormat("highlight", "==", "==") },
            ),
        )
        add(
            EditorToolbarAction(
                id = "color",
                title = strings.colorMenu,
                icon = Icons.Filled.Palette,
                section = EditorToolbarSection.STYLE,
                primary = false,
                onClick = callbacks.onOpenColorMode,
            ),
        )
        add(
            EditorToolbarAction(
                id = "link",
                title = strings.link,
                icon = Icons.Filled.Link,
                section = EditorToolbarSection.OBJECTS,
                primary = false,
                onClick = callbacks.onOpenLinkPicker,
            ),
        )
        add(
            EditorToolbarAction(
                id = "quote",
                title = strings.quote,
                icon = Icons.Filled.FormatQuote,
                section = EditorToolbarSection.STYLE,
                primary = false,
                isActive = "quote" in activeTokenIds,
                onClick = callbacks.onToggleQuote,
            ),
        )
        add(
            EditorToolbarAction(
                id = "code_block",
                title = strings.codeBlock,
                glyph = "{ }",
                section = EditorToolbarSection.BLOCKS,
                primary = false,
                isActive = "code_block" in activeTokenIds,
                onClick = callbacks.onToggleCodeBlock,
            ),
        )
    }

    EditorToolbarContext.MATH -> listOf(
        EditorToolbarAction(
            id = "math_exit",
            title = strings.exitBlock,
            glyph = strings.exitBlock,
            section = EditorToolbarSection.MATH,
            primary = true,
            tint = AppColors.Accent,
            onClick = callbacks.onExitSpecialBlock,
        ),
        EditorToolbarAction(
            id = "math_fraction",
            title = strings.mathFraction,
            glyph = "a/b",
            section = EditorToolbarSection.MATH,
            primary = true,
            tint = AppColors.Accent,
            onClick = { callbacks.onInsertMathSnippet("\\frac{a}{b}") },
        ),
        EditorToolbarAction(
            id = "math_exponent",
            title = strings.mathExponent,
            glyph = "x²",
            section = EditorToolbarSection.MATH,
            primary = true,
            tint = AppColors.Accent,
            onClick = { callbacks.onInsertMathSnippet("^{2}") },
        ),
        EditorToolbarAction(
            id = "math_subscript",
            title = strings.mathSubscript,
            glyph = "xᵢ",
            section = EditorToolbarSection.MATH,
            primary = true,
            tint = AppColors.Accent,
            onClick = { callbacks.onInsertMathSnippet("_{i}") },
        ),
        EditorToolbarAction(
            id = "math_sqrt",
            title = strings.mathSqrt,
            glyph = "√x",
            section = EditorToolbarSection.MATH,
            primary = true,
            tint = AppColors.Accent,
            onClick = { callbacks.onInsertMathSnippet("\\sqrt{x}") },
        ),
        EditorToolbarAction(
            id = "math_sum",
            title = strings.mathSum,
            glyph = "∑",
            section = EditorToolbarSection.MATH,
            primary = true,
            tint = AppColors.Accent,
            onClick = { callbacks.onInsertMathSnippet("\\sum_{i=1}^{n}") },
        ),
        EditorToolbarAction(
            id = "math_integral",
            title = strings.mathIntegral,
            glyph = "∫",
            section = EditorToolbarSection.MATH,
            primary = true,
            tint = AppColors.Accent,
            onClick = { callbacks.onInsertMathSnippet("\\int_{a}^{b}") },
        ),
        EditorToolbarAction(
            id = "math_pi",
            title = strings.mathPi,
            glyph = "π",
            section = EditorToolbarSection.MATH,
            primary = true,
            tint = AppColors.Accent,
            onClick = { callbacks.onInsertMathSnippet("\\pi") },
        ),
        EditorToolbarAction(
            id = "math_infinity",
            title = strings.mathInfinity,
            glyph = "∞",
            section = EditorToolbarSection.MATH,
            primary = true,
            tint = AppColors.Accent,
            onClick = { callbacks.onInsertMathSnippet("\\infty") },
        ),
    )

    EditorToolbarContext.CODE -> listOf(
        EditorToolbarAction(
            id = "code_exit",
            title = strings.exitBlock,
            glyph = strings.exitBlock,
            section = EditorToolbarSection.CODE,
            primary = true,
            tint = AppColors.Accent,
            onClick = callbacks.onExitSpecialBlock,
        ),
        EditorToolbarAction(
            id = "code_indent",
            title = strings.codeIndent,
            icon = Icons.AutoMirrored.Filled.FormatIndentIncrease,
            section = EditorToolbarSection.CODE,
            primary = true,
            onClick = { callbacks.onInsertCodeSnippet("  ") },
        ),
        EditorToolbarAction(
            id = "code_braces",
            title = strings.codeBraces,
            glyph = "{ }",
            section = EditorToolbarSection.CODE,
            primary = true,
            onClick = { callbacks.onInsertCodeSnippet("{\n  \n}") },
        ),
        EditorToolbarAction(
            id = "code_parens",
            title = strings.codeParens,
            glyph = "( )",
            section = EditorToolbarSection.CODE,
            primary = true,
            onClick = { callbacks.onInsertCodeSnippet("()") },
        ),
        EditorToolbarAction(
            id = "code_brackets",
            title = strings.codeBrackets,
            glyph = "[ ]",
            section = EditorToolbarSection.CODE,
            primary = true,
            onClick = { callbacks.onInsertCodeSnippet("[]") },
        ),
        EditorToolbarAction(
            id = "code_quotes",
            title = strings.codeQuotes,
            glyph = "\" \"",
            section = EditorToolbarSection.CODE,
            primary = true,
            onClick = { callbacks.onInsertCodeSnippet("\"\"") },
        ),
        EditorToolbarAction(
            id = "code_arrow",
            title = strings.codeArrow,
            glyph = "=>",
            section = EditorToolbarSection.CODE,
            primary = true,
            onClick = { callbacks.onInsertCodeSnippet(" => ") },
        ),
        EditorToolbarAction(
            id = "code_semicolon",
            title = ";",
            glyph = ";",
            section = EditorToolbarSection.CODE,
            primary = true,
            onClick = { callbacks.onInsertCodeSnippet(";") },
        ),
        EditorToolbarAction(
            id = "code_copy",
            title = strings.copyCode,
            icon = Icons.Filled.ContentCopy,
            section = EditorToolbarSection.CODE,
            primary = true,
            onClick = callbacks.onCopyCode,
        ),
    )

    EditorToolbarContext.TABLE -> listOf(
        EditorToolbarAction(
            id = "table_exit",
            title = strings.exitBlock,
            glyph = strings.exitBlock,
            section = EditorToolbarSection.TABLE,
            primary = true,
            tint = AppColors.Accent,
            onClick = callbacks.onExitSpecialBlock,
        ),
        EditorToolbarAction(
            id = "table_add_row",
            title = strings.tableAddRow,
            glyph = strings.tableAddRow,
            section = EditorToolbarSection.TABLE,
            primary = true,
            onClick = callbacks.onTableAddRow,
        ),
        EditorToolbarAction(
            id = "table_add_col",
            title = strings.tableAddCol,
            glyph = strings.tableAddCol,
            section = EditorToolbarSection.TABLE,
            primary = true,
            onClick = callbacks.onTableAddColumn,
        ),
        EditorToolbarAction(
            id = "table_del_row",
            title = strings.tableDelRow,
            glyph = strings.tableDelRow,
            section = EditorToolbarSection.TABLE,
            primary = true,
            onClick = callbacks.onTableDeleteRow,
        ),
        EditorToolbarAction(
            id = "table_del_col",
            title = strings.tableDelCol,
            glyph = strings.tableDelCol,
            section = EditorToolbarSection.TABLE,
            primary = true,
            onClick = callbacks.onTableDeleteColumn,
        ),
        EditorToolbarAction(
            id = "table_align_left",
            title = strings.tableAlignLeft,
            icon = Icons.AutoMirrored.Filled.FormatAlignLeft,
            section = EditorToolbarSection.TABLE,
            primary = true,
            onClick = { callbacks.onTableAlign("left") },
        ),
        EditorToolbarAction(
            id = "table_align_center",
            title = strings.tableAlignCenter,
            icon = Icons.Filled.FormatAlignCenter,
            section = EditorToolbarSection.TABLE,
            primary = true,
            onClick = { callbacks.onTableAlign("center") },
        ),
        EditorToolbarAction(
            id = "table_align_right",
            title = strings.tableAlignRight,
            icon = Icons.AutoMirrored.Filled.FormatAlignRight,
            section = EditorToolbarSection.TABLE,
            primary = true,
            onClick = { callbacks.onTableAlign("right") },
        ),
        EditorToolbarAction(
            id = "table_clear",
            title = strings.tableClear,
            icon = Icons.Filled.Delete,
            section = EditorToolbarSection.TABLE,
            primary = true,
            tint = AppColors.Danger,
            onClick = callbacks.onTableClear,
        ),
    )
}

/**
 * Host-side rendering of the Dynamic Island single-row toolbar + Pattern C Keyboard-Replacement Panel.
 */
@Composable
fun EditorToolbarRail(
    controller: EditorToolbarController?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeTargetVisible = rememberImeTargetVisible()
    var overflowActions by remember { mutableStateOf<List<EditorToolbarAction>?>(null) }
    var submenuAction by remember { mutableStateOf<EditorToolbarAction?>(null) }

    // Observe the animated IME height without reading it during composition: otherwise the whole
    // toolbar recomposes on every frame of the keyboard open/close animation.
    LaunchedEffect(controller, density, imeInsets) {
        snapshotFlow { imeInsets.getBottom(density) }
            .collect { imeBottomPx ->
                if (imeBottomPx > 0 && controller != null) {
                    val h = with(density) { imeBottomPx.toDp() }
                    if (h >= 180.dp) {
                        controller.lastImeHeightDp = h
                    }
                }
            }
    }

    val isVisible = controller != null &&
        controller.visible &&
        (imeTargetVisible || controller.forceVisibleWithoutIme || controller.panelType != EditorKeyboardPanelType.NONE)

    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(
            expandFrom = Alignment.Bottom,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ) + fadeIn(tween(150)),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ) + fadeOut(tween(150)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .then(
                    if (!imeTargetVisible) Modifier.navigationBarsPadding() else Modifier,
                ),
        ) {
            EditorToolbarSurface(
                controller = controller,
                onRequestOverflow = {
                    overflowActions = controller?.actions.orEmpty().filter { !it.primary }
                },
                onRequestSubmenu = { submenuAction = it },
            )

            // Pattern C: Panel in place of the keyboard
            val panelType = controller?.panelType ?: EditorKeyboardPanelType.NONE
            val panelComposable = controller?.panelContent
            AnimatedVisibility(
                visible = panelType != EditorKeyboardPanelType.NONE && panelComposable != null && !imeTargetVisible,
                enter = expandVertically(tween(160)) + fadeIn(tween(160)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(140)),
            ) {
                val panelHeight = (controller?.lastImeHeightDp ?: 280.dp).coerceIn(250.dp, 330.dp)
                Surface(
                    color = AppColors.Surface,
                    border = BorderStroke(1.dp, AppColors.KeyboardBarBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(panelHeight),
                ) {
                    panelComposable?.invoke(panelHeight)
                }
            }
        }
    }

    overflowActions?.let { actions ->
        if (actions.isNotEmpty()) {
            EditorToolbarOverflowSheet(
                actions = actions,
                onDismiss = { overflowActions = null },
            )
        } else {
            overflowActions = null
        }
    }
    submenuAction?.let { action ->
        EditorToolbarSubmenuSheet(
            action = action,
            onDismiss = { submenuAction = null },
        )
    }
}

@Composable
private fun EditorToolbarSurface(
    controller: EditorToolbarController?,
    onRequestOverflow: () -> Unit,
    onRequestSubmenu: (EditorToolbarAction) -> Unit,
) {
    val strings = editorToolbarStrings()
    val currentMode = controller?.mode ?: EditorToolbarMode.NORMAL
    val customRow = controller?.dynamicRowContent

    // Translucent pill bar docked above the keyboard. Only the shape
    // and colours are styled; the dynamic modes, actions and transitions below are untouched.
    Surface(
        color = Color(0xD90B1A4A),
        shape = RoundedCornerShape(25.dp),
        border = BorderStroke(1.dp, AppColors.KeyboardBarBorder),
        shadowElevation = 0.dp,
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(50.dp),
    ) {
        AnimatedContent(
            targetState = currentMode,
            transitionSpec = {
                (fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 3 })
                    .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 3 })
                    .using(SizeTransform(clip = false))
            },
            label = "DynamicToolbarTransition",
        ) { modeState ->
            val rowComposable = if (modeState == currentMode) customRow else null
            if (rowComposable != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    rowComposable()
                }
            } else {
                val primaryActions = remember(controller?.actions) {
                    controller?.actions.orEmpty().filter { it.primary }
                }
                val secondaryCount = remember(controller?.actions) {
                    controller?.actions.orEmpty().count { !it.primary }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        primaryActions.forEach { action ->
                            val effective = if (action.id == "list" && action.isActive) {
                                action.copy(onClick = { onRequestSubmenu(action) })
                            } else {
                                action
                            }
                            EditorToolbarButton(action = effective, onRequestSubmenu = onRequestSubmenu)
                        }
                    }

                    if (secondaryCount > 0) {
                        EditorToolbarButton(
                            action = EditorToolbarAction(
                                id = "overflow",
                                title = strings.overflowTitle,
                                icon = Icons.Filled.MoreVert,
                                onClick = onRequestOverflow,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditorToolbarButton(
    action: EditorToolbarAction,
    onRequestSubmenu: ((EditorToolbarAction) -> Unit)? = null,
) {
    val tint = if (action.isActive) AppColors.Accent else (action.tint ?: AppColors.TextPrimary)
    val bgColor = if (action.isActive) AppColors.Accent.copy(alpha = 0.14f) else Color.Transparent
    val interaction: Modifier = if (action.submenu != null && onRequestSubmenu != null) {
        Modifier.combinedClickable(
            onClick = action.onClick,
            onLongClick = { onRequestSubmenu(action) },
        )
    } else {
        Modifier.clickable(onClick = action.onClick)
    }
    val hasLongGlyph = (action.glyph?.length ?: 0) > 2
    Box(
        modifier = Modifier
            .height(38.dp)
            .then(if (hasLongGlyph) Modifier.padding(horizontal = 4.dp) else Modifier.width(38.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor, RoundedCornerShape(10.dp))
            .then(interaction)
            .padding(horizontal = if (hasLongGlyph) 8.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (action.icon != null) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.title,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        } else if (action.glyph != null) {
            Text(
                text = action.glyph,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/**
 * Reusable compact chip for Dynamic Island toolbar modes (@mention results, #tag results, block types, etc.).
 */
@Composable
internal fun ToolbarChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingEmoji: String? = null,
    leadingIcon: ImageVector? = null,
    isAccent: Boolean = false,
    isActive: Boolean = false,
    chipColor: Color? = null,
) {
    val bg = when {
        chipColor != null -> chipColor.copy(alpha = 0.22f)
        isActive -> AppColors.Accent.copy(alpha = 0.20f)
        isAccent -> AppColors.Accent.copy(alpha = 0.14f)
        else -> AppColors.Surface
    }
    val border = when {
        chipColor != null -> chipColor.copy(alpha = 0.65f)
        isActive || isAccent -> AppColors.Accent.copy(alpha = 0.55f)
        else -> AppColors.BorderSubtle
    }
    val fg = when {
        chipColor != null -> chipColor
        isActive || isAccent -> AppColors.Accent
        else -> AppColors.TextPrimary
    }

    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bg, RoundedCornerShape(9.dp))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (!leadingEmoji.isNullOrBlank()) {
            Text(text = leadingEmoji, fontSize = 13.sp)
        } else if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Pattern C.1: Categorized Block Catalog Panel in the keyboard slot (for `/` and `+`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BlockCatalogKeyboardPanel(
    filterQuery: String,
    onSelectBlockType: (String) -> Unit,
    onSwitchToKeyboard: () -> Unit,
) {
    data class CatalogEntry(
        val id: String,
        val label: String,
        val glyph: String,
        val categoryRes: Int,
    )

    val entries = listOf(
        // Text
        CatalogEntry(BlockTypeKeys.PARAGRAPH, stringResource(R.string.block_type_paragraph), "¶", R.string.block_category_text),
        CatalogEntry(BlockTypeKeys.H1, stringResource(R.string.block_type_h1), "H1", R.string.block_category_text),
        CatalogEntry(BlockTypeKeys.H2, stringResource(R.string.block_type_h2), "H2", R.string.block_category_text),
        CatalogEntry(BlockTypeKeys.H3, stringResource(R.string.block_type_h3), "H3", R.string.block_category_text),
        CatalogEntry(BlockTypeKeys.H4, stringResource(R.string.block_type_h4), "H4", R.string.block_category_text),
        CatalogEntry(BlockTypeKeys.QUOTE, stringResource(R.string.block_type_quote), "❝", R.string.block_category_text),
        CatalogEntry(BlockTypeKeys.CALLOUT, stringResource(R.string.block_type_callout), "💡", R.string.block_category_text),
        CatalogEntry(BlockTypeKeys.GROUP, stringResource(R.string.block_type_group), "▣", R.string.block_category_text),
        // Lists
        CatalogEntry(BlockTypeKeys.BULLET, stringResource(R.string.block_type_bullet), "•", R.string.block_category_lists),
        CatalogEntry(BlockTypeKeys.NUMBERED, stringResource(R.string.block_type_numbered), "1.", R.string.block_category_lists),
        CatalogEntry(BlockTypeKeys.ALPHABETIC, stringResource(R.string.block_type_alphabetic), "a.", R.string.block_category_lists),
        CatalogEntry(BlockTypeKeys.ROMAN, stringResource(R.string.block_type_roman), "Ⅰ.", R.string.block_category_lists),
        CatalogEntry(BlockTypeKeys.TODO, stringResource(R.string.block_type_todo), "☑", R.string.block_category_lists),
        CatalogEntry(BlockTypeKeys.TOGGLE, stringResource(R.string.block_type_toggle), "▶", R.string.block_category_lists),
        // Technical
        CatalogEntry(BlockTypeKeys.CODE, stringResource(R.string.block_type_code), "{ }", R.string.block_category_technical),
        CatalogEntry(BlockTypeKeys.MATH, stringResource(R.string.block_type_math), "∑", R.string.block_category_technical),
        CatalogEntry(BlockTypeKeys.TABLE, stringResource(R.string.block_type_table), "⊞", R.string.block_category_technical),
        CatalogEntry(BlockTypeKeys.DIVIDER, stringResource(R.string.block_type_divider), "—", R.string.block_category_technical),
        // Media
        CatalogEntry(BlockTypeKeys.IMAGE, stringResource(R.string.block_type_image), "🖼", R.string.block_category_media),
        CatalogEntry(BlockTypeKeys.AUDIO, stringResource(R.string.block_type_audio), "🎙", R.string.block_category_media),
        CatalogEntry(BlockTypeKeys.PDF, stringResource(R.string.block_type_pdf), "📄", R.string.block_category_media),
        // Objects & Web
        CatalogEntry(BlockTypeKeys.BOOKMARK, stringResource(R.string.block_type_bookmark), "🔖", R.string.block_category_objects),
        CatalogEntry(BlockTypeKeys.EMBED, stringResource(R.string.block_type_embed), "@", R.string.block_category_objects),
        CatalogEntry(BlockTypeKeys.CANVAS, stringResource(R.string.block_type_canvas), "🎨", R.string.block_category_objects),
    )

    val cleanFilter = filterQuery.trim().removePrefix("/")
    val filtered = remember(cleanFilter, entries) {
        if (cleanFilter.isBlank()) {
            entries
        } else {
            entries.filter {
                it.id.contains(cleanFilter, ignoreCase = true) ||
                    it.label.contains(cleanFilter, ignoreCase = true)
            }
        }
    }

    val categories = listOf(
        R.string.block_category_text,
        R.string.block_category_lists,
        R.string.block_category_technical,
        R.string.block_category_media,
        R.string.block_category_objects,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (cleanFilter.isNotEmpty()) {
                    "${stringResource(R.string.dialog_insert_block_title)}: /$cleanFilter"
                } else {
                    stringResource(R.string.dialog_insert_block_title)
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
            )
            ToolbarChip(
                label = stringResource(R.string.action_switch_to_keyboard),
                leadingIcon = Icons.Filled.Keyboard,
                onClick = onSwitchToKeyboard,
            )
        }

        categories.forEach { catRes ->
            val group = filtered.filter { it.categoryRes == catRes }
            if (group.isNotEmpty()) {
                Text(
                    text = stringResource(catRes).uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextTertiary,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    group.forEach { item ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppColors.SurfaceElevated, RoundedCornerShape(10.dp))
                                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(10.dp))
                                .clickable { onSelectBlockType(item.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = item.glyph,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.Accent,
                            )
                            Text(
                                text = item.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pattern C.2: Inline Date Calendar Panel in the keyboard slot.
 */
@Composable
internal fun DateCalendarKeyboardPanel(
    onSelectIsoDate: (String) -> Unit,
    onSwitchToKeyboard: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val currentLocale = remember(configuration) {
        configuration.locales[0] ?: Locale.getDefault()
    }
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }
    val today = remember { LocalDate.now() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Quick date chips row + switch back to keyboard
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolbarChip(
                    label = stringResource(R.string.date_today),
                    leadingEmoji = "📅",
                    isAccent = true,
                    onClick = { onSelectIsoDate(today.format(DateTimeFormatter.ISO_LOCAL_DATE)) },
                )
                ToolbarChip(
                    label = stringResource(R.string.date_tomorrow),
                    onClick = { onSelectIsoDate(today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)) },
                )
                ToolbarChip(
                    label = stringResource(R.string.date_yesterday),
                    onClick = { onSelectIsoDate(today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)) },
                )
                ToolbarChip(
                    label = stringResource(R.string.date_next_monday),
                    onClick = {
                        val daysUntilMonday = ((8 - today.dayOfWeek.value) % 7).let { if (it == 0) 7 else it }
                        onSelectIsoDate(today.plusDays(daysUntilMonday.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE))
                    },
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            ToolbarChip(
                label = stringResource(R.string.action_switch_to_keyboard),
                leadingIcon = Icons.Filled.Keyboard,
                onClick = onSwitchToKeyboard,
            )
        }

        // Month navigation header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.ChevronLeft,
                contentDescription = null,
                tint = AppColors.TextPrimary,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { currentMonth = currentMonth.minusMonths(1) }
                    .padding(4.dp),
            )
            val monthName = currentMonth.month.getDisplayName(JavaTextStyle.FULL, currentLocale)
                .replaceFirstChar { it.titlecase(currentLocale) }
            Text(
                text = "$monthName ${currentMonth.year}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = AppColors.TextPrimary,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { currentMonth = currentMonth.plusMonths(1) }
                    .padding(4.dp),
            )
        }

        // Days grid
        val daysInMonth = currentMonth.lengthOfMonth()
        val firstDayOffset = (currentMonth.atDay(1).dayOfWeek.value - 1) // 0 = Monday
        val totalCells = firstDayOffset + daysInMonth
        val rows = (totalCells + 6) / 7

        for (r in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (c in 0 until 7) {
                    val cellIdx = r * 7 + c
                    val dayNum = cellIdx - firstDayOffset + 1
                    if (dayNum in 1..daysInMonth) {
                        val date = currentMonth.atDay(dayNum)
                        val isToday = date == today
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isToday) AppColors.Accent else AppColors.SurfaceElevated,
                                    CircleShape,
                                )
                                .clickable {
                                    onSelectIsoDate(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = dayNum.toString(),
                                fontSize = 12.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                                color = if (isToday) Color.White else AppColors.TextPrimary,
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

/**
 * Pattern C.3: Audio Recorder Panel in the keyboard slot.
 * Keeps recording while the user scrolls through the note, with Discard / Pause / Insert controls.
 */
@Composable
internal fun AudioRecorderKeyboardPanel(
    onDiscard: () -> Unit,
    onInsertRecording: (bytes: ByteArray, durationSeconds: Int) -> Unit,
) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    val outputFile = remember { File(context.cacheDir, "panel_rec_${System.currentTimeMillis()}.m4a") }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }

    fun startRecorder() {
        val rec = runCatching {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(outputFile.absolutePath)
            r.prepare()
            r.start()
            r
        }.getOrNull()
        recorder = rec
        isRecording = rec != null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRecorder()
        } else {
            onDiscard()
        }
    }

    LaunchedEffect(Unit) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            startRecorder()
        } else {
            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(isRecording, isPaused) {
        while (isRecording && !isPaused) {
            delay(1000)
            elapsedSeconds++
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "waveAmp",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (isPaused) stringResource(R.string.media_pause) else stringResource(R.string.media_recording),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isPaused) AppColors.TextSecondary else AppColors.Accent,
        )

        Text(
            text = formatDurationSeconds(elapsedSeconds),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = AppColors.TextPrimary,
        )

        // Animated waveform bars
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (bar in 0 until 24) {
                val factor = if (isPaused) 0.2f else ((bar % 5 + 1) * 0.18f * wavePhase).coerceIn(0.15f, 1f)
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height((34 * factor).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AppColors.Accent, RoundedCornerShape(2.dp)),
                )
            }
        }

        // Controls: Discard | Pause/Resume | Insert
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarChip(
                label = stringResource(R.string.action_discard),
                leadingIcon = Icons.Filled.Delete,
                chipColor = AppColors.Danger,
                onClick = {
                    runCatching { recorder?.stop() }
                    runCatching { recorder?.release() }
                    recorder = null
                    outputFile.delete()
                    onDiscard()
                },
            )

            ToolbarChip(
                label = if (isPaused) stringResource(R.string.action_resume) else stringResource(R.string.media_pause),
                leadingIcon = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                onClick = {
                    if (isPaused) {
                        runCatching { recorder?.resume() }
                        isPaused = false
                    } else {
                        runCatching { recorder?.pause() }
                        isPaused = true
                    }
                },
            )

            ToolbarChip(
                label = stringResource(R.string.action_insert_audio),
                leadingIcon = Icons.Filled.Check,
                isAccent = true,
                onClick = {
                    runCatching { recorder?.stop() }
                    runCatching { recorder?.release() }
                    recorder = null
                    val recordedBytes = runCatching {
                        if (outputFile.exists() && outputFile.length() > 0) {
                            outputFile.readBytes()
                        } else {
                            null
                        }
                    }.getOrNull()
                    outputFile.delete()
                    if (recordedBytes != null && recordedBytes.isNotEmpty()) {
                        onInsertRecording(recordedBytes, elapsedSeconds.coerceAtLeast(1))
                    } else {
                        onDiscard()
                    }
                },
            )
        }
    }
}

@Composable
private fun EditorToolbarOverflowSheet(
    actions: List<EditorToolbarAction>,
    onDismiss: () -> Unit,
) {
    UniversalEditorBottomSheet(
        onDismiss = onDismiss,
        headerTitle = stringResource(R.string.toolbar_overflow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            EditorToolbarSection.entries.forEach { section ->
                val sectionActions = actions.filter { it.section == section }
                if (sectionActions.isEmpty()) return@forEach
                Text(
                    text = toolbarSectionLabel(section).uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp),
                )
                HorizontalDivider(color = AppColors.BorderSubtle)
                sectionActions.forEach { action ->
                    EditorToolbarSheetRow(
                        action = action,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorToolbarSubmenuSheet(
    action: EditorToolbarAction,
    onDismiss: () -> Unit,
) {
    UniversalEditorBottomSheet(
        onDismiss = onDismiss,
        headerTitle = action.title,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            action.submenu.orEmpty().forEach { item ->
                val itemTint = if (item.isActive) AppColors.Accent else AppColors.TextPrimary
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            item.onClick()
                            onDismiss()
                        }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (item.icon != null) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.title,
                            tint = itemTint,
                            modifier = Modifier.size(20.dp),
                        )
                    } else if (item.glyph != null) {
                        Text(
                            text = item.glyph,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = itemTint,
                        )
                    }
                    Text(
                        text = item.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.isActive) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = AppColors.Accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorToolbarSheetRow(
    action: EditorToolbarAction,
    onDismiss: () -> Unit,
) {
    val tint = if (action.isActive) AppColors.Accent else (action.tint ?: AppColors.TextPrimary)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                action.onClick()
                onDismiss()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.Surface, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (action.icon != null) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            } else if (action.glyph != null) {
                Text(
                    text = action.glyph,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.SansSerif,
                    color = tint,
                )
            }
        }
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = action.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextPrimary,
        )
    }
}

@Composable
private fun toolbarSectionLabel(section: EditorToolbarSection): String = when (section) {
    EditorToolbarSection.STYLE -> stringResource(R.string.toolbar_section_style)
    EditorToolbarSection.LISTS -> stringResource(R.string.toolbar_section_lists)
    EditorToolbarSection.OBJECTS -> stringResource(R.string.toolbar_section_objects)
    EditorToolbarSection.BLOCKS -> stringResource(R.string.toolbar_section_blocks)
    EditorToolbarSection.MATH -> stringResource(R.string.toolbar_section_math)
    EditorToolbarSection.CODE -> stringResource(R.string.toolbar_section_code)
    EditorToolbarSection.TABLE -> stringResource(R.string.toolbar_section_table)
}
