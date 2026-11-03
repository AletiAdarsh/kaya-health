package com.kaya.health.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Optional, opt-in updates from the project's GitHub releases. This is the only thing in Kaya that goes online.
 *
 * What it does, and nothing more:
 *  1. Once a day (only if the user turned it on), ask api.github.com for the latest release of this project. The
 *     request carries no data about the user: no IDs, no health data, no app usage.
 *  2. If that release is newer and the user taps Update, download its APK from GitHub.
 *  3. Refuse it unless it is this same app, a higher version, and signed with the same key as the installed app.
 *  4. Hand it to Android's installer, which shows its own confirmation.
 */
object Updater {
    const val REPO = "AletiAdarsh/kaya-health"
    const val RELEASES_URL = "https://github.com/$REPO/releases"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val TAG = "KayaUpdate"
    private const val CHECK_EVERY_MS = 20L * 60 * 60 * 1000
    private const val ACTION_STATUS = "com.kaya.health.UPDATE_STATUS"

    data class Release(val version: String, val apkUrl: String, val size: Long, val notes: String, val pageUrl: String)

    sealed interface Phase {
        data object Idle : Phase
        data object Checking : Phase
        data object UpToDate : Phase
        data class Downloading(val percent: Int) : Phase
        data object Installing : Phase
        data object NeedsPermission : Phase
        data class Failed(val message: String) : Phase
    }

    data class UiState(val phase: Phase = Phase.Idle, val available: Release? = null, val lastChecked: Long = 0)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(c: Context) = c.getSharedPreferences("kaya_updates", Context.MODE_PRIVATE)

    /** The user has answered the "check for updates online?" question (either way). */
    fun asked(c: Context) = prefs(c).getBoolean("asked", false)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit { putBoolean("asked", true); putBoolean("enabled", on) }
        if (on) check(c, force = true) else _state.update { it.copy(phase = Phase.Idle, available = null) }
    }

    fun currentVersion(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Hide the Home card for this version (Settings still shows it). */
    fun snooze(c: Context, version: String) = prefs(c).edit { putString("snoozed", version) }
    fun snoozed(c: Context, version: String) = prefs(c).getString("snoozed", null) == version

    /** Loads the last result, then checks again if it's been a day. Call when the app opens. */
    fun checkIfDue(c: Context) {
        val p = prefs(c)
        val cached = p.getString("release", null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
            ?.takeIf { Versions.isNewer(it.version, currentVersion(c)) }
        _state.update { it.copy(available = cached, lastChecked = p.getLong("lastCheck", 0)) }
        if (enabled(c) && System.currentTimeMillis() - p.getLong("lastCheck", 0) > CHECK_EVERY_MS) check(c)
    }

    fun check(c: Context, force: Boolean = false) {
        if (!enabled(c) && !force) return
        val app = c.applicationContext
        scope.launch {
            _state.update { it.copy(phase = Phase.Checking) }
            try {
                val json = get(API)
                val rel = parse(JSONObject(json))
                val now = System.currentTimeMillis()
                prefs(app).edit { putLong("lastCheck", now); putString("release", json.let { JSONObject(it) }.let(::slim).toString()) }
                val newer = rel.takeIf { Versions.isNewer(it.version, currentVersion(app)) }
                _state.update { it.copy(phase = if (newer == null) Phase.UpToDate else Phase.Idle, available = newer, lastChecked = now) }
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                _state.update { it.copy(phase = Phase.Failed("Couldn't reach GitHub. Check your connection and try again.")) }
            }
        }
    }

    /** Downloads, verifies and installs [rel]. Asks for "install unknown apps" first if Kaya doesn't have it yet. */
    fun update(c: Context, rel: Release) {
        val app = c.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(phase = Phase.NeedsPermission) }
            runCatching {
                c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        scope.launch {
            try {
                val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val apk = File(dir, "Kaya-${rel.version}.apk")
                download(rel, apk)
                verify(app, apk)?.let { problem -> apk.delete(); throw IllegalStateException(problem) }
                _state.update { it.copy(phase = Phase.Installing) }
                install(app, apk)
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                _state.update { it.copy(phase = Phase.Failed(e.message ?: "Update failed. Try again, or download it from GitHub.")) }
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private fun parse(j: JSONObject): Release {
        val assets = j.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
            ?: error("The latest release has no APK")
        return Release(
            version = j.getString("tag_name").removePrefix("v"),
            apkUrl = apk.getString("browser_download_url"),
            size = apk.optLong("size"),
            notes = j.optString("body"),
            pageUrl = j.optString("html_url", RELEASES_URL),
        )
    }

    /** Keeps only what [parse] needs, for the cached copy. */
    private fun slim(j: JSONObject) = JSONObject()
        .put("tag_name", j.getString("tag_name")).put("body", j.optString("body")).put("html_url", j.optString("html_url"))
        .put("assets", j.getJSONArray("assets"))

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Kaya-Android-updater")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error("GitHub answered ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun download(rel: Release, to: File) {
        val c = open(rel.apkUrl).apply { setRequestProperty("Accept", "application/octet-stream") }
        try {
            if (c.responseCode !in 200..299) error("Download failed (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: rel.size
            var done = 0L; var lastPct = -1
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val pct = if (total > 0) (100 * done / total).toInt() else 0
                        if (pct != lastPct) { lastPct = pct; _state.update { it.copy(phase = Phase.Downloading(pct)) } }
                    }
                }
            }
            if (rel.size > 0 && to.length() != rel.size) error("The download was incomplete. Try again.")
        } finally { c.disconnect() }
    }

    /** Null if [apk] is a newer build of this app signed with the same key; otherwise why not. */
    private fun verify(c: Context, apk: File): String? {
        val pm = c.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val new = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The download isn't a valid app file."
        if (new.packageName != c.packageName) return "The download is a different app, so it wasn't installed."
        val mine = pm.getPackageInfo(c.packageName, flags)
        if (new.longVersionCode <= mine.longVersionCode) return "The download isn't newer than the version you have."
        fun certs(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners.orEmpty()
            .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        val a = certs(new); val b = certs(mine)
        if (a.isEmpty() || a != b) return "The download isn't signed by Kaya's developer, so it wasn't installed."
        return null
    }

    private fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            apk.inputStream().use { input -> s.openWrite("kaya.apk", 0, apk.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            val pi = PendingIntent.getBroadcast(
                c, id, Intent(c, UpdateReceiver::class.java).setAction(ACTION_STATUS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pi.intentSender)
        }
    }

    internal fun onStatus(c: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { c.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.update { it.copy(phase = Phase.Idle, available = null) }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "install status $status: $msg")
                _state.update {
                    it.copy(phase = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) Phase.Idle
                        else Phase.Failed("Android couldn't install the update${msg?.let { m -> " ($m)" } ?: ""}."))
                }
            }
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onStatus(context, intent)
}
