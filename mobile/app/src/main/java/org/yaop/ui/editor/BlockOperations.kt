package org.yaop.ui.editor

import org.yaop.core.model.Block
import org.yaop.core.model.BlockTypeKeys
import org.yaop.core.model.CalloutType
import org.yaop.core.model.HeadingLevel
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.RichSpanEngine
import org.yaop.core.model.SequencedListBlock
import org.yaop.core.model.SpannedBlock
import org.yaop.core.model.toPlainText
import org.yaop.core.model.toTextSpan
import java.util.UUID

sealed interface BlockSplitResult {
    data class ExitList(val convertedBlock: Block) : BlockSplitResult
    data class Split(val updatedCurrent: Block, val newNextBlock: Block) : BlockSplitResult
}

sealed interface BlockBackspaceResult {
    data class DemoteToParagraph(val demotedBlock: Block) : BlockBackspaceResult
    data class DeleteEmptyBlock(
        val previousBlockId: String,
        val cursorOffset: Int,
        val updatedBlocks: List<Block>,
    ) : BlockBackspaceResult
    data class MergeWithPrevious(
        val previousBlockId: String,
        val cursorOffset: Int,
        val updatedBlocks: List<Block>,
    ) : BlockBackspaceResult
    object NoOp : BlockBackspaceResult
}

data class VisibleBlockEntry(
    val originalIndex: Int,
    val block: Block,
    val hiddenChildrenCount: Int = 0,
    val isFirstInGroup: Boolean = false,
    val isLastInGroup: Boolean = false,
) {
    val realIndex: Int get() = originalIndex
}

interface BlockOperationsContract {
    fun extractPlainText(block: Block): String
    fun extractSpans(block: Block): List<InlineSpan>
    fun withSpans(block: Block, spans: List<InlineSpan>): Block
    fun withText(block: Block, text: String): Block
    fun canIndent(block: Block): Boolean
    fun withIndentLevel(block: Block, indentLevel: Int): Block
    fun withGroupId(block: Block, groupId: String?): Block
    fun withBlockColor(block: Block, blockColor: String?): Block
    fun splitOnEnter(currentBlock: Block, beforeText: String, afterText: String): BlockSplitResult
    fun splitOnEnterAtOffset(currentBlock: Block, caretOffset: Int): BlockSplitResult
    fun handleBackspaceOnEmpty(blocks: List<Block>, currentIndex: Int): BlockBackspaceResult
    fun convertBlockType(current: Block, targetType: String): Block
}

object BlockOperations : BlockOperationsContract {

    override fun extractPlainText(block: Block): String = when (block) {
        is SpannedBlock -> block.spans.toPlainText()
        is Block.Code -> block.code
        is Block.Math -> block.latex
        is Block.Table -> block.rows.joinToString("\n") { it.joinToString(" | ") }
        is Block.ObjectEmbed -> block.targetObjectId
        is Block.Bookmark -> block.title.ifBlank { block.url }
        is Block.Divider -> ""
        else -> ""
    }

    override fun extractSpans(block: Block): List<InlineSpan> = when (block) {
        is SpannedBlock -> RichSpanEngine.upgradeLegacySpans(block.spans)
        is Block.Code -> if (block.code.isEmpty()) emptyList() else listOf(InlineSpan.Text(block.code))
        is Block.Math -> if (block.latex.isEmpty()) emptyList() else listOf(InlineSpan.Text(block.latex))
        is Block.Table -> block.rows.flatten().joinToString(" ").toTextSpan()
        else -> emptyList()
    }

    override fun withSpans(block: Block, spans: List<InlineSpan>): Block {
        val normalized = RichSpanEngine.normalize(spans)
        return when (block) {
            is SpannedBlock -> block.withSpans(normalized)
            is Block.Code -> block.copy(code = normalized.toPlainText())
            is Block.Math -> block.copy(latex = normalized.toPlainText())
            else -> block
        }
    }

    override fun withText(block: Block, text: String): Block = when (block) {
        is Block.Code -> block.copy(code = text)
        is Block.Math -> block.copy(latex = text)
        else -> {
            val (updatedSpans, _) = RichSpanEngine.applyTextEdit(
                oldSpans = extractSpans(block),
                newVisibleText = text,
                newCaret = text.length,
            )
            withSpans(block, updatedSpans)
        }
    }

    override fun canIndent(block: Block): Boolean = block is SpannedBlock

    override fun withIndentLevel(block: Block, indentLevel: Int): Block {
        if (!canIndent(block)) return block
        return block.withIndent(indentLevel.coerceIn(0, 6))
    }

    override fun withGroupId(block: Block, groupId: String?): Block = block.withGroup(groupId)

    override fun withBlockColor(block: Block, blockColor: String?): Block = block.withColor(blockColor)

    fun isTextEditableBlock(block: Block): Boolean = canIndent(block)

    override fun splitOnEnterAtOffset(
        currentBlock: Block,
        caretOffset: Int,
    ): BlockSplitResult {
        val currentSpans = extractSpans(currentBlock)
        val (beforeSpans, afterSpans) = RichSpanEngine.splitSpansAt(currentSpans, caretOffset)
        return splitWithSpans(currentBlock, beforeSpans, afterSpans)
    }

    override fun splitOnEnter(
        currentBlock: Block,
        beforeText: String,
        afterText: String,
    ): BlockSplitResult {
        val currentSpans = extractSpans(currentBlock)
        val currentPlain = currentSpans.toPlainText()
        val (beforeSpans, afterSpans) = if (currentPlain == beforeText + afterText) {
            RichSpanEngine.splitSpansAt(currentSpans, beforeText.length)
        } else {
            beforeText.toTextSpan() to afterText.toTextSpan()
        }
        return splitWithSpans(currentBlock, beforeSpans, afterSpans)
    }

    private fun splitWithSpans(
        currentBlock: Block,
        beforeSpans: List<InlineSpan>,
        afterSpans: List<InlineSpan>,
    ): BlockSplitResult {
        val isExitCandidate = (currentBlock is Block.Todo) ||
            (currentBlock is Block.BulletList) ||
            (currentBlock is Block.NumberedList) ||
            (currentBlock is Block.AlphabeticList) ||
            (currentBlock is Block.RomanList) ||
            (currentBlock is Block.Quote) ||
            (currentBlock is Block.Toggle)

        if (isExitCandidate && beforeSpans.toPlainText().isBlank() && afterSpans.toPlainText().isBlank()) {
            val converted = Block.Paragraph(
                id = currentBlock.id,
                spans = emptyList(),
                indentLevel = currentBlock.indentLevel,
                groupId = currentBlock.groupId,
                blockColor = currentBlock.blockColor,
            )
            return BlockSplitResult.ExitList(converted)
        }

        val updatedCurrent = withSpans(currentBlock, beforeSpans)
        val groupId = currentBlock.groupId

        val newBlock = when (currentBlock) {
            is Block.Todo -> Block.Todo(
                id = UUID.randomUUID().toString(),
                checked = false,
                spans = afterSpans,
                indentLevel = currentBlock.indentLevel,
                groupId = groupId,
            )
            is Block.BulletList -> Block.BulletList(
                id = UUID.randomUUID().toString(),
                spans = afterSpans,
                indentLevel = currentBlock.indentLevel,
                groupId = groupId,
            )
            is Block.NumberedList -> Block.NumberedList(
                id = UUID.randomUUID().toString(),
                number = currentBlock.number + 1,
                spans = afterSpans,
                indentLevel = currentBlock.indentLevel,
                groupId = groupId,
            )
            is Block.AlphabeticList -> Block.AlphabeticList(
                id = UUID.randomUUID().toString(),
                number = currentBlock.number + 1,
                spans = afterSpans,
                indentLevel = currentBlock.indentLevel,
                groupId = groupId,
            )
            is Block.RomanList -> Block.RomanList(
                id = UUID.randomUUID().toString(),
                number = currentBlock.number + 1,
                spans = afterSpans,
                indentLevel = currentBlock.indentLevel,
                groupId = groupId,
            )
            is Block.Toggle -> {
                if (currentBlock.isExpanded) {
                    // Open toggle -> new block inside the toggle (+1 indent)
                    Block.Paragraph(
                        id = UUID.randomUUID().toString(),
                        spans = afterSpans,
                        indentLevel = (currentBlock.indentLevel + 1).coerceAtMost(6),
                        groupId = groupId,
                    )
                } else {
                    // Closed toggle -> sibling toggle below at same indent
                    Block.Toggle(
                        id = UUID.randomUUID().toString(),
                        spans = afterSpans,
                        isExpanded = false,
                        indentLevel = currentBlock.indentLevel,
                        groupId = groupId,
                    )
                }
            }
            else -> Block.Paragraph(
                id = UUID.randomUUID().toString(),
                spans = afterSpans,
                indentLevel = currentBlock.indentLevel,
                groupId = groupId,
            )
        }

        return BlockSplitResult.Split(updatedCurrent, newBlock)
    }

    /**
     * Handles Backspace at cursor index 0 in strict Notion order:
     * 1. Removes one indent level (`indentLevel > 0` -> `indentLevel - 1`).
     * 2. Removes the type (list / quote / heading / toggle / callout → paragraph), or leaves the group.
     * 3. Merges the spans into the previous block (or deletes the empty paragraph).
     */
    override fun handleBackspaceOnEmpty(
        blocks: List<Block>,
        currentIndex: Int,
    ): BlockBackspaceResult {
        if (currentIndex !in blocks.indices) return BlockBackspaceResult.NoOp
        val currentBlock = blocks[currentIndex]

        // 1. Outdent by 1 level first if indented
        if (currentBlock.indentLevel > 0) {
            val outdented = withIndentLevel(currentBlock, currentBlock.indentLevel - 1)
            return BlockBackspaceResult.DemoteToParagraph(outdented)
        }

        // 2. Demote special block type to Paragraph preserving spans, indent (0), and groupId
        if (currentBlock !is Block.Paragraph) {
            val demoted = Block.Paragraph(
                id = currentBlock.id,
                spans = extractSpans(currentBlock),
                indentLevel = 0,
                groupId = currentBlock.groupId,
                blockColor = currentBlock.blockColor,
            )
            return BlockBackspaceResult.DemoteToParagraph(demoted)
        }

        // 2b. If inside a group and at the start, leave the group before merging outside
        if (currentBlock.groupId != null) {
            val prevGroupId = blocks.getOrNull(currentIndex - 1)?.groupId
            if (prevGroupId != currentBlock.groupId) {
                return BlockBackspaceResult.DemoteToParagraph(currentBlock.copy(groupId = null))
            }
        }

        // 3. Paragraph at indent 0 -> delete if empty or merge spans with previous block
        if (currentIndex > 0) {
            val prevBlock = blocks[currentIndex - 1]
            val currentSpans = extractSpans(currentBlock)
            val currentText = currentSpans.toPlainText()
            val prevSpans = extractSpans(prevBlock)
            val prevText = prevSpans.toPlainText()
            val mutable = blocks.toMutableList()

            if (currentText.isEmpty()) {
                mutable.removeAt(currentIndex)
                return BlockBackspaceResult.DeleteEmptyBlock(
                    previousBlockId = prevBlock.id,
                    cursorOffset = prevText.length,
                    updatedBlocks = mutable,
                )
            } else if (isTextEditableBlock(prevBlock)) {
                val mergedSpans = RichSpanEngine.normalize(prevSpans + currentSpans)
                val mergedPrev = withSpans(prevBlock, mergedSpans)
                mutable[currentIndex - 1] = mergedPrev
                mutable.removeAt(currentIndex)
                return BlockBackspaceResult.MergeWithPrevious(
                    previousBlockId = prevBlock.id,
                    cursorOffset = prevText.length,
                    updatedBlocks = mutable,
                )
            }
        }

        return BlockBackspaceResult.NoOp
    }

    /**
     * Recomputes the display number of every numbered/alphabetic/roman item so that
     * indented items form their own sub-list: a deeper item restarts at 1 and
     * returning to the outer level continues its previous counter.
     */
    fun renumberLists(blocks: List<Block>): List<Block> {
        val counters = mutableMapOf<Pair<String, Int>, Int>()
        var lastListKey: Pair<String, Int>? = null
        return blocks.map { block ->
            if (block is SequencedListBlock) {
                val key = block.listKey to block.indentLevel
                val prevKey = lastListKey
                val n = if (prevKey == key || (prevKey != null && prevKey.second > block.indentLevel && counters.containsKey(key))) {
                    (counters[key] ?: 0) + 1
                } else {
                    1
                }
                counters.keys.retainAll { it.second <= block.indentLevel }
                counters[key] = n
                lastListKey = key
                block.withNumber(n)
            } else {
                counters.clear()
                lastListKey = null
                block
            }
        }
    }

    /**
     * Computes the visible blocks when flat [Block.Toggle] items are collapsed,
     * along with group boundary flags (`isFirstInGroup`, `isLastInGroup`) for rendering group boxes.
     */
    fun computeVisibleBlocks(blocks: List<Block>): List<VisibleBlockEntry> {
        val rawVisible = mutableListOf<VisibleBlockEntry>()
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            if (b is Block.Toggle && !b.isExpanded) {
                var hiddenCount = 0
                var j = i + 1
                while (j < blocks.size && blocks[j].indentLevel > b.indentLevel) {
                    hiddenCount++
                    j++
                }
                rawVisible.add(VisibleBlockEntry(originalIndex = i, block = b, hiddenChildrenCount = hiddenCount))
                i = j
            } else {
                // Even when a toggle is expanded, compute how many direct/nested children it has
                val childCount = if (b is Block.Toggle) {
                    var count = 0
                    var j = i + 1
                    while (j < blocks.size && blocks[j].indentLevel > b.indentLevel) {
                        count++
                        j++
                    }
                    count
                } else {
                    0
                }
                rawVisible.add(VisibleBlockEntry(originalIndex = i, block = b, hiddenChildrenCount = childCount))
                i++
            }
        }

        return rawVisible.mapIndexed { idx, entry ->
            val gid = entry.block.groupId
            if (gid.isNullOrBlank()) {
                entry
            } else {
                val prevGid = rawVisible.getOrNull(idx - 1)?.block?.groupId
                val nextGid = rawVisible.getOrNull(idx + 1)?.block?.groupId
                entry.copy(
                    isFirstInGroup = prevGid != gid,
                    isLastInGroup = nextGid != gid,
                )
            }
        }
    }

    override fun convertBlockType(current: Block, targetType: String): Block {
        val spans = extractSpans(current)
        val text = extractPlainText(current)
        val indent = current.indentLevel
        val groupId = current.groupId
        val color = current.blockColor

        return when (targetType) {
            BlockTypeKeys.H1 -> Block.Heading(current.id, HeadingLevel.H1, spans, indentLevel = indent, groupId = groupId, blockColor = color)
            BlockTypeKeys.H2 -> Block.Heading(current.id, HeadingLevel.H2, spans, indentLevel = indent, groupId = groupId, blockColor = color)
            BlockTypeKeys.H3 -> Block.Heading(current.id, HeadingLevel.H3, spans, indentLevel = indent, groupId = groupId, blockColor = color)
            BlockTypeKeys.H4 -> Block.Heading(current.id, HeadingLevel.H4, spans, indentLevel = indent, groupId = groupId, blockColor = color)
            BlockTypeKeys.TODO -> Block.Todo(current.id, checked = (current as? Block.Todo)?.checked ?: false, spans = spans, indentLevel = indent, groupId = groupId, blockColor = color)
            BlockTypeKeys.BULLET -> Block.BulletList(current.id, spans = spans, indentLevel = indent, groupId = groupId, blockColor = color)
            BlockTypeKeys.NUMBERED -> Block.NumberedList(current.id, number = 1, spans = spans, indentLevel = indent, groupId = groupId, blockColor = color)
            BlockTypeKeys.ALPHABETIC -> Block.AlphabeticList(current.id, number = 1, spans = spans, indentLevel = indent, groupId = groupId, blockColor = color)
            BlockTypeKeys.ROMAN -> Block.RomanList(current.id, number = 1, spans = spans, indentLevel = indent, groupId = groupId, blockColor = color)
            BlockTypeKeys.QUOTE -> Block.Quote(current.id, spans = spans, indentLevel = indent, groupId = groupId, blockColor = color)
            BlockTypeKeys.CALLOUT -> Block.Callout(current.id, CalloutType.NOTE, "💡", spans = spans, indentLevel = indent, groupId = groupId, blockColor = color)
            BlockTypeKeys.GROUP -> {
                val newGroupId = groupId ?: UUID.randomUUID().toString()
                withGroupId(
                    if (current is Block.Divider || current is Block.Table) {
                        Block.Paragraph(current.id, spans, indentLevel = indent, blockColor = color)
                    } else {
                        current
                    },
                    newGroupId,
                )
            }
            BlockTypeKeys.CODE -> Block.Code(current.id, code = text, indentLevel = 0, groupId = groupId, blockColor = color)
            BlockTypeKeys.MATH -> Block.Math(current.id, latex = text, indentLevel = 0, groupId = groupId, blockColor = color)
            BlockTypeKeys.TABLE -> Block.Table(current.id, indentLevel = 0, groupId = groupId, blockColor = color)
            BlockTypeKeys.DIVIDER -> Block.Divider(current.id, indentLevel = 0, groupId = groupId, blockColor = color)
            BlockTypeKeys.TOGGLE -> Block.Toggle(current.id, spans = spans, isExpanded = true, indentLevel = indent, groupId = groupId, blockColor = color)
            BlockTypeKeys.BOOKMARK -> {
                val url = text.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
                val domain = runCatching { java.net.URI(url).host?.removePrefix("www.").orEmpty() }.getOrDefault("")
                Block.Bookmark(current.id, url = url, title = url, domain = domain, indentLevel = 0, groupId = groupId, blockColor = color)
            }
            BlockTypeKeys.CANVAS -> {
                val existingData = (current as? Block.Canvas)?.data ?: org.yaop.core.model.whiteboard.WhiteboardData(id = UUID.randomUUID().toString())
                Block.Canvas(current.id, data = existingData, indentLevel = 0, groupId = groupId, blockColor = color)
            }
            else -> Block.Paragraph(current.id, spans, indentLevel = indent, groupId = groupId, blockColor = color)
        }
    }

    /**
     * Moves all blocks in [selectedIds] up (`delta = -1`) or down (`delta = +1`).
     */
    fun moveBlocks(blocks: List<Block>, selectedIds: Set<String>, delta: Int): List<Block> {
        if (selectedIds.isEmpty() || delta == 0) return blocks
        val list = blocks.toMutableList()
        if (delta < 0) {
            for (i in 1 until list.size) {
                if (list[i].id in selectedIds && list[i - 1].id !in selectedIds) {
                    val tmp = list[i - 1]
                    list[i - 1] = list[i]
                    list[i] = tmp
                }
            }
        } else {
            for (i in (list.size - 2) downTo 0) {
                if (list[i].id in selectedIds && list[i + 1].id !in selectedIds) {
                    val tmp = list[i + 1]
                    list[i + 1] = list[i]
                    list[i] = tmp
                }
            }
        }
        return renumberLists(list)
    }

    /**
     * Duplicates all blocks in [selectedIds] right after the last selected block, assigning new IDs.
     */
    fun duplicateBlocks(blocks: List<Block>, selectedIds: Set<String>): List<Block> {
        if (selectedIds.isEmpty()) return blocks
        val lastIdx = blocks.indexOfLast { it.id in selectedIds }
        if (lastIdx == -1) return blocks
        val selected = blocks.filter { it.id in selectedIds }.map { cloneBlockWithNewId(it) }
        val mutable = blocks.toMutableList()
        mutable.addAll(lastIdx + 1, selected)
        return renumberLists(mutable)
    }

    private fun cloneBlockWithNewId(block: Block): Block {
        val newId = UUID.randomUUID().toString()
        return block.withNewId(newId)
    }

    /**
     * Groups all selected blocks into a shared `groupId` (or ungroups them if they already share the same groupId).
     */
    fun toggleGroupBlocks(blocks: List<Block>, selectedIds: Set<String>): List<Block> {
        val selected = blocks.filter { it.id in selectedIds }
        if (selected.isEmpty()) return blocks
        val existingGroupId = selected.first().groupId
        val allInSameGroup = !existingGroupId.isNullOrBlank() && selected.all { it.groupId == existingGroupId }
        val targetGroupId = if (allInSameGroup) null else UUID.randomUUID().toString()
        return blocks.map { block ->
            if (block.id in selectedIds) withGroupId(block, targetGroupId) else block
        }
    }

    /**
     * Adjusts indentation by [delta] (`+1` or `-1`) on all indentable blocks in [selectedIds].
     */
    fun indentBlocks(blocks: List<Block>, selectedIds: Set<String>, delta: Int): List<Block> =
        renumberLists(
            blocks.map { block ->
                if (block.id in selectedIds && canIndent(block)) {
                    withIndentLevel(block, block.indentLevel + delta)
                } else {
                    block
                }
            },
        )

    /**
     * Applies or clears [colorName] on all blocks in [selectedIds].
     */
    fun colorBlocks(blocks: List<Block>, selectedIds: Set<String>, colorName: String?): List<Block> =
        blocks.map { block ->
            if (block.id in selectedIds) withBlockColor(block, colorName) else block
        }
}
