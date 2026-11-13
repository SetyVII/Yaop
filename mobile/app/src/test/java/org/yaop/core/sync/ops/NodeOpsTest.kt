package org.yaop.core.sync.ops

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class NodeOpsTest {
    private fun decoded(kind: String, json: String, wire: String = "ab12") =
        DecodedOp(RemoteOp(1, kind, wire, "dev", ByteArray(0)), JSONObject(json))
    private fun run(h: RemoteOpHandler, d: DecodedOp) = runBlocking { h.handle(d) }

    private class Memory : DerivedSink {
        val map = HashMap<String, DerivedArtifact>()
        override fun put(wireId: String, artifact: DerivedArtifact) { map[wireId] = artifact }
        override fun get(wireId: String) = map[wireId]
    }

    @Test fun storesAValidArtifactUnderItsBlindId() {
        val sink = Memory()
        val r = run(DerivedOpHandler(sink), decoded("derived", """{"v":1,"kind":"pdf.text","source_hash":"h1","producer":"node/1","data":{"text":"hola"}}"""))
        assertEquals(OpResult.APPLIED, r)
        assertEquals("h1", sink.get("ab12")!!.sourceHash)
    }

    @Test fun newerProducerOverwrites() {
        val sink = Memory(); val h = DerivedOpHandler(sink)
        run(h, decoded("derived", """{"v":1,"kind":"ocr.text","source_hash":"h1","producer":"node/1","data":{}}"""))
        run(h, decoded("derived", """{"v":1,"kind":"ocr.text","source_hash":"h1","producer":"node/2","data":{}}"""))
        assertEquals("node/2", sink.get("ab12")!!.producer)
    }

    @Test fun malformedArtifactsAreSkipped() {
        val sink = Memory(); val h = DerivedOpHandler(sink)
        assertEquals(OpResult.SKIPPED, run(h, decoded("derived", """{"v":2,"kind":"x","source_hash":"h","data":{}}""")))
        assertEquals(OpResult.SKIPPED, run(h, decoded("derived", """{"v":1,"kind":"","source_hash":"h","data":{}}""")))
        assertEquals(OpResult.SKIPPED, run(h, decoded("derived", """{"v":1,"kind":"x","data":{}}""")))
        assertNull(sink.get("ab12"))
    }

    @Test fun fileSinkRoundtripAndRejectsUnsafeNames() {
        val dir = Files.createTempDirectory("derived").toFile()
        val sink = FileDerivedSink(dir)
        sink.put("0a1b", DerivedArtifact("thumb.grid", "h", "node/1", JSONObject("""{"b64":"AAAA"}""")))
        assertNotNull(sink.get("0a1b"))
        try { sink.put("../evil", DerivedArtifact("k", "h", "p", JSONObject())); org.junit.Assert.fail() }
        catch (e: IllegalArgumentException) { }
    }

    @Test fun nodeRequestIsQueuedOnlyWhenThereIsASink() {
        val got = ArrayList<NodeRequest>()
        val payload = decoded("node_request", """{"v":1,"object_id":"obj1","kind":"pdf.text"}""")
        assertEquals(OpResult.SKIPPED, run(NodeRequestOpHandler(null), payload))
        assertEquals(OpResult.APPLIED, run(NodeRequestOpHandler(object : NodeRequestSink { override fun enqueue(request: NodeRequest) { got += request } }), payload))
        assertEquals("pdf.text", got.single().kind)
        assertEquals(OpResult.SKIPPED, run(NodeRequestOpHandler(null), decoded("node_request", """{"v":1,"kind":"x"}""")))
    }
}
