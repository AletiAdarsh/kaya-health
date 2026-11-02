package com.kaya.health.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaya.health.band.BandDay
import com.kaya.health.band.BandState
import com.kaya.health.band.BandViewModel
import com.kaya.health.band.LiveMode
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val secondsFmt = DateTimeFormatter.ofPattern("h:mm:ss a")
private val hhmmFmt = DateTimeFormatter.ofPattern("h:mm a")

@Composable
fun BandScreen(band: BandViewModel) {
    val context = LocalContext.current
    val state by band.state.collectAsState()
    val info by band.info.collectAsState()
    val days by band.days.collectAsState()
    val selected by band.selected.collectAsState()
    val day = days[selected]
    val busy by band.busy.collectAsState()
    val live by band.live.collectAsState()
    val batteryState by band.battery.collectAsState()
    val frequency by band.frequency.collectAsState()
    val log by band.log.collectAsState()
    var permitted by remember { mutableStateOf(BandViewModel.hasPermissions(context)) }
    var showLog by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = BandViewModel.hasPermissions(context)
        if (permitted) band.connectAndSync()
    }

    LaunchedEffect(permitted) { if (permitted && state !is BandState.Connected) band.connectAndSync() }
    // Live heart rate keeps running when you switch tabs, until you tap Stop (or close the app).

    var openVital by remember { mutableStateOf<com.kaya.health.band.Vital?>(null) }
    LaunchedEffect(state is BandState.Connected) { if (state is BandState.Connected) band.refreshVitals() }
    openVital?.let { v ->
        androidx.activity.compose.BackHandler { openVital = null }
        VitalDetail(band, v, onBack = { openVital = null })
        return
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Band", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Muted("Direct Bluetooth connection to your Noise REP.")
        }

        if (!permitted) {
            item {
                KCard {
                    CardTitle("Allow Bluetooth")
                    Muted("Kaya needs Bluetooth permission to talk to your band directly.")
                    Button(onClick = { permLauncher.launch(BandViewModel.requiredPermissions()) }) { Text("Allow") }
                }
            }
            return@LazyColumn
        }

        item { ConnectionCard(state, info, busy, batteryState, onConnect = band::connectAndSync, onDisconnect = band::disconnect) }
        item { LiveHeartCard(live, enabled = true, onStart = band::startLive, onStop = band::stopLive) }
        item { VitalsSection(band, onOpen = { openVital = it }) }
        frequency?.let { f ->
            item {
                KCard {
                    CardTitle("How often your band measures")
                    fun fmt(level: Int) = if (level <= 0) "off" else "level $level"
                    (f.awake.keys + f.asleep.keys).distinct().forEach { k ->
                        LineItem(k, "awake ${f.awake[k]?.let(::fmt) ?: "–"} · asleep ${f.asleep[k]?.let(::fmt) ?: "–"}")
                    }
                    Muted("Read from the band's own settings (command 768). The band reports a level per measurement (yours are all 1, likely \"standard\"); Noise doesn't publish what each level means. Kaya doesn't change them: more frequent measuring uses more battery.")
                }
            }
        }
        if (days.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    days.keys.sortedDescending().forEach { date ->
                        val label = if (date == java.time.LocalDate.now()) "Today" else if (date == java.time.LocalDate.now().minusDays(1)) "Yesterday" else date.format(dayFmt)
                        androidx.compose.material3.FilterChip(selected = date == selected, onClick = { band.select(date) }, label = { Text(label) })
                    }
                }
            }
        }
        day?.let { d ->
            item { BandActivityCard(d) }
            item { BandHeartCard(d) }
        }
        item {
            KCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardTitle("Connection log")
                    Row(Modifier.weight(1f)) {}
                    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide" else "Show") }
                }
                if (showLog) log.takeLast(30).forEach {
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun ConnectionCard(
    state: BandState, info: com.kaya.health.band.DeviceInfo?, busy: String?, battery: com.kaya.health.band.Battery?,
    onConnect: () -> Unit, onDisconnect: () -> Unit,
) {
    KCard {
        val (title, color) = when (state) {
            is BandState.Connected -> state.name to MaterialTheme.colorScheme.primary
            is BandState.Connecting -> (state.name ?: "Looking for your band") to MaterialTheme.colorScheme.onSurface
            is BandState.Failed -> "Not connected" to MaterialTheme.colorScheme.secondary
            BandState.Idle -> "Not connected" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("●  ", color = color)
            CardTitle(title)
        }
        when (state) {
            is BandState.Connected -> Muted("Connected directly over Bluetooth")
            is BandState.Connecting -> Muted("Connecting…")
            is BandState.Failed -> Muted(state.reason)
            BandState.Idle -> Muted("Tap Connect")
        }
        battery?.let { LineItem("Battery", "${it.percent}%" + if (it.charging) " · charging" else if (it.full) " · full" else "") }
        info?.let {
            if (it.model.isNotBlank()) LineItem("Model", it.model)
            if (it.firmware.isNotBlank()) LineItem("Firmware", it.firmware)
            if (it.serial.isNotBlank()) LineItem("Serial", it.serial)
        }
        busy?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  $it")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnect, enabled = busy == null) { Text(if (state is BandState.Connected) "Sync now" else "Connect") }
            if (state is BandState.Connected) OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
    }
}

@Composable
internal fun LiveHeartCard(live: com.kaya.health.band.LiveUi, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "scale")
    KCard {
        CardTitle("Heart rate")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Favorite, contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(44.dp).scale(if (live.running && live.bpm != null) scale else 1f),
            )
            Text(
                live.bpm?.toString() ?: "--",
                fontSize = 64.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp),
            )
            Text(" bpm", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
        }
        live.at?.let { at ->
            Muted(
                when (live.mode) {
                    LiveMode.STREAM -> "Live · ${at.format(secondsFmt)}"
                    else -> "Band's newest reading · recorded ${at.format(hhmmFmt)}"
                }
            )
        }
        live.note?.let { Muted(it) }
        if (live.running && (live.spo2 != null || live.stress != null)) {
            Row(Modifier.fillMaxWidth()) {
                live.spo2?.let { Stat("SpO2 %", "$it", Modifier.weight(1f)) }
                live.stress?.let { Stat("stress /100", "$it", Modifier.weight(1f)) }
            }
            Muted("SpO2 and stress here are the band's most recent stored measurement, not a new reading. Only heart rate is measured live.")
        }
        if (live.running && live.history.size >= 2) {
            Sparkline(live.history.map { it.toDouble() })
            Muted("Last ${live.history.size} readings · range ${live.history.min()}–${live.history.max()} bpm")
        }
        if (live.running) {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
        } else {
            Button(
                onClick = onStart, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Check heart rate", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun BandActivityCard(d: BandDay) {
    KCard {
        CardTitle(if (d.date == java.time.LocalDate.now()) "Today, from the band" else "${d.date.format(fullDayFmt)}, from the band")
        Muted("Synced at ${d.syncedAt.clock()}")
        Row(Modifier.fillMaxWidth()) {
            Stat("steps", d.steps?.let { "%,d".format(it) } ?: "–", Modifier.weight(1f))
            Stat("km", d.distanceM?.let { "%.2f".format(it / 1000.0) } ?: "–", Modifier.weight(1f))
            Stat("active min", d.activeMinutes?.toString() ?: "–", Modifier.weight(1f))
        }
        d.hourlySteps?.let { HourlyBars(it) }
        if (d.activity == null) Muted("The band didn't send activity for today yet.")
    }
}

@Composable
private fun HourlyBars(values: IntArray) {
    val color = MaterialTheme.colorScheme.primary
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val slot = size.width / 24f
        values.forEachIndexed { h, v ->
            val hgt = size.height * v / max
            drawRect(color, topLeft = Offset(h * slot + slot * 0.15f, size.height - hgt), size = Size(slot * 0.7f, hgt))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Muted("12am"); Muted("6am"); Muted("12pm"); Muted("6pm"); Muted("12am")
    }
}

@Composable
private fun BandHeartCard(d: BandDay) {
    val readings = d.hrReadings
    KCard {
        CardTitle("Heart rate, from the band")
        if (readings.isEmpty()) {
            Muted("No heart-rate readings received yet.")
            return@KCard
        }
        val bpms = readings.map { it.second }
        Row(Modifier.fillMaxWidth()) {
            Stat("lowest", "${bpms.min()}", Modifier.weight(1f))
            Stat("average", "${bpms.average().roundToInt()}", Modifier.weight(1f))
            Stat("highest", "${bpms.max()}", Modifier.weight(1f))
        }
        Muted("%,d readings from %s to %s".format(readings.size, readings.first().first.format(hhmmFmt), readings.last().first.format(hhmmFmt)))
        HrDayChart(readings.map { it.first.toLocalTime().toSecondOfDay() / 86_400f to it.second })
    }
}

@Composable
private fun HrDayChart(points: List<Pair<Float, Int>>) {
    val color = MaterialTheme.colorScheme.error
    val lo = (points.minOf { it.second } - 5).toFloat()
    val hi = (points.maxOf { it.second } + 5).toFloat()
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        var prev: Pair<Float, Int>? = null
        for (p in points) {
            val x = p.first * size.width
            val y = size.height * (1 - (p.second - lo) / (hi - lo))
            val q = prev
            // Join neighbouring readings; leave gaps where the band has none (> 5 min apart).
            if (q != null && (p.first - q.first) * 86_400 < 300) {
                drawLine(color, Offset(q.first * size.width, size.height * (1 - (q.second - lo) / (hi - lo))), Offset(x, y), strokeWidth = 2.dp.toPx())
            }
            prev = p
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Muted("12am"); Muted("6am"); Muted("12pm"); Muted("6pm"); Muted("12am")
    }
}
