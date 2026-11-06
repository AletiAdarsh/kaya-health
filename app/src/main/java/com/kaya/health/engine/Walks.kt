package com.kaya.health.engine

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.min

/** A walk, found automatically or recorded with Start walk. */
data class Walk(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val steps: Int,
    val avgHr: Int?,
    val maxHr: Int?,
    val detected: Boolean,
) {
    val minutes: Double get() = Duration.between(start, end).seconds / 60.0
    val key: String get() = "$start"
    fun distanceM(heightCm: Double) = steps * Calories.strideM(heightCm, running = false)
    fun speedKmh(heightCm: Double) = if (minutes > 0) distanceM(heightCm) / 1000.0 / (minutes / 60.0) else 0.0
    /** Calories above resting, from the ACSM walking equation (weight × speed), not from heart rate. */
    fun extraKcal(weightKg: Double, heightCm: Double): Double {
        if (minutes <= 0) return 0.0
        val speed = distanceM(heightCm) / minutes            // metres per minute
        val net = Calories.walkingVo2(speed) - Calories.walkingVo2(0.0)
        return Calories.kcalFromVo2(net, weightKg, minutes)
    }

    /**
     * What the heart rate adds on top of [extraKcal]. The steps-and-speed equation runs 10–30% low for heavier walkers
     * (carrying more weight costs more per step) and in heat, and the heart rate shows that extra work. But the
     * heart-rate formula watches use ([Calories.heartRateFormulaKcal]) overshoots badly when the pulse runs high for the
     * pace, so only [HR_TRUST] of the gap between the two counts, and never more than [HR_CAP] × the steps estimate.
     * The formula over-reads for a heavy body even at an easy pulse, so the extra only starts once the walk's heart rate
     * is above 30% of your heart-rate reserve (where walks count at all) and reaches its full size at 60% (hard work).
     * Steps decide; heart rate can only nudge up, never down. For example, a 100-minute walk at 4.3 km/h and 150 bpm
     * by a heavy walker: steps ≈ 430 kcal, heart-rate formula ≈ 1,450 above resting (the watch app showed ~1,600), Kaya ≈ 430 + 129.
     */
    fun heartExtraKcal(weightKg: Double, heightCm: Double, age: Int, sex: Sex, bmrPerDay: Double, restingHr: Double): Double {
        val hr = avgHr ?: return 0.0
        if (minutes <= 0) return 0.0
        val reserve = (hr - restingHr) / (208 - 0.7 * age - restingHr)
        val share = ((reserve - 0.30) / 0.30).coerceIn(0.0, 1.0)
        val move = extraKcal(weightKg, heightCm)
        val byHeart = Calories.heartRateFormulaKcal(hr.toDouble(), weightKg, age, sex, minutes) - bmrPerDay / 1440.0 * minutes
        return share * (HR_TRUST * (byHeart - move)).coerceIn(0.0, HR_CAP * move)
    }

    fun totalExtraKcal(weightKg: Double, heightCm: Double, age: Int, sex: Sex, bmrPerDay: Double, restingHr: Double) =
        extraKcal(weightKg, heightCm) + heartExtraKcal(weightKg, heightCm, age, sex, bmrPerDay, restingHr)

    companion object {
        const val HR_TRUST = 0.3
        const val HR_CAP = 0.3
    }
}

/**
 * Finds walks in a day of band data: at least 10 minutes with heart rate clearly up
 * (≥ resting + 30% of heart-rate reserve, and at least 20 bpm over resting), during hours when the band
 * counted real stepping (≥ 40 steps a minute). Heart rate alone isn't enough (stress, heat, coffee),
 * and steps alone can't tell a stroll from a shuffle, so both have to agree.
 */
object WalkDetector {
    fun detect(
        hr: List<Pair<LocalDateTime, Int>>,
        hourlySteps: IntArray?,
        restingHr: Double,
        age: Int,
        minMinutes: Int = 10,
    ): List<Walk> {
        if (hr.size < 20 || hourlySteps == null) return emptyList()
        val maxHr = 208 - 0.7 * age
        val threshold = max(restingHr + 0.30 * (maxHr - restingHr), restingHr + 20)
        val up = hr.filter { it.second >= threshold }.map { it.first }.sorted()
        if (up.isEmpty()) return emptyList()

        // Group raised readings into runs, bridging gaps of up to 3 minutes (a traffic light, a pause).
        val raw = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
        var a = up.first(); var b = up.first()
        for (t in up.drop(1)) {
            if (Duration.between(b, t).toMinutes() <= 3) b = t else { raw += a to b; a = t; b = t }
        }
        raw += a to b

        class Run(val s: LocalDateTime, val e0: LocalDateTime, val bpm: List<Int>) {
            val e: LocalDateTime get() = e0.plusSeconds(30)
            val minutes: Double get() = Duration.between(s, e).seconds / 60.0
            val median: Int get() = bpm.sorted()[bpm.size / 2]
            var steps = 0.0
        }
        fun bpmIn(s: LocalDateTime, e: LocalDateTime) = hr.filter { !it.first.isBefore(s) && !it.first.isAfter(e) }

        val runs = raw.mapNotNull { (s0, e0) ->
            // Heart rate stays up for a few minutes after you stop (and takes a few to climb). Trim the ends that sit
            // well below the walk's own level, so the walk ends when you stopped, not when your pulse settled.
            val inRun = bpmIn(s0, e0)
            if (inRun.isEmpty()) return@mapNotNull null
            val med = inRun.map { it.second }.sorted()[inRun.size / 2]
            val core = inRun.filter { it.second >= med - 20 }
            val s = core.first().first; val e = core.last().first
            Run(s, e, bpmIn(s, e).map { it.second }).takeIf { it.minutes >= minMinutes }
        }

        // The band only gives steps per hour. Share each hour's steps between the runs in it, hardest-working run first
        // (up to a brisk 115 a minute), so a stretch of slightly raised heart rate before a walk can't take the walk's steps.
        val left = DoubleArray(24) { h -> (hourlySteps.getOrNull(h) ?: 0).toDouble() }
        val day = runs.firstOrNull()?.s?.toLocalDate()
        for (r in runs.sortedByDescending { it.median }) {
            var h = r.s.withMinute(0).withSecond(0).withNano(0)
            while (h.isBefore(r.e)) {
                val hEnd = h.plusHours(1)
                val overlap = Duration.between(maxOf(r.s, h), minOf(r.e, hEnd)).seconds / 60.0
                if (overlap > 0 && h.toLocalDate() == day && h.hour in 0..23) {
                    // A walk that fills the hour gets all of it; part of an hour, at most 90% (some steps were around it).
                    val take = if (overlap >= 50) left[h.hour] else min(left[h.hour], min(overlap * 115, (hourlySteps.getOrNull(h.hour) ?: 0) * 0.9))
                    r.steps += take; left[h.hour] -= take
                }
                h = hEnd
            }
        }

        return runs.mapNotNull { r ->
            if (r.steps < 400 || r.steps / r.minutes < 40) return@mapNotNull null
            Walk(r.s, r.e, r.steps.toInt(), r.bpm.average().toInt(), r.bpm.max(), detected = true)
        }
    }
}
