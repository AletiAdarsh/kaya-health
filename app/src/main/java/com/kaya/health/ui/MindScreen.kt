package com.kaya.health.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.kaya.health.band.BandViewModel
import com.kaya.health.band.Vital
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class MindSession(val at: LocalDateTime, val minutes: Int, val hrStart: Int?, val hrEnd: Int?)

/** Breathing sessions, kept on the phone. */
class MindStore(context: Context) {
    private val prefs = context.getSharedPreferences("kaya_mind", Context.MODE_PRIVATE)
    fun load(): List<MindSession> = runCatching {
        val a = JSONArray(prefs.getString("sessions", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            MindSession(LocalDateTime.parse(o.getString("at")), o.getInt("min"), o.optInt("hr0", -1).takeIf { it > 0 }, o.optInt("hr1", -1).takeIf { it > 0 })
        }
    }.getOrDefault(emptyList())
    fun add(s: MindSession) {
        val all = (load() + s).takeLast(200)
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("at", it.at.toString()).put("min", it.minutes).put("hr0", it.hrStart ?: -1).put("hr1", it.hrEnd ?: -1)) }
        prefs.edit { putString("sessions", a.toString()) }
    }
}

private data class Phase(val name: String, val seconds: Int, val from: Float, val to: Float)

private data class Pattern(val name: String, val blurb: String, val phases: List<Phase>)

/** Breathing patterns. All slow the breath to ~4–6 a minute, which calms the heart; they differ in rhythm. */
private val PATTERNS = listOf(
    Pattern("Calm", "In 4 · hold 2 · out 6. Long out-breaths slow the heart.",
        listOf(Phase("Breathe in", 4, 0.45f, 1f), Phase("Hold", 2, 1f, 1f), Phase("Breathe out", 6, 1f, 0.45f))),
    Pattern("Box", "In 4 · hold 4 · out 4 · hold 4. Steadies you under pressure.",
        listOf(Phase("Breathe in", 4, 0.45f, 1f), Phase("Hold", 4, 1f, 1f), Phase("Breathe out", 4, 1f, 0.45f), Phase("Hold", 4, 0.45f, 0.45f))),
    Pattern("Sleep", "In 4 · hold 7 · out 8. Winding down before bed.",
        listOf(Phase("Breathe in", 4, 0.45f, 1f), Phase("Hold", 7, 1f, 1f), Phase("Breathe out", 8, 1f, 0.45f))),
    Pattern("Coherent", "In 5 · out 5. Six breaths a minute, an even rhythm.",
        listOf(Phase("Breathe in", 5, 0.45f, 1f), Phase("Breathe out", 5, 1f, 0.45f))),
)

private val GROUNDING = listOf(
    "5" to "things you can SEE. Name them slowly.",
    "4" to "things you can FEEL: your feet, the chair, your clothes, the air.",
    "3" to "things you can HEAR.",
    "2" to "things you can SMELL (or two smells you like).",
    "1" to "thing you can TASTE, or one slow sip of water.",
)

@Composable
fun MindScreen(band: BandViewModel) {
    val context = LocalContext.current
    val store = remember { MindStore(context) }
    var sessions by remember { mutableStateOf(store.load()) }
    val live by band.live.collectAsState()
    val vitals by band.vitals.collectAsState()
    var minutes by remember { mutableIntStateOf(2) }
    var patternIdx by remember { mutableIntStateOf(0) }
    val PATTERN = PATTERNS[patternIdx].phases
    var running by remember { mutableStateOf(false) }
    var phaseIdx by remember { mutableIntStateOf(0) }
    var phaseTick by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(0) }
    var hrStart by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        band.startLive()
        delay(3000)
        hrStart = band.live.value.bpm
        left = minutes * 60
        phaseIdx = 0; phaseTick = 0
        while (left > 0) {
            val p = PATTERN[phaseIdx]
            phaseTick++
            if (phaseTick >= p.seconds) { phaseTick = 0; phaseIdx = (phaseIdx + 1) % PATTERN.size }
            delay(1000); left--
        }
        val end = band.live.value.bpm
        store.add(MindSession(LocalDateTime.now(), minutes, hrStart, end))
        sessions = store.load()
        band.stopLive()
        running = false
    }

    val p = PATTERN[phaseIdx]
    val target = if (!running) 0.6f else p.from + (p.to - p.from) * ((phaseTick + 1f) / p.seconds)
    val scale by animateFloatAsState(target, tween(if (running) 1000 else 600), label = "breath")

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        card(1) { PageHeader("Mind", "Breathe, ground, calm down", Area.mind, Icons.Rounded.SelfImprovement) }
        card(2) {
            Panel(accent = Area.mind) {
                Box(Modifier.fillMaxWidth().size(240.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(240.dp)) {
                        val r = size.minDimension / 2 * scale
                        drawCircle(Brush.radialGradient(listOf(Area.mind.copy(alpha = 0.55f), Area.mind.copy(alpha = 0.05f)), center, r), r)
                        drawCircle(Area.mind.copy(alpha = 0.35f), r, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    }
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (running) p.name else "Ready", style = MaterialTheme.typography.headlineSmall)
                        if (running) Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat("heart rate now", live.bpm?.toString() ?: "–")
                    Stat("at start", hrStart?.toString() ?: "–")
                }
                if (!running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PATTERNS.forEachIndexed { i, pt -> FilterChip(selected = patternIdx == i, onClick = { patternIdx = i; phaseIdx = 0 }, label = { Text(pt.name) }) }
                    }
                    Muted(PATTERNS[patternIdx].blurb)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 5, 10).forEach { m -> FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") }) }
                    }
                    Button(
                        onClick = { running = true }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Area.mind),
                    ) { Text("Start") }
                } else {
                    OutlinedButton(onClick = { running = false; band.stopLive() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                }
            }
        }
        card(3) {
            val stress = vitals[Vital.STRESS.metric]
            Panel {
                PanelLabel("Stress today", null, Area.mind, stress?.lastOrNull()?.second?.let { "latest ${it.roundToInt()} · ${stressWord(it)}" },
                    info = "Stress (0–100) is your band's own reading, worked out from heart-rate variability: the smaller the beat-to-beat variation, the higher the score. Under 30 relaxed, 30–59 normal, 60–79 high, 80+ very high.\n\nIt also rises with exercise, caffeine, heat and poor sleep, so a single high reading isn't a problem; long stretches above 60 are worth a breathing break.")
                stress?.takeIf { it.size >= 2 }?.let { st ->
                    val v = st.map { it.second }
                    val calm = st.groupBy { it.first.hour }.mapValues { (_, l) -> l.map { it.second }.average() }.minByOrNull { it.value }
                    Row(Modifier.fillMaxWidth()) {
                        Stat("average", "${v.average().roundToInt()}", Modifier.weight(1f))
                        Stat("high (60+)", minutesText((v.count { it >= 60 } * 5).toLong()), Modifier.weight(1f))
                        Stat("calmest", calm?.key?.let { h -> if (h == 0) "12 am" else if (h < 12) "$h am" else if (h == 12) "12 pm" else "${h - 12} pm" } ?: "–", Modifier.weight(1f))
                    }
                }
                if (stress != null && stress.size >= 2) {
                    SmoothChart(
                        series = listOf(Series(stress.map { it.first.toLocalTime().toSecondOfDay() / 3600.0 to it.second }, Area.mind, fill = true, width = 2.dp)),
                        xRange = 0.0..24.0, gapAbove = 30 / 60.0, height = 120.dp,
                        yLines = listOf(60.0 to Area.activity),
                        inspect = { x, y -> "%d:%02d · %.0f".format(x.toInt(), ((x % 1) * 60).toInt(), y) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Muted("12am"); Muted("6am"); Muted("12pm"); Muted("6pm"); Muted("12am") }
                } else Muted("No stress readings yet today.")
            }
        }
        card(3) { GroundingCard() }
        card(4) {
            val week = sessions.filter { !it.at.toLocalDate().isBefore(LocalDate.now().minusDays(6)) }
            TileRow {
                Tile("This week", "${week.sumOf { it.minutes }}", "min", "${week.size} sessions", Area.mind, null, Modifier.weight(1f))
                val drops = sessions.mapNotNull { s -> if (s.hrStart != null && s.hrEnd != null) s.hrStart - s.hrEnd else null }
                Tile("Heart rate drop", drops.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.toString() ?: "–", "bpm", "average per session", Area.mind, null, Modifier.weight(1f))
            }
        }
        if (sessions.isNotEmpty()) {
            card(5) { Text("Sessions", style = MaterialTheme.typography.titleMedium) }
            items(sessions.reversed().take(20)) { s ->
                LineItem(
                    s.at.format(DateTimeFormatter.ofPattern("d MMM, h:mm a")) + " · ${s.minutes} min",
                    if (s.hrStart != null && s.hrEnd != null) "${s.hrStart} → ${s.hrEnd} bpm" else "–",
                )
            }
        }
    }
}

/** 5-4-3-2-1 grounding: a step-by-step way out of a stress or anxiety spike, no band needed. */
@Composable
private fun GroundingCard() {
    var step by remember { mutableIntStateOf(-1) }
    Panel(accent = Area.mind) {
        PanelLabel("5-4-3-2-1 grounding", null, Area.mind,
            info = "A grounding exercise used in anxiety and panic care: naming what your senses notice pulls attention out of racing thoughts and back to the room. It takes about two minutes.")
        if (step < 0) {
            Muted("For racing thoughts, anxiety or a craving spiral. Two minutes, eyes open.")
            OutlinedButton(onClick = { step = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Start") }
        } else if (step < GROUNDING.size) {
            val (n, what) = GROUNDING[step]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, style = MaterialTheme.typography.displayMedium, color = Area.mind)
                Text("   $what", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Bar((step + 1) / GROUNDING.size.toDouble(), Area.mind, height = 4.dp)
            Button(onClick = { step++ }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Area.mind)) {
                Text(if (step == GROUNDING.size - 1) "Done" else "Next")
            }
        } else {
            Text("Well done. Notice your breathing now: slower?", style = MaterialTheme.typography.titleMedium)
            androidx.compose.material3.TextButton(onClick = { step = -1 }) { Text("Again") }
        }
    }
}
