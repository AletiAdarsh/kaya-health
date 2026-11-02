package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class WalksTest {
    private val day = LocalDateTime.of(2026, 11, 2, 0, 0)

    /** 30-s readings: resting 75 all day, raised to [walkHr] from [from] for [minutes]. */
    private fun hrDay(from: LocalDateTime, minutes: Int, walkHr: Int): List<Pair<LocalDateTime, Int>> =
        (0 until 2880).map { i ->
            val t = day.plusSeconds(i * 30L)
            t to if (!t.isBefore(from) && t.isBefore(from.plusMinutes(minutes.toLong()))) walkHr else 75
        }

    @Test fun findsAMorningWalk() {
        val steps = IntArray(24).also { it[7] = 3600 }
        val w = WalkDetector.detect(hrDay(day.plusHours(7).plusMinutes(10), 35, 120), steps, restingHr = 65.0, age = 30)
        assertEquals(1, w.size)
        assertEquals(35.0, w[0].minutes, 1.0)
        assertTrue(w[0].steps in 3000..3600)
        assertEquals(120, w[0].avgHr)
    }

    @Test fun ignoresRaisedHeartRateWithoutSteps() {
        // Stress or a hot bus ride: heart rate up, hardly any steps.
        val steps = IntArray(24).also { it[14] = 150 }
        assertTrue(WalkDetector.detect(hrDay(day.plusHours(14), 30, 115), steps, 65.0, 30).isEmpty())
    }

    @Test fun ignoresShortBursts() {
        val steps = IntArray(24).also { it[9] = 900 }
        assertTrue(WalkDetector.detect(hrDay(day.plusHours(9), 6, 125), steps, 65.0, 30).isEmpty())
    }

    @Test fun walkCaloriesUseWeightAndSpeedNotHeartRate() {
        // 30 min, 3,300 steps at 175 cm (~0.73 m stride) ≈ 2.5 km ≈ 5 km/h; at 100 kg about 130 kcal above resting.
        val w = Walk(day.plusHours(7), day.plusHours(7).plusMinutes(30), 3300, 118, 130, detected = false)
        assertEquals(5.0, w.speedKmh(175.0), 0.4)
        assertTrue(w.extraKcal(100.0, 175.0) in 105.0..155.0)
    }
}
