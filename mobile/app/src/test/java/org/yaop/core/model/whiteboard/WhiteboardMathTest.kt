package org.yaop.core.model.whiteboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardMathTest {

    @Test
    fun screenToCanvas_and_canvasToScreen_roundtripsAccurately() {
        val viewport = CanvasViewport(panX = 150f, panY = -80f, zoom = 1.5f)
        val originalScreen = Offset(400f, 600f)

        val canvas = WhiteboardMath.screenToCanvas(originalScreen, viewport)
        val backToScreen = WhiteboardMath.canvasToScreen(canvas, viewport)

        assertEquals(originalScreen.x, backToScreen.x, 0.001f)
        assertEquals(originalScreen.y, backToScreen.y, 0.001f)
    }

    @Test
    fun visibleCanvasRect_boundsCalculatedCorrectly() {
        val viewport = CanvasViewport(panX = 0f, panY = 0f, zoom = 1.0f)
        val screenSize = Size(1000f, 2000f)
        val rect = WhiteboardMath.visibleCanvasRect(screenSize, viewport, bufferPx = 100f)

        assertEquals(-100f, rect.left, 0.001f)
        assertEquals(-100f, rect.top, 0.001f)
        assertEquals(1100f, rect.right, 0.001f)
        assertEquals(2100f, rect.bottom, 0.001f)
    }

    @Test
    fun isNodeVisible_correctlyFiltersOutsideNodes() {
        val visibleRect = Rect(0f, 0f, 500f, 500f)

        val insideNode = WhiteboardNode(
            id = "n1",
            x = 100f,
            y = 100f,
            content = WhiteboardNodeContent.Sticky("Inside"),
        )
        val outsideNode = WhiteboardNode(
            id = "n2",
            x = 800f,
            y = 800f,
            content = WhiteboardNodeContent.Sticky("Outside"),
        )

        assertTrue(WhiteboardMath.isNodeVisible(insideNode, visibleRect))
        assertFalse(WhiteboardMath.isNodeVisible(outsideNode, visibleRect))
    }

    @Test
    fun calculateNodeAnchor_computesExpectedBorderIntersections() {
        val nodeRect = Rect(left = 100f, top = 100f, right = 300f, bottom = 200f) // center = (200, 150)

        // Target to the right
        val targetRight = Offset(500f, 150f)
        val anchorRight = WhiteboardMath.calculateNodeAnchor(nodeRect, targetRight)
        assertEquals(300f, anchorRight.x, 0.01f)
        assertEquals(150f, anchorRight.y, 0.01f)

        // Target above
        val targetAbove = Offset(200f, -50f)
        val anchorTop = WhiteboardMath.calculateNodeAnchor(nodeRect, targetAbove)
        assertEquals(200f, anchorTop.x, 0.01f)
        assertEquals(100f, anchorTop.y, 0.01f)
    }

    @Test
    fun calculateZoomToFit_framesContentCentered() {
        val node1 = WhiteboardNode(id = "1", x = 0f, y = 0f, width = 200f, height = 100f, content = WhiteboardNodeContent.Sticky())
        val node2 = WhiteboardNode(id = "2", x = 600f, y = 400f, width = 200f, height = 100f, content = WhiteboardNodeContent.Sticky())
        val data = WhiteboardData(nodes = listOf(node1, node2))

        val screenSize = IntSize(1000, 1000)
        val fitted = WhiteboardMath.calculateZoomToFit(data, screenSize, paddingPx = 50f)

        assertTrue(fitted.zoom in CanvasViewport.MIN_ZOOM..CanvasViewport.MAX_ZOOM)
        // Center of content is (400, 250)
        // Check that content center translates to screen center (500, 500)
        val projectedCenter = WhiteboardMath.canvasToScreen(Offset(400f, 250f), fitted)
        assertEquals(500f, projectedCenter.x, 1.0f)
        assertEquals(500f, projectedCenter.y, 1.0f)
    }
}
