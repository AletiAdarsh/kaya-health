package com.kaya.health.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kaya.health.data.BodyLog
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private const val KCAL_PER_KG = 7700.0
private val stopDayFmt = DateTimeFormatter.ofPattern("d MMM")

/** The user's own message for this moment, recorded on a good day. Kept in app storage and in backups. */
fun voiceFile(context: android.content.Context) = File(context.filesDir, "voice").apply { mkdirs() }.let { File(it, "why.m4a") }

/** The honest numbers behind "Stop. Look.", all from the user's own plan, intake and weight. Pure maths, testable. */
object RealityMath {
    /** Days a [kcal] surplus pushes the goal back, at the plan's daily deficit. */
    fun daysPushed(kcal: Double, dailyDeficit: Double): Double = if (dailyDeficit <= 0) 0.0 else kcal / dailyDeficit

    /** Weight after [weeks] if every week looks like the average logged day ([avgIntake]) against [tdee]. */
    fun weightAfter(currentKg: Double, avgIntake: Double, tdee: Double, weeks: Int): Double =
        currentKg + (avgIntake - tdee) * 7 * weeks / KCAL_PER_KG
}

@Composable
fun VoiceMessagePanel(autoPlay: Boolean) {
    val context = LocalContext.current
    val file = remember { voiceFile(context) }
    var has by remember { mutableStateOf(file.exists() && file.length() > 0) }
    var recording by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    val recorder = remember { mutableStateOf<MediaRecorder?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }

    fun play() {
        if (!file.exists()) return
        player.value?.release()
        player.value = runCatching {
            MediaPlayer().apply { setDataSource(file.path); setOnCompletionListener { playing = false }; prepare(); start() }
        }.getOrNull()
        playing = player.value != null
    }
    fun stopPlay() { player.value?.runCatching { stop(); release() }; player.value = null; playing = false }
    fun startRec() {
        stopPlay()
        val tmp = File(file.parentFile, "new.m4a")
        recorder.value = runCatching {
            (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000); setAudioSamplingRate(44_100)
                setMaxDuration(60_000)
                setOutputFile(tmp.path)
                prepare(); start()
            }
        }.getOrNull()
        recording = recorder.value != null; seconds = 0
    }
    fun stopRec() {
        recorder.value?.runCatching { stop(); release() }
        recorder.value = null; recording = false
        val tmp = File(file.parentFile, "new.m4a")
        if (tmp.exists() && tmp.length() > 2000) { tmp.copyTo(file, overwrite = true); tmp.delete(); has = true }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startRec() }

    LaunchedEffect(recording) { while (recording) { delay(1000); seconds++; if (seconds >= 60) stopRec() } }
    LaunchedEffect(Unit) { if (autoPlay && has) play() }
    DisposableEffect(Unit) { onDispose { stopPlay(); recorder.value?.runCatching { stop(); release() } } }

    Panel(accent = Area.body) {
        PanelLabel("Your own words", null, Area.body)
        when {
            recording -> {
                Text("Recording… 0:%02d".format(seconds), style = MaterialTheme.typography.titleLarge, color = Area.activity)
                Muted("Say why you're doing this, what you're sick of, and what you want to see on your goal day. Talk to the you who's about to eat.")
                Button(onClick = { stopRec() }, modifier = Modifier.fillMaxWidth()) { Text("Stop and keep it") }
            }
            has -> {
                Text(if (playing) "Playing your message…" else "Your message to yourself", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { if (playing) stopPlay() else play() }, modifier = Modifier.weight(1f)) { Text(if (playing) "Stop" else "Play it again") }
                    OutlinedButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRec()
                        else askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }, modifier = Modifier.weight(1f)) { Text("Record again") }
                }
            }
            else -> {
                Muted("Record a short message (up to a minute) on a day you feel strong: why you started, what you're tired of, what you want. It plays here, in your own voice, every time you open this. Stays on your phone.")
                Button(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRec()
                    else askMic.launch(Manifest.permission.RECORD_AUDIO)
                }, modifier = Modifier.fillMaxWidth()) { Text("Record my message") }
            }
        }
    }
}

/** Latest progress photo next to where you're headed. */
@Composable
fun PhotoVsGoalPanel(vm: AppViewModel) {
    val context = LocalContext.current
    val body by vm.body.collectAsState()
    val profile by vm.profile.collectAsState()
    val photo = remember { BodyLog(context).photos().lastOrNull() }
    val bmp = remember(photo) {
        photo?.let { f -> runCatching { android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull() }
    }
    val plan = body.plan
    val now = body.latestKg
    Panel(accent = Area.body) {
        PanelLabel("You, today", null, Area.body)
        if (bmp != null) {
            Image(bmp.asImageBitmap(), "Your latest progress photo", Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
            Muted(photo!!.nameWithoutExtension.take(10).let { "Photo from $it" })
        } else Muted("Add a progress photo on the Body page and it will show here. Seeing yourself hits harder than any number.")
        if (now != null && plan != null) {
            Text("%.1f kg now → %.0f kg".format(now, plan.goalKg) + " by ${plan.plannedDate.format(stopDayFmt)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val cp = profile.checkpointKg; val cpDate = profile.checkpointDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (cp != null && cpDate != null && cpDate.isAfter(LocalDate.now()))
                Muted("${profile.checkpointName ?: "Checkpoint"}: %.0f kg in ${java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), cpDate)} days.".format(cp))
        }
    }
}

/** What this food, this week, and this pace actually cost, and the other ending. */
@Composable
fun TruthPanel(vm: AppViewModel, foodKcal: Double?) {
    val body by vm.body.collectAsState()
    val today by vm.today.collectAsState()
    val plan = body.plan ?: return
    val now = body.latestKg ?: plan.currentKg
    val target = today.targetKcal ?: body.targetKcal ?: plan.dailyTarget
    val deficit = (plan.tdee - target).coerceAtLeast(1.0)
    // Last 7 days of logged eating (today included as so far).
    val since = LocalDate.now().minusDays(6)
    val days = body.intake.filter { !it.date.isBefore(since) && it.date.isBefore(LocalDate.now()) }.associate { it.date to it.kcal } +
        (LocalDate.now() to today.eatenKcal)
    val logged = days.filterValues { it > 300 }
    val over = logged.values.sum() - target * logged.size
    val avg = logged.values.takeIf { it.isNotEmpty() }?.average()

    Panel(accent = Area.activity) {
        PanelLabel("The truth, in your numbers", null, Area.activity)
        foodKcal?.let { k ->
            val d = RealityMath.daysPushed(k, deficit)
            Text("This ${k.roundToInt()} kcal pushes ${plan.goalKg.roundToInt()} kg back by " +
                (if (d < 1) "${(d * 24).roundToInt()} hours" else "%.1f days".format(d)) + ".", style = MaterialTheme.typography.titleMedium)
        }
        if (logged.size >= 2) {
            if (over > 200) Text("This week you're ${over.roundToInt()} kcal over plan. Your goal has already slid %.1f days.".format(over / deficit),
                style = MaterialTheme.typography.bodyLarge)
            else Text("This week you're on plan${if (over < -200) ", ${(-over).roundToInt()} kcal under" else ""}. Don't hand it back now.", style = MaterialTheme.typography.bodyLarge)
        }
        if (avg != null && logged.size >= 2) {
            val in3 = RealityMath.weightAfter(now, avg, plan.tdee, 13)
            val best = now - (plan.achievableRateKg.takeIf { it > 0 } ?: 0.75) * 13
            Text(
                if (in3 >= now - 0.5) "If every week goes like this one: about %.0f kg in 3 months. Still here. Still this.".format(in3)
                else "If every week goes like this one: about %.0f kg in 3 months, not %.0f.".format(in3, best),
                style = MaterialTheme.typography.bodyLarge, color = Area.activity,
            )
            Text("Keep to plan and it's about %.0f kg in 3 months, and %.0f kg by ${plan.plannedDate.format(stopDayFmt)}.".format(best, plan.goalKg),
                style = MaterialTheme.typography.bodyLarge, color = Area.food)
        }
        Muted("Same body, two endings. You pick one now.")
    }
}
