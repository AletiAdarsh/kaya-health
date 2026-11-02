@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kaya.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.MealType
import com.kaya.health.data.FoodDb
import com.kaya.health.data.FoodFavourite
import com.kaya.health.data.FoodItem
import kotlin.math.roundToInt

val MEALS = listOf(MealType.MEAL_TYPE_BREAKFAST, MealType.MEAL_TYPE_LUNCH, MealType.MEAL_TYPE_SNACK, MealType.MEAL_TYPE_DINNER)

fun FoodFavourite.asItem() = FoodItem(name.substringBefore(" ("), name.substringAfter("(", "").removeSuffix(")").ifBlank { "1 portion" },
    kcal, proteinG ?: 0.0, 0.0, 0.0, 0.0, group = "Mine")

/**
 * Add food, the way the big trackers do it: search at the top, then Recent / My foods / All,
 * a one-tap + on every row (one portion), or tap the row to pick how much. Works offline.
 */
@Composable
fun FoodSearchSheet(
    initialMeal: Int = AppViewModel.mealTypeNow(),
    recent: List<FoodItem> = emptyList(),
    mine: List<FoodFavourite> = emptyList(),
    onDismiss: () -> Unit,
    onAdd: (item: FoodItem, amount: Double, meal: Int) -> Unit,
    onManual: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(if (recent.isNotEmpty()) 0 else 2) }
    var picked by remember { mutableStateOf<FoodItem?>(null) }
    var meal by remember { mutableIntStateOf(initialMeal) }
    var added by remember { mutableStateOf(setOf<String>()) }
    val mineItems = remember(mine) { mine.map { it.asItem() } }
    val results = remember(query, tab, recent, mineItems) {
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            (recent + mineItems).filter { it.name.lowercase().contains(q) } + FoodDb.search(query)
        } else when (tab) { 0 -> recent; 1 -> mineItems; else -> FoodDb.ALL }
    }.distinctBy { it.name.lowercase() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val p = picked
            if (p == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add to", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    MealPicker(meal) { meal = it }
                }
                OutlinedTextField(
                    query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text("Search: idli, biryani, egg, chai…") },
                )
                if (query.isBlank()) TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    listOf("Recent", "My foods", "All").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
                if (results.isEmpty()) Muted(
                    when {
                        query.isNotBlank() -> "Nothing found for \"$query\". Create it below."
                        tab == 0 -> "Foods you log show up here, so the next time is one tap."
                        tab == 1 -> "Foods you create (with \"save to My foods\") show up here."
                        else -> ""
                    }
                )
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(results, key = { it.group + it.name }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = f }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${f.kcal.roundToInt()} kcal · ${f.portion}" + if (f.protein >= 1) " · ${f.protein.roundToInt()} g protein" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val done = f.name in added
                            FilledTonalIconButton(onClick = { onAdd(f, 1.0, meal); added = added + f.name }, modifier = Modifier.size(40.dp)) {
                                Icon(if (done) Icons.Rounded.Check else Icons.Rounded.Add, contentDescription = "Add one portion")
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    }
                }
                Row {
                    TextButton(onClick = onManual) { Text("+ Create a food") }
                    Spacer(Modifier.weight(1f))
                    if (added.isNotEmpty()) Button(onClick = onDismiss) { Text("Done (${added.size})") }
                }
            } else {
                PortionPicker(p, meal, onMeal = { meal = it }, onBack = { picked = null }) { amount ->
                    onAdd(p, amount, meal); added = added + p.name; picked = null
                }
            }
        }
    }
}

@Composable
fun MealPicker(meal: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        TextButton(onClick = { open = true }) { Text(AppViewModel.mealLabel(meal) + " ▾", style = MaterialTheme.typography.titleLarge, color = Area.food) }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            MEALS.forEach { t -> androidx.compose.material3.DropdownMenuItem(text = { Text(AppViewModel.mealLabel(t)) }, onClick = { onPick(t); open = false }) }
        }
    }
}

/** How much: a − / + stepper in half portions, quick picks, live calories and macros. */
@Composable
private fun PortionPicker(p: FoodItem, meal: Int, onMeal: (Int) -> Unit, onBack: () -> Unit, onAdd: (Double) -> Unit) {
    var amount by remember { mutableDoubleStateOf(1.0) }
    Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Muted("1 serving = ${p.portion}")
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { amount = (amount - 0.5).coerceAtLeast(0.5) }) { Icon(Icons.Rounded.Remove, contentDescription = "Less") }
        Text(amountLabel(amount), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 18.dp))
        FilledTonalIconButton(onClick = { amount = (amount + 0.5).coerceAtMost(20.0) }) { Icon(Icons.Rounded.Add, contentDescription = "More") }
        Text("  serving${if (amount == 1.0) "" else "s"}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.5, 1.0, 1.5, 2.0, 3.0).forEach { a -> FilterChip(selected = amount == a, onClick = { amount = a }, label = { Text(amountLabel(a)) }) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Figure("${(p.kcal * amount).roundToInt()}", "kcal", big = false)
        Spacer(Modifier.weight(1f))
        Text("${(p.protein * amount).roundToInt()} g protein", style = MaterialTheme.typography.titleMedium, color = Area.energy)
    }
    if (p.carbs + p.fat > 0) MacroLine(p.protein * amount, p.carbs * amount, p.fat * amount, p.fiber * amount)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Meal", style = MaterialTheme.typography.labelLarge)
        MealPicker(meal, onMeal)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Button(onClick = { onAdd(amount) }, modifier = Modifier.weight(1f)) { Text("Add ${(p.kcal * amount).roundToInt()} kcal") }
    }
}

fun amountLabel(a: Double) = when (a) {
    0.5 -> "½"; 1.5 -> "1½"; 2.5 -> "2½"; else -> if (a % 1.0 == 0.0) a.toInt().toString() else a.toString()
}

/** Name to save for [amount] servings of [p], e.g. "Roti / phulka ×2". */
fun loggedName(p: FoodItem, amount: Double) = if (amount == 1.0) p.name else "${p.name} ×${amountLabel(amount)}"
