package org.yaop.core.model.whiteboard

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Top-level data structure representing an entire infinite 2D Whiteboard canvas.
 * Aligned with the Capacities Visual Note-Taking and Heptabase spatial models.
 */
@Immutable
@Serializable
data class WhiteboardData(
    val id: String = UUID.randomUUID().toString(),
    val nodes: List<WhiteboardNode> = emptyList(),
    val connectors: List<WhiteboardConnector> = emptyList(),
    val sections: List<WhiteboardSection> = emptyList(),
    val strokes: List<WhiteboardStroke> = emptyList(),
    val viewport: CanvasViewport = CanvasViewport(),
)

/**
 * Viewport transformation parameters on the infinite plane.
 */
@Immutable
@Serializable
data class CanvasViewport(
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1.0f,
) {
    companion object {
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 4.0f
        const val DEFAULT_ZOOM = 1.0f
    }
}

/**
 * A spatial 2D node placed on the whiteboard plane.
 */
@Immutable
@Serializable
data class WhiteboardNode(
    val id: String = UUID.randomUUID().toString(),
    val x: Float,
    val y: Float,
    val width: Float = DEFAULT_WIDTH,
    val height: Float = DEFAULT_HEIGHT,
    val zIndex: Int = 0,
    val content: WhiteboardNodeContent,
) {
    companion object {
        const val DEFAULT_WIDTH = 240f
        const val DEFAULT_HEIGHT = 150f
        const val STICKY_DEFAULT_SIZE = 190f
    }
}

/**
 * Content payload of a [WhiteboardNode].
 */
@Serializable
sealed interface WhiteboardNodeContent {

    /**
     * An object card that points to a first-class [org.yaop.core.model.PkmObject]
     * in the vault (Note, Task, Book, WebLink, Image, etc.).
     */
    @Serializable
    @SerialName("object_card")
    data class ObjectCard(
        val objectId: String,
        val viewMode: CardViewMode = CardViewMode.DETAILED,
    ) : WhiteboardNodeContent

    /**
     * A standalone color-coded sticky note on the canvas.
     */
    @Serializable
    @SerialName("sticky")
    data class Sticky(
        val text: String = "",
        val colorHex: String = WhiteboardPalette.STICKY_YELLOW,
    ) : WhiteboardNodeContent

    /**
     * Plain textual annotation or canvas title label without background card chrome.
     */
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String = "",
        val fontSize: Int = 18,
        val colorHex: String = "#FFFFFF",
    ) : WhiteboardNodeContent
}

/**
 * Visual presentation mode of an object card on the whiteboard.
 */
@Serializable
enum class CardViewMode {
    COMPACT,
    DETAILED,
}

/**
 * Visual relationship edge / connector between two nodes.
 */
@Immutable
@Serializable
data class WhiteboardConnector(
    val id: String = UUID.randomUUID().toString(),
    val fromNodeId: String,
    val toNodeId: String,
    val label: String = "",
    val style: ConnectorStyle = ConnectorStyle.SOLID,
    val colorHex: String = "#3E58EB",
    val directed: Boolean = true,
)

@Serializable
enum class ConnectorStyle {
    SOLID,
    DASHED,
}

/**
 * Visual bounding frame / section grouping cards and elements together.
 */
@Immutable
@Serializable
data class WhiteboardSection(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val x: Float,
    val y: Float,
    val width: Float = 420f,
    val height: Float = 320f,
    val colorHex: String = WhiteboardPalette.SECTION_SLATE,
)

/**
 * Freehand vector drawing stroke on the canvas.
 */
@Immutable
@Serializable
data class WhiteboardStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<StrokePoint> = emptyList(),
    val colorHex: String = "#FFFFFF",
    val strokeWidth: Float = 3.5f,
    val isHighlighter: Boolean = false,
)

@Immutable
@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f,
)

/**
 * Active editing tool in the Whiteboard toolbar.
 */
enum class WhiteboardTool {
    SELECT,
    PAN,
    CARD,
    STICKY,
    CONNECTOR,
    PEN,
    HIGHLIGHTER,
    SECTION,
    ERASER,
}
