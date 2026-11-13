package org.yaop.core.debug

import org.yaop.YaopApp
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.LocalDate

/**
 * Fills a throwaway vault with realistic content so every screen can be judged without going through
 * onboarding or touching real data. Debug builds only (see MainActivity): launch with
 * `adb shell am start -n <pkg>/org.yaop.MainActivity --ez seed_demo true`.
 */
object DemoSeeder {
    private const val HLC = "0000000000001-0000-aaaaaaaa"

    suspend fun seed(app: YaopApp) {
        val repo = app.repository
        if (!repo.vaultKeyStore.hasVaultKey()) {
            repo.createVaultKey(ByteArray(32).also { SecureRandom().nextBytes(it) }, 1L)
        }
        val today = LocalDate.now().toString()
        for (wire in objects(today)) {
            val id = wire.getString("id")
            repo.applyMergedWire(id, wire, wire)
        }
    }

    private fun run(text: String, extra: JSONObject.() -> Unit = {}) = JSONObject().put("text", text).apply(extra)

    private fun block(id: String, type: String, text: String, extra: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().put("id", id).put("type", type).put("text", text)
            .put("runs", JSONArray().put(run(text))).apply(extra)

    private fun obj(id: String, type: String, title: String, props: JSONObject.() -> Unit = {}, blocks: List<JSONObject> = emptyList()): JSONObject =
        JSONObject().put("id", id).put("type_id", type).put("title", title)
            .put("properties", JSONObject().apply(props).put("blocks", JSONArray(blocks)))
            .put("links", JSONArray()).put("hlc", HLC)
            .put("created_at", "2026-11-10T09:00:00.000Z").put("updated_at", "2026-11-12T09:00:00.000Z")

    private fun objects(today: String): List<JSONObject> = listOf(
        obj("daily-$today", "journal", today, blocks = listOf(
            block("d1", "paragraph", "Read chapter 4 of The Name of the Wind and noted ideas for Project Y."),
            block("d2", "todo", "Llamar a Marcos") { put("checked", false) },
            block("d3", "paragraph", "Idea: oak for the legs, pine for the top."),
        )),
        obj("n-mesa", "note", "Mesa de roble", { put("pinned", true); put("icon", JSONObject().put("emoji", "🪵")) }, listOf(
            block("m1", "heading", "Despiece") { put("level", 1) },
            block("m2", "paragraph", "Measurements for the workshop table, with 2 mm of play per piece."),
            block("m3", "table", "") {
                put("align", JSONArray(listOf("left", "right", "left")))
                put("rows", JSONArray().put(JSONArray().put(run("Pieza")).put(run("Cant.")).put(run("Madera")))
                    .put(JSONArray().put(run("Patas")).put(run("4")).put(run("Roble")))
                    .put(JSONArray().put(run("Rails")).put(run("6")).put(run("Pine"))))
            },
            block("m4", "callout", "Height = your wrist with the arm hanging (≈ 85 cm).") { put("callout_type", "INFO"); put("emoji", "💡") },
            block("m5", "heading", "Pasos") { put("level", 2) },
            block("m6", "numbered_list", "Escuadrar y cepillar todas las piezas") { put("number", 1) },
            block("m7", "numbered_list", "Espigas en las patas") { put("number", 2) },
            block("m8", "bullet_list", "Try it first on a pine offcut") { put("indent", 2) },
            block("m9", "heading", "Pendiente") { put("level", 2) },
            block("m10", "todo", "Comprar la haya") { put("checked", true) },
            block("m11", "todo", "Pedirle la sierra de calar a Marcos") { put("checked", false) },
            block("m12", "toggle", "Opciones de acabado") { put("expanded", true) },
            block("m13", "quote", "Mide dos veces, corta una."),
            block("m14", "code", "def volumen(l, a, e):\n    return l * a * e / 1e6") { put("language", "python") },
            block("m15", "divider", ""),
            block("m16", "paragraph", "Beech moves a lot with humidity: let it acclimatize for two weeks.") { put("block_color", "red") },
        )),
        obj("p-marcos", "person", "Marcos Vidal", { put("icon", JSONObject().put("emoji", "🧑")); put("email", "marcos@example.org") },
            listOf(block("pm1", "paragraph", "Carpintero. Me presta la sierra de calar."))),
        obj("b-viento", "book", "El nombre del viento", { put("author", JSONArray(listOf("Patrick Rothfuss"))); put("rating", 5) },
            listOf(block("bv1", "paragraph", "Chapter 4: music and the names of things."))),
        obj("w-roble", "weblink", "Oak guide for furniture", { put("url", "https://example.org/oak") }),
        obj("t-sierra", "task", "Pedirle la sierra a Marcos", { put("status", "todo"); put("due_date", today) }),
        obj("t-haya", "task", "Comprar la haya", { put("status", "done"); put("due_date", today) }),
        obj("t-leer", "task", "Read 30 pages", { put("status", "in_progress"); put("due_date", today) }),
        obj("t-prop", "task", "Revisar propuesta de Proyecto Y", { put("status", "todo"); put("due_date", LocalDate.now().minusDays(1).toString()) }),
        obj("pr-y", "project", "Proyecto Y", { put("icon", JSONObject().put("emoji", "🚀")) },
            listOf(block("py1", "paragraph", "Workshop redesign: plans, budget and schedule."))),
    )
}
