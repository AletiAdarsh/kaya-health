package com.kaya.health.band

import java.time.LocalDateTime

/** Commands the band understands. Byte layouts come from OpenNoiseRep's captures of NoiseFit. */
object Commands {
    const val TYPE_DEVICE_INFO = 32
    const val TYPE_INDEX = 112
    const val TYPE_SERIES = 113
    /** 115 is CONFIRM_FITNESS_TYPE_ID ("received, you may clear it"). Kaya must NOT send it. */
    const val TYPE_CONFIRM_DO_NOT_SEND = 115
    const val TYPE_BATTERY = 33
    const val TYPE_GET_MEASURE_FREQUENCY = 768
    const val TYPE_HEALTH = 8

    const val METRIC_ACTIVITY = 0
    const val METRIC_HEART_RATE = 2
    const val METRIC_SPO2 = 4
    const val METRIC_HRV = 21
    const val METRIC_STRESS = 22
    /** Breathing rate per 5 min, recorded only while the band (using its motion sensor) thinks you're asleep. */
    const val METRIC_SLEEP_BREATHING = 19
    /** Skin temperature ×100 °C, per 5 min (big-endian u16 pairs). */
    const val METRIC_SKIN_TEMP = 8

    const val REALTIME_START = 45
    const val REALTIME_STOP = 46
    const val REALTIME_EVENT = 47

    /** type 32 → firmware, serial, MAC, model. Bytes: 08 20 */
    fun deviceInfo(): ByteArray = Wire.encode(1 to TYPE_DEVICE_INFO)

    /** type 161 FIND_WEAR ("find my band"): asks the band to buzz. Changes no settings. */
    const val TYPE_FIND_WEAR = 161
    /** micro_function(12) { find_phone_mode(1): FIND_START = 0, FIND_STOP = 1 }. The REP accepts it but doesn't buzz. */
    fun findBand(start: Boolean = true): ByteArray = Wire.encode(1 to TYPE_FIND_WEAR, 12 to Wire.encode(1 to if (start) 0 else 1))

    /**
     * type 355 SET_RING_SPORT_STATUS: start or end a workout on the band, exactly as NoiseFit does.
     * sporting(8) → ring_Sport_Param_Data(10) { sport_status(4): 1 start / 4 end, startsport_type(6): 2 = walk,
     * startsport_curtime(7): unix start time }. The band buzzes on start and on end, and sends heart rate
     * every 2 s (type 363) while the workout runs.
     */
    const val TYPE_WORKOUT = 355

    /** Read-only: vibration intensity (232) and notification settings (495). */
    fun getVibrationIntensity(): ByteArray = Wire.encode(1 to 232)
    fun getNotificationSettings(): ByteArray = Wire.encode(1 to 495)

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    /** type 179 SEND_APP_NOTIFICATION: notification(13) → app_notification(2) {1 app, 2 package, 3 title, 4 text, 5 ticker}. */
    fun appNotification(title: String, text: String): ByteArray = Wire.encode(
        1 to 179,
        13 to Wire.encode(2 to Wire.encode(1 to utf8("Kaya"), 2 to utf8("com.kaya.health"), 3 to utf8(title), 4 to utf8(text), 5 to utf8(title))),
    )

    /** type 178 SEND_SYSTEM_NOTIFICATION: notification(13) → system_notification(1) {1 type (2 = MESSAGE), 2 number, 3 contact, 4 text}. */
    fun messageNotification(from: String, text: String): ByteArray = Wire.encode(
        1 to 178,
        13 to Wire.encode(1 to Wire.encode(1 to 2, 2 to utf8(""), 3 to utf8(from), 4 to utf8(text))),
    )
    fun workout(start: Boolean, startUnix: Long, sportType: Int = 2): ByteArray =
        Wire.encode(1 to TYPE_WORKOUT, 8 to Wire.encode(10 to Wire.encode(4 to if (start) 1 else 4, 6 to sportType, 7 to startUnix)))

    /** type 33 GET_DEVICE_BATTERY_STATUS → device(4) → device_battery_status(2) {1 capacity %, 2 charge status}. */
    fun battery(): ByteArray = Wire.encode(1 to TYPE_BATTERY)

    /** type 768 GET_DATA_MEASURE_FREQUENCY (read-only) → setting_menu(15) → config_measure_ferquency(56). */
    fun measureFrequency(): ByteArray = Wire.encode(1 to TYPE_GET_MEASURE_FREQUENCY)

    /** type 112 → which days/metrics the band holds. Bytes: 08 70 4a 03 e8 01 03 */
    fun dataIndex(): ByteArray = Wire.encode(1 to TYPE_INDEX, 9 to Wire.encode(29 to 3))

    /** type 113/115 → one metric's samples since [since] (band's local clock). */
    fun series(type: Int, since: LocalDateTime, metric: Int): ByteArray {
        val date = Wire.encode(
            1 to since.year, 2 to since.monthValue, 3 to since.dayOfMonth,
            4 to since.hour, 5 to since.minute, 6 to since.second,
        )
        val entry = Wire.encode(1 to date, 2 to metric)
        return Wire.encode(1 to type, 9 to Wire.encode(1 to entry))
    }

    /** Xiaomi-style live stream start/stop (type 8, subtype 45/46). The REP REJECTS these (status 1). Kept for tests only. */
    fun realtimeStart(): ByteArray = Wire.encode(1 to TYPE_HEALTH, 2 to REALTIME_START)
    fun realtimeStop(): ByteArray = Wire.encode(1 to TYPE_HEALTH, 2 to REALTIME_STOP)

    // ---- Found by recording NoiseFit's Bluetooth traffic on the user's phone (btsnoop log, 1 Nov 2026) ----

    const val TYPE_APP_STATE = 164
    const val TYPE_LIVE_SUMMARY = 165
    const val TYPE_WORKOUT_LIVE = 363

    /**
     * type 164 {12: {3: 0}}: "the app is open". The band then pushes a type-165 live summary
     * (current heart rate, today's steps/calories/distance) about every 10 seconds.
     * Bytes: 08 a4 01 62 02 18 00
     */
    fun appForeground(): ByteArray = Wire.encode(1 to TYPE_APP_STATE, 12 to Wire.encode(3 to 0))

    /** type 164 {12: {3: 1}}: "the app is in the background". Stops the pushes. */
    fun appBackground(): ByteArray = Wire.encode(1 to TYPE_APP_STATE, 12 to Wire.encode(3 to 1))
}

/** The band's pushed "today so far" summary (type 165). */
data class LiveSummary(
    val heartRate: Int, val steps: Int, val calories: Int, val distanceM: Int,
    /** SEBasicData fields from NoiseFit's own protocol definition. 0 = not reported. */
    val spo2: Int = 0, val stress: Int = 0, val batteryPct: Int? = null, val charging: Boolean = false, val sleepMinutes: Int = 0,
)

data class Battery(val percent: Int, val charging: Boolean, val full: Boolean)

/** How often the band measures each signal, in seconds (as configured on the band). */
data class MeasureFrequency(val awake: Map<String, Int>, val asleep: Map<String, Int>)

data class DeviceInfo(val firmware: String, val serial: String, val mac: String, val model: String)

data class Series(
    val start: LocalDateTime,
    val metric: Int,
    /** Seconds for heart rate (30); other metrics may use minutes. */
    val interval: Int?,
    /** One unsigned byte per slot; 0 = no reading. */
    val samples: IntArray,
    /** Metric 0 only: hourly arrays (steps, distance_m, calories, active_minutes). */
    val hourly: Map<String, IntArray> = emptyMap(),
    /** Metric 0 only: the band's own day totals. */
    val totals: Map<String, Long> = emptyMap(),
)

data class RealtimeSample(val heartRate: Int, val steps: Int, val calories: Int)

object Decode {
    fun type(payload: ByteArray): Long? = Wire.int(payload, 1)
    fun subtype(payload: ByteArray): Long? = Wire.int(payload, 2)
    /** Replies with field 100 are status codes (e.g. "nothing to send"). */
    fun status(payload: ByteArray): Long? = Wire.int(payload, 100)

    fun deviceInfo(payload: ByteArray): DeviceInfo? {
        val di = Wire.sub(payload, 4, 1) ?: return null
        fun s(f: Int) = Wire.bytes(di, f)?.toString(Charsets.US_ASCII) ?: ""
        return DeviceInfo(s(1), s(2), s(3), s(4))
    }

    fun date(buf: ByteArray?): LocalDateTime? = try {
        LocalDateTime.of(
            Wire.int(buf, 1)!!.toInt(), Wire.int(buf, 2)!!.toInt(), Wire.int(buf, 3)!!.toInt(),
            (Wire.int(buf, 4) ?: 0).toInt(), (Wire.int(buf, 5) ?: 0).toInt(), (Wire.int(buf, 6) ?: 0).toInt(),
        )
    } catch (_: Exception) { null }

    /** Every series body in a type-113/115 reply (one per day/metric). */
    fun allSeries(payload: ByteArray): List<Series> {
        val container = Wire.bytes(payload, 9) ?: return emptyList()
        val fields = Wire.parseOrNull(container) ?: return emptyList()
        return fields.filter { it.wireType == 2 }.mapNotNull { f -> seriesBody(f.bytes!!) }
    }

    fun series(payload: ByteArray): Series? = allSeries(payload).firstOrNull()

    private fun seriesBody(body: ByteArray): Series? {
        val entry = Wire.bytes(body, 1) ?: return null
        val start = date(Wire.bytes(entry, 1)) ?: return null
        val metric = Wire.int(entry, 2)?.toInt() ?: return null
        val f3 = Wire.bytes(body, 3)
        // Some metrics (14, 18, 19, ...) send one byte per slot in field 2 instead (no interval field).
        // A whole day of them gives the cadence: 288 bytes = 5-minute slots.
        val f2blob = if (f3 == null) Wire.parseOrNull(body)?.firstOrNull { it.number == 2 && it.wireType == 2 }?.bytes else null
        val raw = f3 ?: f2blob
        val samples = raw?.let { b -> IntArray(b.size) { b[it].toInt() and 0xFF } } ?: IntArray(0)
        val interval = if (f3 != null) Wire.int(body, 2)?.toInt()
        else f2blob?.size?.takeIf { it > 0 && 1440 % it == 0 }?.let { 1440 / it }
        if (metric != Commands.METRIC_ACTIVITY) return Series(start, metric, interval, samples)

        // Daily activity: big-endian u16 per hour. f3 steps, f5 distance (m), f7 calories, f15 active minutes.
        val channels = linkedMapOf("steps" to 3, "distance_m" to 5, "calories" to 7, "active_minutes" to 15)
        val hourly = channels.mapNotNull { (name, fno) -> hourlyU16(Wire.bytes(body, fno))?.let { name to it } }.toMap()
        val totals = buildMap {
            Wire.int(body, 9)?.let { put("steps", it) }
            Wire.int(body, 10)?.let { put("calories", it) }
            Wire.int(body, 13)?.let { put("active_minutes", it) }
            hourly["distance_m"]?.let { put("distance_m", it.sum().toLong()) }
            for ((name, arr) in hourly) if (name !in this) put(name, arr.sum().toLong())
        }
        return Series(start, metric, interval, samples, hourly, totals)
    }

    private fun hourlyU16(b: ByteArray?): IntArray? {
        if (b == null || b.size < 2) return null
        val n = minOf(b.size / 2, 24)
        return IntArray(n) { i -> ((b[2 * i].toInt() and 0xFF) shl 8) or (b[2 * i + 1].toInt() and 0xFF) }
    }

    /**
     * type 165 → {12: {4: {1 steps, 2 calories, 3 distance m, 4 heart rate, 16.. hourly arrays}}}.
     * Heart rate verified against the band's stored 30-s record (88 = 88 at 00:13:59).
     */
    fun liveSummary(payload: ByteArray): LiveSummary? {
        if (type(payload) != Commands.TYPE_LIVE_SUMMARY.toLong()) return null
        val s = Wire.sub(payload, 12, 4) ?: return null
        val bat = Wire.bytes(s, 7)
        return LiveSummary(
            heartRate = (Wire.int(s, 4) ?: 0).toInt(),
            steps = (Wire.int(s, 1) ?: 0).toInt(),
            calories = (Wire.int(s, 2) ?: 0).toInt(),
            distanceM = (Wire.int(s, 3) ?: 0).toInt(),
            spo2 = (Wire.int(s, 5) ?: 0).toInt(),
            stress = (Wire.int(s, 13) ?: 0).toInt(),
            batteryPct = bat?.let { Wire.int(it, 1)?.toInt() },
            charging = bat?.let { Wire.int(it, 2) == 1L } ?: false,
            sleepMinutes = (Wire.int(s, 9) ?: 0).toInt(),
        )
    }

    fun battery(payload: ByteArray): Battery? {
        val b = Wire.sub(payload, 4, 2) ?: return null
        val pct = Wire.int(b, 1)?.toInt() ?: return null
        val st = Wire.int(b, 2)
        return Battery(pct, charging = st == 1L, full = st == 3L)
    }

    fun measureFrequency(payload: ByteArray): MeasureFrequency? {
        val c = Wire.sub(payload, 15, 56) ?: return null
        fun read(b: ByteArray?): Map<String, Int> {
            if (b == null) return emptyMap()
            val names = listOf("heart rate", "breathing", "temperature", "beat-to-beat (RRI)", "HRV", "SpO2")
            return names.mapIndexedNotNull { i, n -> Wire.int(b, i + 1)?.let { n to it.toInt() } }.toMap()
        }
        return MeasureFrequency(read(Wire.bytes(c, 1)), read(Wire.bytes(c, 2)))
    }

    /** type 363 → {8: {5: {1 heart rate, …, 5 unix time}}}: sent every 2 s during a workout. */
    fun workoutHeartRate(payload: ByteArray): Int? {
        if (type(payload) != Commands.TYPE_WORKOUT_LIVE.toLong()) return null
        return Wire.int(Wire.sub(payload, 8, 5), 1)?.toInt()?.takeIf { it > 0 }
    }

    /** A live-stream event (type 8, subtype 47) → sample, else null. */
    fun realtime(payload: ByteArray): RealtimeSample? {
        if (type(payload) != Commands.TYPE_HEALTH.toLong() || subtype(payload) != Commands.REALTIME_EVENT.toLong()) return null
        val stats = Wire.sub(payload, 10, 39) ?: return null
        return RealtimeSample(
            heartRate = (Wire.int(stats, 4) ?: 0).toInt(),
            steps = (Wire.int(stats, 1) ?: 0).toInt(),
            calories = (Wire.int(stats, 2) ?: 0).toInt(),
        )
    }
}
