package com.kaya.health.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kaya.health.data.WeightEntry
import com.kaya.health.engine.GoalPlan
import com.kaya.health.engine.GoalPlanner
import com.kaya.health.engine.GoalSettings
import com.kaya.health.engine.GoalStatus
import com.kaya.health.engine.Profile
import com.kaya.health.engine.TrendPoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val longDate = DateTimeFormatter.ofPattern("d MMM yyyy")
private val shortDate = DateTimeFormatter.ofPattern("d MMM")

@Composable
fun BodyScreen(vm: AppViewModel, band: com.kaya.health.band.BandViewModel) {
    val ui by vm.body.collectAsState()
    val hc by vm.hc.collectAsState()
    val profile by vm.profile.collectAsState()
    var addOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    val plan = ui.plan
    var i = 0

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeader("Body", "Your weight goal, re-planned every day", Area.body, Icons.Rounded.MonitorWeight) }

        if (plan == null) {
            item {
                Panel(Modifier.enter(1), accent = Area.body) {
                    PanelLabel("Start your plan", Icons.Rounded.Flag, Area.body)
                    Text("Set a goal weight and a pace. Kaya turns it into a daily calorie target and a date, and re-plans every day from what you actually eat and weigh.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { editOpen = true }, colors = ButtonDefaults.buttonColors(containerColor = Area.body, contentColor = Color(0xFF2A1C00)), modifier = Modifier.fillMaxWidth()) { Text("Set goal") }
                }
            }
        } else {
            item { Hero(plan, Modifier.enter(++i), onEdit = { editOpen = true }, onLog = { addOpen = true }, canLog = hc.connected) }
            item { WeightNotesCard(ui.trend, losing = !plan.gaining, modifier = Modifier.enter(++i)) }
            item { FitnessAgeCard(vm, band, Modifier.enter(++i)) }
            ui.motivation?.checkpoint?.let { cp -> item { CheckpointCard(cp, Modifier.enter(++i)) } }
            item { Journey(plan, ui.trend, profile, Modifier.enter(++i)) }
            item { TargetCard(plan, Modifier.enter(++i)) }
            item { Milestones(plan, Modifier.enter(++i)) }
            item { PlanCheck(plan, Modifier.enter(++i)) }
            item { NumbersGrid(plan, Modifier.enter(++i)) }
        }

        item {
            Panel(Modifier.enter(++i)) {
                PanelLabel("Weigh-ins", Icons.Rounded.Timeline, Area.body, ui.latestKg?.let { "latest %.1f kg".format(it) })
                if (!hc.connected) Muted("Connect Health Connect in Settings to save weigh-ins.")
                ui.recent.take(6).forEach { WeighRow(it, vm) }
                if (ui.recent.isEmpty()) Muted("No weigh-ins yet. Morning weigh-ins (after the toilet, before food) give the cleanest trend.")
                FilledTonalButton(onClick = { addOpen = true }, enabled = hc.connected, modifier = Modifier.fillMaxWidth()) { Text("Log today's weight") }
            }
        }
        item { WaistCard(Modifier.enter(++i)) }
        item { PhotosCard(Modifier.enter(++i)) }
        ui.estimate?.let { est ->
            item {
                Panel(Modifier.enter(++i)) {
                    PanelLabel("How your burn is worked out", null, Area.energy, "trust ${(est.confidence * 100).roundToInt()}%")
                    Muted(est.note)
                }
            }
        }
    }

    if (addOpen) AddWeightDialog(ui.latestKg ?: profile.fallbackWeightKg, onDismiss = { addOpen = false }) { vm.addWeight(it); addOpen = false }
    if (editOpen) GoalSheet(profile, plan, ui.latestKg ?: profile.fallbackWeightKg, onDismiss = { editOpen = false }) { goal, rate ->
        vm.saveProfile(profile.copy(goalWeightKg = goal, rateKgPerWeek = rate)); editOpen = false
    }
}

// ---------------------------------------------------------------- hero

@Composable
private fun Hero(p: GoalPlan, modifier: Modifier, onEdit: () -> Unit, onLog: () -> Unit, canLog: Boolean) {
    Panel(modifier, accent = Area.body) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NOW", style = MaterialTheme.typography.labelMedium, color = Area.body)
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUp((p.currentKg * 10).roundToInt(), MaterialTheme.typography.displayLarge) { "%.1f".format(it / 10.0) }
                    Text(" kg", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                }
                Text("Goal %.1f kg".format(p.goalKg), style = MaterialTheme.typography.titleMedium, color = Area.body)
            }
            ScoreRing((p.progress * 100).roundToInt(), Area.body, "% there", size = 92.dp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val done = if (p.gaining) p.currentKg - p.startKg else p.startKg - p.currentKg
            MiniStat(if (p.gaining) "gained" else "lost", "%.1f kg".format(done.coerceAtLeast(0.0)), Modifier.weight(1f))
            MiniStat("to go", "%.1f kg".format(kotlin.math.abs(p.goalKg - p.currentKg).let { if (p.status == com.kaya.health.engine.GoalStatus.DONE) 0.0 else it }), Modifier.weight(1f))
            MiniStat("on plan today", "%.1f kg".format(p.planKgToday), Modifier.weight(1f))
        }
        // Your date (what you're aiming for) next to the realistic one (what the numbers say right now).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateCard("YOUR DATE", p.plannedDate.format(shortDate), "${p.daysToPlanned.coerceAtLeast(0)} days · %.1f kg/wk".format(p.requestedRateKg), Area.body, Modifier.weight(1f))
            val realistic = p.dateAtTrend ?: p.dateAtPlan
            DateCard(
                "REALISTIC", realistic.format(shortDate),
                if (p.dateAtTrend != null) "from your weigh-ins" else "at %.2f kg/wk now".format(p.achievableRateKg),
                Area.energy, Modifier.weight(1f),
            )
        }
        if (p.currentKg > p.goalKg) {
            val diff = p.currentKg - p.planKgToday
            Muted(
                when {
                    diff <= 0.05 -> "You're on or ahead of your plan line. Keep going."
                    else -> "%.1f kg behind your plan line today.".format(diff)
                }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(statusLabel(p.status), statusColor(p.status))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Edit goal") }
        }
        Button(
            onClick = onLog, enabled = canLog, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Area.body, contentColor = Color(0xFF2A1C00)),
        ) { Text("Log today's weight") }
    }
}

@Composable
private fun CheckpointCard(cp: com.kaya.health.engine.CheckpointView, modifier: Modifier) {
    Panel(modifier, accent = Area.energy) {
        PanelLabel(cp.name, Icons.Rounded.Flag, Area.energy, cp.date.format(longDate))
        Row(verticalAlignment = Alignment.Bottom) {
            if (cp.reached) Text("Reached ✓", style = MaterialTheme.typography.displaySmall, color = Area.food)
            else {
                CountUp(cp.daysLeft.toInt().coerceAtLeast(0), MaterialTheme.typography.displayMedium, Area.energy) { "$it" }
                Text(" days to %.0f kg".format(cp.kg), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        Bar(cp.progress, Area.energy)
        if (!cp.reached) Muted(
            "At your current pace you'll be around %.1f kg on ${cp.date.format(shortDate)}".format(cp.projectedKg) +
                (if (cp.onTrack) ", on track. " else if (cp.close) ", within reach: %.1f kg a week from here makes it. ".format(cp.neededKgPerWeek) else ". It needs %.1f kg a week from here. ".format(cp.neededKgPerWeek)) +
                "The first couple of weeks usually drop 1–3 kg extra from water, so the scale may beat this."
        )
    }
}

@Composable
private fun DateCard(label: String, date: String, sub: String, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.12f)).padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        Text(date, style = MaterialTheme.typography.headlineSmall)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun statusColor(s: GoalStatus) = when (s) {
    GoalStatus.BEHIND -> Area.vitals
    GoalStatus.NOT_ENOUGH_DATA -> Area.mind
    else -> Area.food
}

// ---------------------------------------------------------------- journey chart

@Composable
private fun Journey(p: GoalPlan, trend: List<TrendPoint>, profile: Profile, modifier: Modifier) {
    var range by remember { mutableStateOf("All") }
    val start = profile.goalStartDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
    val today = LocalDate.now()
    val endAll = maxOf(p.plannedDate, p.dateAtTrend ?: p.dateAtPlan, today.plusDays(14))
    val from = when (range) { "1M" -> today.minusDays(14); "3M" -> today.minusDays(30); else -> minOf(start, trend.firstOrNull()?.date ?: start) }
    val to = when (range) { "1M" -> today.plusDays(16); "3M" -> today.plusDays(60); else -> endAll }
    fun d(x: LocalDate) = x.toEpochDay().toDouble()
    val proj = p.dateAtTrend ?: p.dateAtPlan
    Panel(modifier.animateContentSize()) {
        PanelLabel("Journey", Icons.Rounded.Insights, Area.body)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1M", "3M", "All").forEach { r ->
                FilterChip(
                    selected = range == r, onClick = { range = r }, label = { Text(r) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Area.body.copy(alpha = 0.2f), selectedLabelColor = Area.body),
                )
            }
        }
        val trendPts = trend.map { d(it.date) to it.trend }.ifEmpty { listOf(d(today) to p.currentKg) } + listOf(d(today) to p.currentKg)
        SmoothChart(
            series = listOf(
                Series(trendPts.distinctBy { it.first }, Area.body, fill = true, width = 4.dp, label = "you"),
                Series(listOf(d(start) to p.startKg, d(p.plannedDate) to p.goalKg), Area.body.copy(alpha = 0.55f), dashed = true, width = 2.dp),
                Series(listOf(d(today) to p.currentKg, d(proj) to p.goalKg), Area.energy, dashed = true, width = 3.dp),
                Series(trend.mapNotNull { t -> t.raw?.let { d(t.date) to it } }, Color.White.copy(alpha = 0.0f), dots = true),
            ),
            yLines = listOf(p.goalKg to Area.food),
            xRange = d(from)..d(to),
            height = 210.dp,
            inspect = { x, y -> "${LocalDate.ofEpochDay(x.toLong()).format(shortDate)} · %.1f kg".format(y) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(from.format(shortDate)); Muted(to.format(shortDate))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(Area.body, "your weight"); Legend(Area.body.copy(alpha = 0.55f), "the plan"); Legend(Area.energy, "your pace"); Legend(Area.food, "goal")
        }
        Muted("Drag across the chart to read values.")
    }
}

@Composable
private fun Legend(c: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- target

@Composable
private fun TargetCard(p: GoalPlan, modifier: Modifier) {
    Panel(modifier, accent = Area.food) {
        PanelLabel("Eat today", Icons.Rounded.Restaurant, Area.food)
        Row(verticalAlignment = Alignment.Bottom) {
            CountUp(p.dailyTarget.roundToInt(), MaterialTheme.typography.displayMedium)
            Text(" kcal", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
        // burn split into "eat" and "deficit"
        val eat = (minOf(p.dailyTarget, p.tdee) / maxOf(p.dailyTarget, p.tdee)).toFloat().coerceIn(0f, 1f)
        Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape)) {
            Box(Modifier.weight(eat.coerceAtLeast(0.01f)).height(14.dp).background(Area.food))
            Box(Modifier.weight((1 - eat).coerceAtLeast(0.01f)).height(14.dp).background(Area.body.copy(alpha = 0.55f)))
        }
        Row(Modifier.fillMaxWidth()) {
            Legend(Area.food, "eat ${p.dailyTarget.kcal()}"); Spacer(Modifier.weight(1f)); Legend(Area.body.copy(alpha = 0.55f), (if (p.gaining) "surplus " else "deficit ") + kotlin.math.abs(p.tdee - p.dailyTarget).kcal())
        }
        Muted(
            if (p.gaining) "You burn about ${p.tdee.kcal()} kcal a day, so eating ${p.dailyTarget.kcal()} puts you ${(p.dailyTarget - p.tdee).kcal()} over: " +
                "about %.2f kg a week. Strength training makes that mostly muscle.".format((p.dailyTarget - p.tdee) * 7 / 7700)
            else "You burn about ${p.tdee.kcal()} kcal a day, so eating ${p.dailyTarget.kcal()} puts you ${(p.tdee - p.dailyTarget).kcal()} under: " +
                "about %.2f kg a week. ".format((p.tdee - p.dailyTarget) * 7 / 7700) +
                "Every extra 1,000 steps a day brings your goal about ${p.daysSoonerPer1000Steps.roundToInt()} days sooner."
        )
    }
}

// ---------------------------------------------------------------- milestones

@Composable
private fun Milestones(p: GoalPlan, modifier: Modifier) {
    val dir = if (p.gaining) 1.0 else -1.0
    val rate = ((p.trendKgPerWeek ?: 0.0) * dir).takeIf { it > 0.05 } ?: p.achievableRateKg.coerceAtLeast(0.05)
    val marks = buildList {
        if (p.gaining) {
            var m = kotlin.math.ceil(p.startKg / 5) * 5
            if (m <= p.startKg) m += 5
            while (m < p.goalKg - 0.01) { add(m); m += 5 }
        } else {
            var m = floor(p.startKg / 5) * 5
            if (m >= p.startKg) m -= 5
            while (m > p.goalKg + 0.01) { add(m); m -= 5 }
        }
        add(p.goalKg)
    }
    Panel(modifier) {
        PanelLabel("Milestones", Icons.Rounded.Flag, Area.body, "your plan · realistic")
        marks.forEachIndexed { idx, m ->
            val reached = if (p.gaining) p.currentKg >= m else p.currentKg <= m
            val date = if (reached) null else LocalDate.now().plusDays(ceil(kotlin.math.abs(p.currentKg - m) / rate * 7).toLong())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(26.dp)) {
                    Box(
                        Modifier.size(16.dp).clip(CircleShape).background(if (reached) Area.food else if (m == p.goalKg) Area.body else MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    if (idx < marks.lastIndex) Box(Modifier.width(2.dp).height(28.dp).background(MaterialTheme.colorScheme.outline))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(bottom = if (idx < marks.lastIndex) 14.dp else 0.dp)) {
                    Text("%.0f kg".format(m) + if (m == p.goalKg) "  ·  goal" else "", style = MaterialTheme.typography.titleMedium, color = if (m == p.goalKg) Area.body else MaterialTheme.colorScheme.onSurface)
                    if (reached) Text("reached ✓", style = MaterialTheme.typography.bodySmall, color = Area.food)
                    else Row {
                        val mine = p.plannedDate.minusDays(ceil(kotlin.math.abs(m - p.goalKg) / p.requestedRateKg * 7).toLong())
                        Text(mine.format(shortDate), style = MaterialTheme.typography.bodyMedium, color = Area.body)
                        Text("  ·  ${date!!.format(shortDate)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- verdicts + numbers

@Composable
private fun PlanCheck(p: GoalPlan, modifier: Modifier) {
    Panel(modifier) {
        PanelLabel("Reality check", Icons.Rounded.Insights, Area.body)
        p.notes.forEach { n ->
            val warn = listOf("aggressive", "faster", "more than is safe", "under ", "going up", "over your burn").any { n.contains(it) }
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    if (warn) Icons.Rounded.WarningAmber else Icons.Rounded.CheckCircle, null,
                    tint = if (warn) Area.body else Area.food, modifier = Modifier.size(20.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(n, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun NumbersGrid(p: GoalPlan, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("BMI", "%.1f".format(p.bmi), "", "${GoalPlanner.bmiLabel(p.bmi)} · %.1f at goal".format(p.goalBmi), Area.body, null, Modifier.weight(1f))
            Tile("Burn (TDEE)", p.tdee.kcal(), "kcal", "all-day total", Area.energy, null, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("BMR", p.bmr.kcal(), "kcal", "body at complete rest", Area.mind, null, Modifier.weight(1f))
            Tile("Safe pace", "%.2f".format(p.safeRateKg), "kg/wk", "fastest now: %.2f".format(p.achievableRateKg), Area.food, null, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WeighRow(w: WeightEntry, vm: AppViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("%.1f kg".format(w.kg), style = MaterialTheme.typography.titleMedium)
            Muted("${w.time.day()} · ${w.time.clock()}" + if (!w.own) " · ${vm.friendly(w.source)}" else "")
        }
        if (w.own) IconButton(onClick = { vm.deleteWeight(w) }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// ---------------------------------------------------------------- edit goal sheet with live preview

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GoalSheet(profile: Profile, plan: GoalPlan?, currentKg: Double, onDismiss: () -> Unit, onSave: (Double, Double) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var goalText by remember { mutableStateOf(profile.goalWeightKg?.let { "%.1f".format(it) } ?: "") }
    var rate by remember { mutableStateOf(profile.rateKgPerWeek) }
    val goal = goalText.toDoubleOrNull()?.takeIf { it in 35.0..250.0 && kotlin.math.abs(it - currentKg) >= 0.5 }
    val tdee = plan?.tdee ?: (com.kaya.health.engine.Calories.bmr(currentKg, profile.heightCm, profile.age, profile.sex) * 1.2)
    val preview = goal?.let {
        GoalPlanner.plan(profile, GoalSettings(it, rate, LocalDate.now(), currentKg), currentKg, tdee, null, null, LocalDate.now())
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Your goal", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                goalText, { goalText = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                label = { Text("Goal weight (kg)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = goalText.isNotBlank() && goal == null,
                supportingText = { if (goalText.isNotBlank() && goal == null) Text("Enter a weight at least 0.5 kg away from your current %.1f kg".format(currentKg)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Text("Pace", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { r ->
                    val fast = r > currentKg * 0.01
                    FilterChip(
                        selected = rate == r, onClick = { rate = r }, label = { Text("$r kg" + if (fast) " ⚠" else "") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = (if (fast) Area.vitals else Area.body).copy(alpha = 0.22f)),
                    )
                }
            }
            Panel(accent = Area.body) {
                if (preview == null) Muted("Enter a goal to see your daily target and date.")
                else {
                    Row {
                        Column(Modifier.weight(1f)) { Text("Eat", style = MaterialTheme.typography.labelMedium, color = Area.food); Text("${preview.dailyTarget.kcal()} kcal", style = MaterialTheme.typography.titleLarge) }
                        Column(Modifier.weight(1f)) { Text("Reach goal", style = MaterialTheme.typography.labelMedium, color = Area.body); Text(preview.dateAtPlan.format(longDate), style = MaterialTheme.typography.titleLarge) }
                    }
                    Text(preview.notes.first(), style = MaterialTheme.typography.bodyMedium)
                    if (preview.achievableRateKg < rate - 0.01) Text(
                        "Fastest safe pace with your burn: %.2f kg/week".format(preview.achievableRateKg),
                        style = MaterialTheme.typography.bodyMedium, color = Area.body,
                    )
                }
            }
            Button(
                onClick = { goal?.let { onSave(it, rate) } }, enabled = goal != null, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Area.body, contentColor = Color(0xFF2A1C00)),
            ) { Text("Save goal") }
        }
    }
}

@Composable
private fun AddWeightDialog(initial: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf("%.1f".format(initial)) }
    val value = text.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Today's weight") },
        text = {
            OutlinedTextField(
                text, { text = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("kg") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = { TextButton(enabled = value != null && value in 30.0..300.0, onClick = { onSave(value!!) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/** Fitness age: the age your aerobic fitness matches, from the HUNT study's model. See [com.kaya.health.engine.FitnessAge]. */
@Composable
private fun FitnessAgeCard(vm: AppViewModel, band: com.kaya.health.band.BandViewModel, modifier: Modifier) {
    val profile by vm.profile.collectAsState()
    val ui by vm.body.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val vitals by band.vitals.collectAsState()
    val sleep by band.sleep.collectAsState()
    LaunchedEffect(Unit) { if (sleep.result == null) band.analyzeSleep() }
    val inputs = remember(vitals, sleep.result, ui.latestKg, ui.motivation) {
        // Resting heart rate: your average while asleep (the study measured it seated, which is usually a little
        // higher, never the night's lowest dip). Falls back to the band's own resting estimate.
        val rhr = sleep.result?.avgSleepingHr?.takeIf { it in 35..120 }?.toDouble()
            ?: band.history(com.kaya.health.band.Vital.RESTING_HR).take(7).takeIf { it.isNotEmpty() }?.map { it.avg }?.average()
            ?: band.recoveryInputs().restingHr
        // Exercise sessions in the last 14 days: recorded walks, else days with 7,000+ steps.
        val since = java.time.LocalDate.now().minusDays(13)
        val walks = com.kaya.health.band.WalkStore(context).recorded().filter { !it.start.toLocalDate().isBefore(since) && it.minutes >= 10 }
        val sessions: Double; val minutes: Double; val hard: Boolean
        if (walks.isNotEmpty()) {
            sessions = walks.size / 2.0
            minutes = walks.map { it.minutes }.sorted()[walks.size / 2]
            val maxHr = profile.maxHr
            hard = rhr != null && walks.count { (it.avgHr ?: 0) >= rhr + 0.5 * (maxHr - rhr) } * 2 >= walks.size
        } else {
            val big = (ui.motivation?.stepsByDay ?: emptyMap()).filter { !it.key.isBefore(since) && it.value >= 7000 }.values
            sessions = big.size / 2.0
            minutes = big.map { (it - 3000) / 100.0 }.sorted().getOrNull(big.size / 2) ?: 0.0
            hard = false
        }
        val waist = com.kaya.health.data.BodyLog(context).waist().entries.lastOrNull()?.value
        Triple(rhr, Triple(sessions, minutes, hard), waist)
    }
    val (rhr, act, waist) = inputs
    Panel(modifier, accent = Area.vitals) {
        PanelLabel("Fitness age", null, Area.vitals, info = FITNESS_AGE_INFO)
        if (rhr == null) { Muted("Wear the band for a day or two so Kaya knows your resting heart rate."); return@Panel }
        val kg = ui.latestKg ?: profile.fallbackWeightKg
        val r = remember(rhr, act, waist, kg, profile) {
            com.kaya.health.engine.FitnessAge.compute(profile.age, profile.sex, profile.heightCm, kg, rhr, act.first, act.second, act.third, waist, profile.goalWeightKg)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Figure("${r.fitnessAge}", "years")
            Text("   you're ${profile.age}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        }
        Muted("VO2max ≈ %.0f ml/kg/min · sleeping HR %.0f · %s · %s".format(
            r.vo2max, rhr,
            if (waist != null) "waist %.0f cm".format(waist) else "BMI %.1f".format(kg / (profile.heightCm / 100).let { it * it }),
            if (act.first < 0.5) "little exercise lately" else "%.1f workouts a week".format(act.first),
        ))
        if (r.whatIf.isNotEmpty()) {
            Text("What brings it down", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            r.whatIf.forEach { (label, age) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("$age", style = MaterialTheme.typography.titleSmall, color = if (age < r.fitnessAge) Area.food else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (waist == null) Muted("Log your waist below for a more accurate number.")
    }
}

private const val FITNESS_AGE_INFO = "Fitness age is the age at which an average person has your aerobic fitness (VO2max), the strongest single predictor of long-term health.\n\nVO2max is estimated with the HUNT study's model (Nes et al. 2011, ~4,600 Norwegian adults checked against treadmill tests) from your age, waist (or BMI if no waist is logged), resting heart rate (Kaya uses your average heart rate while asleep) and how often and how hard you exercise. It's then matched to the average VO2max by age from the FRIEND registry (Kaminsky et al. 2015).\n\nIt's an estimate, roughly ±5 ml/kg/min, so watch the trend rather than one number. Weight, regular brisk exercise and a lower resting heart rate all bring it down, often by years within months."
