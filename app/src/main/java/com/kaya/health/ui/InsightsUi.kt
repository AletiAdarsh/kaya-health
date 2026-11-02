package com.kaya.health.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaya.health.data.BodyLog
import com.kaya.health.data.DisciplineStore
import com.kaya.health.data.FoodDb
import com.kaya.health.engine.Insights
import com.kaya.health.engine.TrendPoint
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ---------------------------------------------------------------- "What can I eat?"

/** Meal ideas that fit what's left of today's calories and protein. Collapsed to one line until tapped. */
@Composable
fun MealIdeasCard(vm: AppViewModel, target: Double?) {
    val food by vm.food.collectAsState()
    val profile by vm.profile.collectAsState()
    if (target == null) return
    val entries = if (food.date == LocalDate.now()) food.entries else emptyList()
    val kcalLeft = target - entries.sumOf { it.kcal }
    val proteinLeft = (profile.proteinTarget - entries.sumOf { it.proteinG ?: 0.0 }).coerceAtLeast(0.0)
    val ideas = remember(kcalLeft.roundToInt(), proteinLeft.roundToInt()) { Insights.mealIdeas(FoodDb.ALL, kcalLeft, proteinLeft) }
    var open by remember { mutableStateOf(false) }
    Panel(accent = Area.food, onClick = { open = !open }) {
        PanelLabel("What can I eat?", null, Area.food, if (open) "hide" else "show")
        Text(
            when {
                kcalLeft < 0 -> "You're ${(-kcalLeft).roundToInt()} kcal over. If you're truly hungry, keep it light and protein-first."
                kcalLeft < 150 -> "Only ${kcalLeft.roundToInt()} kcal left. Light, filling options:"
                else -> "${kcalLeft.roundToInt()} kcal and ${proteinLeft.roundToInt()} g protein left. Meals that fit:"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ideas.forEach { idea ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(idea.items.joinToString(" + ") { it.name }, style = MaterialTheme.typography.titleSmall)
                            Muted(idea.items.joinToString(" · ") { it.portion } + " · ${idea.kcal.roundToInt()} kcal · ${idea.protein.roundToInt()} g protein")
                        }
                        TextButton(onClick = {
                            idea.items.forEach { f -> vm.addFood(f.name, f.kcal, f.protein, AppViewModel.mealTypeNow(), f.carbs, f.fat, f.fiber) }
                        }) { Text("I ate this") }
                    }
                }
                if (ideas.isEmpty()) Muted("Nothing in the food list fits. Water, black tea or a walk can help the hunger pass.")
                Muted("Ideas use the built-in food list; portions are typical home servings.")
            }
        }
    }
}

// ---------------------------------------------------------------- water

@Composable
fun WaterCard(weightKg: Double, ownGoal: Int = 0) {
    val context = LocalContext.current
    val log = remember { BodyLog(context) }
    val today = LocalDate.now()
    var glasses by remember { mutableIntStateOf(log.water(today)) }
    val goal = Insights.waterGlasses(weightKg, ownGoal)
    fun set(n: Int) { glasses = n.coerceAtLeast(0); log.setWater(today, glasses); com.kaya.health.KayaWidget.refresh(context) }
    Panel(accent = Area.sleep) {
        PanelLabel("Water", null, Area.sleep, "goal ${goal * 250 / 1000.0} L")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$glasses / $goal glasses", style = MaterialTheme.typography.titleLarge)
                Muted("%.2f L of %.1f L".format(glasses * 0.25, goal * 0.25))
            }
            OutlinedButton(onClick = { set(glasses - 1) }, enabled = glasses > 0) { Text("−") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { set(glasses + 1) }) { Text("+ glass") }
        }
        Bar(glasses / goal.toDouble(), Area.sleep)
    }
}

// ---------------------------------------------------------------- weight notes (water weight, plateau)

@Composable
fun WeightNotesCard(trend: List<TrendPoint>, losing: Boolean, modifier: Modifier = Modifier) {
    val notes = remember(trend, losing) { Insights.weightNotes(trend, losing, LocalDate.now()) }
    if (notes.isEmpty()) return
    Panel(modifier, accent = Area.mind) {
        PanelLabel("Reading the scale", null, Area.mind)
        notes.forEach { n ->
            Text(n.title, style = MaterialTheme.typography.titleMedium)
            Muted(n.text)
        }
    }
}

// ---------------------------------------------------------------- waist

@Composable
fun WaistCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val log = remember { BodyLog(context) }
    var version by remember { mutableIntStateOf(0) }
    val all = remember(version) { log.waist() }
    var input by remember { mutableStateOf("") }
    val fmt = DateTimeFormatter.ofPattern("d MMM")
    Panel(modifier, accent = Area.body) {
        PanelLabel("Waist", null, Area.body, all.keys.lastOrNull()?.format(fmt))
        if (all.isEmpty()) Muted("Measure at the belly button, relaxed, once a week. The waist often shrinks while the scale is stuck.")
        else {
            val first = all.entries.first(); val last = all.entries.last()
            Row(verticalAlignment = Alignment.Bottom) {
                Figure("%.1f".format(last.value), "cm", big = false)
                if (all.size > 1) {
                    val d = last.value - first.value
                    Text("  %+.1f cm since %s".format(d, first.key.format(fmt)), style = MaterialTheme.typography.titleSmall,
                        color = if (d <= 0) Area.food else Area.activity, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            if (all.size > 2) Muted(all.entries.toList().takeLast(6).joinToString("  →  ") { "%.1f".format(it.value) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                label = { Text("Today, cm") }, modifier = Modifier.width(130.dp))
            val cm = input.toDoubleOrNull()
            Button(onClick = { log.setWaist(LocalDate.now(), cm); input = ""; version++ }, enabled = cm != null && cm in 40.0..250.0) { Text("Save") }
        }
    }
}

// ---------------------------------------------------------------- progress photos

@Composable
fun PhotosCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val log = remember { BodyLog(context) }
    var version by remember { mutableIntStateOf(0) }
    val photos = remember(version) { log.photos() }
    var viewing by remember { mutableStateOf<File?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { log.addPhoto(uri, LocalDate.now()); version++ }
    }
    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy")
    Panel(modifier, accent = Area.body) {
        PanelLabel("Progress photos", null, Area.body, if (photos.isNotEmpty()) "${photos.size}" else null)
        if (photos.size >= 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(photos.first(), photos.last()).forEachIndexed { i, f ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Thumb(f, Modifier.fillMaxWidth().aspectRatio(0.75f)) { viewing = f }
                        Muted((if (i == 0) "Then · " else "Now · ") + (log.photoDate(f)?.format(fmt) ?: ""))
                    }
                }
            }
        }
        if (photos.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(photos.reversed(), key = { it.name }) { f -> Thumb(f, Modifier.size(64.dp)) { viewing = f } }
        } else Muted("Same spot, same light, every 2 weeks. Photos stay in Kaya on this phone (and in your own backup), never uploaded.")
        Button(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("+ Add photo") }
    }
    viewing?.let { f ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { viewing = null }) {
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Thumb(f, Modifier.fillMaxWidth().aspectRatio(0.75f), sample = 1) {}
                Text(log.photoDate(f)?.format(fmt) ?: f.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Row {
                    TextButton(onClick = { log.deletePhoto(f); viewing = null; version++ }) { Text("Delete", color = Area.vitals) }
                    TextButton(onClick = { viewing = null }) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun Thumb(f: File, modifier: Modifier, sample: Int = 4, onClick: () -> Unit) {
    val bmp = remember(f.path, sample) { runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }.getOrNull() }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.2f)).clickable(onClick = onClick)) {
        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
    }
}

// ---------------------------------------------------------------- sleep and eating

@Composable
fun SleepLinkCard(intake: List<com.kaya.health.engine.DayIntake>, merged: Map<LocalDate, AppViewModel.Night>) {
    val context = LocalContext.current
    val nights = remember(merged) { merged.mapValues { it.value.minutes } }
    val cravings = remember { DisciplineStore(context).cravings().groupingBy { it.at.toLocalDate() }.eachCount() }
    val link = remember(intake, nights) { Insights.sleepLink(nights, intake.associate { it.date to it.kcal }, cravings) }
    Panel(accent = Area.sleep) {
        PanelLabel("Sleep and eating", null, Area.sleep)
        if (link == null) {
            Muted("Kaya is collecting nights. After 3 short nights (< 6 h) and 3 longer ones with food logged, you'll see how sleep changes your eating. ${nights.size} night${if (nights.size == 1) "" else "s"} saved so far.")
        } else {
            val diff = link.kcalShort - link.kcalOther
            Text(
                if (diff > 50) "After short nights you eat about ${diff.roundToInt()} kcal more."
                else if (diff < -50) "Short nights haven't made you eat more. Nice."
                else "Your eating is about the same after short and long nights.",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Muted("Under 6 h (${link.shortNights} days): ${link.kcalShort.roundToInt()} kcal, %.1f cravings/day. 6 h+ (${link.otherNights} days): ${link.kcalOther.roundToInt()} kcal, %.1f cravings/day.".format(link.cravShort, link.cravOther))
            if (diff > 50) Muted("Sleep is part of the diet: an earlier night may be the easiest calorie cut you have.")
        }
    }
}
