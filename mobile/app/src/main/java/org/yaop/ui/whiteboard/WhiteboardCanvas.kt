package org.yaop.ui.whiteboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.toColorInt
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectType
import org.yaop.core.model.whiteboard.CanvasViewport
import org.yaop.core.model.whiteboard.ConnectorStyle
import org.yaop.core.model.whiteboard.WhiteboardConnector
import org.yaop.core.model.whiteboard.WhiteboardMath
import org.yaop.core.model.whiteboard.WhiteboardNode
import org.yaop.core.model.whiteboard.WhiteboardStroke
import org.yaop.core.model.whiteboard.WhiteboardTool
import org.yaop.ui.theme.TypePalette
import kotlin.math.floor
import kotlin.math.max

/**
 * High-performance 2D Infinite Canvas for visual note-taking.
 * Renders the dot grid, connectors, freehand drawings, frames, and cards with viewport culling.
 */
@Composable
fun WhiteboardCanvas(
    state: WhiteboardState,
    allObjects: List<PkmObject>,
    allTypes: List<ObjectType>,
    onNavigateToObject: (String) -> Unit,
    onToggleTask: (PkmObject) -> Unit,
    onConvertToNote: ((WhiteboardNode, String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var screenSize by remember { mutableStateOf(IntSize(1080, 1920)) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { screenSize = it }
            // Multi-touch gestures: Pinch-to-zoom & two-finger pan
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (pan != Offset.Zero || zoom != 1.0f) {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    }
                    if (zoom != 1.0f) {
                        state.zoomBy(zoom, centroid)
                    }
                    if (pan != Offset.Zero) {
                        state.panBy(pan.x, pan.y)
                    }
                }
            }
            // Single-touch & Stylus gestures: Drawing, erasing, single-finger pan, or tapping empty space
            .pointerInput(state.activeTool, state.viewport) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val isStylus = down.type == PointerType.Stylus
                    val isDrawingTool = state.activeTool == WhiteboardTool.PEN || state.activeTool == WhiteboardTool.HIGHLIGHTER
                    val shouldDraw = if (state.isFingerBrushMode) {
                        isStylus || isDrawingTool
                    } else {
                        isStylus || (down.type != PointerType.Touch && isDrawingTool)
                    }
                    val isEraser = state.activeTool == WhiteboardTool.ERASER
                    val isPanTool = state.activeTool == WhiteboardTool.PAN

                    val canvasDownPos = WhiteboardMath.screenToCanvas(down.position, state.viewport)
                    val isOverNode = state.data.nodes.any { node ->
                        WhiteboardMath.nodeBounds(node).contains(canvasDownPos)
                    }

                    if (shouldDraw) {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        val isHighlighter = state.activeTool == WhiteboardTool.HIGHLIGHTER
                        state.startStroke(down.position, isHighlighter = isHighlighter, pressure = down.pressure)
                        down.consume()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                state.endStroke()
                                break
                            }
                            state.addStrokePoint(change.position, pressure = change.pressure)
                            change.consume()
                        }
                    } else if (isEraser) {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        state.eraseAtScreenPoint(down.position)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            state.eraseAtScreenPoint(change.position)
                            change.consume()
                        }
                    } else if (isPanTool) {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        var previousPos = down.position
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.position - previousPos
                            state.panBy(delta.x, delta.y)
                            previousPos = change.position
                            change.consume()
                        }
                    } else if (!isOverNode) {
                        // Empty canvas space touched in SELECT or other mode!
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()

                        var previousPos = down.position
                        var totalMovement = 0f
                        var isDrag = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!isDrag) {
                                    // Pointer released without dragging -> deselect all notes and frames!
                                    state.selectNode(null)
                                    state.selectedSectionId = null
                                    state.selectedConnectorId = null
                                }
                                break
                            }
                            val delta = change.position - previousPos
                            totalMovement += delta.getDistance()
                            if (totalMovement > 8f) {
                                isDrag = true
                                state.panBy(delta.x, delta.y)
                                change.consume()
                            }
                            previousPos = change.position
                        }
                    }
                }
            },
    ) {
        val viewport = state.viewport
        val visibleRect = WhiteboardMath.visibleCanvasRect(
            screenSize = Size(screenSize.width.toFloat(), screenSize.height.toFloat()),
            viewport = viewport,
        )

        // 1. Vector Drawing & Connectors Surface (Underneath cards)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawDotGrid(viewport, visibleRect)
            drawConnectors(state, viewport, visibleRect)
            drawStrokes(state.data.strokes, viewport, visibleRect)

            // Draw active in-progress vector stroke
            if (state.activeStrokePoints.isNotEmpty()) {
                val screenPoints = state.activeStrokePoints.map { pt ->
                    WhiteboardMath.canvasToScreen(Offset(pt.x, pt.y), viewport)
                }
                val strokePath = WhiteboardMath.buildSmoothStrokePath(screenPoints.map { org.yaop.core.model.whiteboard.StrokePoint(it.x, it.y) })
                val strokeColor = if (state.isDrawingHighlighter) {
                    runCatching { Color(state.activeInkColor.toColorInt()).copy(alpha = 0.35f) }
                        .getOrDefault(TypePalette.HighlightYellow)
                } else {
                    runCatching { Color(state.activeInkColor.toColorInt()) }.getOrDefault(TypePalette.ColorTextPrimary)
                }
                val strokeWidth = if (state.isDrawingHighlighter) {
                    max(state.activeStrokeWidth * 3.5f, 18f) * viewport.zoom
                } else {
                    state.activeStrokeWidth * viewport.zoom
                }

                drawPath(
                    path = strokePath,
                    color = strokeColor,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }

        // 2. Sections / Frames layer
        state.data.sections.forEach { section ->
            if (WhiteboardMath.isSectionVisible(section, visibleRect)) {
                WhiteboardSectionView(
                    section = section,
                    state = state,
                )
            }
        }

        // 3. Interactive Nodes (Cards & Stickies)
        state.data.nodes.forEach { node ->
            if (WhiteboardMath.isNodeVisible(node, visibleRect)) {
                WhiteboardNodeView(
                    node = node,
                    state = state,
                    allObjects = allObjects,
                    allTypes = allTypes,
                    onNavigateToObject = onNavigateToObject,
                    onToggleTask = onToggleTask,
                    onConvertToNote = onConvertToNote,
                )
            }
        }
    }
}

/**
 * Draws the infinite dot grid background with high performance.
 */
private fun DrawScope.drawDotGrid(viewport: CanvasViewport, visibleRect: Rect) {
    val stepCanvas = 36f
    val dotColor = Color.White.copy(alpha = (0.11f * viewport.zoom.coerceIn(0.5f, 1.2f)).coerceIn(0.04f, 0.18f))
    val dotRadius = (1.5f * viewport.zoom).coerceIn(1.0f, 2.8f)

    val startX = floor(visibleRect.left / stepCanvas) * stepCanvas
    val endX = visibleRect.right + stepCanvas
    val startY = floor(visibleRect.top / stepCanvas) * stepCanvas
    val endY = visibleRect.bottom + stepCanvas

    var currentX = startX
    while (currentX <= endX) {
        var currentY = startY
        while (currentY <= endY) {
            val screenPt = WhiteboardMath.canvasToScreen(Offset(currentX, currentY), viewport)
            drawCircle(
                color = dotColor,
                radius = dotRadius,
                center = screenPt,
            )
            currentY += stepCanvas
        }
        currentX += stepCanvas
    }
}

/**
 * Renders all dynamic visual connectors between cards.
 */
private fun DrawScope.drawConnectors(
    state: WhiteboardState,
    viewport: CanvasViewport,
    visibleRect: Rect,
) {
    val nodeMap = state.data.nodes.associateBy { it.id }

    for (connector in state.data.connectors) {
        val fromNode = nodeMap[connector.fromNodeId] ?: continue
        val toNode = nodeMap[connector.toNodeId] ?: continue

        val fromBounds = WhiteboardMath.nodeBounds(fromNode)
        val toBounds = WhiteboardMath.nodeBounds(toNode)

        val startAnchorCanvas = WhiteboardMath.calculateNodeAnchor(fromBounds, toBounds.center)
        val endAnchorCanvas = WhiteboardMath.calculateNodeAnchor(toBounds, fromBounds.center)

        val startScreen = WhiteboardMath.canvasToScreen(startAnchorCanvas, viewport)
        val endScreen = WhiteboardMath.canvasToScreen(endAnchorCanvas, viewport)

        val isSelected = state.selectedConnectorId == connector.id
        val connectorColor = if (isSelected) {
            TypePalette.ColorIce
        } else {
            runCatching { Color(connector.colorHex.toColorInt()) }.getOrDefault(TypePalette.ColorCobalt)
        }

        val path = WhiteboardMath.buildConnectorPath(startScreen, endScreen)
        val strokeWidth = (if (isSelected) 3.5f else 2.5f) * viewport.zoom.coerceIn(0.6f, 1.5f)

        val pathEffect = if (connector.style == ConnectorStyle.DASHED) {
            PathEffect.dashPathEffect(floatArrayOf(14f * viewport.zoom, 10f * viewport.zoom), 0f)
        } else null

        drawPath(
            path = path,
            color = connectorColor,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = pathEffect,
            ),
        )

        // Arrow head
        if (connector.directed) {
            val controlPt = Offset((startScreen.x + endScreen.x) / 2f, (startScreen.y + endScreen.y) / 2f)
            val (tip, wing1, wing2) = WhiteboardMath.calculateArrowHeadPoints(
                endPoint = endScreen,
                controlPoint = controlPt,
                arrowSize = 13f * viewport.zoom.coerceIn(0.6f, 1.6f),
            )
            val arrowPath = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(wing1.x, wing1.y)
                lineTo(wing2.x, wing2.y)
                close()
            }
            drawPath(path = arrowPath, color = connectorColor, style = Fill)
        }
    }

    // In-progress connector drag line
    val sourceId = state.connectingFromNodeId
    val targetPos = state.connectionDragTarget
    if (sourceId != null && targetPos != null) {
        val sourceNode = nodeMap[sourceId]
        if (sourceNode != null) {
            val sourceCenter = WhiteboardMath.nodeBounds(sourceNode).center
            val startScreen = WhiteboardMath.canvasToScreen(sourceCenter, viewport)
            val endScreen = WhiteboardMath.canvasToScreen(targetPos, viewport)

            val inProgressPath = WhiteboardMath.buildConnectorPath(startScreen, endScreen)
            drawPath(
                path = inProgressPath,
                color = TypePalette.ColorPurple,
                style = Stroke(
                    width = 2.5f * viewport.zoom.coerceIn(0.6f, 1.5f),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                ),
            )
        }
    }
}

/**
 * Draws all committed freehand vector strokes.
 */
private fun DrawScope.drawStrokes(
    strokes: List<WhiteboardStroke>,
    viewport: CanvasViewport,
    visibleRect: Rect,
) {
    for (stroke in strokes) {
        if (stroke.points.size < 2) continue

        val screenPoints = stroke.points.map { pt ->
            WhiteboardMath.canvasToScreen(Offset(pt.x, pt.y), viewport)
        }

        val strokePath = WhiteboardMath.buildSmoothStrokePath(screenPoints.map { org.yaop.core.model.whiteboard.StrokePoint(it.x, it.y) })
        val strokeColor = if (stroke.isHighlighter) {
            runCatching { Color(stroke.colorHex.toColorInt()).copy(alpha = 0.38f) }
                .getOrDefault(TypePalette.HighlightYellow)
        } else {
            runCatching { Color(stroke.colorHex.toColorInt()) }.getOrDefault(TypePalette.ColorTextPrimary)
        }

        val strokeWidth = stroke.strokeWidth * viewport.zoom

        drawPath(
            path = strokePath,
            color = strokeColor,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
