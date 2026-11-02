package com.kaya.health.band

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class ProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun commandsMatchNoiseFitCaptureBytes() {
        assertArrayEquals(bytes(0x08, 0x20), Commands.deviceInfo())
        assertArrayEquals(bytes(0x08, 0x70, 0x4a, 0x03, 0xe8, 0x01, 0x03), Commands.dataIndex())
        assertArrayEquals(bytes(0x08, 0x08, 0x10, 0x2D), Commands.realtimeStart())
        assertArrayEquals(bytes(0x08, 0x08, 0x10, 0x2E), Commands.realtimeStop())
    }

    @Test fun seriesRoundTrip() {
        val date = Wire.encode(1 to 2026, 2 to 10, 3 to 28, 4 to 8, 5 to 30, 6 to 0)
        val entry = Wire.encode(1 to date, 2 to 2)
        val body = Wire.encode(1 to entry, 2 to 30, 3 to bytes(0, 61, 0, 72))
        val payload = Wire.encode(1 to 113, 9 to Wire.encode(1 to body))
        val s = Decode.series(payload)!!
        assertEquals(LocalDateTime.of(2026, 10, 28, 8, 30, 0), s.start)
        assertEquals(2, s.metric)
        assertEquals(30, s.interval)
        assertArrayEquals(intArrayOf(0, 61, 0, 72), s.samples)
    }

    @Test fun dailyActivityHourlyBigEndian() {
        val date = Wire.encode(1 to 2026, 2 to 10, 3 to 31)
        val entry = Wire.encode(1 to date, 2 to 0)
        val steps = bytes(0x01, 0x00, 0x00, 0x10) // 256, 16
        val body = Wire.encode(1 to entry, 3 to steps, 9 to 272)
        val s = Decode.series(Wire.encode(1 to 113, 9 to Wire.encode(6 to body)))!!
        assertArrayEquals(intArrayOf(256, 16), s.hourly["steps"])
        assertEquals(272L, s.totals["steps"])
    }

    @Test fun realtimeEventParsing() {
        val stats = Wire.encode(1 to 1234, 2 to 56, 3 to 7, 4 to 72)
        val event = Wire.encode(1 to 8, 2 to 47, 10 to Wire.encode(39 to stats))
        assertEquals(72, Decode.realtime(event)!!.heartRate)
        assertNull(Decode.realtime(Commands.realtimeStart()))
        assertNull(Decode.realtime(bytes(0xff, 0xff)))
    }

    @Test fun appStateCommandsMatchNoiseFitCapture() {
        // From the btsnoop capture: TX "1=164 12:{3=0}" was 7 bytes
        assertArrayEquals(bytes(0x08, 0xa4, 0x01, 0x62, 0x02, 0x18, 0x00), Commands.appForeground())
        assertArrayEquals(bytes(0x08, 0xa4, 0x01, 0x62, 0x02, 0x18, 0x01), Commands.appBackground())
    }

    @Test fun liveSummaryFromCapture() {
        // Real capture, 00:13:59: 1=165 12:{4:{1=19 2=32 3=14 4=88 …}}
        val inner = Wire.encode(1 to 19, 2 to 32, 3 to 14, 4 to 88, 5 to 0)
        val payload = Wire.encode(1 to 165, 12 to Wire.encode(4 to inner))
        assertEquals(LiveSummary(heartRate = 88, steps = 19, calories = 32, distanceM = 14), Decode.liveSummary(payload))
        assertNull(Decode.liveSummary(Commands.appForeground()))
    }

    @Test fun workoutHeartRateFromCapture() {
        // Real capture: 1=363 8:{5:{1=93 2=12 3=76 4=63 5=1793472401 …}}
        val inner = Wire.encode(1 to 93, 2 to 12, 3 to 76, 4 to 63, 5 to 1793472401L)
        assertEquals(93, Decode.workoutHeartRate(Wire.encode(1 to 363, 8 to Wire.encode(5 to inner))))
    }

    @Test fun deviceInfoParsing() {
        val di = Wire.encode(1 to "1.2.3".toByteArray(), 2 to "SN1".toByteArray(), 3 to "AA:BB".toByteArray(), 4 to "REP".toByteArray())
        val payload = Wire.encode(1 to 32, 4 to Wire.encode(1 to di))
        assertEquals(DeviceInfo("1.2.3", "SN1", "AA:BB", "REP"), Decode.deviceInfo(payload))
    }
}
