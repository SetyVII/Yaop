package org.yaop.core

import org.yaop.core.model.*
import org.yaop.core.model.HabitMath
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HabitMathTest {
    private val today = LocalDate.of(2026, 11, 13)
    private fun habit(vararg p: Pair<String, PropertyValue>) =
        PkmObject(id = "h", typeId = CanonicalTypeIds.HABIT, title = "x".toTextSpan(), properties = mapOf(*p))
    private fun done(d: LocalDate) = HabitKeys.dayKey(d) to PropertyValue.Checkbox(true)

    @Test fun streakCountsBackFromYesterdayAndTodayAdds() {
        val h = habit(done(today.minusDays(1)), done(today.minusDays(2)), done(today.minusDays(4)))
        assertEquals(2, HabitMath.streak(h, today))
        assertEquals(3, HabitMath.streak(h.copy(properties = h.properties + done(today)), today))
    }

    @Test fun skipDoesNotBreakNorCount() {
        val h = habit(done(today.minusDays(1)), HabitKeys.dayKey(today.minusDays(2)) to PropertyValue.Select("skip"), done(today.minusDays(3)))
        assertEquals(2, HabitMath.streak(h, today))
    }

    @Test fun countGoalAndTapWrap() {
        var h = habit(HabitKeys.KIND to PropertyValue.Select("count"), HabitKeys.GOAL to PropertyValue.Number(2.0))
        h = HabitMath.tap(h, today); assertFalse(HabitMath.isMet(h, today))
        h = HabitMath.tap(h, today); assertTrue(HabitMath.isMet(h, today))
        h = HabitMath.tap(h, today); assertEquals(DayRecord.None, HabitMath.record(h, today))
    }

    @Test fun checkToggleRemovesKey() {
        var h = habit()
        h = HabitMath.tap(h, today); assertTrue(h.properties.containsKey("d:2026-11-13"))
        h = HabitMath.tap(h, today); assertFalse(h.properties.containsKey("d:2026-11-13"))
    }

    @Test fun weekdaysOnlyDueOnChosenDays() {
        val h = habit(HabitKeys.FREQ to PropertyValue.Select("weekdays"), HabitKeys.DAYS to PropertyValue.MultiSelect(listOf("fri")))
        assertTrue(HabitMath.isDue(h, today)) // 2026-11-13 is a Friday
        assertFalse(HabitMath.isDue(h, today.minusDays(1)))
    }

    @Test fun perWeekStreak() {
        val h = habit(HabitKeys.FREQ to PropertyValue.Select("per_week"), HabitKeys.TIMES to PropertyValue.Number(2.0),
            done(LocalDate.of(2026, 11, 2)), done(LocalDate.of(2026, 11, 4)), done(LocalDate.of(2026, 11, 9)), done(LocalDate.of(2026, 11, 10)))
        assertEquals(2, HabitMath.streak(h, today))
    }
}
