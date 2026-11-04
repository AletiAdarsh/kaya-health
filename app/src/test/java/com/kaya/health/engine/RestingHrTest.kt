package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RestingHrTest {
    private val day = LocalDateTime.of(2026, 11, 4, 0, 0)

    /** A typical morning: ~86 bpm early night, ~72 from 5:00 to 9:00, then a 2.5 km walk out and back at ~155. */
    private fun morning(): List<Pair<LocalDateTime, Int>> = (0 until 2 * 60 * 11).map { i ->
        val m = i / 2
        val bpm = when {
            m < 300 -> 86
            m < 540 -> 72
            m in 593..606 || m in 612..625 -> 155        // out 9:53-10:06, back 10:12-10:25 (4-minute stop between)
            m in 587..592 -> 120                         // warming up
            else -> 92
        }
        day.plusSeconds(30L * i) to bpm
    }

    @Test fun lowestHalfHourNotBatteryLevel() {
        assertEquals(72.0, RestingHr.fromReadings(morning())!!, 0.01)
    }

    @Test fun ignoresDropoutsAndTooFewReadings() {
        val r = morning().toMutableList()
        r[700] = r[700].first to 31                       // strap slipped
        assertEquals(72.0, RestingHr.fromReadings(r)!!, 0.01)
        assertNull(RestingHr.fromReadings(morning().take(30)))
    }

    @Test fun walkIsFoundOnceRestingIsRight() {
        val hourly = IntArray(24).also { it[9] = 1260; it[10] = 2410 }
        val rhr = RestingHr.fromReadings(morning())!!
        val walks = WalkDetector.detect(morning(), hourly, rhr, 24)
        assertEquals(2, walks.size)
        assertTrue(walks.all { it.minutes >= 10 })
        // The old bug: a "resting" of 31 (battery %) hid the walk.
        assertTrue(WalkDetector.detect(morning(), hourly, 31.0, 24).isEmpty())
    }
}

class BurnRangeTest {
    @Test fun rangeSitsAroundTheNumberSkewedUp() {
        // A full day: resting 2,240, total 2,650 → 2,540–2,990.
        val (lo, hi) = Calories.burnRange(2650.0, 2240.0)
        assertEquals(2540.0, lo, 0.01)
        assertEquals(2990.0, hi, 0.01)
    }
}
