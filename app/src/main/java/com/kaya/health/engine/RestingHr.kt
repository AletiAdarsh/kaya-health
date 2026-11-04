package com.kaya.health.engine

import java.time.LocalDateTime

/**
 * Resting heart rate from the band's 30-second heart-rate log: the lowest 30-minute average of the day.
 *
 * The band has no resting-heart-rate series of its own (series 23, once thought to be one, is its hourly
 * battery level). The lowest sustained half hour is almost always during sleep, which is the standard way
 * wearables estimate resting HR; averaging 30 minutes keeps single dropouts (the strap slipping) out.
 */
object RestingHr {
    const val WINDOW_MIN = 30L
    /** At least 20 of the window's 30 minutes must have readings. */
    private const val MIN_READINGS = 40

    fun fromReadings(readings: List<Pair<LocalDateTime, Int>>): Double? {
        val r = readings.filter { it.second in 35..200 }.sortedBy { it.first }
        if (r.size < MIN_READINGS) return null
        var best: Double? = null
        var lo = 0
        var sum = 0L
        for (hi in r.indices) {
            sum += r[hi].second
            while (r[lo].first.isBefore(r[hi].first.minusMinutes(WINDOW_MIN).plusSeconds(1))) { sum -= r[lo].second; lo++ }
            val n = hi - lo + 1
            if (n >= MIN_READINGS) {
                val avg = sum.toDouble() / n
                if (best == null || avg < best) best = avg
            }
        }
        return best
    }
}
