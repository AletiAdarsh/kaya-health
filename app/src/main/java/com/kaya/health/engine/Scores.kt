package com.kaya.health.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A 0–100 score with every contributor shown, so nothing is a black box. */
data class Score(val value: Int, val parts: List<Contributor>, val headline: String)
data class Contributor(val label: String, val points: Int, val outOf: Int, val detail: String)

object Scores {
    /**
     * Energy (readiness): how recovered you are this morning. Built like Samsung's Energy Score / Oura Readiness:
     * last night's sleep plus overnight resting heart rate and HRV compared with YOUR usual (baseline).
     * Lower resting HR and higher HRV than usual = better recovered.
     */
    fun energy(sleepScore: Int?, restingHr: Double?, restingBaseline: Double?, hrv: Double?, hrvBaseline: Double?): Score {
        val parts = ArrayList<Contributor>()
        parts += if (sleepScore != null) Contributor("Last night's sleep", (sleepScore * 0.4).roundToInt(), 40, "sleep score $sleepScore")
        else Contributor("Last night's sleep", 20, 40, "no sleep analysed yet, counted as average")
        parts += when {
            restingHr == null -> Contributor("Resting heart rate", 18, 30, "no reading yet, counted as average")
            restingBaseline == null -> Contributor("Resting heart rate", 22, 30, "${restingHr.roundToInt()} bpm; baseline builds over a few days")
            else -> {
                val diff = restingHr - restingBaseline
                val pts = (30 - max(0.0, diff) * 4).coerceIn(0.0, 30.0).roundToInt()
                Contributor("Resting heart rate", pts, 30, "${restingHr.roundToInt()} bpm vs your usual ${restingBaseline.roundToInt()}" +
                    if (diff > 2) " (higher: strain, poor sleep, illness or alcohol)" else " (good)")
            }
        }
        parts += when {
            hrv == null -> Contributor("Heart-rate variability", 18, 30, "no reading yet, counted as average")
            hrvBaseline == null -> Contributor("Heart-rate variability", 22, 30, "${hrv.roundToInt()} ms; baseline builds over a few days")
            else -> {
                val pctBelow = max(0.0, (hrvBaseline - hrv) / hrvBaseline * 100)
                val pts = (30 - pctBelow * 1.5).coerceIn(0.0, 30.0).roundToInt()
                Contributor("Heart-rate variability", pts, 30, "${hrv.roundToInt()} ms vs your usual ${hrvBaseline.roundToInt()}" +
                    if (pctBelow > 10) " (lower: body still recovering)" else " (good)")
            }
        }
        val v = parts.sumOf { it.points }
        return Score(v, parts, when { v >= 80 -> "Charged"; v >= 60 -> "Good to go"; v >= 40 -> "Take it steady"; else -> "Recover today" })
    }

    /**
     * Effort: how much you moved today against your goals. Steps, active minutes (from the band) and minutes with
     * heart rate in an exercise zone (above resting + half your heart-rate reserve).
     */
    fun effort(steps: Long?, stepGoal: Int, activeMinutes: Long?, zoneMinutes: Int): Score {
        val s = steps ?: 0
        val a = activeMinutes ?: 0
        val parts = listOf(
            Contributor("Steps", (50 * min(1.0, s.toDouble() / stepGoal)).roundToInt(), 50, "%,d of %,d".format(s, stepGoal)),
            Contributor("Walking minutes", (30 * min(1.0, a / 30.0)).roundToInt(), 30, "$a of 30 min, from steps"),
            Contributor("Heart-rate zone time", (20 * min(1.0, zoneMinutes / 20.0)).roundToInt(), 20, "$zoneMinutes of 20 min with your heart rate up"),
        )
        val v = parts.sumOf { it.points }
        return Score(v, parts, when { v >= 85 -> "Goal smashed"; v >= 60 -> "Active day"; v >= 30 -> "Getting there"; else -> "Let's move" })
    }

    /** Minutes in an exercise zone: HR ≥ resting + 50% of (max − resting), max = 208 − 0.7 × age (Tanaka). */
    fun zoneMinutes(hrReadings30s: List<Int>, restingHr: Double, age: Int): Int {
        val maxHr = 208 - 0.7 * age
        val threshold = restingHr + 0.5 * (maxHr - restingHr)
        return hrReadings30s.count { it >= threshold } / 2
    }
}
