package com.kaya.health.data

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backups that the user owns. Kaya has no server: nothing leaves the phone unless the user turns it on,
 * and then it only goes where they chose.
 *
 *  - A backup file (zip) the user picks with the system file picker: phone storage, Google Drive, OneDrive,
 *    Dropbox, anything with a documents provider. Kaya rewrites that file once a day at the hour they set.
 *  - Android's own backup (optional): end-to-end encrypted with the screen lock, restored automatically
 *    when Kaya is reinstalled or moved to a new phone. See [KayaBackupAgent].
 *
 * The zip holds Kaya's settings, the band history (the band deletes days once synced, so this is the only
 * copy), and the food and weigh-ins from Health Connect.
 */
object Backup {
    private const val FORMAT = 1
    private const val SETTINGS = "kaya_backup"
    /** Settings files that go into a backup. kaya_backup itself stays out: its file link only works on this install. */
    private val PREFS = listOf("kaya_profile", "kaya_food", "kaya_mind", "kaya_sessions", "kaya_wins", "kaya_band", "kaya_discipline", "kaya_walks", "kaya_reminders", "kaya_bodylog")
    private const val WORK = "kaya-daily-backup"
    private const val PENDING_HEALTH = "restore_pending_health.json"

    data class Settings(
        val uri: Uri?,
        val fileName: String?,
        val daily: Boolean,
        val hour: Int,
        val lastAt: Instant?,
        val lastBytes: Long,
        val lastError: String?,
        val androidBackup: Boolean,
    )

    fun settings(context: Context): Settings {
        val p = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        return Settings(
            uri = p.getString("uri", null)?.let(Uri::parse),
            fileName = p.getString("name", null),
            daily = p.getBoolean("daily", false),
            hour = p.getInt("hour", 23),
            lastAt = p.getLong("lastAt", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            lastBytes = p.getLong("lastBytes", 0),
            lastError = p.getString("lastError", null),
            androidBackup = androidBackupOn(context),
        )
    }

    /** Remembers the picked file (and keeps permission to write to it across reboots). */
    fun setTarget(context: Context, uri: Uri, name: String?) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit {
            putString("uri", uri.toString()); putString("name", displayName(context, uri) ?: name); remove("lastError")
        }
    }

    /** The file's real name ("kaya-backup.zip"), and where it lives (e.g. "Google Drive"). */
    fun displayName(context: Context, uri: Uri): String? {
        val file = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: return null
        val where = when {
            uri.authority?.contains("google.android.apps.docs") == true -> "Google Drive"
            uri.authority?.contains("onedrive") == true -> "OneDrive"
            uri.authority?.contains("dropbox") == true -> "Dropbox"
            uri.authority?.startsWith("com.android.") == true -> "phone storage"
            else -> null
        }
        return if (where != null) "$file · $where" else file
    }

    /** Fixes up the stored name for links made before Kaya looked up real names. */
    fun refreshName(context: Context) {
        val p = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        val uri = p.getString("uri", null)?.let(Uri::parse) ?: return
        displayName(context, uri)?.let { n -> if (n != p.getString("name", null)) p.edit { putString("name", n) } }
    }

    fun setDaily(context: Context, on: Boolean, hour: Int) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit { putBoolean("daily", on); putInt("hour", hour) }
        schedule(context)
    }

    fun setAndroidBackup(context: Context, on: Boolean) {
        // Kept in its own file so the choice itself travels with an Android backup.
        context.getSharedPreferences("kaya_cloud", Context.MODE_PRIVATE).edit { putBoolean("android", on) }
        if (on) android.app.backup.BackupManager(context).dataChanged()
    }

    fun androidBackupOn(context: Context) =
        context.getSharedPreferences("kaya_cloud", Context.MODE_PRIVATE).getBoolean("android", false)

    /** (Re)plans the daily backup at the chosen hour, or cancels it. */
    fun schedule(context: Context) {
        val s = settings(context)
        val wm = WorkManager.getInstance(context)
        if (!s.daily || s.uri == null) { wm.cancelUniqueWork(WORK); return }
        val now = LocalDateTime.now()
        var next = now.withHour(s.hour).truncatedTo(ChronoUnit.HOURS)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, req)
    }

    fun backupNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("kaya-backup-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<BackupWorker>().build())
    }

    /** Builds the zip. */
    suspend fun export(context: Context, repo: HealthRepository?): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put(
                "kaya-backup.json",
                JSONObject().put("format", FORMAT).put("created", Instant.now().toString())
                    .put("app", context.packageName).toString(2).toByteArray(),
            )
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            PREFS.forEach { name -> File(prefsDir, "$name.xml").takeIf { it.exists() }?.let { put("prefs/$name.xml", it.readBytes()) } }
            File(context.filesDir, "band").listFiles()?.sortedBy { it.name }?.forEach { put("band/${it.name}", it.readBytes()) }
            File(context.filesDir, "progress").listFiles()?.sortedBy { it.name }?.forEach { put("progress/${it.name}", it.readBytes()) }
            File(context.filesDir, "voice/why.m4a").takeIf { it.exists() }?.let { put("voice/why.m4a", it.readBytes()) }
            if (repo != null) runCatching {
                val (food, weights) = repo.exportForBackup(Instant.now().minus(Duration.ofDays(3650)), Instant.now().plusSeconds(3600))
                put("health.json", healthJson(food, weights).toString().toByteArray())
            }
        }
        return out.toByteArray()
    }

    /** Writes a backup to the chosen file. Returns the size, or throws with a readable message. */
    suspend fun writeToTarget(context: Context, repo: HealthRepository?): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val s = settings(context)
        val uri = s.uri ?: error("No backup file chosen")
        val bytes = export(context, repo)
        val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: runCatching { context.contentResolver.openOutputStream(uri, "w") }.getOrNull()
            ?: error("Can't write to the backup file. If Kaya was reinstalled, pick the file again.")
        stream.use { it.write(bytes) }
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit {
            putLong("lastAt", System.currentTimeMillis()); putLong("lastBytes", bytes.size.toLong()); remove("lastError")
        }
        bytes.size.toLong()
    }

    fun recordError(context: Context, message: String) =
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit { putString("lastError", message) }

    data class RestoreResult(val settings: Int, val bandFiles: Int, val healthPending: Boolean)

    /**
     * Restores a backup zip. Settings are replaced; band days are merged (a fuller local copy wins);
     * food and weigh-ins are queued and added to Health Connect on the next start, skipping duplicates.
     * The app must restart afterwards so the restored settings load.
     */
    fun restore(context: Context, input: InputStream): RestoreResult {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && !e.name.contains("..")) entries[e.name] = zip.readBytes()
            }
        }
        val head = entries["kaya-backup.json"]?.let { JSONObject(String(it)) } ?: error("That file isn't a Kaya backup.")
        if (head.optInt("format") > FORMAT) error("This backup is from a newer Kaya. Update the app first.")

        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
        var settings = 0
        entries.filterKeys { it.startsWith("prefs/") }.forEach { (name, bytes) ->
            val file = name.removePrefix("prefs/")
            if (file.removeSuffix(".xml") in PREFS) { File(prefsDir, file).writeBytes(bytes); settings++ }
        }
        val photoDir = File(context.filesDir, "progress").apply { mkdirs() }
        entries.filterKeys { it.startsWith("progress/") }.forEach { (name, bytes) ->
            val f = File(photoDir, File(name).name)
            if (!f.exists()) f.writeBytes(bytes)
        }
        entries["voice/why.m4a"]?.let { bytes ->
            val f = File(context.filesDir, "voice").apply { mkdirs() }.let { File(it, "why.m4a") }
            if (!f.exists()) f.writeBytes(bytes)
        }
        val bandDir = File(context.filesDir, "band").apply { mkdirs() }
        var band = 0
        entries.filterKeys { it.startsWith("band/") }.forEach { (name, bytes) ->
            val f = File(bandDir, name.removePrefix("band/"))
            if (!f.exists() || f.length() < bytes.size) { f.writeBytes(bytes); band++ }
        }
        val health = entries["health.json"]
        health?.let { File(context.filesDir, PENDING_HEALTH).writeBytes(it) }
        return RestoreResult(settings, band, health != null)
    }

    /** Adds food and weigh-ins left by a restore once Health Connect is available. Returns how many were added, or null if nothing was waiting. */
    suspend fun importPendingHealth(context: Context, repo: HealthRepository): Int? {
        val f = File(context.filesDir, PENDING_HEALTH)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val food = o.optJSONArray("food")?.let { a ->
            (0 until a.length()).map { i ->
                val x = a.getJSONObject(i)
                fun opt(k: String) = x.optDouble(k).takeIf { !it.isNaN() }
                FoodEntry("", x.getString("name"), x.getDouble("kcal"), opt("protein"),
                    x.getInt("meal"), Instant.parse(x.getString("time")), true, context.packageName, opt("carbs"), opt("fat"), opt("fiber"))
            }
        } ?: emptyList()
        val weights = o.optJSONArray("weights")?.let { a ->
            (0 until a.length()).map { i -> val x = a.getJSONObject(i); WeightEntry("", Instant.parse(x.getString("time")), x.getDouble("kg"), true, context.packageName) }
        } ?: emptyList()
        val added = repo.importFromBackup(food, weights)
        f.delete()
        return added
    }

    private fun healthJson(food: List<FoodEntry>, weights: List<WeightEntry>) = JSONObject()
        .put("food", JSONArray().apply {
            food.forEach { put(JSONObject().put("name", it.name).put("kcal", it.kcal).put("protein", it.proteinG ?: JSONObject.NULL).put("meal", it.mealType).put("time", it.time.toString())
                .put("carbs", it.carbsG ?: JSONObject.NULL).put("fat", it.fatG ?: JSONObject.NULL).put("fiber", it.fiberG ?: JSONObject.NULL)) }
        })
        .put("weights", JSONArray().apply { weights.forEach { put(JSONObject().put("time", it.time.toString()).put("kg", it.kg)) } })
}

/** Runs the daily (or "back up now") backup to the user's chosen file. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repo = runCatching { HealthRepository(applicationContext) }.getOrNull()
        Backup.writeToTarget(applicationContext, repo)
        Result.success()
    } catch (e: Exception) {
        Backup.recordError(applicationContext, e.message ?: e.toString())
        Result.retry().takeIf { runAttemptCount < 2 } ?: Result.failure()
    }
}

/**
 * Android's own backup, only when the user switched it on in Kaya. Android encrypts it with the phone's
 * screen lock before it leaves the device and restores it automatically on reinstall or a new phone.
 */
class KayaBackupAgent : BackupAgent() {
    override fun onFullBackup(data: FullBackupDataOutput) {
        if (Backup.androidBackupOn(this)) super.onFullBackup(data)
    }
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit
}
