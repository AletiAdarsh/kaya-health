package com.kaya.health.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodDbTest {
    @Test fun findsByNameAndOtherNames() {
        assertEquals("Idli", FoodDb.search("idli").first().name)
        assertTrue(FoodDb.search("pappu").any { it.name.startsWith("Dal") })      // Telugu name
        assertTrue(FoodDb.search("chapati").any { it.name.startsWith("Roti") })
        assertTrue(FoodDb.search("chicken biryani").first().name == "Chicken biryani")
        assertTrue(FoodDb.search("").isEmpty())
    }

    @Test fun namesAreUniqueAndValuesSane() {
        assertEquals(FoodDb.ALL.size, FoodDb.ALL.map { it.name }.toSet().size)
        FoodDb.ALL.forEach {
            assertTrue(it.name, it.kcal in 0.0..1000.0 && it.protein in 0.0..60.0)
            // protein can't supply more calories than the food has (4 kcal per gram)
            assertTrue(it.name, it.protein * 4 <= it.kcal + 5)
        }
    }

    @Test fun macrosAddUpToTheCalories() {
        // 4 kcal per gram of protein and carbs, 9 per gram of fat. Alcohol (7 kcal/g) isn't a macro, so beer is skipped.
        FoodDb.ALL.filter { it.name != "Beer" && it.kcal >= 20 }.forEach {
            // Fiber gives about 2 kcal per gram rather than 4.
            val fromMacros = 4 * it.protein + 4 * (it.carbs - it.fiber) + 2 * it.fiber + 9 * it.fat
            assertTrue("${it.name}: ${fromMacros.toInt()} vs ${it.kcal.toInt()}", fromMacros in it.kcal * 0.8..it.kcal * 1.2)
            assertTrue(it.name, it.fiber <= it.carbs + 0.01 || it.carbs == 0.0)
        }
    }

    @org.junit.Test fun loggedEntriesBecomeOnePortion() {
        val r = FoodDb.fromLogged("Rice, cooked (white) ×3", 615.0, 13.0, 135.0, 1.5, 1.5)
        org.junit.Assert.assertEquals("Rice, cooked (white)", r.name)
        org.junit.Assert.assertEquals(205.0, r.kcal, 1e-9)
        val half = FoodDb.fromLogged("Gulab jamun ×½", 75.0, 1.0, null, null, null)
        org.junit.Assert.assertEquals(150.0, half.kcal, 1e-9)
        org.junit.Assert.assertEquals(250.0, FoodDb.fromLogged("Chicken curry ×2½", 625.0, null, null, null, null).kcal, 1e-9)
        org.junit.Assert.assertEquals("Rice khichdi 300 g", FoodDb.fromLogged("Rice khichdi 300 g", 375.0, 12.0, null, null, null).name)
    }
}
