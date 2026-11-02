@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kaya.health.ui

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kaya.health.engine.Profile
import com.kaya.health.engine.Sex
import kotlin.math.roundToInt

/** Used both for first-time setup and the Profile tab. */
@Composable
fun ProfileScreen(vm: AppViewModel, firstRun: Boolean, onOpenCheck: () -> Unit = {}, onOpenBand: () -> Unit = {}, onConnectHealth: () -> Unit = {}) {
    val profile by vm.profile.collectAsState()
    val hc by vm.hc.collectAsState()
    val context = LocalContext.current

    val totalInches = (profile.heightCm / 2.54).roundToInt()
    var feet by remember(profile) { mutableStateOf((totalInches / 12).toString()) }
    var inches by remember(profile) { mutableStateOf((totalInches % 12).toString()) }
    var age by remember(profile) { mutableStateOf(profile.age.toString()) }
    var weight by remember(profile) { mutableStateOf(if (firstRun) "" else "%.1f".format(profile.fallbackWeightKg)) }
    var sex by remember(profile) { mutableStateOf(profile.sex) }
    var goalKg by remember(profile) { mutableStateOf(profile.goalWeightKg?.let { "%.1f".format(it) } ?: "") }
    var rate by remember(profile) { mutableStateOf(profile.rateKgPerWeek) }
    var stepGoal by remember(profile) { mutableStateOf(profile.stepGoal.toString()) }
    var intake by remember(profile) { mutableStateOf(profile.intakeKcal?.toString() ?: "") }
    var protein by remember(profile) { mutableStateOf(if (profile.proteinGoalG > 0) profile.proteinGoalG.toString() else "") }
    var metric by remember(profile) { mutableStateOf(profile.heightMetric) }
    var cm by remember(profile) { mutableStateOf(profile.heightCm.roundToInt().toString()) }
    var showMotivation by remember(profile) { mutableStateOf(profile.showMotivation) }
    var confetti by remember(profile) { mutableStateOf(profile.confetti) }
    var look by remember(profile) { mutableStateOf(profile.look) }
    var weekSteps by remember(profile) { mutableStateOf(profile.weeklySteps.toString()) }
    var sleepGoal by remember(profile) { mutableStateOf("%.1f".format(profile.sleepGoalMin / 60.0)) }
    var water by remember(profile) { mutableStateOf(if (profile.waterGoalGlasses > 0) profile.waterGoalGlasses.toString() else "") }
    var maxHr by remember(profile) { mutableStateOf(if (profile.maxHrOverride > 0) profile.maxHrOverride.toString() else "") }
    var cpName by remember(profile) { mutableStateOf(profile.checkpointName ?: "") }
    var cpKg by remember(profile) { mutableStateOf(profile.checkpointKg?.let { "%.1f".format(it) } ?: "") }
    var cpDate by remember(profile) { mutableStateOf(profile.checkpointDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }) }
    var pickDate by remember { mutableStateOf(false) }

    val heightCm = if (metric) cm.toDoubleOrNull() else feet.toIntOrNull()?.let { f -> (f * 12 + (inches.toIntOrNull() ?: 0)) * 2.54 }
    val ageVal = age.toIntOrNull()
    val weightVal = weight.toDoubleOrNull()
    val goalVal = goalKg.toDoubleOrNull()
    val stepVal = stepGoal.toIntOrNull()
    val intakeVal = intake.toIntOrNull()
    val proteinVal = protein.toIntOrNull()
    val weekVal = weekSteps.toIntOrNull()
    val cpKgVal = cpKg.toDoubleOrNull()
    val sleepVal = sleepGoal.toDoubleOrNull()
    val waterVal = water.toIntOrNull()
    val maxHrVal = maxHr.toIntOrNull()
    val extrasValid = (intake.isBlank() || intakeVal != null && intakeVal in 800..5000) &&
        (protein.isBlank() || proteinVal != null && proteinVal in 20..400) && weekVal != null && weekVal in 5000..300000 &&
        (cpKg.isBlank() || cpKgVal != null && cpKgVal in 35.0..250.0 && cpDate != null) &&
        sleepVal != null && sleepVal in 4.0..12.0 && (water.isBlank() || waterVal != null && waterVal in 1..40) &&
        (maxHr.isBlank() || maxHrVal != null && maxHrVal in 120..230)
    val valid = heightCm != null && heightCm in 120.0..230.0 && ageVal != null && ageVal in 13..100 &&
        weightVal != null && weightVal in 30.0..300.0 && (goalKg.isBlank() || goalVal != null && goalVal in 35.0..250.0 && kotlin.math.abs(goalVal - (weightVal ?: 0.0)) >= 0.5) &&
        extrasValid

    androidx.compose.foundation.layout.Column(
        Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (firstRun) {
            Text("Step 2 of 3", style = MaterialTheme.typography.labelLarge, color = Area.energy)
            Text("About you", style = MaterialTheme.typography.displaySmall)
            Muted("Used for the calorie and fitness maths. Everything here can be changed any time in Settings, and none of it leaves your phone.")
        } else PageHeader("Settings", "Your details, goal and devices", Area.energy, androidx.compose.material.icons.Icons.Rounded.Settings)

        KCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle("Height")
                Spacer(Modifier.weight(1f))
                FilterChip(selected = !metric, onClick = { metric = false }, label = { Text("ft / in") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = metric, onClick = { metric = true }, label = { Text("cm") })
            }
            if (metric) {
                OutlinedTextField(cm, { cm = it.filter(Char::isDigit).take(3) }, label = { Text("centimetres") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(feet, { feet = it.filter(Char::isDigit).take(1) }, label = { Text("feet") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(inches, { inches = it.filter(Char::isDigit).take(2) }, label = { Text("inches") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            heightCm?.let { h -> Muted(if (metric) "= %d ft %d in".format((h / 2.54).roundToInt() / 12, (h / 2.54).roundToInt() % 12) else "= ${h.roundToInt()} cm") }
        }

        KCard {
            CardTitle("About you")
            OutlinedTextField(age, { age = it.filter(Char::isDigit).take(3) }, label = { Text("Age") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(weight, { weight = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Current weight (kg)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Muted("Sex affects the resting-calorie formula.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sex == Sex.MALE, onClick = { sex = Sex.MALE }, label = { Text("Male") })
                FilterChip(selected = sex == Sex.FEMALE, onClick = { sex = Sex.FEMALE }, label = { Text("Female") })
            }
        }

        KCard {
            CardTitle("Goal")
            OutlinedTextField(goalKg, { goalKg = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Goal weight (kg): lower to lose, higher to gain, blank = maintain") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("Speed", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { r ->
                    FilterChip(selected = rate == r, onClick = { rate = r }, label = { Text("$r kg/wk") })
                }
            }
            weightVal?.let { w ->
                Muted(
                    "≈ ${(rate * 7700 / 7).roundToInt()} kcal/day below your burn. Safe pace for you: up to %.2f kg/week (1%% of your weight)".format(w * 0.01) +
                        if (rate > w * 0.01) ". This is faster, so the Body tab will show what's realistic." else "."
                )
            }
            OutlinedTextField(weekSteps, { weekSteps = it.filter(Char::isDigit).take(6) }, label = { Text("Weekly step goal (Mon–Sun)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Muted("About %,d a day. A weekly total lets a busy day be made up on another.".format((weekVal ?: 0) / 7))
        }

        KCard {
            CardTitle("Food")
            OutlinedTextField(intake, { intake = it.filter(Char::isDigit).take(4) }, label = { Text("Daily intake (kcal), blank = Kaya plans it") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (intakeVal != null && intakeVal < (if (sex == Sex.MALE) 1500 else 1200))
                Muted("Below the usual ${if (sex == Sex.MALE) 1500 else 1200} kcal minimum. Kaya will use it; keep protein high and move back up if you feel dizzy or exhausted.")
            OutlinedTextField(protein, { protein = it.filter(Char::isDigit).take(3) }, label = { Text("Protein goal (g/day), blank = automatic") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(water, { water = it.filter(Char::isDigit).take(2) }, label = { Text("Water goal (250 ml glasses), blank = automatic") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }

        KCard {
            CardTitle("Sleep & heart")
            OutlinedTextField(sleepGoal, { sleepGoal = it.filter { c -> c.isDigit() || c == '.' }.take(4) }, label = { Text("Sleep goal (hours a night)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(maxHr, { maxHr = it.filter(Char::isDigit).take(3) }, label = { Text("Max heart rate (bpm), blank = estimate from age") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Muted("Only set max heart rate if you've measured it (e.g. in a hard test). It sets your heart-rate zones and training load.")
        }

        Muted(
            "Automatic protein: about %d g a day (1.2 g per kg of your goal weight).".format(
                (((goalVal ?: weightVal ?: profile.fallbackWeightKg)) * 1.2).toInt().coerceIn(60, 200)
            )
        )

        KCard {
            CardTitle("Checkpoint")
            Muted("A milestone on the way with a countdown on Home, e.g. a weight to reach by a birthday, a wedding or a trip.")
            OutlinedTextField(cpName, { cpName = it.take(24) }, label = { Text("Name (e.g. Birthday)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cpKg, { cpKg = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Weight (kg), blank = none") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                Text(cpDate?.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy")) ?: "Pick a date")
            }
            if (cpKg.isNotBlank() || cpName.isNotBlank()) androidx.compose.material3.TextButton(onClick = { cpKg = ""; cpName = ""; cpDate = null }) {
                Text("Remove checkpoint")
            }
        }

        KCard {
            CardTitle("Look")
            Muted("Tap a style to see the whole app in it. It's saved straight away.")
            ThemePicker(selected = look) { l -> look = l.name; KayaLook.current = l; vm.setLook(l.name) }
        }

        KCard {
            CardTitle("Personal touches")
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text("Motivation card on Home")
                    Muted("Daily line, checkpoint countdown, checklist and streaks.")
                }
                androidx.compose.material3.Switch(checked = showMotivation, onCheckedChange = { showMotivation = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text("Confetti for wins")
                    Muted("Each kilo lost, a full checklist day, a checkpoint reached.")
                }
                androidx.compose.material3.Switch(checked = confetti, onCheckedChange = { confetti = it })
            }
        }
        if (pickDate) {
            val state = androidx.compose.material3.rememberDatePickerState(
                initialSelectedDateMillis = (cpDate ?: java.time.LocalDate.now().plusWeeks(6)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            androidx.compose.material3.DatePickerDialog(
                onDismissRequest = { pickDate = false },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        state.selectedDateMillis?.let { cpDate = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }
                        pickDate = false
                    }) { Text("OK") }
                },
            ) { androidx.compose.material3.DatePicker(state) }
        }

        Button(
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                vm.saveProfile(
                    profile.copy(
                        heightCm = heightCm!!, age = ageVal!!, sex = sex, fallbackWeightKg = weightVal!!,
                        goalWeightKg = goalVal, rateKgPerWeek = rate,
                        intakeKcal = intakeVal, proteinGoalG = proteinVal ?: 0, weeklySteps = weekVal!!,
                        sleepGoalMin = ((sleepVal ?: 7.5) * 60).toInt(), waterGoalGlasses = waterVal ?: 0, maxHrOverride = maxHrVal ?: 0,
                        showMotivation = showMotivation, confetti = confetti, heightMetric = metric, look = look,
                        checkpointKg = cpKgVal, checkpointDate = cpDate?.takeIf { cpKgVal != null }?.toString(),
                        checkpointName = cpName.trim().ifBlank { "Checkpoint" }.takeIf { cpKgVal != null },
                    )
                )
            },
        ) { Text(if (firstRun) "Next" else "Save") }

        if (!firstRun) {
            RemindersCard()
            BackupCard(vm)
            KCard {
                CardTitle("Band")
                Muted("Connection, battery, sync and the raw connection log.")
                OutlinedButton(onClick = onOpenBand) { Text("Band & device") }
            }
            KCard {
                CardTitle("Health Connect")
                Muted(
                    when {
                        hc.connected && hc.missing.isEmpty() -> "Connected, with all permissions."
                        hc.connected -> "Connected, but ${hc.missing.size} permission(s) are off. Some cards may be empty."
                        else -> "Not connected."
                    }
                )
                if (!hc.connected) Button(onClick = onConnectHealth) { Text("Connect Health Connect") }
                OutlinedButton(onClick = { openHealthConnect(context) }) { Text("Manage in Health Connect") }
            }
            KCard {
                CardTitle("How Kaya counts calories")
                Muted("Workouts: the lab equations used by sports scientists (ACSM). They use your weight, speed and slope, not heart rate. Heart rate also rises with heat, poor sleep, caffeine and low fitness, which don't burn extra calories.")
                Muted("Resting: Mifflin–St Jeor formula.")
                Muted("Real burn: once you've logged ~2 weeks of food and weigh-ins, Kaya works backwards from your weight trend. 1 kg of fat ≈ 7,700 kcal. This beats any watch.")
            }
        }
    }
}
