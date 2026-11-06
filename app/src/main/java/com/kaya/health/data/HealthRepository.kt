package com.kaya.health.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.kaya.health.engine.ActivityKind
import com.kaya.health.engine.DayIntake
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

data class DayActivity(
    val steps: Long,
    val distanceM: Double?,
    val hrMin: Long?,
    val hrAvg: Long?,
    val hrMax: Long?,
    /** What other apps (e.g. NoiseFit) wrote as "active calories" for the day. */
    val reportedActiveKcal: Double?,
    val sources: Set<String>,
)

data class SessionData(
    val id: String,
    val start: Instant,
    val end: Instant,
    val kind: ActivityKind,
    val title: String?,
    val source: String,
    val distanceM: Double?,
    val steps: Long?,
    val hrAvg: Long?,
    val hrMax: Long?,
    /** Calories the app that recorded this session reported for it (active if available, else total). */
    val reportedKcal: Double?,
    /** Metadata.RECORDING_METHOD_*: auto-detected vs started by the user. */
    val recordingMethod: Int,
) {
    val autoDetected: Boolean get() = recordingMethod == Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED
    val recordingLabel: String
        get() = when (recordingMethod) {
            Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> "auto-detected"
            Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> "started by you"
            Metadata.RECORDING_METHOD_MANUAL_ENTRY -> "entered by hand"
            else -> "recording method not stated"
        }

    val durationMin: Double get() = Duration.between(start, end).seconds / 60.0
}

data class SleepData(
    val start: Instant,
    val end: Instant,
    val asleepMin: Long,
    val deepMin: Long?,
    val remMin: Long?,
    val lightMin: Long?,
    val awakeMin: Long?,
    val source: String,
    /** Each sleep period (night sleep and naps), earliest first. */
    val periods: List<Pair<Instant, Instant>> = emptyList(),
)

data class FoodEntry(
    val id: String,
    val name: String,
    val kcal: Double,
    val proteinG: Double?,
    val mealType: Int,
    val time: Instant,
    val own: Boolean,
    val source: String,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val fiberG: Double? = null,
)

data class WeightEntry(val id: String, val time: Instant, val kg: Double, val own: Boolean, val source: String)

/** What one app wrote into Health Connect for one data type on one day. */
data class SourceStat(val source: String, val records: Int, val value: Double, val first: Instant, val last: Instant)

data class HrSourceStat(
    val source: String, val samples: Int, val min: Long, val avg: Double, val max: Long, val first: Instant, val last: Instant,
)

data class SleepRaw(val source: String, val start: Instant, val end: Instant, val stages: Int)

/** Raw view of a day, broken down by the app that wrote each piece. Used by the Check screen. */
data class DataCheck(
    val date: LocalDate,
    val stepsTotal: Long?,
    val steps: List<SourceStat>,
    val distanceTotalM: Double?,
    val distance: List<SourceStat>,
    val heartRate: List<HrSourceStat>,
    val restingHr: List<SourceStat>,
    val activeKcal: List<SourceStat>,
    val totalKcal: List<SourceStat>,
    val sessions: List<SessionData>,
    val sleep: List<SleepRaw>,
    /** Data types Kaya has no permission to read. */
    val noPermission: List<String>,
) {
    /** Most recent moment any NoiseFit record covers: roughly "when NoiseFit last synced". */
    val lastNoiseData: Instant?
        get() = (steps + distance + restingHr + activeKcal + totalKcal).filter { isNoise(it.source) }.map { it.last }
            .plus(heartRate.filter { isNoise(it.source) }.map { it.last })
            .plus(sessions.filter { isNoise(it.source) }.map { it.end })
            .maxOrNull()

    companion object {
        fun isNoise(pkg: String) = pkg.contains("noise", ignoreCase = true)
    }
}

/** NoiseFit's app id: the only app whose Health Connect steps Kaya accepts (they're the band's count). */
const val NOISEFIT_PACKAGE = "com.noisefit"

/**
 * Everything Kaya knows about your body comes through Health Connect, Android's shared on-device
 * health database. Other apps (NoiseFit, Samsung Health, Strava…) write into it, and Kaya reads with your permission.
 * Kaya writes the weight and food entries you add, so other apps can use them too.
 */
class HealthRepository(private val context: Context) {

    val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private var granted: Set<String> = emptySet()

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context, PROVIDER)

    suspend fun refreshGranted(): Set<String> =
        client.permissionController.getGrantedPermissions().also { granted = it }

    /** Core permissions, plus "read older than 30 days" where the phone supports it. */
    fun requestablePermissions(): Set<String> {
        val history = runCatching {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)
        return if (history) PERMISSIONS + HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY else PERMISSIONS
    }

    private fun canRead(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in granted

    private fun dayRange(date: LocalDate) =
        TimeRangeFilter.between(date.atStartOfDay(), date.plusDays(1).atStartOfDay())

    /**
     * Where Kaya takes data from: what you entered in Kaya, and NoiseFit (which copies the band). Other apps (Strava,
     * Fitbit, the phone's own pedometer) are left out: they double-count walks the band already saw, or count steps
     * the band didn't take.
     */
    private val trusted = setOf(DataOrigin(context.packageName), DataOrigin(NOISEFIT_PACKAGE))

    /** [everyone] = true only for the Data check screen, which shows what every app wrote. */
    private suspend fun <T : Record> readAll(type: KClass<T>, filter: TimeRangeFilter, everyone: Boolean = false): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(recordType = type, timeRangeFilter = filter, pageToken = token,
                dataOriginFilter = if (everyone) emptySet() else trusted))
            out += resp.records
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    // ---------------------------------------------------------------- activity

    suspend fun dayActivity(date: LocalDate): DayActivity {
        val metrics = buildSet<AggregateMetric<*>> {
            if (canRead(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (canRead(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (canRead(HeartRateRecord::class)) {
                add(HeartRateRecord.BPM_MIN); add(HeartRateRecord.BPM_AVG); add(HeartRateRecord.BPM_MAX)
            }
            if (canRead(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
        }
        if (metrics.isEmpty()) return DayActivity(0, null, null, null, null, null, emptySet())
        val r = client.aggregate(AggregateRequest(metrics = metrics, timeRangeFilter = dayRange(date), dataOriginFilter = trusted))
        return DayActivity(
            steps = r[StepsRecord.COUNT_TOTAL] ?: 0L,
            distanceM = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
            hrMin = r[HeartRateRecord.BPM_MIN],
            hrAvg = r[HeartRateRecord.BPM_AVG],
            hrMax = r[HeartRateRecord.BPM_MAX],
            reportedActiveKcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            sources = r.dataOrigins.map { it.packageName }.toSet(),
        )
    }

    /**
     * Steps per day that NoiseFit wrote to Health Connect: the band's own count, copied by NoiseFit. The phone's
     * pedometer and apps that copy it (Fitbit) are left out; Kaya counts only the band.
     */
    suspend fun bandStepsViaNoiseFit(from: LocalDate, toExclusive: LocalDate): Map<LocalDate, Long> =
        dailySteps(from, toExclusive, setOf(DataOrigin(NOISEFIT_PACKAGE))).toMap()

    suspend fun dailySteps(from: LocalDate, toExclusive: LocalDate, origins: Set<DataOrigin> = emptySet()): List<Pair<LocalDate, Long>> {
        if (!canRead(StepsRecord::class)) return emptyList()
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), toExclusive.atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
                dataOriginFilter = origins,
            )
        )
        return groups.map { it.startTime.toLocalDate() to (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) }
    }

    suspend fun latestRestingHr(): Long? {
        if (!canRead(RestingHeartRateRecord::class)) return null
        val now = Instant.now()
        return readAll(RestingHeartRateRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(7)), now))
            .maxByOrNull { it.time }?.beatsPerMinute
    }

    suspend fun sessions(from: Instant, to: Instant): List<SessionData> {
        if (!canRead(ExerciseSessionRecord::class)) return emptyList()
        val records = readAll(ExerciseSessionRecord::class, TimeRangeFilter.between(from, to))
        return records.map { s ->
            val window = TimeRangeFilter.between(s.startTime, s.endTime)
            val metrics = buildSet<AggregateMetric<*>> {
                if (canRead(HeartRateRecord::class)) { add(HeartRateRecord.BPM_AVG); add(HeartRateRecord.BPM_MAX) }
                if (canRead(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
                if (canRead(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            }
            val r = if (metrics.isEmpty()) null
            else client.aggregate(AggregateRequest(metrics = metrics, timeRangeFilter = window, dataOriginFilter = trusted))
            val reported = recordedCalories(s.metadata.dataOrigin.packageName, window)
            SessionData(
                recordingMethod = s.metadata.recordingMethod,
                id = s.metadata.id,
                start = s.startTime,
                end = s.endTime,
                kind = kindFor(s.exerciseType),
                title = s.title,
                source = s.metadata.dataOrigin.packageName,
                distanceM = r?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
                steps = r?.get(StepsRecord.COUNT_TOTAL),
                hrAvg = r?.get(HeartRateRecord.BPM_AVG),
                hrMax = r?.get(HeartRateRecord.BPM_MAX),
                reportedKcal = reported,
            )
        }.sortedBy { it.start }
    }

    private fun sleepWindow(date: LocalDate) =
        TimeRangeFilter.between(date.minusDays(1).atTime(18, 0), date.plusDays(1).atStartOfDay())

    /**
     * Calories that [pkg] itself recorded in [window], read from raw records. Aggregates aren't used
     * because Health Connect fills gaps in "total calories" with an estimated resting rate, which would
     * make it look as if the app had reported a number.
     */
    private suspend fun recordedCalories(pkg: String, window: TimeRangeFilter): Double? {
        val origin = setOf(DataOrigin(pkg))
        if (canRead(ActiveCaloriesBurnedRecord::class)) {
            val recs = client.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, window, dataOriginFilter = origin)).records
            if (recs.isNotEmpty()) return recs.sumOf { it.energy.inKilocalories }
        }
        if (canRead(TotalCaloriesBurnedRecord::class)) {
            val recs = client.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, window, dataOriginFilter = origin)).records
            if (recs.isNotEmpty()) return recs.sumOf { it.energy.inKilocalories }
        }
        return null
    }

    /**
     * Minutes asleep for the main night ending on [date] (6 pm the day before to 2 pm), from whichever app wrote the most
     * sleep to Health Connect. Naps later in the day are left out. Used to fill nights Kaya didn't record itself.
     */
    suspend fun nightAsleepMin(date: LocalDate): Pair<Int, String>? {
        if (!canRead(SleepSessionRecord::class)) return null
        val recs = readAll(SleepSessionRecord::class, TimeRangeFilter.between(date.minusDays(1).atTime(18, 0), date.atTime(14, 0)))
        if (recs.isEmpty()) return null
        val (src, chosen) = recs.groupBy { it.metadata.dataOrigin.packageName }
            .maxBy { (_, v) -> v.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() } }
        var awake = 0L
        for (s in chosen) for (st in s.stages) when (st.stage) {
            SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED ->
                awake += Duration.between(st.startTime, st.endTime).toMinutes()
        }
        val total = chosen.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
        return ((total - awake).toInt().takeIf { it > 0 } ?: return null) to src
    }

    /** Sleep sessions any app (e.g. NoiseFit) wrote to Health Connect between [from] and [to], in local time. */
    suspend fun sleepSessions(from: java.time.LocalDateTime, to: java.time.LocalDateTime): List<Pair<java.time.LocalDateTime, java.time.LocalDateTime>> {
        if (!canRead(SleepSessionRecord::class)) return emptyList()
        val zone = java.time.ZoneId.systemDefault()
        return readAll(SleepSessionRecord::class, TimeRangeFilter.between(from, to))
            .map { java.time.LocalDateTime.ofInstant(it.startTime, zone) to java.time.LocalDateTime.ofInstant(it.endTime, zone) }
    }

    /** Sleep belonging to [date]: the night before (from 6 pm) plus any naps during the day. */
    suspend fun sleepEndingOn(date: LocalDate): SleepData? {
        if (!canRead(SleepSessionRecord::class)) return null
        val recs = readAll(SleepSessionRecord::class, sleepWindow(date))
        if (recs.isEmpty()) return null
        // Several apps may record the same night. Keep the source with the most sleep.
        val (src, chosen) = recs.groupBy { it.metadata.dataOrigin.packageName }
            .maxBy { (_, v) -> v.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() } }
        var deep = 0L; var rem = 0L; var light = 0L; var awake = 0L; var hasStages = false
        for (s in chosen) for (st in s.stages) {
            hasStages = true
            val m = Duration.between(st.startTime, st.endTime).toMinutes()
            when (st.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> deep += m
                SleepSessionRecord.STAGE_TYPE_REM -> rem += m
                SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> light += m
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> awake += m
            }
        }
        val total = chosen.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
        return SleepData(
            start = chosen.minOf { it.startTime },
            end = chosen.maxOf { it.endTime },
            asleepMin = total - awake,
            deepMin = deep.takeIf { hasStages },
            remMin = rem.takeIf { hasStages },
            lightMin = light.takeIf { hasStages },
            awakeMin = awake.takeIf { hasStages },
            source = src,
            periods = chosen.sortedBy { it.startTime }.map { it.startTime to it.endTime },
        )
    }

    // ---------------------------------------------------------------- data check

    suspend fun dataCheck(date: LocalDate): DataCheck {
        val range = dayRange(date)
        val noPermission = mutableListOf<String>()

        suspend fun <T : Record> raw(type: KClass<T>, label: String, filter: TimeRangeFilter = range): List<T> =
            if (canRead(type)) readAll(type, filter, everyone = true) else { noPermission += label; emptyList() }

        fun <T : Record> bySource(records: List<T>, start: (T) -> Instant, end: (T) -> Instant, value: (T) -> Double) =
            records.groupBy { it.metadata.dataOrigin.packageName }.map { (src, v) ->
                SourceStat(src, v.size, v.sumOf(value), v.minOf(start), v.maxOf(end))
            }.sortedByDescending { it.value }

        val steps = bySource(raw(StepsRecord::class, "Steps"), { it.startTime }, { it.endTime }) { it.count.toDouble() }
        val distance = bySource(raw(DistanceRecord::class, "Distance"), { it.startTime }, { it.endTime }) { it.distance.inMeters }
        val active = bySource(raw(ActiveCaloriesBurnedRecord::class, "Active calories"), { it.startTime }, { it.endTime }) { it.energy.inKilocalories }
        val total = bySource(raw(TotalCaloriesBurnedRecord::class, "Total calories"), { it.startTime }, { it.endTime }) { it.energy.inKilocalories }
        // For resting HR, "value" is the latest reading rather than a sum.
        val resting = raw(RestingHeartRateRecord::class, "Resting heart rate")
            .groupBy { it.metadata.dataOrigin.packageName }
            .map { (src, v) -> SourceStat(src, v.size, v.maxBy { it.time }.beatsPerMinute.toDouble(), v.minOf { it.time }, v.maxOf { it.time }) }

        val hr = raw(HeartRateRecord::class, "Heart rate")
            .groupBy { it.metadata.dataOrigin.packageName }
            .mapNotNull { (src, recs) ->
                val samples = recs.flatMap { it.samples }
                if (samples.isEmpty()) null
                else HrSourceStat(
                    src, samples.size, samples.minOf { it.beatsPerMinute }, samples.map { it.beatsPerMinute }.average(),
                    samples.maxOf { it.beatsPerMinute }, samples.minOf { it.time }, samples.maxOf { it.time },
                )
            }

        val sleep = raw(SleepSessionRecord::class, "Sleep", sleepWindow(date))
            .map { SleepRaw(it.metadata.dataOrigin.packageName, it.startTime, it.endTime, it.stages.size) }
            .sortedBy { it.start }

        val sessions = if (canRead(ExerciseSessionRecord::class)) {
            sessions(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        } else { noPermission += "Workouts"; emptyList() }

        val agg = dayActivity(date)
        return DataCheck(
            date = date,
            stepsTotal = agg.steps.takeIf { canRead(StepsRecord::class) },
            steps = steps,
            distanceTotalM = agg.distanceM,
            distance = distance,
            heartRate = hr,
            restingHr = resting,
            activeKcal = active,
            totalKcal = total,
            sessions = sessions,
            sleep = sleep,
            noPermission = noPermission,
        )
    }

    // ---------------------------------------------------------------- food

    suspend fun food(date: LocalDate): List<FoodEntry> {
        if (!canRead(NutritionRecord::class)) return emptyList()
        return readAll(NutritionRecord::class, dayRange(date)).map { n ->
            val pkg = n.metadata.dataOrigin.packageName
            FoodEntry(
                id = n.metadata.id,
                name = n.name ?: "Food",
                kcal = n.energy?.inKilocalories ?: 0.0,
                proteinG = n.protein?.inGrams,
                mealType = n.mealType,
                time = n.startTime,
                own = pkg == context.packageName,
                source = pkg,
                carbsG = n.totalCarbohydrate?.inGrams,
                fatG = n.totalFat?.inGrams,
                fiberG = n.dietaryFiber?.inGrams,
            )
        }.sortedBy { it.time }
    }

    suspend fun addFood(
        name: String, kcal: Double, proteinG: Double?, mealType: Int, date: LocalDate,
        carbsG: Double? = null, fatG: Double? = null, fiberG: Double? = null,
    ) {
        val time = timeOn(date)
        val off = zone.rules.getOffset(time)
        client.insertRecords(
            listOf(
                NutritionRecord(
                    startTime = time,
                    startZoneOffset = off,
                    endTime = time.plusSeconds(60),
                    endZoneOffset = off,
                    metadata = Metadata.manualEntry(),
                    name = name,
                    energy = Energy.kilocalories(kcal),
                    protein = proteinG?.let { Mass.grams(it) },
                    totalCarbohydrate = carbsG?.let { Mass.grams(it) },
                    totalFat = fatG?.let { Mass.grams(it) },
                    dietaryFiber = fiberG?.let { Mass.grams(it) },
                    mealType = mealType,
                )
            )
        )
    }

    suspend fun deleteFood(id: String) =
        client.deleteRecords(NutritionRecord::class, recordIdsList = listOf(id), clientRecordIdsList = emptyList())

    /** Daily kcal totals for [from, toExclusive). Days with nothing logged are left out. */
    suspend fun dailyIntake(from: LocalDate, toExclusive: LocalDate): List<DayIntake> {
        if (!canRead(NutritionRecord::class)) return emptyList()
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(NutritionRecord.ENERGY_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), toExclusive.atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
                dataOriginFilter = trusted,
            )
        )
        return groups.mapNotNull { g ->
            g.result[NutritionRecord.ENERGY_TOTAL]?.inKilocalories?.takeIf { it > 0 }
                ?.let { DayIntake(g.startTime.toLocalDate(), it) }
        }
    }

    // ---------------------------------------------------------------- weight

    suspend fun weights(from: Instant, to: Instant): List<WeightEntry> {
        if (!canRead(WeightRecord::class)) return emptyList()
        return readAll(WeightRecord::class, TimeRangeFilter.between(from, to)).map { w ->
            val pkg = w.metadata.dataOrigin.packageName
            WeightEntry(w.metadata.id, w.time, w.weight.inKilograms, pkg == context.packageName, pkg)
        }.sortedBy { it.time }
    }

    suspend fun addWeight(kg: Double, date: LocalDate) {
        val time = timeOn(date)
        client.insertRecords(
            listOf(WeightRecord(time = time, zoneOffset = zone.rules.getOffset(time), weight = Mass.kilograms(kg), metadata = Metadata.manualEntry()))
        )
    }

    suspend fun deleteWeight(id: String) =
        client.deleteRecords(WeightRecord::class, recordIdsList = listOf(id), clientRecordIdsList = emptyList())

    // ---------------------------------------------------------------- heart-rate fallback

    /**
     * Every heart-rate sample other apps (NoiseFit) saved in Health Connect for [date], as (time, bpm).
     * Used only to fill a day the band has already deleted and Kaya never saved.
     */
    suspend fun heartRateSamples(date: LocalDate): List<Pair<java.time.LocalDateTime, Int>> {
        if (!canRead(HeartRateRecord::class)) return emptyList()
        return readAll(HeartRateRecord::class, dayRange(date)).flatMap { r ->
            r.samples.map { it.time.atZone(zone).toLocalDateTime() to it.beatsPerMinute.toInt() }
        }.filter { it.first.toLocalDate() == date && it.second in 30..220 }.sortedBy { it.first }
    }

    // ---------------------------------------------------------------- backup

    /** Food Kaya logged and every weigh-in, for the backup file. */
    suspend fun exportForBackup(from: Instant, to: Instant): Pair<List<FoodEntry>, List<WeightEntry>> {
        val range = TimeRangeFilter.between(from, to)
        val food = if (canRead(NutritionRecord::class)) readAll(NutritionRecord::class, range)
            .filter { it.metadata.dataOrigin.packageName == context.packageName }
            .map { n ->
                FoodEntry(
                    n.metadata.id, n.name ?: "Food", n.energy?.inKilocalories ?: 0.0, n.protein?.inGrams, n.mealType, n.startTime, true, context.packageName,
                    n.totalCarbohydrate?.inGrams, n.totalFat?.inGrams, n.dietaryFiber?.inGrams,
                )
            }
        else emptyList()
        return food to weights(from, to)
    }

    /** Puts back food and weigh-ins from a backup, skipping any already in Health Connect (same time and value). Returns how many were added. */
    suspend fun importFromBackup(food: List<FoodEntry>, weights: List<WeightEntry>): Int {
        val all = food.map { it.time } + weights.map { it.time }
        if (all.isEmpty()) return 0
        val range = TimeRangeFilter.between(all.min().minusSeconds(60), all.max().plusSeconds(120))
        val haveFood = readAll(NutritionRecord::class, range).map { it.startTime.epochSecond to Math.round(it.energy?.inKilocalories ?: 0.0) }.toSet()
        val haveWeight = readAll(WeightRecord::class, range).map { it.time.epochSecond to Math.round(it.weight.inKilograms * 10) }.toSet()
        val newFood = food.filter { (it.time.epochSecond to Math.round(it.kcal)) !in haveFood }.map { f ->
            val off = zone.rules.getOffset(f.time)
            NutritionRecord(
                startTime = f.time, startZoneOffset = off, endTime = f.time.plusSeconds(60), endZoneOffset = off,
                metadata = Metadata.manualEntry(), name = f.name, energy = Energy.kilocalories(f.kcal),
                protein = f.proteinG?.let { Mass.grams(it) }, mealType = f.mealType,
                totalCarbohydrate = f.carbsG?.let { Mass.grams(it) }, totalFat = f.fatG?.let { Mass.grams(it) },
                dietaryFiber = f.fiberG?.let { Mass.grams(it) },
            )
        }
        val newWeights = weights.filter { (it.time.epochSecond to Math.round(it.kg * 10)) !in haveWeight }.map { w ->
            WeightRecord(time = w.time, zoneOffset = zone.rules.getOffset(w.time), weight = Mass.kilograms(w.kg), metadata = Metadata.manualEntry())
        }
        (newFood + newWeights).chunked(500).forEach { client.insertRecords(it) }
        return newFood.size + newWeights.size
    }

    /** Now if [date] is today, otherwise the same clock time on that day. */
    private fun timeOn(date: LocalDate): Instant {
        val today = LocalDate.now(zone)
        return if (date == today) Instant.now()
        else date.atTime(LocalTime.now(zone).withNano(0)).atZone(zone).toInstant()
    }

    companion object {
        const val PROVIDER = "com.google.android.apps.healthdata"

        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getWritePermission(WeightRecord::class),
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getWritePermission(NutritionRecord::class),
        )

        fun kindFor(type: Int): ActivityKind = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> ActivityKind.WALK
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> ActivityKind.RUN
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> ActivityKind.HIKE
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> ActivityKind.CYCLE
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> ActivityKind.STRENGTH
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> ActivityKind.YOGA
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> ActivityKind.CARDIO_MACHINE
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> ActivityKind.SWIM
            else -> ActivityKind.OTHER
        }

        fun friendlySource(pkg: String, ownPackage: String): String = when {
            pkg == ownPackage -> "Kaya"
            pkg.contains("noise", ignoreCase = true) -> "NoiseFit"
            pkg == "com.google.android.apps.fitness" -> "Google Fit"
            pkg == "com.sec.android.app.shealth" -> "Samsung Health"
            pkg == "com.strava" -> "Strava"
            pkg == "com.fitbit.FitbitMobile" -> "Fitbit"
            pkg == PROVIDER || pkg == "android" || pkg.startsWith("com.android.healthconnect") ||
                pkg.startsWith("com.google.android.healthconnect") -> "This phone"
            else -> pkg
        }
    }
}
