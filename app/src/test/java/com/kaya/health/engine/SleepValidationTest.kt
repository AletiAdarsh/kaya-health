package com.kaya.health.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Validates SleepAnalyzer against real sleep-lab nights.
 *
 * Dataset: Walch et al. 2019, "Motion and heart rate from a wrist-worn wearable and labeled sleep from
 * polysomnography" (PhysioNet sleep-accel 1.0.0, ODC-By). 31 adults, Apple Watch heart rate, and lab sleep stages
 * scored every 30 s by technicians.
 *
 * Runs only when KAYA_WALCH_DIR points at a folder with hr/, lab/, steps/. Writes a report to
 * build/sleep-validation.txt. The data is not stored in the repo.
 */
class SleepValidationTest {

    private enum class T { WAKE, LIGHT, DEEP, REM }

    private fun truth(code: Int): T? = when (code) {
        0 -> T.WAKE; 1, 2 -> T.LIGHT; 3, 4 -> T.DEEP; 5 -> T.REM; else -> null
    }

    private fun pred(s: SleepStage): T = when (s) {
        SleepStage.AWAKE -> T.WAKE; SleepStage.LIGHT -> T.LIGHT; SleepStage.DEEP -> T.DEEP; SleepStage.REM -> T.REM
    }

    class Subject(val id: String, val hr: List<Pair<Double, Int>>, val labels: List<Pair<Int, Int>>, val steps: List<Pair<Double, Int>>)

    private fun load(dir: File): List<Subject> = File(dir, "lab").listFiles()!!.sorted().mapNotNull { lab ->
        val id = lab.name.substringBefore("_")
        val hrF = File(dir, "hr/${id}_heartrate.txt")
        if (!hrF.exists()) return@mapNotNull null
        val hr = hrF.readLines().mapNotNull { l -> l.split(",").takeIf { it.size == 2 }?.let { it[0].toDouble() to it[1].trim().toDouble().roundToInt() } }
        val labels = lab.readLines().mapNotNull { l -> l.trim().split(Regex("\\s+")).takeIf { it.size == 2 }?.let { it[0].toDouble().toInt() to it[1].toInt() } }
        val stF = File(dir, "steps/${id}_steps.txt")
        val steps = if (stF.exists()) stF.readLines().mapNotNull { l -> l.split(",").takeIf { it.size == 2 }?.let { it[0].toDouble() to it[1].trim().toInt() } } else emptyList()
        Subject(id, hr, labels, steps)
    }

    /** t = 0 (lab "lights off") is placed at 23:00. */
    private val anchor = LocalDateTime.of(2026, 1, 1, 23, 0)
    private fun at(sec: Double): LocalDateTime = anchor.plusNanos((sec * 1e9).toLong())

    data class Scores(
        val n: Int, val acc4: Double, val kappa: Double, val sleepWakeAcc: Double, val sens: Double, val spec: Double,
        val tstErrMin: Double, val baselineLight: Double,
        val trueMin: Map<String, Int>, val predMin: Map<String, Int>,
    )

    val perSubject = ArrayList<String>()

    fun evaluate(subjects: List<Subject>, analyze: (List<Pair<LocalDateTime, Int>>, Map<LocalDateTime, Int>, LocalDateTime, LocalDateTime) -> SleepResult?): Scores {
        val cm = Array(4) { IntArray(4) }
        var tstErr = 0.0
        for (s in subjects) {
            val lastLabel = s.labels.maxOf { it.first }
            // Mimic the band: one averaged reading per 30-s slot, from 2 h before lights-off to 1 h after the end.
            val slots = HashMap<Long, MutableList<Int>>()
            for ((t, v) in s.hr) if (t >= -7200 && t <= lastLabel + 3600 && v > 0) slots.getOrPut(Math.floorDiv(t.toLong(), 30L)) { ArrayList() } += v
            val readings = slots.toSortedMap().map { (k, v) -> at(k * 30.0) to v.average().roundToInt() }
            val hourly = HashMap<LocalDateTime, Int>()
            for ((t, v) in s.steps) if (t >= -7200 && t <= lastLabel + 3600) {
                val h = at(t).withMinute(0).withSecond(0).withNano(0)
                hourly[h] = (hourly[h] ?: 0) + v
            }
            val r = analyze(readings, hourly, at(-7200.0), at(lastLabel + 3600.0))
            val sleepT = s.labels.filter { (truth(it.second) ?: T.WAKE) != T.WAKE }.map { it.first }
            perSubject += "${s.id}: lab sleep ${at(sleepT.min().toDouble()).toLocalTime()}-${at(sleepT.max().toDouble()).toLocalTime()} | kaya " +
                (r?.let { "${it.bedtime.toLocalTime()}-${it.wake.toLocalTime()} asleep ${it.asleepMin}m" } ?: "NO SLEEP FOUND")
            var trueSleep = 0; var predSleep = 0
            for ((t, code) in s.labels) {
                val tr = truth(code) ?: continue
                val time = at(t.toDouble() + 15)
                val p = if (r == null || time.isBefore(r.bedtime) || !time.isBefore(r.wake)) T.WAKE
                else pred(r.epochs.lastOrNull { !it.start.isAfter(time) }?.stage ?: SleepStage.LIGHT)
                cm[tr.ordinal][p.ordinal]++
                if (tr != T.WAKE) trueSleep++
                if (p != T.WAKE) predSleep++
            }
            tstErr += abs(trueSleep - predSleep) / 2.0
        }
        val total = cm.sumOf { it.sum() }.toDouble()
        val correct = (0 until 4).sumOf { cm[it][it] }.toDouble()
        val pe = (0 until 4).sumOf { i -> cm[i].sum().toDouble() * (0 until 4).sumOf { j -> cm[j][i] }.toDouble() } / (total * total)
        val acc = correct / total
        val wakeRow = cm[0]; val sleepRows = (1..3).map { cm[it] }
        val tn = wakeRow[0].toDouble(); val fp = wakeRow.sum() - tn
        val tp = sleepRows.sumOf { it.sum() - it[0] }.toDouble(); val fn = sleepRows.sumOf { it[0] }.toDouble()
        val names = T.entries.map { it.name }
        return Scores(
            n = subjects.size, acc4 = acc, kappa = (acc - pe) / (1 - pe), sleepWakeAcc = (tp + tn) / total,
            sens = tp / (tp + fn), spec = tn / (tn + fp), tstErrMin = tstErr / subjects.size,
            baselineLight = cm[1].sum() / total,
            trueMin = names.mapIndexed { i, n -> n to cm[i].sum() / 2 / subjects.size }.toMap(),
            predMin = names.mapIndexed { i, n -> n to (0 until 4).sumOf { j -> cm[j][i] } / 2 / subjects.size }.toMap(),
        )
    }

    fun fmt(label: String, s: Scores) = buildString {
        appendLine("== $label (n=${s.n})")
        appendLine("  4-stage accuracy %.1f%%  (always-'light' baseline %.1f%%)  Cohen's kappa %.2f".format(s.acc4 * 100, s.baselineLight * 100, s.kappa))
        appendLine("  asleep-vs-awake accuracy %.1f%%  sleep detected %.1f%%  wake detected %.1f%%".format(s.sleepWakeAcc * 100, s.sens * 100, s.spec * 100))
        appendLine("  total-sleep error %.0f min/night".format(s.tstErrMin))
        appendLine("  avg minutes per night  lab=${s.trueMin}  kaya=${s.predMin}")
    }

    @Test fun validateAgainstSleepLab() {
        val dir = System.getenv("KAYA_WALCH_DIR")?.let(::File)
        assumeTrue("KAYA_WALCH_DIR not set; skipping sleep-lab validation", dir != null && File(dir, "lab").isDirectory)
        val subjects = load(dir!!)
        val all = evaluate(subjects) { r, h, a, b -> SleepAnalyzer.analyze(r, h, a, b) }
        val report = fmt("Kaya SleepAnalyzer vs sleep lab, all subjects", all)
        File("build/sleep-validation.txt").writeText(report + "\n" + perSubject.joinToString("\n"))
        println(report)
    }
}
