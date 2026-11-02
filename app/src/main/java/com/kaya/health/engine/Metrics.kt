package com.kaya.health.engine

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.exp
import kotlin.math.sqrt

/** One analysed night, kept so trends and baselines can be worked out. */
data class NightRecord(
    val wakeDay: LocalDate,
    val bed: LocalDateTime,
    val wake: LocalDateTime,
    val asleepMin: Int,
    val avgHr: Int,
    val breathing: Double?,
    val skinTemp: Double?,
    val hrv: Double?,
)

/**
 * Derived health metrics from the band's raw data. Each is backed by published research (see the notes)
 * and judged against the person's own baseline where that matters more than population norms.
 */
object Metrics {
    // ------------------------------------------------------------------ heart

    /**
     * Heart-rate recovery: peak heart rate in the last 2 minutes of exercise minus the heart rate 1 minute after it
     * ended (Cole et al., NEJM 1999). ≤ 12 bpm with a cool-down is considered abnormal. Null without enough readings.
     */
    fun heartRateRecovery(hr: List<Pair<LocalDateTime, Int>>, exerciseEnd: LocalDateTime): Int? {
        val peak = hr.filter { !it.first.isBefore(exerciseEnd.minusMinutes(2)) && !it.first.isAfter(exerciseEnd) }.maxOfOrNull { it.second } ?: return null
        val after = hr.filter { !it.first.isBefore(exerciseEnd.plusSeconds(50)) && !it.first.isAfter(exerciseEnd.plusSeconds(80)) }.map { it.second }
        if (after.isEmpty()) return null
        return peak - after.average().toInt()
    }

    /**
     * Night-time heart-rate dip: how much lower your heart rate is asleep than awake and resting during the day,
     * as a fraction. Below 10% ("non-dipping") is linked to higher cardiovascular risk.
     * Daytime level = median of 9 am – 9 pm readings, so walks and spikes don't dominate.
     */
    fun nightDip(dayReadings: List<Pair<LocalDateTime, Int>>, sleepingAvg: Int): Double? {
        val day = dayReadings.filter { it.first.hour in 9..20 && it.second in 35..200 }.map { it.second }.sorted()
        if (day.size < 120 || sleepingAvg <= 0) return null
        val median = day[day.size / 2].toDouble()
        return (median - sleepingAvg) / median
    }

    /**
     * Banister TRIMP for a day of 30-second heart-rate readings. Only time above 30% of heart-rate reserve counts
     * (TRIMP was designed for exercise; counting a day of sitting would just add noise).
     */
    fun trimp(readings30s: List<Int>, restingHr: Double, maxHr: Double, male: Boolean): Double {
        val k = if (male) 1.92 else 1.67
        return readings30s.sumOf { v ->
            val r = (v - restingHr) / (maxHr - restingHr)
            if (r < 0.3 || r > 1.2) 0.0 else 0.5 * r * 0.64 * exp(k * r)
        }
    }

    /** Last 7 days' average daily load divided by the last 28 days' (needs 14+ days). 0.8–1.3 is the usual "steady" band. */
    fun loadRatio(dailyLoad: Map<LocalDate, Double>, today: LocalDate): Double? {
        val have = dailyLoad.keys.filter { !it.isAfter(today) && !it.isBefore(today.minusDays(27)) }
        if (have.size < 14) return null
        // Average over recorded days only: a day without band data isn't a rest day.
        val acute = (0L..6L).mapNotNull { dailyLoad[today.minusDays(it)] }.takeIf { it.isNotEmpty() }?.average() ?: return null
        val chronic = (0L..27L).mapNotNull { dailyLoad[today.minusDays(it)] }.average()
        return if (chronic <= 0.0) null else acute / chronic
    }

    /** Walking heart rate per 100 steps a minute: a lower number at the same pace means a fitter heart. */
    fun walkingHrPer100Spm(avgHr: Int, steps: Int, minutes: Double): Double? {
        if (minutes < 10 || steps < 500) return null
        val spm = steps / minutes
        return avgHr / spm * 100
    }

    // ------------------------------------------------------------------ sleep

    /** Minutes after noon, so bedtimes either side of midnight sit on one line (11 pm = 660, 1 am = 780). */
    private fun clock(t: LocalDateTime) = ((t.hour + 12) % 24) * 60 + t.minute

    /** Bedtime consistency: standard deviation of bedtimes over the nights given, minutes. Needs 3+ nights. */
    fun bedtimeSpread(nights: List<NightRecord>): Int? {
        if (nights.size < 3) return null
        val m = nights.map { clock(it.bed).toDouble() }
        val mean = m.average()
        return sqrt(m.sumOf { (it - mean) * (it - mean) } / m.size).toInt()
    }

    /** Sleep debt over the last 7 nights: total shortfall below [needMin] (default 7.5 h). Missing nights are skipped. */
    fun sleepDebt(nights: List<NightRecord>, today: LocalDate, needMin: Int = 450): Int =
        nights.filter { !it.wakeDay.isBefore(today.minusDays(6)) && !it.wakeDay.isAfter(today) }.sumOf { maxOf(0, needMin - it.asleepMin) }

    /**
     * Social jetlag: how much later the middle of your sleep falls on weekends (Fri/Sat nights) than on work nights,
     * in minutes. Over an hour is linked to worse metabolic health (Roenneberg 2012). Needs both kinds of night.
     */
    fun socialJetlag(nights: List<NightRecord>): Int? {
        fun mid(n: NightRecord) = clock(n.bed) + Duration.between(n.bed, n.wake).toMinutes().toInt() / 2
        val weekend = nights.filter { it.wakeDay.dayOfWeek == DayOfWeek.SATURDAY || it.wakeDay.dayOfWeek == DayOfWeek.SUNDAY }
        val work = nights - weekend.toSet()
        if (weekend.isEmpty() || work.size < 2) return null
        return (weekend.map { mid(it) }.average() - work.map { mid(it) }.average()).toInt()
    }

    // ------------------------------------------------------------------ baselines

    /** Today's value minus the median of earlier values (needs 3+). */
    fun vsBaseline(today: Double?, earlier: List<Double>): Double? {
        if (today == null || earlier.size < 3) return null
        val s = earlier.sorted()
        return today - s[s.size / 2]
    }

    data class StrainSignal(val flags: List<String>) { val raised get() = flags.size >= 2 }

    /**
     * Early strain/illness hint: last night against your previous nights. Flags a sleeping heart rate 5+ bpm up,
     * breathing 1.5+/min up, skin temperature 0.4 °C+ up, or HRV 20%+ down. Two or more together is worth noticing
     * (they often move before you feel ill), but it is a hint, not a diagnosis.
     */
    fun strain(last: NightRecord, earlier: List<NightRecord>): StrainSignal {
        val flags = buildList {
            vsBaseline(last.avgHr.toDouble(), earlier.map { it.avgHr.toDouble() })?.let { if (it >= 5) add("sleeping heart rate +%.0f bpm".format(it)) }
            vsBaseline(last.breathing, earlier.mapNotNull { it.breathing })?.let { if (it >= 1.5) add("breathing +%.1f/min".format(it)) }
            vsBaseline(last.skinTemp, earlier.mapNotNull { it.skinTemp })?.let { if (it >= 0.4) add("skin temperature +%.1f °C".format(it)) }
            val hrvs = earlier.mapNotNull { it.hrv }
            vsBaseline(last.hrv, hrvs)?.let { d -> val base = hrvs.sorted()[hrvs.size / 2]; if (base > 0 && d / base <= -0.2) add("HRV %.0f%% lower".format(100 * d / base)) }
        }
        return StrainSignal(flags)
    }

    // ------------------------------------------------------------------ energy

    /** Average daily energy balance (intake − burn) over logged days, and what it implies per week in kg (7,700 kcal/kg). */
    fun energyBalance(intake: List<Double>, burn: Double): Pair<Double, Double>? {
        if (intake.size < 3) return null
        val bal = intake.average() - burn
        return bal to bal * 7 / 7700
    }

    // ------------------------------------------------------------------ more heart

    /**
     * Moderate and vigorous minutes from 30-second heart rate: moderate = 40–59% of heart-rate reserve,
     * vigorous = 60%+ (ACSM). WHO recommends 150 moderate or 75 vigorous minutes a week; vigorous counts double.
     */
    fun activeMinutes(readings30s: List<Int>, restingHr: Double, maxHr: Double): Pair<Int, Int> {
        var mod = 0; var vig = 0
        for (v in readings30s) {
            val r = (v - restingHr) / (maxHr - restingHr)
            if (r >= 0.6 && r <= 1.2) vig++ else if (r >= 0.4) mod++
        }
        return mod / 2 to vig / 2
    }

    /** Clock time of the night's lowest 30-minute average heart rate (the body-clock low point). */
    fun heartRateNadir(hr: List<Pair<LocalDateTime, Int>>, from: LocalDateTime, to: LocalDateTime): LocalDateTime? {
        val night = hr.filter { !it.first.isBefore(from) && it.first.isBefore(to) && it.second in 30..200 }
        if (night.size < 120) return null
        var best: Pair<LocalDateTime, Double>? = null
        for (i in 0..night.size - 60) {
            val avg = night.subList(i, i + 60).sumOf { it.second } / 60.0
            if (best == null || avg < best.second) best = night[i + 30].first to avg
        }
        return best?.first
    }

    // ------------------------------------------------------------------ daily rhythm (hourly steps)

    /**
     * Interdaily stability (IS, 0–1) and intradaily variability (IV, 0–2) from hourly activity over several whole days
     * (Van Someren et al. 1999). High IS = the same routine every day; high IV = activity broken into many bits.
     * Needs 3+ days of 24 values.
     */
    fun rhythm(days: List<IntArray>): Pair<Double, Double>? {
        val full = days.filter { it.size == 24 }
        if (full.size < 3) return null
        val x = full.flatMap { it.toList() }.map { it.toDouble() }
        val n = x.size; val mean = x.average()
        val total = x.sumOf { (it - mean) * (it - mean) }
        if (total <= 0) return null
        val hourMeans = (0 until 24).map { h -> full.map { it[h].toDouble() }.average() }
        val isv = n * hourMeans.sumOf { (it - mean) * (it - mean) } / (24 * total)
        val iv = n * (1 until n).sumOf { (x[it] - x[it - 1]) * (x[it] - x[it - 1]) } / ((n - 1) * total)
        return isv to iv
    }

    /** Hours (8 am – 10 pm, up to [nowHour]) with 250+ steps, and the longest run of hours under 250. */
    fun activeHours(hourly: IntArray, nowHour: Int): Pair<Int, Int> {
        val hours = (8..minOf(21, nowHour - 1)).filter { it < hourly.size }
        var best = 0; var run = 0
        for (h in hours) { if (hourly[h] < 250) { run++; best = maxOf(best, run) } else run = 0 }
        return hours.count { hourly[it] >= 250 } to best
    }

    // ------------------------------------------------------------------ food timing

    /** First to last food log of a day, minutes. */
    fun eatingWindow(times: List<LocalDateTime>): Int? =
        if (times.size < 2) null else Duration.between(times.min(), times.max()).toMinutes().toInt()

    /** Share of the day's calories eaten at or after [hour] (default 8 pm). */
    fun lateShare(entries: List<Pair<LocalDateTime, Double>>, hour: Int = 20): Double? {
        val total = entries.sumOf { it.second }
        return if (total <= 0) null else entries.filter { it.first.hour >= hour }.sumOf { it.second } / total
    }

    /** Day-to-day variation in calories (coefficient of variation, 0–1). Needs 4+ days. */
    fun calorieVariation(daily: List<Double>): Double? {
        if (daily.size < 4) return null
        val m = daily.average(); if (m <= 0) return null
        return sqrt(daily.sumOf { (it - m) * (it - m) } / daily.size) / m
    }

    /** Consecutive days up to yesterday (or today if logged) with any food logged. */
    fun streak(days: Set<LocalDate>, today: LocalDate): Int =
        generateSequence(if (today in days) today else today.minusDays(1)) { it.minusDays(1) }.takeWhile { it in days }.count()
}
