package com.kaya.health.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import com.kaya.health.engine.ActivityKind
import kotlin.math.roundToInt

@Composable
fun TodayScreen(vm: AppViewModel, onConnect: () -> Unit, onOpenProfile: () -> Unit = {}) {
    val hc by vm.hc.collectAsState()
    val ui by vm.today.collectAsState()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text("Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Muted(ui.date.format(fullDayFmt))
                }
                if (ui.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else IconButton(onClick = vm::refreshAll) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                IconButton(onClick = onOpenProfile) { Icon(Icons.Filled.Settings, contentDescription = "Profile and settings") }
            }
        }

        if (!hc.connected) item { ConnectCard(hc, onConnect) }
        ui.error?.let { item { KCard { CardTitle("Something went wrong"); Muted(it) } } }

        if (hc.connected) {
            ui.burn?.let { burn -> item { BurnCard(ui, burn) } }
            item { MovementCard(ui) }
            item { HeartCard(ui) }
            item { SleepCard(ui) }
            item { Text("Workouts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            if (ui.sessions.isEmpty()) item { KCard { Muted("No workouts recorded today. Use Start walk on the Activity page, or log one in another app that shares with Health Connect.") } }
            items(ui.sessions, key = { it.data.id }) { WorkoutCard(it, vm) }
            if (ui.sources.isNotEmpty()) item { Muted("Data from: " + ui.sources.joinToString(", ")) }
        }
    }
}

@Composable
fun ConnectCard(hc: HcState, onConnect: () -> Unit) {
    val context = LocalContext.current
    KCard {
        CardTitle("Connect to Health Connect")
        when {
            !hc.checked -> Muted("Checking…")
            hc.needsUpdate -> {
                Muted("Health Connect is Android's shared health database. Kaya saves your food and weigh-ins there. Install or update it first.")
                Button(onClick = {
                    val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
                    }
                }) { Text("Get Health Connect") }
            }
            !hc.available -> Muted("Health Connect isn't available on this phone (needs Android 9 or newer).")
            else -> {
                Muted("Kaya saves the food and weight you log in Health Connect, and can read workouts other apps share there. Everything stays on your phone.")
                Button(onClick = onConnect) { Text("Allow access") }
                Muted("If the button does nothing, Android has stopped asking after earlier refusals. Use the button below and allow everything for Kaya.")
                OutlinedButton(onClick = { openHealthConnect(context) }) { Text("Open Health Connect settings") }
            }
        }
    }
}

fun openHealthConnect(context: android.content.Context) {
    runCatching { context.startActivity(HealthConnectClient.getHealthConnectManageDataIntent(context)) }
        .recoverCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
}

@Composable
private fun BurnCard(ui: TodayUi, burn: DayBurn) {
    KCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CardTitle("Burned so far today") }
            InfoDot("How calories are worked out", CALORIE_INFO)
        }
        BigNumber(burn.total.kcal(), "kcal")
        Muted("${burn.rangeText} likely range")
        Spacer(Modifier.size(4.dp))
        LineItem("Resting (body running itself)", burn.resting.kcal())
        LineItem("Workouts (extra)", burn.workouts.kcal())
        LineItem("Everyday steps (extra)", burn.everydaySteps.kcal())
        if (burn.digestion > 0) LineItem("Digesting food", burn.digestion.kcal())
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        LineItem("Kaya says active", "${burn.active.kcal()} kcal", strong = true, valueColor = MaterialTheme.colorScheme.primary)
        LineItem("Eaten", "${ui.eatenKcal.kcal()} kcal")
        ui.targetKcal?.let { LineItem("Daily food target", "${it.kcal()} kcal", strong = true) }
        if (!ui.weightFromHealthConnect) Muted("Using the weight from your profile (${ui.weightKg.roundToInt()} kg). Log your weight in the Body tab to keep this accurate.")
    }
}

@Composable
private fun MovementCard(ui: TodayUi) {
    val a = ui.activity ?: return
    KCard {
        CardTitle("Movement")
        Row(Modifier.fillMaxWidth()) {
            Stat("steps", "%,d".format(a.steps), Modifier.weight(1f))
            Stat("km", a.distanceM?.let { "%.2f".format(it / 1000) } ?: "–", Modifier.weight(1f))
        }
        if (!ui.bandStepsShared) {
            Text(
                "These are your phone's steps (${ui.stepSources.joinToString()}), not the band's.",
                color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun HeartCard(ui: TodayUi) {
    val a = ui.activity ?: return
    KCard {
        CardTitle("Heart")
        Row(Modifier.fillMaxWidth()) {
            Stat("resting", ui.restingHr?.toString() ?: "–", Modifier.weight(1f))
            Stat("lowest", a.hrMin?.toString() ?: "–", Modifier.weight(1f))
            Stat("average", a.hrAvg?.toString() ?: "–", Modifier.weight(1f))
            Stat("highest", a.hrMax?.toString() ?: "–", Modifier.weight(1f))
        }
        Muted("bpm. Resting heart rate going down over weeks is one of the clearest signs of getting fitter.")
    }
}

@Composable
private fun SleepCard(ui: TodayUi) {
    KCard {
        CardTitle("Sleep (last night + naps)")
        val s = ui.sleep
        if (s == null) {
            Muted("No sleep recorded.")
            return@KCard
        }
        BigNumber(minutesText(s.asleepMin), "asleep")
        s.periods.forEach { (a, b) ->
            val label = if (a.atZone(java.time.ZoneId.systemDefault()).hour in 10..20) "Nap" else "Sleep"
            LineItem("$label ${a.clock()} – ${b.clock()}", minutesText(java.time.Duration.between(a, b).toMinutes()))
        }
        if (s.deepMin != null) {
            Row(Modifier.fillMaxWidth()) {
                Stat("deep", minutesText(s.deepMin), Modifier.weight(1f))
                Stat("REM", minutesText(s.remMin ?: 0), Modifier.weight(1f))
                Stat("light", minutesText(s.lightMin ?: 0), Modifier.weight(1f))
                Stat("awake", minutesText(s.awakeMin ?: 0), Modifier.weight(1f))
            }
            Muted("Sleep stages from a wrist band are rough guesses. Total time asleep is the reliable number.")
        }
        if (s.asleepMin < 7 * 60) Muted("Under 7 hours. Short sleep tends to raise hunger the next day.")
    }
}

@Composable
private fun WorkoutCard(row: SessionRow, vm: AppViewModel) {
    val d = row.data
    val e = row.est
    val c = row.check
    KCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CardTitle(d.title?.takeIf { it.isNotBlank() } ?: d.kind.label)
            Spacer(Modifier.weight(1f))
            Muted(vm.friendly(d.source) + " · " + d.recordingLabel)
        }
        Muted("${d.start.clock()} – ${d.end.clock()} · ${d.durationMin.roundToInt()} min")
        if (!c.counted) {
            Text("Not counted", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
            Muted(c.reason)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.setSessionOverride(d.id, true) }) { Text("It was a real workout") }
                if (c.userDecided) TextButton(onClick = { vm.setSessionOverride(d.id, null) }) { Text("Undo") }
            }
            return@KCard
        }
        Row(Modifier.fillMaxWidth()) {
            Stat("km", e.distanceM?.let { "%.2f".format(it / 1000) } ?: "–", Modifier.weight(1f))
            Stat("km/h", e.speedKmh?.let { "%.1f".format(it) } ?: "–", Modifier.weight(1f))
            Stat("avg HR", d.hrAvg?.toString() ?: "–", Modifier.weight(1f))
            Stat("max HR", d.hrMax?.toString() ?: "–", Modifier.weight(1f))
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        d.reportedKcal?.let { LineItem("${vm.friendly(d.source)} says", "${it.kcal()} kcal") }
        LineItem("Kaya: total burned", "${e.totalKcal.kcal()} kcal", strong = true, valueColor = MaterialTheme.colorScheme.primary)
        LineItem("…of which extra from the ${d.kind.label.lowercase()}", "${e.activeKcal.kcal()} kcal")
        row.hrFormulaKcal?.let {
            if (d.kind.onFeet) LineItem("Heart-rate formula (what watches use)", "${it.kcal()} kcal")
        }
        Muted(e.method)
        if (d.kind == ActivityKind.WALK && e.speedKmh == null) Muted("No distance or steps for this walk, so this is a rough average.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted(c.reason, Modifier.weight(1f))
            if (c.userDecided) TextButton(onClick = { vm.setSessionOverride(d.id, null) }) { Text("Undo") }
            else TextButton(onClick = { vm.setSessionOverride(d.id, false) }) { Text("Not a workout?") }
        }
    }
}

