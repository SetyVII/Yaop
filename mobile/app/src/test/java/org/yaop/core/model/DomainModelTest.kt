package org.yaop.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainModelTest {

    @Test
    fun taskStatus_fromId_normalizesCorrectly() {
        assertEquals(TaskStatus.DONE, TaskStatus.fromId("done"))
        assertEquals(TaskStatus.DONE, TaskStatus.fromId("DONE"))
        assertEquals(TaskStatus.DONE, TaskStatus.fromId("opt_done"))
        assertEquals(TaskStatus.DONE, TaskStatus.fromId("hecho"))
        assertEquals(TaskStatus.DONE, TaskStatus.fromId("completada"))
        assertEquals(TaskStatus.DONE, TaskStatus.fromId("completed"))

        assertEquals(TaskStatus.IN_PROGRESS, TaskStatus.fromId("in_progress"))
        assertEquals(TaskStatus.IN_PROGRESS, TaskStatus.fromId("doing"))
        assertEquals(TaskStatus.IN_PROGRESS, TaskStatus.fromId("en_progreso"))

        assertEquals(TaskStatus.TODO, TaskStatus.fromId("todo"))
        assertEquals(TaskStatus.TODO, TaskStatus.fromId("opt_todo"))
        assertEquals(TaskStatus.TODO, TaskStatus.fromId(null))
        assertEquals(TaskStatus.TODO, TaskStatus.fromId(""))
        assertEquals(TaskStatus.TODO, TaskStatus.fromId("unknown_custom"))
    }

    @Test
    fun taskStatus_toggle_switchesCorrectly() {
        assertEquals(TaskStatus.DONE, TaskStatus.TODO.toggled())
        assertEquals(TaskStatus.TODO, TaskStatus.DONE.toggled())
        assertEquals(TaskStatus.DONE, TaskStatus.IN_PROGRESS.toggled())
    }

    @Test
    fun taskPriority_fromId_normalizesCorrectly() {
        assertEquals(TaskPriority.LOW, TaskPriority.fromId("low"))
        assertEquals(TaskPriority.LOW, TaskPriority.fromId("baja"))
        assertEquals(TaskPriority.MEDIUM, TaskPriority.fromId("medium"))
        assertEquals(TaskPriority.MEDIUM, TaskPriority.fromId("media"))
        assertEquals(TaskPriority.HIGH, TaskPriority.fromId("high"))
        assertEquals(TaskPriority.HIGH, TaskPriority.fromId("alta"))
        assertNull(TaskPriority.fromId(null))
        assertNull(TaskPriority.fromId(""))
        assertNull(TaskPriority.fromId("unknown"))
    }

    @Test
    fun syncStatus_fromId_normalizesCorrectly() {
        assertEquals(SyncStatus.LOCAL, SyncStatus.fromId("local"))
        assertEquals(SyncStatus.SYNCED, SyncStatus.fromId("synced"))
        assertEquals(SyncStatus.SYNCING, SyncStatus.fromId("syncing"))
        assertEquals(SyncStatus.ERROR, SyncStatus.fromId("error"))
        assertEquals(SyncStatus.LOCAL, SyncStatus.fromId("other"))
    }

    @Test
    fun yaopObject_taskStatusExtensions_workAsExpected() {
        val initialTask = PkmObject(
            id = "task-1",
            typeId = CanonicalTypeIds.TASK,
            properties = mapOf(
                PropertyKeys.STATUS to PropertyValue.Select(TaskStatus.TODO.id),
            ),
        )

        assertEquals(TaskStatus.TODO, initialTask.taskStatusEnum)
        assertFalse(initialTask.isTaskDone)

        val toggled = initialTask.toggleTaskStatus()
        assertEquals(TaskStatus.DONE, toggled.taskStatusEnum)
        assertTrue(toggled.isTaskDone)

        val backToTodo = toggled.toggleTaskStatus()
        assertEquals(TaskStatus.TODO, backToTodo.taskStatusEnum)
        assertFalse(backToTodo.isTaskDone)

        val inProgress = initialTask.withTaskStatus(TaskStatus.IN_PROGRESS)
        assertEquals(TaskStatus.IN_PROGRESS, inProgress.taskStatusEnum)
        assertFalse(inProgress.isTaskDone)
    }

    @Test
    fun pkmObject_withProperty_updates() {
        val obj = PkmObject(
            id = "note-1",
            typeId = CanonicalTypeIds.NOTE,
        )

        val updated = obj.withProperty(PropertyKeys.AUTHOR, PropertyValue.RichText("Alice".toTextSpan()))
        val retrieved = updated.property(PropertyKeys.AUTHOR)
        assertTrue(retrieved is PropertyValue.RichText)
    }

    @Test
    fun block_polymorphicExtensions_workCorrectly() {
        val paragraph: Block = Block.Paragraph(id = "p1", spans = "Hello".toTextSpan())
        val withIndent = paragraph.withIndent(2)
        assertEquals(2, withIndent.indentLevel)
        assertEquals("p1", withIndent.id)

        val withGroup = withIndent.withGroup("grp-1")
        assertEquals("grp-1", withGroup.groupId)

        val withColor = withGroup.withColor("#FF0000")
        assertEquals("#FF0000", withColor.blockColor)

        val withNewId = withColor.withNewId("p2")
        assertEquals("p2", withNewId.id)

        assertTrue(paragraph is SpannedBlock)
        val spanned = paragraph as SpannedBlock
        val withNewSpans = spanned.withSpans("World".toTextSpan())
        assertEquals("World", withNewSpans.spans.toPlainText())
    }

    @Test
    fun sequencedListBlock_polymorphicNumbering_worksCorrectly() {
        val numbered: Block = Block.NumberedList(id = "num-1", number = 1, spans = "First".toTextSpan())
        assertTrue(numbered is SequencedListBlock)
        val sequenced = numbered as SequencedListBlock
        assertEquals(BlockTypeKeys.NUMBERED, sequenced.listKey)
        val updated = sequenced.withNumber(5)
        assertEquals(5, updated.number)
    }

    @Test
    fun block_serialization_roundTripPreservesTypes() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val blocks: List<Block> = listOf(
            Block.Paragraph(id = "b1", spans = "Para".toTextSpan(), indentLevel = 1, groupId = "g1", blockColor = "red"),
            Block.Heading(id = "b2", level = HeadingLevel.H2, spans = "Head".toTextSpan()),
            Block.NumberedList(id = "b3", number = 3, spans = "Item".toTextSpan()),
            Block.Code(id = "b4", code = "val x = 1", language = "kotlin"),
            Block.Bookmark(id = "b5", url = "https://example.com", title = "Example"),
        )

        val encoded = json.encodeToString(blocks)
        val decoded = json.decodeFromString<List<Block>>(encoded)

        assertEquals(blocks.size, decoded.size)
        assertEquals(blocks[0], decoded[0])
        assertEquals(blocks[1], decoded[1])
        assertEquals(blocks[2], decoded[2])
        assertEquals(blocks[3], decoded[3])
        assertEquals(blocks[4], decoded[4])
    }
}
