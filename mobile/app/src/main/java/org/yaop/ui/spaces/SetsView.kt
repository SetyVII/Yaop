package org.yaop.ui.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectType
import org.yaop.core.query.QueryEngine
import org.yaop.core.query.SavedSets
import org.yaop.core.repository.PkmRepository
import org.yaop.core.sync.SyncContractCodec
import org.yaop.ui.common.DesignModalBottomSheet
import org.yaop.ui.common.ObjectCard
import org.yaop.ui.common.localizedName
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Operators offered by the editor (spec/query.md), with what their value field expects. */
private data class OpSpec(val op: String, val label: Int, val needs: String)

private val OPS = listOf(
    OpSpec("eq", R.string.set_op_eq, "text"),
    OpSpec("neq", R.string.set_op_neq, "text"),
    OpSpec("contains", R.string.set_op_contains, "text"),
    OpSpec("not_contains", R.string.set_op_not_contains, "text"),
    OpSpec("has_any", R.string.set_op_has_any, "list"),
    OpSpec("has_none", R.string.set_op_has_none, "list"),
    OpSpec("gte", R.string.set_op_gte, "number"),
    OpSpec("lte", R.string.set_op_lte, "number"),
    OpSpec("before", R.string.set_op_before, "date"),
    OpSpec("after", R.string.set_op_after, "date"),
    OpSpec("on", R.string.set_op_on, "date"),
    OpSpec("empty", R.string.set_op_empty, ""),
    OpSpec("not_empty", R.string.set_op_not_empty, ""),
    OpSpec("is_true", R.string.set_op_is_true, ""),
)

private fun opSpec(op: String) = OPS.find { it.op == op } ?: OPS.first()

/** Text typed for a filter value → the JSON the contract expects for that operator. */
private fun parseValue(op: String, text: String): Any? {
    val t = text.trim()
    return when (opSpec(op).needs) {
        "" -> null
        "list" -> JSONArray(t.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        "number" -> t.toDoubleOrNull() ?: 0.0
        "text" -> t.toDoubleOrNull()?.takeIf { Regex("""-?\d+(\.\d+)?""").matches(t) } ?: t
        else -> t
    }
}

private fun valueText(v: Any?): String = when (v) {
    null, JSONObject.NULL -> ""
    is JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }
    is Double -> if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    else -> v.toString()
}

/** Display text of one field of an object (table cells): lists joined, object refs as titles. */
private fun cellText(wire: JSONObject, prop: String, titleOf: (String) -> String?): String {
    val v = QueryEngine.fieldValue(wire, prop) ?: return ""
    fun one(x: Any?): String = when (x) {
        is String -> if (prop == "@updated_at" || prop == "@created_at") x.take(10) else titleOf(x) ?: x
        is Boolean -> if (x) "✓" else ""
        is Number -> valueText(x.toDouble())
        is JSONObject -> listOfNotNull(x.optString("start").takeIf { it.isNotEmpty() }, x.optString("end").takeIf { it.isNotEmpty() }).joinToString(" → ")
        else -> ""
    }
    return if (v is JSONArray) (0 until v.length()).map { one(v.opt(it)) }.filter { it.isNotEmpty() }.joinToString(", ") else one(v)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SetsView(
    repository: PkmRepository,
    allTypes: List<ObjectType>,
    onOpenObject: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sets by repository.getSets().collectAsState(initial = emptyList())
    val active by repository.getAllActiveObjects().collectAsState(initial = repository.getCachedAllActiveObjects())
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = sets.find { it.id == selectedId } ?: sets.firstOrNull()
    val query = remember(selected) { selected?.let { SavedSets.queryOf(it) } ?: JSONObject().put("version", 1) }
    val viewKind = selected?.let { SavedSets.activeKindOf(it) } ?: "gallery"
    var items by remember { mutableStateOf<List<PkmObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }
    val typeOptions = remember(allTypes) { allTypes.filter { it.id != DefaultTypes.SET.id } }
    val newSetName = stringResource(R.string.set_new_name)

    LaunchedEffect(selected?.id, selected?.wireProps, active, rev) {
        val id = selected?.id ?: run { items = emptyList(); return@LaunchedEffect }
        val r = repository.runSet(id)
        items = r.getOrDefault(emptyList())
        error = (r.exceptionOrNull() as? QueryEngine.QueryException)?.code
    }

    fun commit(q: JSONObject) {
        val id = selected?.id ?: return
        scope.launch { repository.updateSetQuery(id, q); rev++ }
    }

    val typeNames = typeOptions.associate { it.id to it.localizedName() }
    fun typeName(id: String) = typeNames[id] ?: id

    Column(modifier = Modifier.fillMaxSize()) {
        // Saved sets + new
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(sets, key = { it.id }) { s ->
                SetChip(text = s.plainTitle, selected = s.id == selected?.id) { selectedId = s.id }
            }
            item(key = "__new__") {
                SetChip(text = "+ " + stringResource(R.string.set_new), selected = false) {
                    scope.launch {
                        selectedId = repository.createSet(newSetName)
                        showEditor = true
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        if (selected == null) {
            Box(Modifier.fillMaxSize().padding(bottom = 60.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.set_empty_title), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.set_empty_hint), fontSize = 13.sp, color = AppColors.TextSecondary)
                }
            }
            return@Column
        }

        // Header: SET tag, summary, editable title
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(11.dp))
                    .background(Color(0x29DCEC4E))
                    .border(1.dp, Color(0x73DCEC4E), RoundedCornerShape(11.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) { Text("SET", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFDCEC4E), letterSpacing = 1.sp) }
            Spacer(Modifier.width(10.dp))
            val types = query.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val filters = query.optJSONArray("filters")?.length() ?: 0
            val summary = buildList {
                add(if (types.isEmpty()) stringResource(R.string.set_all_types) else types.joinToString(", ") { typeName(it) })
                if (filters > 0) add(pluralStringResource(R.plurals.set_n_filters, filters, filters))
                add(pluralStringResource(R.plurals.set_n_objects, items.size, items.size))
            }.joinToString(" · ")
            Text(summary, modifier = Modifier.weight(1f), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SetChip(text = stringResource(R.string.set_edit_query), selected = showEditor) { showEditor = true }
        }
        Spacer(Modifier.height(6.dp))
        var title by remember(selected.id) { mutableStateOf(selected.plainTitle) }
        BasicTextField(
            value = title,
            onValueChange = { title = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { scope.launch { repository.renameObject(selected.id, title) } }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        // View tabs
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((kind, label) in listOf("gallery" to R.string.set_view_gallery, "table" to R.string.set_view_table, "list" to R.string.set_view_list)) {
                SetChip(text = stringResource(label), selected = viewKind == kind) {
                    scope.launch { repository.setSetActiveView(selected.id, kind) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        val typeById = remember(allTypes) { allTypes.associateBy { it.id } }
        when {
            items.isEmpty() -> Box(Modifier.fillMaxSize().padding(bottom = 60.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (error != null) stringResource(R.string.set_query_error, error!!) else stringResource(R.string.set_no_results),
                    fontSize = 14.sp, color = if (error != null) AppColors.Danger else AppColors.TextSecondary,
                )
            }
            viewKind == "gallery" -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                items(items, key = { it.id }) { obj ->
                    ObjectCard(obj = obj, type = typeById[obj.typeId], onClick = { onOpenObject(obj.id) })
                }
            }
            viewKind == "list" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
                items(items, key = { it.id }) { obj ->
                    val type = typeById[obj.typeId]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.Surface)
                            .clickable { onOpenObject(obj.id) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(obj.icon?.emoji ?: type?.icon?.emoji ?: "📄", fontSize = 18.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(obj.plainTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((type?.localizedName() ?: obj.typeId).uppercase(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextTertiary)
                        }
                    }
                }
            }
            else -> SetTable(query, items, repository, onOpenObject)
        }
    }

    if (showEditor && selected != null) {
        DesignModalBottomSheet(onDismissRequest = { showEditor = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                SectionLabel(stringResource(R.string.home_tab_types))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val types = query.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    typeOptions.forEach { t ->
                        SetChip(text = t.localizedName(), selected = t.id in types) {
                            val next = if (t.id in types) types - t.id else types + t.id
                            commit(JSONObject(query.toString()).put("types", JSONArray(next)))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                SectionLabel(stringResource(R.string.set_filters))
                val filters = query.optJSONArray("filters") ?: JSONArray()
                for (i in 0 until filters.length()) {
                    val f = filters.optJSONObject(i) ?: continue
                    FilterEditorRow(
                        filter = f,
                        onChange = { updated ->
                            val q = JSONObject(query.toString())
                            q.getJSONArray("filters").put(i, updated)
                            commit(q)
                        },
                        onRemove = {
                            val q = JSONObject(query.toString())
                            q.getJSONArray("filters").remove(i)
                            commit(q)
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                SetChip(text = "+ " + stringResource(R.string.set_add_filter), selected = false) {
                    val q = JSONObject(query.toString())
                    val arr = q.optJSONArray("filters") ?: JSONArray().also { q.put("filters", it) }
                    arr.put(JSONObject().put("prop", "@title").put("op", "contains").put("value", ""))
                    commit(q)
                }
                Spacer(Modifier.height(16.dp))
                SectionLabel(stringResource(R.string.set_sort))
                val sort0 = query.optJSONArray("sort")?.optJSONObject(0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QueryInput(
                        value = sort0?.optString("prop").orEmpty(),
                        placeholder = "@updated_at",
                        modifier = Modifier.weight(1f),
                    ) { prop ->
                        val q = JSONObject(query.toString())
                        val dir = sort0?.optString("dir")?.ifEmpty { "asc" } ?: "asc"
                        q.put("sort", if (prop.isBlank()) JSONArray() else JSONArray().put(JSONObject().put("prop", prop.trim()).put("dir", dir)))
                        commit(q)
                    }
                    Spacer(Modifier.width(8.dp))
                    SetChip(text = if (sort0?.optString("dir") == "desc") "desc ↓" else "asc ↑", selected = false) {
                        if (sort0 == null) return@SetChip
                        val q = JSONObject(query.toString())
                        q.getJSONArray("sort").getJSONObject(0).put("dir", if (sort0.optString("dir") == "desc") "asc" else "desc")
                        commit(q)
                    }
                }
                Spacer(Modifier.height(20.dp))
                SetChip(text = stringResource(R.string.set_delete), selected = false) {
                    scope.launch {
                        repository.moveToTrash(selected.id)
                        showEditor = false
                        selectedId = null
                    }
                }
            }
        }
    }
}

@Composable
private fun SetTable(query: JSONObject, items: List<PkmObject>, repository: PkmRepository, onOpenObject: (String) -> Unit) {
    // Columns: title, type, every property the query looks at, and the last edit.
    val columns = remember(query.toString()) {
        val cols = mutableListOf("@title", "@type")
        fun add(p: String?) { if (!p.isNullOrBlank() && p !in cols) cols += p }
        query.optJSONArray("filters")?.let { a -> for (i in 0 until a.length()) add(a.optJSONObject(i)?.optString("prop")) }
        query.optJSONArray("sort")?.let { a -> for (i in 0 until a.length()) add(a.optJSONObject(i)?.optString("prop")) }
        add("@updated_at")
        cols
    }
    val titleCache = remember(items) { HashMap<String, String?>() }
    val titleOf: (String) -> String? = { id -> titleCache.getOrPut(id) { repository.getCachedObject(id)?.plainTitle } }
    val label: @Composable (String) -> String = { p ->
        when (p) {
            "@title" -> stringResource(R.string.set_col_name)
            "@type" -> stringResource(R.string.set_col_type)
            "@updated_at" -> stringResource(R.string.set_col_edited)
            else -> p
        }
    }
    val scroll = rememberScrollState()
    Column(Modifier.horizontalScroll(scroll)) {
        Row(Modifier.background(AppColors.SurfaceElevated).padding(vertical = 8.dp)) {
            columns.forEachIndexed { i, c ->
                Text(label(c).uppercase(), Modifier.width(if (i == 0) 200.dp else 130.dp).padding(horizontal = 10.dp),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextTertiary, maxLines = 1)
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
            items(items, key = { it.id }) { obj ->
                val wire = remember(obj) {
                    SyncContractCodec.toServerJson(obj).apply { put("type_id", obj.typeId) }
                }
                Row(Modifier.clickable { onOpenObject(obj.id) }.padding(vertical = 10.dp)) {
                    columns.forEachIndexed { i, c ->
                        val text = if (c == "@type") {
                            DefaultTypes.ALL.find { it.id == obj.typeId }?.localizedName() ?: obj.typeId
                        } else cellText(wire, c, titleOf)
                        Text(text, Modifier.width(if (i == 0) 200.dp else 130.dp).padding(horizontal = 10.dp),
                            fontSize = if (i == 0) 14.sp else 12.sp,
                            fontFamily = if (i == 0) FontFamily.Default else FontFamily.Monospace,
                            color = if (i == 0) AppColors.TextPrimary else AppColors.TextSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.width((200 + 130 * (columns.size - 1)).dp).height(1.dp).background(AppColors.BorderSubtle))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterEditorRow(filter: JSONObject, onChange: (JSONObject) -> Unit, onRemove: () -> Unit) {
    val op = filter.optString("op", "eq")
    var showOps by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.Surface).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QueryInput(value = filter.optString("prop"), placeholder = "@title", modifier = Modifier.weight(1f)) {
                onChange(JSONObject(filter.toString()).put("prop", it.trim()))
            }
            Spacer(Modifier.width(8.dp))
            SetChip(text = stringResource(opSpec(op).label), selected = showOps) { showOps = !showOps }
            Spacer(Modifier.width(8.dp))
            SetChip(text = "✕", selected = false, onClick = onRemove)
        }
        if (showOps) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OPS.forEach { spec ->
                    SetChip(text = stringResource(spec.label), selected = spec.op == op) {
                        showOps = false
                        val next = JSONObject(filter.toString()).put("op", spec.op)
                        val v = parseValue(spec.op, valueText(filter.opt("value")))
                        if (v == null) next.remove("value") else next.put("value", v)
                        onChange(next)
                    }
                }
            }
        }
        if (opSpec(op).needs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            QueryInput(
                value = valueText(filter.opt("value")),
                placeholder = when (opSpec(op).needs) {
                    "list" -> "a, b, c"
                    "date" -> "today-7"
                    "number" -> "0"
                    else -> stringResource(R.string.set_value)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { text ->
                val next = JSONObject(filter.toString())
                val v = parseValue(op, text)
                if (v == null) next.remove("value") else next.put("value", v)
                onChange(next)
            }
        }
    }
}

/** Small field that commits when the keyboard action is pressed. */
@Composable
private fun QueryInput(value: String, placeholder: String, modifier: Modifier = Modifier, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(AppColors.SurfaceElevated)
            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (text.isEmpty()) Text(placeholder, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextTertiary)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
        color = AppColors.TextTertiary, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SetChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) AppColors.Ice.copy(alpha = 0.16f) else AppColors.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) AppColors.Ice.copy(alpha = 0.6f) else AppColors.BorderSubtle, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) AppColors.TextPrimary else AppColors.TextSecondary, maxLines = 1)
    }
}
