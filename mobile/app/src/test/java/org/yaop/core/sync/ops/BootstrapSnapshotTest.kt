package org.yaop.core.sync.ops

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BootstrapSnapshotTest {
    @Test fun roundtrip() {
        val built = BootstrapSnapshot.build(2, 4900, listOf(JSONObject("{\"id\":\"a\"}"), JSONObject("{\"id\":\"b\"}")), listOf(JSONObject("{\"id\":\"note\"}")))
        val p = BootstrapSnapshot.parse(JSONObject(built.toString()))!!
        assertEquals(2L, p.keyEpoch); assertEquals(4900L, p.coveredSeq)
        assertEquals(listOf("a", "b"), p.objects.map { it.getString("id") }); assertEquals(1, p.types.size)
    }

    @Test fun aSingleObjectSnapshotIsNotAContainer() {
        assertNull(BootstrapSnapshot.parse(JSONObject("{\"id\":\"a\",\"title\":\"t\"}")))
        assertNull(BootstrapSnapshot.parse(JSONObject("{\"v\":9,\"objects\":[]}")))
    }
}
