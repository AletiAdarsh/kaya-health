package com.kaya.health.band

import java.time.LocalDate
import java.time.LocalDateTime

data class VitalDay(val date: LocalDate, val min: Double, val avg: Double, val max: Double, val readings: Int)

/**
 * One measurement the Noise REP records, and how to read it. Meanings were worked out from the data
 * (see docs/BAND_PROTOCOL.md). Ones marked [confirmed] = false are best interpretations, labelled as such in the app.
 */
data class Vital(
    val metric: Int,
    val name: String,
    val unit: String,
    val decimals: Int,
    val about: String,
    val normal: String,
    val confirmed: Boolean,
    /** Plausible range; values outside are treated as "no reading". */
    val valid: ClosedFloatingPointRange<Double>,
    /** Show the day's median instead of the latest reading (for noisy measurements like wrist SpO2). */
    val showMedian: Boolean = false,
) {
    fun headline(readings: List<Pair<LocalDateTime, Double>>): Double? =
        if (readings.isEmpty()) null
        else if (showMedian) readings.map { it.second }.sorted().let { it[it.size / 2] }
        else readings.last().second

    fun format(v: Double): String = if (decimals == 0) "%.0f".format(v) else "%.${decimals}f".format(v)

    companion object {
        val HEART = Vital(
            Commands.METRIC_HEART_RATE, "Heart rate", "bpm", 0,
            "Recorded every 30 seconds, all day and night. Pulse rate is the same thing measured at the wrist.",
            "Resting 60–100 bpm for most adults; lower usually means fitter.", true, 30.0..220.0,
        )
        val SPO2 = Vital(
            Commands.METRIC_SPO2, "Blood oxygen (SpO2)", "%", 0,
            "Recorded every 5 minutes, mostly while still or asleep. Wrist SpO2 at night is noisy (arm position, a loose strap, light leaking in), so Kaya shows the day's typical (median) value rather than single readings. NoiseFit appears to hide the low readings altogether.",
            "95–100% is normal. One low reading means little. Many low readings night after night, plus loud snoring or daytime sleepiness, is worth raising with a doctor.", true, 70.0..100.0,
            showMedian = true,
        )
        val STRESS = Vital(
            Commands.METRIC_STRESS, "Stress", "/100", 0,
            "The band's 0–100 stress score every 5 minutes, from heart-rate variability. Higher = more strain.",
            "Under ~30 relaxed, 30–60 normal, over ~60 high. Exercise and caffeine raise it.", true, 1.0..100.0,
        )
        val HRV = Vital(
            Commands.METRIC_HRV, "HRV", "ms", 0,
            "Heart-rate variability every 15 minutes. How much the gap between beats varies; a sign of recovery.",
            "Very personal. Compare with your own average: higher than usual = well recovered.", false, 5.0..250.0,
        )
        val BREATHING = Vital(
            Commands.METRIC_SLEEP_BREATHING, "Breathing rate", "/min", 0,
            "Breaths per minute every 5 minutes, recorded only while the band thinks you're asleep.",
            "About 12–20 while asleep is typical for adults.", false, 5.0..40.0,
        )
        val SKIN_TEMP = Vital(
            Commands.METRIC_SKIN_TEMP, "Skin temperature", "°C", 1,
            "Wrist skin temperature every 5 minutes overnight. Skin runs cooler than body (core) temperature.",
            "Usually 32–36 °C at the wrist. The trend matters more than the number; a jump can mean illness.", false, 25.0..42.0,
        )
        // Series 23 is the band's hourly battery level, not a resting heart rate (NoiseFit's SDK names it battery_data).
        // Resting heart rate is worked out from the heart-rate log instead: engine/RestingHr.kt.

        val ALL = listOf(HEART, SPO2, STRESS, HRV, BREATHING, SKIN_TEMP)

        /** Time-stamped readings from a stored/fetched series. */
        fun readings(v: Vital, s: Series): List<Pair<LocalDateTime, Double>> {
            if (v.metric == Commands.METRIC_HEART_RATE) {
                val step = (s.interval ?: 30).toLong()
                return s.samples.withIndex().filter { it.value > 0 }.map { s.start.plusSeconds(it.index * step) to it.value.toDouble() }
            }
            val raw = s.samples
            // Skin temperature: big-endian 16-bit values, hundredths of a degree.
            val values: List<Double> = if (v.metric == Commands.METRIC_SKIN_TEMP)
                (0 until raw.size / 2).map { ((raw[2 * it] shl 8) or raw[2 * it + 1]) / 100.0 }
            else raw.map { it.toDouble() }
            if (values.isEmpty()) return emptyList()
            val minutes = (s.interval?.takeIf { it in 1..60 } ?: (1440 / values.size)).toLong()
            return values.withIndex()
                .filter { it.value in v.valid && raw.getOrNull(if (v.metric == Commands.METRIC_SKIN_TEMP) it.index * 2 else it.index) != 0xFF }
                .map { s.start.plusMinutes(it.index * minutes) to it.value }
        }
    }
}
