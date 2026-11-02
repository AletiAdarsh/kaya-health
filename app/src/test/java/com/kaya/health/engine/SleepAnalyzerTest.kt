package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import kotlin.random.Random

class SleepAnalyzerTest {
    private val day = LocalDateTime.of(2026, 11, 1, 0, 0)

    /** Synthetic night: awake 18:00–23:00 (HR ~88, walking), asleep 23:00–07:00 with deep early / REM late, awake after. */
    private fun syntheticNight(): Pair<List<Pair<LocalDateTime, Int>>, Map<LocalDateTime, Int>> {
        val rnd = Random(7)
        val from = day.minusHours(6) // 18:00 the evening before
        val readings = ArrayList<Pair<LocalDateTime, Int>>()
        var t = from
        while (t.isBefore(day.plusHours(10))) {
            val h = Duration.between(day.minusHours(1), t).toMinutes() / 60.0 // hours since 23:00
            val bpm = when {
                h < 0 || h >= 8 -> 88 + rnd.nextInt(-4, 5) // awake
                h < 3 && (h % 1.5) < 0.6 -> 58 + rnd.nextInt(-1, 2) // deep blocks, steady
                h >= 4 && (h % 1.5) >= 0.9 -> 70 + rnd.nextInt(-10, 11) // REM blocks, irregular
                else -> 64 + rnd.nextInt(-5, 6) // light
            }
            readings += t to bpm
            t = t.plusSeconds(30)
        }
        val steps = HashMap<LocalDateTime, Int>()
        var hr = from
        while (hr.isBefore(day.plusHours(10))) {
            val asleep = !hr.isBefore(day.minusHours(1)) && hr.isBefore(day.plusHours(7))
            steps[hr] = if (asleep) 0 else 400
            hr = hr.plusHours(1)
        }
        return readings to steps
    }

    @Test fun findsBedtimeWakeAndStages() {
        val (readings, steps) = syntheticNight()
        val r = SleepAnalyzer.analyze(readings, steps, day.minusHours(6), day.plusHours(10))!!
        val bedErr = Duration.between(day.minusHours(1), r.bedtime).abs().toMinutes()
        val wakeErr = Duration.between(day.plusHours(7), r.wake).abs().toMinutes()
        assertTrue("bedtime off by $bedErr min (${r.bedtime})", bedErr <= 20)
        assertTrue("wake off by $wakeErr min (${r.wake})", wakeErr <= 20)
        assertTrue("asleep ${r.asleepMin}", r.asleepMin in 6 * 60 + 30..8 * 60 + 20)
        // Stage accuracy is judged on real sleep-lab nights (SleepValidationTest), not on synthetic data.
        assertTrue(kotlin.math.abs(r.asleepMin - r.minutes.filterKeys { it != SleepStage.AWAKE }.values.sum()) <= 2)
        assertTrue("score ${r.score} ${r.scoreParts}", r.score in 30..100)
        val short = r.copy(asleepMin = 255, inBedMin = 265)
        assertTrue("short night score", SleepAnalyzer.score(short).sumOf { it.second } < 70)
        assertEquals(r.score, r.scoreParts.sumOf { it.second })
    }

    @Test fun awakeAllDayFindsNoSleep() {
        val readings = (0 until 2000).map { day.plusSeconds(30L * it) to (85 + (it % 7)) }
        assertNull(SleepAnalyzer.analyze(readings, emptyMap(), day, day.plusHours(16)))
    }

    @Test fun tooLittleDataFindsNoSleep() {
        val readings = (0 until 30).map { day.plusSeconds(30L * it) to 60 }
        assertNull(SleepAnalyzer.analyze(readings, emptyMap(), day, day.plusHours(10)))
    }
}
