package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NapFinderTest {
    private val day = LocalDateTime.of(2026, 11, 2, 0, 0)
    private fun at(h: Int, m: Int = 0) = day.plusHours(h.toLong()).plusMinutes(m.toLong())
    private fun every5(a: LocalDateTime, b: LocalDateTime) = generateSequence(a) { it.plusMinutes(5) }.takeWhile { it.isBefore(b) }.toList()

    @Test fun afternoonNapFromBreathingIsFoundOutsideTheNights() {
        // Morning night 1:03–9:41, a nap 2:48–5:11 pm, then the next night from 11:52 pm.
        val times = every5(at(1, 3), at(9, 41)) + every5(at(14, 48), at(17, 11)) + every5(at(23, 52), at(31, 18))
        val nights = listOf(LocalDateTime.MIN to at(10, 11), at(23, 22) to at(31, 48))
        val naps = NapFinder.fromBreathing(times, nights)
        assertEquals(1, naps.size)
        assertEquals(at(14, 48), naps[0].first)
        assertTrue(NapFinder.minutes(naps) in 140L..150L)
    }

    @Test fun shortStillnessIsNotANap() {
        val naps = NapFinder.fromBreathing(every5(at(15), at(15, 10)), emptyList())
        assertTrue(naps.isEmpty())
    }

    @Test fun heartFallbackNeedsLowHeartRateAndNoSteps() {
        val readings = generateSequence(at(12)) { it.plusSeconds(30) }.takeWhile { it.isBefore(at(19)) }.map { t ->
            t to if (!t.isBefore(at(14, 50)) && t.isBefore(at(17, 10))) 72 else 95
        }.toList()
        val quiet = (0..23).associate { at(it) to if (it in 15..16) 0 else 800 }
        val naps = NapFinder.fromHeart(readings, quiet, sleepingHr = 70, from = at(10), to = at(20), exclude = emptyList())
        assertEquals(1, naps.size)
        assertTrue(NapFinder.minutes(naps) in 130L..145L)
        // Same heart rate but walking through those hours: not a nap.
        val walking = (0..23).associate { at(it) to 800 }
        assertTrue(NapFinder.fromHeart(readings, walking, 70, at(10), at(20), emptyList()).isEmpty())
    }

    @Test fun mergeJoinsOverlaps() {
        val m = NapFinder.merge(listOf(at(14) to at(15)), listOf(at(14, 30) to at(16)), listOf(at(18) to at(18, 30)))
        assertEquals(listOf(at(14) to at(16), at(18) to at(18, 30)), m)
    }
}
