package org.yaop.ui.editor

import org.yaop.core.model.Block
import org.yaop.core.model.HeadingLevel
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.RichSpanEngine
import org.yaop.core.model.TextMark
import org.yaop.core.model.toAlphabetic
import org.yaop.core.model.toRoman
import org.yaop.core.model.toTextSpan
import org.yaop.core.model.toVisibleString
import org.yaop.core.sync.SyncContractCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockOperationsTest {

    @Test
    fun testRomanAndAlphabeticNumerals() {
        assertEquals("I", 1.toRoman())
        assertEquals("IV", 4.toRoman())
        assertEquals("IX", 9.toRoman())
        assertEquals("X", 10.toRoman())
        assertEquals("XL", 40.toRoman())
        assertEquals("XC", 90.toRoman())
        assertEquals("C", 100.toRoman())

        assertEquals("a", 1.toAlphabetic())
        assertEquals("b", 2.toAlphabetic())
        assertEquals("z", 26.toAlphabetic())
        assertEquals("aa", 27.toAlphabetic())
    }

    @Test
    fun testConvertBlockType() {
        val paragraph = Block.Paragraph("test-1", emptyList())

        val heading = BlockOperations.convertBlockType(paragraph, "h1")
        assertTrue(heading is Block.Heading)
        assertEquals(HeadingLevel.H1, (heading as Block.Heading).level)

        val todo = BlockOperations.convertBlockType(paragraph, "todo")
        assertTrue(todo is Block.Todo)

        val bullet = BlockOperations.convertBlockType(paragraph, "bullet")
        assertTrue(bullet is Block.BulletList)

        val alphabetic = BlockOperations.convertBlockType(paragraph, "alphabetic")
        assertTrue(alphabetic is Block.AlphabeticList)

        val bookmark = BlockOperations.convertBlockType(paragraph, "bookmark")
        assertTrue(bookmark is Block.Bookmark)

        val group = BlockOperations.convertBlockType(paragraph, "group")
        assertTrue(!group.groupId.isNullOrBlank())

        val math = BlockOperations.convertBlockType(paragraph, "math")
        assertTrue(math is Block.Math)

        val table = BlockOperations.convertBlockType(paragraph, "table")
        assertTrue(table is Block.Table)
    }

    @Test
    fun testRichSpanEngineMarksColorsAndAtomicDeletion() {
        val initial = listOf(InlineSpan.Text("Hola mundo"))
        val bolded = RichSpanEngine.toggleMarkInRange(initial, 0, 4, "bold")
        assertEquals("Hola mundo", bolded.toVisibleString())
        assertTrue(RichSpanEngine.activeMarksInRange(bolded, 0, 4).contains("bold"))
        assertFalse(RichSpanEngine.activeMarksInRange(bolded, 5, 10).contains("bold"))

        val colored = RichSpanEngine.applyColorToRange(bolded, 5, 10, "blue", isBackground = false)
        val secondRun = colored.last() as InlineSpan.Text
        assertEquals("blue", secondRun.textColor)

        // colorsInRange verification
        val (activeText, activeBg) = RichSpanEngine.colorsInRange(colored, 5, 10)
        assertEquals("blue", activeText)
        assertNull(activeBg)

        // If setting background to "blue" on text that already has "blue", textColor is cleared to avoid unreadable clash
        val coloredBg = RichSpanEngine.applyColorToRange(colored, 5, 10, "blue", isBackground = true)
        val bgRun = coloredBg.last() as InlineSpan.Text
        assertEquals("blue", bgRun.backgroundColor)
        assertNull(bgRun.textColor)

        // Test explicit override for typing: hasExplicitTextColor = true with null activeTextColor clears text color
        val (clearedTyping, _) = RichSpanEngine.applyTextEdit(
            oldSpans = colored,
            oldText = colored.toVisibleString(),
            newText = colored.toVisibleString() + "!",
            newCaret = 11,
            hasExplicitTextColor = true,
            activeTextColor = null,
        )
        val lastRun = clearedTyping.last() as InlineSpan.Text
        assertEquals("!", lastRun.text)
        assertNull(lastRun.textColor)

        // Test explicit override for typing format: activeFormats = emptySet() turns off bold
        val boldSpans = listOf(InlineSpan.Text("bold", marks = setOf(TextMark.BOLD)))
        val (unboldTyping, _) = RichSpanEngine.applyTextEdit(
            oldSpans = boldSpans,
            oldText = "bold",
            newText = "bold normal",
            newCaret = 11,
            activeFormats = emptySet(),
        )
        val unboldRun = unboldTyping.last() as InlineSpan.Text
        assertEquals(" normal", unboldRun.text)
        assertTrue(unboldRun.marks.isEmpty())

        // Test changing color mid-word: "ho" (red) then "la" (blue)
        val initialHo = listOf(InlineSpan.Text("ho", textColor = "red"))
        val (afterL, _) = RichSpanEngine.applyTextEdit(
            oldSpans = initialHo,
            oldText = "ho",
            newText = "hol",
            newCaret = 3,
            hasExplicitTextColor = true,
            activeTextColor = "blue",
            hasExplicitBgColor = true,
            activeBgColor = null,
        )
        val (afterLa, _) = RichSpanEngine.applyTextEdit(
            oldSpans = afterL,
            oldText = "hol",
            newText = "hola",
            newCaret = 4,
            hasExplicitTextColor = true,
            activeTextColor = "blue",
            hasExplicitBgColor = true,
            activeBgColor = null,
        )
        assertEquals(2, afterLa.size)
        assertEquals("ho", (afterLa[0] as InlineSpan.Text).text)
        assertEquals("red", (afterLa[0] as InlineSpan.Text).textColor)
        assertNull((afterLa[0] as InlineSpan.Text).backgroundColor)
        assertEquals("la", (afterLa[1] as InlineSpan.Text).text)
        assertEquals("blue", (afterLa[1] as InlineSpan.Text).textColor)
        assertNull((afterLa[1] as InlineSpan.Text).backgroundColor)

        // Atomic mention deletion: deleting a single character inside an InlineSpan.Mention removes the whole mention
        val withMention = listOf(
            InlineSpan.Text("Ver "),
            InlineSpan.Mention("arch"),
            InlineSpan.Text(" ahora"),
        )
        val oldVisible = withMention.toVisibleString() // "Ver @arch ahora"
        val editedVisible = oldVisible.removeRange(6, 7) // delete 'r' inside "@arch"
        val (afterDelete, _) = RichSpanEngine.applyTextEdit(
            oldSpans = withMention,
            oldText = oldVisible,
            newText = editedVisible,
            newCaret = 6,
        )
        assertTrue(afterDelete.none { it is InlineSpan.Mention })
        assertEquals("Ver  ahora", afterDelete.toVisibleString())
    }

    @Test
    fun testFlatToggleEnterAndVisibility() {
        val openToggle = Block.Toggle("t1", "Header".toTextSpan(), isExpanded = true, indentLevel = 0)
        val openSplit = BlockOperations.splitOnEnterAtOffset(openToggle, 6) as BlockSplitResult.Split
        assertTrue(openSplit.newNextBlock is Block.Paragraph)
        assertEquals(1, openSplit.newNextBlock.indentLevel)

        val closedToggle = Block.Toggle("t2", "Closed".toTextSpan(), isExpanded = false, indentLevel = 0)
        val closedSplit = BlockOperations.splitOnEnterAtOffset(closedToggle, 6) as BlockSplitResult.Split
        assertTrue(closedSplit.newNextBlock is Block.Toggle)
        assertEquals(0, closedSplit.newNextBlock.indentLevel)

        // Visibility computation when Toggle is collapsed
        val doc = listOf(
            closedToggle,
            Block.Paragraph("c1", "Hidden 1".toTextSpan(), indentLevel = 1),
            Block.Paragraph("c2", "Hidden 2".toTextSpan(), indentLevel = 2),
            Block.Paragraph("after", "Visible sibling".toTextSpan(), indentLevel = 0),
        )
        val visible = BlockOperations.computeVisibleBlocks(doc)
        assertEquals(2, visible.size)
        assertEquals("t2", visible[0].block.id)
        assertEquals(2, visible[0].hiddenChildrenCount)
        assertEquals("after", visible[1].block.id)
    }

    @Test
    fun testNotionOrderBackspaceAtStart() {
        // Step 1: Outdent first when indentLevel > 0
        val indentedBullet = Block.BulletList("b1", "Item".toTextSpan(), indentLevel = 2)
        val step1 = BlockOperations.handleBackspaceOnEmpty(listOf(indentedBullet), 0)
        assertTrue(step1 is BlockBackspaceResult.DemoteToParagraph)
        val outdented = (step1 as BlockBackspaceResult.DemoteToParagraph).demotedBlock
        assertTrue(outdented is Block.BulletList)
        assertEquals(1, outdented.indentLevel)

        // Step 2: At indentLevel 0, demote non-paragraph to Paragraph
        val rootBullet = Block.BulletList("b1", "Item".toTextSpan(), indentLevel = 0)
        val step2 = BlockOperations.handleBackspaceOnEmpty(listOf(rootBullet), 0)
        val demoted = (step2 as BlockBackspaceResult.DemoteToParagraph).demotedBlock
        assertTrue(demoted is Block.Paragraph)

        // Step 3: Paragraph at indentLevel 0 with groupId leaves group first
        val groupedPara = Block.Paragraph("p1", "Grouped".toTextSpan(), indentLevel = 0, groupId = "grp-1")
        val step2Group = BlockOperations.handleBackspaceOnEmpty(listOf(groupedPara), 0)
        val ungrouped = (step2Group as BlockBackspaceResult.DemoteToParagraph).demotedBlock
        assertNull(ungrouped.groupId)

        // Step 4: Plain Paragraph at indentLevel 0 merges with previous text block
        val first = Block.Paragraph("p0", "Hello ".toTextSpan())
        val second = Block.Paragraph("p1", "world".toTextSpan())
        val merged = BlockOperations.handleBackspaceOnEmpty(listOf(first, second), 1)
        assertTrue(merged is BlockBackspaceResult.MergeWithPrevious)
        val mergeResult = merged as BlockBackspaceResult.MergeWithPrevious
        assertEquals(1, mergeResult.updatedBlocks.size)
        assertEquals("Hello world", BlockOperations.extractPlainText(mergeResult.updatedBlocks[0]))
        assertEquals(6, mergeResult.cursorOffset)
    }

    @Test
    fun testSyncContractStructuredJsonRoundTrip() {
        val blocks = listOf(
            Block.Heading("h1", HeadingLevel.H2, listOf(InlineSpan.Text("Título", isBold = true))),
            Block.Paragraph("g1", "Dentro de grupo 1".toTextSpan(), groupId = "grp-A"),
            Block.Paragraph("g2", "Dentro de grupo 2".toTextSpan(), groupId = "grp-A"),
            Block.AlphabeticList("a1", 1, "Elemento A".toTextSpan(), indentLevel = 1),
            Block.Bookmark("bm1", url = "https://example.com", title = "Example"),
        )
        val jsonArr = SyncContractCodec.blocksToJsonArray(blocks)
        val decoded = SyncContractCodec.jsonArrayToBlocks(jsonArr)

        assertEquals(5, decoded.size)
        assertTrue(decoded[0] is Block.Heading)
        assertEquals( decoded[1].groupId, decoded[2].groupId)
        assertTrue(!decoded[1].groupId.isNullOrBlank())
        assertTrue(decoded[3] is Block.AlphabeticList)
        assertEquals(1, decoded[3].indentLevel)
        assertTrue(decoded[4] is Block.Bookmark)
        assertEquals("https://example.com", (decoded[4] as Block.Bookmark).url)
    }

    @Test
    fun testFluidTypingCaretPreservationAndWordSafety() {
        // Typing repeated characters in the middle of a word must keep exact caret offset
        val initial = listOf(InlineSpan.Text("casa"))
        val (afterInsert, caret) = RichSpanEngine.applyTextEdit(
            oldSpans = initial,
            oldText = "casa",
            newText = "cassa",
            newCaret = 3,
        )
        assertEquals("cassa", afterInsert.toVisibleString())
        assertEquals(3, caret)

        // Normal words starting with "tex" or "table" must never trigger block-type conversions
        val para = Block.Paragraph("p1", emptyList())
        assertNull(DefaultMarkdownShortcutHandler.processPrefix(para, "tex"))
        assertNull(DefaultMarkdownShortcutHandler.processPrefix(para, "texto"))
        assertNull(DefaultMarkdownShortcutHandler.processPrefix(para, "table"))
        assertNull(DefaultMarkdownShortcutHandler.processPrefix(para, "tablero"))
    }

    @Test
    fun testObjectMentionRenderingAndBoundsSafety() {
        // 1. Mention without fallbackText
        val mentionWithoutFallback = InlineSpan.Mention(targetObjectId = "8899aabb-1122-3344-5566-778899aabbcc")
        val visibleNoFallback = mentionWithoutFallback.toVisibleString()
        assertEquals("@8899aabb-1122-3344-5566-778899aabbcc", visibleNoFallback)

        val renderedNoFallback = MarkdownRenderer.renderSpans(listOf(mentionWithoutFallback))
        assertEquals(visibleNoFallback, renderedNoFallback.text.text)
        assertEquals(1, renderedNoFallback.pills.size)
        assertEquals(MarkdownPillKind.MENTION, renderedNoFallback.pills[0].kind)
        assertEquals("8899aabb-1122-3344-5566-778899aabbcc", renderedNoFallback.pills[0].target)

        // 2. Mention with fallbackText
        val mentionWithFallback = InlineSpan.Mention(
            targetObjectId = "8899aabb-1122-3344-5566-778899aabbcc",
            fallbackText = "Nota de reunión",
        )
        val visibleWithFallback = mentionWithFallback.toVisibleString()
        assertEquals("@Nota de reunión", visibleWithFallback)

        val renderedWithFallback = MarkdownRenderer.renderSpans(
            listOf(
                InlineSpan.Text("Mira esto: "),
                mentionWithFallback,
                InlineSpan.Text(" para más info."),
            ),
        )
        assertEquals("Mira esto: @Nota de reunión para más info.", renderedWithFallback.text.text)
        assertEquals(1, renderedWithFallback.pills.size)
        assertEquals("8899aabb-1122-3344-5566-778899aabbcc", renderedWithFallback.pills[0].target)
        assertEquals("Nota de reunión", renderedWithFallback.pills[0].label)

        // 3. Ensure RichSpanVisualTransformation handles mention without throwing OutOfBoundsException
        val transformation = RichSpanVisualTransformation(listOf(mentionWithFallback))
        val transformed = transformation.filter(androidx.compose.ui.text.AnnotatedString("@Nota de reunión"))
        assertEquals("@Nota de reunión", transformed.text.text)
    }

    @Test
    fun testDateSpanParsingRenderingAndAtomicBehavior() {
        // 1. Parsing text with @YYYY-MM-DD
        val parsed = RichSpanEngine.parseMarkdownToSpans("Mira esto @2026-11-08 y luego @2026-11-12 fin")
        val dateSpans = parsed.filterIsInstance<InlineSpan.Date>()
        assertEquals(2, dateSpans.size)
        assertEquals("2026-11-08", dateSpans[0].isoDate)
        assertEquals("2026-11-12", dateSpans[1].isoDate)
        assertEquals("@2026-11-08", dateSpans[0].toVisibleString())

        // 2. Rendering pills in MarkdownVisualTransformation
        val rendered = MarkdownRenderer.renderSpans(parsed)
        val datePills = rendered.pills.filter { it.kind == MarkdownPillKind.DATE }
        assertEquals(2, datePills.size)
        assertEquals("2026-11-08", datePills[0].target)
        assertEquals("2026-11-12", datePills[1].target)

        // 3. Atomic deletion: deleting right after @2026-11-08 deletes the entire span
        val initialSpans = listOf(
            InlineSpan.Text("Fecha: "),
            InlineSpan.Date("2026-11-08"),
        )
        val visible = initialSpans.toVisibleString() // "Fecha: @2026-11-08" (length 18)
        assertEquals("Fecha: @2026-11-08", visible)

        val (afterBackspace, newCaret) = RichSpanEngine.applyTextEdit(
            oldSpans = initialSpans,
            oldText = visible,
            newText = visible.dropLast(1), // Backspace at end
            newCaret = visible.length - 1,
        )
        assertEquals("Fecha: ", afterBackspace.toVisibleString())
        assertEquals("Fecha: ".length, newCaret)
    }

    @Test
    fun testFormalBlockTagRenderingAndCustomColors() {
        val spans = listOf(
            InlineSpan.Text("Nota sobre "),
            InlineSpan.Tag("arquitectura"),
            InlineSpan.Text(" y también #diseño formal"),
        )
        val customColors = mapOf("arquitectura" to "#2563EB", "diseño" to "#7C3AED")
        val rendered = MarkdownRenderer.renderSpans(spans, customColors)

        assertEquals("Nota sobre #arquitectura y también #diseño formal", rendered.text.text)
        // Only the picked tag is a pill; typed "#diseño" stays plain text (docs/product.md §4.5).
        val tagPills = rendered.pills.filter { it.kind == MarkdownPillKind.TAG }
        assertEquals(1, tagPills.size)

        assertEquals("arquitectura", tagPills[0].target)
        assertEquals("#arquitectura", tagPills[0].label)
        assertTrue(tagPills[0].borderColor != null)
        assertTrue(tagPills[0].accentColor != null)
    }
}
