package com.kaya.health.engine

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Level { GOOD, NORMAL, WATCH, UNRELIABLE, NONE }

data class VitalStatus(val label: String, val level: Level)

/** Band metric ids (see docs/BAND_PROTOCOL.md). Kept here so the rules don't depend on the Bluetooth layer. */
object MetricId {
    const val HEART = 2
    const val SPO2 = 4
    const val SKIN_TEMP = 8
    const val BREATHING = 19
    const val HRV = 21
    const val STRESS = 22
    const val RESTING = 23
}

/**
 * Plain-language status for each vital, judged against your own usual level when Kaya has one
 * (average of up to 14 earlier days), otherwise against standard adult ranges.
 */
object VitalInsights {
    private val hm = DateTimeFormatter.ofPattern("h:mm a")

    /** True when wrist SpO2 readings jump around too much to trust a single number. */
    fun noisy(values: List<Double>) = values.size >= 3 && values.max() - values.min() > 12

    fun status(metric: Int, values: List<Double>, usual: Double?): VitalStatus {
        if (values.isEmpty()) return VitalStatus("No reading yet", Level.NONE)
        val last = values.last()
        val typical = values.sorted()[values.size / 2]
        return when (metric) {
            MetricId.HEART -> when {
                last < 40 -> VitalStatus("Low", Level.WATCH)
                last <= 100 -> VitalStatus("Normal", Level.NORMAL)
                else -> VitalStatus("Raised", Level.WATCH)
            }
            MetricId.SPO2 -> when {
                noisy(values) -> VitalStatus("Unreliable", Level.UNRELIABLE)
                typical >= 95 -> VitalStatus("Normal", Level.GOOD)
                typical >= 92 -> VitalStatus("Slightly low", Level.WATCH)
                else -> VitalStatus("Low: check", Level.WATCH)
            }
            MetricId.STRESS -> when {
                last < 30 -> VitalStatus("Relaxed", Level.GOOD)
                last < 60 -> VitalStatus("Normal", Level.NORMAL)
                last < 80 -> VitalStatus("High", Level.WATCH)
                else -> VitalStatus("Very high", Level.WATCH)
            }
            MetricId.HRV -> {
                val avg = values.average()
                when {
                    usual == null -> VitalStatus("Learning your usual", Level.NONE)
                    avg >= usual * 1.1 -> VitalStatus("Above your usual", Level.GOOD)
                    avg <= usual * 0.85 -> VitalStatus("Below your usual", Level.WATCH)
                    else -> VitalStatus("Your usual", Level.NORMAL)
                }
            }
            MetricId.BREATHING -> {
                val avg = values.average()
                if (avg in 10.0..20.0) VitalStatus("Normal", Level.GOOD) else VitalStatus("Unusual", Level.WATCH)
            }
            MetricId.SKIN_TEMP -> {
                val avg = values.average()
                when {
                    usual == null -> VitalStatus("Learning your usual", Level.NONE)
                    avg - usual >= 0.7 -> VitalStatus("Warmer than usual", Level.WATCH)
                    usual - avg >= 0.7 -> VitalStatus("Cooler than usual", Level.NORMAL)
                    else -> VitalStatus("Your usual", Level.NORMAL)
                }
            }
            MetricId.RESTING -> {
                val low = values.min()
                when {
                    usual != null && low <= usual - 2 -> VitalStatus("Lower than usual", Level.GOOD)
                    usual != null && low >= usual + 4 -> VitalStatus("Higher than usual", Level.WATCH)
                    low <= 75 -> VitalStatus("Healthy", Level.GOOD)
                    low <= 100 -> VitalStatus("Normal", Level.NORMAL)
                    else -> VitalStatus("High", Level.WATCH)
                }
            }
            else -> VitalStatus("", Level.NONE)
        }
    }

    /**
     * A few sentences about *your* day, most useful first. [today] maps metric → today's readings,
     * [usual] maps metric → your usual daily value (null until there's history).
     */
    fun forYou(
        today: Map<Int, List<Pair<LocalDateTime, Double>>>,
        usual: Map<Int, Double?>,
        losingWeight: Boolean,
    ): List<String> {
        val out = ArrayList<String>()

        today[MetricId.RESTING]?.map { it.second }?.takeIf { it.isNotEmpty() }?.min()?.let { r ->
            val u = usual[MetricId.RESTING]
            out += when {
                u != null && r <= u - 2 -> "Resting heart rate ${r.roundToInt()} bpm, ${(u - r).roundToInt()} below your usual ${u.roundToInt()}. A sign you're well recovered."
                u != null && r >= u + 4 -> "Resting heart rate ${r.roundToInt()} bpm, ${(r - u).roundToInt()} above your usual ${u.roundToInt()}. Poor sleep, stress, a big meal or getting ill can do this. Take it easier today."
                u != null -> "Resting heart rate ${r.roundToInt()} bpm, right at your usual."
                else -> "Resting heart rate ${r.roundToInt()} bpm, in the healthy range. Kaya learns your usual after a few days."
            } + if (losingWeight) " As you lose weight it usually drifts down; watch it here." else ""
        }

        today[MetricId.STRESS]?.takeIf { it.size >= 6 }?.let { s ->
            val high = s.filter { it.second >= 60 }
            if (high.isNotEmpty()) {
                val minutes = high.size * 5
                val peak = high.groupBy { it.first.hour }.maxByOrNull { it.value.size }!!.key
                out += "Stress was high for about ${durationText(minutes)} today, mostly around ${hourText(peak)}. Two minutes of slow breathing in Mind can bring it down."
            } else out += "Stress stayed in a normal range today."
        }

        today[MetricId.HRV]?.map { it.second }?.takeIf { it.size >= 3 }?.average()?.let { h ->
            val u = usual[MetricId.HRV]
            if (u != null && abs(h - u) / u >= 0.1) out += if (h > u)
                "HRV ${h.roundToInt()} ms, higher than your usual ${u.roundToInt()}: your body is handling load well."
            else "HRV ${h.roundToInt()} ms, lower than your usual ${u.roundToInt()}: you may need more recovery (sleep, water, an easier day)."
        }

        today[MetricId.HEART]?.takeIf { it.size >= 10 }?.let { hr ->
            val peak = hr.maxBy { it.second }
            if (peak.second >= 110) out += "Highest heart rate ${peak.second.roundToInt()} bpm at ${peak.first.format(hm)}. That's real effort; if it was a walk, nice."
        }

        today[MetricId.SPO2]?.map { it.second }?.takeIf { it.size >= 6 }?.let { o ->
            val typical = o.sorted()[o.size / 2]
            if (!noisy(o) && typical < 93) out += "Overnight blood oxygen was typically ${typical.roundToInt()}%. One night means little, but if it keeps happening, check with a fingertip oximeter and mention it to a doctor."
        }

        today[MetricId.SKIN_TEMP]?.map { it.second }?.takeIf { it.isNotEmpty() }?.average()?.let { t ->
            val u = usual[MetricId.SKIN_TEMP]
            if (u != null && t - u >= 0.7) out += "Skin temperature was %.1f °C above your usual overnight. Sometimes the first sign of a cold; rest if you feel off.".format(t - u)
        }
        return out
    }

    private fun durationText(min: Int) = if (min < 60) "$min min" else "${min / 60} h" + if (min % 60 >= 10) " ${min % 60} min" else ""
    private fun hourText(h: Int) = when { h == 0 -> "midnight"; h < 12 -> "$h am"; h == 12 -> "noon"; else -> "${h - 12} pm" }
}
