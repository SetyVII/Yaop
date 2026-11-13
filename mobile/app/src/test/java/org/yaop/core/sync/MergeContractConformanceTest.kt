package org.yaop.core.sync

import org.yaop.core.model.HybridLogicalClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Same fixtures as Go (server) and C++ (desktop --selftest): spec/fixtures. */
class MergeContractConformanceTest {

    private val specDir: File = sequenceOf("../../spec/fixtures", "../spec/fixtures", "spec/fixtures")
        .map { File(it) }.firstOrNull { it.isDirectory }
        ?: error("spec/fixtures not found from ${File(".").absolutePath}")

    private fun fixtures(sub: String): List<Pair<String, JSONObject>> {
        val files = File(specDir, sub).listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue("no fixtures in $sub", files.isNotEmpty())
        return files.map { it.name to JSONObject(it.readText()) }
    }

    @Test
    fun hlcOrderAndNodes() {
        for ((name, fx) in fixtures("hlc")) {
            val cases = fx.getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val c = cases.getJSONObject(i)
                val label = "$name/${c.getString("name")}"
                assertEquals(label, c.getInt("cmp"), HybridLogicalClock.compare(c.getString("a"), c.getString("b")))
                assertEquals("$label (antisymmetric)", -c.getInt("cmp"), HybridLogicalClock.compare(c.getString("b"), c.getString("a")))
            }
            val nodes = fx.optJSONArray("nodes") ?: continue
            for (i in 0 until nodes.length()) {
                val n = nodes.getJSONObject(i)
                assertEquals(n.getString("device_id"), n.getString("node"), HybridLogicalClock.nodeFor(n.getString("device_id")))
            }
        }
    }

    @Test
    fun tagKeys() {
        val fx = JSONObject(File(specDir, "links/tag_keys.json").readText()).getJSONArray("cases")
        for (i in 0 until fx.length()) {
            val c = fx.getJSONObject(i)
            assertEquals(c.getString("name"), c.getString("key"), MergeContract.tagKey(c.getString("name")))
        }
    }

    @Test
    fun linksDerive() {
        val fx = JSONObject(File(specDir, "links/derive.json").readText())
        val want = fx.getJSONArray("expect_links").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(want, MergeContract.deriveLinks(fx.getJSONObject("object")))
    }

    @Test
    fun hlcReceiveMovesPastRemote() {
        val far = "9999999999999-0005-ffffffff"
        val after = HybridLogicalClock.receive(far)
        assertTrue(HybridLogicalClock.compare(after, far) > 0)
        assertTrue(HybridLogicalClock.compare(HybridLogicalClock.now(), after) > 0)
    }

    @Test
    fun stamp() {
        for ((name, fx) in fixtures("stamp")) {
            val prev = fx.optJSONObject("prev")
            val prevMeta = MergeContract.Meta(
                MergeContract.metaFromJson(prev?.optJSONObject("field_hlc")),
                MergeContract.metaFromJson(prev?.optJSONObject("block_hlc")),
                MergeContract.metaFromJson(prev?.optJSONObject("deleted_blocks")),
                MergeContract.anchorsFromJson(prev?.optJSONObject("block_anchor")),
            )
            val now = fx.getString("now")
            val result = MergeContract.stamp(prev, fx.getJSONObject("next"), prevMeta, now)
            val want = fx.getJSONObject("expect_meta")
            assertEquals("$name field_hlc", MergeContract.metaFromJson(want.optJSONObject("field_hlc")), result.meta.fieldHlc)
            assertEquals("$name block_hlc", MergeContract.metaFromJson(want.optJSONObject("block_hlc")), result.meta.blockHlc)
            assertEquals("$name deleted_blocks", MergeContract.metaFromJson(want.optJSONObject("deleted_blocks")), result.meta.deletedBlocks)
            val gotHlc = if (result.changed) now else prev?.optString("hlc").orEmpty()
            assertEquals("$name hlc", fx.getString("expect_hlc"), gotHlc)
            // block_anchor (/3): compared only when the fixture pins it (legacy files omit it).
            want.optJSONObject("block_anchor")?.let {
                val gotAnchor = if (result.meta.blockAnchor.isEmpty()) JSONObject() else JSONObject().apply {
                    result.meta.blockAnchor.forEach { (id, a) ->
                        put(id, JSONObject().put("after", a.after).put("hlc", a.hlc))
                    }
                }
                assertEquals("$name block_anchor", MergeContract.canon(it), MergeContract.canon(gotAnchor))
            }
        }
    }

    /** spec/fixtures/merge (pares, /2 y /3): merge(a,b) y merge(b,a) == expect, clave a clave. */
    @Test
    fun merge() {
        for ((name, fx) in fixtures("merge")) {
            val a = fx.getJSONObject("a")
            val b = fx.getJSONObject("b")
            val expect = fx.getJSONObject("expect")
            for ((dir, got) in listOf("merge(a,b)" to MergeContract.merge(a, b), "merge(b,a)" to MergeContract.merge(b, a))) {
                val label = "$name $dir"
                for (key in expect.keys()) {
                    assertEquals(
                        "$label $key",
                        MergeContract.canon(expect.get(key)),
                        MergeContract.canon(got.opt(key) ?: JSONObject()),
                    )
                }
            }
        }
    }
}

/** Cross-platform text fidelity: parse → export reproduces spec/fixtures/doc exactly. */
class DocRoundTripConformanceTest {
    private val specDir: java.io.File = (System.getenv("YAOP_SPEC_DIR")?.let { sequenceOf(it) } ?: sequenceOf("../../spec/fixtures", "../spec/fixtures", "spec/fixtures"))
        .map { java.io.File(it) }.first { it.isDirectory }
    private val dumpDir: String? = System.getenv("YAOP_ROUNDTRIP_DUMP")

    @Test
    fun docRoundTrip() {
        val files = java.io.File(specDir, "doc").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        val failures = mutableListOf<String>()
        for (f in files) {
            val fx = JSONObject(f.readText())
            val input = fx.getJSONArray("blocks")
            val src = fx.optJSONArray("input") ?: input
            val out = SyncContractCodec.blocksToStructuredJson(SyncContractCodec.structuredJsonToBlocks(src))
            if (dumpDir != null) {
                // Differential mode over a private corpus: write outputs, never print content.
                java.io.File(dumpDir).mkdirs()
                java.io.File(dumpDir, f.name).writeText(out.toString())
                continue
            }
            for (i in 0 until maxOf(input.length(), out.length())) {
                val a = MergeContract.canon(input.opt(i))
                val b = MergeContract.canon(out.opt(i))
                if (a != b) failures += "${f.name}[$i]\n    want $a\n    got  $b"
            }
        }
        assertTrue("doc roundtrip divergences:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}

/** Whole-object fidelity (spec/object.md): title, rich title, properties, references. */
class ObjectRoundTripConformanceTest {
    private val specDir: java.io.File = (System.getenv("YAOP_SPEC_DIR")?.let { sequenceOf(it) } ?: sequenceOf("../../spec/fixtures", "../spec/fixtures", "spec/fixtures"))
        .map { java.io.File(it) }.first { it.isDirectory }
    private val dumpDir: String? = System.getenv("YAOP_ROUNDTRIP_DUMP")
    private val keys = listOf("id", "type_id", "title", "title_runs", "properties", "links", "property_links")

    @Test
    fun objectRoundTrip() {
        val files = java.io.File(specDir, "object").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        val failures = mutableListOf<String>()
        for (f in files) {
            val input = JSONObject(f.readText()).getJSONObject("object")
            val out = SyncContractCodec.toServerJson(SyncContractCodec.fromServerJson(input))
            if (dumpDir != null) {
                java.io.File(dumpDir).mkdirs()
                java.io.File(dumpDir, f.name).writeText(out.toString())
                continue
            }
            for (k in keys) {
                val empty = if (k == "links" || k == "property_links") org.json.JSONArray() else null
                val a = MergeContract.canon(input.opt(k) ?: empty)
                val b = MergeContract.canon(out.opt(k) ?: empty)
                if (a != b) failures += "${f.name} $k\n    want $a\n    got  $b"
            }
        }
        assertTrue("object roundtrip divergences:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
