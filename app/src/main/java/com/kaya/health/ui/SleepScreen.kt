package com.kaya.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaya.health.band.BandViewModel
import com.kaya.health.engine.SleepResult
import com.kaya.health.engine.SleepStage
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Crescent moon (Material "dark mode" glyph, Apache-2.0). */
val MoonIcon: ImageVector = ImageVector.Builder("Moon", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        pathData = addPathNodes(
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9s9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36c-0.98,1.37 -2.58,2.26 " +
                "-4.4,2.26c-2.98,0 -5.4,-2.42 -5.4,-5.4c0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3z"
        ),
        fill = SolidColor(Color.Black),
    ).build()

private val clockFmt = DateTimeFormatter.ofPattern("h:mm a")

private fun stageColor(s: SleepStage): Color = when (s) {
    SleepStage.AWAKE -> Color(0xFFFFA94D)
    SleepStage.REM -> Color(0xFF7DD3FC)
    SleepStage.LIGHT -> Color(0xFF4FD1C5)
    SleepStage.DEEP -> Color(0xFF6366F1)
}

const val SLEEP_INFO = "When you slept: from your band's own motion-based sleep window (it records breathing only while it thinks you're asleep). If the band doesn't report one, Kaya finds the longest stretch its model classifies as sleep.\n\nStages: every 30 seconds Kaya's model weighs your heart rate against your own sleeping baseline, how steady it is over 5 and 15 minutes, how much it jumps beat to beat, its trend, steps that hour and how far into the night it is.\n\nAccuracy: trained and tested on 31 sleep-lab nights (Walch et al. 2019, PhysioNet). On unseen people it matched the lab's stage 61% of the time and asleep vs awake 90% of the time, typical for wrist trackers. Bedtime, wake time and total sleep are the solid numbers; stages are estimates."

const val SLEEP_SCORE_INFO = "Out of 100:\n\n• Duration: up to 40 points, full marks at 7 h asleep.\n• Continuity: up to 20 points, full marks when you're asleep 90%+ of the time in bed.\n• Deep sleep: up to 15 points for 13–23% deep.\n• REM: up to 15 points for 18–25% REM.\n• Awakenings: up to 10 points for 0–1 times awake.\n\nOn a short night the quality points are scaled down, so a great 4-hour sleep can't score like a full night."

@Composable
fun SleepScreen(band: BandViewModel, vm: AppViewModel) {
    val ui by band.sleep.collectAsState()
    val nights by vm.nights.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val today = LocalDate.now()
    var day by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val date = LocalDate.ofEpochDay(day)
    val isToday = date == today
    LaunchedEffect(ui.analysedAt) { vm.loadNights() }

    LaunchedEffect(Unit) { if (ui.result == null && !ui.loading) band.analyzeSleep() }

    // An earlier day: the night that ended that morning (as analysed then) and the naps that started that day.
    val saved = remember(day, ui.analysedAt) { if (isToday) null else com.kaya.health.data.SleepArchive(context).load(date) }
    val dayNaps = remember(day, ui.analysedAt) { if (isToday) ui.naps else com.kaya.health.data.BodyLog(context).naps(date) }
    val r = if (isToday) ui.result else saved?.result
    val brAvg = if (isToday) ui.breathingAvg else saved?.breathingAvg
    val brRange = if (isToday) ui.breathingRange else saved?.breathingRange
    val napMin = com.kaya.health.engine.NapFinder.minutes(dayNaps)

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        card(1) {
            PageHeader("Sleep", if (isToday) ui.analysedAt?.let { "Last night · updated ${it.clock()}" } ?: "Last night" else "The night that ended this morning, and naps", Area.sleep, MoonIcon) {
                InfoDot("How Kaya measures sleep", SLEEP_INFO)
                if (isToday) {
                    if (ui.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Area.sleep)
                    else androidx.compose.material3.TextButton(onClick = band::analyzeSleep) {
                        Text(if (ui.result == null) "Analyse" else "Refresh", color = Area.sleep)
                    }
                }
            }
            DayStrip(date, Area.sleep) { day = it.toEpochDay() }
        }
        if (isToday) ui.message?.let { item { Panel { Muted(it) } } }

        if (r != null) {
            card(2) { SummaryCard(r, if (isToday) "Last night" else "Night before") }
            card(3) { StageBreakdown(r) }
            card(4) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TileRow {
                        Tile("Efficiency", "${(100.0 * r.asleepMin / r.inBedMin.coerceAtLeast(1)).roundToInt()}", "%", "asleep vs in bed", Area.sleep, null, Modifier.weight(1f))
                        Tile("Woke up", "${r.awakenings}", "times", "${minutesText((r.minutes[SleepStage.AWAKE] ?: 0).toLong())} awake", Area.sleep, null, Modifier.weight(1f))
                    }
                    TileRow {
                        Tile("Sleeping HR", "${r.avgSleepingHr}", "bpm", "lowest ${r.lowestHr}", Area.vitals, null, Modifier.weight(1f))
                        Tile("Breathing", brAvg?.let { "%.0f".format(it) } ?: "–", "/min",
                            brRange?.let { (a, b) -> "range $a–$b" } ?: "no reading", Area.mind, null, Modifier.weight(1f))
                    }
                    if (dayNaps.isNotEmpty()) NapTiles(dayNaps, napMin, r.asleepMin.toLong(), isToday)
                }
            }
            card(5) { HypnogramCard(r) }
            if (isToday) card(6) { NightHeartCard(r, ui.readings) }
        } else if (!isToday) {
            card(2) {
                val n = nights[date]
                Panel(accent = Area.sleep) {
                    PanelLabel("Night before", MoonIcon, Area.sleep)
                    if (n != null) {
                        Figure(minutesText(n.minutes.toLong()), "asleep")
                        Muted(if (n.fromOtherApp) "From Health Connect. Kaya didn't analyse this night itself, so there are no stages for it."
                            else "Kaya saved only the total for this night (from before day-by-day detail was added).")
                    } else Muted("No sleep recorded for this night.")
                }
                if (dayNaps.isNotEmpty()) NapTiles(dayNaps, napMin, n?.minutes?.toLong(), false)
            }
        }
        card(7) { WeekCard(nights, date) { day = it.toEpochDay() } }
        if (r != null) card(8) { ScoreCard(r) }
    }
}

@Composable
private fun NapTiles(naps: List<Pair<LocalDateTime, LocalDateTime>>, napMin: Long, nightMin: Long?, isToday: Boolean) {
    TileRow {
        Tile(if (naps.size > 1) "Naps (${naps.size})" else "Nap", minutesText(napMin), "",
            naps.joinToString(", ") { "${it.first.format(shortClock)}–${it.second.format(shortClock)}" }, Area.sleep, null, Modifier.weight(1f))
        Tile(if (isToday) "Total today" else "Total that day", minutesText((nightMin ?: 0L) + napMin), "", "night + naps", Area.sleep, null, Modifier.weight(1f))
    }
}

private val shortClock = DateTimeFormatter.ofPattern("h:mm")

/** One stacked bar for the night plus time and share per stage. */
@Composable
private fun StageBreakdown(r: SleepResult) {
    val order = listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE)
    val total = order.sumOf { r.minutes[it] ?: 0 }.coerceAtLeast(1)
    Panel {
        PanelLabel("Stages", null, Area.sleep, info = "Healthy adult ranges (share of time asleep): deep 13–23%, REM 20–25%, light 50–60%. Ranges shift with age; night-to-night consistency matters more than one night.")
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            var x = 0f
            order.forEach { s ->
                val w = size.width * (r.minutes[s] ?: 0) / total
                drawRect(stageColor(s), topLeft = Offset(x, 0f), size = Size(w, size.height)); x += w
            }
        }
        Row(Modifier.fillMaxWidth()) {
            order.forEach { s ->
                val min = r.minutes[s] ?: 0
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(8.dp)) { drawCircle(stageColor(s)) }
                        Text(" ${s.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(minutesText(min.toLong()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Muted(if (s == SleepStage.AWAKE) " " else "${(r.share(s) * 100).roundToInt()}%")
                }
            }
        }
    }
}

/** Last 7 nights: Kaya's own analysis, with nights it missed filled from Health Connect. */
@Composable
private fun WeekCard(nights: Map<java.time.LocalDate, AppViewModel.Night>, selected: LocalDate, onPick: (LocalDate) -> Unit) {
    val today = java.time.LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val values = days.map { (nights[it]?.minutes ?: 0).toDouble() }
    val have = values.filter { it > 0 }
    val borrowed = days.filter { nights[it]?.fromOtherApp == true }
    Panel {
        PanelLabel("Last 7 nights", null, Area.sleep, have.takeIf { it.isNotEmpty() }?.let { "avg ${minutesText(it.average().toLong())}" },
            info = "Nights Kaya analysed itself come from your band's raw data. Nights it missed (before you installed it, or when the band had already been synced elsewhere) are filled from Health Connect, as recorded by the app that wrote them there.")
        if (have.size < 2) { Muted("Fills in as nights are recorded (${have.size} so far)."); return@Panel }
        GrowBars(values, Area.sleep, height = 90.dp, highlight = days.indexOf(selected).takeIf { it >= 0 } ?: 6)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            days.forEach { d ->
                Text(d.dayOfWeek.name.take(1) + if (d in borrowed) "*" else "", Modifier.clickable { onPick(d) }.padding(4.dp),
                    style = MaterialTheme.typography.bodySmall, fontWeight = if (d == selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (d == selected) Area.sleep else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Stat("best", minutesText(have.max().toLong()), Modifier.weight(1f))
            Stat("shortest", minutesText(have.min().toLong()), Modifier.weight(1f))
            Stat("nights 7 h+", "${have.count { it >= 420 }}/${have.size}", Modifier.weight(1f))
        }
        if (borrowed.isNotEmpty()) Muted("* from Health Connect")
    }
}

@Composable
private fun SummaryCard(r: SleepResult, label: String = "Last night") {
    Panel(accent = Area.sleep) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PanelLabel(label, MoonIcon, Area.sleep)
                Figure(minutesText(r.asleepMin.toLong()), "asleep")
                Muted("${r.bedtime.format(clockFmt)} → ${r.wake.format(clockFmt)}  ·  ${minutesText(r.inBedMin.toLong())} in bed")
            }
            ScoreRing(r.score, Area.sleep, "Sleep score", size = 88.dp, stroke = 8.dp)
        }
        if (r.dataCoverage < 0.85) {
            Text(
                "The band missed ${(100 - r.dataCoverage * 100).roundToInt()}% of readings during the night (loose or off the wrist?), so this is less certain.",
                color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HypnogramCard(r: SleepResult) {
    val rows = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
    Panel {
        PanelLabel("Stages through the night", null, Area.sleep)
        Row {
            Column(Modifier.width(52.dp).height(140.dp), verticalArrangement = Arrangement.SpaceAround) {
                rows.forEach { Muted(it.label) }
            }
            Canvas(Modifier.weight(1f).height(140.dp)) {
                val n = r.epochs.size
                if (n == 0) return@Canvas
                val w = size.width / n
                val rowH = size.height / rows.size
                r.epochs.forEachIndexed { i, e ->
                    val row = rows.indexOf(e.stage)
                    drawRect(stageColor(e.stage), topLeft = Offset(i * w, row * rowH + rowH * 0.15f), size = Size(w + 0.5f, rowH * 0.7f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 52.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(r.bedtime.format(clockFmt))
            Muted(r.bedtime.plusMinutes(r.inBedMin / 2L).format(clockFmt))
            Muted(r.wake.format(clockFmt))
        }
    }
}

@Composable
private fun NightHeartCard(r: SleepResult, readings: List<Pair<LocalDateTime, Int>>) {
    val pts = readings.filter { !it.first.isBefore(r.bedtime.minusMinutes(60)) && it.first.isBefore(r.wake.plusMinutes(60)) }
    Panel {
        PanelLabel("Heart rate overnight", null, Area.vitals)
        Row(Modifier.fillMaxWidth()) {
            Stat("avg asleep", "${r.avgSleepingHr}", Modifier.weight(1f))
            Stat("lowest", "${r.lowestHr}", Modifier.weight(1f))
            Stat("awake level", "${r.awakeHr}", Modifier.weight(1f))
        }
        if (pts.size < 2) return@Panel
        val t0 = pts.first().first
        SmoothChart(
            series = listOf(Series(pts.map { Duration.between(t0, it.first).seconds / 3600.0 to it.second.toDouble() }, Area.sleep, fill = true, width = 2.5.dp)),
            gapAbove = 5 / 60.0, height = 150.dp,
            inspect = { x, y -> "${t0.plusSeconds((x * 3600).toLong()).format(clockFmt)} · ${y.toInt()} bpm" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(pts.first().first.format(clockFmt)); Muted(pts.last().first.format(clockFmt))
        }
    }
}

@Composable
private fun ScoreCard(r: SleepResult) {
    Panel {
        PanelLabel("Score ${r.score}", null, Area.sleep, info = SLEEP_SCORE_INFO)
        r.scoreParts.forEach { (label, pts) ->
            val max = Regex("= (\\d+)\\)").find(label)?.groupValues?.get(1)?.toIntOrNull()
            val name = if (label.startsWith("Short night")) "Short-night penalty"
                else label.substringBefore(" (").substringBefore(":").replace(Regex(" \\d.*"), "")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(if (max != null) "$pts / $max" else "$pts", style = MaterialTheme.typography.labelLarge,
                    color = if (pts < 0) Area.activity else MaterialTheme.colorScheme.onSurface)
            }
            if (max != null) Bar(pts.toDouble() / max, Area.sleep, height = 4.dp)
        }
    }
}
