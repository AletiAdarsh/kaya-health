package com.kaya.health.engine

import java.time.LocalDate
import kotlin.math.roundToInt

data class DayWeight(val date: LocalDate, val kg: Double)
data class DayIntake(val date: LocalDate, val kcal: Double)

/** One point per calendar day. [raw] is the day's weigh-in (if any); [trend] is the smoothed weight. */
data class TrendPoint(val date: LocalDate, val raw: Double?, val trend: Double)

data class BurnEstimate(
    /** Formula-based daily burn: resting rate + digestion + your average steps. */
    val formulaTdee: Double,
    /** Daily burn worked out from what you ate and how your weight moved. Null until there's enough data. */
    val observedTdee: Double?,
    /** What Kaya actually uses: a blend that shifts toward [observedTdee] as data builds up. */
    val tdee: Double,
    /** 0..1: how much weight [observedTdee] gets in [tdee]. */
    val confidence: Double,
    val weeklyChangeKg: Double?,
    val avgIntake: Double?,
    val loggedDays: Int,
    val weighIns: Int,
    val spanDays: Int,
    val note: String,
)

/**
 * The method no watch can fool: energy in, change in body weight, therefore energy out.
 * Eat X kcal/day on average and lose Y kg/week, and your real burn is X + Y × 7700 / 7.
 */
object RealBurn {
    const val MIN_SPAN_DAYS = 14
    const val MIN_WEIGH_INS = 8
    const val MIN_LOGGED_DAYS = 10

    /** Exponentially smoothed weight. Daily weight jumps 1–2 kg with water and salt, and the trend ignores that. */
    fun trend(weights: List<DayWeight>, alpha: Double = 0.1): List<TrendPoint> {
        if (weights.isEmpty()) return emptyList()
        val byDay = weights.groupBy { it.date }.mapValues { (_, v) -> v.map { it.kg }.average() }
        val start = byDay.keys.min()
        val end = byDay.keys.max()
        val out = mutableListOf<TrendPoint>()
        var t = byDay.getValue(start)
        var d = start
        while (!d.isAfter(end)) {
            val raw = byDay[d]
            if (raw != null) t += alpha * (raw - t)
            out += TrendPoint(d, raw, t)
            d = d.plusDays(1)
        }
        return out
    }

    /** Least-squares slope of the raw daily weigh-ins, kg per day. */
    fun slopeKgPerDay(weights: List<DayWeight>): Double? {
        val byDay = weights.groupBy { it.date }.mapValues { (_, v) -> v.map { it.kg }.average() }
        if (byDay.size < 2) return null
        val x0 = byDay.keys.min().toEpochDay()
        val xs = byDay.keys.map { (it.toEpochDay() - x0).toDouble() }
        val ys = byDay.values.toList()
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        return if (den == 0.0) null else num / den
    }

    /**
     * @param intakes daily food totals. Pass only finished days, not today.
     * @param formulaTdee fallback estimate used until enough of your own data exists.
     */
    fun estimate(
        weights: List<DayWeight>,
        intakes: List<DayIntake>,
        formulaTdee: Double,
        today: LocalDate,
        windowDays: Int = 28,
    ): BurnEstimate {
        val from = today.minusDays(windowDays - 1L)
        val w = weights.filter { !it.date.isBefore(from) && !it.date.isAfter(today) }
        val weighInDays = w.map { it.date }.distinct()
        val weighIns = weighInDays.size
        val spanDays = if (weighInDays.isEmpty()) 0
        else (weighInDays.max().toEpochDay() - weighInDays.min().toEpochDay()).toInt() + 1
        val logged = intakes.filter { !it.date.isBefore(from) && it.date.isBefore(today) && it.kcal > 0 }
        val loggedDays = logged.size
        val avgIntake = if (logged.isEmpty()) null else logged.map { it.kcal }.average()
        val slope = if (weighIns >= 2) slopeKgPerDay(w) else null
        val weekly = slope?.times(7)

        val enough = spanDays >= MIN_SPAN_DAYS && weighIns >= MIN_WEIGH_INS && loggedDays >= MIN_LOGGED_DAYS
        if (!enough || avgIntake == null || slope == null) {
            val note = "Using the formula for now. Weigh yourself most mornings and log all your food. " +
                "After about 2 weeks Kaya works out your real burn from your own body, with no watch guessing. " +
                "So far: ${plural(weighIns, "weigh-in")} over ${plural(spanDays, "day")}, ${plural(loggedDays, "day")} of food logged " +
                "(needs $MIN_WEIGH_INS weigh-ins over $MIN_SPAN_DAYS+ days and $MIN_LOGGED_DAYS food days)."
            return BurnEstimate(formulaTdee, null, formulaTdee, 0.0, weekly, avgIntake, loggedDays, weighIns, spanDays, note)
        }

        val observed = avgIntake - slope * Calories.KCAL_PER_KG_FAT
        if (observed < 1200 || observed > 6000) {
            val note = "Your data gives an unlikely result (${observed.roundToInt()} kcal/day). Usually this means " +
                "some meals weren't logged, or the first-week water-weight drop is skewing it. Still using the formula."
            return BurnEstimate(formulaTdee, observed, formulaTdee, 0.0, weekly, avgIntake, loggedDays, weighIns, spanDays, note)
        }

        val confidence = ((minOf(spanDays, loggedDays) - 7) / 21.0).coerceIn(0.0, 1.0)
        val blended = confidence * observed + (1 - confidence) * formulaTdee
        val dir = if (weekly!! <= 0) "dropped" else "rose"
        val note = "Over the last $spanDays days you ate about ${avgIntake.roundToInt()} kcal/day and your weight " +
            "$dir %.2f kg/week. So your body really burns about ${observed.roundToInt()} kcal/day.".format(kotlin.math.abs(weekly))
        return BurnEstimate(formulaTdee, observed, blended, confidence, weekly, avgIntake, loggedDays, weighIns, spanDays, note)
    }

    /** Daily food target. Floors keep it from going unsafely low. */
    fun target(tdee: Double, deficit: Int, sex: Sex): Double {
        val floor = if (sex == Sex.MALE) 1500.0 else 1200.0
        return maxOf(floor, tdee - deficit)
    }
}


private fun plural(n: Number, word: String) = "$n $word" + if (n.toLong() == 1L) "" else "s"
