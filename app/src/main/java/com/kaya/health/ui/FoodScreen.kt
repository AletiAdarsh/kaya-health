@file:OptIn(ExperimentalLayoutApi::class)

package com.kaya.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kaya.health.ui.FoodUi
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.MealType
import com.kaya.health.data.FoodEntry
import com.kaya.health.data.FoodFavourite
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun FoodScreen(vm: AppViewModel) {
    val ui by vm.food.collectAsState()
    val profile by vm.profile.collectAsState()
    val hc by vm.hc.collectAsState()
    var dialogOpen by remember { mutableStateOf(false) }
    var searchMeal by remember { mutableStateOf<Int?>(null) }
    var manageMine by remember { mutableStateOf(false) }
    val isToday = ui.date == LocalDate.now()

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        card(1) {
            PageHeader("Food", if (isToday) "Today · " + ui.date.format(fullDayFmt) else ui.date.format(fullDayFmt), Area.food, Icons.Rounded.Restaurant) {
                IconButton(onClick = { vm.showFoodDay(ui.date.minusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
                }
                IconButton(onClick = { vm.showFoodDay(ui.date.plusDays(1)) }, enabled = !isToday) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
                }
            }
        }

        if (!hc.connected) {
            card(2) { Panel { Muted("Connect Health Connect in Settings first. Food is saved there so it stays on your phone and other apps can use it.") } }
            return@LazyColumn
        }

        card(2) { DaySummary(ui, profile.proteinTarget.toDouble()) }

        // One section per meal, like a food diary.
        val sections = MEALS + listOf(0)
        sections.forEach { meal ->
            val items = ui.entries.filter { if (meal == 0) it.mealType !in MEALS else it.mealType == meal }
            if (meal == 0 && items.isEmpty()) return@forEach
            card(3) {
                MealSection(
                    meal, items, vm,
                    repeat = if (meal == 0 || items.isNotEmpty()) emptyList() else ui.previousDay.filter { it.mealType == meal },
                    onAdd = { searchMeal = meal },
                )
            }
        }

        card(4) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialogOpen = true }, modifier = Modifier.weight(1f)) { Text("Create a food") }
                OutlinedButton(onClick = { manageMine = true }, modifier = Modifier.weight(1f)) { Text("My foods (${ui.favourites.size})") }
            }
        }
        if (ui.loading) card(5) { Centered("Loading…") }
    }

    searchMeal?.let { meal ->
        FoodSearchSheet(
            initialMeal = if (meal == 0) AppViewModel.mealTypeNow() else meal,
            recent = ui.recent, mine = ui.favourites,
            onDismiss = { searchMeal = null },
            onAdd = { f, amount, m -> vm.addFood(loggedName(f, amount), f.kcal * amount, f.protein * amount, m, f.carbs * amount, f.fat * amount, f.fiber * amount) },
            onManual = { searchMeal = null; dialogOpen = true },
        )
    }

    if (dialogOpen) {
        AddFoodDialog(
            prefill = null,
            onDismiss = { dialogOpen = false },
            onSave = { name, kcal, protein, meal, saveFav, carbs, fat ->
                vm.addFood(name, kcal, protein, meal, carbs, fat)
                if (saveFav) vm.addFavourite(FoodFavourite(name, kcal, protein))
                dialogOpen = false
            },
        )
    }

    if (manageMine) AlertDialog(
        onDismissRequest = { manageMine = false },
        title = { Text("My foods") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ui.favourites.isEmpty()) Muted("None yet. Create a food and tick \"Save to My foods\".")
                ui.favourites.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.name} · ${f.kcal.roundToInt()} kcal", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { vm.removeFavourite(f) }) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
                    }
                }
                var confirm by remember { mutableStateOf<String?>(null) }
                Text("Start from a list", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.kaya.health.data.FavouritesStore.PRESETS.keys.forEach { name -> AssistChip(onClick = { confirm = name }, label = { Text(name) }) }
                }
                confirm?.let { name ->
                    TextButton(onClick = { vm.useFoodPreset(name); confirm = null }) { Text(if (name == "Empty") "Tap to clear My foods" else "Tap to replace My foods with the $name list") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { manageMine = false }) { Text("Done") } },
    )
}

/** Calories and macros for the day against simple targets. */
@Composable
private fun DaySummary(ui: FoodUi, proteinGoal: Double) {
    val target = ui.targetKcal
    // Fat 25% of calories, carbs the rest after protein; fiber 30 g.
    val fatGoal = target?.let { it * 0.25 / 9 }
    val carbGoal = target?.let { ((it - proteinGoal * 4 - it * 0.25) / 4).coerceAtLeast(50.0) }
    Panel(accent = Area.food) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(
                target?.let { (100 * ui.totalKcal / it).roundToInt().coerceIn(0, 100) }, if (target != null && ui.totalKcal > target) Area.activity else Area.food,
                "% eaten", size = 92.dp, stroke = 9.dp,
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                val left = target?.let { it - ui.totalKcal }
                Text(
                    when { left == null -> "${ui.totalKcal.kcal()} kcal"; left >= 0 -> "${left.kcal()} left"; else -> "${(-left).kcal()} over" },
                    style = MaterialTheme.typography.headlineMedium, color = if (left != null && left < 0) Area.activity else MaterialTheme.colorScheme.onSurface,
                )
                Muted("${ui.totalKcal.kcal()} eaten" + (target?.let { " of ${it.kcal()} kcal" } ?: ""))
            }
            InfoDot("Your food targets", "Calories: your daily target from the Body plan (or your own number in Settings).\n\nProtein: your setting, or 1.2 g per kg of goal weight, to keep muscle while losing fat.\n\nFat: about 25% of calories. Carbs: what's left after protein and fat. Fiber: 25–30 g a day keeps you fuller.\n\nFood values come from the Indian Food Composition Tables (2017) and USDA, rounded to typical home portions.")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MacroGoal("Protein", ui.totalProtein, proteinGoal, Area.energy, Modifier.weight(1f))
            MacroGoal("Carbs", ui.totalCarbs, carbGoal, Color(0xFFFFC857), Modifier.weight(1f))
            MacroGoal("Fat", ui.totalFat, fatGoal, Area.activity, Modifier.weight(1f))
            MacroGoal("Fiber", ui.totalFiber, 30.0, Area.food, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MacroGoal(name: String, value: Double, goal: Double?, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${value.roundToInt()}" + (goal?.let { "/${it.roundToInt()}" } ?: "") + " g", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Bar(if (goal != null && goal > 0) value / goal else 0.0, color, height = 5.dp)
    }
}

/** A meal in the diary: its foods, its total, and + Add. Empty meals offer yesterday's again. */
@Composable
private fun MealSection(meal: Int, items: List<FoodEntry>, vm: AppViewModel, repeat: List<FoodEntry>, onAdd: () -> Unit) {
    Panel(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(AppViewModel.mealLabel(meal), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (items.isNotEmpty()) Muted("${items.sumOf { it.kcal }.kcal()} kcal · ${items.sumOf { it.proteinG ?: 0.0 }.roundToInt()} g protein")
            }
            if (meal != 0) androidx.compose.material3.FilledTonalButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Text(" Add")
            }
        }
        items.forEach { e ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = MaterialTheme.typography.bodyLarge)
                    Muted(
                        e.time.clock() +
                            (if (e.carbsG != null || e.fatG != null) " · P ${(e.proteinG ?: 0.0).roundToInt()} · C ${(e.carbsG ?: 0.0).roundToInt()} · F ${(e.fatG ?: 0.0).roundToInt()} g"
                            else e.proteinG?.let { " · ${it.roundToInt()} g protein" } ?: "") +
                            if (!e.own) " · from ${vm.friendly(e.source)}" else ""
                    )
                }
                Text(e.kcal.kcal(), style = MaterialTheme.typography.titleSmall)
                if (e.own) IconButton(onClick = { vm.deleteFood(e) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (items.isEmpty() && repeat.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Same as yesterday?", style = MaterialTheme.typography.bodyMedium)
                    Muted(repeat.joinToString(", ") { it.name } + " · ${repeat.sumOf { it.kcal }.kcal()} kcal")
                }
                TextButton(onClick = { vm.addFoods(repeat, meal) }) { Text("Log again") }
            }
        }
    }
}

@Composable
private fun AddFoodDialog(
    prefill: FoodFavourite?,
    onDismiss: () -> Unit,
    onSave: (name: String, kcal: Double, protein: Double?, meal: Int, saveFav: Boolean, carbs: Double?, fat: Double?) -> Unit,
) {
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(prefill?.name ?: "") }
    var kcal by remember { mutableStateOf(prefill?.kcal?.roundToInt()?.toString() ?: "") }
    var protein by remember { mutableStateOf(prefill?.proteinG?.roundToInt()?.toString() ?: "") }
    var meal by remember { mutableIntStateOf(AppViewModel.mealTypeNow()) }
    var saveFav by remember { mutableStateOf(false) }
    val kcalValue = kcal.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add food") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("What did you eat?") }, singleLine = true)
                OutlinedTextField(
                    kcal, { kcal = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Calories (kcal)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    protein, { protein = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Protein (g), optional") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        carbs, { carbs = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Carbs (g)") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        fat, { fat = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Fat (g)") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(MealType.MEAL_TYPE_BREAKFAST, MealType.MEAL_TYPE_LUNCH, MealType.MEAL_TYPE_SNACK, MealType.MEAL_TYPE_DINNER).forEach { t ->
                        FilterChip(selected = meal == t, onClick = { meal = t }, label = { Text(AppViewModel.mealLabel(t)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saveFav, onCheckedChange = { saveFav = it })
                    Text("Save to My foods")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = kcalValue != null && kcalValue > 0,
                onClick = { onSave(name.trim(), kcalValue!!, protein.toDoubleOrNull(), meal, saveFav, carbs.toDoubleOrNull(), fat.toDoubleOrNull()) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
