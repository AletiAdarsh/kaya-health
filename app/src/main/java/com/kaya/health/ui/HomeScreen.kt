package com.kaya.health.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaya.health.band.BandState
import com.kaya.health.band.BandViewModel
import com.kaya.health.band.Vital
import com.kaya.health.engine.GoalStatus
import com.kaya.health.engine.Score
import com.kaya.health.engine.Scores
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun HomeScreen(vm: AppViewModel, band: BandViewModel, go: (Dest) -> Unit) {
    val profile by vm.profile.collectAsState()
    val hc by vm.hc.collectAsState()
    val today by vm.today.collectAsState()
    val body by vm.body.collectAsState()
    val bandState by band.state.collectAsState()
    val battery by band.battery.collectAsState()
    val days by band.days.collectAsState()
    val sleep by band.sleep.collectAsState()
    val vitals by band.vitals.collectAsState()
    val live by band.live.collectAsState()
    var openScore by remember { mutableStateOf<String?>(null) }
    var sos by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        band.analyzeSleep()
        band.refreshVitals()
    }

    val day = days[LocalDate.now()]
    val rec = remember(vitals, days) { band.recoveryInputs() }
    val energy = remember(rec, sleep.result) { Scores.energy(sleep.result?.score, rec.restingHr, rec.restingBaseline, rec.hrv, rec.hrvBaseline) }
    val zone = remember(day) { day?.hrReadings?.map { it.second }?.let { Scores.zoneMinutes(it, rec.restingHr ?: 70.0, profile.age) } ?: 0 }
    val effort = remember(day, zone, profile.stepGoal) { Scores.effort(day?.steps, profile.dailyStepTarget, day?.activeMinutes, zone) }
    val sleepScore = sleep.result?.score

    val context = androidx.compose.ui.platform.LocalContext.current
    val wins = remember { CelebrationStore(context) }
    val m = body.motivation
    val target = today.targetKcal ?: body.targetKcal
    val liveSteps = day?.steps?.toLong() ?: 0L
    // Fire confetti once per real win: each whole kilo lost, a new lowest weight, a full checklist, a checkpoint reached.
    val celebrate = remember(m, liveSteps) {
        if (m == null) null else {
            val d = LocalDate.now()
            val keys = buildList {
                val kg = kotlin.math.floor(m.lostKg).toInt()
                if (kg >= 1) add("kg-$kg")
                if (m.newLowToday) add("low-$d")
                if (checklist(m, profile, target, liveSteps).all) add("list-$d")
                m.checkpoint?.takeIf { it.reached }?.let { add("cp-${it.name}-${it.kg}") }
            }
            keys.firstOrNull { wins.firstTime(it) }
        }
    }

    val hour = LocalTime.now().hour
    val greeting = when { hour < 5 -> "Still up?"; hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }

    if (sos) CravingSos(
        weightKg = body.latestKg ?: today.weightKg.takeIf { it > 0 } ?: 70.0, heightCm = 0.0,
        targetKcal = target, eatenToday = today.eatenKcal, currentKg = body.latestKg,
        onLogFood = { f -> vm.addFood(f.name, f.kcal, f.protein, AppViewModel.mealTypeNow(), f.carbs, f.fat, f.fiber) },
        onClose = { sos = false },
    )

    androidx.compose.foundation.layout.Box {
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        card(1) {
            PageHeader(greeting, LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), Area.energy) {
                when (bandState) {
                    is BandState.Connected -> Tag("Band ${battery?.percent?.let { "$it%" } ?: "on"}", Area.energy)
                    is BandState.Connecting -> Tag("Band…", Area.mind)
                    else -> androidx.compose.material3.TextButton(onClick = band::connectAndSync) { Text("Connect band", color = Area.vitals) }
                }
            }
        }

        // ---- keep going: today's line, checkpoint countdown, checklist, streaks

        // ---- the three scores
        card(2) {
            Panel(accent = Area.energy) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ScoreRing(energy.value, Area.energy, "Energy", size = 78.dp, stroke = 8.dp, onClick = { openScore = if (openScore == "energy") null else "energy" })
                    ScoreRing(sleepScore, Area.sleep, "Sleep", size = 78.dp, stroke = 8.dp, onClick = { go(Dest.SLEEP) })
                    ScoreRing(effort.value, Area.activity, "Effort", size = 78.dp, stroke = 8.dp, onClick = { openScore = if (openScore == "effort") null else "effort" })
                }
                Text(
                    "${energy.headline}. " + when {
                        energy.value >= 70 -> "Good day for a longer walk."
                        energy.value >= 45 -> "Normal day: keep moving, nothing extreme."
                        else -> "Go easy today and aim for an early night."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                AnimatedVisibility(openScore != null) {
                    val s: Score = if (openScore == "effort") effort else energy
                    val accent = if (openScore == "effort") Area.activity else Area.energy
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (openScore == "effort") "Effort ${s.value}: ${s.headline}" else "Energy ${s.value}: ${s.headline}", style = MaterialTheme.typography.titleMedium)
                        s.parts.forEach { ContributorRow(it, accent) }
                    }
                }
                Muted("Tap Energy or Effort to see how it's scored, or Sleep to open last night.")
            }
        }

        // Slim motivation strip under the scores (tap to expand), so the scores stay at the top.
        if (m != null && profile.showMotivation) card(3) { KeepGoingCard(m, body.plan, profile, target, liveSteps) { go(Dest.BODY) } }

        if (!hc.connected) card(3) {
            Panel(accent = Area.food, onClick = { go(Dest.CALORIES) }) {
                PanelLabel("Finish setup", Icons.Rounded.Restaurant, Area.food)
                Text("Connect Health Connect so Kaya can save your food and weigh-ins.", style = MaterialTheme.typography.bodyLarge)
            }
        }

        // ---- calories today
        // ---- nutrition: calories, protein, meals, and Add food right here
        card(4) { NutritionCard(vm, target, today.burn?.total) { go(Dest.FOOD) } }
        card(4) { MealIdeasCard(vm, target) }
        card(4) { WaterCard(body.latestKg ?: profile.fallbackWeightKg, profile.waterGoalGlasses) }
        card(4) { DisciplineStrip(onSos = { sos = true }, onOpen = { go(Dest.DISCIPLINE) }) }

        // ---- weight goal
        card(5) {
            val plan = body.plan
            Panel(accent = Area.body, onClick = { go(Dest.BODY) }) {
                PanelLabel("Weight goal", Icons.Rounded.MonitorWeight, Area.body, plan?.let { statusLabel(it.status) })
                if (plan == null) {
                    Text("Set a goal weight to get a daily calorie target and an expected date.", style = MaterialTheme.typography.bodyLarge)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Figure("%.1f".format(plan.currentKg), "kg")
                        Text("  →  %.1f kg".format(plan.goalKg), style = MaterialTheme.typography.titleLarge, color = Area.body, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    Bar(plan.progress, Area.body)
                    Text(
                        "Your date ${plan.plannedDate.format(DateTimeFormatter.ofPattern("d MMM"))} · realistic ${(plan.dateAtTrend ?: plan.dateAtPlan).format(DateTimeFormatter.ofPattern("d MMM"))} · " +
                            "%.1f kg to go".format((plan.currentKg - plan.goalKg).coerceAtLeast(0.0)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- tiles
        card(6) {
            TileRow {
                val hrLast = live.bpm?.toString() ?: day?.hrReadings?.lastOrNull()?.second?.toString() ?: "–"
                Tile("Heart", hrLast, "bpm", if (live.running) "live" else "latest", Area.vitals, Icons.Rounded.Favorite, Modifier.weight(1f)) { go(Dest.VITALS) }
                val stress = vitals[Vital.STRESS.metric]?.lastOrNull()?.second
                Tile("Stress", stress?.roundToInt()?.toString() ?: "–", "/100", stressWord(stress), Area.mind, Icons.Rounded.SelfImprovement, Modifier.weight(1f)) { go(Dest.MIND) }
            }
        }
        card(7) {
            TileRow {
                Tile(
                    "Steps", day?.steps?.let { "%,d".format(it) } ?: "–", "", "goal %,d today".format(profile.dailyStepTarget),
                    Area.activity, Icons.AutoMirrored.Rounded.DirectionsWalk, Modifier.weight(1f),
                ) { go(Dest.ACTIVITY) }
                val r = sleep.result
                Tile(
                    "Sleep", r?.let { minutesText(it.asleepMin.toLong()) } ?: "–", "",
                    sleep.napMinutes.takeIf { it > 0 }?.let { "+ ${minutesText(it)} nap" }
                        ?: r?.let { "${it.bedtime.toLocalTime().format(DateTimeFormatter.ofPattern("h:mm"))}–${it.wake.toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a"))}" }
                        ?: "tap to analyse",
                    Area.sleep, Icons.Rounded.Bedtime, Modifier.weight(1f),
                ) { go(Dest.SLEEP) }
            }
        }

        // ---- mind nudge
        card(8) {
            val stress = vitals[Vital.STRESS.metric]?.takeLast(6)?.map { it.second }?.average()
            Panel(accent = Area.mind, onClick = { go(Dest.MIND) }) {
                PanelLabel("Mind", Icons.Rounded.SelfImprovement, Area.mind)
                Text(
                    if (stress != null && stress >= 50) "Your stress has been high lately (${stress.roundToInt()}). Two minutes of slow breathing can bring it down."
                    else "Two minutes of slow breathing, with your heart rate live from the band.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(onClick = { go(Dest.MIND) }) { Text("Start breathing") }
            }
        }

        card(9) {
            Panel(onClick = { go(Dest.SETTINGS) }) {
                PanelLabel("Band", Icons.Rounded.Watch, Area.energy, battery?.let { "${it.percent}%" + if (it.charging) " charging" else "" })
                Text(
                    when (val s = bandState) {
                        is BandState.Connected -> "Connected directly over Bluetooth"
                        is BandState.Connecting -> "Connecting…"
                        is BandState.Failed -> s.reason
                        BandState.Idle -> "Not connected: tap to connect"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    if (profile.confetti) Confetti(celebrate)
    }
}

fun statusLabel(s: GoalStatus) = when (s) {
    GoalStatus.DONE -> "Reached!"
    GoalStatus.AHEAD -> "Ahead of plan"
    GoalStatus.ON_TRACK -> "On track"
    GoalStatus.BEHIND -> "Behind plan"
    GoalStatus.NOT_ENOUGH_DATA -> "Needs weigh-ins"
}

fun stressWord(v: Double?) = when {
    v == null -> "no reading"
    v < 30 -> "relaxed"
    v < 60 -> "normal"
    v < 80 -> "high"
    else -> "very high"
}
