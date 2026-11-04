package com.kaya.health.engine

import com.kaya.health.ui.RealityMath
import org.junit.Assert.assertEquals
import org.junit.Test

class RealityMathTest {
    @Test fun surplusInDays() {
        // 900 kcal at a 1,250 kcal/day planned deficit ≈ 0.72 days.
        assertEquals(0.72, RealityMath.daysPushed(900.0, 1250.0), 0.01)
    }

    @Test fun threeMonthsAtThisPace() {
        // Eating 2,100 a day against a 2,500 burn: -400/day → about 4.7 kg in 13 weeks.
        assertEquals(123.0 - 4.73, RealityMath.weightAfter(123.0, 2100.0, 2500.0, 13), 0.05)
        // Eating at maintenance: no change.
        assertEquals(123.0, RealityMath.weightAfter(123.0, 2500.0, 2500.0, 13), 0.001)
    }
}
