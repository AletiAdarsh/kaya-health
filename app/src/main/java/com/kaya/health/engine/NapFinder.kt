package com.kaya.health.engine

import java.time.Duration
import java.time.LocalDateTime

/**
 * Daytime sleep. The band logs breathing (metric 19) only while its motion sensor says you're asleep, so the first
 * source is stretches of breathing readings outside the main night. When the band logged nothing (or Kaya never got
 * that day's log), heart rate is the fallback: a long, still stretch at sleeping heart rate with almost no steps.
 */
object NapFinder {
    const val MIN_NAP = 20L

    /** Joins breathing timestamps (one per [stepMin] minutes) into naps, skipping anything inside [exclude]. */
    fun fromBreathing(
        times: List<LocalDateTime>,
        exclude: List<Pair<LocalDateTime, LocalDateTime>>,
        stepMin: Long = 5,
        maxGapMin: Long = 30,
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        val pts = times.filter { t -> exclude.none { (a, b) -> !t.isBefore(a) && t.isBefore(b) } }.sorted()
        val out = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
        var a: LocalDateTime? = null; var b: LocalDateTime? = null
        for (t in pts) {
            if (b != null && Duration.between(b, t).toMinutes() <= maxGapMin) b = t
            else { if (a != null && b != null) out += a to b.plusMinutes(stepMin); a = t; b = t }
        }
        if (a != null && b != null) out += a to b.plusMinutes(stepMin)
        return out.filter { Duration.between(it.first, it.second).toMinutes() >= MIN_NAP }
    }

    /**
     * Fallback from heart rate: stretches of 30+ minutes where heart rate stays at or below [sleepingHr] + 6 (short
     * blips up to 3 minutes allowed) and every full clock hour inside has under 120 steps. Only looks between
     * [from] and [to], outside [exclude].
     */
    fun fromHeart(
        readings: List<Pair<LocalDateTime, Int>>,
        hourlySteps: Map<LocalDateTime, Int>,
        sleepingHr: Int,
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: List<Pair<LocalDateTime, LocalDateTime>>,
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        val limit = sleepingHr + 6
        val r = readings.filter { (t, v) -> v in 30..220 && !t.isBefore(from) && t.isBefore(to) && exclude.none { (a, b) -> !t.isBefore(a) && t.isBefore(b) } }
            .sortedBy { it.first }
        val runs = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
        var start: LocalDateTime? = null; var lastLow: LocalDateTime? = null
        for ((t, v) in r) {
            if (v <= limit) {
                if (start == null || lastLow == null || Duration.between(lastLow, t).toMinutes() > 3) {
                    if (start != null && lastLow != null) runs += start to lastLow
                    start = t
                }
                lastLow = t
            }
        }
        if (start != null && lastLow != null) runs += start to lastLow
        return runs.filter { (a, b) ->
            if (Duration.between(a, b).toMinutes() < 30) return@filter false
            // Every whole hour inside the stretch must be nearly step-free; a nap can't hide a walk.
            var h = a.withMinute(0).withSecond(0).withNano(0).let { if (it.isBefore(a)) it.plusHours(1) else it }
            var ok = true
            while (!h.plusHours(1).isAfter(b)) { if ((hourlySteps[h] ?: 0) >= 120) ok = false; h = h.plusHours(1) }
            ok
        }
    }

    /** Union of nap lists, merging any that overlap. */
    fun merge(vararg lists: List<Pair<LocalDateTime, LocalDateTime>>): List<Pair<LocalDateTime, LocalDateTime>> {
        val all = lists.flatMap { it }.sortedBy { it.first }
        val out = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
        for (n in all) {
            val last = out.lastOrNull()
            if (last != null && !n.first.isAfter(last.second)) out[out.size - 1] = last.first to maxOf(last.second, n.second)
            else out += n
        }
        return out
    }

    fun minutes(naps: List<Pair<LocalDateTime, LocalDateTime>>) = naps.sumOf { Duration.between(it.first, it.second).toMinutes() }
}
