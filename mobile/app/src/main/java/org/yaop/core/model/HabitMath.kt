package org.yaop.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields

/** Habit property keys (spec/object.md, habits). Day records are `d:YYYY-MM-DD`. */
object HabitKeys {
    const val KIND = "habit_kind"      // check | count | time
    const val GOAL = "habit_goal"
    const val UNIT = "habit_unit"
    const val FREQ = "habit_freq"      // daily | weekdays | per_week | per_month
    const val DAYS = "habit_days"      // mon..sun
    const val TIMES = "habit_times"
    const val SLOT = "habit_slot"      // morning | afternoon | evening | any
    const val START = "habit_start"
    const val STATE = "habit_state"    // active | paused | archived
    const val DAY_PREFIX = "d:"
    const val SKIP = "skip"

    fun dayKey(date: LocalDate) = DAY_PREFIX + date
}

sealed interface DayRecord {
    data object None : DayRecord
    data class Done(val amount: Double) : DayRecord
    data object Skip : DayRecord
}

/** Pure habit calculations, computed on display only (spec §24: no stored streaks). */
object HabitMath {
    private fun PkmObject.sel(key: String): String? = (property(key) as? PropertyValue.Select)?.selectedOptionId
    private fun PkmObject.num(key: String): Double? = (property(key) as? PropertyValue.Number)?.value

    val PkmObject.isHabit get() = typeId == CanonicalTypeIds.HABIT
    fun kind(h: PkmObject) = h.sel(HabitKeys.KIND) ?: "check"
    fun freq(h: PkmObject) = h.sel(HabitKeys.FREQ) ?: "daily"
    fun state(h: PkmObject) = h.sel(HabitKeys.STATE) ?: "active"
    fun slot(h: PkmObject) = h.sel(HabitKeys.SLOT) ?: "any"
    fun goal(h: PkmObject) = (h.num(HabitKeys.GOAL) ?: 1.0).coerceAtLeast(1.0)
    fun times(h: PkmObject) = (h.num(HabitKeys.TIMES) ?: 3.0).toInt().coerceAtLeast(1)
    fun unit(h: PkmObject) = (h.property(HabitKeys.UNIT) as? PropertyValue.RichText)?.spans?.joinToString("") { (it as? InlineSpan.Text)?.text ?: "" } ?: ""
    fun start(h: PkmObject): LocalDate? =
        (h.property(HabitKeys.START) as? PropertyValue.DateRange)?.startDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private fun days(h: PkmObject): Set<String> = when (val v = h.property(HabitKeys.DAYS)) {
        is PropertyValue.MultiSelect -> v.selectedOptionIds.toSet()
        is PropertyValue.ObjectRelation -> v.targetObjectIds.toSet()
        else -> emptySet()
    }

    fun record(h: PkmObject, date: LocalDate): DayRecord = when (val v = h.property(HabitKeys.dayKey(date))) {
        is PropertyValue.Checkbox -> if (v.checked) DayRecord.Done(1.0) else DayRecord.None
        is PropertyValue.Number -> v.value?.let { DayRecord.Done(it) } ?: DayRecord.None
        is PropertyValue.Select -> if (v.selectedOptionId == HabitKeys.SKIP) DayRecord.Skip else DayRecord.None
        is PropertyValue.RichText -> if (unitText(v) == HabitKeys.SKIP) DayRecord.Skip else DayRecord.None
        else -> DayRecord.None
    }
    private fun unitText(v: PropertyValue.RichText) = v.spans.joinToString("") { (it as? InlineSpan.Text)?.text ?: "" }

    private fun code(d: DayOfWeek) = d.name.take(3).lowercase()

    /** Is a record expected on [date]? (per_week/per_month are flexible: any day counts.) */
    fun isDue(h: PkmObject, date: LocalDate): Boolean {
        if (state(h) != "active") return false
        start(h)?.let { if (date.isBefore(it)) return false }
        return when (freq(h)) {
            "weekdays" -> code(date.dayOfWeek) in days(h)
            else -> true
        }
    }

    /** 0..1 progress of one day; Skip counts as complete (it never breaks anything). */
    fun dayProgress(h: PkmObject, date: LocalDate): Float = when (val r = record(h, date)) {
        DayRecord.None -> 0f
        DayRecord.Skip -> 1f
        is DayRecord.Done -> if (kind(h) == "check") 1f else (r.amount / goal(h)).toFloat().coerceIn(0f, 1f)
    }

    fun isMet(h: PkmObject, date: LocalDate): Boolean = dayProgress(h, date) >= 1f && record(h, date) !is DayRecord.Skip

    private fun periodStart(h: PkmObject, d: LocalDate): LocalDate = when (freq(h)) {
        "per_week" -> d.with(DayOfWeek.MONDAY)
        "per_month" -> d.withDayOfMonth(1)
        else -> d
    }
    private fun prevPeriod(h: PkmObject, ps: LocalDate): LocalDate = when (freq(h)) {
        "per_week" -> ps.minusWeeks(1)
        "per_month" -> ps.minusMonths(1)
        else -> ps.minusDays(1)
    }
    private fun periodLen(h: PkmObject, ps: LocalDate): Int = when (freq(h)) {
        "per_week" -> 7
        "per_month" -> ps.lengthOfMonth()
        else -> 1
    }
    private fun periodMet(h: PkmObject, ps: LocalDate): Boolean {
        if (freq(h) == "daily" || freq(h) == "weekdays") return isMet(h, ps)
        val n = (0 until periodLen(h, ps)).count { isMet(h, ps.plusDays(it.toLong())) }
        return n >= times(h)
    }

    /** Consecutive met periods counting back from the previous one; the current one adds if already met. */
    fun streak(h: PkmObject, today: LocalDate): Int {
        val limit = start(h) ?: today.minusDays(3650)
        var ps = periodStart(h, today)
        var n = 0
        if (periodMet(h, ps)) n++
        ps = prevPeriod(h, ps)
        var guard = 0
        while (!ps.plusDays(periodLen(h, ps).toLong() - 1).isBefore(limit) && guard++ < 4000) {
            val daily = freq(h) == "daily" || freq(h) == "weekdays"
            if (daily && (!isDue(h, ps) || record(h, ps) is DayRecord.Skip)) { ps = prevPeriod(h, ps); continue }
            if (!periodMet(h, ps)) break
            n++
            ps = prevPeriod(h, ps)
        }
        return n
    }

    /** Met / due over the last 30 days (skips excluded); per_* uses days done vs. expected. */
    fun rate30(h: PkmObject, today: LocalDate): Float {
        var due = 0; var met = 0
        for (i in 0 until 30) {
            val d = today.minusDays(i.toLong())
            if (!isDue(h, d) || record(h, d) is DayRecord.Skip) continue
            due++; if (isMet(h, d)) met++
        }
        if (freq(h) == "per_week" || freq(h) == "per_month") {
            val perDay = if (freq(h) == "per_week") times(h) / 7f else times(h) / 30f
            val expected = (due * perDay).coerceAtLeast(1f)
            return (met / expected).coerceAtMost(1f)
        }
        return if (due == 0) 0f else met.toFloat() / due
    }

    fun bestStreak(h: PkmObject, today: LocalDate): Int {
        val first = start(h) ?: h.properties.keys.filter { it.startsWith(HabitKeys.DAY_PREFIX) }
            .mapNotNull { runCatching { LocalDate.parse(it.removePrefix(HabitKeys.DAY_PREFIX)) }.getOrNull() }.minOrNull() ?: return 0
        var best = 0; var run = 0
        var d = first
        val daily = freq(h) == "daily" || freq(h) == "weekdays"
        if (!daily) return streak(h, today)
        while (!d.isAfter(today)) {
            if (isDue(h, d) && record(h, d) !is DayRecord.Skip) {
                if (isMet(h, d)) { run++; best = maxOf(best, run) } else if (d != today) run = 0
            }
            d = d.plusDays(1)
        }
        return best
    }

    fun total(h: PkmObject): Int = h.properties.keys.count { it.startsWith(HabitKeys.DAY_PREFIX) && record(h, LocalDate.parse(it.removePrefix(HabitKeys.DAY_PREFIX).take(10))) is DayRecord.Done }

    // ---- writes (one key per day, so merges never collide) ----

    private fun withRecord(h: PkmObject, date: LocalDate, v: PropertyValue?): PkmObject {
        val key = HabitKeys.dayKey(date)
        val props = if (v == null) h.properties - key else h.properties + (key to v)
        return h.copy(properties = props, updatedAt = java.time.Instant.now().toString())
    }

    /** Tap: check toggles; count/time adds [step] (wraps to cleared once the goal is exceeded by a tap at goal). */
    fun tap(h: PkmObject, date: LocalDate, step: Double = 1.0): PkmObject {
        if (kind(h) == "check") {
            return withRecord(h, date, if (isMet(h, date)) null else PropertyValue.Checkbox(true))
        }
        val cur = (record(h, date) as? DayRecord.Done)?.amount ?: 0.0
        return if (cur >= goal(h)) withRecord(h, date, null) else withRecord(h, date, PropertyValue.Number(cur + step))
    }

    fun setAmount(h: PkmObject, date: LocalDate, amount: Double?): PkmObject =
        withRecord(h, date, amount?.let { if (kind(h) == "check") PropertyValue.Checkbox(it > 0) else PropertyValue.Number(it) })

    fun setSkip(h: PkmObject, date: LocalDate, skip: Boolean): PkmObject =
        withRecord(h, date, if (skip) PropertyValue.Select(HabitKeys.SKIP) else null)

    fun dueOn(all: List<PkmObject>, date: LocalDate): List<PkmObject> = dueToday(all, date)

    /** Active habits due today, ordered by slot (morning, afternoon, evening, any) then title. */
    fun dueToday(all: List<PkmObject>, today: LocalDate): List<PkmObject> {
        val order = mapOf("morning" to 0, "afternoon" to 1, "evening" to 2, "any" to 3)
        return all.filter { it.isHabit && !it.isTemplate && isDue(it, today) }
            .sortedWith(compareBy({ order[slot(it)] ?: 3 }, { it.plainTitle.lowercase() }))
    }
}
