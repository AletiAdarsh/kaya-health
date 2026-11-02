package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class VitalInsightsTest {
    private val t0 = LocalDateTime.of(2026, 11, 1, 0, 0)

    @Test fun spo2NoisyIsUnreliable() {
        assertEquals(Level.UNRELIABLE, VitalInsights.status(MetricId.SPO2, listOf(98.0, 72.0, 95.0, 100.0), null).level)
        assertEquals("Normal", VitalInsights.status(MetricId.SPO2, listOf(97.0, 96.0, 98.0), null).label)
    }

    @Test fun restingAgainstYourUsual() {
        assertEquals("Lower than usual", VitalInsights.status(MetricId.RESTING, listOf(61.0, 64.0), 64.0).label)
        assertEquals("Healthy", VitalInsights.status(MetricId.RESTING, listOf(61.0), null).label)
    }

    @Test fun hrvNeedsHistory() {
        assertEquals(Level.NONE, VitalInsights.status(MetricId.HRV, listOf(69.0), null).level)
        assertEquals("Below your usual", VitalInsights.status(MetricId.HRV, listOf(50.0), 69.0).label)
    }

    @Test fun stressInsightFindsPeakHour() {
        // 12 readings, 5 min apart from 1 pm; 6 of them high
        val s = (0 until 12).map { t0.plusHours(13).plusMinutes(it * 5L) to if (it < 6) 70.0 else 40.0 }
        val lines = VitalInsights.forYou(mapOf(MetricId.STRESS to s), emptyMap(), losingWeight = false)
        assertTrue(lines.first(), lines.first().contains("30 min") && lines.first().contains("1 pm"))
    }
}
