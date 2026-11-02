package com.kaya.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.kaya.health.engine.Level
import com.kaya.health.engine.VitalInsights
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaya.health.band.BandViewModel
import com.kaya.health.band.Vital
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("h:mm a")

/** Your usual daily value for a vital: average of up to 14 earlier saved days (lowest for resting HR). Null until there are 2+ days. */
fun usualOf(band: BandViewModel, v: Vital): Double? =
    band.history(v).filter { it.date != LocalDate.now() }.take(14).takeIf { it.size >= 2 }
        ?.let { days -> if (v.metric == Vital.RESTING_HR.metric) days.map { it.min }.average() else days.map { it.avg }.average() }

fun vitalColor(v: Vital): Color = when (v.metric) {
    Vital.HEART.metric, Vital.RESTING_HR.metric -> Area.vitals
    Vital.SPO2.metric -> Area.mind
    Vital.STRESS.metric -> Area.activity
    Vital.HRV.metric -> Area.energy
    Vital.BREATHING.metric -> Area.sleep
    else -> Area.body
}

fun vitalIcon(v: Vital): ImageVector = when (v.metric) {
    Vital.HEART.metric -> Icons.Rounded.Favorite
    Vital.SPO2.metric -> Icons.Rounded.WaterDrop
    Vital.STRESS.metric -> Icons.Rounded.Bolt
    Vital.HRV.metric -> Icons.Rounded.Timeline
    Vital.BREATHING.metric -> Icons.Rounded.Air
    Vital.SKIN_TEMP.metric -> Icons.Rounded.Thermostat
    else -> Icons.Rounded.Bedtime
}

fun levelColor(l: Level, accent: Color): Color = when (l) {
    Level.GOOD -> Area.food
    Level.NORMAL -> accent
    Level.WATCH -> Area.activity
    Level.UNRELIABLE, Level.NONE -> Color(0xFF8A93A3)
}

/** The tiles below the heart-rate card: one per vital, two per row, equal heights. */
@Composable
fun VitalsSection(band: BandViewModel, onOpen: (Vital) -> Unit) {
    val vitals by band.vitals.collectAsState()
    val loading by band.vitalsLoading.collectAsState()
    val usual = remember(vitals) { Vital.ALL.associate { it.metric to usualOf(band, it) } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("All vitals", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  Reading band", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else TextButton(onClick = band::refreshVitals) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)); Text(" Refresh")
            }
        }
        Vital.ALL.filter { it.metric != Vital.HEART.metric }.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { v -> VitalTile(v, vitals[v.metric], usual[v.metric], Modifier.weight(1f).fillMaxHeight()) { onOpen(v) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Not on this band: ECG and blood pressure. They need electrical contacts or a cuff; the Noise REP only has optical sensors.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VitalTile(v: Vital, readings: List<Pair<LocalDateTime, Double>>?, usual: Double?, modifier: Modifier, onClick: () -> Unit) {
    val accent = vitalColor(v)
    val values = readings?.map { it.second } ?: emptyList()
    val status = VitalInsights.status(v.metric, values, usual)
    val noisy = v.showMedian && VitalInsights.noisy(values)
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClickLabel = "Open ${v.name}", onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(vitalIcon(v), contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(v.name.replace(" (SpO2)", ""), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                when {
                    values.isEmpty() -> "–"
                    noisy -> "?"
                    else -> v.format(v.headline(readings!!)!!)
                },
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            )
            Text(" ${v.unit}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (values.size >= 3) MiniTrend(values.takeLast(48), accent, Modifier.fillMaxWidth().height(28.dp))
        Spacer(Modifier.weight(1f))
        if (status.label.isNotEmpty()) Tag(status.label, levelColor(status.level, accent))
        Text(
            when {
                readings == null -> "Loading…"
                values.isEmpty() -> if (v.metric in listOf(Vital.SPO2.metric, Vital.BREATHING.metric, Vital.SKIN_TEMP.metric)) "Measured overnight" else "No reading today yet"
                noisy -> "${values.size} readings, ${v.format(values.min())}–${v.format(values.max())}%: too jumpy to trust"
                v.showMedian -> "typical of ${values.size} today"
                usual != null -> "usual ${v.format(usual)} · ${readings.last().first.format(hm)}"
                else -> "latest ${readings.last().first.format(hm)}"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
        )
    }
}

/** Tiny line of today's readings for a tile. */
@Composable
private fun MiniTrend(values: List<Double>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val lo = values.min()
        val hi = values.max().let { if (it - lo < 1e-6) lo + 1 else it }
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1).coerceAtLeast(1)
            val y = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path, color.copy(alpha = 0.85f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

/** Personal read of today's vitals, against your own usual levels. */
@Composable
fun ForYouCard(band: BandViewModel, losingWeight: Boolean) {
    val vitals by band.vitals.collectAsState()
    val lines = remember(vitals) {
        VitalInsights.forYou(vitals, Vital.ALL.associate { it.metric to usualOf(band, it) }, losingWeight)
    }
    if (lines.isEmpty()) return
    Panel(accent = Area.energy) {
        PanelLabel("For you today", Icons.Rounded.AutoAwesome, Area.energy)
        lines.take(4).forEach { l ->
            Row {
                Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(Area.energy))
                Spacer(Modifier.width(10.dp))
                Text(l, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Top of Vitals: heart rate now (live when checking), today's curve, and today's range. */
@Composable
fun HeartHero(band: BandViewModel, live: com.kaya.health.band.LiveUi, enabled: Boolean) {
    val vitals by band.vitals.collectAsState()
    // Readings under 40 bpm during the day are almost always the band slipping, not your heart.
    val hrRaw = vitals[Vital.HEART.metric] ?: emptyList()
    val hr = hrRaw.filter { it.second >= 40 }
    // 5-minute medians: a calmer curve that still shows walks and rest.
    val hrSmooth = remember(hr) {
        hr.groupBy { it.first.withSecond(0).withNano(0).withMinute(it.first.minute / 5 * 5) }
            .map { (t, l) -> t to l.map { it.second }.sorted()[l.size / 2] }.sortedBy { it.first }
    }
    val resting = vitals[Vital.RESTING_HR.metric]?.minOfOrNull { it.second }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val beatMs = (60_000 / (live.bpm ?: 70).coerceIn(40, 200)) / 2
    val beat by pulse.animateFloat(1f, 1.16f, infiniteRepeatable(tween(beatMs), RepeatMode.Reverse), label = "beat")
    val showing = live.bpm ?: hr.lastOrNull()?.second?.toInt()
    Panel(accent = Area.vitals) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Area.vitals.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Favorite, contentDescription = null, tint = Area.vitals,
                    modifier = Modifier.size(34.dp).scale(if (live.running && live.bpm != null) beat else 1f),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(
                    if (live.running) "LIVE" else "HEART RATE", style = MaterialTheme.typography.labelMedium,
                    color = if (live.running) Area.vitals else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(showing?.toString() ?: "–", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                    Text(" bpm", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text(
                    when {
                        live.running && live.at != null -> "Updating every ~2 s · " + live.at.format(DateTimeFormatter.ofPattern("h:mm:ss a"))
                        live.running -> "Waiting for the band…"
                        hr.isNotEmpty() -> "Band's latest reading, " + hr.last().first.format(hm)
                        else -> "Tap Check for a live reading"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        live.note?.let { Muted(it) }
        if (live.running && live.history.size >= 2) {
            MiniTrend(live.history.map { it.toDouble() }, Area.vitals, Modifier.fillMaxWidth().height(56.dp))
            Muted("This check: ${live.history.min()}–${live.history.max()} bpm over ${live.history.size} readings")
        } else if (hr.size >= 2) {
            DayChart(hrSmooth, height = 120.dp)
            val v = hr.map { it.second }
            Row(Modifier.fillMaxWidth()) {
                Stat("lowest", "%.0f".format(v.min()), Modifier.weight(1f))
                Stat("average", "%.0f".format(v.average()), Modifier.weight(1f))
                Stat("highest", "%.0f".format(v.max()), Modifier.weight(1f))
                Stat("resting", resting?.let { "%.0f".format(it) } ?: "–", Modifier.weight(1f))
            }
        }
        if (live.running) {
            OutlinedButton(onClick = band::stopLive, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Stop") }
        } else {
            Button(
                onClick = band::startLive, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Area.vitals, contentColor = Color(0xFF2A0710)),
            ) {
                Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Check heart rate", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Full-screen detail: today's graph, another saved day, and the daily history Kaya has kept. */
@Composable
fun VitalDetail(band: BandViewModel, v: Vital, onBack: () -> Unit) {
    val vitals by band.vitals.collectAsState()
    var day by remember { mutableStateOf(LocalDate.now()) }
    val history = remember(v, vitals) { band.history(v) }
    val readings = if (day == LocalDate.now()) vitals[v.metric] ?: band.savedDay(v, day) else band.savedDay(v, day)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text(v.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        item {
            KCard {
                val last = readings.lastOrNull()
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(v.headline(readings)?.let { v.format(it) } ?: "--", fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Text(" ${v.unit}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Muted(last?.let { if (v.showMedian) "Typical (median) of ${readings.size} readings" else "Latest reading ${it.first.format(hm)}, ${it.first.toLocalDate().format(dayFmt)}" } ?: "No readings for this day")
                if (readings.isNotEmpty()) {
                    val vals = readings.map { it.second }
                    Row(Modifier.fillMaxWidth()) {
                        Stat("lowest", v.format(vals.min()), Modifier.weight(1f))
                        Stat("average", v.format(vals.average()), Modifier.weight(1f))
                        Stat("highest", v.format(vals.max()), Modifier.weight(1f))
                    }
                    DayChart(readings)
                }
                if (v.metric == com.kaya.health.band.Commands.METRIC_HEART_RATE) Muted("For a live reading, use Check heart rate at the top of Vitals.")
                if (v.metric == com.kaya.health.band.Commands.METRIC_SPO2 && readings.size >= 3 && readings.maxOf { it.second } - readings.minOf { it.second } > 12) {
                    Text(
                        "These readings jumped between ${v.format(readings.minOf { it.second })}% and ${v.format(readings.maxOf { it.second })}% within minutes. Real blood oxygen doesn't change that fast; it's typical of a wrist sensor at night (arm position, loose strap). Don't rely on a single number here.",
                        color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall,
                    )
                    Muted("To check properly: a fingertip pulse oximeter while awake and resting should read 95–100%. If it's under ~93%, or you snore loudly, wake up unrefreshed or feel sleepy in the day, ask a doctor about a home sleep test. Breathless, chest pain or bluish lips: get help right away.")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val dates = (listOf(LocalDate.now()) + history.map { it.date }).distinct().take(5)
                dates.forEach { d ->
                    FilterChip(selected = d == day, onClick = { day = d }, label = {
                        Text(if (d == LocalDate.now()) "Today" else if (d == LocalDate.now().minusDays(1)) "Yesterday" else d.format(dayFmt))
                    })
                }
            }
        }
        item {
            KCard {
                CardTitle("What it is")
                Muted(v.about)
                Muted("Normal: ${v.normal}")
                if (!v.confirmed) Muted("Kaya's reading of this band channel is worked out from the data, not published by Noise, so treat exact numbers with some caution.")
            }
        }
        item { Text("Saved history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        if (history.isEmpty()) item { Muted("Nothing saved yet. Kaya keeps a copy every time it reads the band.") }
        else {
            item { if (history.size >= 2) KCard { Muted("Daily average, oldest → newest"); Sparkline(history.reversed().map { it.avg }) } }
            items(history) { h ->
                LineItem(
                    h.date.format(fullDayFmt),
                    "${v.format(h.avg)} ${v.unit}  (${v.format(h.min)}–${v.format(h.max)})",
                )
            }
        }
    }
}

@Composable
private fun DayChart(readings: List<Pair<LocalDateTime, Double>>, height: androidx.compose.ui.unit.Dp = 160.dp) {
    if (readings.size < 2) return
    SmoothChart(
        series = listOf(Series(readings.map { it.first.toLocalTime().toSecondOfDay() / 3600.0 to it.second }, Area.vitals, fill = true, width = 2.5.dp)),
        xRange = 0.0..24.0, gapAbove = 20 / 60.0, height = height,
        inspect = { x, y -> "%d:%02d · %.0f".format(x.toInt(), ((x % 1) * 60).toInt(), y) },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Muted("12am"); Muted("6am"); Muted("12pm"); Muted("6pm"); Muted("12am")
    }
}
