package org.yaop.ui.habits

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.DayRecord
import org.yaop.core.model.PkmObject
import org.yaop.core.model.HabitMath
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.theme.AppFonts
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

val HabitGreen = Color(0xFF86E8A8)

private fun PkmObject.habitEmoji() = icon?.emoji?.takeIf { it.isNotBlank() } ?: "🌱"

/** Progress ring (docs/habits.md §4.3): white 14% track, green arc, filled green when done. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HabitRing(habit: PkmObject, date: LocalDate, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = HabitMath.dayProgress(habit, date)
    val skip = HabitMath.record(habit, date) is DayRecord.Skip
    val done = p >= 1f && !skip
    var sheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (sheet) HabitQuickSheet(habit, date) { sheet = false }
    Box(
        modifier = modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = { sheet = true }),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.minDimension * 0.09f
            val inset = w / 2
            val arc = Size(this.size.width - w, this.size.height - w)
            if (done) {
                drawCircle(HabitGreen)
            } else {
                drawArc(Color.White.copy(alpha = 0.14f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
                if (p > 0f && !skip) drawArc(HabitGreen, -90f, 360f * p, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        Text(habit.habitEmoji(), fontSize = (size.value * 0.38f).sp, color = if (done) Color(0xFF0B1F5C) else Color.White)
    }
}

private fun toggle(scope: kotlinx.coroutines.CoroutineScope, repo: PkmRepository, h: PkmObject, date: LocalDate) {
    scope.launch { repo.saveObject(HabitMath.tap(h, date)) }
}

/** Home strip: today's due habits as rings (max 8), under the journal and above the tasks. */
@Composable
fun HabitsStrip(
    habits: List<PkmObject>,
    today: LocalDate,
    repository: PkmRepository,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(AppColors.Surface, shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.habits_today), Modifier.weight(1f), fontSize = 13.sp, color = AppColors.TextSecondary, fontFamily = AppFonts.InterTight)
            Text(
                stringResource(R.string.home_see_all), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Ice,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSeeAll).padding(4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            habits.take(8).forEach { h ->
                Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    HabitRing(h, today, 44.dp, onClick = { toggle(scope, repository, h, today) })
                    Spacer(Modifier.height(4.dp))
                    Text(h.plainTitle, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Full list grouped by time of day; each row: ring, name, last 7 days, streak. */
@Composable
fun HabitsListScreen(
    all: List<PkmObject>,
    repository: PkmRepository,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    val today = LocalDate.now()
    val scope = rememberCoroutineScope()
    val habits = all.filter { with(HabitMath) { it.isHabit } && !it.isTemplate && HabitMath.state(it) != "archived" }
    val groups = listOf("morning" to R.string.habit_slot_morning, "afternoon" to R.string.habit_slot_afternoon,
        "evening" to R.string.habit_slot_evening, "any" to R.string.habit_slot_any)
    Column(Modifier.fillMaxSize().background(org.yaop.ui.theme.AppBackgroundBrush.Background).padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.habits_title), Modifier.weight(1f), fontFamily = AppFonts.InterTight, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
            Text(stringResource(R.string.common_close), color = AppColors.Ice, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(8.dp))
        }
        if (habits.isEmpty()) {
            Text(stringResource(R.string.habits_empty), color = AppColors.TextSecondary, modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            groups.forEach { (slot, label) ->
                val rows = habits.filter { HabitMath.slot(it) == slot || (slot == "any" && HabitMath.slot(it) !in groups.map { g -> g.first }) }
                    .sortedBy { it.plainTitle.lowercase() }
                if (rows.isNotEmpty()) {
                    item(key = "h-$slot") { Text(stringResource(label), fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)) }
                    items(rows, key = { it.id }) { h ->
                        val shape = RoundedCornerShape(14.dp)
                        Row(
                            Modifier.fillMaxWidth().clip(shape).background(AppColors.Surface, shape).clickable { onOpen(h.id) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HabitRing(h, today, 44.dp, onClick = { toggle(scope, repository, h, today) })
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(h.plainTitle, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (i in 6 downTo 0) {
                                        val d = today.minusDays(i.toLong())
                                        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dayColor(h, d)))
                                    }
                                }
                            }
                            Text("${HabitMath.streak(h, today)} 🔥", fontSize = 13.sp, color = AppColors.TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

private fun dayColor(h: PkmObject, d: LocalDate): Color {
    val p = HabitMath.dayProgress(h, d)
    return when {
        HabitMath.record(h, d) is DayRecord.Skip -> Color.White.copy(alpha = 0.07f)
        p >= 1f -> HabitGreen
        p > 0f -> HabitGreen.copy(alpha = 0.35f)
        else -> Color.White.copy(alpha = 0.07f)
    }
}

/** Habit object header: today's ring, streak / best / rate and a 26-week heat calendar (tap a day to log it). */
@Composable
fun HabitDetailPanel(habit: PkmObject, repository: PkmRepository, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(16.dp)
    val unit = HabitMath.unit(habit)
    val cur = (HabitMath.record(habit, today) as? DayRecord.Done)?.amount ?: 0.0
    Column(modifier.fillMaxWidth().clip(shape).background(AppColors.Surface, shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HabitRing(habit, today, 72.dp, onClick = { toggle(scope, repository, habit, today) })
            Spacer(Modifier.width(16.dp))
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${HabitMath.streak(habit, today)}", fontFamily = AppFonts.InterTight, fontSize = 34.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.habit_days), Modifier.padding(bottom = 6.dp), color = AppColors.Ice, fontSize = 14.sp)
                }
                if (HabitMath.kind(habit) != "check") {
                    Text("${cur.toInt()} / ${HabitMath.goal(habit).toInt()} $unit", fontSize = 13.sp, color = AppColors.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(stringResource(R.string.habit_best), "${HabitMath.bestStreak(habit, today)}")
            Stat(stringResource(R.string.habit_rate30), "${(HabitMath.rate30(habit, today) * 100).toInt()}%")
            Stat(stringResource(R.string.habit_total), "${HabitMath.total(habit)}")
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.horizontalScroll(rememberScrollState(Int.MAX_VALUE)), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val first = today.with(DayOfWeek.MONDAY).minusWeeks(25)
            for (w in 0 until 26) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (dow in 0 until 7) {
                        val d = first.plusWeeks(w.toLong()).plusDays(dow.toLong())
                        val future = d.isAfter(today)
                        Box(
                            Modifier.size(11.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (future) Color.Transparent else dayColor(habit, d))
                                .clickable(enabled = !future) { toggle(scope, repository, habit, d) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, fontFamily = AppFonts.InterTight, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
        Text(label, fontSize = 12.sp, color = AppColors.TextSecondary)
    }
}

/** Long press: exact amount / minutes, rest day (skip) or clear that day. */
@Composable
private fun HabitQuickSheet(habit: PkmObject, date: LocalDate, onDismiss: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as org.yaop.YaopApp
    val scope = rememberCoroutineScope()
    val cur = (HabitMath.record(habit, date) as? DayRecord.Done)?.amount
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(cur?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "") }
    fun save(h: PkmObject) { scope.launch { app.repository.saveObject(h) }; onDismiss() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(habit.plainTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (HabitMath.kind(habit) != "check") androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                    label = { Text(HabitMath.unit(habit).ifBlank { stringResource(R.string.habit_amount) }) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton({ save(HabitMath.setSkip(habit, date, true)) }) { Text(stringResource(R.string.habit_skip)) }
                    androidx.compose.material3.TextButton({ save(HabitMath.setAmount(HabitMath.setSkip(habit, date, false), date, null)) }) { Text(stringResource(R.string.habit_clear)) }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton({
                val v = text.toDoubleOrNull()
                save(if (HabitMath.kind(habit) == "check") HabitMath.setAmount(habit, date, 1.0) else HabitMath.setAmount(habit, date, v))
            }) { Text(stringResource(R.string.habit_save)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Journal section "Habits" of a day: those due that date with their state; tap logs/corrects that day. */
@Composable
fun DailyHabitsSection(date: LocalDate, repository: PkmRepository, modifier: Modifier = Modifier) {
    val all by repository.getAllActiveObjects().collectAsState(initial = emptyList<PkmObject>())
    val habits = HabitMath.dueOn(all, date)
    if (habits.isEmpty()) return
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(stringResource(R.string.home_habits), fontSize = 13.sp, color = AppColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            habits.forEach { h ->
                Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    HabitRing(h, date, 44.dp, onClick = { toggle(scope, repository, h, date) })
                    Text(h.plainTitle, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

