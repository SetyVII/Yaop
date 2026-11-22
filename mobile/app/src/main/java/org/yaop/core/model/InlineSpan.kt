package org.yaop.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TextMark {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    CODE,
    HIGHLIGHT,
}

/**
 * Canonical named color palette for inline text/background and block colors.
 * Stored by name (`"gray"`, `"red"`, etc.) so colors adapt automatically to dark and light themes.
 */
object SpanColorPalette {
    const val GRAY = "gray"
    const val YELLOW = "yellow"
    const val ORANGE = "orange"
    const val RED = "red"
    const val PINK = "pink"
    const val PURPLE = "purple"
    const val BLUE = "blue"
    const val ICE = "ice"
    const val TEAL = "teal"
    const val GREEN = "green"

    val ALL_NAMES: List<String> = listOf(
        YELLOW,
        ORANGE,
        RED,
        PINK,
        PURPLE,
        BLUE,
        ICE,
        TEAL,
        GREEN,
        GRAY,
    )

    val ALL_NAMED: List<String> = ALL_NAMES
}

@Serializable
sealed interface InlineSpan {
    @Serializable
    data class Text(
        val text: String,
        val marks: Set<TextMark> = emptySet(),
        val linkUrl: String? = null,
        val textColor: String? = null,
        val backgroundColor: String? = null,
    ) : InlineSpan {
        constructor(
            text: String,
            isBold: Boolean = false,
            isItalic: Boolean = false,
            isUnderline: Boolean = false,
            isStrikethrough: Boolean = false,
            isCode: Boolean = false,
            isHighlight: Boolean = false,
            linkUrl: String? = null,
            textColor: String? = null,
            backgroundColor: String? = null,
        ) : this(
            text = text,
            marks = buildSet {
                if (isBold) add(TextMark.BOLD)
                if (isItalic) add(TextMark.ITALIC)
                if (isUnderline) add(TextMark.UNDERLINE)
                if (isStrikethrough) add(TextMark.STRIKETHROUGH)
                if (isCode) add(TextMark.CODE)
                if (isHighlight) add(TextMark.HIGHLIGHT)
            },
            linkUrl = linkUrl,
            textColor = textColor,
            backgroundColor = backgroundColor,
        )
    }

    @Serializable
    data class Mention(
        val targetObjectId: String,
        val fallbackText: String? = null,
        /** Block reference inside [targetObjectId] (spec/text.md `block_id`); null = whole object. */
        val targetBlockId: String? = null,
    ) : InlineSpan

    @Serializable
    data class Tag(
        val tagId: String,
        val tagName: String,
    ) : InlineSpan {
        constructor(tagName: String) : this(
            tagId = "tag_${tagName.removePrefix("#").trim().lowercase()}",
            tagName = tagName.removePrefix("#").trim(),
        )
    }

    @Serializable
    data class Date(
        val isoDate: String,
        val hasTime: Boolean = false,
    ) : InlineSpan

    /** Inline TeX (wire run `{"type":"math","latex":…}`), atomic like a pill. */
    @Serializable
    data class Math(
        val latex: String,
        val display: Boolean = false,
    ) : InlineSpan
}

/**
 * Visible text representation of a single [InlineSpan].
 * Has a 1-to-1 character length with what is rendered in the WYSIWYG editor.
 */
fun InlineSpan.toVisibleString(): String = when (this) {
    is InlineSpan.Text -> text
    is InlineSpan.Mention -> {
        val rawLabel = fallbackText?.takeIf { it.isNotBlank() } ?: targetObjectId
        val clean = rawLabel.removePrefix("@").trim().ifBlank { "mention" }
        "@$clean"
    }
    is InlineSpan.Tag -> {
        val clean = tagName.removePrefix("#").trim().ifBlank { "tag" }
        "#$clean"
    }
    is InlineSpan.Date -> {
        val clean = isoDate.removePrefix("@").trim().ifBlank { "date" }
        "@$clean"
    }
    is InlineSpan.Math -> "$" + latex.ifBlank { "?" } + "$"
}

/**
 * Helper to convert a list of [InlineSpan]s to visible plain text for editing, search, and preview.
 */
fun List<InlineSpan>.toPlainText(): String {
    if (isEmpty()) return ""
    if (size == 1) {
        val first = this[0]
        if (first is InlineSpan.Text) return first.text
    }
    return buildString {
        for (span in this@toPlainText) {
            append(span.toVisibleString())
        }
    }
}

fun List<InlineSpan>.toVisibleString(): String = toPlainText()

fun String.toTextSpan(): List<InlineSpan> =
    if (isEmpty()) emptyList() else RichSpanEngine.parseMarkdownToSpans(this)

data class SpanSegment(
    val span: InlineSpan,
    val start: Int,
    val end: Int,
) {
    val length: Int get() = end - start
    val isAtomic: Boolean get() = span !is InlineSpan.Text
}

/**
 * Pure manipulation engine for structured [InlineSpan] lists.
 * Handles WYSIWYG text edits, atomic deletion of mentions/tags/dates, range formatting,
 * named colors, safe URL links, splitting, merging, and legacy Markdown conversion.
 */
object RichSpanEngine {

    private val MENTION_BRACKET = Regex("""@\[\[?([^\]]+?)]]?""")
    private val WIKILINK = Regex("""\[\[([^\]]+)]]""")
    private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\((https?://[^\s)]+)\)""")
    private val TAG_TOKEN = Regex("""(?<=^|[\s(\[])#([a-zA-Z0-9_\u0080-\uFFFF]+)""")
    private val DATE_TOKEN = Regex("""(?<=^|[\s(\[])@(\d{4}-\d{2}-\d{2})""")
    private val BOLD_ITALIC = Regex("""\*\*\*(.+?)\*\*\*""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""")
    private val UNDERLINE_HTML = Regex("""<u>(.+?)</u>""")
    private val STRIKE = Regex("""~~(.+?)~~""")
    private val CODE = Regex("""`([^`]+)`""")
    private val HIGHLIGHT = Regex("""==(.+?)==""")
    private val ITALIC = Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""")

    fun segments(spans: List<InlineSpan>): List<SpanSegment> {
        val result = ArrayList<SpanSegment>(spans.size)
        var cursor = 0
        for (span in spans) {
            val len = span.toVisibleString().length
            if (len > 0) {
                result.add(SpanSegment(span, cursor, cursor + len))
                cursor += len
            }
        }
        return result
    }

    /**
     * Coalesces adjacent [InlineSpan.Text] nodes with identical styling and drops empty text nodes.
     */
    fun normalize(spans: List<InlineSpan>): List<InlineSpan> {
        if (spans.isEmpty()) return emptyList()
        // Fast path: already-normalized lists (the common case while typing) are returned as-is,
        // avoiding an allocation on every keystroke.
        var needsWork = false
        for (i in spans.indices) {
            val span = spans[i]
            if (span is InlineSpan.Text) {
                if (span.text.isEmpty()) {
                    needsWork = true
                    break
                }
                if (i > 0) {
                    val prev = spans[i - 1]
                    if (prev is InlineSpan.Text &&
                        prev.marks == span.marks &&
                        prev.linkUrl == span.linkUrl &&
                        prev.textColor == span.textColor &&
                        prev.backgroundColor == span.backgroundColor
                    ) {
                        needsWork = true
                        break
                    }
                }
            }
        }
        if (!needsWork) return spans

        val out = ArrayList<InlineSpan>(spans.size)
        for (span in spans) {
            when (span) {
                is InlineSpan.Text -> {
                    if (span.text.isEmpty()) continue
                    val prev = out.lastOrNull()
                    if (prev is InlineSpan.Text &&
                        prev.marks == span.marks &&
                        prev.linkUrl == span.linkUrl &&
                        prev.textColor == span.textColor &&
                        prev.backgroundColor == span.backgroundColor
                    ) {
                        out[out.lastIndex] = prev.copy(text = prev.text + span.text)
                    } else {
                        out.add(span)
                    }
                }
                else -> out.add(span)
            }
        }
        return out
    }

    /**
     * Normalizes [spans] by coalescing adjacent [InlineSpan.Text] runs with identical styling.
     * Note: Raw Markdown parsing is only performed explicitly via [fromLegacyMarkdown] / [parseMarkdownToSpans]
     * when importing Markdown, never during live keystrokes.
     */
    fun upgradeLegacySpans(spans: List<InlineSpan>): List<InlineSpan> = normalize(spans)

    private fun containsPotentialMarkdownSyntax(text: String): Boolean {
        for (i in 0 until text.length) {
            when (text[i]) {
                '@', '[', '#', '*', '<', '~', '`', '=' -> return true
            }
        }
        return false
    }

    /**
     * Parses Markdown inline syntax into structured [InlineSpan]s.
     */
    fun parseMarkdownToSpans(raw: String): List<InlineSpan> {
        if (raw.isEmpty()) return emptyList()
        if (!containsPotentialMarkdownSyntax(raw)) {
            return listOf(InlineSpan.Text(raw))
        }
        val out = mutableListOf<InlineSpan>()

        fun parseSegment(
            text: String,
            marks: Set<TextMark>,
            linkUrl: String? = null,
            textColor: String? = null,
            bgColor: String? = null,
        ) {
            var index = 0
            val len = text.length
            val plainBuffer = StringBuilder()

            fun flushPlain() {
                if (plainBuffer.isNotEmpty()) {
                    out.add(
                        InlineSpan.Text(
                            text = plainBuffer.toString(),
                            marks = marks,
                            linkUrl = linkUrl,
                            textColor = textColor,
                            backgroundColor = bgColor,
                        ),
                    )
                    plainBuffer.clear()
                }
            }

            while (index < len) {
                when (val ch = text[index]) {
                    '@' -> {
                        val mMention = MENTION_BRACKET.matchAt(text, index)
                        if (mMention != null) {
                            flushPlain()
                            val content = mMention.groupValues[1].trim()
                            val title = content.substringAfterLast('/').trim().ifBlank { content }
                            out.add(InlineSpan.Mention(targetObjectId = content, fallbackText = title))
                            index = mMention.range.last + 1
                            continue
                        }
                        val mDate = DATE_TOKEN.matchAt(text, index)
                        if (mDate != null) {
                            flushPlain()
                            val dateIso = mDate.groupValues[1]
                            out.add(InlineSpan.Date(isoDate = dateIso))
                            index = mDate.range.last + 1
                            continue
                        }
                    }

                    '[' -> {
                        val mWiki = WIKILINK.matchAt(text, index)
                        if (mWiki != null) {
                            flushPlain()
                            val content = mWiki.groupValues[1].trim()
                            val title = content.substringAfterLast('/').trim().ifBlank { content }
                            out.add(InlineSpan.Mention(targetObjectId = content, fallbackText = title))
                            index = mWiki.range.last + 1
                            continue
                        }
                        val mLink = MARKDOWN_LINK.matchAt(text, index)
                        if (mLink != null) {
                            flushPlain()
                            val label = mLink.groupValues[1]
                            val url = mLink.groupValues[2].trim()
                            parseSegment(label, marks, linkUrl = url, textColor = textColor, bgColor = bgColor)
                            index = mLink.range.last + 1
                            continue
                        }
                    }

                    '#' -> {
                        val mTag = TAG_TOKEN.matchAt(text, index)
                        if (mTag != null) {
                            flushPlain()
                            val tagName = mTag.groupValues[1]
                            val tagId = "tag_${tagName.lowercase()}"
                            out.add(InlineSpan.Tag(tagId = tagId, tagName = tagName))
                            index = mTag.range.last + 1
                            continue
                        }
                    }

                    '*' -> {
                        val mBoldItalic = BOLD_ITALIC.matchAt(text, index)
                        if (mBoldItalic != null) {
                            flushPlain()
                            parseSegment(
                                mBoldItalic.groupValues[1],
                                marks + TextMark.BOLD + TextMark.ITALIC,
                                linkUrl,
                                textColor,
                                bgColor,
                            )
                            index = mBoldItalic.range.last + 1
                            continue
                        }
                        val mBold = BOLD.matchAt(text, index)
                        if (mBold != null) {
                            flushPlain()
                            parseSegment(mBold.groupValues[1], marks + TextMark.BOLD, linkUrl, textColor, bgColor)
                            index = mBold.range.last + 1
                            continue
                        }
                        val mItalic = ITALIC.matchAt(text, index)
                        if (mItalic != null) {
                            flushPlain()
                            parseSegment(mItalic.groupValues[1], marks + TextMark.ITALIC, linkUrl, textColor, bgColor)
                            index = mItalic.range.last + 1
                            continue
                        }
                    }

                    '<' -> {
                        val mUnderline = UNDERLINE_HTML.matchAt(text, index)
                        if (mUnderline != null) {
                            flushPlain()
                            parseSegment(mUnderline.groupValues[1], marks + TextMark.UNDERLINE, linkUrl, textColor, bgColor)
                            index = mUnderline.range.last + 1
                            continue
                        }
                    }

                    '~' -> {
                        val mStrike = STRIKE.matchAt(text, index)
                        if (mStrike != null) {
                            flushPlain()
                            parseSegment(mStrike.groupValues[1], marks + TextMark.STRIKETHROUGH, linkUrl, textColor, bgColor)
                            index = mStrike.range.last + 1
                            continue
                        }
                    }

                    '`' -> {
                        val mCode = CODE.matchAt(text, index)
                        if (mCode != null) {
                            flushPlain()
                            out.add(
                                InlineSpan.Text(
                                    text = mCode.groupValues[1],
                                    marks = marks + TextMark.CODE,
                                    linkUrl = linkUrl,
                                    textColor = textColor,
                                    backgroundColor = bgColor,
                                ),
                            )
                            index = mCode.range.last + 1
                            continue
                        }
                    }

                    '=' -> {
                        val mHighlight = HIGHLIGHT.matchAt(text, index)
                        if (mHighlight != null) {
                            flushPlain()
                            parseSegment(mHighlight.groupValues[1], marks + TextMark.HIGHLIGHT, linkUrl, textColor, bgColor)
                            index = mHighlight.range.last + 1
                            continue
                        }
                    }
                }

                plainBuffer.append(text[index])
                index++
            }
            flushPlain()
        }

        parseSegment(raw, emptySet())
        return normalize(out)
    }

    /**
     * Slices [spans] to the sub-range `[sliceStart, sliceEnd)` in visible character coordinates.
     */
    fun sliceSpans(spans: List<InlineSpan>, sliceStart: Int, sliceEnd: Int): List<InlineSpan> {
        if (sliceEnd <= sliceStart) return emptyList()
        val segs = segments(spans)
        val out = mutableListOf<InlineSpan>()
        for (seg in segs) {
            if (seg.end <= sliceStart || seg.start >= sliceEnd) continue
            val localStart = (sliceStart - seg.start).coerceAtLeast(0)
            val localEnd = (sliceEnd - seg.start).coerceAtMost(seg.length)
            if (localEnd <= localStart) continue
            when (val s = seg.span) {
                is InlineSpan.Text -> {
                    out.add(s.copy(text = s.text.substring(localStart, localEnd)))
                }
                else -> {
                    if (localStart == 0 && localEnd == seg.length) {
                        out.add(s)
                    } else {
                        // Partial slice of an atomic span degrades to plain text
                        out.add(InlineSpan.Text(s.toVisibleString().substring(localStart, localEnd)))
                    }
                }
            }
        }
        return normalize(out)
    }

    /**
     * Splits [spans] at [offset] into `(beforeSpans, afterSpans)` preserving all marks, links, colors, and atomic nodes.
     */
    fun splitSpansAt(spans: List<InlineSpan>, offset: Int): Pair<List<InlineSpan>, List<InlineSpan>> {
        val upgraded = upgradeLegacySpans(spans)
        val totalLen = upgraded.toPlainText().length
        val clamped = offset.coerceIn(0, totalLen)
        return sliceSpans(upgraded, 0, clamped) to sliceSpans(upgraded, clamped, totalLen)
    }

    /**
     * Applies a plain-text edit from `BasicTextField` onto structured [oldSpans].
     * Returns `(updatedSpans, resultingCaretOffset)`.
     *
     * - Deleting any character of an atomic span (`Mention`, `Tag`, `Date`) removes the entire atomic span cleanly.
     * - Newly inserted characters receive [activeMarks], [activeTextColor], and [activeBgColor].
     * - Typing at the boundary of a URL link does NOT extend the link, preventing the cursor from getting trapped.
     */
    private fun isSingleUnstyledTextOrEmpty(spans: List<InlineSpan>): Boolean {
        if (spans.isEmpty()) return true
        if (spans.size != 1) return false
        val s = spans[0] as? InlineSpan.Text ?: return false
        return s.marks.isEmpty() && s.linkUrl == null && s.textColor == null && s.backgroundColor == null
    }

    fun applyTextEdit(
        oldSpans: List<InlineSpan>,
        newVisibleText: String,
        newCaret: Int,
        activeMarks: Set<TextMark>? = null,
        activeTextColor: String? = null,
        activeBgColor: String? = null,
        hasExplicitTextColor: Boolean = false,
        hasExplicitBgColor: Boolean = false,
    ): Pair<List<InlineSpan>, Int> {
        val clampedCaret = newCaret.coerceIn(0, newVisibleText.length)
        // Fast-path for plain unstyled text with no active formatting overrides (95%+ of typing)
        if (
            isSingleUnstyledTextOrEmpty(oldSpans) &&
            (activeMarks == null || activeMarks.isEmpty()) &&
            !hasExplicitTextColor &&
            !hasExplicitBgColor
        ) {
            val nextSpans = if (newVisibleText.isEmpty()) emptyList() else listOf(InlineSpan.Text(newVisibleText))
            return nextSpans to clampedCaret
        }

        val upgraded = upgradeLegacySpans(oldSpans)
        val oldText = upgraded.toPlainText()
        if (oldText == newVisibleText) {
            return upgraded to clampedCaret
        }

        // Compute diff window anchored at clampedCaret so repeated characters ("aa", "ll", "  ")
        // never shift the edit point or move the user's cursor.
        var suffixLen = 0
        val maxSuffixBeforeCaret = minOf(oldText.length, newVisibleText.length - clampedCaret)
        while (suffixLen < maxSuffixBeforeCaret &&
            oldText[oldText.length - 1 - suffixLen] == newVisibleText[newVisibleText.length - 1 - suffixLen]
        ) {
            suffixLen++
        }

        var prefixLen = 0
        val maxPrefix = minOf(oldText.length - suffixLen, newVisibleText.length - suffixLen)
        while (prefixLen < maxPrefix && oldText[prefixLen] == newVisibleText[prefixLen]) {
            prefixLen++
        }

        // If IME updated characters after the caret (e.g. composing word replacement),
        // consume any remaining common suffix after prefixLen is fixed.
        val remainingSuffixMax = minOf(oldText.length - prefixLen, newVisibleText.length - prefixLen)
        while (suffixLen < remainingSuffixMax &&
            oldText[oldText.length - 1 - suffixLen] == newVisibleText[newVisibleText.length - 1 - suffixLen]
        ) {
            suffixLen++
        }

        var delStart = prefixLen
        var delEnd = oldText.length - suffixLen
        val inserted = newVisibleText.substring(prefixLen, newVisibleText.length - suffixLen)

        val segs = segments(upgraded)
        var atomicExpanded = false

        // Atomic deletion: if user deleted characters overlapping an atomic span (Mention, Tag, Date),
        // expand the deletion range to remove the entire atomic span in one step.
        if (delStart < delEnd && inserted.isEmpty()) {
            for (seg in segs) {
                if (seg.isAtomic && delStart < seg.end && delEnd > seg.start) {
                    if (seg.start < delStart || seg.end > delEnd) {
                        atomicExpanded = true
                    }
                    delStart = minOf(delStart, seg.start)
                    delEnd = maxOf(delEnd, seg.end)
                }
            }
        }

        val before = sliceSpans(upgraded, 0, delStart)
        val after = sliceSpans(upgraded, delEnd, oldText.length)

        if (inserted.isEmpty()) {
            val combined = normalize(before + after)
            val resultingCaret = if (atomicExpanded) delStart else clampedCaret
            return combined to resultingCaret.coerceIn(0, combined.toPlainText().length)
        }

        // Determine style for inserted text
        val inheritedSpan = segs.firstOrNull { delStart in (it.start + 1)..it.end && it.span is InlineSpan.Text }
            ?.span as? InlineSpan.Text
            ?: segs.firstOrNull { delStart == it.start && it.span is InlineSpan.Text }?.span as? InlineSpan.Text

        val effectiveMarks = activeMarks ?: inheritedSpan?.marks.orEmpty()
        val effectiveTextColor = if (hasExplicitTextColor) activeTextColor else inheritedSpan?.textColor
        val effectiveBgColor = if (hasExplicitBgColor) activeBgColor else inheritedSpan?.backgroundColor

        // Link safety: only keep linkUrl when typing strictly INSIDE an existing link span (not at its edges or after space)
        val strictlyInsideLink = segs.firstOrNull {
            it.span is InlineSpan.Text &&
                !it.span.linkUrl.isNullOrBlank() &&
                delStart > it.start &&
                delEnd < it.end
        }?.span as? InlineSpan.Text
        val effectiveLinkUrl = if (!inserted.contains(' ')) strictlyInsideLink?.linkUrl else null

        val insertedSpan = InlineSpan.Text(
            text = inserted,
            marks = effectiveMarks,
            linkUrl = effectiveLinkUrl,
            textColor = effectiveTextColor,
            backgroundColor = effectiveBgColor,
        )

        val combined = normalize(before + listOf(insertedSpan) + after)
        val nextCaret = clampedCaret.coerceIn(0, combined.toPlainText().length)
        return combined to nextCaret
    }

    /**
     * Returns the set of [TextMark]s active at [offset] (or across `[start, end)`).
     */
    fun marksInRange(spans: List<InlineSpan>, start: Int, end: Int): Set<TextMark> {
        val upgraded = upgradeLegacySpans(spans)
        val segs = segments(upgraded)
        if (segs.isEmpty()) return emptySet()
        if (start == end) {
            val target = segs.firstOrNull { start in (it.start + 1)..it.end }
                ?: segs.firstOrNull { start == it.start }
            return (target?.span as? InlineSpan.Text)?.marks.orEmpty()
        }
        val overlapping = segs.filter { it.end > start && it.start < end }
            .mapNotNull { it.span as? InlineSpan.Text }
        if (overlapping.isEmpty()) return emptySet()
        return overlapping.map { it.marks }.reduce { acc, marks -> acc.intersect(marks) }
    }

    /**
     * Toggles [mark] on `[start, end)`. If all text spans in `[start, end)` already have [mark],
     * removes it; otherwise adds it.
     */
    fun toggleMarkInRange(
        spans: List<InlineSpan>,
        start: Int,
        end: Int,
        mark: TextMark,
    ): List<InlineSpan> {
        val upgraded = upgradeLegacySpans(spans)
        val totalLen = upgraded.toPlainText().length
        val s = minOf(start, end).coerceIn(0, totalLen)
        val e = maxOf(start, end).coerceIn(0, totalLen)
        if (s >= e) return upgraded

        val before = sliceSpans(upgraded, 0, s)
        val middle = sliceSpans(upgraded, s, e)
        val after = sliceSpans(upgraded, e, totalLen)

        val textNodes = middle.filterIsInstance<InlineSpan.Text>()
        val allHaveMark = textNodes.isNotEmpty() && textNodes.all { mark in it.marks }

        val updatedMiddle = middle.map { span ->
            if (span is InlineSpan.Text) {
                val nextMarks = if (allHaveMark) span.marks - mark else span.marks + mark
                span.copy(marks = nextMarks)
            } else {
                span
            }
        }
        return normalize(before + updatedMiddle + after)
    }

    /**
     * Applies or clears a named [colorName] (text or background) on `[start, end)`.
     * Automatically clears conflicting identical text/background colors to guarantee high contrast.
     */
    fun setColorInRange(
        spans: List<InlineSpan>,
        start: Int,
        end: Int,
        colorName: String?,
        isBackground: Boolean,
    ): List<InlineSpan> {
        val upgraded = upgradeLegacySpans(spans)
        val totalLen = upgraded.toPlainText().length
        val s = minOf(start, end).coerceIn(0, totalLen)
        val e = maxOf(start, end).coerceIn(0, totalLen)
        if (s >= e) return upgraded

        val before = sliceSpans(upgraded, 0, s)
        val middle = sliceSpans(upgraded, s, e)
        val after = sliceSpans(upgraded, e, totalLen)

        val updatedMiddle = middle.map { span ->
            if (span is InlineSpan.Text) {
                if (colorName == null) {
                    span.copy(
                        textColor = null,
                        backgroundColor = null,
                        marks = span.marks - TextMark.HIGHLIGHT,
                    )
                } else if (isBackground) {
                    val nextMarks = span.marks - TextMark.HIGHLIGHT
                    // Setting background color clears text color to prevent muddy overlap / summing
                    span.copy(backgroundColor = colorName, marks = nextMarks, textColor = null)
                } else {
                    // Setting text color clears background color to prevent muddy overlap / summing
                    span.copy(textColor = colorName, backgroundColor = null)
                }
            } else {
                span
            }
        }
        return normalize(before + updatedMiddle + after)
    }

    /**
     * Returns the active (textColor to backgroundColor) pair in `[start, end)`.
     * If all text spans within the range share the same color, returns that color name; otherwise null.
     */
    fun colorsInRange(
        spans: List<InlineSpan>,
        start: Int,
        end: Int,
    ): Pair<String?, String?> {
        val upgraded = upgradeLegacySpans(spans)
        val segs = segments(upgraded)
        if (segs.isEmpty()) return null to null
        if (start == end) {
            val target = segs.firstOrNull { start in (it.start + 1)..it.end }
                ?: segs.firstOrNull { start == it.start }
            val textSpan = target?.span as? InlineSpan.Text
            return textSpan?.textColor to textSpan?.backgroundColor
        }
        val overlapping = segs.filter { it.end > start && it.start < end }
            .mapNotNull { it.span as? InlineSpan.Text }
        if (overlapping.isEmpty()) return null to null
        val firstText = overlapping.firstOrNull()?.textColor
        val allSameText = overlapping.all { it.textColor.equals(firstText, ignoreCase = true) }
        val firstBg = overlapping.firstOrNull()?.backgroundColor
        val allSameBg = overlapping.all { it.backgroundColor.equals(firstBg, ignoreCase = true) }
        return (if (allSameText) firstText else null) to (if (allSameBg) firstBg else null)
    }

    /**
     * Applies, updates, or removes a URL link on `[start, end)`.
     * If `start == end` and `url != null`, inserts a new link span with [fallbackLabel] (or [url]).
     */
    fun setLinkInRange(
        spans: List<InlineSpan>,
        start: Int,
        end: Int,
        url: String?,
        fallbackLabel: String? = null,
    ): Pair<List<InlineSpan>, Int> {
        val upgraded = upgradeLegacySpans(spans)
        val totalLen = upgraded.toPlainText().length
        val s = minOf(start, end).coerceIn(0, totalLen)
        val e = maxOf(start, end).coerceIn(0, totalLen)
        val cleanUrl = url?.trim()?.takeIf { it.isNotEmpty() }

        if (s == e) {
            if (cleanUrl == null) return upgraded to s
            val label = fallbackLabel?.trim()?.takeIf { it.isNotEmpty() } ?: cleanUrl
            val before = sliceSpans(upgraded, 0, s)
            val after = sliceSpans(upgraded, s, totalLen)
            val linkSpan = InlineSpan.Text(text = label, linkUrl = cleanUrl)
            val trailingSpace = InlineSpan.Text(text = " ")
            val combined = normalize(before + listOf(linkSpan, trailingSpace) + after)
            return combined to (s + label.length + 1).coerceAtMost(combined.toPlainText().length)
        }

        val before = sliceSpans(upgraded, 0, s)
        val middle = sliceSpans(upgraded, s, e).map { span ->
            when (span) {
                is InlineSpan.Text -> span.copy(linkUrl = cleanUrl)
                else -> InlineSpan.Text(text = span.toVisibleString(), linkUrl = cleanUrl)
            }
        }
        val after = sliceSpans(upgraded, e, totalLen)
        val combined = normalize(before + middle + after)
        return combined to e.coerceAtMost(combined.toPlainText().length)
    }

    /**
     * Replaces `[replaceStart, replaceEnd)` in [spans] with an atomic span (`Mention`, `Tag`, `Date`),
     * optionally followed by a plain space so typing continues smoothly after the pill.
     */
    fun replaceRangeWithAtomic(
        spans: List<InlineSpan>,
        replaceStart: Int,
        replaceEnd: Int,
        atomic: InlineSpan,
        appendTrailingSpace: Boolean = true,
    ): Pair<List<InlineSpan>, Int> {
        val upgraded = upgradeLegacySpans(spans)
        val totalLen = upgraded.toPlainText().length
        val s = minOf(replaceStart, replaceEnd).coerceIn(0, totalLen)
        val e = maxOf(replaceStart, replaceEnd).coerceIn(0, totalLen)

        val before = sliceSpans(upgraded, 0, s)
        val after = sliceSpans(upgraded, e, totalLen)
        val afterPlain = after.toPlainText()
        val needsSpace = appendTrailingSpace && !afterPlain.startsWith(" ")
        val insertedList = if (needsSpace) {
            listOf(atomic, InlineSpan.Text(" "))
        } else {
            listOf(atomic)
        }
        val combined = normalize(before + insertedList + after)
        val caret = s + atomic.toVisibleString().length + (if (needsSpace) 1 else 0)
        return combined to caret.coerceIn(0, combined.toPlainText().length)
    }

    /**
     * Removes the span covering `[rangeStart, rangeEnd)` (e.g. from the Mention preview "Remove mention" action).
     */
    fun removeSpanAtRange(
        spans: List<InlineSpan>,
        rangeStart: Int,
        rangeEnd: Int,
    ): List<InlineSpan> {
        val upgraded = upgradeLegacySpans(spans)
        val totalLen = upgraded.toPlainText().length
        val s = minOf(rangeStart, rangeEnd).coerceIn(0, totalLen)
        val e = maxOf(rangeStart, rangeEnd).coerceIn(0, totalLen)
        val before = sliceSpans(upgraded, 0, s)
        var after = sliceSpans(upgraded, e, totalLen)
        // Trim leading double space if removing an inline pill left two spaces
        if (before.toPlainText().endsWith(" ") && after.toPlainText().startsWith(" ")) {
            after = sliceSpans(after, 1, after.toPlainText().length)
        }
        return normalize(before + after)
    }

    fun fromLegacyMarkdown(raw: String): List<InlineSpan> = parseMarkdownToSpans(raw)

    fun coalesce(spans: List<InlineSpan>): List<InlineSpan> = normalize(spans)

    fun splitAt(spans: List<InlineSpan>, offset: Int): Pair<List<InlineSpan>, List<InlineSpan>> =
        splitSpansAt(spans, offset)

    fun tokenIdToMark(tokenId: String): TextMark? = when (tokenId.lowercase()) {
        "bold" -> TextMark.BOLD
        "italic" -> TextMark.ITALIC
        "underline" -> TextMark.UNDERLINE
        "strike", "strikethrough" -> TextMark.STRIKETHROUGH
        "inline_code", "code" -> TextMark.CODE
        "highlight" -> TextMark.HIGHLIGHT
        else -> null
    }

    fun markToTokenId(mark: TextMark): String = when (mark) {
        TextMark.BOLD -> "bold"
        TextMark.ITALIC -> "italic"
        TextMark.UNDERLINE -> "underline"
        TextMark.STRIKETHROUGH -> "strike"
        TextMark.CODE -> "inline_code"
        TextMark.HIGHLIGHT -> "highlight"
    }

    fun activeMarksInRange(spans: List<InlineSpan>, start: Int, end: Int): Set<String> =
        marksInRange(spans, start, end).mapTo(mutableSetOf(), ::markToTokenId)

    fun toggleMarkInRange(
        spans: List<InlineSpan>,
        start: Int,
        end: Int,
        tokenId: String,
    ): List<InlineSpan> {
        val mark = tokenIdToMark(tokenId) ?: return spans
        return toggleMarkInRange(spans, start, end, mark)
    }

    fun applyColorToRange(
        spans: List<InlineSpan>,
        start: Int,
        end: Int,
        colorName: String?,
        isBackground: Boolean,
    ): List<InlineSpan> = setColorInRange(spans, start, end, colorName, isBackground)

    fun applyLinkToRange(
        spans: List<InlineSpan>,
        start: Int,
        end: Int,
        url: String?,
    ): List<InlineSpan> = setLinkInRange(spans, start, end, url).first

    fun applyTextEdit(
        oldSpans: List<InlineSpan>,
        oldText: String,
        newText: String,
        newCaret: Int,
        activeFormats: Set<String>? = null,
        activeTextColor: String? = null,
        activeBgColor: String? = null,
        hasExplicitTextColor: Boolean = false,
        hasExplicitBgColor: Boolean = false,
    ): Pair<List<InlineSpan>, Int> {
        val marks = activeFormats?.mapNotNull(::tokenIdToMark)?.toSet()
        return applyTextEdit(
            oldSpans = oldSpans,
            newVisibleText = newText,
            newCaret = newCaret,
            activeMarks = marks,
            activeTextColor = activeTextColor,
            activeBgColor = activeBgColor,
            hasExplicitTextColor = hasExplicitTextColor,
            hasExplicitBgColor = hasExplicitBgColor,
        )
    }
}
