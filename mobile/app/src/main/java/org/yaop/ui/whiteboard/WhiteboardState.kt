package org.yaop.ui.whiteboard

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.yaop.core.model.whiteboard.CanvasViewport
import org.yaop.core.model.whiteboard.CardViewMode
import org.yaop.core.model.whiteboard.ConnectorStyle
import org.yaop.core.model.whiteboard.StrokePoint
import org.yaop.core.model.whiteboard.WhiteboardConnector
import org.yaop.core.model.whiteboard.WhiteboardData
import org.yaop.core.model.whiteboard.WhiteboardMath
import org.yaop.core.model.whiteboard.WhiteboardNode
import org.yaop.core.model.whiteboard.WhiteboardNodeContent
import org.yaop.core.model.whiteboard.WhiteboardPalette
import org.yaop.core.model.whiteboard.WhiteboardSection
import org.yaop.core.model.whiteboard.WhiteboardStroke
import org.yaop.core.model.whiteboard.WhiteboardTool
import java.util.UUID
import kotlin.math.max

/**
 * State holder managing infinite canvas interactions, tools, undo/redo,
 * dynamic connector routing, section grouping, and freehand vector ink.
 */
@Stable
class WhiteboardState(
    initialData: WhiteboardData,
    private val onCommit: (WhiteboardData) -> Unit,
) {
    var data by mutableStateOf(initialData)
        private set

    var viewport by mutableStateOf(initialData.viewport)
        private set

    var activeTool by mutableStateOf(WhiteboardTool.SELECT)

    var selectedNodeId by mutableStateOf<String?>(null)
    var selectedSectionId by mutableStateOf<String?>(null)
    var selectedConnectorId by mutableStateOf<String?>(null)

    // Connector linking in-progress
    var connectingFromNodeId by mutableStateOf<String?>(null)
    var connectionDragTarget by mutableStateOf<Offset?>(null)

    // Heptabase-style inspection
    var inspectingObjectId by mutableStateOf<String?>(null)

    // Fullscreen canvas mode (collapses top bars for studio-style creation)
    var isFullScreen by mutableStateOf(false)

    // Tablet & Touch mode: when true, finger draws as a brush; when false, stylus draws and finger pans with palm rejection
    var isFingerBrushMode by mutableStateOf(true)

    // Drawing & Styling preferences
    var activeInkColor by mutableStateOf(WhiteboardPalette.INK_WHITE)
    var activeStrokeWidth by mutableStateOf(3.5f)
    var activeStickyColor by mutableStateOf(WhiteboardPalette.STICKY_YELLOW)

    // Vector stroke being drawn in real-time
    val activeStrokePoints = mutableStateListOf<StrokePoint>()
    var isDrawingHighlighter by mutableStateOf(false)

    // Undo / Redo histories
    private val undoStack = mutableListOf<WhiteboardData>()
    private val redoStack = mutableListOf<WhiteboardData>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun pushUndoSnapshot() {
        undoStack.add(data.copy(viewport = viewport))
        if (undoStack.size > 50) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    private fun commitData(newData: WhiteboardData) {
        data = newData
        onCommit(newData.copy(viewport = viewport))
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val previous = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(data.copy(viewport = viewport))
        data = previous
        viewport = previous.viewport
        selectedNodeId = null
        selectedSectionId = null
        selectedConnectorId = null
        onCommit(previous)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(data.copy(viewport = viewport))
        data = next
        viewport = next.viewport
        onCommit(next)
    }

    // Viewport navigation ----------------------------------------------------

    fun panBy(dx: Float, dy: Float) {
        val newViewport = viewport.copy(
            panX = viewport.panX + dx,
            panY = viewport.panY + dy,
        )
        viewport = newViewport
    }

    fun zoomBy(factor: Float, centroid: Offset) {
        val oldZoom = viewport.zoom
        val newZoom = (oldZoom * factor).coerceIn(CanvasViewport.MIN_ZOOM, CanvasViewport.MAX_ZOOM)
        if (oldZoom == newZoom) return

        // Zoom centered around touch centroid:
        // canvasX = (centroid.x - panX) / oldZoom
        // newPanX = centroid.x - canvasX * newZoom
        val canvasX = (centroid.x - viewport.panX) / oldZoom
        val canvasY = (centroid.y - viewport.panY) / oldZoom

        val newPanX = centroid.x - (canvasX * newZoom)
        val newPanY = centroid.y - (canvasY * newZoom)

        viewport = CanvasViewport(
            panX = newPanX,
            panY = newPanY,
            zoom = newZoom,
        )
    }

    fun resetZoom() {
        viewport = viewport.copy(zoom = 1.0f)
    }

    fun zoomToFit(screenSize: IntSize) {
        if (screenSize.width <= 0 || screenSize.height <= 0) return
        val fitted = WhiteboardMath.calculateZoomToFit(data, screenSize)
        viewport = fitted
    }

    // Node operations --------------------------------------------------------

    fun selectNode(nodeId: String?) {
        selectedNodeId = nodeId
        selectedSectionId = null
        selectedConnectorId = null
        if (nodeId == null && activeTool == WhiteboardTool.CONNECTOR) {
            connectingFromNodeId = null
            connectionDragTarget = null
        }
    }

    fun moveNode(nodeId: String, deltaCanvasX: Float, deltaCanvasY: Float) {
        val updatedNodes = data.nodes.map { node ->
            if (node.id == nodeId) {
                node.copy(
                    x = node.x + deltaCanvasX,
                    y = node.y + deltaCanvasY,
                )
            } else {
                node
            }
        }
        data = data.copy(nodes = updatedNodes)
    }

    fun finishNodeMove() {
        pushUndoSnapshot()
        commitData(data)
    }

    fun addObjectCard(objectId: String, atScreenPos: Offset? = null, screenSize: IntSize? = null) {
        pushUndoSnapshot()
        val canvasPos = if (atScreenPos != null) {
            WhiteboardMath.screenToCanvas(atScreenPos, viewport)
        } else if (screenSize != null) {
            WhiteboardMath.screenToCanvas(Offset(screenSize.width / 2f, screenSize.height / 2f), viewport)
        } else {
            Offset(-viewport.panX / viewport.zoom + 100f, -viewport.panY / viewport.zoom + 100f)
        }

        val newNode = WhiteboardNode(
            id = UUID.randomUUID().toString(),
            x = canvasPos.x - (WhiteboardNode.DEFAULT_WIDTH / 2f),
            y = canvasPos.y - (WhiteboardNode.DEFAULT_HEIGHT / 2f),
            width = WhiteboardNode.DEFAULT_WIDTH,
            height = WhiteboardNode.DEFAULT_HEIGHT,
            content = WhiteboardNodeContent.ObjectCard(objectId = objectId),
        )

        val updated = data.copy(nodes = data.nodes + newNode)
        commitData(updated)
        selectedNodeId = newNode.id
    }

    fun addSticky(
        text: String = "",
        colorHex: String = activeStickyColor,
        atScreenPos: Offset? = null,
        screenSize: IntSize? = null,
    ) {
        pushUndoSnapshot()
        val canvasPos = if (atScreenPos != null) {
            WhiteboardMath.screenToCanvas(atScreenPos, viewport)
        } else if (screenSize != null) {
            WhiteboardMath.screenToCanvas(Offset(screenSize.width / 2f, screenSize.height / 2f), viewport)
        } else {
            Offset(-viewport.panX / viewport.zoom + 120f, -viewport.panY / viewport.zoom + 120f)
        }

        val size = WhiteboardNode.STICKY_DEFAULT_SIZE
        val newNode = WhiteboardNode(
            id = UUID.randomUUID().toString(),
            x = canvasPos.x - (size / 2f),
            y = canvasPos.y - (size / 2f),
            width = size,
            height = size,
            content = WhiteboardNodeContent.Sticky(text = text, colorHex = colorHex),
        )

        val updated = data.copy(nodes = data.nodes + newNode)
        commitData(updated)
        selectedNodeId = newNode.id
    }

    fun updateStickyText(nodeId: String, newText: String) {
        val updatedNodes = data.nodes.map { node ->
            if (node.id == nodeId && node.content is WhiteboardNodeContent.Sticky) {
                node.copy(content = node.content.copy(text = newText))
            } else {
                node
            }
        }
        commitData(data.copy(nodes = updatedNodes))
    }

    fun convertStickyToObject(nodeId: String, newObjectId: String) {
        pushUndoSnapshot()
        val updatedNodes = data.nodes.map { node ->
            if (node.id == nodeId && node.content is WhiteboardNodeContent.Sticky) {
                node.copy(
                    content = WhiteboardNodeContent.ObjectCard(objectId = newObjectId),
                    width = WhiteboardNode.DEFAULT_WIDTH,
                    height = WhiteboardNode.DEFAULT_HEIGHT,
                )
            } else {
                node
            }
        }
        commitData(data.copy(nodes = updatedNodes))
        selectedNodeId = nodeId
    }

    fun updateNodeColor(nodeId: String, colorHex: String) {
        pushUndoSnapshot()
        val updatedNodes = data.nodes.map { node ->
            if (node.id == nodeId && node.content is WhiteboardNodeContent.Sticky) {
                node.copy(content = node.content.copy(colorHex = colorHex))
            } else {
                node
            }
        }
        commitData(data.copy(nodes = updatedNodes))
    }

    fun toggleCardViewMode(nodeId: String) {
        pushUndoSnapshot()
        val updatedNodes = data.nodes.map { node ->
            if (node.id == nodeId && node.content is WhiteboardNodeContent.ObjectCard) {
                val nextMode = if (node.content.viewMode == CardViewMode.DETAILED) {
                    CardViewMode.COMPACT
                } else {
                    CardViewMode.DETAILED
                }
                node.copy(
                    height = if (nextMode == CardViewMode.COMPACT) 68f else WhiteboardNode.DEFAULT_HEIGHT,
                    content = node.content.copy(viewMode = nextMode),
                )
            } else {
                node
            }
        }
        commitData(data.copy(nodes = updatedNodes))
    }

    fun deleteNode(nodeId: String) {
        pushUndoSnapshot()
        val updatedNodes = data.nodes.filterNot { it.id == nodeId }
        val updatedConnectors = data.connectors.filterNot { it.fromNodeId == nodeId || it.toNodeId == nodeId }
        if (selectedNodeId == nodeId) selectedNodeId = null
        commitData(data.copy(nodes = updatedNodes, connectors = updatedConnectors))
    }

    // Connectors -------------------------------------------------------------

    fun startConnection(fromNodeId: String) {
        connectingFromNodeId = fromNodeId
        selectedNodeId = fromNodeId
        activeTool = WhiteboardTool.CONNECTOR
    }

    fun updateConnectionDrag(targetScreenPoint: Offset) {
        connectionDragTarget = WhiteboardMath.screenToCanvas(targetScreenPoint, viewport)
    }

    fun completeConnection(toNodeId: String, label: String = "") {
        val sourceId = connectingFromNodeId ?: return
        if (sourceId == toNodeId) {
            cancelConnection()
            return
        }
        // Avoid duplicate links in the same direction
        val exists = data.connectors.any { it.fromNodeId == sourceId && it.toNodeId == toNodeId }
        if (!exists) {
            pushUndoSnapshot()
            val newConnector = WhiteboardConnector(
                id = UUID.randomUUID().toString(),
                fromNodeId = sourceId,
                toNodeId = toNodeId,
                label = label,
                colorHex = WhiteboardPalette.INK_COBALT,
            )
            commitData(data.copy(connectors = data.connectors + newConnector))
        }
        cancelConnection()
    }

    fun cancelConnection() {
        connectingFromNodeId = null
        connectionDragTarget = null
    }

    fun deleteConnector(connectorId: String) {
        pushUndoSnapshot()
        val updated = data.connectors.filterNot { it.id == connectorId }
        if (selectedConnectorId == connectorId) selectedConnectorId = null
        commitData(data.copy(connectors = updated))
    }

    fun updateConnectorLabel(connectorId: String, newLabel: String) {
        pushUndoSnapshot()
        val updated = data.connectors.map { conn ->
            if (conn.id == connectorId) conn.copy(label = newLabel) else conn
        }
        commitData(data.copy(connectors = updated))
    }

    // Sections / Frames ------------------------------------------------------

    fun addSection(
        title: String,
        atScreenPos: Offset? = null,
        screenSize: IntSize? = null,
        colorHex: String = WhiteboardPalette.SECTION_SLATE,
    ) {
        pushUndoSnapshot()
        val canvasPos = if (atScreenPos != null) {
            WhiteboardMath.screenToCanvas(atScreenPos, viewport)
        } else if (screenSize != null) {
            WhiteboardMath.screenToCanvas(Offset(screenSize.width / 2f, screenSize.height / 2f), viewport)
        } else {
            Offset(-viewport.panX / viewport.zoom + 80f, -viewport.panY / viewport.zoom + 80f)
        }

        val newSection = WhiteboardSection(
            id = UUID.randomUUID().toString(),
            title = title,
            x = canvasPos.x - 200f,
            y = canvasPos.y - 150f,
            width = 440f,
            height = 340f,
            colorHex = colorHex,
        )

        commitData(data.copy(sections = data.sections + newSection))
        selectedSectionId = newSection.id
    }

    fun moveSection(sectionId: String, deltaCanvasX: Float, deltaCanvasY: Float) {
        val targetSection = data.sections.find { it.id == sectionId } ?: return
        val sectionBounds = WhiteboardMath.sectionBounds(targetSection)

        // Move enclosed nodes together with the section frame!
        val updatedNodes = data.nodes.map { node ->
            val nodeRect = WhiteboardMath.nodeBounds(node)
            if (sectionBounds.contains(nodeRect.center)) {
                node.copy(
                    x = node.x + deltaCanvasX,
                    y = node.y + deltaCanvasY,
                )
            } else {
                node
            }
        }

        val updatedSections = data.sections.map { section ->
            if (section.id == sectionId) {
                section.copy(
                    x = section.x + deltaCanvasX,
                    y = section.y + deltaCanvasY,
                )
            } else {
                section
            }
        }

        data = data.copy(nodes = updatedNodes, sections = updatedSections)
    }

    fun finishSectionMove() {
        pushUndoSnapshot()
        commitData(data)
    }

    fun deleteSection(sectionId: String) {
        pushUndoSnapshot()
        val updatedSections = data.sections.filterNot { it.id == sectionId }
        if (selectedSectionId == sectionId) selectedSectionId = null
        commitData(data.copy(sections = updatedSections))
    }

    // Freehand Drawing / Stylus Ink ------------------------------------------

    fun startStroke(screenPoint: Offset, isHighlighter: Boolean = false, pressure: Float = 1.0f) {
        val canvasPoint = WhiteboardMath.screenToCanvas(screenPoint, viewport)
        activeStrokePoints.clear()
        activeStrokePoints.add(StrokePoint(canvasPoint.x, canvasPoint.y, pressure))
        isDrawingHighlighter = isHighlighter
    }

    fun addStrokePoint(screenPoint: Offset, pressure: Float = 1.0f) {
        val canvasPoint = WhiteboardMath.screenToCanvas(screenPoint, viewport)
        activeStrokePoints.add(StrokePoint(canvasPoint.x, canvasPoint.y, pressure))
    }

    fun endStroke() {
        if (activeStrokePoints.size >= 2) {
            pushUndoSnapshot()
            val newStroke = WhiteboardStroke(
                id = UUID.randomUUID().toString(),
                points = activeStrokePoints.toList(),
                colorHex = if (isDrawingHighlighter) WhiteboardPalette.HIGHLIGHTER_YELLOW else activeInkColor,
                strokeWidth = if (isDrawingHighlighter) max(activeStrokeWidth * 3.5f, 18f) else activeStrokeWidth,
                isHighlighter = isDrawingHighlighter,
            )
            commitData(data.copy(strokes = data.strokes + newStroke))
        }
        activeStrokePoints.clear()
    }

    fun deleteStroke(strokeId: String) {
        pushUndoSnapshot()
        val updatedStrokes = data.strokes.filterNot { it.id == strokeId }
        commitData(data.copy(strokes = updatedStrokes))
    }

    // Eraser hit-testing -----------------------------------------------------

    fun eraseAtScreenPoint(screenPoint: Offset, eraseRadiusPx: Float = 24f) {
        val canvasPoint = WhiteboardMath.screenToCanvas(screenPoint, viewport)
        val canvasRadius = eraseRadiusPx / viewport.zoom

        // Check strokes
        val hitStroke = data.strokes.firstOrNull { stroke ->
            stroke.points.any { pt ->
                val dx = pt.x - canvasPoint.x
                val dy = pt.y - canvasPoint.y
                (dx * dx + dy * dy) <= (canvasRadius * canvasRadius)
            }
        }
        if (hitStroke != null) {
            deleteStroke(hitStroke.id)
            return
        }

        // Check connectors
        val hitConnector = data.connectors.firstOrNull { conn ->
            val from = data.nodes.find { it.id == conn.fromNodeId }
            val to = data.nodes.find { it.id == conn.toNodeId }
            if (from != null && to != null) {
                val midX = (from.x + to.x) / 2f
                val midY = (from.y + to.y) / 2f
                val dx = midX - canvasPoint.x
                val dy = midY - canvasPoint.y
                (dx * dx + dy * dy) <= (canvasRadius * canvasRadius * 4f)
            } else false
        }
        if (hitConnector != null) {
            deleteConnector(hitConnector.id)
        }
    }
}
