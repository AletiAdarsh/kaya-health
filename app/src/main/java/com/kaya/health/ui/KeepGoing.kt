package com.kaya.health.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.kaya.health.engine.GoalPlan
import com.kaya.health.engine.Motivation
import com.kaya.health.engine.Profile
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.random.Random

/** What has already been celebrated, so confetti fires once per win. */
class CelebrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("kaya_wins", Context.MODE_PRIVATE)
    /** Returns true the first time [key] is seen. */
    fun firstTime(key: String): Boolean {
        if (prefs.getBoolean(key, false)) return false
        prefs.edit { putBoolean(key, true) }
        return true
    }
}

data class Checklist(val items: List<Pair<String, Boolean>>, val details: List<String>) {
    val done get() = items.count { it.second }
    val all get() = items.all { it.second }
}

fun checklist(m: MotivationUi, profile: Profile, target: Double?, todaySteps: Long): Checklist {
    val week = Motivation.weekSteps(m.stepsByDay + (LocalDate.now() to maxOf(todaySteps, m.stepsByDay[LocalDate.now()] ?: 0L)), LocalDate.now(), profile.weeklySteps)
    val daySteps = week.perDayToGo.coerceAtLeast(0)
    val stepsToday = maxOf(todaySteps, m.stepsByDay[LocalDate.now()] ?: 0L)
    // Only "done" once the day is basically over (after 8 pm, or most of the target eaten) and still under it.
    val hour = java.time.LocalTime.now().hour
    val underSoFar = m.eatenToday > 0 && target != null && m.eatenToday <= target * 1.05
    val ateEnough = underSoFar && (hour >= 20 || m.eatenToday >= target!! * 0.8)
    return Checklist(
        items = listOf(
            "Weigh in" to m.weighStreak.doneToday,
            "Log food, stay under ${target?.kcal() ?: "–"}" to ateEnough,
            "Protein ${profile.proteinTarget} g" to (m.proteinToday >= profile.proteinTarget),
            "Steps for this week" to (week.done >= week.goal || stepsToday >= daySteps),
        ),
        details = listOf(
            if (m.weighStreak.doneToday) "done" else "before breakfast",
            when {
                m.eatenToday <= 0 -> "nothing logged"
                target != null && m.eatenToday > target * 1.05 -> "${m.eatenToday.kcal()} eaten · over by ${(m.eatenToday - target).kcal()}"
                target != null && !ateEnough -> "${m.eatenToday.kcal()} so far · on track, ${(target - m.eatenToday).kcal()} left"
                else -> "${m.eatenToday.kcal()} eaten"
            },
            "${m.proteinToday.roundToInt()} g so far",
            "%,d today · %,d / %,d this week".format(stepsToday, week.done, week.goal),
        ),
    )
}

/** The top card on Home: the line for today, the checkpoint countdown, today's checklist and streaks. */
@Composable
fun KeepGoingCard(m: MotivationUi, plan: GoalPlan?, profile: Profile, target: Double?, todaySteps: Long, onOpen: () -> Unit) {
    val today = LocalDate.now()
    val cp = m.checkpoint
    val list = checklist(m, profile, target, todaySteps)
    val line = Motivation.message(
        Motivation.DayState(
            weighedToday = m.weighStreak.doneToday, newLow = m.newLowToday, lostKg = m.lostKg,
            weighStreak = m.weighStreak.days, foodStreak = m.foodStreak.days, checkpoint = cp,
            eatenKcal = m.eatenToday, targetKcal = target, hour = LocalTime.now().hour,
        ),
        today,
    )
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(250), label = "arrow")
    Panel(onClick = { expanded = !expanded }, padding = 14.dp) {
        // Slim strip: always visible, never pushes the scores down much.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { list.done / list.items.size.toFloat() }, modifier = Modifier.size(44.dp),
                    color = Area.food, trackColor = Area.food.copy(alpha = 0.15f), strokeWidth = 4.dp,
                )
                Text("${list.done}/${list.items.size}", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(line, style = MaterialTheme.typography.titleSmall, maxLines = if (expanded) 3 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        cp?.takeIf { !it.reached && it.daysLeft >= 0 }?.let { "${it.name} in ${it.daysLeft} days" },
                        if (m.weighStreak.days > 0) "${m.weighStreak.days}-day streak" else null,
                        "%.1f kg %s".format(m.lostKg, if (plan?.gaining == true) "gained" else "lost"),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            Icon(
                androidx.compose.material.icons.Icons.Rounded.ExpandMore, contentDescription = if (expanded) "Show less" else "Show more",
                modifier = Modifier.graphicsLayer { rotationZ = arrow },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            expanded,
            // Opens downward from the strip and fades in; no sliding in from a corner.
            enter = androidx.compose.animation.fadeIn(tween(220, delayMillis = 60)) +
                androidx.compose.animation.expandVertically(tween(280), expandFrom = Alignment.Top),
            exit = androidx.compose.animation.fadeOut(tween(120)) +
                androidx.compose.animation.shrinkVertically(tween(240), shrinkTowards = Alignment.Top),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                if (cp != null && !cp.reached && cp.daysLeft >= 0) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Area.body.copy(alpha = 0.08f)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${cp.name} · %.0f kg · ${cp.date.format(DateTimeFormatter.ofPattern("d MMM"))}".format(cp.kg), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("on pace ~%.1f".format(cp.projectedKg), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Bar(cp.progress, Area.body, height = 6.dp)
                        Text(
                            when {
                                cp.onTrack -> "On track"
                                cp.close -> "Within reach · %.1f kg/week gets you there".format(cp.neededKgPerWeek)
                                else -> "Needs %.1f kg/week from here".format(cp.neededKgPerWeek)
                            },
                            style = MaterialTheme.typography.labelMedium, color = if (cp.onTrack) Area.food else if (cp.close) Area.energy else Area.activity,
                        )
                    }
                } else if (cp != null && cp.reached) {
                    Tag("${cp.name}: %.0f kg reached ✓".format(cp.kg), Area.food)
                }

                list.items.forEachIndexed { i, (label, done) -> CheckRow(label, list.details[i], done) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(
                            m.foodStreak.days.takeIf { it > 0 }?.let { "$it-day calorie streak" },
                            plan?.takeIf { it.daysSoonerPer1000Steps >= 0.5 }?.let { "+1k steps a day ≈ ${it.daysSoonerPer1000Steps.roundToInt()} days sooner" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = onOpen) { Text("Your plan") }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, detail: String, done: Boolean) {
    val fill by animateFloatAsState(if (done) 1f else 0f, tween(500), label = "check")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(Area.food.copy(alpha = fill))
                .border(2.dp, if (done) Area.food else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Rounded.Check, null, Modifier.size(17.dp), tint = Color(0xFF0B2A12))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StreakChip(days: Int, label: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(18.dp), tint = if (days > 0) color else MaterialTheme.colorScheme.outline)
            Text(" $days", style = MaterialTheme.typography.titleMedium)
        }
        Text("$label streak", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class Bit(val x: Float, val vx: Float, val vy: Float, val spin: Float, val color: Color, val w: Float, val h: Float)

/** A short burst of confetti over the whole screen. Change [trigger] to fire it again; null = nothing. */
@Composable
fun Confetti(trigger: Any?) {
    if (trigger == null) return
    val t = remember(trigger) { Animatable(0f) }
    val colors = listOf(Area.body, Area.food, Area.energy, Area.activity, Area.sleep, Area.vitals, Area.mind)
    val bits = remember(trigger) {
        val r = Random(trigger.hashCode())
        List(110) {
            Bit(
                x = r.nextFloat(), vx = (r.nextFloat() - 0.5f) * 0.5f, vy = 0.35f + r.nextFloat() * 0.75f,
                spin = (r.nextFloat() - 0.5f) * 1440f, color = colors[r.nextInt(colors.size)],
                w = 8f + r.nextFloat() * 10f, h = 14f + r.nextFloat() * 14f,
            )
        }
    }
    LaunchedEffect(trigger) { t.snapTo(0f); t.animateTo(1f, tween(2600, easing = LinearEasing)) }
    if (t.value >= 1f) return
    Canvas(Modifier.fillMaxSize()) {
        val p = t.value
        bits.forEach { b ->
            val x = (b.x + b.vx * p) * size.width
            val y = -40f + (b.vy * p + 0.9f * p * p) * size.height
            val alpha = if (p > 0.75f) (1f - p) / 0.25f else 1f
            rotate(b.spin * p, Offset(x, y)) {
                drawRect(b.color.copy(alpha = alpha), Offset(x - b.w / 2, y - b.h / 2), Size(b.w, b.h))
            }
        }
    }
}
