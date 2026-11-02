package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class MetricsTest {
    private val d = LocalDate.of(2026, 3, 9) // a Monday
    private fun hr(a: LocalDateTime, b: LocalDateTime, bpm: (LocalDateTime) -> Int) =
        generateSequence(a) { it.plusSeconds(30) }.takeWhile { it.isBefore(b) }.map { it to bpm(it) }.toList()

    @Test fun heartRateRecovery() {
        val end = d.atTime(18, 30)
        val readings = hr(d.atTime(18, 0), d.atTime(18, 35)) { t -> if (t.isAfter(end)) 112 else 130 }
        assertEquals(18, Metrics.heartRateRecovery(readings, end))
        assertNull(Metrics.heartRateRecovery(emptyList(), end))
    }

    @Test fun nightDip() {
        val day = hr(d.atTime(9, 0), d.atTime(21, 0)) { 90 }
        assertEquals((90 - 76) / 90.0, Metrics.nightDip(day, 76)!!, 1e-9)
        assertNull(Metrics.nightDip(day.take(10), 76))
    }

    @Test fun trimpIgnoresSittingAndGrowsWithIntensity() {
        assertEquals(0.0, Metrics.trimp(List(1000) { 75 }, 70.0, 190.0, true), 1e-9)
        val easy = Metrics.trimp(List(60) { 115 }, 70.0, 190.0, true)
        val hard = Metrics.trimp(List(60) { 150 }, 70.0, 190.0, true)
        assertTrue(hard > easy * 2 && easy > 0)
        // 30 min at 130 bpm, rest 40, max 200 → ≈ 32 (worked example in the TRIMP literature)
        assertEquals(32.0, Metrics.trimp(List(60) { 130 }, 40.0, 200.0, true), 1.5)
    }

    @Test fun loadRatio() {
        val load = (0L..27L).associate { d.minusDays(it) to if (it < 7) 60.0 else 30.0 }
        assertEquals(60.0 / ((7 * 60.0 + 21 * 30.0) / 28), Metrics.loadRatio(load, d)!!, 1e-9)
        assertNull(Metrics.loadRatio(load.filterKeys { it >= d.minusDays(5) }, d))
    }

    private fun night(day: LocalDate, bedH: Int, bedM: Int, hours: Long, hr: Int = 70, br: Double? = 16.0, temp: Double? = 34.5, hrv: Double? = 40.0) =
        day.minusDays(if (bedH >= 12) 1 else 0).atTime(bedH, bedM).let { NightRecord(day, it, it.plusHours(hours), (hours * 60).toInt() - 20, hr, br, temp, hrv) }

    @Test fun sleepStats() {
        val nights = listOf(night(d, 23, 0, 7), night(d.minusDays(1), 0, 0, 7), night(d.minusDays(2), 23, 30, 7))
        assertTrue(Metrics.bedtimeSpread(nights)!! in 20..30)
        assertEquals(3 * 50, Metrics.sleepDebt(nights, d)) // 400 min each vs 450
        // Weekend nights 2 h later than work nights → about 120 min of social jetlag.
        val week = listOf(night(d.minusDays(2), 1, 0, 8), night(d.minusDays(1), 1, 0, 8)) +
            listOf(night(d.minusDays(4), 23, 0, 8), night(d.minusDays(5), 23, 0, 8), night(d.minusDays(6), 23, 0, 8))
        assertEquals(120, Metrics.socialJetlag(week))
    }

    @Test fun strainNeedsTwoSignals() {
        val base = (1L..5L).map { night(d.minusDays(it), 23, 0, 7) }
        assertFalse(Metrics.strain(night(d, 23, 0, 7, hr = 76), base).raised)
        val sick = Metrics.strain(night(d, 23, 0, 7, hr = 77, br = 18.0, temp = 35.1), base)
        assertTrue(sick.raised); assertEquals(3, sick.flags.size)
    }

    @Test fun energyBalance() {
        val (bal, kg) = Metrics.energyBalance(listOf(1300.0, 1500.0, 1400.0), 2400.0)!!
        assertEquals(-1000.0, bal, 1e-9); assertEquals(-7000.0 / 7700, kg, 1e-9)
    }

    @Test fun activeMinutesFollowAcsm() {
        // rest 70, max 190 → moderate from 118 bpm, vigorous from 142
        val (mod, vig) = Metrics.activeMinutes(List(60) { 125 } + List(20) { 150 } + List(100) { 80 }, 70.0, 190.0)
        assertEquals(30, mod); assertEquals(10, vig)
    }

    @Test fun nadirFindsTheLowestHalfHour() {
        val from = d.atTime(0, 0)
        val readings = hr(from, d.atTime(8, 0)) { t -> if (t.hour == 4) 55 else 70 }
        val n = Metrics.heartRateNadir(readings, from, d.atTime(8, 0))!!
        assertEquals(4, n.hour)
    }

    @Test fun rhythmStableVsChaotic() {
        val routine = IntArray(24) { if (it in 8..21) 600 else 0 }
        val (isStable, _) = Metrics.rhythm(List(5) { routine })!!
        assertEquals(1.0, isStable, 1e-9)
        val rnd = java.util.Random(1)
        val chaos = List(5) { IntArray(24) { rnd.nextInt(800) } }
        val (isChaos, ivChaos) = Metrics.rhythm(chaos)!!
        assertTrue(isChaos < 0.5); assertTrue(ivChaos > 1.0)
        assertNull(Metrics.rhythm(listOf(routine)))
    }

    @Test fun activeHoursAndStillStretch() {
        val h = IntArray(24); listOf(9, 10, 14, 20).forEach { h[it] = 400 }
        val (active, still) = Metrics.activeHours(h, 23)
        assertEquals(4, active); assertEquals(5, still) // 15–19
    }

    @Test fun foodTiming() {
        val t = listOf(d.atTime(9, 0), d.atTime(13, 0), d.atTime(21, 30))
        assertEquals(750, Metrics.eatingWindow(t))
        assertEquals(0.5, Metrics.lateShare(listOf(d.atTime(13, 0) to 600.0, d.atTime(21, 0) to 600.0))!!, 1e-9)
        assertEquals(0.0, Metrics.calorieVariation(List(5) { 1500.0 })!!, 1e-9)
        assertEquals(3, Metrics.streak(setOf(d.minusDays(1), d.minusDays(2), d.minusDays(3), d.minusDays(5)), d))
    }
}
