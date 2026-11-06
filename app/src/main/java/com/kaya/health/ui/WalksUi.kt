package com.kaya.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kaya.health.band.BandViewModel
import com.kaya.health.engine.Walk
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val clock = DateTimeFormatter.ofPattern("h:mm a")

/** Start walk (live) plus today's walks, recorded or found automatically. */
@Composable
fun WalksCard(band: BandViewModel, weightKg: Double, heightCm: Double, restingHr: Double, age: Int, sex: com.kaya.health.engine.Sex) {
    val bmrDay = com.kaya.health.engine.Calories.bmr(weightKg, heightCm, age, sex)
    fun kcal(w: Walk) = w.totalExtraKcal(weightKg, heightCm, age, sex, bmrDay, restingHr).roundToInt()
    val walk by band.walk.collectAsState()
    val live by band.live.collectAsState()
    val version by band.walksVersion.collectAsState()
    val days by band.days.collectAsState()
    var justDone by remember { mutableStateOf<Walk?>(null) }
    val walks = remember(version, days, restingHr) { band.walksOn(LocalDate.now(), restingHr, age) }

    Panel(accent = Area.activity) {
        PanelLabel("Walks", Icons.AutoMirrored.Rounded.DirectionsWalk, Area.activity, walks.takeIf { it.isNotEmpty() }?.let { "${it.size} today" })
        if (walk.running) {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
            val start = walk.startedAt ?: LocalDateTime.now()
            val secs = Duration.between(start, LocalDateTime.now()).seconds.coerceAtLeast(0)
            val sofar = Walk(start, start.plusSeconds(secs), walk.steps, walk.hr.takeIf { it.isNotEmpty() }?.average()?.toInt(), walk.hr.maxOrNull(), false)
            Row(verticalAlignment = Alignment.Bottom) {
                Figure("%d:%02d".format(secs / 60, secs % 60), "", big = true)
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("steps", "%,d".format(walk.steps), Modifier.weight(1f))
                Stat("km", "%.2f".format(sofar.distanceM(heightCm) / 1000), Modifier.weight(1f))
                Stat("km/h", if (secs > 60) "%.1f".format(sofar.speedKmh(heightCm)) else "–", Modifier.weight(1f))
                Stat("bpm", live.bpm?.toString() ?: "–", Modifier.weight(1f))
            }
            Muted("≈ ${sofar.extraKcal(weightKg, heightCm).roundToInt()} kcal so far, from your weight and speed. Keep the phone near you; the band sends steps and heart rate every ~2 s.")
            Button(
                onClick = { justDone = band.stopWalk() }, modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Area.vitals, contentColor = Color.White),
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(20.dp)); Text("  Finish walk")
            }
        } else {
            justDone?.let { w ->
                Text(
                    "Nice: ${w.minutes.roundToInt()} min, %,d steps, %.2f km, ~${kcal(w)} kcal.".format(w.steps, w.distanceM(heightCm) / 1000),
                    style = MaterialTheme.typography.titleMedium, color = Area.food,
                )
            }
            Button(
                onClick = { justDone = null; band.startWalk() }, modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Area.activity, contentColor = Color(0xFF1E0B00)),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp)); Text("  Start walk")
            }
            if (walks.isEmpty()) Muted("No walks yet today. Walks of 10+ minutes show up here by themselves, found from your heart rate and steps.")
            walks.forEach { w -> WalkRow(w, heightCm, kcal(w)) { band.dismissWalk(w) } }
            if (walks.isNotEmpty()) Muted("Walk calories are already part of today's burn: the steps part through your steps, plus what your heart rate shows on top. Nothing is counted twice.")
        }
    }
}

@Composable
private fun WalkRow(w: Walk, heightCm: Double, kcal: Int, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${w.start.format(clock)}–${w.end.format(clock)} · ${w.minutes.roundToInt()} min",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "%,d steps · %.1f km/h".format(w.steps, w.speedKmh(heightCm)) + (w.avgHr?.let { " · avg $it bpm" } ?: "") +
                        " · ~$kcal kcal",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (w.detected) Tag("auto", Area.activity) else Tag("recorded", Area.food)
        }
        if (w.detected) TextButton(onClick = onDismiss) { Text("Not a walk") }
    }
}
