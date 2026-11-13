package org.yaop.core.query

import org.yaop.core.sync.MergeContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** spec/fixtures/query — the same cases the Go reference and the desktop selftest run. */
class QueryConformanceTest {

    private val dir: File = sequenceOf("../../spec/fixtures/query", "../spec/fixtures/query", "spec/fixtures/query")
        .map { File(it) }.firstOrNull { it.isDirectory }
        ?: error("spec/fixtures/query not found from ${File(".").absolutePath}")

    @Test
    fun fixtures() {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue("no query fixtures", files.isNotEmpty())
        var ran = 0
        for (file in files) {
            val fx = JSONObject(file.readText())
            val objectsArr = fx.getJSONArray("objects")
            val objects = (0 until objectsArr.length()).map { objectsArr.getJSONObject(it) }
            val today = fx.getString("today")
            val cases = fx.getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val c = cases.getJSONObject(i)
                val label = "${file.name}/${c.getString("name")}"
                val expect = c.getJSONObject("expect")
                val result = runCatching { QueryEngine.evaluate(c.getJSONObject("query"), objects, today) }
                ran++
                if (expect.has("error")) {
                    val err = result.exceptionOrNull() as? QueryEngine.QueryException
                    assertEquals(label, expect.getString("error"), err?.code)
                    continue
                }
                val got = result.getOrElse { fail("$label: unexpected ${it.message}"); return }
                val gotJson = if (expect.has("groups")) {
                    JSONObject().put("groups", JSONArray(got.groups.orEmpty().map { g ->
                        JSONObject().put("key", g.key ?: JSONObject.NULL).put("ids", JSONArray(g.ids))
                    }))
                } else {
                    JSONObject().put("ids", JSONArray(got.ids))
                }
                assertEquals(label, MergeContract.canon(expect), MergeContract.canon(gotJson))
            }
        }
        assertTrue("ran $ran cases", ran >= 56)
    }

    /** Scale probe (budget: 10k results < 200 ms on the device). Printed, not gated. */
    @Test
    fun scaleProbe() {
        val status = listOf("not_started", "in_progress", "done")
        val big = (0 until 10_000).map { i ->
            JSONObject()
                .put("id", "o%05d".format(i))
                .put("type_id", if (i % 4 != 0) "task" else "note")
                .put("title", "Object $i title")
                .put("created_at", "2026-11-%02dT10:00:00Z".format(1 + i % 28))
                .put("updated_at", "2026-11-%02dT%02d:00:00Z".format(1 + i % 28, i % 24))
                .put("links", JSONArray(listOf("o%05d".format((i * 7) % 10_000))))
                .put("properties", JSONObject()
                    .put("status", status[i % 3])
                    .put("due_date", "2026-12-%02d".format(1 + i % 28))
                    .put("tags", JSONArray(listOf("t${i % 9}")))
                    .put("rating", i % 5))
        }
        val heavy = JSONObject("""{"version":1,"filters":[{"prop":"@title","op":"contains","value":"object"}],
            "sort":[{"prop":"due_date"},{"prop":"@title","dir":"desc"}],"limit":50}""")
        val kanban = JSONObject("""{"version":1,"types":["task"],"group":{"prop":"status"}}""")
        QueryEngine.evaluate(heavy, big, "2026-11-12") // warm-up (JIT)
        val t0 = System.nanoTime()
        val rh = QueryEngine.evaluate(heavy, big, "2026-11-12")
        val t1 = System.nanoTime()
        val rk = QueryEngine.evaluate(kanban, big, "2026-11-12")
        val t2 = System.nanoTime()
        assertEquals(50, rh.ids.size)
        assertEquals(3, rk.groups?.size)
        println("QUERY-BENCH jvm 10k: contains+2 sorts+limit ${(t1 - t0) / 1_000_000} ms, kanban ${(t2 - t1) / 1_000_000} ms")
    }
}
