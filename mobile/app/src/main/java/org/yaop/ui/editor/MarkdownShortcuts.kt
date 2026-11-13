package org.yaop.ui.editor

import org.yaop.core.model.Block
import org.yaop.core.model.CalloutType
import org.yaop.core.model.HeadingLevel
import org.yaop.core.model.RichSpanEngine
import org.yaop.core.model.toTextSpan
import java.util.UUID

sealed interface MarkdownShortcutResult {
    data class TransformBlock(val newBlock: Block) : MarkdownShortcutResult
    data class InsertDivider(val dividerBlock: Block, val nextParagraph: Block) : MarkdownShortcutResult
}

interface MarkdownShortcutHandler {
    fun processPrefix(currentBlock: Block, text: String): MarkdownShortcutResult?
}

/**
 * Evaluates silent block-start prefix shortcuts (`# `, `- `, `[] `, `1. `, `a. `, `i. `, `> `, `! `, `>> `, `▶ `, `---`, ```` ``` ````, `$$`).
 * Inline Markdown shortcuts (`**`, `*`, `~~`, `==`, `` ` ``) are intentionally omitted on mobile:
 * inline formatting is applied via the dynamic toolbar onto structured [org.yaop.core.model.InlineSpan]s.
 */
object DefaultMarkdownShortcutHandler : MarkdownShortcutHandler {
    override fun processPrefix(currentBlock: Block, text: String): MarkdownShortcutResult? {
        if (currentBlock !is Block.Paragraph && currentBlock !is Block.Heading) return null
        val indent = currentBlock.indentLevel
        val groupId = currentBlock.groupId
        val color = currentBlock.blockColor
        val currentSpans = BlockOperations.extractSpans(currentBlock)

        fun remainderSpans(prefixLen: Int) =
            if (text.length <= prefixLen) {
                emptyList()
            } else {
                val sliced = RichSpanEngine.sliceSpans(currentSpans, prefixLen, currentSpans.sumOf { it.toString().length })
                if (sliced.isNotEmpty() && BlockOperations.extractPlainText(currentBlock) == text) {
                    RichSpanEngine.sliceSpans(currentSpans, prefixLen, text.length)
                } else {
                    text.substring(prefixLen).toTextSpan()
                }
            }

        return when {
            // Divider: --- or ***
            text == "---" || text == "***" -> {
                val divider = Block.Divider(id = currentBlock.id, indentLevel = 0, groupId = groupId, blockColor = color)
                val nextPara = Block.Paragraph(id = UUID.randomUUID().toString(), spans = emptyList(), indentLevel = indent, groupId = groupId)
                MarkdownShortcutResult.InsertDivider(divider, nextPara)
            }
            // Headings
            text.startsWith("#### ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.Heading(currentBlock.id, HeadingLevel.H4, remainderSpans(5), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            text.startsWith("### ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.Heading(currentBlock.id, HeadingLevel.H3, remainderSpans(4), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            text.startsWith("## ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.Heading(currentBlock.id, HeadingLevel.H2, remainderSpans(3), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            text.startsWith("# ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.Heading(currentBlock.id, HeadingLevel.H1, remainderSpans(2), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            // Checkbox / Todo
            text.startsWith("[] ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.Todo(currentBlock.id, checked = false, spans = remainderSpans(3), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            text.startsWith("[ ] ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.Todo(currentBlock.id, checked = false, spans = remainderSpans(4), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            text.startsWith("[x] ") || text.startsWith("[X] ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.Todo(currentBlock.id, checked = true, spans = remainderSpans(4), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            // Bullet list
            text.startsWith("- ") || text.startsWith("* ") || text.startsWith("+ ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.BulletList(currentBlock.id, spans = remainderSpans(2), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            // Numbered list
            text.startsWith("1. ") || text.startsWith("1) ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.NumberedList(currentBlock.id, number = 1, spans = remainderSpans(3), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            // Alphabetic list (a. / A))
            text.startsWith("a. ") || text.startsWith("a) ") || text.startsWith("A. ") || text.startsWith("A) ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.AlphabeticList(currentBlock.id, number = 1, spans = remainderSpans(3), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            // Roman list
            text.startsWith("I. ") || text.startsWith("i. ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.RomanList(currentBlock.id, number = 1, spans = remainderSpans(3), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            // Group container (>> )
            text.startsWith(">> ") -> {
                val targetGroupId = groupId ?: UUID.randomUUID().toString()
                MarkdownShortcutResult.TransformBlock(
                    Block.Paragraph(currentBlock.id, spans = remainderSpans(3), indentLevel = indent, groupId = targetGroupId, blockColor = color),
                )
            }
            // Toggle (▶ or >>> )
            text.startsWith("▶ ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.Toggle(currentBlock.id, spans = remainderSpans(2), isExpanded = true, indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            text.startsWith(">>> ") -> {
                MarkdownShortcutResult.TransformBlock(
                    Block.Toggle(currentBlock.id, spans = remainderSpans(4), isExpanded = true, indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            // Quote
            text.startsWith("> ") || text.startsWith("\"\" ") -> {
                val prefixLen = if (text.startsWith("> ")) 2 else 3
                MarkdownShortcutResult.TransformBlock(
                    Block.Quote(currentBlock.id, spans = remainderSpans(prefixLen), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            // Callout note
            text.startsWith("! ") || text.startsWith("💡 ") -> {
                val prefixLen = if (text.startsWith("! ")) 2 else "💡 ".length
                MarkdownShortcutResult.TransformBlock(
                    Block.Callout(currentBlock.id, CalloutType.NOTE, "💡", spans = remainderSpans(prefixLen), indentLevel = indent, groupId = groupId, blockColor = color),
                )
            }
            // TeX / Math block
            text == "$$" || text.startsWith("$$ ") -> {
                val formula = text.removePrefix("$$ ").removePrefix("$$").trim()
                MarkdownShortcutResult.TransformBlock(
                    Block.Math(currentBlock.id, latex = formula, indentLevel = 0, groupId = groupId, blockColor = color),
                )
            }
            // Code block
            text == "```" || text.startsWith("``` ") -> {
                val lang = text.removePrefix("```").trim().ifEmpty { "kotlin" }
                MarkdownShortcutResult.TransformBlock(
                    Block.Code(currentBlock.id, code = "", language = lang, indentLevel = 0, groupId = groupId, blockColor = color),
                )
            }
            else -> null
        }
    }
}
