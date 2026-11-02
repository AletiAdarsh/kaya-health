@file:OptIn(ExperimentalLayoutApi::class)

package com.kaya.health.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kaya.health.data.Craving
import com.kaya.health.data.DisciplineStore
import com.kaya.health.data.FoodDb
import com.kaya.health.data.FoodItem
import com.kaya.health.engine.Calories
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** Healthier swaps for common cravings. */
private val SWAPS = mapOf(
    "biryani" to "Tandoori chicken + salad (~300 kcal, 35 g protein)",
    "chicken 65" to "Chicken tikka (6 pieces, ~230 kcal, 35 g protein)",
    "butter chicken" to "Chicken tikka with mint chutney (~230 kcal)",
    "gulab" to "A banana or a cup of papaya (~60–100 kcal)",
    "jalebi" to "Chai without sugar and 2 dates (~100 kcal)",
    "laddu" to "A handful of roasted chana (~110 kcal, 6 g protein)",
    "ice cream" to "Greek yogurt or curd with fruit (~120 kcal)",
    "chocolate" to "2 squares of dark chocolate, slowly (~110 kcal)",
    "chips" to "Roasted chana or makhana (~110 kcal)",
    "mixture" to "Roasted chana (~110 kcal, 6 g protein)",
    "cola" to "Soda water with lemon, or buttermilk (0–40 kcal)",
    "soft drink" to "Soda water with lemon, or buttermilk (0–40 kcal)",
    "pizza" to "Paneer or chicken tikka (~250–320 kcal)",
    "burger" to "Chicken tikka or egg bhurji (~220 kcal)",
    "fries" to "Roasted chana or a salad (~110 kcal)",
    "samosa" to "Roasted chana or sprouts chaat (~110 kcal)",
    "noodles" to "Egg bhurji or soya chunks (~200 kcal, high protein)",
    "naan" to "1 plain tandoori roti (~150 kcal)",
)

private val QUICK = listOf("Chicken biryani", "Gulab jamun", "Chips", "Soft drink / cola", "Pizza slice (cheese)", "Ice cream", "Chicken 65 / fried starter", "Samosa")
private val TRIGGERS = listOf("Stressed", "Bored", "Tired", "Hungry", "Social", "Habit")

/**
 * Craving SOS: pick the craving, then a 10-minute countdown with slow breathing, the honest cost,
 * your why, your next reward and a smarter swap. End with what actually happened (no shame).
 */
@Composable
fun CravingSos(
    weightKg: Double, heightCm: Double, targetKcal: Double?, eatenToday: Double, currentKg: Double?,
    onLogFood: (FoodItem) -> Unit, onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { DisciplineStore(context) }
    var food by remember { mutableStateOf<FoodItem?>(null) }
    var query by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(600) }
    var trigger by remember { mutableStateOf("") }
    var finished by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (secondsLeft > 0 && finished == null) { delay(1000); secondsLeft-- }
    }

    fun finish(outcome: String) {
        store.addCraving(Craving(LocalDateTime.now(), food?.name ?: query.ifBlank { "Something" }, food?.kcal, trigger.lowercase(), outcome))
        finished = outcome
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Craving SOS", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }

            when {
                finished != null -> Done(finished!!, store, onClose)
                !started -> {
                    Text("What are you craving?", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        QUICK.mapNotNull { n -> FoodDb.ALL.firstOrNull { it.name == n } }.forEach { f ->
                            FilterChip(selected = food == f, onClick = { food = f }, label = { Text(f.name.substringBefore(" (").substringBefore(" /")) })
                        }
                    }
                    OutlinedTextField(query, { query = it; food = FoodDb.search(it).firstOrNull() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Or type it: dosa, cake, momos…") })
                    food?.let { Muted("Picked: ${it.name} · ${it.portion} · ${it.kcal.roundToInt()} kcal") }
                    Text("What set it off?", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TRIGGERS.forEach { t -> FilterChip(selected = trigger == t, onClick = { trigger = if (trigger == t) "" else t }, label = { Text(t) }) }
                    }
                    Button(onClick = { started = true }, modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Area.activity, contentColor = Color(0xFF1E0B00))) {
                        Text("Give it 10 minutes", style = MaterialTheme.typography.titleMedium)
                    }
                    Muted("Cravings rise and fall like a wave, usually within 10–15 minutes. Ride it out, then decide.")
                }
                else -> {
                    // Countdown ring with a breathing pulse: in 4 s, out 6 s.
                    val pulse = rememberInfiniteTransition(label = "breath")
                    val s by pulse.animateFloat(0.82f, 1f, infiniteRepeatable(tween(5000), RepeatMode.Reverse), label = "s")
                    Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                        val ring = Area.mind
                        Canvas(Modifier.size(210.dp)) {
                            val w = 12.dp.toPx()
                            drawArc(ring.copy(alpha = 0.15f), -90f, 360f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
                            drawArc(ring, -90f, 360f * secondsLeft / 600f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
                        }
                        Box(Modifier.size(150.dp).scale(s).clip(RoundedCornerShape(75.dp)).background(Area.mind.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("%d:%02d".format(secondsLeft / 60, secondsLeft % 60), style = MaterialTheme.typography.displaySmall)
                                Text(if (s > 0.91f) "breathe out" else "breathe in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    food?.let { f ->
                        val walkMin = (f.kcal / walkKcalPerMin(weightKg, heightCm)).roundToInt()
                        Panel(accent = Area.activity) {
                            PanelLabel("The honest cost", null, Area.activity)
                            Text("${f.name}: ${f.kcal.roundToInt()} kcal", style = MaterialTheme.typography.titleLarge)
                            targetKcal?.let { t ->
                                val left = t - eatenToday
                                Muted("That's ${(100 * f.kcal / t).roundToInt()}% of today's ${t.roundToInt()} kcal" +
                                    if (left > 0) " (you have ${left.roundToInt()} left)." else " and you're already at your target.")
                            }
                            Muted("Burning it off ≈ ${walkMin / 60}h ${walkMin % 60}m of brisk walking.")
                        }
                        SWAPS.entries.firstOrNull { f.name.lowercase().contains(it.key) }?.let { (_, swap) ->
                            Panel(accent = Area.food) {
                                PanelLabel("Smarter swap", null, Area.food)
                                Text(swap, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }

                    val why = store.why
                    val next = currentKg?.let { kg -> store.rewards().filter { it.kg < kg }.maxByOrNull { it.kg } }
                    if (why.isNotBlank() || next != null) Panel(accent = Area.body) {
                        PanelLabel("Remember why", null, Area.body)
                        if (why.isNotBlank()) Text("“$why”", style = MaterialTheme.typography.titleMedium)
                        next?.let { r -> Muted("Next reward: ${r.text} at %.0f kg. %.1f kg to go.".format(r.kg, (currentKg - r.kg).coerceAtLeast(0.0))) }
                    } else Muted("Tip: add your \"why\" and rewards on the Discipline page; they'll show up here.")

                    Text("How did it go?", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { finish("passed") }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Area.food, contentColor = Color(0xFF07210F))) { Text("It passed. I'm good") }
                    OutlinedButton(onClick = { finish("smaller") }, modifier = Modifier.fillMaxWidth()) { Text("I had a smaller portion") }
                    OutlinedButton(onClick = { finish("ate"); food?.let(onLogFood) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (food != null) "I ate it (log it in Food)" else "I ate it")
                    }
                }
            }
        }
    }
}

@Composable
private fun Done(outcome: String, store: DisciplineStore, onClose: () -> Unit) {
    val week = store.cravings().filter { !it.at.toLocalDate().isBefore(LocalDate.now().minusDays(6)) }
    val beaten = week.count { it.outcome == "passed" || it.outcome == "smaller" }
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            when (outcome) { "passed" -> "💪"; "smaller" -> "👍"; else -> "🙂" },
            style = MaterialTheme.typography.displayLarge,
        )
        Text(
            when (outcome) {
                "passed" -> "You rode it out. That's discipline."
                "smaller" -> "A smaller portion is still a win."
                else -> "Logged, no guilt. One meal doesn't undo a week."
            },
            style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
        )
        Muted("$beaten of ${week.size} cravings handled this week.")
        Button(onClick = onClose) { Text("Done") }
    }
}

/** Net kcal per minute of brisk walking (5 km/h) for this body, from the ACSM walking equation. */
fun walkKcalPerMin(weightKg: Double, @Suppress("UNUSED_PARAMETER") heightCm: Double): Double {
    val speed = 5000.0 / 60
    return Calories.kcalFromVo2(Calories.walkingVo2(speed) - Calories.walkingVo2(0.0), weightKg, 1.0)
}

