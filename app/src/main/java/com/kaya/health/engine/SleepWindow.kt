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

    /**
     * Awake stretches inside the night, from gaps in the band's breathing log. The band logs breathing (one value per
     * [stepMin] minutes) only while its own sleep detection says you're asleep, so a gap of [minGapMin]+ minutes means
     * the band saw you awake. Gaps split by a short blip of "sleep" (up to [bridgeMin] minutes) count as one stretch:
     * lying awake for two hours, the band often dozes in and out for 5 minutes. Checked against the band's own
     * firmware log and a tester's own account of a night with a long awake stretch.
     */
    fun awakeGaps(
        times: List<LocalDateTime>,
        window: Pair<LocalDateTime, LocalDateTime>,
        stepMin: Long = 5,
        minGapMin: Long = 20,
        bridgeMin: Long = 10,
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        val (a, b) = window
        val pts = times.filter { !it.isBefore(a) && it.isBefore(b) }.sorted().distinct()
        if (pts.isEmpty()) return emptyList()
        val gaps = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
        // Only gaps between readings: before the first one the band is still deciding you're asleep (Kaya's window
        // already starts earlier on purpose), and the night ends at the last one.
        for ((x, y) in pts.zipWithNext()) {
            val end = x.plusMinutes(stepMin)
            if (y.isAfter(end)) gaps += end to y
        }
        val merged = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
        for (g in gaps) {
            val l = merged.lastOrNull()
            if (l != null && java.time.Duration.between(l.second, g.first).toMinutes() <= bridgeMin) merged[merged.size - 1] = l.first to g.second
            else merged += g
        }
        return merged.filter { java.time.Duration.between(it.first, it.second).toMinutes() >= minGapMin }
    }
}
