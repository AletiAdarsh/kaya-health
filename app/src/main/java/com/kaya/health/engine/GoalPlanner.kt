package com.kaya.health.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What the user set: where they want to get to and how fast. */
data class GoalSettings(
    val goalWeightKg: Double,
    val rateKgPerWeek: Double,
    /** When the current goal was set, and the weight then. Used for the planned line and the original date. */
    val startDate: LocalDate,
    val startWeightKg: Double,
)

enum class GoalStatus { DONE, AHEAD, ON_TRACK, BEHIND, NOT_ENOUGH_DATA }

data class GoalPlan(
    val currentKg: Double,
    val goalKg: Double,
    val startKg: Double,
    val bmi: Double,
    val goalBmi: Double,
    val bmr: Double,
    val tdee: Double,
    /** Usual safe ceiling: 1% of body weight per week. */
    val safeRateKg: Double,
    val requestedRateKg: Double,
    /** Rate actually achievable without eating below the safe minimum. */
    val achievableRateKg: Double,
    val minIntake: Double,
    /** Today's calorie target. Adapts so the original date still holds, within safe limits. */
    val dailyTarget: Double,
    val plannedDate: LocalDate,
    /** Expected date if you keep the requested rate from today. */
    val dateAtPlan: LocalDate,
    /** What your weight trend actually did recently (negative = losing). */
    val trendKgPerWeek: Double?,
    val dateAtTrend: LocalDate?,
    /** Where your own plan line says you should weigh today. */
    val planKgToday: Double,
    /** Days left until your own date. */
    val daysToPlanned: Long,
    /** Deficit per day still needed to hit your own date from today. */
    val neededDeficit: Double,
    /** Part of [neededDeficit] that eating the minimum can't cover, so it has to be burned by moving. */
    val extraBurnNeeded: Double,
    /** [extraBurnNeeded] as extra walking steps. */
    val extraStepsNeeded: Long,
    /** How many days sooner the goal comes for every extra 1,000 steps a day. */
    val daysSoonerPer1000Steps: Double,
    val avgIntake: Double?,
    /** Average daily deficit actually achieved recently (TDEE minus logged intake). */
    val avgDeficit: Double?,
    val progress: Double,
    val status: GoalStatus,
    /** True when the goal is above the starting weight (building up rather than losing). */
    val gaining: Boolean = false,
    /** Plain-language verdicts, most important first. */
    val notes: List<String>,
)

/**
 * The weight-goal maths. Every number is shown to the user:
 *  - 1 kg of body fat ≈ 7,700 kcal, so losing r kg/week needs r × 7,700 / 7 kcal/day below your burn (TDEE).
 *  - Safe minimum intake: 1,500 kcal/day (men) / 1,200 (women) without medical supervision.
 *  - Usual safe pace: up to ~1% of body weight per week.
 *  - The daily target re-plans every day: (trend weight − goal) spread over the days left until the planned date.
 */
object GoalPlanner {
    const val KCAL_PER_KG = 7700.0

    fun bmi(kg: Double, heightCm: Double) = kg / ((heightCm / 100) * (heightCm / 100))

    fun bmiLabel(b: Double) = when {
        b < 18.5 -> "underweight"
        b < 25 -> "healthy"
        b < 30 -> "overweight"
        b < 35 -> "obese (class 1)"
        b < 40 -> "obese (class 2)"
        else -> "obese (class 3)"
    }

    fun plan(
        profile: Profile,
        goal: GoalSettings,
        currentKg: Double,
        tdee: Double,
        trendKgPerWeek: Double?,
        avgIntake: Double?,
        today: LocalDate,
    ): GoalPlan {
        if (goal.goalWeightKg > goal.startWeightKg) return planGain(profile, goal, currentKg, tdee, trendKgPerWeek, avgIntake, today)
        val notes = ArrayList<String>()
        val bmr = Calories.bmr(currentKg, profile.heightCm, profile.age, profile.sex)
        val minIntake = if (profile.sex == Sex.MALE) 1500.0 else 1200.0
        val safeRate = 0.01 * currentKg
        val rate = goal.rateKgPerWeek.coerceAtLeast(0.1)
        val toLose = currentKg - goal.goalWeightKg
        val chosen = profile.intakeKcal?.toDouble()
        // With your own intake, that's what you eat: the deficit (and the realistic date) follow from it.
        val maxDeficit = max(0.0, tdee - (chosen ?: minIntake))
        val achievable = if (chosen != null) maxDeficit * 7 / KCAL_PER_KG else min(rate, maxDeficit * 7 / KCAL_PER_KG)

        val totalWeeks = (goal.startWeightKg - goal.goalWeightKg).coerceAtLeast(0.0) / rate
        val plannedDate = goal.startDate.plusDays(ceil(totalWeeks * 7).toLong())
        val dateAtPlan = today.plusDays(ceil(max(0.0, toLose) / achievable.coerceAtLeast(0.05) * 7).toLong())

        // Daily target: what it takes to still hit the planned date from where you are today.
        val daysLeft = ChronoUnit.DAYS.between(today, plannedDate).coerceAtLeast(1)
        val neededDeficit = max(0.0, toLose) * KCAL_PER_KG / daysLeft
        val plannedDeficit = rate * KCAL_PER_KG / 7
        val dailyTarget = chosen ?: (tdee - min(neededDeficit, maxDeficit)).coerceAtLeast(minIntake)

        val extraBurn = max(0.0, neededDeficit - maxDeficit)
        val kcalPerStep = Calories.everydayStepsActiveKcal(1000, currentKg, profile.heightCm) / 1000
        val sinceStart = ChronoUnit.DAYS.between(goal.startDate, today).coerceAtLeast(0)
        val planKgToday = max(goal.goalWeightKg, goal.startWeightKg - rate * sinceStart / 7.0)
        val eatingDeficit = max(1.0, tdee - dailyTarget)
        val per1000 = kcalPerStep * 1000
        val daysSooner = max(0.0, toLose) * KCAL_PER_KG * (1 / eatingDeficit - 1 / (eatingDeficit + per1000))
        val dateAtTrend = trendKgPerWeek?.takeIf { it < -0.05 }?.let { today.plusDays(ceil(max(0.0, toLose) / -it * 7).toLong()) }
        val avgDeficit = avgIntake?.let { tdee - it }

        val status = when {
            toLose <= 0 -> GoalStatus.DONE
            trendKgPerWeek == null -> GoalStatus.NOT_ENOUGH_DATA
            dateAtTrend != null && !dateAtTrend.isAfter(plannedDate.minusDays(7)) && dateAtTrend.isBefore(plannedDate) -> GoalStatus.AHEAD
            dateAtTrend != null && !dateAtTrend.isAfter(plannedDate.plusDays(7)) -> GoalStatus.ON_TRACK
            else -> GoalStatus.BEHIND
        }

        if (toLose <= 0) notes += "You've reached ${goal.goalWeightKg.fmt()} kg. Set a new goal or switch to maintaining."
        if (rate > safeRate * 1.5) notes += "${rate.fmt()} kg/week is very aggressive: more than 1.5% of your body weight per week. Talk to a doctor before trying this."
        else if (rate > safeRate) notes += "${rate.fmt()} kg/week is faster than the usual safe pace (about ${safeRate.fmt()} kg/week, 1% of your weight). Often OK at your size early on, ideally with a doctor's OK."
        else notes += "${rate.fmt()} kg/week is a safe, sustainable pace for you (limit ≈ ${safeRate.fmt()} kg/week)."
        if (chosen != null && chosen < minIntake) {
            notes += "You chose ${chosen.roundToInt()} kcal a day, below the usual ${minIntake.roundToInt()} minimum. Keep protein high (${profile.proteinTarget} g+), drink water, and move back up if you feel dizzy, faint or exhausted for days."
        }
        if (chosen == null && achievable < rate - 0.01) {
            notes += "That pace needs ${plannedDeficit.roundToInt()} kcal/day below your burn (${tdee.roundToInt()}), which means eating under ${minIntake.roundToInt()} kcal, the safe minimum. " +
                "The fastest safe pace with your current burn is ${achievable.fmt()} kg/week; moving more raises it."
        }
        if (neededDeficit > maxDeficit && toLose > 0) {
            notes += "To reach ${goal.goalWeightKg.fmt()} kg by ${plannedDate.fmtDate()}: eat ${dailyTarget.roundToInt()} and burn about ${extraBurn.roundToInt()} kcal extra a day by moving " +
                "(≈ ${"%,d".format(if (kcalPerStep > 0) (extraBurn / kcalPerStep).roundToLong() else 0)} extra steps). Without the extra movement, the realistic date is ${dateAtPlan.fmtDate()}."
        }
        if (trendKgPerWeek != null) {
            notes += when {
                trendKgPerWeek < -0.05 -> "Your weight trend: −${(-trendKgPerWeek).fmt()} kg/week. At this pace you reach ${goal.goalWeightKg.fmt()} kg around ${dateAtTrend?.fmtDate()}."
                trendKgPerWeek > 0.05 -> "Your weight trend is going up (+${trendKgPerWeek.fmt()} kg/week). Check that all food is logged and the daily target is being met."
                else -> "Your weight trend is flat. Eat closer to the daily target to start losing."
            }
        } else notes += "Weigh in on a few mornings so Kaya can see your real pace."
        if (avgDeficit != null) {
            val kgWk = avgDeficit * 7 / KCAL_PER_KG
            notes += if (avgDeficit > 0) "From your food log you've averaged ${avgDeficit.roundToInt()} kcal/day under your burn ≈ ${kgWk.fmt()} kg/week."
            else "From your food log you've averaged ${(-avgDeficit).roundToInt()} kcal/day over your burn, which means gaining."
        }

        return GoalPlan(
            currentKg = currentKg, goalKg = goal.goalWeightKg, startKg = goal.startWeightKg,
            bmi = bmi(currentKg, profile.heightCm), goalBmi = bmi(goal.goalWeightKg, profile.heightCm),
            bmr = bmr, tdee = tdee, safeRateKg = safeRate, requestedRateKg = rate, achievableRateKg = achievable,
            minIntake = minIntake, dailyTarget = dailyTarget, plannedDate = plannedDate, dateAtPlan = dateAtPlan,
            trendKgPerWeek = trendKgPerWeek, dateAtTrend = dateAtTrend,
            planKgToday = planKgToday, daysToPlanned = ChronoUnit.DAYS.between(today, plannedDate), neededDeficit = neededDeficit,
            extraBurnNeeded = extraBurn, daysSoonerPer1000Steps = daysSooner, extraStepsNeeded = if (kcalPerStep > 0) (extraBurn / kcalPerStep).roundToLong() else 0, avgIntake = avgIntake, avgDeficit = avgDeficit,
            progress = ((goal.startWeightKg - currentKg) / (goal.startWeightKg - goal.goalWeightKg).coerceAtLeast(0.1)).coerceIn(0.0, 1.0),
            status = status, notes = notes,
        )
    }

    /**
     * Gaining weight: a planned surplus. Safe lean gain is roughly 0.25–0.5 kg a week; faster mostly adds fat.
     * Same shape of result as losing, so every screen works for both.
     */
    private fun planGain(
        profile: Profile, goal: GoalSettings, currentKg: Double, tdee: Double,
        trendKgPerWeek: Double?, avgIntake: Double?, today: LocalDate,
    ): GoalPlan {
        val notes = ArrayList<String>()
        val bmr = Calories.bmr(currentKg, profile.heightCm, profile.age, profile.sex)
        val safeRate = 0.5
        val rate = goal.rateKgPerWeek.coerceIn(0.1, 1.0)
        val toGain = goal.goalWeightKg - currentKg
        val totalWeeks = (goal.goalWeightKg - goal.startWeightKg).coerceAtLeast(0.0) / rate
        val plannedDate = goal.startDate.plusDays(ceil(totalWeeks * 7).toLong())
        val daysLeft = ChronoUnit.DAYS.between(today, plannedDate).coerceAtLeast(1)
        val neededSurplus = max(0.0, toGain) * KCAL_PER_KG / daysLeft
        val chosen = profile.intakeKcal?.toDouble()
        val dailyTarget = chosen ?: (tdee + min(neededSurplus, 1000.0))
        val surplus = dailyTarget - tdee
        val achievable = max(0.0, surplus) * 7 / KCAL_PER_KG
        val dateAtPlan = today.plusDays(ceil(max(0.0, toGain) / achievable.coerceAtLeast(0.05) * 7).toLong())
        val dateAtTrend = trendKgPerWeek?.takeIf { it > 0.05 }?.let { today.plusDays(ceil(max(0.0, toGain) / it * 7).toLong()) }
        val sinceStart = ChronoUnit.DAYS.between(goal.startDate, today).coerceAtLeast(0)
        val planKgToday = min(goal.goalWeightKg, goal.startWeightKg + rate * sinceStart / 7.0)
        val status = when {
            toGain <= 0 -> GoalStatus.DONE
            trendKgPerWeek == null -> GoalStatus.NOT_ENOUGH_DATA
            dateAtTrend != null && dateAtTrend.isBefore(plannedDate.minusDays(7)) -> GoalStatus.AHEAD
            dateAtTrend != null && !dateAtTrend.isAfter(plannedDate.plusDays(7)) -> GoalStatus.ON_TRACK
            else -> GoalStatus.BEHIND
        }
        if (toGain <= 0) notes += "You've reached ${goal.goalWeightKg.fmt()} kg. Set a new goal or switch to maintaining."
        notes += if (rate > safeRate) "${rate.fmt()} kg/week is fast for gaining: above about 0.5 kg/week most of the extra is fat, not muscle."
        else "${rate.fmt()} kg/week is a sensible gaining pace. Pair it with strength training and plenty of protein (${profile.proteinTarget} g+)."
        notes += "That's about ${surplus.roundToInt()} kcal a day above your burn (${tdee.roundToInt()})."
        if (trendKgPerWeek != null) notes += when {
            trendKgPerWeek > 0.05 -> "Your weight trend: +${trendKgPerWeek.fmt()} kg/week. At this pace you reach ${goal.goalWeightKg.fmt()} kg around ${dateAtTrend?.fmtDate()}."
            trendKgPerWeek < -0.05 -> "Your weight is going down (−${(-trendKgPerWeek).fmt()} kg/week). Eat more, and log everything so Kaya can see it."
            else -> "Your weight is flat. Add a snack or a bigger portion to start gaining."
        } else notes += "Weigh in on a few mornings so Kaya can see your real pace."
        return GoalPlan(
            currentKg = currentKg, goalKg = goal.goalWeightKg, startKg = goal.startWeightKg,
            bmi = bmi(currentKg, profile.heightCm), goalBmi = bmi(goal.goalWeightKg, profile.heightCm),
            bmr = bmr, tdee = tdee, safeRateKg = safeRate, requestedRateKg = rate, achievableRateKg = achievable,
            minIntake = tdee, dailyTarget = dailyTarget, plannedDate = plannedDate, dateAtPlan = dateAtPlan,
            trendKgPerWeek = trendKgPerWeek, dateAtTrend = dateAtTrend,
            planKgToday = planKgToday, daysToPlanned = ChronoUnit.DAYS.between(today, plannedDate), neededDeficit = -neededSurplus,
            extraBurnNeeded = 0.0, daysSoonerPer1000Steps = 0.0, extraStepsNeeded = 0, avgIntake = avgIntake, avgDeficit = avgIntake?.let { tdee - it },
            progress = ((currentKg - goal.startWeightKg) / (goal.goalWeightKg - goal.startWeightKg).coerceAtLeast(0.1)).coerceIn(0.0, 1.0),
            status = status, notes = notes, gaining = true,
        )
    }

    private fun Double.fmt() = if (this >= 10) "%.1f".format(this) else "%.2f".format(this).trimEnd('0').trimEnd('.')
    private fun LocalDate.fmtDate() = format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
}
