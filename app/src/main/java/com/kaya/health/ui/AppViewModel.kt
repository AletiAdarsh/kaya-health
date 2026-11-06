package com.kaya.health.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.MealType
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaya.health.data.DataCheck
import com.kaya.health.data.DayActivity
import com.kaya.health.data.FavouritesStore
import com.kaya.health.data.FoodEntry
import com.kaya.health.data.FoodFavourite
import com.kaya.health.data.HealthRepository
import com.kaya.health.data.ProfileStore
import com.kaya.health.data.SessionData
import com.kaya.health.data.SessionOverrideStore
import com.kaya.health.engine.SessionCheck
import com.kaya.health.engine.SessionRules
import com.kaya.health.data.SleepData
import com.kaya.health.data.WeightEntry
import com.kaya.health.engine.ActivityKind
import com.kaya.health.engine.BurnEstimate
import com.kaya.health.engine.Calories
import com.kaya.health.engine.DayWeight
import com.kaya.health.engine.Profile
import com.kaya.health.engine.RealBurn
import com.kaya.health.engine.SessionEstimate
import com.kaya.health.engine.SessionInput
import com.kaya.health.engine.Sex
import com.kaya.health.engine.TrendPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.max

data class HcState(
    val status: Int? = null,
    val granted: Set<String> = emptySet(),
) {
    val checked get() = status != null
    val available get() = status == HealthConnectClient.SDK_AVAILABLE
    val needsUpdate get() = status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
    val connected get() = available && granted.isNotEmpty()
    val missing get() = HealthRepository.PERMISSIONS - granted
}

data class SessionRow(
    val data: SessionData,
    val est: SessionEstimate,
    val hrFormulaKcal: Double?,
    val check: SessionCheck,
) {
    /** Calories came from measured movement (distance/steps) rather than an activity average. */
    val movementMeasured get() = est.speedKmh != null
}

/**
 * Digesting food costs about 10% of what you eat. Food isn't always logged, so a day never counts less than
 * [MIN_DIGESTION] kcal for it (spread across the day: half of it by noon).
 */
const val MIN_DIGESTION = 150.0
fun digestionKcal(eaten: Double, dayFraction: Double = 1.0) = maxOf(0.10 * eaten, MIN_DIGESTION * dayFraction.coerceIn(0.0, 1.0))

data class DayBurn(
    val resting: Double, val workouts: Double, val everydaySteps: Double, val digestion: Double,
    /** What heart rate adds to walks beyond their steps (see Walk.heartExtraKcal). */
    val walkHeart: Double = 0.0,
) {
    val active get() = workouts + everydaySteps + walkHeart
    val total get() = resting + workouts + everydaySteps + walkHeart + digestion
    /** The likely range, shown in brackets after the number, e.g. "(1,210–1,430)". */
    val rangeText: String get() = com.kaya.health.engine.Calories.burnRange(total, resting).let { (lo, hi) -> "(${lo.kcal()}–${hi.kcal()})" }
}

data class TodayUi(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val date: LocalDate = LocalDate.now(),
    val activity: DayActivity? = null,
    val restingHr: Long? = null,
    val sleep: SleepData? = null,
    val sessions: List<SessionRow> = emptyList(),
    val burn: DayBurn? = null,
    val eatenKcal: Double = 0.0,
    val targetKcal: Double? = null,
    val weightKg: Double = 0.0,
    val weightFromHealthConnect: Boolean = false,
    val sources: List<String> = emptyList(),
    val hasNoiseData: Boolean = false,
    /** Earliest heart-rate reading NoiseFit shared today. Readings before this didn't reach Kaya. */
    val noiseHrFrom: Instant? = null,
    /** Today's calorie total written out step by step. */
    val working: List<String> = emptyList(),
    val error: String? = null,
)

data class CheckUi(
    val loading: Boolean = false,
    val date: LocalDate = LocalDate.now(),
    val check: DataCheck? = null,
    val rows: List<SessionRow> = emptyList(),
    val error: String? = null,
)

data class FoodUi(
    val loading: Boolean = false,
    val date: LocalDate = LocalDate.now(),
    val entries: List<FoodEntry> = emptyList(),
    val favourites: List<FoodFavourite> = emptyList(),
    val targetKcal: Double? = null,
    val error: String? = null,
    /** Foods logged in the last two weeks, one portion each, most recent first (for quick re-logging). */
    val recent: List<com.kaya.health.data.FoodItem> = emptyList(),
    /** The day before [date], so a meal can be repeated in one tap. */
    val previousDay: List<FoodEntry> = emptyList(),
) {
    val totalKcal get() = entries.sumOf { it.kcal }
    val totalProtein get() = entries.sumOf { it.proteinG ?: 0.0 }
    val totalCarbs get() = entries.sumOf { it.carbsG ?: 0.0 }
    val totalFat get() = entries.sumOf { it.fatG ?: 0.0 }
    val totalFiber get() = entries.sumOf { it.fiberG ?: 0.0 }
    /** True when at least one food today has carbs/fat recorded (older entries may only have calories). */
    val hasMacros get() = entries.any { it.carbsG != null || it.fatG != null }
}

data class BodyUi(
    /** The weight-goal plan (null when no goal is set). Recomputed on every refresh from weigh-ins and food. */
    val plan: com.kaya.health.engine.GoalPlan? = null,
    val loading: Boolean = false,
    val trend: List<TrendPoint> = emptyList(),
    val recent: List<WeightEntry> = emptyList(),
    val latestKg: Double? = null,
    val estimate: BurnEstimate? = null,
    val targetKcal: Double? = null,
    val walks: List<SessionRow> = emptyList(),
    val error: String? = null,
    val motivation: MotivationUi? = null,
    /** Calories eaten per logged day, last 35 days (for insights). */
    val intake: List<com.kaya.health.engine.DayIntake> = emptyList(),
)

/** Everything the "keep going" parts of the app need. */
data class MotivationUi(
    val weighStreak: com.kaya.health.engine.Streak,
    val foodStreak: com.kaya.health.engine.Streak,
    /** Finished days' steps this week and before (band or Health Connect); today's live count is added on screen. */
    val stepsByDay: Map<LocalDate, Long>,
    val proteinToday: Double,
    val eatenToday: Double,
    val lostKg: Double,
    val lowestKg: Double?,
    val newLowToday: Boolean,
    val checkpoint: com.kaya.health.engine.CheckpointView?,
    val realisticKgPerWeek: Double,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val bandStore = com.kaya.health.band.BandStore(app)
    private val walkStore = com.kaya.health.band.WalkStore(app)

    /** The day's walks (as on the Activity tab) and what their heart rate adds on top of the steps-based burn. */
    private fun walkHeart(date: LocalDate): Triple<List<com.kaya.health.engine.Walk>, Double, Double> {
        val hr = bandStore.load(date, com.kaya.health.band.Commands.METRIC_HEART_RATE)
        val act = bandStore.load(date, com.kaya.health.band.Commands.METRIC_ACTIVITY)
        if (hr == null || act == null) return Triple(emptyList(), 0.0, 70.0)
        val day = com.kaya.health.band.BandDay(date, hr, act, Instant.EPOCH)
        val resting = com.kaya.health.engine.RestingHr.fromReadings(day.hrReadings) ?: 70.0
        val p = _profile.value
        val walks = com.kaya.health.band.walksFor(date, day, walkStore, resting, p.age)
        return Triple(walks, walks.sumOf { it.heartExtraKcal(weightKg(), p.heightCm, p.age, p.sex, bmr(), resting) }, resting)
    }
    init {
        runCatching { com.kaya.health.data.Backup.schedule(app) }
        runCatching { com.kaya.health.data.Reminders.schedule(app) }
    }
    private val repo = HealthRepository(app)
    private val profileStore = ProfileStore(app)
    private val favStore = FavouritesStore(app)
    private val overrides = SessionOverrideStore(app)
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val ownPackage = app.packageName

    private val _profile = MutableStateFlow(profileStore.load())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    private val _hc = MutableStateFlow(HcState())
    val hc: StateFlow<HcState> = _hc.asStateFlow()

    private val _today = MutableStateFlow(TodayUi())
    val today: StateFlow<TodayUi> = _today.asStateFlow()

    private val _food = MutableStateFlow(FoodUi(favourites = favStore.load()))
    val food: StateFlow<FoodUi> = _food.asStateFlow()

    private val _body = MutableStateFlow(BodyUi())
    val body: StateFlow<BodyUi> = _body.asStateFlow()

    private val _check = MutableStateFlow(CheckUi())
    val check: StateFlow<CheckUi> = _check.asStateFlow()

    private val _snack = MutableStateFlow<String?>(null)
    val snack: StateFlow<String?> = _snack.asStateFlow()
    fun snackShown() { _snack.value = null }

    // ------------------------------------------------------------------ backup

    private val _backup = MutableStateFlow(com.kaya.health.data.Backup.settings(app))
    val backup: StateFlow<com.kaya.health.data.Backup.Settings> = _backup.asStateFlow()
    fun refreshBackup() {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.kaya.health.data.Backup.refreshName(getApplication()) } }
            _backup.value = com.kaya.health.data.Backup.settings(getApplication())
        }
    }

    fun backupTarget(uri: android.net.Uri, name: String?, backUpNow: Boolean = true) {
        com.kaya.health.data.Backup.setTarget(getApplication(), uri, name)
        if (!_backup.value.daily) com.kaya.health.data.Backup.setDaily(getApplication(), true, _backup.value.hour)
        else com.kaya.health.data.Backup.schedule(getApplication())
        refreshBackup()
        if (backUpNow) backupNow()
    }

    fun backupDaily(on: Boolean, hour: Int) {
        com.kaya.health.data.Backup.setDaily(getApplication(), on, hour); refreshBackup()
    }

    fun androidBackup(on: Boolean) {
        com.kaya.health.data.Backup.setAndroidBackup(getApplication(), on); refreshBackup()
    }

    fun backupNow() {
        viewModelScope.launch {
            try {
                val size = com.kaya.health.data.Backup.writeToTarget(getApplication(), repo)
                _snack.value = "Backed up (${sizeText(size)})"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.kaya.health.data.Backup.recordError(getApplication(), e.message ?: e.toString())
                _snack.value = "Backup failed: ${e.message}"
            }
            refreshBackup()
        }
    }

    /** Restores a backup file, then restarts Kaya so the restored settings load. */
    fun restoreBackup(uri: android.net.Uri, relink: Boolean) {
        viewModelScope.launch {
            try {
                val ctx: android.app.Application = getApplication()
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { com.kaya.health.data.Backup.restore(ctx, it) } ?: error("Can't open that file")
                }
                if (relink) com.kaya.health.data.Backup.setTarget(ctx, uri, null)
                android.util.Log.i("KayaBackup", "restored $r")
                val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ctx.startActivity(intent)
                Runtime.getRuntime().exit(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _snack.value = "Restore failed: ${e.message}"
            }
        }
    }

    private var refreshJob: Job? = null

    fun permissionsToRequest(): Set<String> =
        runCatching { repo.requestablePermissions() }.getOrDefault(HealthRepository.PERMISSIONS)

    fun friendly(pkg: String) = HealthRepository.friendlySource(pkg, ownPackage)

    // ------------------------------------------------------------------ refresh

    /** Minutes asleep per night (keyed by wake-up day), last 14 days: Kaya's own nights, else Health Connect's. */
    data class Night(val minutes: Int, val fromOtherApp: Boolean)
    private val _nights = MutableStateFlow<Map<LocalDate, Night>>(emptyMap())
    val nights: StateFlow<Map<LocalDate, Night>> = _nights.asStateFlow()

    fun loadNights() {
        viewModelScope.launch {
            val log = com.kaya.health.data.BodyLog(getApplication())
            val own = log.nights()
            val today = LocalDate.now(zone)
            val out = HashMap<LocalDate, Night>()
            for (d in 0L..13L) {
                val day = today.minusDays(d)
                val mine = own[day]
                if (mine != null) { out[day] = Night(mine + log.morningSleepMin(day), false); continue }
                runCatching { repo.nightAsleepMin(day) }.onFailure { if (it is CancellationException) throw it }
                    .getOrNull()?.let { (min, _) -> out[day] = Night(min, true) }
            }
            _nights.value = out
        }
    }

    fun refreshAll() {
        loadNights()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val status = runCatching { repo.sdkStatus() }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)
            val granted = if (status == HealthConnectClient.SDK_AVAILABLE)
                runCatching { repo.refreshGranted() }.getOrDefault(emptySet()) else emptySet()
            _hc.value = HcState(status, granted)
            if (granted.isEmpty()) {
                _today.update { it.copy(loaded = true, loading = false) }
                return@launch
            }
            // Food and weigh-ins from a restored backup go back into Health Connect (duplicates skipped).
            runCatching { com.kaya.health.data.Backup.importPendingHealth(getApplication(), repo) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()?.let { _snack.value = "Backup restored: $it food and weight entries added back" }
            loadBody()
            loadToday()
            loadFood(_food.value.date)
            loadCheck(_check.value.date)
        }
    }

    private fun weightKg(): Double = _body.value.latestKg ?: _profile.value.fallbackWeightKg

    private fun bmr(): Double {
        val p = _profile.value
        return Calories.bmr(weightKg(), p.heightCm, p.age, p.sex)
    }

    private fun target(): Double? = _body.value.targetKcal
        ?: _profile.value.intakeKcal?.toDouble() ?: _body.value.estimate?.tdee

    private fun row(s: SessionData, typicalHr: Double? = null): SessionRow {
        val p = _profile.value
        val est = Calories.estimate(SessionInput(s.kind, s.durationMin, s.distanceM, s.steps), weightKg(), p.heightCm)
        val hrFormula = s.hrAvg?.let { Calories.heartRateFormulaKcal(it.toDouble(), weightKg(), p.age, p.sex, s.durationMin) }
        // Calories come from the band only: its steps already include any walk another app recorded (Strava, NoiseFit),
        // so adding that app's workout counted the walk twice (3,530 kcal on a tester's day with a 2,800 kcal burn).
        // A workout counts only if you tell Kaya to count it (e.g. a swim the band can't measure).
        val check = if (overrides.get(s.id) == true) SessionRules.judge(s.kind, est.speedKmh != null, s.hrAvg, typicalHr, true)
            else SessionCheck(false, "Kaya counts your band's steps and heart rate only. This came from ${friendly(s.source)}, and any steps in it are already in the band's count. Tap \"It was a real workout\" only if the band couldn't see it (e.g. swimming).", false)
        return SessionRow(s, est, hrFormula, check)
    }

    /** true = count it, false = ignore it, null = let Kaya decide. */
    fun setSessionOverride(id: String, count: Boolean?) {
        overrides.set(id, count)
        viewModelScope.launch {
            loadToday()
            loadCheck(_check.value.date)
            loadBody()
        }
    }

    /**
     * A finished day's calories, the same way as today's but for the whole 24 hours: resting burn, steps outside
     * workouts, counted workouts and digestion. Steps are the band's own count only (never the phone's).
     */
    suspend fun burnFor(date: LocalDate, bandSteps: Long?): Pair<DayBurn, Long>? = runCatching {
        val viaNoiseFit = runCatching { repo.bandStepsViaNoiseFit(date, date.plusDays(1)) }
            .onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyMap())
        val steps = maxOf(bandSteps ?: 0L, bandStepsOn(date, viaNoiseFit))
        val start = date.atStartOfDay(zone).toInstant()
        val sessions = runCatching { repo.sessions(start, date.plusDays(1).atStartOfDay(zone).toInstant()).map { row(it) } }.getOrDefault(emptyList())
        val counted = sessions.filter { it.check.counted }
        val eaten = runCatching { repo.food(date).sumOf { it.kcal } }.getOrDefault(0.0)
        val everyday = max(0L, steps - counted.filter { it.movementMeasured }.sumOf { it.data.steps ?: 0L })
        DayBurn(
            resting = bmr(),
            workouts = counted.sumOf { it.est.activeKcal },
            everydaySteps = Calories.everydayStepsActiveKcal(everyday, weightKg(), _profile.value.heightCm),
            digestion = digestionKcal(eaten),
            walkHeart = runCatching { walkHeart(date).second }.getOrDefault(0.0),
        ) to steps
    }.onFailure { if (it is CancellationException) throw it }.getOrNull()

    /**
     * The band's step count for [date]: what Kaya read from the band, or the band's count as NoiseFit copied it into
     * Health Connect ([viaNoiseFit]), whichever is further along. Never the phone's own pedometer: it sits on a desk
     * (440 vs the band's 1,782 on a tester's day) and Health Connect mixes it in for hours NoiseFit hasn't synced.
     */
    private fun bandStepsOn(date: LocalDate, viaNoiseFit: Map<LocalDate, Long> = emptyMap()): Long =
        maxOf(bandStore.load(date, 0)?.totals?.get("steps") ?: 0L, viaNoiseFit[date] ?: 0L)

    private suspend fun loadToday() {
        _today.update { it.copy(loading = true, error = null) }
        try {
            val date = LocalDate.now(zone)
            val now = Instant.now()
            // Steps and distance come from the band only. The phone's own step counter (via Health Connect) isn't used:
            // it misses steps when the phone sits on a desk and can add some on a bike or car ride.
            val viaNoiseFit = runCatching { repo.bandStepsViaNoiseFit(date, date.plusDays(1)) }
                .onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyMap())
            val act = repo.dayActivity(date).let { a ->
                val band = bandStore.load(date, 0)?.totals
                a.copy(steps = bandStepsOn(date, viaNoiseFit), distanceM = band?.get("distance_m")?.takeIf { it > 0 }?.toDouble())
            }
            val raw = repo.dataCheck(date)
            val sessions = repo.sessions(date.atStartOfDay(zone).toInstant(), now).map { row(it, act.hrAvg?.toDouble()) }
            val counted = sessions.filter { it.check.counted }
            val sleep = repo.sleepEndingOn(date)
            val resting = repo.latestRestingHr()
            val eaten = repo.food(date).sumOf { it.kcal }

            val p = _profile.value
            // Steps inside counted, movement-measured workouts are already in the workout calories.
            val sessionSteps = counted.filter { it.movementMeasured }.sumOf { it.data.steps ?: 0L }
            val everyday = max(0L, act.steps - sessionSteps)
            val minutesSoFar = Duration.between(date.atStartOfDay(zone), ZonedDateTime.now(zone)).toMinutes()
            val walks = runCatching { walkHeart(date) }.getOrDefault(Triple(emptyList(), 0.0, 70.0))
            val burn = DayBurn(
                resting = bmr() * minutesSoFar / 1440.0,
                workouts = counted.sumOf { it.est.activeKcal },
                everydaySteps = Calories.everydayStepsActiveKcal(everyday, weightKg(), p.heightCm),
                digestion = digestionKcal(eaten, minutesSoFar / 1440.0),
                walkHeart = walks.second,
            )
            val kg = weightKg()
            val bmrDay = bmr()
            val stride = Calories.strideM(p.heightCm, running = false)
            val working = buildList {
                add(
                    if (_body.value.latestKg != null) "Weight used: %.1f kg (your latest weigh-in)".format(kg)
                    else "Weight used: %.1f kg (from your profile; log a weigh-in on the Body tab to update it)".format(kg)
                )
                add(
                    "Resting burn per day (Mifflin–St Jeor): 10 × %.1f kg + 6.25 × %.0f cm − 5 × %d years %s = %,.0f kcal"
                        .format(kg, p.heightCm, p.age, if (p.sex == Sex.MALE) "+ 5" else "− 161", bmrDay)
                )
                add("Resting so far: %,.0f × %,d minutes since midnight ÷ 1,440 = %,.0f kcal".format(bmrDay, minutesSoFar, burn.resting))
                add("Everyday steps: %,d today − %,d during workouts = %,d steps".format(act.steps, sessionSteps, everyday))
                add(
                    "   %,d × %.2f m stride = %.2f km × 0.5 kcal per kg per km × %.1f kg = %,.0f kcal"
                        .format(everyday, stride, everyday * stride / 1000, kg, burn.everydaySteps)
                )
                add(
                    "Workouts (extra only): " +
                        (if (counted.isEmpty()) "none counted" else counted.joinToString(" + ") { "%,.0f".format(it.est.activeKcal) }) +
                        " = %,.0f kcal".format(burn.workouts)
                )
                if (burn.walkHeart > 0) {
                    add("Walks, heart rate on top of steps (%.0f%% of the gap to the heart-rate formula, at most +%.0f%%):"
                        .format(com.kaya.health.engine.Walk.HR_TRUST * 100, com.kaya.health.engine.Walk.HR_CAP * 100))
                    walks.first.forEach { w ->
                        val extra = w.heartExtraKcal(kg, p.heightCm, p.age, p.sex, bmrDay, walks.third)
                        if (extra > 0) add("   %s, %,.0f min at avg %d bpm: +%,.0f kcal".format(w.start.toLocalTime().toString().take(5), w.minutes, w.avgHr ?: 0, extra))
                    }
                }
                val ignored = sessions.size - counted.size
                if (ignored > 0) add("   ($ignored workout(s) from other apps not counted: the band's steps already include them. See the workout cards.)")
                add(if (0.10 * eaten >= burn.digestion) "Digesting food: 10%% of %,.0f kcal eaten = %,.0f kcal".format(eaten, burn.digestion)
                    else "Digesting food: at least %,.0f kcal a day even when food isn't logged (10%% of %,.0f kcal logged is less), so far %,.0f kcal"
                        .format(MIN_DIGESTION, eaten, burn.digestion))
                add(
                    "Total: %,.0f + %,.0f + %,.0f + %,.0f + %,.0f = %,.0f kcal"
                        .format(burn.resting, burn.everydaySteps, burn.walkHeart, burn.workouts, burn.digestion, burn.total)
                )
            }
            val allSources = (act.sources + sessions.map { it.data.source } + listOfNotNull(sleep?.source))
            _today.value = TodayUi(
                working = working,
                noiseHrFrom = raw.heartRate.filter { DataCheck.isNoise(it.source) }.minOfOrNull { it.first },
                loading = false,
                loaded = true,
                date = date,
                activity = act,
                restingHr = resting,
                sleep = sleep,
                sessions = sessions,
                burn = burn,
                eatenKcal = eaten,
                targetKcal = target(),
                weightKg = weightKg(),
                weightFromHealthConnect = _body.value.latestKg != null,
                sources = allSources.map(::friendly).distinct().sorted(),
                hasNoiseData = allSources.any { it.contains("noise", ignoreCase = true) },
            )
            if (date == LocalDate.now(zone)) runCatching { com.kaya.health.KayaWidget.saveFood(getApplication(), eaten, target()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _today.update { it.copy(loading = false, loaded = true, error = e.message ?: e.toString()) }
        }
    }

    private suspend fun loadBody() {
        _body.update { it.copy(loading = true, error = null) }
        try {
            val today = LocalDate.now(zone)
            val now = Instant.now()
            val weights = repo.weights(now.minus(Duration.ofDays(120)), now)
            val latest = weights.lastOrNull()?.kg
            _body.update { it.copy(latestKg = latest) } // so the maths below uses it

            val p = _profile.value
            val kg = latest ?: p.fallbackWeightKg
            // Steps per finished day over the last 2 weeks, from the band. More walking → higher burn → earlier date.
            val viaNoiseFit = runCatching { repo.bandStepsViaNoiseFit(today.minusDays(14), today.plusDays(1)) }
                .onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyMap())
            val bandSteps = (1L..14L).associate { d -> today.minusDays(d).let { it to bandStepsOn(it, viaNoiseFit) } }
            val steps = bandSteps.values.filter { it > 0 }
            val avgSteps = if (steps.isEmpty()) 6000.0 else steps.average()
            val formula = Calories.bmr(kg, p.heightCm, p.age, p.sex) * 1.15 +
                Calories.everydayStepsActiveKcal(avgSteps.toLong(), kg, p.heightCm)

            val intake = repo.dailyIntake(today.minusDays(35), today)
            val dayWeights = weights.map { DayWeight(it.time.atZone(zone).toLocalDate(), it.kg) }
            val est = RealBurn.estimate(dayWeights, intake, formula, today)
            val walks = repo.sessions(now.minus(Duration.ofDays(30)), now)
                .filter { it.kind == ActivityKind.WALK || it.kind == ActivityKind.HIKE || it.kind == ActivityKind.RUN }
                .map(::row)

            // The journey starts at the goal's start date: older weigh-ins don't drag today's weight around.
            val goalStart = p.goalStartDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val journey = goalStart?.let { gs -> dayWeights.filter { !it.date.isBefore(gs) } }?.takeIf { it.isNotEmpty() } ?: dayWeights
            val trendNow = RealBurn.trend(journey).lastOrNull()?.trend
            val currentKg = trendNow ?: kg
            val plan = p.goalWeightKg?.let { goalKg ->
                com.kaya.health.engine.GoalPlanner.plan(
                    profile = p,
                    goal = com.kaya.health.engine.GoalSettings(
                        goalWeightKg = goalKg,
                        rateKgPerWeek = p.rateKgPerWeek,
                        startDate = p.goalStartDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today,
                        startWeightKg = p.goalStartWeightKg ?: currentKg,
                    ),
                    currentKg = currentKg,
                    tdee = est.tdee,
                    trendKgPerWeek = est.weeklyChangeKg?.takeIf { est.weighIns >= 4 && est.spanDays >= 7 },
                    avgIntake = est.avgIntake?.takeIf { est.loggedDays >= 3 },
                    today = today,
                )
            }
            val motivation = runCatching {
                val weighDays = dayWeights.map { it.date }.toSet()
                val target = plan?.dailyTarget ?: p.intakeKcal?.toDouble() ?: est.tdee
                val onTarget = intake.filter { it.kcal <= target * 1.05 }.map { it.date }.toSet()
                val todayFood = repo.food(today)
                val stepsByDay = (0L..13L).associate { d -> today.minusDays(d).let { it to bandStepsOn(it, viaNoiseFit) } }
                val before = dayWeights.filter { it.date.isBefore(today) }.minOfOrNull { it.kg }
                val todayKg = dayWeights.filter { it.date == today }.minOfOrNull { it.kg }
                val startKg = p.goalStartWeightKg ?: currentKg
                val dirSign = if ((p.goalWeightKg ?: 0.0) > startKg) 1.0 else -1.0
                val realistic = ((est.weeklyChangeKg ?: 0.0) * dirSign).takeIf { est.weighIns >= 4 && est.spanDays >= 7 && it > 0.05 }
                    ?: plan?.achievableRateKg ?: 0.0
                val cpDate = p.checkpointDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                MotivationUi(
                    weighStreak = com.kaya.health.engine.Motivation.streak(weighDays, today),
                    foodStreak = com.kaya.health.engine.Motivation.streak(onTarget, today),
                    stepsByDay = stepsByDay,
                    proteinToday = todayFood.sumOf { it.proteinG ?: 0.0 },
                    eatenToday = todayFood.sumOf { it.kcal },
                    // Progress towards the goal, in either direction (lost when losing, gained when gaining).
                    lostKg = (if ((p.goalWeightKg ?: 0.0) > startKg) currentKg - startKg else startKg - currentKg).coerceAtLeast(0.0),
                    lowestKg = dayWeights.minOfOrNull { it.kg },
                    newLowToday = (p.goalWeightKg ?: 0.0) <= startKg && todayKg != null && before != null && todayKg < before - 0.05,
                    checkpoint = if (p.checkpointKg != null && cpDate != null) com.kaya.health.engine.Motivation.checkpoint(
                        p.checkpointName ?: "Checkpoint", p.checkpointKg, cpDate, startKg, currentKg, realistic, today,
                        gaining = (p.goalWeightKg ?: 0.0) > startKg,
                    ) else null,
                    realisticKgPerWeek = realistic,
                )
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            _body.value = BodyUi(
                motivation = motivation,
                loading = false,
                trend = RealBurn.trend(dayWeights.filter { !it.date.isBefore(today.minusDays(60)) }),
                recent = weights.takeLast(10).reversed(),
                latestKg = latest,
                estimate = est,
                plan = plan,
                // No goal = maintain: eat about what you burn (or your own number).
                targetKcal = plan?.dailyTarget ?: p.intakeKcal?.toDouble() ?: est.tdee,
                walks = walks.reversed(),
                intake = intake,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _body.update { it.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }

    fun showCheckDay(date: LocalDate) {
        if (date.isAfter(LocalDate.now(zone))) return
        viewModelScope.launch { loadCheck(date) }
    }

    private suspend fun loadCheck(date: LocalDate) {
        if (!_hc.value.connected) return
        _check.update { it.copy(loading = true, date = date, error = null) }
        try {
            val c = repo.dataCheck(date)
            val typical = c.heartRate.filter { DataCheck.isNoise(it.source) }.maxByOrNull { it.samples }?.avg
            val rows = c.sessions.map { row(it, typical) }
            _check.value = CheckUi(loading = false, date = date, check = c, rows = rows)
            if (debuggable) logReport(c, rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _check.update { it.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }

    private val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Debug builds only: writes the Check screen's raw numbers to logcat (tag "KayaCheck") so they can be
     * read over USB with `adb logcat -s KayaCheck`. Only the developer's PC can read this. Other apps can't.
     */
    private fun logReport(c: DataCheck, rows: List<SessionRow>) {
        val p = _profile.value
        val out = mutableListOf<String>()
        out += "===== KAYA REPORT ${c.date} (generated ${ZonedDateTime.now(zone).toLocalTime().withNano(0)}) ====="
        out += "profile: height=%.0fcm age=%d sex=%s weightUsed=%.1fkg (fromHC=%s) deficit=%d"
            .format(p.heightCm, p.age, p.sex, weightKg(), _body.value.latestKg != null, p.deficitKcal)
        out += "granted=${_hc.value.granted.size}/${HealthRepository.PERMISSIONS.size} noPermission=${c.noPermission}"
        out += "lastNoiseData=${c.lastNoiseData?.atZone(zone)?.toLocalDateTime()}"
        out += "steps: kayaUses=${c.stepsTotal} " + c.steps.joinToString { "${friendly(it.source)}=${it.value.toLong()} (${it.records} recs ${it.first.clock()}-${it.last.clock()})" }
        out += "distance: kayaUses=${c.distanceTotalM?.let { "%.0fm".format(it) }} " + c.distance.joinToString { "${friendly(it.source)}=%.0fm (${it.records} recs)".format(it.value) }
        c.heartRate.forEach { out += "hr ${friendly(it.source)}: samples=${it.samples} min=${it.min} avg=%.1f max=${it.max} ${it.first.clock()}-${it.last.clock()}".format(it.avg) }
        c.restingHr.forEach { out += "restingHr ${friendly(it.source)}: latest=${it.value.toLong()} (${it.records} recs)" }
        c.activeKcal.forEach { out += "activeKcal ${friendly(it.source)}=%.0f (${it.records} recs ${it.first.clock()}-${it.last.clock()})".format(it.value) }
        c.totalKcal.forEach { out += "totalKcal ${friendly(it.source)}=%.0f (${it.records} recs)".format(it.value) }
        c.sleep.forEach { out += "sleep ${friendly(it.source)}: ${it.start.atZone(zone).toLocalDateTime()} -> ${it.end.atZone(zone).toLocalDateTime()} stages=${it.stages}" }
        rows.forEach { r ->
            val s = r.data
            out += "session ${s.kind} ${friendly(s.source)} ${s.start.clock()}-${s.end.clock()} %.1fmin dist=${s.distanceM?.let { "%.0f".format(it) }} steps=${s.steps} hrAvg=${s.hrAvg} hrMax=${s.hrMax} reported=${s.reportedKcal?.let { "%.0f".format(it) }} method=${s.recordingLabel}"
                .format(s.durationMin)
            out += "   verdict: counted=${r.check.counted} userDecided=${r.check.userDecided} | ${r.check.reason}"
            out += "   kaya: total=%.0f active=%.0f hrFormula=%s".format(r.est.totalKcal, r.est.activeKcal, r.hrFormulaKcal?.let { "%.0f".format(it) })
            r.est.working.forEach { out += "   | $it" }
        }
        if (c.date == LocalDate.now(zone)) _today.value.working.forEach { out += "today | $it" }
        out += "===== END ====="
        out.forEach { Log.i("KayaCheck", it) }
    }

    fun showFoodDay(date: LocalDate) {
        if (date.isAfter(LocalDate.now(zone))) return
        viewModelScope.launch { loadFood(date) }
    }

    private suspend fun loadFood(date: LocalDate) {
        _food.update { it.copy(loading = true, date = date, error = null) }
        try {
            val entries = repo.food(date)
            _food.update { it.copy(loading = false, entries = entries, targetKcal = target()) }
            // History for Recent and "repeat yesterday": a light read of the previous 14 days.
            val history = (1L..14L).flatMap { d -> runCatching { repo.food(date.minusDays(d)) }.getOrDefault(emptyList()) }
            val previous = history.filter { it.time.atZone(zone).toLocalDate() == date.minusDays(1) }
            val recent = (entries.reversed() + history.sortedByDescending { it.time })
                .map { com.kaya.health.data.FoodDb.fromLogged(it.name, it.kcal, it.proteinG, it.carbsG, it.fatG, it.fiberG) }
                .distinctBy { it.name.lowercase() }.take(30)
            _food.update { it.copy(recent = recent, previousDay = previous) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _food.update { it.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }

    // ------------------------------------------------------------------ actions

    /**
     * Saves profile edits. A changed goal weight (or a first goal) restarts the plan from today's weight.
     * A changed "current weight" is logged as today's weigh-in.
     */
    /** Applies and stores a theme on its own (the rest of Settings keeps any unsaved edits). */
    fun setLook(name: String) { profileStore.saveLook(name) }

    fun saveProfile(p: Profile) {
        val old = _profile.value
        val currentKg = _body.value.latestKg ?: old.fallbackWeightKg
        var next = p
        if (p.goalWeightKg != null && (old.goalWeightKg != p.goalWeightKg || old.goalStartDate == null)) {
            next = next.copy(goalStartDate = LocalDate.now(zone).toString(), goalStartWeightKg = p.fallbackWeightKg.takeIf { it != old.fallbackWeightKg } ?: currentKg)
        }
        if (old.setupDone && kotlin.math.abs(p.fallbackWeightKg - old.fallbackWeightKg) >= 0.05 && _hc.value.connected) {
            viewModelScope.launch { runCatching { repo.addWeight(p.fallbackWeightKg, LocalDate.now(zone)) } }
        }
        // The look is saved on its own by the theme picker; never overwrite it with an older copy.
        val saved = next.copy(setupDone = true, look = profileStore.load().look)
        profileStore.save(saved)
        _profile.value = saved
        refreshAll()
    }

    fun addFood(name: String, kcal: Double, protein: Double?, mealType: Int, carbs: Double? = null, fat: Double? = null, fiber: Double? = null) {
        val date = _food.value.date
        viewModelScope.launch {
            try {
                repo.addFood(name.ifBlank { "Food" }, kcal, protein, mealType, date, carbs, fat, fiber)
                _snack.value = "Added ${name.ifBlank { "food" }} (${kcal.toInt()} kcal)"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _snack.value = "Couldn't save: ${e.message}"
            }
            loadFood(date)
            loadToday()
            loadBody()
        }
    }

    /** Logs several foods at once (e.g. repeating yesterday's lunch), with one refresh at the end. */
    fun addFoods(items: List<FoodEntry>, mealType: Int) {
        val date = _food.value.date
        viewModelScope.launch {
            var ok = 0
            items.forEach { e ->
                runCatching { repo.addFood(e.name, e.kcal, e.proteinG, mealType, date, e.carbsG, e.fatG, e.fiberG); ok++ }
                    .onFailure { if (it is CancellationException) throw it }
            }
            _snack.value = if (ok == items.size) "Added ${items.size} food${if (items.size == 1) "" else "s"} (${items.sumOf { it.kcal }.toInt()} kcal)" else "Saved $ok of ${items.size}"
            loadFood(date); loadToday(); loadBody()
        }
    }

    fun deleteFood(entry: FoodEntry) {
        viewModelScope.launch {
            runCatching { repo.deleteFood(entry.id) }.onFailure { _snack.value = "Couldn't delete: ${it.message}" }
            loadFood(_food.value.date)
            loadToday()
            loadBody()
        }
    }

    fun addFavourite(f: FoodFavourite) {
        val list = (_food.value.favourites.filterNot { it.name.equals(f.name, ignoreCase = true) } + f)
        favStore.save(list)
        _food.update { it.copy(favourites = list) }
    }

    /** Replaces the quick-add list with one of the starter lists. */
    fun useFoodPreset(name: String) {
        val list = FavouritesStore.PRESETS[name] ?: return
        favStore.save(list)
        _food.update { it.copy(favourites = list) }
    }

    fun removeFavourite(f: FoodFavourite) {
        val list = _food.value.favourites - f
        favStore.save(list)
        _food.update { it.copy(favourites = list) }
    }

    fun addWeight(kg: Double) {
        viewModelScope.launch {
            try {
                repo.addWeight(kg, LocalDate.now(zone))
                _snack.value = "Saved %.1f kg".format(kg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _snack.value = "Couldn't save: ${e.message}"
            }
            loadBody()
            loadToday()
            loadFood(_food.value.date)
        }
    }

    fun deleteWeight(entry: WeightEntry) {
        viewModelScope.launch {
            runCatching { repo.deleteWeight(entry.id) }.onFailure { _snack.value = "Couldn't delete: ${it.message}" }
            loadBody()
            loadToday()
        }
    }

    companion object {
        fun mealTypeNow(): Int {
            val h = LocalTime.now().hour
            return when {
                h < 11 -> MealType.MEAL_TYPE_BREAKFAST
                h < 16 -> MealType.MEAL_TYPE_LUNCH
                h < 19 -> MealType.MEAL_TYPE_SNACK
                else -> MealType.MEAL_TYPE_DINNER
            }
        }

        fun mealLabel(type: Int) = when (type) {
            MealType.MEAL_TYPE_BREAKFAST -> "Breakfast"
            MealType.MEAL_TYPE_LUNCH -> "Lunch"
            MealType.MEAL_TYPE_DINNER -> "Dinner"
            MealType.MEAL_TYPE_SNACK -> "Snack"
            else -> "Other"
        }
    }
}
