package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DisciplineTest {
    private val d = LocalDate.of(2026, 11, 2)

    @Test fun habitDaysAndScores() {
        val checks = setOf("water|$d", "water|${d.minusDays(1)}", "walk|$d")
        assertEquals(setOf(d, d.minusDays(1)), Discipline.daysDone("water", checks))
        val habits = listOf("water" to d.minusDays(10), "walk" to d.minusDays(10), "read" to d.plusDays(1))
        assertEquals(1.0, Discipline.dayScore(d, habits, checks)!!, 1e-9)   // "read" didn't exist yet
        assertEquals(0.5, Discipline.dayScore(d.minusDays(1), habits, checks)!!, 1e-9)
    }

    @Test fun bestStreakFindsTheLongestRun() {
        val days = setOf(d, d.minusDays(1), d.minusDays(2), d.minusDays(5), d.minusDays(6))
        assertEquals(3, Discipline.bestStreak(days))
        assertEquals(0, Discipline.bestStreak(emptySet()))
    }

    @Test fun neverMissTwice() {
        assertTrue(Discipline.yesterdaySlipped(0.25, null, null))
        assertTrue(Discipline.yesterdaySlipped(1.0, 2300.0, 2000.0))
        assertFalse(Discipline.yesterdaySlipped(0.75, 2100.0, 2000.0))
    }

    @Test fun cravingPatternFindsTriggerAndHour() {
        val t = d.atTime(21, 30)
        val log = listOf(
            t to ("stress" to "passed"), t.minusDays(1) to ("stress" to "ate"),
            t.minusDays(2).withHour(15) to ("boredom" to "smaller"), t.minusDays(3) to ("stress" to "passed"),
        )
        val p = Discipline.cravingPattern(log, d.minusDays(7))
        assertEquals(4, p.count); assertEquals(3, p.resisted)
        assertEquals("stress", p.topTrigger); assertEquals(21, p.topHour)
    }

    @Test fun challengeProgress() {
        val start = d.minusDays(4)
        val (done, elapsed, finished) = Discipline.challengeProgress(start, 30, setOf(start, start.plusDays(1), d), d)
        assertEquals(3, done); assertEquals(5, elapsed); assertFalse(finished)
    }

    @Suppress("unused") private val keep = LocalDateTime.MIN
}
