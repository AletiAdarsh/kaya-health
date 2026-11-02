package com.kaya.health.engine

import java.time.Duration
import java.time.LocalDateTime

/**
 * The night's sleep window from the band's own sleep log (it records breathing only while it thinks you're asleep).
 *
 * The band's log has holes: it can skip an hour while you're clearly asleep. So the logged stretches are joined
 * across gaps of up to [maxGapMin] minutes when the gap looks like sleep (almost no steps, heart rate near the
 * sleeping level). A real wake-up (walking around, heart rate at its daytime level) still splits the night.
 */
object SleepWindow {
    fun find(
        breath: List<LocalDateTime>,
        stepsByHour: Map<LocalDateTime, Int>,
        hr: List<Pair<LocalDateTime, Int>>,
        sampleMin: Long = 5,
        maxGapMin: Long = 120,
    ): Pair<LocalDateTime, LocalDateTime>? {
        if (breath.isEmpty()) return null
        // 1. Stretches of logged sleep, small holes (≤ 30 min) closed.
        val runs = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
        var a = breath.first(); var b = a
        for (t in breath.sorted().drop(1)) {
            if (Duration.between(b, t).toMinutes() <= 30) b = t else { runs += a to b; a = t; b = t }
        }
        runs += a to b
        val withEnd = runs.map { it.first to it.second.plusMinutes(sampleMin) }

        // Sleeping heart rate: median inside the logged stretches.
        val inside = hr.filter { (t, _) -> withEnd.any { !t.isBefore(it.first) && t.isBefore(it.second) } }.map { it.second }.sorted()
        val sleepHr = inside.getOrNull(inside.size / 2)

        fun quiet(from: LocalDateTime, to: LocalDateTime): Boolean {
            val gapMin = Duration.between(from, to).toMinutes()
            if (gapMin > maxGapMin) return false
            // Steps in the hours the gap touches, scaled to the gap's share of each hour.
            var steps = 0.0
            var h = from.withMinute(0).withSecond(0).withNano(0)
            while (h.isBefore(to)) {
                val s = maxOf(from, h); val e = minOf(to, h.plusHours(1))
                steps += (stepsByHour[h] ?: 0) * Duration.between(s, e).toMinutes() / 60.0
                h = h.plusHours(1)
            }
            if (steps > 120) return false
            val gapHr = hr.filter { !it.first.isBefore(from) && it.first.isBefore(to) }.map { it.second }.sorted()
            if (sleepHr != null && gapHr.size >= 10) {
                val med = gapHr[gapHr.size / 2]
                if (med > sleepHr + 10) return false
            }
            return true
        }

        // 2. Join neighbouring stretches across quiet gaps, then take the longest chain.
        val chains = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
        var cur = withEnd.first()
        for (r in withEnd.drop(1)) {
            cur = if (quiet(cur.second, r.first)) cur.first to r.second else { chains += cur; r }
        }
        chains += cur
        val best = chains.maxByOrNull { Duration.between(it.first, it.second) } ?: return null

        // 3. The band starts its log a little after you drop off. Step back (up to 30 min) while the heart rate
        //    is already at its sleeping level and you aren't moving.
        var start = best.first
        if (sleepHr != null) {
            repeat(6) {
                val s = start.minusMinutes(sampleMin)
                val win = hr.filter { !it.first.isBefore(s) && it.first.isBefore(start) }.map { it.second }.sorted()
                val hourSteps = stepsByHour[s.withMinute(0).withSecond(0).withNano(0)] ?: 0
                if (win.size < 4 || win[win.size / 2] > sleepHr + 6 || hourSteps > 150) return best.copy(first = start)
                start = s
            }
        }
        return best.copy(first = start)
    }
}
