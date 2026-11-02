package com.kaya.health.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.max

data class Streak(val days: Int, val doneToday: Boolean)

data class CheckpointView(
    val name: String,
    val kg: Double,
    val date: LocalDate,
    val daysLeft: Long,
    /** Weight expected on [date] at the realistic pace (fat only). */
    val projectedKg: Double,
    /** kg/week needed from today to make it. */
    val neededKgPerWeek: Double,
    val onTrack: Boolean,
    /** Not quite on track, but within about 1.5 kg (early water loss often covers that). */
    val close: Boolean,
    val reached: Boolean,
    /** Share of the way from the goal's start weight to the checkpoint. */
    val progress: Double,
)

data class WeekSteps(
    val done: Long,
    val goal: Int,
    /** Days left in this Monday–Sunday week, today included. */
    val daysLeft: Int,
    /** Steps a day for the rest of the week to still make [goal]. */
    val perDayToGo: Long,
)

/** Small, honest nudges: streaks, a checkpoint countdown, weekly steps and a line for the day. */
object Motivation {

    /** Consecutive days in [done] ending today, or ending yesterday if today isn't done yet (the streak is still alive). */
    fun streak(done: Set<LocalDate>, today: LocalDate): Streak {
        val doneToday = today in done
        var d = if (doneToday) today else today.minusDays(1)
        var n = 0
        while (d in done) { n++; d = d.minusDays(1) }
        return Streak(n, doneToday)
    }

    /** [gaining] = the goal is above the start weight, so the checkpoint is reached from below. */
    fun checkpoint(
        name: String, kg: Double, date: LocalDate,
        startKg: Double, currentKg: Double, kgPerWeek: Double, today: LocalDate, gaining: Boolean = false,
    ): CheckpointView {
        val days = ChronoUnit.DAYS.between(today, date)
        val d = if (gaining) 1.0 else -1.0
        val reached = if (gaining) currentKg >= kg else currentKg <= kg
        val projected = currentKg + d * max(0.0, kgPerWeek) * max(0L, days) / 7.0
        val left = max(0.0, (kg - currentKg) * d)
        val needed = if (days > 0) left / (days / 7.0) else 0.0
        val short = (kg - projected) * d   // how far the projection falls short of the checkpoint
        val span = ((kg - startKg) * d).coerceAtLeast(0.1)
        return CheckpointView(
            name, kg, date, days, projected, needed,
            onTrack = reached || short <= 0.3,
            close = !reached && short > 0.3 && short <= 1.5,
            reached = reached,
            progress = (((currentKg - startKg) * d) / span).coerceIn(0.0, 1.0),
        )
    }

    fun weekSteps(perDay: Map<LocalDate, Long>, today: LocalDate, goal: Int): WeekSteps {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val done = perDay.filterKeys { !it.isBefore(monday) && !it.isAfter(today) }.values.sum()
        val left = (ChronoUnit.DAYS.between(today, monday.plusDays(6)) + 1).toInt()
        val perDay = ceil(max(0L, goal - done).toDouble() / left).toLong()
        return WeekSteps(done, goal, left, perDay)
    }

    data class DayState(
        val weighedToday: Boolean,
        val newLow: Boolean,
        val lostKg: Double,
        val weighStreak: Int,
        val foodStreak: Int,
        val checkpoint: CheckpointView?,
        val eatenKcal: Double,
        val targetKcal: Double?,
        val hour: Int,
    )

    private val general = listOf(
        "You don't need a perfect day. You need a day that counts.",
        "Every 7,700 kcal you don't eat is a kilo that's gone for good.",
        "Slow on the scale is still faster than not starting.",
        "The scale bounces. The trend is the truth. Keep feeding it weigh-ins.",
        "Busy day? A short walk and an honest food log still win it.",
        "Consistency beats intensity. Show up again today.",
        "You're not on a diet, you're running an experiment on yourself. Collect the data.",
        "One meal off plan is a meal. Not a week.",
        "Future you is lighter because of what you do today.",
        "Water, protein, walk, sleep. Boring basics, real results.",
    )

    /** One line for today: contextual when something happened, otherwise a rotating one. */
    fun message(s: DayState, today: LocalDate): String {
        val cp = s.checkpoint
        return when {
            s.newLow -> "New lowest weight. That's the work showing up on the scale."
            cp != null && cp.reached -> "${cp.name}: ${fmt(cp.kg)} kg reached. Next stop, the goal."
            s.lostKg >= 1 && s.weighedToday -> "${fmt(s.lostKg)} kg down so far. That's real."
            s.weighStreak >= 3 -> "${s.weighStreak}-day weigh-in streak. Keep it alive."
            s.foodStreak >= 3 -> "${s.foodStreak} days in a row on your calories. That's how it's done."
            cp != null && cp.daysLeft in 1..60 && cp.close -> "${cp.daysLeft} days to ${cp.name.lowercase()}. ${fmt(cp.kg)} kg is within reach. Every day on plan counts."
            cp != null && cp.daysLeft in 1..60 && cp.onTrack -> "${cp.daysLeft} days to ${cp.name.lowercase()}. On track for ${fmt(cp.kg)} kg."
            cp != null && cp.daysLeft in 1..60 -> "${cp.daysLeft} days to ${cp.name.lowercase()}. Every day on plan pulls it closer."
            !s.weighedToday && s.hour < 12 -> "Morning: weigh in before you eat. It takes ten seconds."
            s.targetKcal != null && s.eatenKcal > 0 && s.eatenKcal <= s.targetKcal && s.hour >= 20 -> "Under your calories today. Close the day strong."
            else -> general[(today.dayOfYear) % general.size]
        }
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) "%.0f".format(v) else "%.1f".format(v)
}
