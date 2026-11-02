package com.kaya.health.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Pure logic for the Discipline page, so it can be unit-tested. */
object Discipline {
    /** Ticks for one habit, as dates. [checks] holds "habitId|date". */
    fun daysDone(habitId: String, checks: Set<String>): Set<LocalDate> =
        checks.mapNotNull { k -> k.split("|").takeIf { it.size == 2 && it[0] == habitId }?.let { runCatching { LocalDate.parse(it[1]) }.getOrNull() } }.toSet()

    /** Share of habits done on [date] (0..1), counting only habits that existed then. Null if there were none. */
    fun dayScore(date: LocalDate, habitIds: List<Pair<String, LocalDate>>, checks: Set<String>): Double? {
        val active = habitIds.filter { !it.second.isAfter(date) }
        if (active.isEmpty()) return null
        return active.count { "${it.first}|$date" in checks }.toDouble() / active.size
    }

    /** Longest run of consecutive days in [days]. */
    fun bestStreak(days: Set<LocalDate>): Int {
        var best = 0; var run = 0; var prev: LocalDate? = null
        for (d in days.sorted()) {
            run = if (prev != null && ChronoUnit.DAYS.between(prev, d) == 1L) run + 1 else 1
            best = maxOf(best, run); prev = d
        }
        return best
    }

    /**
     * "Never miss twice": yesterday slipped if under half the habits got done, or calories went 10%+ over target.
     * Shown as a reset card today, without guilt.
     */
    fun yesterdaySlipped(habitScoreYesterday: Double?, kcalYesterday: Double?, targetKcal: Double?): Boolean =
        (habitScoreYesterday != null && habitScoreYesterday < 0.5) ||
            (kcalYesterday != null && targetKcal != null && kcalYesterday > targetKcal * 1.1)

    fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    data class CravingPattern(val count: Int, val resisted: Int, val topTrigger: String?, val topHour: Int?)

    /** What the craving log shows: how many, how many beaten, the usual trigger and hour. */
    fun cravingPattern(all: List<Pair<LocalDateTime, Pair<String, String>>>, since: LocalDate): CravingPattern {
        val recent = all.filter { !it.first.toLocalDate().isBefore(since) }
        val resisted = recent.count { it.second.second == "passed" || it.second.second == "smaller" }
        val trigger = recent.map { it.second.first }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val hour = recent.groupingBy { it.first.hour }.eachCount().filter { it.value >= 2 }.maxByOrNull { it.value }?.key
        return CravingPattern(recent.size, resisted, trigger, hour)
    }

    /** A challenge's progress: days done, days elapsed (capped), finished? */
    fun challengeProgress(start: LocalDate, days: Int, done: Set<LocalDate>, today: LocalDate): Triple<Int, Int, Boolean> {
        val end = start.plusDays(days.toLong() - 1)
        val inRange = done.count { !it.isBefore(start) && !it.isAfter(end) }
        val elapsed = (ChronoUnit.DAYS.between(start, minOf(today, end)) + 1).toInt().coerceIn(0, days)
        return Triple(inRange, elapsed, inRange >= days)
    }
}
