package com.kaya.health.engine

import kotlin.math.roundToInt

/**
 * Fitness age: the age at which an average person has your aerobic fitness (VO2max).
 *
 * 1. VO2max is estimated with the HUNT non-exercise model (Nes et al., Med Sci Sports Exerc 2011; ~4,600 adults,
 *    checked against treadmill tests): age, waist (or BMI), resting heart rate and a physical-activity index.
 * 2. That VO2max is placed on the average-by-age curve of the FRIEND registry (Kaminsky et al., Mayo Clin Proc 2015),
 *    and the matching age is your fitness age (the idea behind Nes et al. 2013's "fitness age").
 *
 * It's an estimate (about ±5 ml/kg/min for VO2max), best used to watch the trend.
 */
object FitnessAge {
    /** FRIEND treadmill VO2max means by age decade (midpoints), ml/kg/min. */
    private val MEN = listOf(25 to 48.0, 35 to 42.4, 45 to 37.8, 55 to 32.6, 65 to 28.2, 75 to 24.4)
    private val WOMEN = listOf(25 to 37.6, 35 to 30.9, 45 to 27.9, 55 to 24.2, 65 to 20.9, 75 to 18.3)

    /**
     * HUNT physical-activity index = frequency × intensity × duration.
     * Frequency per week: never 0, <1 0.5, 1 1, 2–3 2.5, ≥4 5. Intensity: easy 1, breathless/sweaty 5, near exhaustion 10.
     * Duration per session: <15 min 0.1, 15–29 0.38, 30–60 0.75, >60 1.
     */
    fun activityIndex(sessionsPerWeek: Double, typicalMinutes: Double, hard: Boolean): Double {
        val f = when { sessionsPerWeek <= 0.0 -> 0.0; sessionsPerWeek < 1 -> 0.5; sessionsPerWeek < 2 -> 1.0; sessionsPerWeek < 4 -> 2.5; else -> 5.0 }
        val d = when { typicalMinutes < 15 -> 0.1; typicalMinutes < 30 -> 0.38; typicalMinutes <= 60 -> 0.75; else -> 1.0 }
        return f * (if (hard) 5.0 else 1.0) * d
    }

    /** Estimated VO2max, ml/kg/min. Uses waist when known (the model's better version), otherwise BMI. */
    fun vo2max(age: Int, sex: Sex, restingHr: Double, activityIndex: Double, waistCm: Double?, bmi: Double): Double = when {
        waistCm != null && sex == Sex.MALE -> 100.27 - 0.296 * age - 0.369 * waistCm - 0.155 * restingHr + 0.226 * activityIndex
        waistCm != null -> 74.74 - 0.247 * age - 0.259 * waistCm - 0.114 * restingHr + 0.198 * activityIndex
        sex == Sex.MALE -> 92.05 - 0.327 * age - 0.933 * bmi - 0.167 * restingHr + 0.257 * activityIndex
        else -> 70.77 - 0.244 * age - 0.749 * bmi - 0.107 * restingHr + 0.213 * activityIndex
    }

    /** The age whose average VO2max equals [vo2], read off the reference curve (20–80). */
    fun ageFor(vo2: Double, sex: Sex): Int {
        val ref = if (sex == Sex.MALE) MEN else WOMEN
        if (vo2 >= ref.first().second) {
            val (a0, v0) = ref[0]; val (a1, v1) = ref[1]
            return (a0 - (vo2 - v0) / (v0 - v1) * (a1 - a0)).roundToInt().coerceAtLeast(20)
        }
        for (i in 0 until ref.size - 1) {
            val (a0, v0) = ref[i]; val (a1, v1) = ref[i + 1]
            if (vo2 <= v0 && vo2 >= v1) return (a0 + (v0 - vo2) / (v0 - v1) * (a1 - a0)).roundToInt()
        }
        val (a0, v0) = ref[ref.size - 2]; val (a1, v1) = ref.last()
        return (a1 + (v1 - vo2) / (v0 - v1) * (a1 - a0)).roundToInt().coerceAtMost(80)
    }

    data class Result(
        val vo2max: Double,
        val fitnessAge: Int,
        /** What-ifs: label to fitness age. */
        val whatIf: List<Pair<String, Int>>,
        val usedWaist: Boolean,
    )

    fun compute(
        age: Int, sex: Sex, heightCm: Double, weightKg: Double, restingHr: Double,
        sessionsPerWeek: Double, typicalMinutes: Double, hard: Boolean, waistCm: Double?, goalKg: Double?,
    ): Result {
        val h = heightCm / 100
        val bmi = weightKg / (h * h)
        val pa = activityIndex(sessionsPerWeek, typicalMinutes, hard)
        val v = vo2max(age, sex, restingHr, pa, waistCm, bmi)
        fun ageWith(w: Double = weightKg, rhr: Double = restingHr, act: Double = pa, waist: Double? = waistCm): Int {
            // Losing weight shrinks the waist too: about 1 cm per kg in large weight-loss studies.
            val waistNew = waist?.let { it - (weightKg - w) }
            return ageFor(vo2max(age, sex, rhr, act, waistNew, w / (h * h)), sex)
        }
        val whatIf = buildList {
            goalKg?.takeIf { it < weightKg - 1 }?.let { add("At your goal of %.0f kg".format(it) to ageWith(w = it)) }
            add("Walking briskly 30–60 min, 4+ days a week" to ageWith(act = activityIndex(4.0, 45.0, true)))
            if (restingHr > 60) add("Resting heart rate 8 bpm lower" to ageWith(rhr = restingHr - 8))
            goalKg?.takeIf { it < weightKg - 1 }?.let {
                add("All three together" to ageWith(w = it, rhr = maxOf(50.0, restingHr - 8), act = activityIndex(4.0, 45.0, true)))
            }
        }
        return Result(v, ageFor(v, sex), whatIf, waistCm != null)
    }
}
