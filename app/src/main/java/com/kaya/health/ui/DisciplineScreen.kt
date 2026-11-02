@file:OptIn(ExperimentalLayoutApi::class)

package com.kaya.health.ui

import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaya.health.data.Challenge
import com.kaya.health.data.DisciplineStore
import com.kaya.health.data.FocusSession
import com.kaya.health.data.Habit
import com.kaya.health.data.Intention
import com.kaya.health.data.MoodEntry
import com.kaya.health.data.Reward
import com.kaya.health.data.WeekReview
import com.kaya.health.engine.Discipline
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

private val HABIT_IDEAS = listOf("Log every meal", "Walk after dinner", "3 L water", "No sugar", "Protein at every meal", "In bed by 11", "No food after 9 pm", "10 min stretching")
private val CHALLENGES = listOf("No sugar" to 30, "10k steps a day" to 30, "No junk food" to 30, "Log every meal" to 30, "Walk after dinner" to 21, "No soft drinks" to 30)
private val MOODS = listOf("😞", "😕", "😐", "🙂", "😄")

/**
 * The Discipline page: habits with streaks, the morning plan, Craving SOS, a focus timer, mood,
 * 30-day challenges, your why and rewards, the weekly review and a share card. All on the phone.
 */
@Composable
fun DisciplineScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val store = remember { DisciplineStore(context) }
    var version by remember { mutableIntStateOf(0) }   // bump after any write so the page re-reads the store
    val bump: () -> Unit = { version++ }
    val today = LocalDate.now()
    val body by vm.body.collectAsState()
    val todayUi by vm.today.collectAsState()
    var sos by remember { mutableStateOf(false) }

    val habits = remember(version) { store.habits() }
    val checks = remember(version) { store.checks() }
    val habitPairs = habits.map { it.id to it.created }
    val yesterdayScore = Discipline.dayScore(today.minusDays(1), habitPairs, checks)
    val yesterdayIntention = remember(version) { store.intention(today.minusDays(1)) }
    val slipped = Discipline.yesterdaySlipped(yesterdayScore, null, null) || yesterdayIntention?.done == false

    val cravingsAll = remember(version, sos) { store.cravings() }
    val weekCravings = cravingsAll.filter { !it.at.toLocalDate().isBefore(today.minusDays(6)) }
    val bestStreak = habits.maxOfOrNull { h ->
        val days = Discipline.daysDone(h.id, checks)
        generateSequence(if (today in days) today else today.minusDays(1)) { it.minusDays(1) }.takeWhile { it in days }.count()
    } ?: 0
    val focusToday = remember(version) { store.focus() }.filter { it.start.toLocalDate() == today }.sumOf { it.minutes }
    val mood = remember(version) { store.moods()[today] }
    val intention = remember(version) { store.intention(today) }
    val challenges = remember(version) { store.challenges() }

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Discipline", "Small wins, every day", Area.body, Icons.Rounded.EmojiEvents) }

        // Today at a glance, with Craving SOS one tap away.
        item {
            Panel(accent = Area.body) {
                Row(Modifier.fillMaxWidth()) {
                    DashStat("${habits.count { "${it.id}|$today" in checks }}/${habits.size}", "habits", Modifier.weight(1f))
                    DashStat(if (bestStreak > 0) "🔥$bestStreak" else "0", "streak", Modifier.weight(1f))
                    DashStat("${weekCravings.count { it.outcome != "ate" }}/${weekCravings.size}", "cravings beaten", Modifier.weight(1f))
                    DashStat("${focusToday}m", "focus", Modifier.weight(1f))
                }
                Button(onClick = { sos = true }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Area.activity, contentColor = Color(0xFF1E0B00))) { Text("Craving? Tap SOS") }
                CravingInsights(cravingsAll)
            }
        }

        if (slipped) item {
            Panel(accent = Area.mind) {
                PanelLabel("Never miss twice", null, Area.mind)
                Muted("Yesterday slipped, and that's normal. Two in a row starts a new habit, so today just do the smallest version: one habit, one honest log.")
            }
        }

        item { HabitsCard(store, habits, checks, today, bump) }

        item { Collapsible("Today's plan", intention?.goal?.let { g -> (if (intention.done == true) "✓ " else "") + g } ?: "Not set yet", Area.energy, startOpen = intention == null && java.time.LocalTime.now().hour < 12) { IntentionCard(store, today, version, bump) } }
        item { Collapsible("Mood & energy", mood?.let { "${MOODS[(it.mood - 1).coerceIn(0, 4)]}  energy ${it.energy}/5" } ?: "Not logged today", Area.mind) { MoodCard(store, today, version, bump) } }
        item { Collapsible("Focus timer", if (focusToday > 0) "$focusToday min today" else "15–60 min, phone away", Area.sleep) { FocusCard(store, version, bump) } }
        item {
            val active = challenges.filter { !today.isAfter(it.start.plusDays(it.days - 1L)) }
            Collapsible("Challenges", active.firstOrNull()?.let { c ->
                val (done, elapsed, _) = Discipline.challengeProgress(c.start, c.days, c.done, today)
                "${c.name} · day $elapsed/${c.days}, $done kept" + if (active.size > 1) " +${active.size - 1}" else ""
            } ?: "None running", Area.vitals) { ChallengesCard(store, today, version, bump) }
        }
        item { Collapsible("Why & rewards", store.why.ifBlank { "Add your reason and milestone rewards" }, Area.body) { WhyCard(store, body.latestKg, version, bump) } }
        item { Collapsible("Weekly review", if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) "It's Sunday: 2 minutes" else "Best on Sundays", Area.energy) { ReviewCard(store, today, habits, checks, version, bump) } }
        item { Collapsible("Sleep & eating", "How your nights change your appetite", Area.sleep) { SleepLinkCard(body.intake, vm.nights.collectAsState().value) } }
        item { Collapsible("Share progress", "Send today or this week to a friend", Area.food) { ShareCard(context, store, habits, checks, today, body.latestKg, body.plan?.goalKg, todayUi.eatenKcal, todayUi.targetKcal) } }
        item { Spacer(Modifier.height(40.dp)) }
    }

    if (sos) CravingSos(
        weightKg = body.latestKg ?: todayUi.weightKg.takeIf { it > 0 } ?: 70.0, heightCm = 0.0,
        targetKcal = todayUi.targetKcal ?: body.targetKcal, eatenToday = todayUi.eatenKcal, currentKg = body.latestKg,
        onLogFood = { f -> vm.addFood(f.name, f.kcal, f.protein, AppViewModel.mealTypeNow(), f.carbs, f.fat, f.fiber) },
        onClose = { sos = false; bump() },
    )
}

@Composable
private fun CravingInsights(all: List<com.kaya.health.data.Craving>) {
    if (all.isEmpty()) return
    val p = Discipline.cravingPattern(all.map { it.at to (it.trigger to it.outcome) }, LocalDate.now().minusDays(29))
    if (p.count == 0) return
    val bits = buildList {
        add("Last 30 days: ${p.resisted} of ${p.count} cravings beaten")
        p.topTrigger?.let { add("usually when ${it.lowercase()}") }
        p.topHour?.let { add("often around ${hourLabel(it)}") }
    }
    Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Area.activity)
    if (p.topHour != null) Muted("Plan for it: have a protein snack or a walk ready before ${hourLabel(p.topHour)}.")
}

private fun hourLabel(h: Int) = when { h == 0 -> "12 am"; h < 12 -> "$h am"; h == 12 -> "12 pm"; else -> "${h - 12} pm" }

// ---- 2. Morning intention + if-then plan
@Composable
private fun IntentionCard(store: DisciplineStore, today: LocalDate, version: Int, bump: () -> Unit) {
    val saved = remember(version) { store.intention(today) }
    var goal by remember(saved) { mutableStateOf(saved?.goal ?: "") }
    var plan by remember(saved) { mutableStateOf(saved?.ifThen ?: "") }
    var editing by remember(saved) { mutableStateOf(saved == null) }
    Panel(accent = Area.energy) {
        PanelLabel("Today's plan", null, Area.energy)
        if (editing) {
            OutlinedTextField(goal, { goal = it }, label = { Text("One thing I'll do today") }, placeholder = { Text("Walk 30 min after lunch") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(plan, { plan = it }, label = { Text("If… then…") }, placeholder = { Text("If I crave sweets after dinner, then I'll have curd and walk 10 min") }, modifier = Modifier.fillMaxWidth())
            Muted("An if-then plan decides in advance, so you don't rely on willpower in the moment.")
            Button(onClick = { store.saveIntention(today, Intention(goal.trim(), plan.trim(), saved?.done)); bump() }, enabled = goal.isNotBlank()) { Text("Set") }
        } else if (saved != null) {
            Text(saved.goal, style = MaterialTheme.typography.titleMedium)
            if (saved.ifThen.isNotBlank()) Muted(saved.ifThen)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(saved.done == true, { store.saveIntention(today, saved.copy(done = if (saved.done == true) null else true)); bump() }, label = { Text("Done ✓") })
                FilterChip(saved.done == false, { store.saveIntention(today, saved.copy(done = if (saved.done == false) null else false)); bump() }, label = { Text("Not today") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editing = true }) { Text("Edit") }
            }
        }
    }
}

// ---- 1. Habit tracker with streaks and a 5-week heatmap
@Composable
private fun HabitsCard(store: DisciplineStore, habits: List<Habit>, checks: Set<String>, today: LocalDate, bump: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var manage by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    Panel(accent = Area.food) {
        val doneToday = habits.count { "${it.id}|$today" in checks }
        PanelLabel("Habits", null, Area.food, if (habits.isNotEmpty()) "$doneToday/${habits.size} today" else null)
        if (habits.isEmpty()) Muted("Pick 2–3 small habits. Tick them each day; the streak does the rest.") else Muted("Tap a habit to see its last 5 weeks.")
        habits.forEach { h ->
            val days = Discipline.daysDone(h.id, checks)
            val on = today in days
            val current = generateSequence(if (on) today else today.minusDays(1)) { it.minusDays(1) }.takeWhile { it in days }.count()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) Area.food else Area.food.copy(alpha = 0.12f))
                            .clickable { store.setCheck(h.id, today, !on); bump() },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (on) "✓" else "", color = Color(0xFF07210F), fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).clickable { openId = if (openId == h.id) null else h.id }) {
                        Text(h.name, style = MaterialTheme.typography.titleMedium)
                        Muted(if (current > 0) "🔥 $current day streak · best ${Discipline.bestStreak(days)}" else "Best streak ${Discipline.bestStreak(days)}")
                    }
                    if (manage) TextButton(onClick = { store.saveHabits(habits - h); bump() }) { Text("Remove", color = Area.vitals) }
                }
                if (openId == h.id) Heatmap(days, today, h.created, Area.food) { d -> store.setCheck(h.id, d, d !in days); bump() }
            }
        }
        if (adding) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HABIT_IDEAS.filter { idea -> habits.none { it.name == idea } }.forEach { idea -> FilterChip(name == idea, { name = idea }, label = { Text(idea) }) }
            }
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Habit") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    store.saveHabits(habits + Habit(UUID.randomUUID().toString().take(8), name.trim(), today)); name = ""; adding = false; bump()
                }, enabled = name.isNotBlank()) { Text("Add") }
                TextButton(onClick = { adding = false }) { Text("Cancel") }
            }
        } else Row {
            TextButton(onClick = { adding = true }) { Text("+ Add habit") }
            if (habits.isNotEmpty()) TextButton(onClick = { manage = !manage }) { Text(if (manage) "Done" else "Manage") }
        }
    }
}

/** Last 5 weeks, Monday-first, one square per day. Tap a past day to fix a missed tick. */
@Composable
private fun Heatmap(days: Set<LocalDate>, today: LocalDate, created: LocalDate, accent: Color, onToggle: (LocalDate) -> Unit) {
    val start = Discipline.mondayOf(today).minusWeeks(4)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 46.dp)) {
        for (w in 0 until 5) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (dow in 0 until 7) {
                val d = start.plusDays((w * 7 + dow).toLong())
                val future = d.isAfter(today)
                val color = when {
                    future -> Color.Transparent
                    d in days -> accent
                    d.isBefore(created) -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                    else -> accent.copy(alpha = 0.13f)
                }
                Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(color)
                    .let { if (!future) it.clickable { onToggle(d) } else it })
            }
        }
    }
}

// ---- 8. Mood and energy
@Composable
private fun MoodCard(store: DisciplineStore, today: LocalDate, version: Int, bump: () -> Unit) {
    val all = remember(version) { store.moods() }
    val saved = all[today]
    var mood by remember(saved) { mutableIntStateOf(saved?.mood ?: 0) }
    var energy by remember(saved) { mutableIntStateOf(saved?.energy ?: 0) }
    var note by remember(saved) { mutableStateOf(saved?.note ?: "") }
    Panel(accent = Area.mind) {
        PanelLabel("How are you today?", null, Area.mind, if (saved != null) "saved" else null)
        Text("Mood", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MOODS.forEachIndexed { i, e -> FilterChip(mood == i + 1, { mood = i + 1 }, label = { Text(e, style = MaterialTheme.typography.titleLarge) }) }
        }
        Text("Energy", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { n -> FilterChip(energy == n, { energy = n }, label = { Text("⚡".repeat(1) + n) }) }
        }
        OutlinedTextField(note, { note = it }, label = { Text("A line about today (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { store.saveMood(today, MoodEntry(mood, energy, note.trim())); bump() }, enabled = mood > 0 && energy > 0) { Text(if (saved != null) "Update" else "Save") }
        // last 7 days at a glance
        val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
        if (week.count { it in all } >= 2) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            week.forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(all[d]?.let { MOODS[(it.mood - 1).coerceIn(0, 4)] } ?: "·", style = MaterialTheme.typography.titleMedium)
                    Text(d.dayOfWeek.name.take(1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---- 3. Focus timer
@Composable
private fun FocusCard(store: DisciplineStore, version: Int, bump: () -> Unit) {
    val context = LocalContext.current
    var minutes by remember { mutableIntStateOf(25) }
    var label by remember { mutableStateOf("") }
    var endsAt by remember { mutableLongStateOf(0L) }
    var startedAt by remember { mutableStateOf<LocalDateTime?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = endsAt > 0
    LaunchedEffect(endsAt) {
        while (endsAt > 0) {
            now = System.currentTimeMillis()
            if (now >= endsAt) {
                store.addFocus(FocusSession(startedAt ?: LocalDateTime.now(), minutes, label.trim()))
                vibrate(context)
                endsAt = 0; bump(); break
            }
            delay(500)
        }
    }
    val sessions = remember(version) { store.focus() }
    val todayMin = sessions.filter { it.start.toLocalDate() == LocalDate.now() }.sumOf { it.minutes }
    Panel(accent = Area.sleep) {
        PanelLabel("Focus", null, Area.sleep, if (todayMin > 0) "$todayMin min today" else null)
        if (running) {
            val left = ((endsAt - now) / 1000).coerceAtLeast(0)
            Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.displayMedium)
            Bar(1.0 - left / (minutes * 60.0), Area.sleep)
            if (label.isNotBlank()) Muted(label)
            OutlinedButton(onClick = { endsAt = 0 }) { Text("Stop (won't count)") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 25, 45, 60).forEach { m -> FilterChip(minutes == m, { minutes = m }, label = { Text("$m min") }) }
            }
            OutlinedTextField(label, { label = it }, singleLine = true, label = { Text("Working on (optional)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { startedAt = LocalDateTime.now(); endsAt = System.currentTimeMillis() + minutes * 60_000L }) { Text("Start focus") }
            Muted("Phone away, one task. Your phone vibrates when time's up. Keep Kaya open on this page.")
        }
    }
}

private fun vibrate(context: Context) {
    runCatching {
        val v = context.getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 700), -1))
    }
}

// ---- 9. 30-day challenges
@Composable
private fun ChallengesCard(store: DisciplineStore, today: LocalDate, version: Int, bump: () -> Unit) {
    val list = remember(version) { store.challenges() }
    var picking by remember { mutableStateOf(false) }
    Panel(accent = Area.vitals) {
        PanelLabel("Challenges", null, Area.vitals)
        if (list.isEmpty() && !picking) Muted("Commit to one thing for 30 days. Tick each day you keep it.")
        list.forEach { c ->
            val (done, elapsed, finished) = Discipline.challengeProgress(c.start, c.days, c.done, today)
            val over = today.isAfter(c.start.plusDays(c.days - 1L))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Muted(when {
                            finished -> "🏆 Completed all ${c.days} days!"
                            over -> "Ended: $done of ${c.days} days"
                            else -> "Day $elapsed of ${c.days} · $done kept"
                        })
                    }
                    if (!over) {
                        val on = today in c.done
                        FilterChip(on, {
                            store.saveChallenges(list.map { if (it.id == c.id) it.copy(done = if (on) it.done - today else it.done + today) else it }); bump()
                        }, label = { Text(if (on) "Kept today ✓" else "Kept today?") })
                    }
                    TextButton(onClick = { store.saveChallenges(list.filter { it.id != c.id }); bump() }) { Text("✕") }
                }
                Bar(done / c.days.toDouble(), Area.vitals)
            }
        }
        if (picking) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CHALLENGES.filter { (n, _) -> list.none { it.name == n } }.forEach { (n, d) ->
                    FilterChip(false, {
                        store.saveChallenges(list + Challenge(UUID.randomUUID().toString().take(8), n, today, d, emptySet())); picking = false; bump()
                    }, label = { Text("$n · $d d") })
                }
            }
            TextButton(onClick = { picking = false }) { Text("Cancel") }
        } else TextButton(onClick = { picking = true }) { Text("+ Start a challenge") }
    }
}

// ---- 6. Your why + rewards at milestones
@Composable
private fun WhyCard(store: DisciplineStore, currentKg: Double?, version: Int, bump: () -> Unit) {
    var why by remember(version) { mutableStateOf(store.why) }
    val rewards = remember(version) { store.rewards() }
    var kg by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    Panel(accent = Area.body) {
        PanelLabel("Your why", null, Area.body)
        OutlinedTextField(why, { why = it }, label = { Text("Why are you doing this?") },
            placeholder = { Text("To feel light and confident at my birthday") }, modifier = Modifier.fillMaxWidth())
        if (why != store.why) Button(onClick = { store.why = why.trim(); bump() }) { Text("Save") }
        Muted("Craving SOS shows this when it matters most.")
        Text("Rewards", style = MaterialTheme.typography.titleMedium)
        rewards.forEach { r ->
            val got = currentKg != null && currentKg <= r.kg
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (got) "🎁" else "🔒", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.text, style = MaterialTheme.typography.bodyLarge)
                    Muted(if (got) "Unlocked at %.1f kg. Enjoy it!".format(r.kg)
                    else "At %.1f kg".format(r.kg) + (currentKg?.let { " · %.1f kg to go".format(it - r.kg) } ?: ""))
                }
                TextButton(onClick = { store.saveRewards(rewards - r); bump() }) { Text("✕") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(kg, { kg = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true, label = { Text("At kg") }, modifier = Modifier.width(96.dp))
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Reward") }, placeholder = { Text("New shoes") }, modifier = Modifier.weight(1f))
        }
        val k = kg.toDoubleOrNull()
        TextButton(onClick = { store.saveRewards(rewards + Reward(k!!, text.trim())); kg = ""; text = ""; bump() }, enabled = k != null && k > 20 && text.isNotBlank()) { Text("+ Add reward") }
    }
}

// ---- 7. Weekly review (numbers filled in for you, plus three questions)
@Composable
private fun ReviewCard(store: DisciplineStore, today: LocalDate, habits: List<Habit>, checks: Set<String>, version: Int, bump: () -> Unit) {
    // Sunday reviews this week; Monday to Saturday reviews last week.
    val monday = if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) Discipline.mondayOf(today) else Discipline.mondayOf(today).minusWeeks(1)
    val days = (0 until 7).map { monday.plusDays(it.toLong()) }.filter { !it.isAfter(today) }
    val pairs = habits.map { it.id to it.created }
    val scores = days.mapNotNull { Discipline.dayScore(it, pairs, checks) }
    val cravings = remember(version) { store.cravings() }.filter { it.at.toLocalDate() in days }
    val focusMin = remember(version) { store.focus() }.filter { it.start.toLocalDate() in days }.sumOf { it.minutes }
    val moods = remember(version) { store.moods() }.filterKeys { it in days }.values
    val saved = remember(version) { store.review(monday) }
    var well by remember(saved) { mutableStateOf(saved?.wentWell ?: "") }
    var slip by remember(saved) { mutableStateOf(saved?.slipped ?: "") }
    var change by remember(saved) { mutableStateOf(saved?.change ?: "") }
    var open by remember { mutableStateOf(today.dayOfWeek == java.time.DayOfWeek.SUNDAY && saved == null) }
    val fmt = DateTimeFormatter.ofPattern("d MMM")
    Panel(accent = Area.energy, onClick = { open = !open }) {
        PanelLabel("Weekly review", null, Area.energy, "${monday.format(fmt)} – ${monday.plusDays(6).format(fmt)}")
        Text(buildList {
            if (scores.isNotEmpty()) add("Habits ${(100 * scores.average()).roundToInt()}%")
            if (cravings.isNotEmpty()) add("Cravings beaten ${cravings.count { it.outcome != "ate" }}/${cravings.size}")
            if (focusMin > 0) add("Focus ${focusMin} min")
            if (moods.isNotEmpty()) add("Mood ${MOODS[(moods.map { it.mood }.average().roundToInt() - 1).coerceIn(0, 4)]}")
        }.ifEmpty { listOf("Your numbers fill in as you use this page.") }.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
        if (open) {
            OutlinedTextField(well, { well = it }, label = { Text("What went well?") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(slip, { slip = it }, label = { Text("Where did I slip, and why?") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(change, { change = it }, label = { Text("One change for next week") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { store.saveReview(monday, WeekReview(well.trim(), slip.trim(), change.trim())); open = false; bump() }) { Text("Save review") }
        } else if (saved != null) {
            if (saved.change.isNotBlank()) Text("This week's change: ${saved.change}", style = MaterialTheme.typography.titleSmall, color = Area.energy)
        } else Muted("Tap to answer three quick questions. Best on Sundays.")
    }
}

// ---- 10. Accountability: share a summary with a friend
@Composable
private fun ShareCard(
    context: Context, store: DisciplineStore, habits: List<Habit>, checks: Set<String>, today: LocalDate,
    currentKg: Double?, goalKg: Double?, eaten: Double, target: Double?,
) {
    var weekly by remember { mutableStateOf(false) }
    Panel(accent = Area.food) {
        PanelLabel("Accountability", null, Area.food)
        Muted("Send your progress to a friend or family member. People who report to someone stick with it longer.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!weekly, { weekly = false }, label = { Text("Today") })
            FilterChip(weekly, { weekly = true }, label = { Text("This week") })
        }
        Button(onClick = {
            val text = shareText(store, habits, checks, today, weekly, currentKg, goalKg, eaten, target)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, "Share progress"))
        }, colors = ButtonDefaults.buttonColors(containerColor = Area.food, contentColor = Color(0xFF07210F))) { Text("Share") }
    }
}

private fun shareText(
    store: DisciplineStore, habits: List<Habit>, checks: Set<String>, today: LocalDate, weekly: Boolean,
    currentKg: Double?, goalKg: Double?, eaten: Double, target: Double?,
): String = buildString {
    val days = if (weekly) (6 downTo 0).map { today.minusDays(it.toLong()) } else listOf(today)
    appendLine(if (weekly) "My week on Kaya 💪" else "My day on Kaya 💪")
    if (currentKg != null) appendLine("Weight: %.1f kg".format(currentKg) + (goalKg?.let { " (goal %.0f kg, %.1f to go)".format(it, currentKg - it) } ?: ""))
    if (!weekly && target != null && eaten > 0) appendLine("Food: ${eaten.roundToInt()} of ${target.roundToInt()} kcal")
    habits.forEach { h ->
        val n = days.count { "${h.id}|$it" in checks }
        appendLine(if (weekly) "• ${h.name}: $n/7 days" else "• ${h.name}: ${if (n > 0) "✓" else "–"}")
    }
    val cr = store.cravings().filter { it.at.toLocalDate() in days }
    if (cr.isNotEmpty()) appendLine("Cravings beaten: ${cr.count { it.outcome != "ate" }}/${cr.size}")
    store.challenges().forEach { c ->
        val (done, elapsed, _) = Discipline.challengeProgress(c.start, c.days, c.done, today)
        if (!today.isAfter(c.start.plusDays(c.days - 1L))) appendLine("${c.name}: day $elapsed/${c.days}, $done kept")
    }
}.trim()

/** Home's slim discipline row: today's habit ticks and a one-tap Craving SOS. */
@Composable
fun DisciplineStrip(onSos: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DisciplineStore(context) }
    var version by remember { mutableIntStateOf(0) }
    val today = LocalDate.now()
    val habits = remember(version) { store.habits() }
    val checks = remember(version) { store.checks() }
    Panel(accent = Area.body, onClick = onOpen) {
        PanelLabel("Discipline", Icons.Rounded.EmojiEvents, Area.body,
            if (habits.isNotEmpty()) "${habits.count { "${it.id}|$today" in checks }}/${habits.size} habits" else null)
        if (habits.isEmpty()) Muted("Set up 2–3 daily habits, a morning plan and your why.")
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            habits.forEach { h ->
                val on = "${h.id}|$today" in checks
                FilterChip(on, { store.setCheck(h.id, today, !on); version++ }, label = { Text((if (on) "✓ " else "") + h.name) })
            }
        }
        Button(onClick = onSos, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Area.activity, contentColor = Color(0xFF1E0B00))) { Text("Craving? Tap SOS") }
    }
}

@Composable
private fun DashStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** One line with a summary; tap to open the full tool. Keeps the page short and scannable. */
@Composable
private fun Collapsible(title: String, summary: String, accent: Color, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(startOpen) }
    if (!open) {
        Panel(padding = 14.dp, onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(accent))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else Column {
        content()
        TextButton(onClick = { open = false }, modifier = Modifier.align(Alignment.End)) { Text("Close") }
    }
}
