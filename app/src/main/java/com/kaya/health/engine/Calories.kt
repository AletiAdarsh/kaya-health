package com.kaya.health.engine

import kotlin.math.max

enum class Sex { MALE, FEMALE }

enum class ActivityKind(val label: String) {
    WALK("Walk"), RUN("Run"), HIKE("Hike"), CYCLE("Cycling"), STRENGTH("Strength"),
    YOGA("Yoga"), CARDIO_MACHINE("Cardio machine"), SWIM("Swim"), OTHER("Workout");

    val onFeet: Boolean get() = this == WALK || this == RUN || this == HIKE
}

data class Profile(
    val heightCm: Double = 175.0,
    val age: Int = 25,
    val sex: Sex = Sex.MALE,
    /** Used only until a weight exists in Health Connect. */
    val fallbackWeightKg: Double = 80.0,
    /** How far below your real burn to eat each day. 500 kcal ≈ 0.45 kg of fat per week. */
    val deficitKcal: Int = 500,
    val setupDone: Boolean = false,
    /** Weight goal. null = no goal set (maintain). */
    val goalWeightKg: Double? = null,
    val rateKgPerWeek: Double = 1.0,
    /** ISO date the goal was set, and the weight then. */
    val goalStartDate: String? = null,
    val goalStartWeightKg: Double? = null,
    val stepGoal: Int = 8000,
    /** Your own daily intake (kcal). null = let Kaya plan it (never below the usual safe minimum). */
    val intakeKcal: Int? = null,
    /** Daily protein goal in grams; 0 = automatic ([proteinTarget]). */
    val proteinGoalG: Int = 0,
    /** Steps per Monday–Sunday week. */
    val weeklySteps: Int = 49000,
    /** Nightly sleep goal, minutes (used for sleep debt and the 7-night chart). */
    val sleepGoalMin: Int = 450,
    /** Water goal in 250 ml glasses; 0 = automatic from weight. */
    val waterGoalGlasses: Int = 0,
    /** Known maximum heart rate; 0 = estimate it as 208 − 0.7 × age (Tanaka). */
    val maxHrOverride: Int = 0,
    /** A checkpoint on the way, e.g. a weight to reach by a birthday or a trip. */
    val checkpointKg: Double? = null,
    val checkpointDate: String? = null,
    val checkpointName: String? = null,
    /** Home's motivation card (daily line, checkpoint countdown, checklist, streaks). */
    val showMotivation: Boolean = true,
    /** Confetti for wins (each kilo lost, a full checklist, a checkpoint reached). */
    val confetti: Boolean = true,
    /** Height entered in centimetres instead of feet and inches. */
    val heightMetric: Boolean = false,
    /** The app's look: "CLASSIC" or "SAKURA" (Sakura Night). */
    val look: String = "CLASSIC",
) {
    /** Protein goal: yours if set, otherwise about 1.2 g per kg of goal weight (or current weight), a common target while losing fat. */
    val proteinTarget: Int get() =
        if (proteinGoalG > 0) proteinGoalG else ((goalWeightKg ?: fallbackWeightKg) * 1.2).toInt().coerceIn(60, 200)

    /** A day's fair share of the weekly step goal. */
    /** Maximum heart rate: the user's own if known, else Tanaka's 208 − 0.7 × age. */
    val maxHr: Double get() = if (maxHrOverride in 120..230) maxHrOverride.toDouble() else 208 - 0.7 * age
    val dailyStepTarget: Int get() = (weeklySteps / 7).coerceAtLeast(1000)
}

data class SessionInput(
    val kind: ActivityKind,
    val durationMin: Double,
    /** Distance measured by the watch/phone, if any. */
    val distanceM: Double?,
    val steps: Long?,
)

data class SessionEstimate(
    /** Everything burned during the session, including what the body burns at rest anyway. */
    val totalKcal: Double,
    /** Only the extra caused by the activity (total minus resting). This is what to compare against food. */
    val activeKcal: Double,
    val speedKmh: Double?,
    val distanceM: Double?,
    val method: String,
    /** The calculation written out line by line, so it can be checked by hand. */
    val working: List<String> = emptyList(),
)

/**
 * Calorie maths based on how much oxygen the body uses, which is what labs actually measure.
 *
 * Walking/running use the ACSM metabolic equations. They depend on body weight, speed and slope,
 * not on heart rate. Heart rate also depends on fitness, heat, sleep and caffeine, which don't change
 * the calories a walk burns.
 */
object Calories {
    /** ≈ kcal released per litre of oxygen used. */
    const val KCAL_PER_LITRE_O2 = 5.0
    /** Oxygen used at rest, ml per kg per minute (= 1 MET). */
    const val REST_VO2 = 3.5
    /** Energy in 1 kg of body fat. */
    const val KCAL_PER_KG_FAT = 7700.0

    /** Typical stride as a fraction of height, used when only steps are known. */
    fun strideM(heightCm: Double, running: Boolean): Double =
        heightCm / 100.0 * if (running) 0.65 else 0.415

    /** Mifflin–St Jeor resting metabolic rate, kcal/day. */
    fun bmr(weightKg: Double, heightCm: Double, age: Int, sex: Sex): Double =
        10 * weightKg + 6.25 * heightCm - 5 * age + if (sex == Sex.MALE) 5.0 else -161.0

    /** ACSM walking equation. Speed in metres/minute, grade as a fraction (0.05 = 5% slope). Returns ml O2/kg/min. */
    fun walkingVo2(speedMPerMin: Double, grade: Double = 0.0): Double =
        0.1 * speedMPerMin + 1.8 * speedMPerMin * grade + REST_VO2

    /** ACSM running equation. Same units as [walkingVo2]. */
    fun runningVo2(speedMPerMin: Double, grade: Double = 0.0): Double =
        0.2 * speedMPerMin + 0.9 * speedMPerMin * grade + REST_VO2

    fun kcalFromVo2(vo2: Double, weightKg: Double, minutes: Double): Double =
        vo2 * weightKg / 1000.0 * KCAL_PER_LITRE_O2 * minutes

    /** Rough averages (Compendium of Physical Activities) for sessions with no distance. */
    fun metFor(kind: ActivityKind): Double = when (kind) {
        ActivityKind.WALK -> 3.5
        ActivityKind.RUN -> 8.0
        ActivityKind.HIKE -> 6.0
        ActivityKind.CYCLE -> 6.0
        ActivityKind.STRENGTH -> 3.5
        ActivityKind.YOGA -> 2.5
        ActivityKind.CARDIO_MACHINE -> 5.0
        ActivityKind.SWIM -> 6.0
        ActivityKind.OTHER -> 3.5
    }

    fun estimate(s: SessionInput, weightKg: Double, heightCm: Double): SessionEstimate {
        val minutes = s.durationMin
        if (minutes <= 0.0) return SessionEstimate(0.0, 0.0, null, s.distanceM, "No duration recorded")

        val working = mutableListOf<String>()
        var dist = s.distanceM?.takeIf { it > 0 }
        var distSource = "measured distance"
        if (dist != null) {
            working += "Distance: %,.0f m (as recorded)".format(dist)
        } else if (s.kind.onFeet && (s.steps ?: 0L) > 0L) {
            val stride = strideM(heightCm, s.kind == ActivityKind.RUN)
            dist = s.steps!! * stride
            distSource = "distance worked out from steps"
            working += "Distance: %,d steps × %.2f m stride = %,.0f m".format(s.steps, stride, dist)
        }

        val speed = dist?.let { it / minutes } // metres per minute
        if (speed != null && (s.kind.onFeet || (s.kind == ActivityKind.OTHER && speed >= 50.0))) {
            val running = s.kind == ActivityKind.RUN || speed > 134.0 // nobody walks faster than ~8 km/h
            val grade = if (s.kind == ActivityKind.HIKE) 0.05 else 0.0
            val vo2 = if (running) runningVo2(speed, grade) else walkingVo2(speed, grade)
            val total = kcalFromVo2(vo2, weightKg, minutes)
            val active = kcalFromVo2(vo2 - REST_VO2, weightKg, minutes)
            val eq = if (running) "running" else "walking"
            val slope = if (grade > 0) "assumes a 5% slope" else "assumes flat ground"
            working += "Speed: %,.0f m ÷ %.1f min = %.1f m/min (%.1f km/h)".format(dist, minutes, speed, speed * 0.06)
            val gradeTerm = if (grade > 0) " + ${if (running) "0.9" else "1.8"} × %.1f × %.2f".format(speed, grade) else ""
            working += "Oxygen used ($eq equation): ${if (running) "0.2" else "0.1"} × %.1f$gradeTerm + 3.5 = %.2f ml per kg per min".format(speed, vo2)
            working += "Total: %.2f × %.1f kg ÷ 1000 × 5 kcal per litre × %.1f min = %,.0f kcal".format(vo2, weightKg, minutes, total)
            working += "Extra from moving: (%.2f − 3.5 resting) × %.1f kg ÷ 1000 × 5 × %.1f min = %,.0f kcal".format(vo2, weightKg, minutes, active)
            return SessionEstimate(total, active, speed * 60.0 / 1000.0, dist, "Lab $eq equation (ACSM) · $distSource · $slope", working)
        }

        val met = metFor(s.kind)
        val total = met * weightKg * minutes / 60.0
        val active = max(0.0, met - 1.0) * weightKg * minutes / 60.0
        working += "No distance, so using the average for ${s.kind.label.lowercase()}: %.1f METs".format(met)
        working += "Total: %.1f × %.1f kg × %.1f min ÷ 60 = %,.0f kcal".format(met, weightKg, minutes, total)
        working += "Extra from moving: (%.1f − 1) × %.1f kg × %.1f min ÷ 60 = %,.0f kcal".format(met, weightKg, minutes, active)
        return SessionEstimate(total, active, null, dist, "Rough average for this activity (%.1f METs), no distance available".format(met), working)
    }

    /**
     * Likely range around a day's burn. The resting formula (Mifflin–St Jeor) is within ±10% for about 3 in 4
     * people with obesity, and step counting misses movement that isn't steps (standing, fidgeting, carrying).
     * So the range reaches a little below (−5% of resting) and further above (+15% of resting), rounded to 10s.
     */
    fun burnRange(total: Double, resting: Double): Pair<Double, Double> {
        fun r10(x: Double) = Math.round(x / 10.0) * 10.0
        return r10(total - 0.05 * resting) to r10(total + 0.15 * resting)
    }

    /** Extra calories from everyday steps outside workouts. Net walking cost ≈ 0.5 kcal per kg per km. */
    fun everydayStepsActiveKcal(steps: Long, weightKg: Double, heightCm: Double): Double {
        val km = steps * strideM(heightCm, running = false) / 1000.0
        return 0.5 * weightKg * km
    }

    /**
     * The heart-rate formula most watches use (Keytel et al., 2005, without a fitness term).
     * NoiseFit's workout numbers match this within a few percent. Kept here only to show
     * where the watch's number comes from. Kaya uses only part of it, for walks: see [Walk.heartExtraKcal].
     */
    fun heartRateFormulaKcal(avgHr: Double, weightKg: Double, age: Int, sex: Sex, minutes: Double): Double {
        val kjPerMin = if (sex == Sex.MALE)
            -55.0969 + 0.6309 * avgHr + 0.1988 * weightKg + 0.2017 * age
        else
            -20.4022 + 0.4472 * avgHr - 0.1263 * weightKg + 0.074 * age
        return max(0.0, kjPerMin / 4.184) * minutes
    }
}
