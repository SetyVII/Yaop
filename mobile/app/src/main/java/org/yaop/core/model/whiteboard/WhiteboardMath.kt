package org.yaop.core.model.whiteboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 2D mathematical utilities for infinite canvas projections, smooth curves,
 * dynamic connector anchors, and viewport culling.
 */
object WhiteboardMath {

    /**
     * Converts screen pixel coordinates to infinite canvas coordinate space.
     */
    fun screenToCanvas(screenPoint: Offset, viewport: CanvasViewport): Offset {
        return Offset(
            x = (screenPoint.x - viewport.panX) / viewport.zoom,
            y = (screenPoint.y - viewport.panY) / viewport.zoom,
        )
    }

    /**
     * Converts canvas coordinates to screen pixel coordinates.
     */
    fun canvasToScreen(canvasPoint: Offset, viewport: CanvasViewport): Offset {
        return Offset(
            x = canvasPoint.x * viewport.zoom + viewport.panX,
            y = canvasPoint.y * viewport.zoom + viewport.panY,
        )
    }

    /**
     * Calculates the visible bounding box in canvas coordinates given screen dimensions.
     */
    fun visibleCanvasRect(screenSize: Size, viewport: CanvasViewport, bufferPx: Float = 120f): Rect {
        val left = (-viewport.panX - bufferPx) / viewport.zoom
        val top = (-viewport.panY - bufferPx) / viewport.zoom
        val right = (-viewport.panX + screenSize.width + bufferPx) / viewport.zoom
        val bottom = (-viewport.panY + screenSize.height + bufferPx) / viewport.zoom
        return Rect(left = left, top = top, right = right, bottom = bottom)
    }

    /**
     * Checks if a [WhiteboardNode] intersects the visible canvas rectangle.
     */
    fun isNodeVisible(node: WhiteboardNode, visibleRect: Rect): Boolean {
        val nodeRect = Rect(
            left = node.x,
            top = node.y,
            right = node.x + node.width,
            bottom = node.y + node.height,
        )
        return visibleRect.overlaps(nodeRect)
    }

    /**
     * Checks if a [WhiteboardSection] intersects the visible canvas rectangle.
     */
    fun isSectionVisible(section: WhiteboardSection, visibleRect: Rect): Boolean {
        val sectionRect = Rect(
            left = section.x,
            top = section.y,
            right = section.x + section.width,
            bottom = section.y + section.height,
        )
        return visibleRect.overlaps(sectionRect)
    }

    /**
     * Calculates the bounding rect of a node.
     */
    fun nodeBounds(node: WhiteboardNode): Rect =
        Rect(node.x, node.y, node.x + node.width, node.y + node.height)

    /**
     * Calculates the bounding rect of a section.
     */
    fun sectionBounds(section: WhiteboardSection): Rect =
        Rect(section.x, section.y, section.x + section.width, section.y + section.height)

    /**
     * Computes the optimal anchor point on the border of a node rect facing a target point.
     */
    fun calculateNodeAnchor(nodeRect: Rect, targetPoint: Offset): Offset {
        val center = nodeRect.center
        val dx = targetPoint.x - center.x
        val dy = targetPoint.y - center.y

        if (abs(dx) < 0.001f && abs(dy) < 0.001f) {
            return Offset(center.x, nodeRect.top)
        }

        val halfW = nodeRect.width / 2f
        val halfH = nodeRect.height / 2f

        // Intersect ray with rectangle
        val scaleX = if (dx != 0f) halfW / abs(dx) else Float.MAX_VALUE
        val scaleY = if (dy != 0f) halfH / abs(dy) else Float.MAX_VALUE
        val scale = min(scaleX, scaleY)

        return Offset(
            x = center.x + dx * scale,
            y = center.y + dy * scale,
        )
    }

    /**
     * Computes a smooth Bézier path connecting two points with cubic curvature.
     */
    fun buildConnectorPath(start: Offset, end: Offset): Path {
        val path = Path()
        path.moveTo(start.x, start.y)

        val dx = end.x - start.x
        val dy = end.y - start.y
        val distance = sqrt(dx * dx + dy * dy)
        val curvature = min(distance * 0.4f, 150f)

        // Determine primary orientation (horizontal vs vertical)
        val control1: Offset
        val control2: Offset
        if (abs(dx) >= abs(dy)) {
            val sign = if (dx >= 0) 1f else -1f
            control1 = Offset(start.x + sign * curvature, start.y)
            control2 = Offset(end.x - sign * curvature, end.y)
        } else {
            val sign = if (dy >= 0) 1f else -1f
            control1 = Offset(start.x, start.y + sign * curvature)
            control2 = Offset(end.x, end.y - sign * curvature)
        }

        path.cubicTo(
            control1.x, control1.y,
            control2.x, control2.y,
            end.x, end.y,
        )
        return path
    }

    /**
     * Computes points for drawing a triangular arrowhead at [endPoint] pointing from [controlPoint].
     */
    fun calculateArrowHeadPoints(endPoint: Offset, controlPoint: Offset, arrowSize: Float = 14f): Triple<Offset, Offset, Offset> {
        val dx = endPoint.x - controlPoint.x
        val dy = endPoint.y - controlPoint.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.001f) {
            return Triple(endPoint, endPoint, endPoint)
        }
        val unitX = dx / length
        val unitY = dy / length

        val perpX = -unitY
        val perpY = unitX

        val baseCenter = Offset(
            x = endPoint.x - unitX * arrowSize,
            y = endPoint.y - unitY * arrowSize,
        )

        val wing1 = Offset(
            x = baseCenter.x + perpX * (arrowSize * 0.5f),
            y = baseCenter.y + perpY * (arrowSize * 0.5f),
        )

        val wing2 = Offset(
            x = baseCenter.x - perpX * (arrowSize * 0.5f),
            y = baseCenter.y - perpY * (arrowSize * 0.5f),
        )

        return Triple(endPoint, wing1, wing2)
    }

    /**
     * Converts a list of stroke points into a smooth quadratic Bézier Compose [Path].
     */
    fun buildSmoothStrokePath(points: List<StrokePoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path

        if (points.size == 1) {
            val p = points[0]
            path.moveTo(p.x, p.y)
            path.lineTo(p.x + 0.5f, p.y + 0.5f)
            return path
        }

        path.moveTo(points[0].x, points[0].y)

        for (i in 1 until points.size) {
            val p0 = points[i - 1]
            val p1 = points[i]
            val midX = (p0.x + p1.x) / 2f
            val midY = (p0.y + p1.y) / 2f
            path.quadraticTo(p0.x, p0.y, midX, midY)
        }

        val last = points.last()
        path.lineTo(last.x, last.y)
        return path
    }

    /**
     * Calculates the bounding box that encloses all elements on the whiteboard,
     * and computes the optimal [CanvasViewport] to frame all content centered with padding.
     */
    fun calculateZoomToFit(
        data: WhiteboardData,
        screenSize: IntSize,
        paddingPx: Float = 80f,
    ): CanvasViewport {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        fun expand(x1: Float, y1: Float, x2: Float, y2: Float) {
            minX = min(minX, x1)
            minY = min(minY, y1)
            maxX = max(maxX, x2)
            maxY = max(maxY, y2)
        }

        data.nodes.forEach { node ->
            expand(node.x, node.y, node.x + node.width, node.y + node.height)
        }
        data.sections.forEach { section ->
            expand(section.x, section.y, section.x + section.width, section.y + section.height)
        }
        data.strokes.forEach { stroke ->
            stroke.points.forEach { pt ->
                expand(pt.x, pt.y, pt.x, pt.y)
            }
        }

        if (minX == Float.MAX_VALUE || maxX == -Float.MAX_VALUE) {
            // No content, reset to origin
            return CanvasViewport(panX = 0f, panY = 0f, zoom = 1.0f)
        }

        val contentWidth = max(maxX - minX, 100f)
        val contentHeight = max(maxY - minY, 100f)

        val availableW = max(screenSize.width - paddingPx * 2, 100f)
        val availableH = max(screenSize.height - paddingPx * 2, 100f)

        val zoomX = availableW / contentWidth
        val zoomY = availableH / contentHeight
        val targetZoom = min(zoomX, zoomY).coerceIn(CanvasViewport.MIN_ZOOM, CanvasViewport.MAX_ZOOM)

        val contentCenterX = (minX + maxX) / 2f
        val contentCenterY = (minY + maxY) / 2f

        val targetPanX = (screenSize.width / 2f) - (contentCenterX * targetZoom)
        val targetPanY = (screenSize.height / 2f) - (contentCenterY * targetZoom)

        return CanvasViewport(
            panX = targetPanX,
            panY = targetPanY,
            zoom = targetZoom,
        )
    }
}
