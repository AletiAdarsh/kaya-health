package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CaloriesTest {
    private val kg = 100.0
    private val heightCm = 175.0

    @Test fun walk30minAt5kmh() {
        // 5 km/h = 83.3 m/min -> VO2 11.83 ml/kg/min -> 5.92 kcal/min at 100 kg
        val e = Calories.estimate(SessionInput(ActivityKind.WALK, 30.0, 2500.0, null), kg, heightCm)
        assertEquals(178.0, e.totalKcal, 2.0)
        assertEquals(125.0, e.activeKcal, 2.0)
        assertEquals(5.0, e.speedKmh!!, 0.01)
    }

    @Test fun workingShowsTheSameNumbersAsTheResult() {
        val e = Calories.estimate(SessionInput(ActivityKind.WALK, 30.0, 2500.0, null), kg, heightCm)
        assertEquals(5, e.working.size) // distance, speed, oxygen, total, extra
        assertTrue(e.working[1], e.working[1].contains("83.3 m/min") && e.working[1].contains("5.0 km/h"))
        assertTrue(e.working[2], e.working[2].contains("11.83"))
        assertTrue(e.working[3], e.working[3].endsWith("= 178 kcal"))
        assertTrue(e.working[4], e.working[4].endsWith("= 125 kcal"))
        val fromSteps = Calories.estimate(SessionInput(ActivityKind.WALK, 30.0, null, 3000), kg, heightCm)
        assertTrue(fromSteps.working[0], fromSteps.working[0].startsWith("Distance: 3,000 steps × 0.73 m stride"))
    }

    @Test fun busRideLoggedAsCyclingIsNotCounted() {
        // A bus ride auto-detected as "CYCLE": no HR shared, no distance
        val noHr = SessionRules.judge(ActivityKind.CYCLE, movementMeasured = false, hrAvg = null, typicalHr = 90.0, override = null)
        assertTrue(!noHr.counted)
        // "CYCLE" with avg HR 104 on a day averaging 101: just sitting
        val normalHr = SessionRules.judge(ActivityKind.CYCLE, false, 104, 101.4, null)
        assertTrue(!normalHr.counted)
        // A genuine ride with HR well above normal is counted
        assertTrue(SessionRules.judge(ActivityKind.CYCLE, false, 130, 95.0, null).counted)
        // The user's own choice always wins
        assertTrue(SessionRules.judge(ActivityKind.CYCLE, false, null, 90.0, true).counted)
        assertTrue(!SessionRules.judge(ActivityKind.WALK, true, 140, 90.0, false).counted)
        // Walks with measured distance count even without heart rate
        assertTrue(SessionRules.judge(ActivityKind.WALK, true, null, null, null).counted)
    }

    @Test fun heartRateFormulaMatchesKeytel() {
        // Keytel 2005 (male): 30.5 min at avg HR 147, 100 kg, 30 y ≈ 463 kcal; 20 min at HR 127 ≈ 244 kcal
        assertEquals(463.0, Calories.heartRateFormulaKcal(147.0, kg, 30, Sex.MALE, 30.5), 5.0)
        assertEquals(244.0, Calories.heartRateFormulaKcal(127.0, kg, 30, Sex.MALE, 20.0), 5.0)
    }

    @Test fun aHeartRateGuessOfThatSizeMeansJogging() {
        // Jogging ~6.5 km/h for 30.5 min is what the lab equation needs to reach ~385 kcal at 100 kg
        val dist = 6.5 * 1000 * 30.5 / 60
        val e = Calories.estimate(SessionInput(ActivityKind.RUN, 30.5, dist, null), kg, heightCm)
        assertEquals(385.0, e.totalKcal, 25.0)
    }

    @Test fun stepsFallbackWhenNoDistance() {
        val e = Calories.estimate(SessionInput(ActivityKind.WALK, 30.0, null, 3300), kg, heightCm)
        assertNotNull(e.distanceM)
        assertTrue(e.method.contains("steps"))
    }

    @Test fun walkTooFastIsTreatedAsRunning() {
        val e = Calories.estimate(SessionInput(ActivityKind.WALK, 30.0, 5000.0, null), kg, heightCm) // 10 km/h
        assertTrue(e.method.contains("running"))
    }

    @Test fun bmrMifflin() {
        assertEquals(1948.75, Calories.bmr(100.0, 175.0, 30, Sex.MALE), 0.5)
    }

    @Test fun observedBurnFromIntakeAndWeight() {
        // Eat 2200/day for 28 days, lose 0.5 kg/week exactly -> real burn = 2200 + 0.5*7700/7 = 2750
        val today = LocalDate.of(2026, 10, 31)
        val weights = (0 until 28).map { i -> DayWeight(today.minusDays(27L - i), 100.0 - 0.5 / 7 * i) }
        val intakes = (1..28).map { i -> DayIntake(today.minusDays(i.toLong()), 2200.0) }
        val est = RealBurn.estimate(weights, intakes, 3000.0, today)
        assertEquals(2750.0, est.observedTdee!!, 1.0)
        assertEquals(-0.5, est.weeklyChangeKg!!, 0.001)
        assertTrue(est.confidence > 0.9)
    }

    @Test fun notEnoughDataFallsBackToFormula() {
        val today = LocalDate.of(2026, 10, 31)
        val est = RealBurn.estimate(listOf(DayWeight(today, 100.0)), emptyList(), 2900.0, today)
        assertNull(est.observedTdee)
        assertEquals(2900.0, est.tdee, 0.0)
    }

    @Test fun trendSmoothsWaterSpikes() {
        val d = LocalDate.of(2026, 10, 1)
        val pts = RealBurn.trend(listOf(DayWeight(d, 100.0), DayWeight(d.plusDays(1), 102.0), DayWeight(d.plusDays(2), 100.0)))
        assertEquals(3, pts.size)
        assertTrue(pts[1].trend < 100.5) // a +2 kg spike moves the trend only ~0.2 kg
    }
}
