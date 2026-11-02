package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessAgeTest {
    @Test fun referenceCurveRoundTrips() {
        assertEquals(25, FitnessAge.ageFor(48.0, Sex.MALE))
        assertEquals(40, FitnessAge.ageFor((42.4 + 37.8) / 2, Sex.MALE))
        assertEquals(20, FitnessAge.ageFor(70.0, Sex.MALE))
        assertEquals(80, FitnessAge.ageFor(10.0, Sex.MALE))
        assertEquals(35, FitnessAge.ageFor(30.9, Sex.FEMALE))
    }

    @Test fun activityIndexFollowsHunt() {
        assertEquals(0.0, FitnessAge.activityIndex(0.0, 0.0, false), 1e-9)
        assertEquals(5 * 5 * 0.75, FitnessAge.activityIndex(5.0, 45.0, true), 1e-9)
        assertEquals(2.5 * 1 * 0.38, FitnessAge.activityIndex(3.0, 20.0, false), 1e-9)
    }

    @Test fun heavierAndLessActiveIsOlder() {
        // 30-year-old man, 175 cm: 75 kg active vs 115 kg inactive.
        val fit = FitnessAge.compute(30, Sex.MALE, 175.0, 75.0, 58.0, 5.0, 45.0, true, null, null)
        val unfit = FitnessAge.compute(30, Sex.MALE, 175.0, 115.0, 78.0, 0.5, 10.0, false, null, 90.0)
        assertTrue("fit ${fit.fitnessAge}", fit.fitnessAge <= 32)
        assertTrue("unfit ${unfit.fitnessAge}", unfit.fitnessAge >= 45)
        // Every what-if improves on today, and all three together is the best.
        unfit.whatIf.forEach { assertTrue(it.first, it.second <= unfit.fitnessAge) }
        assertEquals(unfit.whatIf.minOf { it.second }, unfit.whatIf.last().second)
    }

    @Test fun waistVersionIsUsedWhenKnown() {
        val r = FitnessAge.compute(30, Sex.MALE, 175.0, 90.0, 65.0, 2.0, 30.0, false, 100.0, null)
        assertTrue(r.usedWaist)
        assertEquals(100.27 - 0.296 * 30 - 0.369 * 100 - 0.155 * 65 + 0.226 * FitnessAge.activityIndex(2.0, 30.0, false), r.vo2max, 1e-9)
    }
}
