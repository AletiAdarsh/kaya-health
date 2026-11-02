package com.kaya.health.band

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Background sync: reads the band every couple of hours and just before midnight, even when Kaya is closed,
 * and saves every day on the phone. The band deletes a day once any app (NoiseFit) confirms it, so this is
 * what makes sure Kaya keeps a full copy of each day.
 */
object BandSync {
    private const val PERIODIC = "kaya-band-sync"
    private const val NIGHTLY = "kaya-band-sync-night"
    private const val PREFS = "kaya_band_sync"

    /** Everything Kaya saves for a day. */
    val METRICS = listOf(
        Commands.METRIC_HEART_RATE, Commands.METRIC_ACTIVITY, Commands.METRIC_SPO2, Commands.METRIC_SKIN_TEMP,
        Commands.METRIC_SLEEP_BREATHING, Commands.METRIC_HRV, Commands.METRIC_STRESS, 23,
    )

    /** Set by the open app: lets a background run use the app's existing connection instead of opening a second one. */
    @Volatile var viaApp: (suspend () -> Int)? = null

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BandSyncWorker>(2, TimeUnit.HOURS, 30, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
        // And once a day at 11:45 pm, so the whole day is saved before NoiseFit can take it overnight.
        val now = LocalDateTime.now()
        var next = now.withHour(23).withMinute(45).truncatedTo(ChronoUnit.MINUTES)
        if (!next.isAfter(now)) next = next.plusDays(1)
        wm.enqueueUniquePeriodicWork(
            NIGHTLY, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BandSyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES).build(),
        )
    }

    fun lastRun(context: Context): Pair<Instant, Int>? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0).takeIf { it > 0 } ?: return null
        return Instant.ofEpochMilli(at) to p.getInt("saved", 0)
    }

    internal fun record(context: Context, saved: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong("at", System.currentTimeMillis()); putInt("saved", saved) }

    /** Which (day, metric) pairs the band still holds, or null if it didn't say. */
    suspend fun index(client: BandClient): Set<Pair<LocalDate, Int>>? =
        client.ask(Commands.dataIndex(), Commands.TYPE_INDEX, timeoutMs = 3_000)?.let { reply ->
            val container = Wire.sub(reply, 9, 2)
            Wire.allBytes(container, 1).mapNotNull { e ->
                val d = Decode.date(Wire.bytes(e, 1)) ?: return@mapNotNull null
                val m = Wire.int(e, 2)?.toInt() ?: return@mapNotNull null
                d.toLocalDate() to m
            }.toSet()
        }

    /** Reads one day's metric from the band and saves it. Only type 113 (request); never 115 (confirm/delete). */
    suspend fun fetchAndSave(client: BandClient, store: BandStore, day: LocalDate, metric: Int): Series? {
        val reply = client.ask(Commands.series(Commands.TYPE_SERIES, day.atStartOfDay(), metric), Commands.TYPE_SERIES, timeoutMs = 3_000, attempts = 3, matches = { r ->
            Decode.status(r) != null || Decode.allSeries(r).any { it.metric == metric && it.start.toLocalDate() == day }
        }) ?: return null
        val s = Decode.allSeries(reply).filter { it.metric == metric }.firstOrNull { it.start.toLocalDate() == day } ?: return null
        if (s.samples.none { it > 0 } && s.hourly.isEmpty()) return null
        store.save(day, metric, s)
        return s
    }
}

class BandSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return Result.success()
        // App open: let it sync over its own connection (two connections to one band would fight).
        BandSync.viaApp?.let { run ->
            val saved = runCatching { run() }.getOrDefault(0)
            BandSync.record(ctx, saved)
            Log.i(BandClient.TAG, "background sync (via app): saved $saved")
            return Result.success()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = BandClient(ctx, scope)
        val store = BandStore(ctx)
        return try {
            val t0 = System.currentTimeMillis()
            if (!client.connect()) return Result.retry()
            val onBand = BandSync.index(client)
            var saved = 0
            for (day in listOf(LocalDate.now(), LocalDate.now().minusDays(1))) for (m in BandSync.METRICS) {
                if (onBand != null && (day to m) !in onBand) continue
                if (BandSync.fetchAndSave(client, store, day, m) != null) saved++
            }
            BandSync.record(ctx, saved)
            runCatching { com.kaya.health.KayaWidget.refresh(ctx) }
            Log.i(BandClient.TAG, "background sync: saved $saved series in ${System.currentTimeMillis() - t0} ms")
            Result.success()
        } catch (e: Exception) {
            Log.i(BandClient.TAG, "background sync failed: ${e.message}")
            Result.retry()
        } finally {
            client.disconnect()
            scope.cancel()
        }
    }
}
