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
        val runs = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
        var a = up.first(); var b = up.first()
        for (t in up.drop(1)) {
            if (Duration.between(b, t).toMinutes() <= 3) b = t else { runs += a to b; a = t; b = t }
        }
        runs += a to b

        return runs.mapNotNull { (s, e0) ->
            val e = e0.plusSeconds(30)
            val minutes = Duration.between(s, e).seconds / 60.0
            if (minutes < minMinutes) return@mapNotNull null
            // The band only gives steps per hour, so give each hour's steps to the walk, up to a brisk 115 a minute.
            var steps = 0.0
            var h = s.withMinute(0).withSecond(0).withNano(0)
            while (h.isBefore(e)) {
                val hEnd = h.plusHours(1)
                val overlap = Duration.between(maxOf(s, h), minOf(e, hEnd)).seconds / 60.0
                if (overlap > 0 && h.hour in hourlySteps.indices && h.toLocalDate() == s.toLocalDate())
                    steps += min(hourlySteps[h.hour] * 0.9, overlap * 115)
                h = hEnd
            }
            if (steps < 400 || steps / minutes < 40) return@mapNotNull null
            val inRun = hr.filter { !it.first.isBefore(s) && !it.first.isAfter(e0) }.map { it.second }
            Walk(s, e, steps.toInt(), inRun.average().toInt(), inRun.max(), detected = true)
        }
    }
}
