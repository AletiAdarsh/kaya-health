package com.kaya.health.engine

import com.kaya.health.data.FoodItem
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** A suggested meal: one to three foods from the built-in list. */
data class MealIdea(val items: List<FoodItem>, val kcal: Double, val protein: Double)

/** A short, kind explanation of what the scale is doing. */
data class WeightNote(val title: String, val text: String)

/** How sleep lines up with eating, from the user's own history. */
data class SleepLink(val shortNights: Int, val otherNights: Int, val kcalShort: Double, val kcalOther: Double, val cravShort: Double, val cravOther: Double)

object Insights {
    /** Groups that make a real meal (no sweets, fast food or sugary drinks). */
    private val MEAL_GROUPS = setOf("Protein", "Dal & curries", "Breads & breakfast", "Rice", "Fruit", "Drinks")
    private val NOT_MEAL = listOf("soft drink", "juice", "beer", "wine", "whisky", "cola", "coffee with sugar", "samosa", "puri", "bonda", "pakora", "vada", "paratha with butter")

    private val BASE_WORDS = listOf("roti", "phulka", "chapati", "rice", "dosa", "idli", "pesarattu", "jowar", "bajra", "ragi", "bread")
    private val NOT_BASE = listOf("fried", "biryani", "curd rice", "pulao", "lemon", "sweet")

    private fun mealFriendly(f: FoodItem): Boolean =
        f.group in MEAL_GROUPS && NOT_MEAL.none { f.name.lowercase().contains(it) } && f.kcal > 0

    /**
     * Meals that fit [kcalLeft] and close as much of [proteinLeft] as possible. Prefers one protein-rich food plus sides,
     * uses most of the budget without going over, and returns distinct main foods.
     * When there's (almost) nothing left, it returns light, protein-first single items instead.
     */
    fun mealIdeas(foods: List<FoodItem>, kcalLeft: Double, proteinLeft: Double, count: Int = 3): List<MealIdea> {
        val pool = foods.filter(::mealFriendly)
        if (kcalLeft < 150) {
            return pool.filter { it.kcal <= 150 }
                .sortedByDescending { it.protein / it.kcal.coerceAtLeast(20.0) }
                .take(count).map { MealIdea(listOf(it), it.kcal, it.protein) }
        }
        // A real plate: one protein main, optionally a base (roti/rice) and a side (dal, sabzi, curd, salad, fruit).
        val mains = pool.filter { (it.group == "Protein" || it.group == "Dal & curries") && it.protein >= 10 && it.protein / it.kcal >= 0.05 }
        val bases = pool.filter { f ->
            (f.group == "Breads & breakfast" || f.group == "Rice") && f.kcal <= 250 &&
                BASE_WORDS.any { f.name.lowercase().contains(it) } && NOT_BASE.none { f.name.lowercase().contains(it) }
        }
        val sides = pool.filter { (it.group == "Dal & curries" || it.group == "Fruit") && it.kcal <= 200 }
        val wantP = proteinLeft.coerceAtLeast(15.0)
        val ideas = ArrayList<Pair<Double, MealIdea>>()
        fun consider(items: List<FoodItem>) {
            val k = items.sumOf { it.kcal }
            if (k > kcalLeft) return
            val p = items.sumOf { it.protein }
            val hasBase = items.any { it in bases }
            // Protein first (capped at what's needed), then use the budget well; a roti/rice base makes it a meal.
            val score = min(p, wantP) * 6 - abs(kcalLeft * 0.85 - k) * 0.08 + (if (hasBase && kcalLeft >= 400) 25 else 0)
            ideas += score to MealIdea(items, k, p)
        }
        for (m in mains) {
            consider(listOf(m))
            for (s in sides) if (s != m) consider(listOf(m, s))
            for (b in bases) {
                consider(listOf(m, b))
                for (s in sides) if (s != m) consider(listOf(m, b, s))
            }
        }
        val out = ArrayList<MealIdea>()
        for ((_, idea) in ideas.sortedByDescending { it.first }) {
            // Variety: no repeated main, and no repeated base (roti, rice…).
            if (out.any { o -> o.items.first() == idea.items.first() || o.items.drop(1).any { it in idea.items.drop(1) && it.group != "Dal & curries" && it.group != "Fruit" } }) continue
            out += idea
            if (out.size == count) break
        }
        return out
    }

    /**
     * What the scale is doing: an overnight jump that's water, a plateau that isn't failure, or steady progress.
     * [losing] is the goal direction. Uses the smoothed trend so single weigh-ins don't cause alarm.
     */
    fun weightNotes(trend: List<TrendPoint>, losing: Boolean, today: LocalDate): List<WeightNote> {
        val raws = trend.filter { it.raw != null }
        if (raws.size < 3) return emptyList()
        val notes = ArrayList<WeightNote>()
        val last = raws.last()
        val prev = raws[raws.size - 2]
        val jump = last.raw!! - prev.raw!!
        val gap = last.date.toEpochDay() - prev.date.toEpochDay()
        if (last.date >= today.minusDays(1) && gap <= 3 && abs(jump) >= 0.7) {
            notes += if (jump > 0) WeightNote(
                "Up %.1f kg? That's water, not fat".format(jump),
                "Gaining %.1f kg of fat would take about %,d extra kcal. Salt, carbs, a big late meal, poor sleep or soreness hold water for 1–3 days. Your trend is %.1f kg; follow that.".format(jump, (jump * 7700).roundToInt(), last.trend),
            ) else WeightNote(
                "Down %.1f kg overnight".format(-jump),
                "Nice, but most of a quick drop is water too. The trend (%.1f kg) is the honest number; it'll catch up if you keep going.".format(last.trend),
            )
        }
        // Plateau: trend barely moved in 14 days while the goal is to lose.
        val twoWeeksAgo = trend.lastOrNull { !it.date.isAfter(today.minusDays(14)) }
        if (losing && twoWeeksAgo != null && raws.count { !it.date.isBefore(today.minusDays(14)) } >= 4) {
            val change = last.trend - twoWeeksAgo.trend
            if (change > -0.25) notes += WeightNote(
                "Two weeks without much change",
                "Plateaus are normal: water can hide fat loss for 1–3 weeks, then drop at once. Check three things: are you logging oil, drinks and snacks? Are steps steady? Is sleep 7 h? If all yes, keep going. If it lasts 3+ weeks, lower the target by 100–150 kcal.",
            ) else if (change <= -0.5) notes += WeightNote(
                "Trend down %.1f kg in 2 weeks".format(-change),
                "That's real fat loss showing through the daily noise. Keep doing what you're doing.",
            )
        }
        return notes
    }

    /**
     * Compares days after short nights (< [shortMin] minutes asleep) with the rest: average calories eaten and cravings.
     * Needs at least 3 nights in each group with food logged; null otherwise.
     */
    fun sleepLink(asleepMinByWakeDay: Map<LocalDate, Int>, kcalByDay: Map<LocalDate, Double>, cravingsByDay: Map<LocalDate, Int>, shortMin: Int = 360): SleepLink? {
        val days = asleepMinByWakeDay.keys.filter { (kcalByDay[it] ?: 0.0) > 300 }
        val short = days.filter { asleepMinByWakeDay.getValue(it) < shortMin }
        val other = days - short.toSet()
        if (short.size < 3 || other.size < 3) return null
        return SleepLink(
            short.size, other.size,
            short.map { kcalByDay.getValue(it) }.average(), other.map { kcalByDay.getValue(it) }.average(),
            short.map { (cravingsByDay[it] ?: 0).toDouble() }.average(), other.map { (cravingsByDay[it] ?: 0).toDouble() }.average(),
        )
    }

    /** Daily water goal in 250 ml glasses: about 33 ml per kg, between 8 and 16 glasses. */
    fun waterGlasses(weightKg: Double, own: Int = 0): Int = if (own in 1..40) own else (weightKg * 0.033 / 0.25).roundToInt().coerceIn(8, 16)
}
