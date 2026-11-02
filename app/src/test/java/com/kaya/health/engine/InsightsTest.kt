package com.kaya.health.engine

import com.kaya.health.data.FoodDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightsTest {
    private val d = LocalDate.of(2026, 11, 2)

    @Test fun mealIdeasFitTheBudgetAndBringProtein() {
        val ideas = Insights.mealIdeas(FoodDb.ALL, kcalLeft = 500.0, proteinLeft = 50.0)
        assertEquals(3, ideas.size)
        ideas.forEach { assertTrue(it.kcal <= 500.0); assertTrue("protein ${it.protein}", it.protein >= 25) }
        assertEquals(3, ideas.map { it.items.first() }.toSet().size)
        assertTrue(ideas.none { i -> i.items.any { it.group == "Snacks & sweets" || it.group == "Fast food" } })
    }

    @Test fun overBudgetGivesLightProteinFirstItems() {
        val ideas = Insights.mealIdeas(FoodDb.ALL, kcalLeft = -240.0, proteinLeft = 60.0)
        assertTrue(ideas.isNotEmpty())
        ideas.forEach { assertTrue(it.kcal <= 150) }
    }

    @Test fun overnightJumpIsWater() {
        val t = RealBurn.trend(listOf(DayWeight(d.minusDays(4), 90.0), DayWeight(d.minusDays(2), 89.8), DayWeight(d.minusDays(1), 89.6), DayWeight(d, 91.0)))
        val notes = Insights.weightNotes(t, losing = true, today = d)
        assertTrue(notes.first().title.contains("water"))
    }

    @Test fun plateauAfterTwoFlatWeeks() {
        val w = (0..16 step 2).map { DayWeight(d.minusDays(it.toLong()), 120.0 + (it % 4) * 0.1) }
        val notes = Insights.weightNotes(RealBurn.trend(w), losing = true, today = d)
        assertTrue(notes.any { it.title.startsWith("Two weeks") })
    }

    @Test fun sleepLinkNeedsEnoughNights() {
        val sleep = (1..8).associate { d.minusDays(it.toLong()) to if (it <= 4) 300 else 450 }
        val kcal = (1..8).associate { d.minusDays(it.toLong()) to if (it <= 4) 1800.0 else 1300.0 }
        val link = Insights.sleepLink(sleep, kcal, mapOf(d.minusDays(1) to 2))
        assertNotNull(link); assertEquals(1800.0, link!!.kcalShort, 1e-9); assertEquals(0.5, link.cravShort, 1e-9)
        assertNull(Insights.sleepLink(sleep.filterKeys { it >= d.minusDays(2) }, kcal, emptyMap()))
    }

    @Test fun waterGoal() {
        assertEquals(16, Insights.waterGlasses(130.0))
        assertEquals(9, Insights.waterGlasses(70.0))
    }
}
