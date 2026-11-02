package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MotivationTest {
    private val today = LocalDate.of(2026, 11, 1) // a Sunday

    @Test fun streakAliveUntilTodayIsDone() {
        val done = setOf(today.minusDays(1), today.minusDays(2), today.minusDays(4))
        assertEquals(Streak(2, false), Motivation.streak(done, today))
        assertEquals(Streak(3, true), Motivation.streak(done + today, today))
        assertEquals(0, Motivation.streak(emptySet(), today).days)
    }

    @Test fun losingCheckpoint() {
        // 100 → 92 kg in 48 days at 1.1 kg/week
        val c = Motivation.checkpoint("Holiday", 92.0, LocalDate.of(2026, 12, 19), 100.0, 100.0, 1.1, today)
        assertEquals(48, c.daysLeft)
        assertEquals(100.0 - 1.1 * 48 / 7, c.projectedKg, 0.01)
        assertFalse(c.onTrack) // ~92.5 kg: just short
        assertTrue(c.close)
        assertFalse(c.reached)
        assertTrue(Motivation.checkpoint("Holiday", 92.0, LocalDate.of(2026, 12, 19), 100.0, 100.0, 1.3, today).onTrack)
        assertEquals(8 / (48 / 7.0), c.neededKgPerWeek, 0.01)
    }

    @Test fun weekStepsCountsMondayToToday() {
        val wed = LocalDate.of(2026, 11, 4)
        val steps = mapOf(
            LocalDate.of(2026, 11, 1) to 9000L, // previous Sunday: not this week
            LocalDate.of(2026, 11, 2) to 6000L,
            LocalDate.of(2026, 11, 3) to 8000L,
            wed to 2000L,
        )
        val w = Motivation.weekSteps(steps, wed, 50000)
        assertEquals(16000L, w.done)
        assertEquals(5, w.daysLeft) // Wed..Sun
        assertEquals(6800L, w.perDayToGo)
    }

    @Test fun gainingCheckpoint() {
        // 60 → 63 kg in 48 days at 0.5 kg/week: reaches ~63.4
        val c = Motivation.checkpoint("Wedding", 63.0, LocalDate.of(2026, 12, 19), 60.0, 60.0, 0.5, today, gaining = true)
        assertTrue(c.onTrack)
        assertFalse(c.reached)
        assertEquals(60.0 + 0.5 * 48 / 7, c.projectedKg, 0.01)
        assertTrue(Motivation.checkpoint("Wedding", 63.0, LocalDate.of(2026, 12, 19), 60.0, 63.2, 0.5, today, gaining = true).reached)
    }
}
