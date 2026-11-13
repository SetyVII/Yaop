package org.yaop.core.model.whiteboard

import org.yaop.core.model.Block
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.PkmObject
import org.yaop.core.model.getWhiteboardData
import org.yaop.core.model.withWhiteboardData
import org.yaop.core.sync.SyncContractCodec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    @Test
    fun whiteboardData_jsonSerialization_roundtripsFaithfully() {
        val original = WhiteboardData(
            id = "board-1",
            nodes = listOf(
                WhiteboardNode(
                    id = "n1",
                    x = 120f,
                    y = 240f,
                    width = 240f,
                    height = 150f,
                    content = WhiteboardNodeContent.ObjectCard(objectId = "note-abc", viewMode = CardViewMode.DETAILED),
                ),
                WhiteboardNode(
                    id = "n2",
                    x = 480f,
                    y = 300f,
                    width = 190f,
                    height = 190f,
                    content = WhiteboardNodeContent.Sticky(text = "Review architecture", colorHex = WhiteboardPalette.STICKY_BLUE),
                ),
                WhiteboardNode(
                    id = "n3",
                    x = 50f,
                    y = 50f,
                    content = WhiteboardNodeContent.Text(text = "Executive Summary", fontSize = 24),
                ),
            ),
            connectors = listOf(
                WhiteboardConnector(
                    id = "c1",
                    fromNodeId = "n1",
                    toNodeId = "n2",
                    label = "influences",
                    style = ConnectorStyle.SOLID,
                    colorHex = "#3E58EB",
                ),
            ),
            sections = listOf(
                WhiteboardSection(
                    id = "s1",
                    title = "Research",
                    x = 30f,
                    y = 30f,
                    width = 600f,
                    height = 450f,
                    colorHex = WhiteboardPalette.SECTION_SLATE,
                ),
            ),
            strokes = listOf(
                WhiteboardStroke(
                    id = "st1",
                    points = listOf(StrokePoint(10f, 10f), StrokePoint(20f, 25f), StrokePoint(35f, 50f)),
                    colorHex = "#FFFFFF",
                    strokeWidth = 3f,
                    isHighlighter = false,
                ),
            ),
            viewport = CanvasViewport(panX = 200f, panY = -150f, zoom = 1.25f),
        )

        val encoded = json.encodeToString(WhiteboardData.serializer(), original)
        val decoded = json.decodeFromString(WhiteboardData.serializer(), encoded)

        assertEquals(original.id, decoded.id)
        assertEquals(3, decoded.nodes.size)
        assertEquals(1, decoded.connectors.size)
        assertEquals("influences", decoded.connectors.first().label)
        assertEquals(1, decoded.sections.size)
        assertEquals("Research", decoded.sections.first().title)
        assertEquals(1, decoded.strokes.size)
        assertEquals(1.25f, decoded.viewport.zoom, 0.001f)
    }

    @Test
    fun syncContractCodec_canvasBlockSerialization_roundtripsCorrectly() {
        val boardData = WhiteboardData(
            id = "canvas-test-1",
            nodes = listOf(
                WhiteboardNode(
                    id = "node-1",
                    x = 50f,
                    y = 60f,
                    content = WhiteboardNodeContent.Sticky("Inline Canvas Note"),
                ),
            ),
        )

        val canvasBlock = Block.Canvas(id = "block-c1", data = boardData)
        val jsonArray = SyncContractCodec.blocksToJsonArray(listOf(canvasBlock))
        val recoveredBlocks = SyncContractCodec.jsonArrayToBlocks(jsonArray)

        assertEquals(1, recoveredBlocks.size)
        val recovered = recoveredBlocks.first()
        assertTrue(recovered is Block.Canvas)
        val recoveredCanvas = recovered as Block.Canvas
        assertEquals("block-c1", recoveredCanvas.id)
        assertEquals(1, recoveredCanvas.data.nodes.size)
        assertEquals("Inline Canvas Note", (recoveredCanvas.data.nodes.first().content as WhiteboardNodeContent.Sticky).text)
    }

    @Test
    fun yaopObject_whiteboardDataExtensions_storeAndRetrieve() {
        val obj = PkmObject(
            id = "whiteboard-obj-1",
            typeId = CanonicalTypeIds.WHITEBOARD,
        )

        val data = WhiteboardData(
            id = obj.id,
            nodes = listOf(
                WhiteboardNode(id = "n1", x = 100f, y = 100f, content = WhiteboardNodeContent.Sticky("Test")),
            ),
            viewport = CanvasViewport(panX = 50f, panY = 50f, zoom = 1.5f),
        )

        val updatedObj = obj.withWhiteboardData(data)
        val retrievedData = updatedObj.getWhiteboardData()

        assertEquals(1, retrievedData.nodes.size)
        assertEquals("n1", retrievedData.nodes.first().id)
        assertEquals(1.5f, retrievedData.viewport.zoom, 0.001f)
    }
}
