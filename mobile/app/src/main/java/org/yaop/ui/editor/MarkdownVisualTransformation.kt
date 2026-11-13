package org.yaop.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.RichSpanEngine
import org.yaop.core.model.SpanColorPalette
import org.yaop.core.model.TextMark
import org.yaop.core.model.toPlainText
import org.yaop.core.model.toVisibleString
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors

enum class MarkdownPillKind {
    MENTION,
    WIKILINK,
    TAG,
    DATE,
    LINK,
}

/**
 * Named color resolver for inline text, inline background highlights, and block backgrounds.
 * Mapped directly to TypePalette variables with calibrated contrast so highlighted text
 * never loses legibility on dark OLED surfaces.
 */
object EditorColorPalette {

    fun resolveTextColor(name: String?): Color? = when (name?.lowercase()) {
        SpanColorPalette.YELLOW -> TypePalette.TextYellow
        SpanColorPalette.ORANGE -> TypePalette.TextOrange
        SpanColorPalette.RED -> TypePalette.TextRed
        SpanColorPalette.PINK -> TypePalette.TextPink
        SpanColorPalette.PURPLE -> TypePalette.TextPurple
        SpanColorPalette.BLUE, "cobalt" -> TypePalette.TextCobalt
        SpanColorPalette.ICE, "cyan" -> TypePalette.TextIce
        SpanColorPalette.TEAL -> TypePalette.TextTeal
        SpanColorPalette.GREEN, "lime" -> TypePalette.TextLime
        SpanColorPalette.GRAY, "grey" -> TypePalette.TextGrey
        else -> null
    }

    fun resolveBackgroundColor(name: String?): Color? = when (name?.lowercase()) {
        SpanColorPalette.YELLOW -> TypePalette.HighlightYellow
        SpanColorPalette.ORANGE -> TypePalette.HighlightOrange
        SpanColorPalette.RED -> TypePalette.HighlightRed
        SpanColorPalette.PINK -> TypePalette.HighlightPink
        SpanColorPalette.PURPLE -> TypePalette.HighlightPurple
        SpanColorPalette.BLUE, "cobalt" -> TypePalette.HighlightCobalt
        SpanColorPalette.ICE, "cyan" -> TypePalette.HighlightIce
        SpanColorPalette.TEAL -> TypePalette.HighlightTeal
        SpanColorPalette.GREEN, "lime" -> TypePalette.HighlightLime
        SpanColorPalette.GRAY, "grey" -> TypePalette.HighlightGrey
        else -> null
    }

    fun resolveBlockBackgroundColor(name: String?): Color? = when (name?.lowercase()) {
        SpanColorPalette.YELLOW -> TypePalette.BlockYellow
        SpanColorPalette.ORANGE -> TypePalette.BlockOrange
        SpanColorPalette.RED -> TypePalette.BlockRed
        SpanColorPalette.PINK -> TypePalette.BlockPink
        SpanColorPalette.PURPLE -> TypePalette.BlockPurple
        SpanColorPalette.BLUE, "cobalt" -> TypePalette.BlockCobalt
        SpanColorPalette.ICE, "cyan" -> TypePalette.BlockIce
        SpanColorPalette.TEAL -> TypePalette.BlockTeal
        SpanColorPalette.GREEN, "lime" -> TypePalette.BlockLime
        SpanColorPalette.GRAY, "grey" -> TypePalette.BlockGrey
        else -> null
    }

    fun resolveSwatchColor(name: String): Color = when (name.lowercase()) {
        SpanColorPalette.YELLOW -> TypePalette.ColorYellow
        SpanColorPalette.ORANGE -> TypePalette.ColorOrange
        SpanColorPalette.RED -> TypePalette.ColorRed
        SpanColorPalette.PINK -> TypePalette.ColorPink
        SpanColorPalette.PURPLE -> TypePalette.ColorPurple
        SpanColorPalette.BLUE, "cobalt" -> TypePalette.ColorCobalt
        SpanColorPalette.ICE, "cyan" -> TypePalette.ColorIce
        SpanColorPalette.TEAL -> TypePalette.ColorTeal
        SpanColorPalette.GREEN, "lime" -> TypePalette.ColorLime
        SpanColorPalette.GRAY, "grey" -> TypePalette.ColorGrey
        else -> AppColors.TextPrimary
    }
}

/**
 * A visual pill or interactive range inside the text.
 * When rendering structured [InlineSpan]s, [transformedRange] and [originalRange] are 1-to-1.
 */
data class MarkdownPill(
    val transformedRange: IntRange,
    val originalRange: IntRange,
    val kind: MarkdownPillKind,
    val label: String,
    val target: String,
    val backgroundColor: Color,
    val borderColor: Color?,
    val accentColor: Color? = null,
)

data class MarkdownRender(
    val text: AnnotatedString,
    val offsetMapping: OffsetMapping,
    val pills: List<MarkdownPill>,
)

/**
 * Renderer for both structured [InlineSpan] lists (pure WYSIWYG with 1-to-1 [OffsetMapping.Identity])
 * and legacy raw Markdown strings.
 */
object MarkdownRenderer {

    private val INLINE_TAG_TOKEN = Regex("""(?<=^|[\s(\[])#([a-zA-Z0-9_\u0080-\uFFFF]+)""")

    /**
     * Fast O(N) check to determine whether [spans] requires any visual transformation or pill rendering.
     * When false (pure unstyled plain text without inline `#tags`), `BasicTextField` can use
     * [VisualTransformation.None] directly, eliminating AnnotatedString style passes and IME layout invalidation.
     */
    fun hasRichStylingOrPills(spans: List<InlineSpan>): Boolean {
        for (span in spans) {
            when (span) {
                is InlineSpan.Text -> {
                    if (
                        span.marks.isNotEmpty() ||
                        !span.linkUrl.isNullOrBlank() ||
                        span.textColor != null ||
                        span.backgroundColor != null ||
                        span.text.indexOf('#') >= 0
                    ) {
                        return true
                    }
                }
                else -> return true
            }
        }
        return false
    }

    private fun applyFormalTagStyle(
        builder: AnnotatedString.Builder,
        pills: MutableList<MarkdownPill>,
        start: Int,
        end: Int,
        visibleLabel: String,
        cleanTag: String,
        customHex: String?,
    ) {
        if (start >= end || cleanTag.isBlank()) return
        val textColor = TagPalette.getTextColor(cleanTag, customHex)
        val hashColor = TagPalette.getHashColor(cleanTag, customHex)
        val bgColor = TagPalette.getPillBackground(cleanTag, customHex)
        val borderColor = TagPalette.getPillBorder(cleanTag, customHex)
        val accentColor = TagPalette.getAccentColor(cleanTag, customHex)

        builder.addStyle(
            SpanStyle(
                color = textColor,
                fontWeight = FontWeight.Medium,
                fontSize = 0.90.em,
                letterSpacing = 0.02.em,
            ),
            start,
            end,
        )
        if (visibleLabel.startsWith("#") && end > start + 1) {
            builder.addStyle(
                SpanStyle(
                    color = hashColor,
                    fontWeight = FontWeight.Normal,
                    fontSize = 0.86.em,
                    letterSpacing = 0.035.em,
                ),
                start,
                start + 1,
            )
            builder.addStyle(
                SpanStyle(
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 0.90.em,
                    letterSpacing = 0.02.em,
                ),
                start + 1,
                end,
            )
        }

        val pillRange = start until end
        pills.add(
            MarkdownPill(
                transformedRange = pillRange,
                originalRange = pillRange,
                kind = MarkdownPillKind.TAG,
                label = visibleLabel,
                target = cleanTag,
                backgroundColor = bgColor,
                borderColor = borderColor,
                accentColor = accentColor,
            ),
        )
    }

    /**
     * Pure WYSIWYG renderer for structured [InlineSpan]s.
     * Uses [OffsetMapping.Identity] because `spans.toPlainText()` has a 1-to-1 character correspondence
     * with the rendered text—no hidden Markdown syntax ever exists in the buffer.
     */
    fun renderSpans(
        spans: List<InlineSpan>,
        tagColors: Map<String, String> = emptyMap(),
    ): MarkdownRender {
        val upgraded = RichSpanEngine.upgradeLegacySpans(spans)
        val segs = RichSpanEngine.segments(upgraded)
        val builder = AnnotatedString.Builder()
        val pills = mutableListOf<MarkdownPill>()

        for (seg in segs) {
            val range = seg.start until seg.end
            when (val span = seg.span) {
                is InlineSpan.Text -> {
                    builder.append(span.text)
                    val hasMarksOrColors = span.marks.isNotEmpty() ||
                        !span.linkUrl.isNullOrBlank() ||
                        span.textColor != null ||
                        span.backgroundColor != null

                    if (hasMarksOrColors) {
                        val decorations = mutableListOf<TextDecoration>()
                        if (TextMark.UNDERLINE in span.marks || !span.linkUrl.isNullOrBlank()) {
                            decorations.add(TextDecoration.Underline)
                        }
                        if (TextMark.STRIKETHROUGH in span.marks) {
                            decorations.add(TextDecoration.LineThrough)
                        }
                        val combinedDecoration = if (decorations.isEmpty()) {
                            TextDecoration.None
                        } else {
                            TextDecoration.combine(decorations)
                        }

                        val rawTextColor = EditorColorPalette.resolveTextColor(span.textColor)
                        val rawBgColor = EditorColorPalette.resolveBackgroundColor(span.backgroundColor)

                        val hasSameColorConflict = span.textColor != null &&
                            span.backgroundColor != null &&
                            span.textColor.equals(span.backgroundColor, ignoreCase = true)

                        val fgColor = if (hasSameColorConflict) {
                            AppColors.TextPrimary
                        } else {
                            rawTextColor ?: when {
                                !span.linkUrl.isNullOrBlank() -> AppColors.Accent
                                TextMark.CODE in span.marks -> Color(0xFFE2E8F0)
                                TextMark.HIGHLIGHT in span.marks -> AppColors.TextPrimary
                                else -> Color.Unspecified
                            }
                        }

                        val bgColor = rawBgColor ?: when {
                            TextMark.HIGHLIGHT in span.marks -> TypePalette.HighlightYellow
                            TextMark.CODE in span.marks -> AppColors.SurfaceElevated
                            else -> Color.Unspecified
                        }

                        builder.addStyle(
                            SpanStyle(
                                color = fgColor,
                                background = bgColor,
                                fontWeight = if (TextMark.BOLD in span.marks) FontWeight.Bold else null,
                                fontStyle = if (TextMark.ITALIC in span.marks) FontStyle.Italic else null,
                                fontFamily = if (TextMark.CODE in span.marks) FontFamily.Monospace else null,
                                textDecoration = combinedDecoration,
                            ),
                            seg.start,
                            seg.end,
                        )
                    }

                    if (!span.linkUrl.isNullOrBlank()) {
                        pills.add(
                            MarkdownPill(
                                transformedRange = range,
                                originalRange = range,
                                kind = MarkdownPillKind.LINK,
                                label = span.text,
                                target = span.linkUrl,
                                backgroundColor = AppColors.Accent.copy(alpha = 0.12f),
                                borderColor = AppColors.Accent.copy(alpha = 0.35f),
                            ),
                        )
                    } else if (TextMark.CODE !in span.marks && span.text.indexOf('#') >= 0) {
                        // Also render inline #tag tokens inside plain text runs with the same formal pill style
                        for (match in INLINE_TAG_TOKEN.findAll(span.text)) {
                            val cleanTag = match.groupValues[1].trim()
                            val matchStart = seg.start + match.range.first
                            val matchEnd = seg.start + match.range.last + 1
                            applyFormalTagStyle(
                                builder = builder,
                                pills = pills,
                                start = matchStart,
                                end = matchEnd,
                                visibleLabel = match.value,
                                cleanTag = cleanTag,
                                customHex = tagColors[cleanTag.lowercase()],
                            )
                        }
                    }
                }

                is InlineSpan.Mention -> {
                    val visible = span.toVisibleString()
                    builder.append(visible)
                    builder.addStyle(
                        SpanStyle(
                            color = AppColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        seg.start,
                        seg.end,
                    )
                    pills.add(
                        MarkdownPill(
                            transformedRange = range,
                            originalRange = range,
                            kind = MarkdownPillKind.MENTION,
                            label = span.fallbackText?.takeIf { it.isNotBlank() } ?: span.targetObjectId,
                            target = span.targetObjectId,
                            backgroundColor = AppColors.SurfaceElevated,
                            borderColor = AppColors.BorderHover,
                        ),
                    )
                }

                is InlineSpan.Tag -> {
                    val visible = span.toVisibleString()
                    val cleanTag = visible.removePrefix("#").trim()
                    builder.append(visible)
                    applyFormalTagStyle(
                        builder = builder,
                        pills = pills,
                        start = seg.start,
                        end = seg.end,
                        visibleLabel = visible,
                        cleanTag = cleanTag,
                        customHex = tagColors[cleanTag.lowercase()],
                    )
                }

                is InlineSpan.Date -> {
                    val visible = span.toVisibleString()
                    builder.append(visible)
                    builder.addStyle(
                        SpanStyle(
                            color = AppColors.Accent,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        seg.start,
                        seg.end,
                    )
                    pills.add(
                        MarkdownPill(
                            transformedRange = range,
                            originalRange = range,
                            kind = MarkdownPillKind.DATE,
                            label = span.isoDate,
                            target = span.isoDate,
                            backgroundColor = AppColors.AccentSubtle,
                            borderColor = AppColors.Accent.copy(alpha = 0.5f),
                        ),
                    )
                }

                is InlineSpan.Math -> {
                    // Inline TeX from desktop: kept atomic and shown as $latex$ (no inline renderer yet).
                    builder.append(span.toVisibleString())
                    builder.addStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, color = AppColors.Accent),
                        seg.start,
                        seg.end,
                    )
                }
            }
        }

        return MarkdownRender(
            text = builder.toAnnotatedString(),
            offsetMapping = OffsetMapping.Identity,
            pills = pills,
        )
    }

    /**
     * Renders a raw string by parsing any inline Markdown into structured spans.
     */
    fun render(
        raw: String,
        tagColors: Map<String, String> = emptyMap(),
    ): MarkdownRender {
        val parsed = RichSpanEngine.parseMarkdownToSpans(raw)
        val rendered = renderSpans(parsed, tagColors)
        if (rendered.text.text.length == raw.length) {
            return rendered
        }
        // Fallback when raw string length differs due to stripped legacy delimiters
        val rawLen = raw.length
        val transLen = rendered.text.text.length
        val safeMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                offset.coerceIn(0, transLen)

            override fun transformedToOriginal(offset: Int): Int =
                offset.coerceIn(0, rawLen)
        }
        return rendered.copy(offsetMapping = safeMapping)
    }
}

/**
 * Pure WYSIWYG [VisualTransformation] backed by the block's structured [InlineSpan] list.
 * Guarantees 1-to-1 character identity with the input [AnnotatedString] so the Android IME
 * composition and selection offsets are never disturbed.
 */
data class RichSpanVisualTransformation(
    val spans: List<InlineSpan>,
    val tagColors: Map<String, String> = emptyMap(),
    val preRenderedText: AnnotatedString? = null,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val rawText = text.text
        if (preRenderedText != null && preRenderedText.text == rawText) {
            return TransformedText(preRenderedText, OffsetMapping.Identity)
        }
        if (!MarkdownRenderer.hasRichStylingOrPills(spans)) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val plainFromSpans = spans.toPlainText()
        val effectiveSpans = if (plainFromSpans == rawText) {
            spans
        } else {
            RichSpanEngine.applyTextEdit(spans, rawText, rawText.length).first
        }
        val render = MarkdownRenderer.renderSpans(effectiveSpans, tagColors)
        if (render.text.text == rawText) {
            return TransformedText(render.text, OffsetMapping.Identity)
        }
        // Safety guarantee: if lengths ever diverge, preserve the exact input text with clamped styles
        val safeBuilder = AnnotatedString.Builder(rawText)
        for (styleRange in render.text.spanStyles) {
            val s = styleRange.start.coerceIn(0, rawText.length)
            val e = styleRange.end.coerceIn(0, rawText.length)
            if (s < e) {
                safeBuilder.addStyle(styleRange.item, s, e)
            }
        }
        return TransformedText(safeBuilder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

class MarkdownVisualTransformation(
    private val tagColors: Map<String, String> = emptyMap(),
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val render = MarkdownRenderer.render(text.text, tagColors)
        return TransformedText(render.text, render.offsetMapping)
    }
}
