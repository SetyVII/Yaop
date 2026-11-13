package org.yaop.core.model

import org.yaop.core.model.whiteboard.WhiteboardData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class HeadingLevel {
    H1, H2, H3, H4
}

@Serializable
enum class CalloutType {
    INFO, IDEA, WARNING, NOTE
}

@Serializable
sealed interface Block {
    val id: String
    val indentLevel: Int
    val groupId: String?
    val blockColor: String?

    fun withIndent(level: Int): Block
    fun withGroup(newGroupId: String?): Block
    fun withColor(color: String?): Block
    fun withNewId(newId: String = UUID.randomUUID().toString()): Block

    @Serializable
    data class Paragraph(
        override val id: String,
        override val spans: List<InlineSpan> = emptyList(),
        val isCaption: Boolean = false,
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : SpannedBlock {
        override fun withIndent(level: Int): Paragraph = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Paragraph = copy(groupId = newGroupId)
        override fun withColor(color: String?): Paragraph = copy(blockColor = color)
        override fun withNewId(newId: String): Paragraph = copy(id = newId)
        override fun withSpans(newSpans: List<InlineSpan>): Paragraph = copy(spans = newSpans)
    }

    @Serializable
    data class Heading(
        override val id: String,
        val level: HeadingLevel,
        override val spans: List<InlineSpan> = emptyList(),
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : SpannedBlock {
        override fun withIndent(level: Int): Heading = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Heading = copy(groupId = newGroupId)
        override fun withColor(color: String?): Heading = copy(blockColor = color)
        override fun withNewId(newId: String): Heading = copy(id = newId)
        override fun withSpans(newSpans: List<InlineSpan>): Heading = copy(spans = newSpans)
    }

    @Serializable
    data class Todo(
        override val id: String,
        val checked: Boolean,
        override val spans: List<InlineSpan> = emptyList(),
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : SpannedBlock {
        override fun withIndent(level: Int): Todo = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Todo = copy(groupId = newGroupId)
        override fun withColor(color: String?): Todo = copy(blockColor = color)
        override fun withNewId(newId: String): Todo = copy(id = newId)
        override fun withSpans(newSpans: List<InlineSpan>): Todo = copy(spans = newSpans)
    }

    @Serializable
    data class BulletList(
        override val id: String,
        override val spans: List<InlineSpan> = emptyList(),
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : SpannedBlock {
        override fun withIndent(level: Int): BulletList = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): BulletList = copy(groupId = newGroupId)
        override fun withColor(color: String?): BulletList = copy(blockColor = color)
        override fun withNewId(newId: String): BulletList = copy(id = newId)
        override fun withSpans(newSpans: List<InlineSpan>): BulletList = copy(spans = newSpans)
    }

    @Serializable
    data class NumberedList(
        override val id: String,
        override val number: Int = 1,
        override val spans: List<InlineSpan> = emptyList(),
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : SequencedListBlock {
        override val listKey: String get() = BlockTypeKeys.NUMBERED
        override fun withIndent(level: Int): NumberedList = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): NumberedList = copy(groupId = newGroupId)
        override fun withColor(color: String?): NumberedList = copy(blockColor = color)
        override fun withNewId(newId: String): NumberedList = copy(id = newId)
        override fun withSpans(newSpans: List<InlineSpan>): NumberedList = copy(spans = newSpans)
        override fun withNumber(newNumber: Int): NumberedList = copy(number = newNumber)
    }

    @Serializable
    data class AlphabeticList(
        override val id: String,
        override val number: Int = 1,
        override val spans: List<InlineSpan> = emptyList(),
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : SequencedListBlock {
        override val listKey: String get() = BlockTypeKeys.ALPHABETIC
        override fun withIndent(level: Int): AlphabeticList = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): AlphabeticList = copy(groupId = newGroupId)
        override fun withColor(color: String?): AlphabeticList = copy(blockColor = color)
        override fun withNewId(newId: String): AlphabeticList = copy(id = newId)
        override fun withSpans(newSpans: List<InlineSpan>): AlphabeticList = copy(spans = newSpans)
        override fun withNumber(newNumber: Int): AlphabeticList = copy(number = newNumber)
    }

    @Serializable
    data class RomanList(
        override val id: String,
        override val number: Int = 1,
        override val spans: List<InlineSpan> = emptyList(),
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : SequencedListBlock {
        override val listKey: String get() = BlockTypeKeys.ROMAN
        override fun withIndent(level: Int): RomanList = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): RomanList = copy(groupId = newGroupId)
        override fun withColor(color: String?): RomanList = copy(blockColor = color)
        override fun withNewId(newId: String): RomanList = copy(id = newId)
        override fun withSpans(newSpans: List<InlineSpan>): RomanList = copy(spans = newSpans)
        override fun withNumber(newNumber: Int): RomanList = copy(number = newNumber)
    }

    /**
     * Flat toggle block : hides subsequent contiguous blocks
     * whose [indentLevel] is strictly greater than this toggle's [indentLevel] when [isExpanded] is false.
     */
    @Serializable
    data class Toggle(
        override val id: String,
        override val spans: List<InlineSpan> = emptyList(),
        val isExpanded: Boolean = true,
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : SpannedBlock {
        override fun withIndent(level: Int): Toggle = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Toggle = copy(groupId = newGroupId)
        override fun withColor(color: String?): Toggle = copy(blockColor = color)
        override fun withNewId(newId: String): Toggle = copy(id = newId)
        override fun withSpans(newSpans: List<InlineSpan>): Toggle = copy(spans = newSpans)
    }

    @Serializable
    data class Quote(
        override val id: String,
        override val spans: List<InlineSpan> = emptyList(),
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : SpannedBlock {
        override fun withIndent(level: Int): Quote = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Quote = copy(groupId = newGroupId)
        override fun withColor(color: String?): Quote = copy(blockColor = color)
        override fun withNewId(newId: String): Quote = copy(id = newId)
        override fun withSpans(newSpans: List<InlineSpan>): Quote = copy(spans = newSpans)
    }

    @Serializable
    data class Callout(
        override val id: String,
        @SerialName("calloutType")
        val type: CalloutType = CalloutType.NOTE,
        val emoji: String = "💡",
        override val spans: List<InlineSpan> = emptyList(),
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : SpannedBlock {
        override fun withIndent(level: Int): Callout = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Callout = copy(groupId = newGroupId)
        override fun withColor(color: String?): Callout = copy(blockColor = color)
        override fun withNewId(newId: String): Callout = copy(id = newId)
        override fun withSpans(newSpans: List<InlineSpan>): Callout = copy(spans = newSpans)
    }

    @Serializable
    data class Code(
        override val id: String,
        val code: String,
        val language: String = "kotlin",
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : Block {
        override fun withIndent(level: Int): Code = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Code = copy(groupId = newGroupId)
        override fun withColor(color: String?): Code = copy(blockColor = color)
        override fun withNewId(newId: String): Code = copy(id = newId)
    }

    @Serializable
    data class Math(
        override val id: String,
        val latex: String,
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : Block {
        override fun withIndent(level: Int): Math = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Math = copy(groupId = newGroupId)
        override fun withColor(color: String?): Math = copy(blockColor = color)
        override fun withNewId(newId: String): Math = copy(id = newId)
    }

    @Serializable
    data class Divider(
        override val id: String,
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : Block {
        override fun withIndent(level: Int): Divider = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Divider = copy(groupId = newGroupId)
        override fun withColor(color: String?): Divider = copy(blockColor = color)
        override fun withNewId(newId: String): Divider = copy(id = newId)
    }

    @Serializable
    data class Image(
        override val id: String,
        val assetId: String,
        val caption: List<InlineSpan> = emptyList(),
        val widthPercent: Float = 1.0f,
        val isUploading: Boolean = false,
        val uploadProgress: Float = 1.0f,
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : Block {
        override fun withIndent(level: Int): Image = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Image = copy(groupId = newGroupId)
        override fun withColor(color: String?): Image = copy(blockColor = color)
        override fun withNewId(newId: String): Image = copy(id = newId)
    }

    @Serializable
    data class Audio(
        override val id: String,
        val assetId: String,
        val durationSeconds: Int = 0,
        val caption: List<InlineSpan> = emptyList(),
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : Block {
        override fun withIndent(level: Int): Audio = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Audio = copy(groupId = newGroupId)
        override fun withColor(color: String?): Audio = copy(blockColor = color)
        override fun withNewId(newId: String): Audio = copy(id = newId)
    }

    @Serializable
    data class Pdf(
        override val id: String,
        val assetId: String,
        val pageCount: Int = 1,
        val lastReadPage: Int = 1,
        val fileName: String = "document.pdf",
        val isUploading: Boolean = false,
        val uploadProgress: Float = 1.0f,
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : Block {
        override fun withIndent(level: Int): Pdf = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Pdf = copy(groupId = newGroupId)
        override fun withColor(color: String?): Pdf = copy(blockColor = color)
        override fun withNewId(newId: String): Pdf = copy(id = newId)
    }

    @Serializable
    data class Table(
        override val id: String,
        val rows: List<List<String>> = listOf(
            listOf("", ""),
            listOf("", ""),
            listOf("", ""),
        ),
        val alignments: List<String> = emptyList(),
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : Block {
        override fun withIndent(level: Int): Table = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Table = copy(groupId = newGroupId)
        override fun withColor(color: String?): Table = copy(blockColor = color)
        override fun withNewId(newId: String): Table = copy(id = newId)
    }

    @Serializable
    data class ObjectEmbed(
        override val id: String,
        val targetObjectId: String,
        val viewMode: String = "card", // "card", "compact", "inline"
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : Block {
        override fun withIndent(level: Int): ObjectEmbed = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): ObjectEmbed = copy(groupId = newGroupId)
        override fun withColor(color: String?): ObjectEmbed = copy(blockColor = color)
        override fun withNewId(newId: String): ObjectEmbed = copy(id = newId)
    }

    @Serializable
    data class Bookmark(
        override val id: String,
        val url: String,
        val title: String = "",
        val description: String = "",
        val domain: String = "",
        val thumbnailUrl: String? = null,
        val faviconUrl: String? = null,
        val targetObjectId: String? = null,
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : Block {
        override fun withIndent(level: Int): Bookmark = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Bookmark = copy(groupId = newGroupId)
        override fun withColor(color: String?): Bookmark = copy(blockColor = color)
        override fun withNewId(newId: String): Bookmark = copy(id = newId)
    }

    @Serializable
    data class Canvas(
        override val id: String,
        val data: WhiteboardData = WhiteboardData(),
        override val indentLevel: Int = 0,
        override val groupId: String? = null,
        override val blockColor: String? = null,
    ) : Block {
        override fun withIndent(level: Int): Canvas = copy(indentLevel = level)
        override fun withGroup(newGroupId: String?): Canvas = copy(groupId = newGroupId)
        override fun withColor(color: String?): Canvas = copy(blockColor = color)
        override fun withNewId(newId: String): Canvas = copy(id = newId)
    }
}

/**
 * Sub-interface for blocks that support rich inline spans.
 */
@Serializable
sealed interface SpannedBlock : Block {
    val spans: List<InlineSpan>
    fun withSpans(newSpans: List<InlineSpan>): SpannedBlock
}

/**
 * Sub-interface for list blocks that maintain an ordinal counter (e.g. 1., a., I.).
 */
@Serializable
sealed interface SequencedListBlock : SpannedBlock {
    val number: Int
    val listKey: String
    fun withNumber(newNumber: Int): SequencedListBlock
}

/**
 * Formats an integer to Roman numerals (e.g. 1 -> "I", 2 -> "II", 4 -> "IV").
 */
fun Int.toRoman(): String {
    if (this <= 0) return this.toString()
    val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    var num = this
    val sb = StringBuilder()
    for (i in values.indices) {
        while (num >= values[i]) {
            num -= values[i]
            sb.append(symbols[i])
        }
    }
    return sb.toString()
}

/**
 * Formats a 1-based integer to lowercase alphabetic numerals (e.g. 1 -> "a", 2 -> "b", 27 -> "aa").
 */
fun Int.toAlphabetic(): String {
    if (this <= 0) return "a"
    var n = this
    val sb = StringBuilder()
    while (n > 0) {
        n--
        sb.insert(0, ('a'.code + (n % 26)).toChar())
        n /= 26
    }
    return sb.toString()
}
