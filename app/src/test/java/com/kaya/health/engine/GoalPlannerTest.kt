package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GoalPlannerTest {
    private val me = Profile(heightCm = 175.0, age = 30, sex = Sex.MALE)
    private val today = LocalDate.of(2026, 11, 1)

    @Test fun fastPaceIsHeldAtTheCalorieFloor() {
        val g = GoalSettings(100.0, 1.5, today, 115.0)
        val p = GoalPlanner.plan(me, g, 115.0, tdee = 3000.0, trendKgPerWeek = null, avgIntake = null, today = today)
        assertEquals(37.6, p.bmi, 0.1)
        assertEquals(1.15, p.safeRateKg, 0.001)
        // 1.5 kg/week needs 1,650 kcal/day deficit → 1,350 intake, below the 1,500 floor
        assertEquals(1500.0, p.dailyTarget, 0.5)
        assertEquals(1.36, p.achievableRateKg, 0.01)
        assertEquals(LocalDate.of(2027, 1, 10), p.plannedDate) // 15 kg / 1.5 = 10 weeks = 70 days
        assertTrue(p.notes.any { it.contains("safe minimum") })
    }

    @Test fun twoKgPerWeekFlagged() {
        val p = GoalPlanner.plan(me, GoalSettings(100.0, 2.0, today, 115.0), 115.0, 3000.0, null, null, today)
        assertTrue(p.notes.first().contains("very aggressive"))
    }

    @Test fun safePaceTarget() {
        val p = GoalPlanner.plan(me, GoalSettings(100.0, 1.0, today, 115.0), 115.0, 3000.0, null, null, today)
        assertEquals(3000 - 1100.0, p.dailyTarget, 1.0)
        assertTrue(p.notes.first().contains("safe, sustainable"))
    }

    @Test fun adaptsWhenBehind() {
        // 4 weeks into a 1 kg/week plan but only lost 2 kg → target tightens
        val start = today.minusWeeks(4)
        val p = GoalPlanner.plan(me, GoalSettings(100.0, 1.0, start, 115.0), 113.0, 3000.0, -0.5, 2500.0, today)
        assertTrue(p.dailyTarget < 1900.0)
        assertEquals(GoalStatus.BEHIND, p.status)
    }

    @Test fun done() {
        val p = GoalPlanner.plan(me, GoalSettings(100.0, 1.0, today, 115.0), 99.5, 2800.0, -1.0, null, today)
        assertEquals(GoalStatus.DONE, p.status)
        assertEquals(1.0, p.progress, 0.0)
    }

    @Test fun gainingPlansASurplus() {
        // 60 kg → 66 kg at 0.5 kg/week: 12 weeks, ~550 kcal/day over a 2,400 burn
        val p = GoalPlanner.plan(me, GoalSettings(66.0, 0.5, today, 60.0), 60.0, 2400.0, null, null, today)
        assertTrue(p.gaining)
        assertEquals(LocalDate.of(2027, 1, 24), p.plannedDate)
        assertEquals(2400 + 6 * 7700.0 / 84, p.dailyTarget, 2.0)
        assertTrue(p.dailyTarget > p.tdee)
        assertTrue(p.notes.first().contains("sensible gaining pace"))
    }

    @Test fun gainingProgressGoesUp() {
        val start = today.minusWeeks(4)
        val p = GoalPlanner.plan(me, GoalSettings(66.0, 0.5, start, 60.0), 62.0, 2400.0, 0.5, null, today)
        assertEquals(2.0 / 6.0, p.progress, 0.01)
        assertEquals(62.0, p.planKgToday, 0.01)
    }
}
