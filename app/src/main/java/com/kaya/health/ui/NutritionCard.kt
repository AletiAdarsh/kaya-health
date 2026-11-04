@file:OptIn(ExperimentalLayoutApi::class)

package com.kaya.health.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.MealType
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Home's nutrition card: calories and protein against today's targets, what each meal added,
 * and Add food right here (search opens on Home, no need to leave the page).
 */
@Composable
fun NutritionCard(vm: AppViewModel, target: Double?, burnedSoFar: Double?, burnedRange: String? = null, onOpenFood: () -> Unit) {
    val food by vm.food.collectAsState()
    val profile by vm.profile.collectAsState()
    var searchOpen by remember { mutableStateOf(false) }
    // The Food page may be showing another day; this card is always today.
    val todayEntries = if (food.date == LocalDate.now()) food.entries else emptyList()
    val eaten = todayEntries.sumOf { it.kcal }
    val protein = todayEntries.sumOf { it.proteinG ?: 0.0 }
    val carbs = todayEntries.sumOf { it.carbsG ?: 0.0 }
    val fat = todayEntries.sumOf { it.fatG ?: 0.0 }
    val fiber = todayEntries.sumOf { it.fiberG ?: 0.0 }
    val proteinGoal = profile.proteinTarget.toDouble()

    Panel(accent = Area.food, onClick = onOpenFood) {
        PanelLabel("Nutrition", Icons.Rounded.Restaurant, Area.food, target?.let { "target ${it.kcal()} kcal" })

        Row(verticalAlignment = Alignment.Bottom) {
            Figure(eaten.kcal(), "kcal eaten")
            Spacer(Modifier.weight(1f))
            if (target != null) {
                val left = target - eaten
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        (if (left >= 0) left else -left).kcal(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (left >= 0) Area.food else Area.activity,
                    )
                    Text(if (left >= 0) "left" else "over", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (target != null) Bar(eaten / target, if (eaten > target * 1.05) Area.activity else Area.food, height = 10.dp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Protein", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${protein.roundToInt()} / ${proteinGoal.roundToInt()} g", style = MaterialTheme.typography.titleSmall, color = Area.energy)
        }
        Bar(protein / proteinGoal, Area.energy, height = 8.dp)
        if (todayEntries.any { it.carbsG != null || it.fatG != null }) {
            Text(
                "Carbs ${carbs.roundToInt()} g · Fat ${fat.roundToInt()} g · Fiber ${fiber.roundToInt()} g",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MacroSplitBar(protein, carbs, fat)
        }

        // What each meal added today.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(MealType.MEAL_TYPE_BREAKFAST, MealType.MEAL_TYPE_LUNCH, MealType.MEAL_TYPE_SNACK, MealType.MEAL_TYPE_DINNER).forEach { t ->
                val kcal = todayEntries.filter { it.mealType == t }.sumOf { it.kcal }
                MealChip(AppViewModel.mealLabel(t), if (kcal > 0) kcal.kcal() else "–", kcal > 0)
            }
        }
        burnedSoFar?.let { Muted("Burned so far today: ${it.kcal()} kcal" + (burnedRange?.let { r -> " $r" } ?: "")) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { searchOpen = true }, modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Area.food, contentColor = Color(0xFF07210F)),
            ) { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Text(" Add food") }
            OutlinedButton(onClick = onOpenFood, modifier = Modifier.height(48.dp)) { Text("Food log") }
        }
    }

    if (searchOpen) FoodSearchSheet(
        recent = food.recent, mine = food.favourites,
        onDismiss = { searchOpen = false },
        onAdd = { f, amount, m -> vm.addFood(loggedName(f, amount), f.kcal * amount, f.protein * amount, m, f.carbs * amount, f.fat * amount, f.fiber * amount) },
        onManual = { searchOpen = false; onOpenFood() },
    )
}

@Composable
private fun MealChip(label: String, value: String, filled: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (filled) Area.food.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, color = if (filled) Area.food else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Suppress("unused") private val keepFill = Modifier.fillMaxWidth()
