package com.kaya.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.kaya.health.band.BandViewModel
import com.kaya.health.engine.Scores
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.time.LocalDate

@Composable
fun ActivityScreen(vm: AppViewModel, band: BandViewModel, go: (Dest) -> Unit) {
    val profile by vm.profile.collectAsState()
    val today by vm.today.collectAsState()
    val days by band.days.collectAsState()
    val vitals by band.vitals.collectAsState()
    LaunchedEffect(Unit) { band.refreshVitals() }

    var picked by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(LocalDate.now().toEpochDay()) }
    val date = LocalDate.ofEpochDay(picked)
    val isToday = date == LocalDate.now()
    val day = if (isToday) days[date] else remember(picked, days) { band.savedBandDay(date) }
    // A finished day: whole-day calories (and Health Connect's steps when the band's weren't saved).
    val foodUi by vm.food.collectAsState()
    val past by androidx.compose.runtime.produceState<Pair<DayBurn, Long>?>(null, picked, foodUi.entries) {
        value = if (isToday) null else vm.burnFor(date, day?.steps)
    }
    val burn = if (isToday) today.burn else past?.first
    val stepsShown = day?.steps ?: past?.second?.takeIf { it > 0 }
    val dayWord = if (isToday) "today" else "that day"
    val rec = remember(vitals, days) { band.recoveryInputs() }
    val resting = rec.restingHr ?: 70.0
    val zone = remember(day) { day?.hrReadings?.map { it.second }?.let { Scores.zoneMinutes(it, resting, profile.age) } ?: 0 }
    val effort = remember(day, zone, stepsShown) { Scores.effort(stepsShown, profile.dailyStepTarget, day?.activeMinutes, zone) }

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        card(1) {
            PageHeader("Activity", if (isToday) "Today, straight from your band" else "Saved on this phone from your band", Area.activity, Icons.AutoMirrored.Rounded.DirectionsWalk)
            DayStrip(date, Area.activity) { picked = it.toEpochDay() }
            if (!isToday && day == null) Muted(if ((past?.second ?: 0L) > 0) "Kaya has no band data saved for this day, so steps come from Health Connect." else "Nothing saved for this day.")
        }
        card(2) {
            Panel(accent = Area.activity) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ScoreRing(effort.value, Area.activity, "Effort", size = 110.dp)
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(effort.headline, style = MaterialTheme.typography.titleLarge)
                        Figure(stepsShown?.let { "%,d".format(it) } ?: "–", "steps", big = false)
                        Bar((stepsShown ?: 0).toDouble() / profile.dailyStepTarget, Area.activity)
                        Muted("Daily share of %,d a week: %,d".format(profile.weeklySteps, profile.dailyStepTarget))
                    }
                }
                effort.parts.forEach { ContributorRow(it, Area.activity) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Muted("How Effort is scored", Modifier.weight(1f))
                    InfoDot("Effort", EFFORT_INFO)
                }
            }
        }
        card(2) { WeekSteps(vm, profile.weeklySteps, days[LocalDate.now()]?.steps ?: 0L, date) { picked = it.toEpochDay() } }
        if (isToday) card(3) {
            val bodyUi by vm.body.collectAsState()
            WalksCard(band, bodyUi.latestKg ?: profile.fallbackWeightKg, profile.heightCm, resting, profile.age, profile.sex)
        }
        card(3) {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TileRow {
                    // The band fills in distance an hour at a time, so it lags after a walk; steps × stride doesn't.
                    val distM = listOfNotNull(
                        day?.distanceM?.toDouble(),
                        day?.steps?.let { it * com.kaya.health.engine.Calories.strideM(profile.heightCm, running = false) },
                    ).maxOrNull()
                    Tile("Distance", distM?.let { "%.2f".format(it / 1000.0) } ?: "–", "km", "from steps", Area.activity, null, Modifier.weight(1f))
                    Tile("Walking", day?.activeMinutes?.toString() ?: "–", "min", "on your feet", Area.activity, null, Modifier.weight(1f))
                }
                val hr = day?.hrReadings?.map { it.second }?.filter { it in 30..230 } ?: emptyList()
                TileRow {
                    Tile("Active kcal", burn?.active?.let { "%,d".format(it.toInt()) } ?: "–", "", "above resting", Area.food, null, Modifier.weight(1f))
                    Tile("Heart avg", hr.takeIf { it.isNotEmpty() }?.average()?.toInt()?.toString() ?: "–", "bpm",
                        hr.maxOrNull()?.let { "max $it $dayWord" } ?: "no readings", Area.vitals, null, Modifier.weight(1f))
                }
                val hours = day?.hourlySteps
                val nowH = java.time.LocalTime.now().hour
                val awake = if (isToday) (8..minOf(nowH - 1, 21)) else (8..21)
                val idle = hours?.let { h -> awake.count { it < h.size && h[it] < 250 } }
                val best = hours?.withIndex()?.maxByOrNull { it.value }?.takeIf { it.value > 0 }
                TileRow {
                    Tile("Still hours", idle?.toString() ?: "–", if (awake.isEmpty()) "" else "of ${awake.count()}", "under 250 steps, 8am–10pm", Area.mind, null, Modifier.weight(1f))
                    Tile("Best hour", best?.let { "%,d".format(it.value) } ?: "–", "steps", best?.let { hourName(it.index) } ?: "–", Area.activity, null, Modifier.weight(1f))
                }
            }
        }
        day?.hourlySteps?.let { hours ->
            card(4) {
                Panel {
                    val peak = hours.withIndex().maxByOrNull { it.value }
                    PanelLabel(
                        "Steps by hour", Icons.AutoMirrored.Rounded.DirectionsWalk, Area.activity,
                        peak?.takeIf { it.value > 0 }?.let { "busiest %s · %,d".format(hourName(it.index), it.value) },
                    )
                    GrowBars(hours.map { it.toDouble() }, Area.activity, height = 110.dp, highlight = if (isToday) java.time.LocalTime.now().hour.takeIf { it < hours.size } else null)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Muted("12am"); Muted("6am"); Muted("12pm"); Muted("6pm"); Muted("12am") }
                }
            }
        }
        card(5) {
            val hr = day?.hrReadings?.map { it.second } ?: emptyList()
            val maxHr = profile.maxHr
            val zones = listOf(
                Triple("Rest", 0.0, 0.3), Triple("Easy", 0.3, 0.5), Triple("Fat burn", 0.5, 0.7), Triple("Cardio", 0.7, 0.85), Triple("Peak", 0.85, 2.0),
            ).map { (n, lo, hi) ->
                val a = resting + lo * (maxHr - resting); val b = resting + hi * (maxHr - resting)
                Triple(n, "${a.toInt()}–${minOf(b, maxHr).toInt()}", hr.count { it >= a && it < b } / 2)
            }
            Panel(accent = Area.vitals) {
                PanelLabel("Heart-rate zones $dayWord", Icons.Rounded.MonitorHeart, Area.vitals,
                    info = "Zones use your heart-rate reserve (the Karvonen method): resting ${resting.toInt()} bpm and an estimated maximum of ${maxHr.toInt()} (208 − 0.7 × age, Tanaka 2001).\n\nEach zone is a share of the gap between resting and maximum: Easy 30–50%, Fat burn 50–70%, Cardio 70–85%, Peak 85%+.")
                val total = zones.sumOf { it.third }.coerceAtLeast(1)
                zones.forEach { (n, range, min) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(n, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("$range bpm", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(minutesText(min.toLong()), style = MaterialTheme.typography.labelLarge)
                    }
                    Bar(min.toDouble() / total, Area.vitals, height = 5.dp)
                }
            }
        }
        card(6) {
            val b = burn
            Panel(accent = Area.food, onClick = { go(Dest.CALORIES) }) {
                PanelLabel("Calories burned", Icons.Rounded.LocalFireDepartment, Area.food, info = CALORIE_INFO)
                Figure(b?.total?.kcal() ?: "–", if (isToday) "kcal so far" else "kcal that day")
                b?.let {
                    Muted("${it.rangeText} likely range")
                    LineItem("Resting", it.resting.kcal())
                    LineItem("Walking & workouts", it.active.kcal())
                    LineItem("Digesting food", it.digestion.kcal())
                }
            }
        }
        card(7) { OutlinedButton(onClick = { go(Dest.CALORIES) }, modifier = Modifier.fillMaxWidth()) { Text("Workouts & calorie details") } }
    }
}

@Composable
private fun HourBars(values: IntArray) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val slot = size.width / 24f
        values.forEachIndexed { h, v ->
            val hgt = size.height * v / max
            drawRoundRect(Area.activity, Offset(h * slot + slot * 0.18f, size.height - hgt), Size(slot * 0.64f, hgt), CornerRadius(6f, 6f))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Muted("12am"); Muted("6am"); Muted("12pm"); Muted("6pm"); Muted("12am")
    }
}

private fun hourName(h: Int) = when { h == 0 -> "12 am"; h < 12 -> "$h am"; h == 12 -> "12 pm"; else -> "${h - 12} pm" }

const val CALORIE_INFO = "Resting: your basal metabolic rate (Mifflin–St Jeor: 10 × weight + 6.25 × height − 5 × age + 5 for men, −161 for women), spread across the day.\n\nDigesting food: 10% of what you log, and never less than 150 kcal a day (so days you don't log food still count it).\n\nWalking: the ACSM walking equation. Oxygen used = 0.1 × speed (m/min) + 1.8 × speed × slope + 3.5, turned into calories at 5 kcal per litre of oxygen, times your weight. Speed comes from your band's steps and your stride (0.415 × height).\n\nWorkouts: the same lab equations from distance and time, or average MET values when there's no distance.\n\nHeart rate is not used to guess calories: heart-rate formulas overestimate a lot for heavier bodies and in heat or stress.\n\nThe range in brackets: no formula knows your exact burn. The resting formula is within 10% for about 3 in 4 people, and steps miss standing, fidgeting and carrying things, so the range runs a little below Kaya's number and further above it. Your weight trend against your food log is the real test."

const val EFFORT_INFO = "Effort (0–100) adds three parts:\n\n• Steps: up to 50 points for reaching today's share of your weekly step goal.\n\n• Walking minutes: up to 30 points for 30 minutes on your feet (an hour counts as walking minutes from about 100 steps a minute).\n\n• Heart-rate zone time: up to 20 points for 20 minutes with your heart rate in the fat-burn zone or above."

/** This week (Mon–Sun) against the weekly step goal, one bar per day. */
@Composable
private fun WeekSteps(vm: AppViewModel, weeklyGoal: Int, todaySteps: Long, selected: LocalDate, onPick: (LocalDate) -> Unit) {
    val body by vm.body.collectAsState()
    val today = LocalDate.now()
    val monday = com.kaya.health.engine.Discipline.mondayOf(selected)
    val byDay = body.motivation?.stepsByDay ?: emptyMap()
    val days = (0L..6L).map { monday.plusDays(it) }
    val values = days.map { d -> if (d == today) maxOf(todaySteps, byDay[d] ?: 0L) else if (d.isAfter(today)) 0L else byDay[d] ?: 0L }
    val total = values.sum()
    val left = (weeklyGoal - total).coerceAtLeast(0)
    val daysLeft = days.count { !it.isBefore(today) }
    Panel(accent = Area.activity) {
        val thisWeek = !days.last().isBefore(today)
        PanelLabel(if (thisWeek) "This week" else "Week of " + monday.format(java.time.format.DateTimeFormatter.ofPattern("d MMM")), Icons.AutoMirrored.Rounded.DirectionsWalk, Area.activity, "%,d / %,d".format(total, weeklyGoal))
        Bar(total.toDouble() / weeklyGoal, Area.activity, height = 10.dp)
        GrowBars(values.map { it.toDouble() }, Area.activity, height = 90.dp, highlight = days.indexOf(selected))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            days.forEach { d ->
                Text(d.dayOfWeek.name.take(1), Modifier.clickable(enabled = !d.isAfter(today)) { onPick(d) }.padding(4.dp),
                    style = MaterialTheme.typography.bodySmall, fontWeight = if (d == selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    color = if (d == selected) Area.activity else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!thisWeek) Muted(if (left == 0L) "Weekly goal reached." else "%,d short of the weekly goal.".format(left))
        else Muted(if (left == 0L) "Weekly goal reached. Everything from here is a bonus."
              else "%,d to go · about %,d a day for the %d day%s left".format(left, left / daysLeft.coerceAtLeast(1), daysLeft, if (daysLeft == 1) "" else "s"))
    }
}
