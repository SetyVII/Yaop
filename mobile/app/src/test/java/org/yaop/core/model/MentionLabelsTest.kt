package org.yaop.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MentionLabelsTest {
    private val renamed = mapOf("obj-1" to "hydrogen")

    @Test
    fun renamedTargetUpdatesTheLabel() {
        val blocks = listOf(
            Block.Paragraph("b1", listOf(InlineSpan.Text("see "), InlineSpan.Mention("obj-1", "hydroge"))),
        )
        val out = refreshMentionLabels(blocks) { renamed[it] }
        val mention = (out[0] as Block.Paragraph).spans[1] as InlineSpan.Mention
        assertEquals("hydrogen", mention.fallbackText)
        assertEquals("obj-1", mention.targetObjectId)
    }

    @Test
    fun unchangedOrUnknownTargetsReturnTheSameList() {
        val current = listOf(Block.Paragraph("b1", listOf(InlineSpan.Mention("obj-1", "hydrogen"))))
        assertSame(current, refreshMentionLabels(current) { renamed[it] })

        val unknown = listOf(Block.Paragraph("b1", listOf(InlineSpan.Mention("gone", "old"))))
        assertSame(unknown, refreshMentionLabels(unknown) { renamed[it] })
    }

    @Test
    fun blankTitleKeepsTheLabel() {
        val blocks = listOf(Block.Paragraph("b1", listOf(InlineSpan.Mention("obj-1", "hydroge"))))
        assertSame(blocks, refreshMentionLabels(blocks) { "  " })
    }
}
