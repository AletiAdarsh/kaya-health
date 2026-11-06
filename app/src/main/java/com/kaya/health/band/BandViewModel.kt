package com.kaya.health.band

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Today's data read straight from the band. */
data class BandDay(
    val date: LocalDate,
    val heartRate: Series?,
    val activity: Series?,
    val syncedAt: Instant,
) {
    /** (time, bpm) for every slot that has a reading. */
    val hrReadings: List<Pair<LocalDateTime, Int>>
        get() = heartRate?.let { s ->
            val step = (s.interval ?: 30).toLong()
            s.samples.withIndex().filter { it.value > 0 }.map { s.start.plusSeconds(it.index * step) to it.value }
        } ?: emptyList()

    val steps: Long? get() = activity?.totals?.get("steps")
    val distanceM: Long? get() = activity?.totals?.get("distance_m")
    /**
     * Minutes spent walking, estimated from steps (about 100 steps a minute, at most 60 per hour).
     * The band's own "active" field counts far more than real movement (7+ hours on a 1,200-step day), so it isn't used.
     */
    val activeMinutes: Long? get() = hourlySteps?.sumOf { minOf(60, it / 100).toLong() } ?: steps?.let { it / 100 }
    val bandCalories: Long? get() = activity?.totals?.get("calories")
    val hourlySteps: IntArray? get() = activity?.hourly?.get("steps")
}

/** Recorded walks plus walks found in [day]'s heart rate and steps, minus ones you dismissed. */
fun walksFor(date: LocalDate, day: BandDay?, walkStore: WalkStore, restingHr: Double, age: Int): List<com.kaya.health.engine.Walk> {
    val recorded = walkStore.recordedOn(date)
    val detected = com.kaya.health.engine.WalkDetector.detect(day?.hrReadings ?: emptyList(), day?.hourlySteps, restingHr, age)
        .filter { d -> d.key !in walkStore.dismissed() && recorded.none { r -> r.start.isBefore(d.end) && d.start.isBefore(r.end) } }
    return (recorded + detected).sortedBy { it.start }
}

enum class LiveMode { STREAM, LATEST_READING }

data class LiveUi(
    val running: Boolean = false,
    val bpm: Int? = null,
    val at: LocalDateTime? = null,
    val mode: LiveMode? = null,
    val note: String? = null,
    /** Latest SpO2 / stress the band included in its live summary, if any. */
    val spo2: Int? = null,
    val stress: Int? = null,
    /** Recent live readings (newest last), for the moving graph. */
    val history: List<Int> = emptyList(),
)

data class SleepUi(
    val loading: Boolean = false,
    val result: com.kaya.health.engine.SleepResult? = null,
    /** Raw 30-s readings across the analysis window, for the overnight chart. */
    val readings: List<Pair<LocalDateTime, Int>> = emptyList(),
    val analysedAt: Instant? = null,
    val message: String? = null,
    /** Bedtime/wake came from the band's own (motion-based) sleep window rather than heart rate. */
    val fromBandWindow: Boolean = false,
    /** Breathing rate during sleep, breaths per minute (band metric 19). */
    val breathingAvg: Double? = null,
    val breathingRange: Pair<Int, Int>? = null,
    /** Daytime sleep today, from the band's own sleep detection (it records breathing only while you're asleep). */
    val naps: List<Pair<LocalDateTime, LocalDateTime>> = emptyList(),
    /** Yesterday's naps, so a nap isn't lost the moment the date changes. */
    val yesterdayNaps: List<Pair<LocalDateTime, LocalDateTime>> = emptyList(),
) {
    val napMinutes: Long get() = naps.sumOf { java.time.Duration.between(it.first, it.second).toMinutes() }
}

class BandViewModel(app: Application) : AndroidViewModel(app) {
    val client = BandClient(app, viewModelScope)
    private val store = BandStore(app)
    private val bandFiles = BandFiles(app)
    val state: StateFlow<BandState> = client.state
    val log: StateFlow<List<String>> = client.log

    private val _info = MutableStateFlow<DeviceInfo?>(null)
    val info: StateFlow<DeviceInfo?> = _info.asStateFlow()

    /** Days read from the band (today and yesterday), keyed by date. */
    // Start with what's saved on the phone (today and yesterday), so screens fill in before the band answers.
    private val _days = MutableStateFlow<Map<LocalDate, BandDay>>(
        listOf(LocalDate.now(), LocalDate.now().minusDays(1)).mapNotNull { d ->
            val hr = store.load(d, Commands.METRIC_HEART_RATE)
            val act = store.load(d, Commands.METRIC_ACTIVITY)
            if (hr == null && act == null) null else d to BandDay(d, hr, act, Instant.EPOCH)
        }.toMap()
    )
    val days: StateFlow<Map<LocalDate, BandDay>> = _days.asStateFlow()

    private val _selected = MutableStateFlow(LocalDate.now())
    val selected: StateFlow<LocalDate> = _selected.asStateFlow()
    fun select(date: LocalDate) { _selected.value = date }

    private val _battery = MutableStateFlow<Battery?>(null)
    val battery: StateFlow<Battery?> = _battery.asStateFlow()

    private val _frequency = MutableStateFlow<MeasureFrequency?>(null)
    val frequency: StateFlow<MeasureFrequency?> = _frequency.asStateFlow()

    private val _reconnecting = MutableStateFlow(false)
    /** True for the few seconds between a dropped link and the automatic reconnect. */
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _live = MutableStateFlow(LiveUi())
    val live: StateFlow<LiveUi> = _live.asStateFlow()

    private var liveJob: Job? = null

    // ---------------------------------------------------------------- walks

    /** A walk being recorded with Start walk, fed by the live stream (heart rate and today's step count every ~2 s). */
    data class WalkUi(
        val running: Boolean = false,
        val startedAt: LocalDateTime? = null,
        val startSteps: Int? = null,
        val steps: Int = 0,
        val hr: List<Int> = emptyList(),
    )
    private val _walk = MutableStateFlow(WalkUi())
    val walk: StateFlow<WalkUi> = _walk.asStateFlow()
    private val walkStore = WalkStore(app)
    private val _walksVersion = MutableStateFlow(0)
    /** Changes whenever a walk is recorded or dismissed, so screens re-read the list. */
    val walksVersion: StateFlow<Int> = _walksVersion.asStateFlow()

    @Volatile private var walkUnix: Long = 0

    fun startWalk() {
        _walk.value = WalkUi(running = true, startedAt = LocalDateTime.now())
        walkUnix = System.currentTimeMillis() / 1000
        startLive()
        // Put the band in its own walking-workout mode, like NoiseFit: it buzzes and streams heart rate every 2 s.
        viewModelScope.launch {
            if (client.connect()) {
                val ok = client.tell(Commands.workout(start = true, startUnix = walkUnix))
                Log.i(BandClient.TAG, "walk: band workout start sent=$ok")
            }
        }
    }

    /** Ends the walk, saves it (if it was more than a minute) and returns it. */
    fun stopWalk(): com.kaya.health.engine.Walk? {
        val w = _walk.value
        _walk.value = WalkUi()
        // End the band's workout (it buzzes again), then stop the live stream.
        val unix = walkUnix
        viewModelScope.launch {
            val ok = client.tell(Commands.workout(start = false, startUnix = unix))
            Log.i(BandClient.TAG, "walk: band workout end sent=$ok")
        }
        stopLive()
        val start = w.startedAt ?: return null
        val end = LocalDateTime.now()
        if (java.time.Duration.between(start, end).seconds < 60) return null
        val walk = com.kaya.health.engine.Walk(start, end, w.steps, w.hr.takeIf { it.isNotEmpty() }?.average()?.toInt(), w.hr.maxOrNull(), detected = false)
        walkStore.add(walk)
        _walksVersion.value++
        return walk
    }

    /** Recorded walks plus walks found in the band's heart rate and steps for [date], minus ones you dismissed. */
    fun walksOn(date: LocalDate, restingHr: Double, age: Int): List<com.kaya.health.engine.Walk> =
        walksFor(date, _days.value[date], walkStore, restingHr, age)

    fun dismissWalk(w: com.kaya.health.engine.Walk) { walkStore.dismiss(w.key); _walksVersion.value++ }
    private var syncJob: Job? = null

    fun connectAndSync(force: Boolean = false) {
        if (syncJob?.isActive == true) return
        if (force) { client.clearBackoff(); retries = 0 }
        syncJob = viewModelScope.launch {
            try {
                _busy.value = "Connecting to the band…"
                if (!client.connect()) return@launch
                sync(force = true)
            } finally {
                _busy.value = null
            }
        }
    }

    /** Reads the band. Always clears the "busy" status afterwards, even when called from the Sleep tab. */
    private val syncLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun sync(force: Boolean = false) = syncLock.withLock {
        if (!force && recentlySynced()) return@withLock // another screen just read the band
        _busy.value = "Reading the band…"
        _syncStatus.update { it.copy(running = true, step = "Reading today from your band") }
        val t0 = System.currentTimeMillis()
        try {
            syncInner()
            // A busy band (another app using it) answers nothing: don't count that as a sync, and try again shortly.
            if (client.backingOff) retryLaterIfBusy() else lastSync = Instant.now()
            // A fresh install can analyse sleep before the band's sleep log has arrived; once it's saved, redo it.
            if (_sleep.value.result != null && !_sleep.value.fromBandWindow && store.load(LocalDate.now(), Commands.METRIC_SLEEP_BREATHING) != null)
                viewModelScope.launch { computeSleep(online = false) }
            val ms = System.currentTimeMillis() - t0
            Log.i(BandClient.TAG, "sync took $ms ms")
            _syncStatus.update { it.copy(lastAt = Instant.now(), lastMs = ms, failed = client.backingOff) }
        } catch (e: Exception) {
            _syncStatus.update { it.copy(failed = true) }
            throw e
        } finally {
            _busy.value = null
            _syncStatus.update { it.copy(running = false, step = null) }
        }
    }

    data class SyncStatus(val running: Boolean = false, val step: String? = null, val lastAt: Instant? = null, val lastMs: Long? = null, val failed: Boolean = false)
    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /** For the background job when the app is open: read and save every metric for today and yesterday. */
    private suspend fun backgroundSyncAll(): Int {
        if (!client.connect()) return 0
        sync(force = true)
        var saved = 2
        for (day in listOf(LocalDate.now(), LocalDate.now().minusDays(1))) for (m in BandSync.METRICS) {
            if (m == Commands.METRIC_HEART_RATE || m == Commands.METRIC_ACTIVITY) continue
            if (lastIndex?.contains(day to m) == false) continue
            if (fetchSeries(day.atStartOfDay(), m, day) != null) saved++
        }
        return saved
    }

    private val health by lazy { com.kaya.health.data.HealthRepository(app) }

    /**
     * Last resort for a day the band has deleted and Kaya never saved: rebuild the 30-second heart-rate record
     * from what NoiseFit put in Health Connect (median per 30 s slot). Saved, so it's only done once.
     */
    private suspend fun heartRateFromHealthConnect(date: LocalDate): Series? {
        val samples = runCatching { health.heartRateSamples(date) }.getOrDefault(emptyList())
        if (samples.size < 20) return null
        val slots = IntArray(2880)
        samples.groupBy { (it.first.toLocalTime().toSecondOfDay() / 30).coerceIn(0, 2879) }
            .forEach { (i, l) -> slots[i] = l.map { it.second }.sorted()[l.size / 2] }
        val s = Series(date.atStartOfDay(), Commands.METRIC_HEART_RATE, 30, slots)
        store.save(date, Commands.METRIC_HEART_RATE, s)
        Log.i(BandClient.TAG, "heart rate for $date rebuilt from Health Connect: ${samples.size} samples")
        return s
    }

    @Volatile private var lastSync: Instant? = null

    /** Skip re-reading if the band was read in the last 90 seconds. */
    private fun recentlySynced() = lastSync?.let { java.time.Duration.between(it, Instant.now()).seconds < 90 } ?: false

    private suspend fun syncInner() {
        client.ask(Commands.deviceInfo(), Commands.TYPE_DEVICE_INFO)?.let { reply ->
            Decode.deviceInfo(reply)?.let {
                _info.value = it
                noteFirmware(it.firmware)
                Log.i(BandClient.TAG, "device: $it")
            }
        }
        client.ask(Commands.battery(), Commands.TYPE_BATTERY)?.let { r -> Decode.battery(r)?.let { _battery.value = it; Log.i(BandClient.TAG, "battery: $it") } }
        client.ask(Commands.measureFrequency(), Commands.TYPE_GET_MEASURE_FREQUENCY)?.let { r ->
            Log.i(BandClient.TAG, "measure frequency raw: ${r.hex()}")
            Decode.measureFrequency(r)?.let { _frequency.value = it; Log.i(BandClient.TAG, "measure frequency: $it") }
        }
        // Which (day, metric) pairs the band still holds. Asking for anything else just gets refused and retried.
        val available: Set<Pair<LocalDate, Int>>? = client.ask(Commands.dataIndex(), Commands.TYPE_INDEX)?.let { reply ->
            val container = Wire.sub(reply, 9, 2)
            Wire.allBytes(container, 1).mapNotNull { e ->
                val d = Decode.date(Wire.bytes(e, 1)) ?: return@mapNotNull null
                val m = Wire.int(e, 2)?.toInt() ?: return@mapNotNull null
                d.toLocalDate() to m
            }.toSet().also { Log.i(BandClient.TAG, "index: ${it.size} records: ${it.joinToString(" ") { (d, m) -> "$d:m$m" }}") }
        }
        fun onBand(date: LocalDate, metric: Int) = available?.contains(date to metric) ?: true
        val today = LocalDate.now()
        val result = LinkedHashMap(_days.value)
        for (date in listOf(today, today.minusDays(1))) {
            // From the band if it still has the day, otherwise Kaya's saved copy.
            val hr = (if (onBand(date, Commands.METRIC_HEART_RATE)) fetchSeries(date.atStartOfDay(), Commands.METRIC_HEART_RATE, date) else null)
                ?: store.load(date, Commands.METRIC_HEART_RATE) ?: heartRateFromHealthConnect(date)
            val act = (if (onBand(date, Commands.METRIC_ACTIVITY)) fetchSeries(date.atStartOfDay(), Commands.METRIC_ACTIVITY, date) else null)
                ?: store.load(date, Commands.METRIC_ACTIVITY)
            val day = BandDay(date, hr, act, Instant.now())
            // The band deletes a day once an app (e.g. NoiseFit) has synced it, so past days may be gone.
            if (hr != null || act != null) result[date] = day else result.remove(date)
            _days.value = LinkedHashMap(result)
            Log.i(
                BandClient.TAG,
                "sync $date: hr start=${hr?.start} interval=${hr?.interval} slots=${hr?.samples?.size} readings=${day.hrReadings.size} " +
                    "first=${day.hrReadings.firstOrNull()} last=${day.hrReadings.lastOrNull()} " +
                    "min=${day.hrReadings.minOfOrNull { it.second }} avg=${day.hrReadings.map { it.second }.average()} max=${day.hrReadings.maxOfOrNull { it.second }} | " +
                    "activity totals=${act?.totals} hourlySteps=${act?.hourly?.get("steps")?.joinToString(",")}",
            )
        }
        lastIndex = available
        backfill(available)
    }

    /**
     * Data from before Kaya was installed (or days it missed): every older (day, metric) the band still holds and
     * Kaya hasn't saved, then heart rate from Health Connect for the past week where the band had nothing.
     * The band forgets days once another app syncs them, so this keeps whatever is still there.
     */
    private suspend fun backfill(available: Set<Pair<LocalDate, Int>>?) {
        val today = LocalDate.now()
        val missing = available.orEmpty().filter { (d, m) -> d.isBefore(today.minusDays(1)) && m in BandSync.METRICS && store.load(d, m) == null }
            .sortedByDescending { it.first }
        if (missing.isNotEmpty()) Log.i(BandClient.TAG, "backfill from band: ${missing.size} older series")
        for ((d, m) in missing) {
            _syncStatus.update { it.copy(step = "Saving earlier days from your band ($d)") }
            runCatching { fetchSeries(d.atStartOfDay(), m, d) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        for (i in 2L..7L) {
            val d = today.minusDays(i)
            if (store.load(d, Commands.METRIC_HEART_RATE) != null) continue
            runCatching { heartRateFromHealthConnect(d) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()?.let { store.save(d, Commands.METRIC_HEART_RATE, it) }
        }
    }

    @Volatile private var lastIndex: Set<Pair<LocalDate, Int>>? = null

    /**
     * Other overnight metrics (SpO2, HRV, stress): saved and logged so their formats can be learned. Not shown yet.
     * Runs quietly after the main read so it never holds up the "Reading the band…" status.
     */
    private suspend fun fetchExtraMetrics() {
        val today = LocalDate.now()
        for (metric in listOf(Commands.METRIC_SPO2, Commands.METRIC_HRV, Commands.METRIC_STRESS)) {
            if (lastIndex?.contains(today to metric) == false) continue
            val s = fetchSeries(today.atStartOfDay(), metric, today) ?: continue
            val nz = s.samples.withIndex().filter { it.value > 0 }
            Log.i(BandClient.TAG, "metric $metric: interval=${s.interval} slots=${s.samples.size} nonzero=${nz.size} first=${nz.take(12).joinToString { "${it.index}:${it.value}" }}")
        }
        // The research probe (unknown metric ids) only runs when explicitly asked for from the Band screen,
        // not on every open: it costs ~8 extra round trips.
        if (debuggable && probeRequested) { probeRequested = false; probeUnknownMetrics() }
    }

    private val debuggable = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    @Volatile private var probeRequested = false
    fun requestProbe() { probeRequested = true }

    /**
     * Debug builds: dump the raw layout of every metric the band lists that Kaya doesn't decode yet
     * (looking for per-minute movement for sleep). Logged under tag KayaProbe.
     */
    private suspend fun probeUnknownMetrics(only: List<Int>? = null) {
        val today = LocalDate.now()
        val known = setOf(0, 2, 4, 21, 22)
        val metrics = only ?: lastIndex?.filter { it.first == today }?.map { it.second }?.filter { it !in known }?.sorted() ?: return
        Log.i("KayaProbe", "probing $metrics")
        for (m in metrics) {
            // Read-only request (113). Never 115.
            val reply = client.ask(Commands.series(Commands.TYPE_SERIES, today.atStartOfDay(), m), Commands.TYPE_SERIES, matches = { r ->
                Decode.status(r) != null || (Wire.parseOrNull(Wire.bytes(r, 9))?.any { f ->
                    f.bytes?.let { b -> Wire.int(Wire.bytes(b, 1), 2)?.toInt() == m } == true
                } == true)
            }) ?: continue
            val container = Wire.bytes(reply, 9)
            val bodies = Wire.parseOrNull(container)?.filter { it.wireType == 2 } ?: emptyList()
            for (f in bodies) {
                val fields = Wire.parseOrNull(f.bytes) ?: continue
                val desc = fields.joinToString(" ") { x ->
                    if (x.wireType == 2) "${x.number}:<${x.bytes!!.size}b>" else "${x.number}=${x.varint}"
                }
                Log.i("KayaProbe", "metric $m body f${f.number}: $desc")
                fields.filter { it.wireType == 2 && it.number != 1 }.forEach { x ->
                    val b = x.bytes!!
                    var off = 0
                    while (off < b.size) {
                        Log.i("KayaProbe", "metric $m f${x.number} (${b.size}b) @$off: ${b.copyOfRange(off, minOf(b.size, off + 400)).hex()}")
                        off += 400
                    }
                }
            }
            Decode.status(reply)?.let { Log.i("KayaProbe", "metric $m status $it") }
        }
    }

    // ------------------------------------------------------------------ sleep

    // ------------------------------------------------------------------ vitals

    private val _vitals = MutableStateFlow<Map<Int, List<Pair<LocalDateTime, Double>>>>(emptyMap())
    /** Today's readings per vital (keyed by Vital.metric), oldest first. */
    val vitals: StateFlow<Map<Int, List<Pair<LocalDateTime, Double>>>> = _vitals.asStateFlow()

    private val _vitalsLoading = MutableStateFlow(false)
    val vitalsLoading: StateFlow<Boolean> = _vitalsLoading.asStateFlow()

    /** Readings for one day: from the band if it still has the day, otherwise Kaya's saved copy. */
    private suspend fun vitalReadings(v: Vital, date: LocalDate, fromBand: Boolean): List<Pair<LocalDateTime, Double>> {
        val s = (if (fromBand && client.state.value is BandState.Connected && (lastIndex?.contains(date to v.metric) != false))
            fetchSeries(date.atStartOfDay(), v.metric, date) else null) ?: store.load(date, v.metric) ?: return emptyList()
        return Vital.readings(v, s)
    }

    /** Reads every vital for today from the band (or saved copies). */
    fun refreshVitals() {
        if (_vitalsLoading.value) return
        viewModelScope.launch {
            _vitalsLoading.value = true
            try {
                val today = LocalDate.now()
                // Show what's saved on the phone straight away, then update from the band.
                // A reading never disappears while refreshing: a metric is only replaced by an equal or fuller list.
                val out = LinkedHashMap(_vitals.value)
                fun put(m: Int, l: List<Pair<LocalDateTime, Double>>) {
                    if (l.size >= (out[m]?.size ?: 0)) { out[m] = l; _vitals.value = LinkedHashMap(out) }
                }
                if (out.isEmpty()) for (v in Vital.ALL) if (v.metric != Commands.METRIC_HEART_RATE) put(v.metric, vitalReadings(v, today, fromBand = false))
                if (!recentlySynced() && client.connect()) sync()
                for (v in Vital.ALL) {
                    put(
                        v.metric,
                        if (v.metric == Commands.METRIC_HEART_RATE) (_days.value[today]?.hrReadings ?: emptyList()).map { it.first to it.second.toDouble() }
                        else vitalReadings(v, today, fromBand = true),
                    )
                }
                if (client.backingOff) { _syncStatus.update { it.copy(failed = true) }; retryLaterIfBusy() }
                Log.i(BandClient.TAG, "vitals: " + out.entries.joinToString { (m, l) -> "m$m n=${l.size} last=${l.lastOrNull()?.second}" })
            } finally {
                _vitalsLoading.value = false
            }
        }
    }

    /** Daily summaries from everything Kaya has saved (last 60 days), newest first. */
    fun history(v: Vital): List<VitalDay> = store.dates(v.metric).take(60).mapNotNull { d ->
        val s = store.load(d, v.metric) ?: return@mapNotNull null
        val values = Vital.readings(v, s).map { it.second }
        if (values.isEmpty()) null else VitalDay(d, values.min(), values.average(), values.max(), values.size)
    }

    /** Inputs for the Energy score: today's overnight resting HR / HRV and your usual levels from saved history. */
    data class RecoveryInputs(val restingHr: Double?, val restingBaseline: Double?, val hrv: Double?, val hrvBaseline: Double?)

    private val fwPrefs get() = getApplication<Application>().getSharedPreferences("band_firmware", android.content.Context.MODE_PRIVATE)

    /** Remembers the band's firmware and the day it last changed: an update can change how the band measures HRV. */
    private fun noteFirmware(fw: String) {
        if (fw.isBlank()) return
        val known = fwPrefs.getString("fw", null)
        if (known == fw) return
        fwPrefs.edit().putString("fw", fw).apply {
            if (known != null) putString("since", LocalDate.now().toString())
        }.apply()
    }

    fun recoveryInputs(): RecoveryInputs {
        val today = LocalDate.now()
        val night = { l: List<Pair<LocalDateTime, Double>> -> l.filter { it.first.toLocalDate() == today && it.first.hour < 8 }.map { it.second } }
        val rhr = restingHr(today)
        val hrv = night(_vitals.value[Vital.HRV.metric] ?: emptyList()).takeIf { it.isNotEmpty() }?.average()
        val rhrBase = restingHrHistory().filter { it.first != today }.take(14).map { it.second }.takeIf { it.size >= 2 }?.average()
        // Like for like: tonight's HRV against earlier nights' averages (the all-day average runs higher, so comparing a
        // night with it made every night look a little "below usual").
        // Your usual: the median of the last 7 nights, and only nights since the band's last firmware update (an update
        // changed the band's HRV scale on a tester's band: nightly averages around 65 before, around 45 after). A rolling
        // week also re-centres by itself after an update Kaya didn't see happen.
        val fwSince = fwPrefs.getString("since", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        fun median(l: List<Double>) = l.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val hrvBase = com.kaya.health.data.BodyLog(getApplication()).nightRecords()
            .filter { it.wakeDay != today && (fwSince == null || it.wakeDay.isAfter(fwSince)) }.takeLast(7)
            .mapNotNull { it.hrv }.takeIf { it.size >= 2 }?.let(::median)
            ?: history(Vital.HRV).filter { it.date != today && (fwSince == null || it.date.isAfter(fwSince)) }.take(7).map { it.avg }
                .takeIf { it.size >= 2 }?.let(::median)
        return RecoveryInputs(rhr, rhrBase, hrv, hrvBase)
    }

    /** Resting heart rate for [date]: the lowest 30-minute average in that day's heart-rate log (usually asleep). */
    fun restingHr(date: LocalDate): Double? = savedBandDay(date)?.hrReadings?.let { com.kaya.health.engine.RestingHr.fromReadings(it) }

    /** Resting heart rate for each saved day (up to 60), newest first. */
    fun restingHrHistory(): List<Pair<LocalDate, Double>> =
        store.dates(Commands.METRIC_HEART_RATE).take(60).mapNotNull { d -> restingHr(d)?.let { d to it } }

    /** Any day's steps and heart rate as saved on the phone (live copy for today and yesterday). */
    fun savedBandDay(date: LocalDate): BandDay? = _days.value[date] ?: run {
        val hr = store.load(date, Commands.METRIC_HEART_RATE)
        val act = store.load(date, Commands.METRIC_ACTIVITY)
        if (hr == null && act == null) null else BandDay(date, hr, act, Instant.EPOCH)
    }

    fun savedHourlySteps(date: LocalDate): IntArray? = _days.value[date]?.hourlySteps ?: store.load(date, Commands.METRIC_ACTIVITY)?.hourly?.get("steps")

    /** One day's readings from Kaya's saved copy (for the detail view). */
    fun savedDay(v: Vital, date: LocalDate): List<Pair<LocalDateTime, Double>> = store.load(date, v.metric)?.let { Vital.readings(v, it) } ?: emptyList()

    private val _sleep = MutableStateFlow(SleepUi())
    val sleep: StateFlow<SleepUi> = _sleep.asStateFlow()

    /** Reads the night from the band (or Kaya's saved copy) and works out sleep. */
    fun analyzeSleep() {
        if (_sleep.value.loading) return
        viewModelScope.launch {
            // First pass: what's already saved on the phone, so the screen fills in straight away.
            _sleep.update { it.copy(loading = true, message = if (it.result == null) "Reading last night…" else null) }
            runCatching { computeSleep(online = false) }
            _sleep.update { it.copy(loading = true) }
            // Second pass: fresh data from the band, replacing the first result when it arrives.
            try {
                if (!recentlySynced() && client.connect()) {
                    sync()
                    viewModelScope.launch { fetchExtraMetrics() }
                }
                computeSleep(online = true)
                if (client.backingOff) { _syncStatus.update { it.copy(failed = true) }; retryLaterIfBusy() }
            } catch (e: Exception) {
                _sleep.update { it.copy(loading = false, message = if (it.result == null) "Couldn't analyse: ${e.message}" else null) }
            }
        }
    }

    private suspend fun computeSleep(online: Boolean) {
            run {
                val today = LocalDate.now()
                val yesterday = today.minusDays(1)
                val readings = ArrayList<Pair<LocalDateTime, Int>>()
                val steps = HashMap<LocalDateTime, Int>()
                for (d in listOf(yesterday, today)) {
                    val day = _days.value[d] ?: BandDay(d, store.load(d, Commands.METRIC_HEART_RATE) ?: heartRateFromHealthConnect(d), store.load(d, Commands.METRIC_ACTIVITY), Instant.now())
                    readings += day.hrReadings
                    day.hourlySteps?.forEachIndexed { h, v -> steps[d.atStartOfDay().plusHours(h.toLong())] = v }
                }
                val from = yesterday.atTime(18, 0)
                val to = minOf(LocalDateTime.now(), today.atTime(14, 0))
                // The band's own sleep window: it records breathing (metric 19) only while its motion sensor says
                // you're asleep. Longest run of breathing readings in the night window (gaps up to 30 min merged).
                val breath = ArrayList<Pair<LocalDateTime, Int>>()
                for (d in listOf(yesterday, today)) {
                    val s = (if (online && client.state.value is BandState.Connected && (lastIndex?.contains(d to Commands.METRIC_SLEEP_BREATHING) != false))
                        fetchSeries(d.atStartOfDay(), Commands.METRIC_SLEEP_BREATHING, d) else null)
                        ?: store.load(d, Commands.METRIC_SLEEP_BREATHING) ?: continue
                    val step = (s.interval ?: 5).toLong()
                    s.samples.forEachIndexed { i, v -> if (v in 1..254) breath += s.start.plusMinutes(i * step) to v }
                }
                val inWindow = breath.filter { !it.first.isBefore(from) && it.first.isBefore(to) }.sortedBy { it.first }
                // Band's sleep log joined across quiet gaps (it can skip an hour while you're plainly asleep).
                val bandWindow: Pair<LocalDateTime, LocalDateTime>? = com.kaya.health.engine.SleepWindow.find(
                    inWindow.map { it.first }, steps, readings.filter { !it.first.isBefore(from) && it.first.isBefore(to) },
                )
                val breathing = bandWindow?.let { (a, b) -> inWindow.filter { !it.first.isBefore(a) && it.first.isBefore(b) }.map { it.second } }
                Log.i(BandClient.TAG, "band sleep window (breathing): $bandWindow, breaths/min avg=${breathing?.average()}")
                // Without the band's own sleep log, heart rate alone can mistake a quiet, seated morning for sleep:
                // never let that fallback run past 11 am.
                // With the band's window, stop an hour after it says you woke: later daytime readings would otherwise
                // shift the heart-rate baseline, and last night's numbers would change every time Kaya re-analysed.
                val hrOnlyTo = if (bandWindow == null) minOf(to, today.atTime(11, 0)) else minOf(to, bandWindow.second.plusHours(1))
                // Gaps of 20+ minutes in the band's breathing log are times it saw you awake. Each one checked so far
                // matched a wake-up in the band's own firmware log (e.g. 3:28–3:50 on a tester's night).
                val awakeSpans = bandWindow?.let { w -> com.kaya.health.engine.SleepWindow.awakeGaps(inWindow.map { it.first }, w) } ?: emptyList()
                Log.i(BandClient.TAG, "band saw you awake: $awakeSpans")
                val r0 = com.kaya.health.engine.SleepAnalyzer.analyze(readings, steps, from, hrOnlyTo, bandWindow, awakeSpans, inWindow.map { it.first })
                Log.i(
                    BandClient.TAG,
                    "sleep: readings=${readings.size} window=$from..$to → " + (r0?.let {
                        "bed=${it.bedtime} wake=${it.wake} asleep=${it.asleepMin} inBed=${it.inBedMin} stages=${it.minutes} " +
                            "awakenings=${it.awakenings} hr avg=${it.avgSleepingHr} low=${it.lowestHr} awakeHr=${it.awakeHr} " +
                            "coverage=${"%.2f".format(it.dataCoverage)} score=${it.score} ${it.scoreParts}"
                    } ?: "no sleep found"),
                )
                // Naps, yesterday and today: everything outside the main nights (last night, and yesterday's morning).
                val bodyLog = com.kaya.health.data.BodyLog(getApplication())
                val lastNight = (bandWindow ?: r0?.let { it.bedtime to it.wake })
                val yWake = bodyLog.nightRecords().firstOrNull { it.wakeDay == yesterday }?.wake ?: yesterday.atTime(10, 0)
                val napFrom = yWake.plusMinutes(30)
                val napTo = LocalDateTime.now()
                val nights = listOfNotNull(
                    LocalDateTime.MIN to napFrom,
                    lastNight?.let { (a, b) -> a.minusMinutes(30) to b.plusMinutes(30) } ?: (today.atStartOfDay() to today.atTime(9, 0)),
                )
                val fromBand = com.kaya.health.engine.NapFinder.fromBreathing(breath.map { it.first }.filter { it.isBefore(napTo) }, nights)
                val fromHc = runCatching { health.sleepSessions(napFrom, napTo) }.getOrDefault(emptyList())
                    .filter { (a, b) -> nights.none { (x, y) -> a.isBefore(y) && b.isAfter(x) } }
                    .filter { java.time.Duration.between(it.first, it.second).toMinutes() in com.kaya.health.engine.NapFinder.MIN_NAP..240 }
                val sleepingHr = r0?.avgSleepingHr ?: bodyLog.nightRecords().lastOrNull()?.avgHr
                val fromHr = if (fromBand.isEmpty() && fromHc.isEmpty() && sleepingHr != null)
                    com.kaya.health.engine.NapFinder.fromHeart(readings, steps, sleepingHr, napFrom, napTo, nights) else emptyList()
                Log.i(BandClient.TAG, "naps: band=$fromBand healthConnect=$fromHc heart=$fromHr (outside $nights)")
                val found = com.kaya.health.engine.NapFinder.merge(fromBand, fromHc, fromHr)
                // Back to sleep after an early wake-up is the same night, not a nap. After getting up the band sometimes
                // decides it's off your wrist and logs no sleep at all (4:29–8:02 on a tester's night), so heart rate
                // at or below the night's sleeping level also counts. Sleep starting within an hour of waking, before
                // 11 am, joins the night; the gap in between is awake time.
                val base = r0
                val r = if (base == null || lastNight == null) base else run {
                    val lateHr = com.kaya.health.engine.NapFinder.fromHeart(
                        // Clearly below the night's sleeping average (limit = average − 3) and 45+ minutes long: lying in
                        // bed awake for half an hour after the alarm isn't more sleep.
                        readings, steps, base.avgSleepingHr - 9, lastNight.second, minOf(LocalDateTime.now(), today.atTime(11, 0)), emptyList(),
                    ).filter { java.time.Duration.between(it.first, it.second).toMinutes() >= 45 }
                    val more = com.kaya.health.engine.SleepWindow.morningContinuation(lastNight.second, com.kaya.health.engine.NapFinder.merge(found, lateHr))
                    if (more.isEmpty()) base else {
                        val end = more.last().second
                        val gaps = (listOf(lastNight.second to lastNight.second) + more).zipWithNext { a, b -> a.second to b.first }
                            .filter { (a, b) -> java.time.Duration.between(a, b).toMinutes() >= 5 }
                        val inside = more.flatMap { p -> com.kaya.health.engine.SleepWindow.awakeGaps(inWindow.map { it.first }.filter { !it.isBefore(p.first) && it.isBefore(p.second) }, p) }
                        Log.i(BandClient.TAG, "back to sleep after waking: $more, awake in between: $gaps")
                        com.kaya.health.engine.SleepAnalyzer.analyze(
                            readings, steps, from, minOf(to, end.plusHours(1)), base.bedtime to end, awakeSpans + gaps + inside, inWindow.map { it.first },
                        ) ?: base
                    }
                }
                val night = r?.let { it.bedtime to it.wake }
                fun notInNight(n: Pair<LocalDateTime, LocalDateTime>) = night == null || !(n.first.isBefore(night.second) && n.second.isAfter(night.first))
                night?.let { bodyLog.dropNaps(today, it) }
                val naps = bodyLog.addNaps(today, found.filter { it.first.toLocalDate() == today && notInNight(it) }).filter(::notInNight)
                val yesterdayNaps = bodyLog.addNaps(yesterday, found.filter { it.first.toLocalDate() == yesterday })
                if (r != null) runCatching {
                    val log = com.kaya.health.data.BodyLog(getApplication())
                    log.saveNight(r.wake.toLocalDate(), r.asleepMin)
                    // Overnight averages of the band's other sleep measurements, for baselines (illness/strain hints).
                    fun night(v: Vital) = listOf(yesterday, today).flatMap { d -> store.load(d, v.metric)?.let { Vital.readings(v, it) } ?: emptyList() }
                        .filter { !it.first.isBefore(r.bedtime) && it.first.isBefore(r.wake) }.map { it.second }.takeIf { it.size >= 3 }?.average()
                    log.saveNightRecord(com.kaya.health.engine.NightRecord(
                        r.wake.toLocalDate(), r.bedtime, r.wake, r.asleepMin, r.avgSleepingHr,
                        breathing?.takeIf { it.isNotEmpty() }?.average(), night(Vital.SKIN_TEMP), night(Vital.HRV),
                    ))
                    com.kaya.health.data.SleepArchive(getApplication()).save(
                        r.wake.toLocalDate(), r, breathing?.takeIf { it.isNotEmpty() }?.average(),
                        breathing?.takeIf { it.isNotEmpty() }?.let { it.min() to it.max() },
                    )
                }
                _sleep.value = SleepUi(
                    naps = naps,
                    yesterdayNaps = yesterdayNaps,
                    result = r,
                    fromBandWindow = r != null && bandWindow != null,
                    breathingAvg = breathing?.takeIf { it.isNotEmpty() }?.average(),
                    breathingRange = breathing?.takeIf { it.isNotEmpty() }?.let { it.min() to it.max() },
                    readings = readings.filter { !it.first.isBefore(from) && it.first.isBefore(to) },
                    analysedAt = Instant.now(),
                    message = if (r == null) (if (online) "No sleep found between 6 pm yesterday and now. If you've just woken up, make sure you wore the band to bed. The band needs about 2 hours of night readings." else "Reading last night…") else null,
                    loading = !online,
                )
            }
    }

    /** Tries both series command shapes and returns [metric]'s series for [day], if the band has it. */
    private val fetchedAt = java.util.concurrent.ConcurrentHashMap<Pair<LocalDate, Int>, Instant>()

    private val fetchLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun fetchSeries(since: LocalDateTime, metric: Int, day: LocalDate): Series? = fetchLock.withLock {
        // Sync, Vitals and Sleep often want the same series at the same moment. One at a time, and anything tried in
        // the last 5 minutes (even if the band had nothing or didn't answer) comes from the saved copy instead of
        // asking the band again.
        fetchedAt[day to metric]?.let { t ->
            if (java.time.Duration.between(t, Instant.now()).toMinutes() < 5) return@withLock store.load(day, metric)
        }
        fetchedAt[day to metric] = Instant.now()
        fetchSeriesFromBand(since, metric, day) ?: store.load(day, metric)
    }

    private suspend fun fetchSeriesFromBand(since: LocalDateTime, metric: Int, day: LocalDate): Series? {
        // Only 113 (REQUEST_FITNESS_TYPE_ID). Never 115: that's CONFIRM, which can tell the band to clear the data.
        for (type in listOf(Commands.TYPE_SERIES)) {
            // Only accept a reply for this metric and day (or a status). NoiseFit asks the same command types.
            val reply = client.ask(Commands.series(type, since, metric), type, matches = { r ->
                Decode.status(r) != null || Decode.allSeries(r).any { it.metric == metric && it.start.toLocalDate() == day }
            }) ?: continue
            Decode.status(reply)?.let { Log.i(BandClient.TAG, "series $type metric $metric → status $it") }
            val all = Decode.allSeries(reply).filter { it.metric == metric }
            Log.i(BandClient.TAG, "series $type metric $metric → ${all.size} bodies: " + all.joinToString { "${it.start} n=${it.samples.size} int=${it.interval}" })
            val s = all.firstOrNull { it.start.toLocalDate() == day } ?: all.maxByOrNull { it.start }
            if (s != null && (s.samples.any { it > 0 } || s.hourly.isNotEmpty())) {
                store.save(s.start.toLocalDate(), metric, s)
                return s
            }
        }
        return null
    }

    /** The band's newest heart-rate reading (30-second slots). */
    private suspend fun latestReading(): Pair<LocalDateTime, Int>? {
        // The band only accepts requests that start at midnight, so fetch today's whole series (~3 KB).
        val today = LocalDate.now()
        val s = fetchSeriesFromBand(today.atStartOfDay(), Commands.METRIC_HEART_RATE, today) ?: return null
        _days.value[today]?.let { d -> _days.value = _days.value + (today to d.copy(heartRate = s, syncedAt = Instant.now())) }
        val step = (s.interval ?: 30).toLong()
        val idx = s.samples.indexOfLast { it > 0 }
        if (idx < 0) return null
        return s.start.plusSeconds(idx * step) to s.samples[idx]
    }

    /**
     * "Check heart rate".
     *  1. Shows the band's newest stored reading straight away (30-second record).
     *  2. Tells the band "app is open" (type 164), so it pushes a live summary (type 165) with the
     *     current heart rate and today's steps about every 10 s. Found by recording NoiseFit's traffic.
     *  3. If a workout is running on the band, its 2-second heart-rate stream (type 363) is shown too.
     * On stop, tells the band "app in background" again.
     */
    fun startLive() {
        if (liveJob?.isActive == true) return
        liveJob = viewModelScope.launch {
            _live.value = LiveUi(running = true, note = "Connecting…")
            if (!client.connect()) {
                _live.value = LiveUi(note = "Couldn't reach the band.")
                return@launch
            }
            var gotLive = false
            val listener = launch {
                client.payloads.collect { p ->
                    Decode.liveSummary(p)?.let { s ->
                        Log.i(BandClient.TAG, "live summary: hr=${s.heartRate} spo2=${s.spo2} stress=${s.stress} battery=${s.batteryPct}${if (s.charging) "+" else ""} sleep=${s.sleepMinutes} steps=${s.steps} kcal=${s.calories} dist=${s.distanceM}m")
                        s.batteryPct?.let { _battery.value = Battery(it, s.charging, false) }
                        _live.update { it.copy(spo2 = s.spo2.takeIf { v -> v in 70..100 } ?: it.spo2, stress = s.stress.takeIf { v -> v in 1..100 } ?: it.stress) }
                        if (s.heartRate > 0) {
                            gotLive = true
                            _live.update { it.copy(bpm = s.heartRate, at = LocalDateTime.now(), mode = LiveMode.STREAM, note = "Live from the band, updating every ~2 seconds until you tap Stop", history = (it.history + s.heartRate).takeLast(90)) }
                        }
                        updateTodaySteps(s)
                        if (_walk.value.running && s.steps > 0) _walk.update { w ->
                            val base = w.startSteps ?: s.steps
                            w.copy(startSteps = base, steps = (s.steps - base).coerceAtLeast(0), hr = if (s.heartRate > 0) w.hr + s.heartRate else w.hr)
                        }
                    }
                    Decode.workoutHeartRate(p)?.let { hr ->
                        Log.i(BandClient.TAG, "workout live hr=$hr")
                        gotLive = true
                        _live.update { it.copy(bpm = hr, at = LocalDateTime.now(), mode = LiveMode.STREAM, note = "Live from the band (workout running), every 2 seconds") }
                    }
                }
            }
            try {
                _live.update { it.copy(note = "Reading the band… keep it snug and your arm still.") }
                // While Kaya is already reading vitals, don't queue a full-day fetch behind it: show the newest saved
                // reading instantly and go straight to the live stream (the band handles one message at a time, so
                // live requests slot in between the vitals reads).
                val quick = if (_busy.value != null || _vitalsLoading.value)
                    _days.value[LocalDate.now()]?.hrReadings?.lastOrNull()?.let { it.first to it.second }
                else latestReading()
                quick?.let { (t, bpm) ->
                    if (!gotLive) _live.update { it.copy(bpm = bpm, at = t, mode = LiveMode.LATEST_READING) }
                }
                client.tell(Commands.appForeground())
                if (!gotLive) _live.update { it.copy(note = "Waiting for the band's live reading (up to ~10 s)…") }
                // Re-announce "app open" every 2 s: each one makes the band push a fresh summary right away
                // (the band answered the first 164 instantly), giving a faster live reading than its 10-s default.
                while (isActive) {
                    delay(LIVE_TRIGGER_MS)
                    client.tell(Commands.appForeground())
                }
            } finally {
                listener.cancel()
            }
        }
        liveJob?.invokeOnCompletion {
            _live.update { it.copy(running = false) }
            viewModelScope.launch { if (client.state.value is BandState.Connected) client.tell(Commands.appBackground()) }
        }
    }

    /** Keeps today's step total on screen current from the live summary. */
    private fun updateTodaySteps(s: LiveSummary) {
        val today = LocalDate.now()
        val d = _days.value[today] ?: return
        val act = d.activity ?: return
        val totals = act.totals + mapOf("steps" to s.steps.toLong(), "distance_m" to s.distanceM.toLong(), "calories" to s.calories.toLong())
        _days.value = _days.value + (today to d.copy(activity = act.copy(totals = totals)))
    }

    fun stopLive() {
        liveJob?.cancel()
    }

    fun disconnect() {
        stopLive()
        client.disconnect()
    }

    init {
        runCatching { BandSync.schedule(app) }
        BandSync.viaApp = { backgroundSyncAll() }
        // The link can drop when another app ends its session on the shared connection. While Kaya is on screen,
        // reconnect by itself (a few times a minute at most) instead of leaving the user on "disconnected".
        viewModelScope.launch {
            var prev: BandState = client.state.value
            val recent = ArrayDeque<Long>()
            client.state.collect { st ->
                val dropped = prev is BandState.Connected && st is BandState.Failed
                prev = st
                if (!dropped || !inForeground || _live.value.running) return@collect
                val now = System.currentTimeMillis()
                while (recent.isNotEmpty() && now - recent.first() > 60_000) recent.removeFirst()
                if (recent.size >= 3) return@collect
                recent.addLast(now)
                _reconnecting.value = true
                delay(3_000)
                if (inForeground && client.state.value !is BandState.Connected) connectAndSync()
                _reconnecting.value = false
            }
        }
        // Passive capture: when another app (NoiseFit) syncs over the shared link, the band's data replies pass by
        // Kaya too. Save any series that's new or more complete than Kaya's copy, before the band deletes the day.
        viewModelScope.launch {
            client.payloads.collect { p ->
                if (Decode.type(p)?.toInt() == 4097) { runCatching { bandFiles.capture(p) }.onFailure { Log.w(BandClient.TAG, "file chunk", it) }; return@collect }
                if (Decode.type(p)?.toInt() != Commands.TYPE_SERIES) return@collect
                val series = runCatching { Decode.allSeries(p) }.getOrDefault(emptyList())
                    .filter { s -> s.samples.any { it > 0 } || s.hourly.isNotEmpty() }
                for (s in series) {
                    val day = s.start.toLocalDate()
                    store.save(day, s.metric, s)
                    if (s.metric == Commands.METRIC_HEART_RATE || s.metric == Commands.METRIC_ACTIVITY) {
                        val hr = store.load(day, Commands.METRIC_HEART_RATE); val act = store.load(day, Commands.METRIC_ACTIVITY)
                        _days.value = LinkedHashMap(_days.value).apply { put(day, BandDay(day, hr, act, Instant.now())) }
                    }
                }
                if (series.isNotEmpty()) Log.i(BandClient.TAG, "captured in passing: " + series.joinToString { "${it.start.toLocalDate()} m${it.metric}" })
            }
        }
    }

    /**
     * Lets go of the band when you leave Kaya, so NoiseFit (or anything else) can sync without fighting for it.
     * Kept connected while a live heart-rate check or a walk is running.
     */
    @Volatile private var inForeground = true
    private var retryJob: Job? = null
    private var retries = 0

    /** Kaya is on screen again. */
    fun onForeground() {
        inForeground = true; retries = 0
        lingerJob?.cancel()
        probeMetrics?.let { ms ->
            probeMetrics = null
            viewModelScope.launch {
                Log.i("KayaProbe", "probe requested: $ms")
                kotlinx.coroutines.delay(8_000) // let the normal connect + sync start first
                val ok = kotlinx.coroutines.withTimeoutOrNull(60_000) { client.state.first { it is BandState.Connected } } != null
                Log.i("KayaProbe", "connected=$ok")
                if (4097 in ms) bandFiles.dump()
                if (ok) fetchLock.withLock { probeUnknownMetrics(ms.filter { it != 4097 }) }
            }
        }
        // Kaya let go of the band when it went to the background; take it back so the header and data are live.
        val st = client.state.value
        if (hasPermissions(getApplication()) && st !is BandState.Connected && st !is BandState.Connecting) connectAndSync()
    }

    /**
     * The band ignored us because another app (NoiseFit) was busy with it. Once the pause is over, try again
     * by ourselves (up to twice) while Kaya is on screen, so "Band busy" clears without reopening the app.
     */
    private fun retryLaterIfBusy() {
        if (!client.backingOff || !inForeground || retries >= 2 || retryJob?.isActive == true) return
        retries++
        retryJob = viewModelScope.launch {
            delay(50_000)
            if (!inForeground) return@launch
            Log.i(BandClient.TAG, "band was busy: retrying (attempt $retries)")
            // Steps and heart rate too, not just vitals: a busy band can leave today's activity missing.
            if (client.state.value is BandState.Connected) { syncJob?.join(); sync(force = true) }
            refreshVitals()
            analyzeSleep()
        }
    }

    private val _buzz = MutableStateFlow<String?>(null)
    /** Result of the last "Buzz my band" try, for the screen. */
    val buzz: StateFlow<String?> = _buzz.asStateFlow()

    /** Sends FIND_WEAR (161) and logs whatever the band answers for a few seconds. */
    fun buzzBand() {
        viewModelScope.launch {
            _buzz.value = "Asking the band to buzz…"
            if (!client.connect()) { _buzz.value = "Couldn't reach the band."; return@launch }
            val replies = ArrayList<String>()
            val listener = launch {
                client.payloads.collect { p ->
                    val t = Decode.type(p)
                    if (t != 165L && t != 4097L) { replies += "type $t: ${p.hex()}"; Log.i(BandClient.TAG, "buzz reply type $t: ${p.hex()}") }
                }
            }
            // Read-only first: is vibration switched on at all?
            client.ask(Commands.getVibrationIntensity(), 232, timeoutMs = 3_000)?.let { Log.i(BandClient.TAG, "buzz: vibration intensity reply ${it.hex()}") }
            client.ask(Commands.getNotificationSettings(), 495, timeoutMs = 3_000)?.let { Log.i(BandClient.TAG, "buzz: notification settings reply ${it.hex()}") }
            _buzz.value = "Test 1: app notification…"
            val a = client.tell(Commands.appNotification("Kaya", "Buzz test 1"))
            delay(8_000)
            _buzz.value = "Test 2: message notification…"
            val b = client.tell(Commands.messageNotification("Kaya", "Buzz test 2"))
            delay(4_000)
            listener.cancel()
            Log.i(BandClient.TAG, "buzz: app=$a message=$b replies=$replies")
            _buzz.value = "Done. Did the band buzz at test 1, at test 2 (8 s later), both or neither?"
        }
    }

    fun releaseIfIdle() {
        inForeground = false
        retryJob?.cancel()
        if (_live.value.running || _walk.value.running) return
        if (client.state.value is BandState.Connected || client.state.value is BandState.Connecting) {
            // Mornings, until last night's beat-to-beat file has been seen: keep listening for 5 minutes, so when
            // NoiseFit syncs (it asks the band for the file) Kaya saves a copy as it passes. Kaya never asks for it.
            val h = java.time.LocalTime.now().hour
            if (h in 5..13 && !bandFiles.hasNightFile(LocalDate.now())) {
                Log.i(BandClient.TAG, "app in background: listening 5 min for last night's file")
                lingerJob?.cancel()
                lingerJob = viewModelScope.launch {
                    delay(5 * 60_000)
                    if (!inForeground && !_live.value.running && !_walk.value.running) { Log.i(BandClient.TAG, "releasing the band"); client.disconnect() }
                }
                return
            }
            Log.i(BandClient.TAG, "app in background: releasing the band")
            client.disconnect()
        }
    }
    private var lingerJob: kotlinx.coroutines.Job? = null

    override fun onCleared() {
        BandSync.viaApp = null
        client.disconnect()
    }

    companion object {
        const val LIVE_TRIGGER_MS = 2_000L
        /** Set from MainActivity's "kaya_probe" extra; consumed on the next foreground. */
        @Volatile var probeMetrics: List<Int>? = null

        /** Bluetooth permissions needed for the current Android version. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
