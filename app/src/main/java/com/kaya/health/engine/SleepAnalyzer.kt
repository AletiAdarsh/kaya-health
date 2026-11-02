package com.kaya.health.engine

import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class SleepStage(val label: String) { AWAKE("Awake"), REM("REM"), LIGHT("Light"), DEEP("Deep") }

data class SleepEpoch(val start: LocalDateTime, val hr: Double?, val stage: SleepStage)

data class SleepResult(
    val bedtime: LocalDateTime,
    val wake: LocalDateTime,
    /** 5-minute epochs from bedtime to wake. */
    val epochs: List<SleepEpoch>,
    val minutes: Map<SleepStage, Int>,
    val asleepMin: Int,
    val inBedMin: Int,
    val awakenings: Int,
    val avgSleepingHr: Int,
    val lowestHr: Int,
    /** Centre of the "awake" heart-rate cluster, for context. */
    val awakeHr: Int,
    val score: Int,
    val scoreParts: List<Pair<String, Int>>,
    val dataCoverage: Double,
) {
    fun share(stage: SleepStage): Double = if (asleepMin == 0) 0.0 else (minutes[stage] ?: 0).toDouble() / asleepMin
}

/**
 * Works out sleep from the band's own 30-second heart-rate record plus hourly steps.
 *
 * 1. Split the night into 5-minute epochs (mean and spread of the 30-s readings).
 * 2. Separate "sleeping" from "awake" heart rate by clustering the epochs into two groups.
 *    Sleeping HR is typically 10–25 bpm below a person's resting-awake HR.
 * 3. The longest run of low-HR epochs with no walking (short higher-HR gaps allowed, e.g. REM or
 *    a turn-over) gives bedtime and wake time.
 * 4. Stages, from heart-rate physiology: deep = lowest and steadiest HR (mostly in the first half);
 *    REM = higher and more irregular HR, not in the first hour; awake = HR back near awake
 *    level or movement; light = the rest.
 *
 * Stages from heart rate alone are an ESTIMATE. Even commercial bands using motion and HRV models
 * agree with lab sleep studies only ~60–70% of the time on stages. Bedtime, wake time and total
 * sleep are the reliable parts.
 */
object SleepAnalyzer {
    const val EPOCH_MIN = 5L

    private class Ep(val start: LocalDateTime) {
        val samples = ArrayList<Int>()
        var steps = 0
        val hr: Double? get() = if (samples.size >= 3) samples.average() else null
        val sd: Double?
            get() {
                if (samples.size < 3) return null
                val m = samples.average()
                return sqrt(samples.sumOf { (it - m) * (it - m) } / samples.size)
            }
    }

    private fun pct(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val idx = (p / 100.0 * (s.size - 1)).coerceIn(0.0, (s.size - 1).toDouble())
        val lo = idx.toInt()
        val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (idx - lo)
    }

    /**
     * @param readings (time, bpm) for every 30-s slot with a reading
     * @param hourlySteps steps per clock hour, keyed by the hour's start
     */
    fun analyze(
        readings: List<Pair<LocalDateTime, Int>>,
        hourlySteps: Map<LocalDateTime, Int>,
        from: LocalDateTime,
        to: LocalDateTime,
        /**
         * The band's own sleep window, if it reported one (it records breathing only while its motion sensor
         * says you're asleep). Used for bedtime and wake time instead of heart rate, which can't tell lying
         * awake quietly from light sleep. Stages still come from the model.
         */
        bandWindow: Pair<LocalDateTime, LocalDateTime>? = null,
    ): SleepResult? {
        val start = from.truncatedTo(ChronoUnit.HOURS)
        val n = (Duration.between(start, to).toMinutes() / EPOCH_MIN).toInt()
        if (n < 12) return null
        val eps = List(n) { Ep(start.plusMinutes(it * EPOCH_MIN)) }
        for ((t, bpm) in readings) {
            if (bpm <= 0 || t.isBefore(start) || !t.isBefore(to)) continue
            val i = (Duration.between(start, t).toMinutes() / EPOCH_MIN).toInt()
            if (i in 0 until n) eps[i].samples += bpm
        }
        for (e in eps) e.steps = hourlySteps[e.start.truncatedTo(ChronoUnit.HOURS)] ?: 0

        val hrs = eps.mapNotNull { it.hr }
        if (hrs.size < 24) return null // under 2 hours of data

        // 2-group clustering (1-D k-means) of epoch heart rates
        var cLow = pct(hrs, 10.0)
        var cHigh = pct(hrs, 90.0)
        repeat(25) {
            val lo = hrs.filter { kotlin.math.abs(it - cLow) <= kotlin.math.abs(it - cHigh) }
            val hi = hrs.filter { kotlin.math.abs(it - cLow) > kotlin.math.abs(it - cHigh) }
            if (lo.isNotEmpty()) cLow = lo.average()
            if (hi.isNotEmpty()) cHigh = hi.average()
        }
        // Pass 1 (rough guess, optional): longest run of low heart rate. Only a starting point for the model.
        var roughA: Int? = null; var roughB: Int? = null
        if (cHigh - cLow >= 6) {
            val thr = (cLow + cHigh) / 2
            val low = BooleanArray(n) { eps[it].hr?.let { h -> h <= thr } ?: false }
            data class Run(var a: Int, var b: Int) // inclusive
            val merged = ArrayList<Run>()
            var i = 0
            while (i < n) {
                if (!low[i]) { i++; continue }
                var j = i
                while (j + 1 < n && low[j + 1]) j++
                val l = merged.lastOrNull()
                val gap = l?.let { (it.b + 1 until i) }
                if (l != null && gap!!.count() <= 9 && ((gap.maxOfOrNull { eps[it].steps } ?: 0) <= 150 || gap.all { eps[it].hr == null })) l.b = j
                else merged += Run(i, j)
                i = j + 1
            }
            merged.maxByOrNull { it.b - it.a }?.takeIf { it.b - it.a + 1 >= 12 }?.let { roughA = it.a; roughB = it.b }
        }

        // The trained model (SleepModel) on 30-s slots across the whole window. It decides both the stages and
        // when sleep started and ended (longest sleep stretch, wake gaps under 60 min merged). Refined over a few
        // passes so "how far into the night" is consistent.
        val s0 = start
        val nSlots = (Duration.between(s0, to).seconds / 30).toInt()
        val sums = IntArray(nSlots); val counts = IntArray(nSlots)
        for ((t, bpm) in readings) {
            if (bpm <= 0 || t.isBefore(s0) || !t.isBefore(to)) continue
            val k = (Duration.between(s0, t).seconds / 30).toInt()
            if (k in 0 until nSlots) { sums[k] += bpm; counts[k]++ }
        }
        val hr = Array(nSlots) { k -> if (counts[k] > 0) (sums[k].toDouble() / counts[k]).roundToInt() else null }
        val steps = IntArray(nSlots) { k -> hourlySteps[s0.plusSeconds(30L * k).truncatedTo(ChronoUnit.HOURS)] ?: 0 }
        fun slotOf(t: LocalDateTime) = (Duration.between(s0, t).seconds / 30).toInt().coerceIn(0, nSlots - 1)
        val bw = bandWindow?.takeIf { (a, b) -> Duration.between(a, b).toMinutes() >= 60 && b.isAfter(s0) && a.isBefore(to) }
        var first: Int
        var last: Int
        var all: Array<SleepStage>
        if (bw != null) {
            first = slotOf(bw.first); last = (slotOf(bw.second) - 1).coerceAtLeast(first)
            all = SleepModel.classify(hr, steps, first, last, wakeThreshold = 0.65)
        } else {
            first = roughA?.let { it * 10 } ?: (nSlots * 0.3).toInt()
            last = roughB?.let { minOf(nSlots - 1, it * 10 + 9) } ?: (nSlots * 0.85).toInt()
            all = SleepModel.classify(hr, steps, first, last)
            var found = false
            for (pass in 0 until 4) {
                data class Seg(var a: Int, var b: Int, var sleep: Int)
                val segs = ArrayList<Seg>()
                for (k in 0 until nSlots) {
                    if (all[k] == SleepStage.AWAKE || hr[k] == null) continue
                    val l = segs.lastOrNull()
                    if (l != null && k - l.b <= 120) { l.b = k; l.sleep++ } else segs += Seg(k, k, 1)
                }
                val best = segs.maxByOrNull { it.sleep }?.takeIf { it.sleep >= 120 } ?: break // need 1 h+ asleep
                found = true
                if (best.a == first && best.b == last) break
                first = best.a; last = best.b
                all = SleepModel.classify(hr, steps, first, last)
            }
            if (!found) return null
            // Trim stray "sleep" moments at the edges: the night starts/ends where sleep is sustained
            // (>= 70% asleep over the next/previous 20 min). Lying awake in bed in the morning isn't sleep.
            run {
                fun sustained(from: Int, dir: Int): Boolean {
                    val idx = (0 until 40).map { from + dir * it }.filter { it in first..last }
                    return idx.size >= 20 && idx.count { all[it] != SleepStage.AWAKE } >= 0.7 * idx.size
                }
                var a = first; while (a < last && !sustained(a, 1)) a++
                var b = last; while (b > a && !sustained(b, -1)) b--
                if (b - a >= 120 && (a != first || b != last)) {
                    first = a; last = b
                    all = SleepModel.classify(hr, steps, first, last)
                }
            }
            // Sanity gate: unless sleeping and awake heart rate separated clearly above, the "sleep" stretch must
            // still run at least 1.5 bpm below the rest of the window. A flat heart rate all day is not sleep.
            if (roughA == null) {
                val inside = (first..last).mapNotNull { hr[it] }
                val outside = ((0 until first) + (last + 1 until nSlots)).mapNotNull { hr[it] }
                if (outside.size < 60 || inside.average() > outside.average() - 1.5) return null
            }
        }
        val bed = s0.plusSeconds(30L * first)
        val wake = s0.plusSeconds(30L * (last + 1))
        val stages = (first..last).map { all[it] }
        val times = (first..last).map { s0.plusSeconds(30L * it) }
        val slotHr = (first..last).map { hr[it] }

        val minutes = SleepStage.entries.associateWith { st -> stages.count { it == st } / 2 }
        val asleep = stages.count { it != SleepStage.AWAKE } / 2
        val inBed = stages.size / 2
        // An awakening = 5+ minutes awake in a row (shorter stirrings happen many times a night and aren't remembered).
        // Awake time is still counted in full in the stage minutes.
        var awakenings = 0; var run = 0
        for (st in stages + SleepStage.LIGHT) {
            if (st == SleepStage.AWAKE) run++ else { if (run >= 10) awakenings++; run = 0 }
        }

        val sleepingHr = slotHr.zip(stages).filter { it.second != SleepStage.AWAKE }.mapNotNull { it.first }
        // Lowest = 2nd percentile, so a single bad reading from a loose band (e.g. 32 bpm) doesn't count.
        val lowest = readings.filter { (t, v) -> v in 30..220 && !t.isBefore(bed) && t.isBefore(wake) }.map { it.second }.sorted()
            .let { l -> l.getOrNull((l.size * 0.02).toInt()) } ?: 0

        val result = SleepResult(
            bedtime = bed,
            wake = wake,
            epochs = times.indices.map { SleepEpoch(times[it], slotHr[it]?.toDouble(), stages[it]) },
            minutes = minutes,
            asleepMin = asleep,
            inBedMin = inBed,
            awakenings = awakenings,
            avgSleepingHr = sleepingHr.average().takeIf { !it.isNaN() }?.roundToInt() ?: 0,
            lowestHr = lowest,
            awakeHr = cHigh.roundToInt(),
            score = 0,
            scoreParts = emptyList(),
            dataCoverage = slotHr.count { it != null }.toDouble() / maxOf(1, slotHr.size),
        )
        val parts = score(result)
        return result.copy(score = parts.sumOf { it.second }, scoreParts = parts)
    }

    /** Transparent 0–100 score. Every point is explained on screen. */
    fun score(r: SleepResult): List<Pair<String, Int>> {
        fun inBand(x: Double, lo: Double, hi: Double): Double = when {
            x < lo -> x / lo
            x > hi -> max(0.0, 1 - (x - hi) / hi)
            else -> 1.0
        }
        val hours = r.asleepMin / 60.0
        val efficiency = if (r.inBedMin == 0) 0.0 else r.asleepMin.toDouble() / r.inBedMin
        val parts = mutableListOf(
            "Duration (7 h+ = 40)" to (40 * min(1.0, hours / 7.0)).roundToInt(),
            "Continuity: asleep vs in bed (90%+ = 20)" to (20 * ((efficiency - 0.75) / 0.15).coerceIn(0.0, 1.0)).roundToInt(),
            "Deep sleep 13–23% (= 15)" to (15 * inBand(r.share(SleepStage.DEEP), 0.13, 0.23)).roundToInt(),
            "REM 18–25% (= 15)" to (15 * inBand(r.share(SleepStage.REM), 0.18, 0.25)).roundToInt(),
            "Awakenings: 0–1 (= 10)" to max(0, 10 - 2 * max(0, r.awakenings - 1)),
        )
        // Good quality can't make up for too little sleep: scale the quality points by hours/7.
        if (hours < 7) {
            val quality = parts.drop(1).sumOf { it.second }
            val cut = (quality * (1 - hours / 7.0)).roundToInt()
            if (cut > 0) parts += "Short night (%.1f h of 7 h): quality points scaled down".format(hours) to -cut
        }
        return parts
    }
}
