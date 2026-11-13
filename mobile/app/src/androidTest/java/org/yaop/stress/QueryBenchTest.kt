package org.yaop.stress

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.yaop.core.query.QueryEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Query engine on a mid-range device: the engine alone over 10k wire objects (no database).
 * The stress build is debuggable, which roughly triples ART times; compare against the budget
 * only with a non-debuggable build (2026-11-12, mid-range tablet: 79 / 66 ms; debuggable: ~250 / ~190 ms).
 */
@RunWith(AndroidJUnit4::class)
class QueryBenchTest {
    @Test
    fun tenThousandObjects() {
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
        QueryEngine.evaluate(heavy, big, "2026-11-12") // warm-up
        val runs = 5
        val heavyMs = LongArray(runs)
        val kanbanMs = LongArray(runs)
        repeat(runs) { r ->
            var t = System.nanoTime()
            assertEquals(50, QueryEngine.evaluate(heavy, big, "2026-11-12").ids.size)
            heavyMs[r] = (System.nanoTime() - t) / 1_000_000
            t = System.nanoTime()
            assertEquals(3, QueryEngine.evaluate(kanban, big, "2026-11-12").groups?.size)
            kanbanMs[r] = (System.nanoTime() - t) / 1_000_000
        }
        Log.i("QUERY-BENCH", "device 10k: contains+2 sorts+limit median ${heavyMs.sorted()[runs / 2]} ms " +
            "(${heavyMs.joinToString()}), kanban median ${kanbanMs.sorted()[runs / 2]} ms (${kanbanMs.joinToString()})")
    }
}
