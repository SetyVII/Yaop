package org.yaop.ui.whiteboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.yaop.core.model.whiteboard.CardViewMode
import org.yaop.core.model.whiteboard.WhiteboardData
import org.yaop.core.model.whiteboard.WhiteboardNode
import org.yaop.core.model.whiteboard.WhiteboardNodeContent
import org.yaop.core.model.whiteboard.WhiteboardPalette
import org.yaop.core.model.whiteboard.WhiteboardSection
import org.yaop.core.model.whiteboard.WhiteboardStroke
import org.yaop.core.model.whiteboard.WhiteboardTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardStateTest {

    @Test
    fun panAndZoom_updatesViewportState() {
        var committed: WhiteboardData? = null
        val state = WhiteboardState(WhiteboardData()) { committed = it }

        state.panBy(50f, -20f)
        assertEquals(50f, state.viewport.panX, 0.001f)
        assertEquals(-20f, state.viewport.panY, 0.001f)

        state.zoomBy(1.5f, Offset(500f, 500f))
        assertEquals(1.5f, state.viewport.zoom, 0.001f)

        state.resetZoom()
        assertEquals(1.0f, state.viewport.zoom, 0.001f)
    }

    @Test
    fun addSticky_and_updateText_persistsCorrectly() {
        var latest: WhiteboardData? = null
        val state = WhiteboardState(WhiteboardData()) { latest = it }

        state.addSticky(text = "Hello Capacities", screenSize = IntSize(1000, 1000))
        assertEquals(1, state.data.nodes.size)
        val node = state.data.nodes.first()
        assertTrue(node.content is WhiteboardNodeContent.Sticky)
        assertEquals("Hello Capacities", (node.content as WhiteboardNodeContent.Sticky).text)

        state.updateStickyText(node.id, "Updated Heptabase note")
        val updatedNode = state.data.nodes.first()
        assertEquals("Updated Heptabase note", (updatedNode.content as WhiteboardNodeContent.Sticky).text)
    }

    @Test
    fun addObjectCard_and_toggleCardMode_works() {
        var latest: WhiteboardData? = null
        val state = WhiteboardState(WhiteboardData()) { latest = it }

        state.addObjectCard(objectId = "obj-123", screenSize = IntSize(1000, 1000))
        assertEquals(1, state.data.nodes.size)
        val card = state.data.nodes.first()
        assertTrue(card.content is WhiteboardNodeContent.ObjectCard)
        assertEquals(CardViewMode.DETAILED, (card.content as WhiteboardNodeContent.ObjectCard).viewMode)

        state.toggleCardViewMode(card.id)
        val toggled = state.data.nodes.first()
        assertEquals(CardViewMode.COMPACT, (toggled.content as WhiteboardNodeContent.ObjectCard).viewMode)
    }

    @Test
    fun moveSection_movesEnclosedNodesTogether() {
        val nodeInside = WhiteboardNode(
            id = "node-1",
            x = 100f,
            y = 100f,
            width = 100f,
            height = 100f,
            content = WhiteboardNodeContent.Sticky("Inside Section"),
        )
        val nodeOutside = WhiteboardNode(
            id = "node-2",
            x = 800f,
            y = 800f,
            width = 100f,
            height = 100f,
            content = WhiteboardNodeContent.Sticky("Outside Section"),
        )
        val section = WhiteboardSection(
            id = "sec-1",
            title = "Sprint 1",
            x = 50f,
            y = 50f,
            width = 300f,
            height = 300f,
        )

        val initialData = WhiteboardData(
            nodes = listOf(nodeInside, nodeOutside),
            sections = listOf(section),
        )

        val state = WhiteboardState(initialData) {}

        // Move section by (40, 60)
        state.moveSection("sec-1", 40f, 60f)

        val movedInsideNode = state.data.nodes.find { it.id == "node-1" }!!
        val unmovedOutsideNode = state.data.nodes.find { it.id == "node-2" }!!
        val movedSection = state.data.sections.find { it.id == "sec-1" }!!

        assertEquals(140f, movedInsideNode.x, 0.001f)
        assertEquals(160f, movedInsideNode.y, 0.001f)
        assertEquals(800f, unmovedOutsideNode.x, 0.001f)
        assertEquals(800f, unmovedOutsideNode.y, 0.001f)
        assertEquals(90f, movedSection.x, 0.001f)
        assertEquals(110f, movedSection.y, 0.001f)
    }

    @Test
    fun connections_create_and_preventDuplicates() {
        val node1 = WhiteboardNode(id = "n1", x = 0f, y = 0f, content = WhiteboardNodeContent.Sticky())
        val node2 = WhiteboardNode(id = "n2", x = 400f, y = 400f, content = WhiteboardNodeContent.Sticky())
        val state = WhiteboardState(WhiteboardData(nodes = listOf(node1, node2))) {}

        state.startConnection("n1")
        assertEquals("n1", state.connectingFromNodeId)

        state.completeConnection("n2", label = "references")
        assertEquals(1, state.data.connectors.size)
        assertEquals("n1", state.data.connectors.first().fromNodeId)
        assertEquals("n2", state.data.connectors.first().toNodeId)
        assertEquals("references", state.data.connectors.first().label)

        // Attempting to duplicate the connection does not create a redundant edge
        state.startConnection("n1")
        state.completeConnection("n2")
        assertEquals(1, state.data.connectors.size)

        // Cannot connect to self
        state.startConnection("n1")
        state.completeConnection("n1")
        assertEquals(1, state.data.connectors.size)
    }

    @Test
    fun undoAndRedo_restoresStateHistory() {
        val state = WhiteboardState(WhiteboardData()) {}
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)

        state.addSticky("Card 1")
        assertEquals(1, state.data.nodes.size)
        assertTrue(state.canUndo)

        state.addSticky("Card 2")
        assertEquals(2, state.data.nodes.size)

        state.undo()
        assertEquals(1, state.data.nodes.size)
        assertTrue(state.canRedo)

        state.redo()
        assertEquals(2, state.data.nodes.size)
    }
}
