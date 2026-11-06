package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** A real evening checked against the band's firmware log and a GPS recording of the same walk. */
class WalkSplitTest {
    private val day = LocalDate.of(2026, 1, 15)
    private fun t(h: Int, m: Int) = day.atTime(h, m)
    private fun hr(a: LocalDateTime, b: LocalDateTime, bpm: Int) =
        generateSequence(a) { it.plusSeconds(30) }.takeWhile { it.isBefore(b) }.map { it to bpm }.toList()

    // Resting 70, so walks need 107+ bpm. Slightly raised heart rate 18:57–19:14 (sitting after dinner, 108 bpm),
    // then a real walk 19:51–21:31 at ~150 bpm, with the pulse settling at ~115 until 21:43.
    private val heart = hr(t(0, 0), t(18, 57), 75) + hr(t(18, 57), t(19, 14), 108) + hr(t(19, 14), t(19, 51), 80) +
        hr(t(19, 51), t(21, 31), 150) + hr(t(21, 31), t(21, 43), 115) + hr(t(21, 43), t(23, 59), 80)
    private val steps = IntArray(24).also { it[18] = 130; it[19] = 900; it[20] = 5600; it[21] = 2900 }

    private val walks = WalkDetector.detect(heart, steps, restingHr = 70.0, age = 30)

    @Test fun raisedHeartRateBeforeTheWalkDoesNotTakeItsSteps() {
        assertEquals(1, walks.size)
        assertTrue(walks.single().start >= t(19, 50))
    }

    @Test fun walkEndsWhenYouStoppedNotWhenThePulseSettled() {
        val w = walks.single()
        assertTrue("ended ${w.end}", w.end <= t(21, 32))
        assertTrue(w.steps > 8000)
    }

    @Test fun heartRateAddsAtMostThirtyPercent() {
        val w = walks.single()
        val move = w.extraKcal(120.0, 178.0)
        val extra = w.heartExtraKcal(120.0, 178.0, 30, Sex.MALE, Calories.bmr(120.0, 178.0, 30, Sex.MALE), 70.0)
        assertEquals(0.3 * move, extra, 1.0)
        // A calm walk (pulse in line with the pace) gets nothing extra.
        val calm = w.copy(avgHr = 95)
        assertEquals(0.0, calm.heartExtraKcal(120.0, 178.0, 30, Sex.MALE, Calories.bmr(120.0, 178.0, 30, Sex.MALE), 70.0), 0.0)
    }
}
