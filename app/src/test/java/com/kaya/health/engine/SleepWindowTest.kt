package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SleepWindowTest {
    private val day = LocalDate.of(2026, 3, 10)
    private fun t(h: Int, m: Int) = day.atTime(h, m)
    private fun every5(a: LocalDateTime, b: LocalDateTime) = generateSequence(a) { it.plusMinutes(5) }.takeWhile { it.isBefore(b) }.toList()
    private fun hr(a: LocalDateTime, b: LocalDateTime, bpm: Int) = generateSequence(a) { it.plusSeconds(30) }.takeWhile { it.isBefore(b) }.map { it to bpm }.toList()

    /** A night where the band's log skips an hour and 50 minutes while the wearer is plainly asleep. */
    @Test fun quietGapsAreJoined() {
        val breath = every5(t(1, 15), t(2, 0)) + every5(t(3, 0), t(4, 5)) + every5(t(4, 35), t(8, 35)) + every5(t(9, 25), t(9, 40))
        val heart = hr(t(0, 0), t(1, 0), 93) + hr(t(1, 0), t(9, 40), 76) + hr(t(9, 40), t(10, 0), 92)
        val steps = mapOf(t(5, 0) to 25, t(9, 0) to 18)
        val w = SleepWindow.find(breath, steps, heart)
        assertEquals(t(1, 0), w!!.first)   // heart rate was already at sleeping level from 1:00
        assertEquals(t(9, 40), w.second)
    }

    /** Walking around with a daytime heart rate in the middle splits the night. */
    @Test fun aRealWakeUpSplits() {
        val breath = every5(t(1, 0), t(3, 0)) + every5(t(4, 0), t(9, 0))
        val heart = hr(t(1, 0), t(3, 0), 70) + hr(t(3, 0), t(4, 0), 95) + hr(t(4, 0), t(9, 0), 70)
        val steps = mapOf(t(3, 0) to 900)
        val w = SleepWindow.find(breath, steps, heart)
        assertEquals(t(4, 0), w!!.first)
        assertEquals(t(9, 0), w.second)
    }

    @Test fun longGapsAreNeverJoined() {
        val breath = every5(t(0, 0), t(2, 0)) + every5(t(5, 0), t(8, 0))
        val w = SleepWindow.find(breath, emptyMap(), emptyList())
        assertEquals(t(5, 0), w!!.first)
    }
}
