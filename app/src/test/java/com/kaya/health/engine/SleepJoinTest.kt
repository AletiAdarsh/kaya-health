package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Short wake-ups and going back to sleep after an early wake-up, from two real nights checked against the band's firmware log. */
class SleepJoinTest {
    private val day = LocalDate.of(2026, 1, 15)
    private fun t(h: Int, m: Int) = day.atTime(h, m)
    private fun every5(a: LocalDateTime, b: LocalDateTime) = generateSequence(a) { it.plusMinutes(5) }.takeWhile { it.isBefore(b) }.toList()
    private fun hr(a: LocalDateTime, b: LocalDateTime, bpm: Int) = generateSequence(a) { it.plusSeconds(30) }.takeWhile { it.isBefore(b) }.map { it to bpm }.toList()

    /** Breathing log 0:35–7:35 with one missed slot at 2:15 and a gap 3:25–3:50 (firmware: woke 3:28, asleep again 3:50). */
    private val night = (every5(t(0, 35), t(7, 35)) - t(2, 15) - every5(t(3, 25), t(3, 50)).toSet())

    @Test fun twentyMinuteWakeUpIsAwakeButOneMissedSlotIsNot() {
        val gaps = SleepWindow.awakeGaps(night, t(0, 35) to t(7, 35))
        assertEquals(listOf(t(3, 25) to t(3, 50)), gaps)
    }

    @Test fun wakeUpShowsInTheNight() {
        val heart = hr(t(0, 0), t(9, 0), 85)
        val r = SleepAnalyzer.analyze(heart, emptyMap(), t(0, 0), t(8, 35), t(0, 35) to t(7, 35),
            SleepWindow.awakeGaps(night, t(0, 35) to t(7, 35)), night)!!
        assertEquals(1, r.awakenings)
        assertEquals(395, r.asleepMin)   // 7 h in bed − 25 min awake
    }

    /** Up to pee for 25 minutes (with steps), then asleep again: one night, not a night and a nap. */
    @Test fun shortGetUpRejoinsTheNight() {
        val breath = every5(t(0, 0), t(4, 25)) + every5(t(4, 50), t(8, 0))
        val heart = hr(t(0, 0), t(8, 0), 72)
        val w = SleepWindow.find(breath, mapOf(t(4, 0) to 300), heart)!!
        assertEquals(t(8, 0), w.second)
        assertTrue(w.first <= t(0, 0))
        assertEquals(listOf(t(4, 25) to t(4, 50)), SleepWindow.awakeGaps(breath, w))
    }

    /** The band stopped logging 4:29–8:02 (it thought it was off the wrist); heart rate showed sleep from 4:55. */
    @Test fun morningSleepAfterAnEarlyWakeJoinsTheNight() {
        val wake = t(4, 29)
        val sleeps = listOf(t(4, 55) to t(7, 59), t(8, 2) to t(9, 5), t(16, 0) to t(17, 30))
        val more = SleepWindow.morningContinuation(wake, sleeps)
        assertEquals(listOf(t(4, 55) to t(7, 59), t(8, 2) to t(9, 5)), more)
        // An afternoon nap, or sleep long after getting up, stays a nap.
        assertTrue(SleepWindow.morningContinuation(t(7, 0), listOf(t(9, 0) to t(10, 0))).isEmpty())
        assertTrue(SleepWindow.morningContinuation(t(10, 30), listOf(t(11, 10) to t(12, 0))).isEmpty())
    }
}
