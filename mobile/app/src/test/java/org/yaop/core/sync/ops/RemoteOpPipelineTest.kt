package org.yaop.core.sync.ops

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteOpPipelineTest {
    private class Recorder(override val kinds: Set<String>, val result: OpResult = OpResult.APPLIED) : RemoteOpHandler {
        val seen = ArrayList<String>()
        override suspend fun handle(op: DecodedOp): OpResult { seen += op.payload.optString("id"); return result }
    }

    private fun op(kind: String) = RemoteOp(7, kind, "w", "dev", "{\"id\":\"x\"}".toByteArray())
    private val opener = OpOpener { _, _, blob -> blob }
    private fun run(p: RemoteOpPipeline, op: RemoteOp) = runBlocking { p.apply(op) }

    @Test fun dispatchesByKind() {
        val a = Recorder(setOf("obj")); val b = Recorder(setOf("type"))
        val p = RemoteOpPipeline(RemoteOpRegistry(listOf(a, b)), opener)
        assertEquals(OpResult.APPLIED, run(p, op("type")))
        assertEquals(emptyList<String>(), a.seen); assertEquals(listOf("x"), b.seen)
    }

    @Test fun unknownKindIsSkippedNotFatal() {
        val p = RemoteOpPipeline(RemoteOpRegistry(listOf(Recorder(setOf("obj")))), opener)
        assertEquals(OpResult.SKIPPED, run(p, op("from_the_future")))
    }

    @Test fun undecryptableAndNonJsonAreSkipped() {
        val h = Recorder(setOf("obj"))
        assertEquals(OpResult.SKIPPED, run(RemoteOpPipeline(RemoteOpRegistry(listOf(h)), { _, _, _ -> null }), op("obj")))
        assertEquals(OpResult.SKIPPED, run(RemoteOpPipeline(RemoteOpRegistry(listOf(h)), { _, _, _ -> "no json".toByteArray() }), op("obj")))
        assertTrue(h.seen.isEmpty())
    }

    @Test fun pauseIsPropagated() {
        val p = RemoteOpPipeline(RemoteOpRegistry(listOf(Recorder(setOf("obj"), OpResult.PAUSE))), opener)
        assertEquals(OpResult.PAUSE, run(p, op("obj")))
    }

    @Test fun passthroughNeverDecrypts() {
        var opened = false
        val p = RemoteOpPipeline(RemoteOpRegistry(listOf(PassthroughOpHandler(setOf("derived")))), { _, _, _ -> opened = true; null })
        assertEquals(OpResult.SKIPPED, run(p, op("derived")))
        assertTrue(!opened)
    }

    @Test fun oneKindOneHandler() {
        try { RemoteOpRegistry(listOf(Recorder(setOf("obj")), Recorder(setOf("obj")))); org.junit.Assert.fail() }
        catch (e: IllegalArgumentException) { assertNull(null) }
    }
}
