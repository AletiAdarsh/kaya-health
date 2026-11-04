package com.kaya.health.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class AwakeGapsTest {
    private val day = LocalDateTime.of(2026, 11, 3, 0, 0)

    /** The band's breathing log for a real night with a long awake stretch (# = logged, . = gap), 5-minute slots from midnight. */
    private val night = listOf(
        "####...####.", "........####", "#####....###", "############",
        "##........#.", "....#.......", "..........##", "####",
    ).joinToString("")

    private fun times() = night.mapIndexedNotNull { i, c -> if (c == '#') day.plusMinutes(5L * i) else null }

    @Test fun lyingAwakeFourToSixThirtyIsOneAwakeStretch() {
        val gaps = SleepWindow.awakeGaps(times(), day to day.plusHours(7).plusMinutes(20))
        // 4:10 to 6:50, with the band's 5-minute dozes at 4:50 and 5:20 bridged.
        assertTrue(gaps.any { it.first == day.plusHours(4).plusMinutes(10) && it.second == day.plusHours(6).plusMinutes(50) })
        // A 15-minute gap (0:20) is not an awake stretch; 20 minutes (2:25-2:45, also in the band's own log) is.
        assertTrue(gaps.none { it.first == day.plusMinutes(20) })
        assertTrue(gaps.any { it.first == day.plusHours(2).plusMinutes(25) && it.second == day.plusHours(2).plusMinutes(45) })
    }

    @Test fun totalAwakeIsCloseToWhatTheUserReported() {
        val gaps = SleepWindow.awakeGaps(times(), day to day.plusHours(7).plusMinutes(20))
        val awake = gaps.sumOf { Duration.between(it.first, it.second).toMinutes() }
        // 0:55–1:40 (45) + 2:25–2:45 (20) + 4:10–6:50 (160): the same stretches the band's firmware log marks awake.
        assertEquals(225L, awake)
    }
}
