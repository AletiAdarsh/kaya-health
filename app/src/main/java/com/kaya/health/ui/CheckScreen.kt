package com.kaya.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaya.health.data.DataCheck
import com.kaya.health.data.SourceStat
import java.time.Duration
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * "Can I trust these numbers?" Shows exactly what each app wrote into Health Connect,
 * which NoiseFit screen to compare it with, and Kaya's calorie maths step by step.
 */
@Composable
fun CheckScreen(vm: AppViewModel) {
    val ui by vm.check.collectAsState()
    val hc by vm.hc.collectAsState()
    val today by vm.today.collectAsState()
    val isToday = ui.date == LocalDate.now()

    LaunchedEffect(hc.connected) { if (hc.connected && ui.check == null) vm.showCheckDay(ui.date) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showCheckDay(ui.date.minusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Check the data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Muted(ui.date.format(fullDayFmt))
                }
                if (ui.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { vm.showCheckDay(ui.date) }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                IconButton(onClick = { vm.showCheckDay(ui.date.plusDays(1)) }, enabled = !isToday) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
                }
            }
        }

        if (!hc.connected) {
            item { KCard { Muted("Connect Health Connect on the Today tab first.") } }
            return@LazyColumn
        }

        item {
            KCard {
                CardTitle("How to use this screen")
                Text("1. Open NoiseFit and let the band sync. Kaya only sees what NoiseFit has passed on.")
                Text("2. Measurements (steps, heart rate, sleep, workout times) should match NoiseFit. Each card says which NoiseFit screen to compare with.")
                Text("3. Calories are meant to differ. Kaya recalculates them, and the maths is at the bottom so you can check it by hand.")
            }
        }

        ui.error?.let { item { KCard { CardTitle("Couldn't read Health Connect"); Muted(it) } } }
        val c = ui.check ?: return@LazyColumn

        item { SyncCard(c, vm) }

        item {
            DataCard(
                title = "Steps",
                kayaValue = c.stepsTotal?.let { "%,d".format(it) },
                rows = c.steps.map { it to "%,d steps".format(it.value.roundToInt()) },
                compare = if (c.steps.any { DataCheck.isNoise(it.source) }) "NoiseFit → Home → Steps. Should be the same number once NoiseFit has synced."
                else "NoiseFit → Home → Steps. They WON'T match: NoiseFit doesn't share the band's steps, so Kaya only has your phone's count.",
                vm = vm,
            )
        }
        item {
            DataCard(
                title = "Distance",
                kayaValue = c.distanceTotalM?.let { "%.2f km".format(it / 1000) },
                rows = c.distance.map { it to "%.2f km".format(it.value / 1000) },
                compare = "NoiseFit → Home → Distance. NoiseFit estimates distance from steps (stride length), not GPS, unless you used phone GPS in a workout.",
                vm = vm,
            )
        }
        item {
            KCard {
                CardTitle("Heart rate")
                if (c.heartRate.isEmpty()) Muted("No heart-rate data shared for this day.")
                c.heartRate.forEach { h ->
                    Text(vm.friendly(h.source), fontWeight = FontWeight.SemiBold)
                    LineItem("Readings", "%,d (%s – %s)".format(h.samples, h.first.clock(), h.last.clock()))
                    LineItem("Lowest / average / highest", "${h.min} / ${h.avg.roundToInt()} / ${h.max} bpm")
                    val hours = Duration.between(h.first, h.last).toMinutes() / 60.0
                    if (hours > 1) Muted("≈ one reading every %.0f seconds".format(hours * 3600 / h.samples))
                    if (DataCheck.isNoise(h.source) && h.first.atZone(java.time.ZoneId.systemDefault()).hour >= 3) {
                        Text(
                            "NoiseFit only shared readings from ${h.first.clock()}. NoiseFit's own heart-rate graph starts at midnight, so earlier readings weren't passed on.",
                            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                c.restingHr.forEach { r -> LineItem("Resting HR (${vm.friendly(r.source)})", "${r.value.roundToInt()} bpm") }
                Compare("NoiseFit → Home → Heart rate → day view (lowest, average, highest). The big number on NoiseFit's Home card is just the latest reading, not the average.")
            }
        }
        item {
            KCard {
                CardTitle("Sleep")
                if (c.sleep.isEmpty()) Muted("No sleep shared for the night before this day.")
                c.sleep.forEach { s ->
                    val mins = Duration.between(s.start, s.end).toMinutes()
                    LineItem("${vm.friendly(s.source)}: ${s.start.clock()} – ${s.end.clock()}", minutesText(mins))
                    Muted(if (s.stages > 0) "${s.stages} sleep-stage segments included" else "No sleep stages shared, only start and end times")
                }
                Compare("NoiseFit → Sleep. Start and end times should match. Kaya's Today screen shows time asleep (in bed minus awake), so it can be a bit lower than time in bed.")
            }
        }
        item {
            KCard {
                CardTitle("Workouts")
                if (c.sessions.isEmpty()) Muted("No workouts shared for this day. If NoiseFit shows workouts, it isn't passing them to Health Connect. Your walking steps still count through the Steps line.")
                ui.rows.forEach { r ->
                    val s = r.data
                    Text("${s.kind.label} · ${vm.friendly(s.source)} · ${s.recordingLabel}", fontWeight = FontWeight.SemiBold)
                    LineItem("Time", "${s.start.clock()} – ${s.end.clock()} (${s.durationMin.roundToInt()} min)")
                    LineItem("Avg / max heart rate", "${s.hrAvg ?: "–"} / ${s.hrMax ?: "–"} bpm")
                    LineItem("Distance / steps (from any app)", (s.distanceM?.let { "%.2f km".format(it / 1000) } ?: "–") + " / " + (s.steps?.let { "%,d".format(it) } ?: "–"))
                    s.reportedKcal?.let { LineItem("Calories ${vm.friendly(s.source)} reported", "${it.roundToInt()} kcal") }
                    Text(
                        if (r.check.counted) "Kaya counts it" else "Kaya doesn't count it",
                        color = if (r.check.counted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Muted(r.check.reason)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
                Compare("NoiseFit → Fitness → Workouts. If NoiseFit doesn't list a workout that appears here, NoiseFit logged it in the background (often a bus or bike ride).")
            }
        }
        item {
            KCard {
                CardTitle("Calorie numbers other apps sent")
                if (c.activeKcal.isEmpty() && c.totalKcal.isEmpty()) Muted("No app shared calorie numbers for this day.")
                c.activeKcal.forEach { LineItem("Active (${vm.friendly(it.source)})", "${it.value.roundToInt()} kcal") }
                c.totalKcal.forEach { LineItem("Total (${vm.friendly(it.source)})", "${it.value.roundToInt()} kcal") }
                if ((c.activeKcal + c.totalKcal).none { DataCheck.isNoise(it.source) }) {
                    Muted("None of these are from NoiseFit. It keeps its calorie numbers to itself.")
                }
                Compare("Shown for comparison only. Kaya never uses these.")
            }
        }

        if (c.noPermission.isNotEmpty()) {
            item {
                KCard {
                    CardTitle("Kaya isn't allowed to read")
                    Muted(c.noPermission.joinToString(", "))
                    Muted("Turn these on in Health Connect → App permissions → Kaya.")
                }
            }
        }

        item { Text("Kaya's calorie maths", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (isToday && today.working.isNotEmpty()) {
            item {
                KCard {
                    CardTitle("Burned so far today")
                    today.working.forEach { Working(it) }
                }
            }
        }
        ui.rows.forEach { r ->
            item {
                KCard {
                    CardTitle("${r.data.kind.label} at ${r.data.start.clock()}")
                    r.est.working.forEach { Working(it) }
                    r.hrFormulaKcal?.let {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Muted(
                            "For comparison, the heart-rate formula most watches use gives %,.0f kcal".format(it) +
                                (r.data.reportedKcal?.let { n -> " (NoiseFit reported %,.0f)".format(n) } ?: "") +
                                ". That formula assumes a heart rate of ${r.data.hrAvg} means average-fitness effort."
                        )
                    }
                }
            }
        }
        if (ui.rows.isEmpty() && !isToday) item { Muted("No workouts this day, so there's no workout maths to show.") }
        item {
            KCard {
                CardTitle("How to test Kaya's calories against reality")
                Text("• Distance test: walk a route you've measured on Google Maps (e.g. exactly 2 km). If NoiseFit's distance is off, Kaya's walk calories are off by the same percentage.")
                Text("• The real test is your scale: log food and weigh in daily. After 2–3 weeks the Body tab works out your true burn from your own weight change, and it corrects any error in the formulas.")
            }
        }
    }
}

@Composable
private fun SyncCard(c: DataCheck, vm: AppViewModel) {
    KCard {
        CardTitle("Sync status")
        val last = c.lastNoiseData
        if (last == null) {
            Text("No NoiseFit data in Health Connect for this day.", color = MaterialTheme.colorScheme.error)
            Muted("Either NoiseFit hasn't synced yet, or its Health Connect sharing is off. Open Health Connect → App permissions → NoiseFit and allow everything.")
        } else {
            LineItem("Newest NoiseFit data", "${last.day()} ${last.clock()}", strong = true)
            Muted("Anything NoiseFit recorded after this time hasn't reached Kaya yet.")
        }
        val others = (c.steps + c.distance).map { it.source }.distinct().filterNot { DataCheck.isNoise(it) }
        if (others.isNotEmpty()) {
            Muted("Other apps also sent data: ${others.joinToString { vm.friendly(it) }}. When two apps record the same thing, Health Connect counts it once using its app-priority list (Health Connect → Data and access).")
        }
    }
}

@Composable
private fun DataCard(title: String, kayaValue: String?, rows: List<Pair<SourceStat, String>>, compare: String, vm: AppViewModel) {
    KCard {
        CardTitle(title)
        if (rows.isEmpty()) Muted("Nothing shared for this day.")
        else {
            LineItem("Kaya uses", kayaValue ?: "–", strong = true, valueColor = MaterialTheme.colorScheme.primary)
            rows.forEach { (s, v) ->
                LineItem("${vm.friendly(s.source)} sent", v)
                Muted("${s.records} records, ${s.first.clock()} – ${s.last.clock()}")
            }
            if (rows.size > 1) Muted("More than one app sent $title. Health Connect avoids double counting, so \"Kaya uses\" can be less than the sum.")
        }
        Compare(compare)
    }
}

@Composable
private fun Compare(text: String) {
    Text("Compare with: $text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun Working(line: String) {
    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}
