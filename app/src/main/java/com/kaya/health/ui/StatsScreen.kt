package com.kaya.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.kaya.health.band.BandViewModel
import com.kaya.health.band.Vital
import com.kaya.health.band.WalkStore
import com.kaya.health.data.BodyLog
import com.kaya.health.engine.Metrics
import com.kaya.health.engine.NightRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Mark { GOOD, OK, WATCH, NONE }

private data class StatRow(val name: String, val value: String, val note: String, val mark: Mark, val info: String)

private data class StatsData(
    val heart: List<StatRow>, val sleep: List<StatRow>, val recovery: List<StatRow>, val load: List<StatRow>, val body: List<StatRow>,
    val rhythm: List<StatRow>, val food: List<StatRow>,
)

/** Everything Kaya can work out from the band, the food log and the scale, each with its research and a status. */
@Composable
fun StatsScreen(vm: AppViewModel, band: BandViewModel) {
    val context = LocalContext.current
    val profile by vm.profile.collectAsState()
    val body by vm.body.collectAsState()
    val sleep by band.sleep.collectAsState()
    val days by band.days.collectAsState()
    LaunchedEffect(Unit) { if (sleep.result == null) band.analyzeSleep() }

    val data by produceState<StatsData?>(null, sleep.result, days, body) {
        value = withContext(Dispatchers.Default) { compute(context, vm, band, profile, body, sleep, days) }
    }

    LazyColumn(contentPadding = PaddingValues(start = androidx.compose.ui.unit.Dp(18f), end = androidx.compose.ui.unit.Dp(18f), top = androidx.compose.ui.unit.Dp(18f), bottom = androidx.compose.ui.unit.Dp(40f)),
        verticalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(12f))) {
        card(1) { PageHeader("Stats", "Everything worked out from your data", Area.energy, Icons.Rounded.Insights) }
        val d = data
        if (d == null) { card(2) { Centered("Working it out…") }; return@LazyColumn }
        card(2) { Section("Heart", Area.vitals, d.heart) }
        card(3) { Section("Sleep", Area.sleep, d.sleep) }
        card(4) { Section("Recovery & early warnings", Area.mind, d.recovery) }
        card(5) { Section("Activity & load", Area.activity, d.load) }
        card(6) { Section("Daily rhythm & stress", Area.mind, d.rhythm) }
        card(6) { Section("Food timing", Area.food, d.food) }
        card(6) { Section("Body & food", Area.food, d.body) }
        card(7) { Muted("Green is in a healthy range, orange is worth watching. Numbers marked \"needs data\" fill in as you wear the band, log food and weigh in. These are estimates for tracking yourself, not a medical test.") }
    }
}

@Composable
private fun Section(title: String, accent: Color, rows: List<StatRow>) {
    Panel(accent = accent) {
        PanelLabel(title, null, accent)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.bodyLarge)
                    Muted(r.note)
                }
                Text(
                    r.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = when (r.mark) { Mark.GOOD -> Area.food; Mark.WATCH -> Area.activity; Mark.OK -> MaterialTheme.colorScheme.onSurface; Mark.NONE -> MaterialTheme.colorScheme.onSurfaceVariant },
                )
                InfoDot(r.name, r.info)
            }
        }
    }
}

private const val NEED = "needs data"

private suspend fun compute(
    context: android.content.Context, vm: AppViewModel, band: BandViewModel, profile: com.kaya.health.engine.Profile,
    body: BodyUi, sleep: com.kaya.health.band.SleepUi, days: Map<LocalDate, com.kaya.health.band.BandDay>,
): StatsData {
    val today = LocalDate.now()
    val log = BodyLog(context)
    val nights: List<NightRecord> = log.nightRecords()
    val last = nights.lastOrNull { it.wakeDay == today } ?: nights.lastOrNull()
    val earlier = nights.filter { last != null && it.wakeDay.isBefore(last.wakeDay) && !it.wakeDay.isBefore(last.wakeDay.minusDays(21)) }
    val maxHr = profile.maxHr
    val restBase = (earlier.map { it.avgHr } + listOfNotNull(last?.avgHr)).takeIf { it.isNotEmpty() }?.average() ?: 72.0
    fun hrOn(d: LocalDate): List<Pair<java.time.LocalDateTime, Int>> =
        days[d]?.hrReadings ?: band.savedDay(Vital.HEART, d).map { it.first to it.second.toInt() }

    // ---------------- heart
    val heart = buildList {
        val r = sleep.result
        add(StatRow("Sleeping heart rate", r?.avgSleepingHr?.let { "$it bpm" } ?: NEED,
            "average asleep last night" + (r?.let { " · lowest ${it.lowestHr}" } ?: ""),
            when { r == null -> Mark.NONE; r.avgSleepingHr < 60 -> Mark.GOOD; r.avgSleepingHr <= 75 -> Mark.OK; else -> Mark.WATCH },
            "Your average heart rate while asleep, the closest thing to a true resting heart rate a wrist band can measure. For adults, under 60 is very fit and 60–75 is typical; consistently over 75 usually means low fitness, poor sleep, stress, illness or dehydration. It falls as you get fitter, often within weeks."))
        val dip = r?.let { Metrics.nightDip(hrOn(r.bedtime.toLocalDate()), r.avgSleepingHr) }
        add(StatRow("Night-time dip", dip?.let { "${(it * 100).roundToInt()}%" } ?: NEED, "heart rate asleep vs. awake during the day",
            when { dip == null -> Mark.NONE; dip >= 0.10 -> Mark.GOOD; else -> Mark.WATCH },
            "Healthy hearts slow down at night: sleeping heart rate is normally at least 10% lower than daytime. A smaller drop (\"non-dipping\") is linked with higher cardiovascular risk in research (Baka & Simko 2021 review), and it's often caused by late meals, alcohol, stress or poor sleep. Daytime level = median of your 9 am – 9 pm readings."))
        val walks = WalkStore(context).recorded().filter { it.minutes >= 10 && !it.start.toLocalDate().isBefore(today.minusDays(13)) }
        val hrr = walks.mapNotNull { w -> Metrics.heartRateRecovery(hrOn(w.end.toLocalDate()), w.end) }
        add(StatRow("Heart-rate recovery", hrr.takeIf { it.isNotEmpty() }?.let { "${it.average().roundToInt()} bpm" } ?: NEED,
            if (hrr.isEmpty()) "after a 10+ min walk, stand still for a minute" else "drop 1 min after your walks (${hrr.size})",
            when { hrr.isEmpty() -> Mark.NONE; hrr.average() > 12 -> Mark.GOOD; else -> Mark.WATCH },
            "How many beats your heart rate drops in the first minute after exercise. In a landmark study of 2,400 adults (Cole et al., NEJM 1999), a drop of 12 bpm or less predicted about twice the risk of death over 6 years. Faster recovery = fitter heart. For an accurate reading, end a brisk walk with Stop and stand or walk slowly for a minute."))
        val eff = walks.mapNotNull { w -> w.avgHr?.let { Metrics.walkingHrPer100Spm(it, w.steps, w.minutes) }?.let { w.start to it } }.sortedBy { it.first }
        add(StatRow("Walking heart efficiency", eff.lastOrNull()?.let { "%.0f".format(it.second) } ?: NEED,
            if (eff.size >= 2) "bpm per 100 steps/min · was %.0f".format(eff.first().second) else "heart rate needed per 100 steps a minute",
            if (eff.size >= 2 && eff.last().second < eff.first().second) Mark.GOOD else if (eff.isEmpty()) Mark.NONE else Mark.OK,
            "Your average heart rate on a walk divided by your pace (steps a minute). As your heart gets fitter it pumps more per beat, so the same pace needs fewer beats and this number falls. Compare it with yourself over weeks; it isn't meant to be compared between people."))
        val todayHr = hrOn(today).map { it.second }.filter { it in 35..220 }
        val mx = todayHr.maxOrNull()
        add(StatRow("Highest today", mx?.let { "$it bpm" } ?: NEED, mx?.let { "${(100 * it / maxHr).roundToInt()}% of your estimated max (${maxHr.roundToInt()})" } ?: "",
            when { mx == null -> Mark.NONE; mx / maxHr >= 0.7 -> Mark.GOOD; else -> Mark.OK },
            "Your highest heart rate today against your estimated maximum (208 − 0.7 × age, Tanaka 2001). Reaching 70%+ at least a few times a week means you're giving your heart real exercise."))
        val nadir = sleep.result?.let { r -> Metrics.heartRateNadir(hrOn(r.bedtime.toLocalDate()) + hrOn(r.wake.toLocalDate()), r.bedtime, r.wake) }
        val nadirInfo = "The time your heart rate bottoms out at night, a simple marker of your body clock. In people who sleep around 11 pm–7 am it's usually around 3–5 am. If it comes late, close to waking, your body is still running hot from a late meal, alcohol, a late workout or stress, and the night is less restful."
        add(StatRow("Body-clock low point", nadir?.let { it.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")) } ?: NEED, "when your heart rate was lowest last night",
            when { nadir == null -> Mark.NONE; sleep.result != null && java.time.Duration.between(nadir, sleep.result.wake).toMinutes() < 60 -> Mark.WATCH; else -> Mark.GOOD }, nadirInfo))

    }

    // ---------------- sleep
    val sleepRows = buildList {
        val week = nights.filter { !it.wakeDay.isBefore(today.minusDays(6)) }
        add(StatRow("Average sleep (7 nights)", week.takeIf { it.isNotEmpty() }?.let { minutesText(it.map { n -> n.asleepMin }.average().toLong()) } ?: NEED,
            "${week.size} night${if (week.size == 1) "" else "s"} recorded by Kaya",
            when { week.isEmpty() -> Mark.NONE; week.map { it.asleepMin }.average() >= 420 -> Mark.GOOD; week.map { it.asleepMin }.average() >= 360 -> Mark.OK; else -> Mark.WATCH },
            "Adults need 7–9 hours (American Academy of Sleep Medicine). Regularly sleeping under 6 hours is linked to weight gain, stronger cravings and higher blood pressure."))
        val debt = Metrics.sleepDebt(nights, today, profile.sleepGoalMin)
        add(StatRow("Sleep debt", if (week.isEmpty()) NEED else minutesText(debt.toLong()), "shortfall below your ${minutesText(profile.sleepGoalMin.toLong())} goal, last 7 nights",
            when { week.isEmpty() -> Mark.NONE; debt <= 120 -> Mark.GOOD; debt <= 300 -> Mark.OK; else -> Mark.WATCH },
            "The total you slept under your sleep goal (Settings) across the last week. A few hours is normal; a big debt shows up as hunger (especially for sugar), low energy and worse workouts. Paying it back with one or two longer nights helps."))
        val spread = Metrics.bedtimeSpread(week)
        add(StatRow("Bedtime consistency", spread?.let { "±$it min" } ?: NEED, "how much your bedtime moves around",
            when { spread == null -> Mark.NONE; spread <= 30 -> Mark.GOOD; spread <= 60 -> Mark.OK; else -> Mark.WATCH },
            "Going to bed at a similar time matters: in 61,000 UK Biobank adults, regular sleepers had about 20–48% lower mortality than the most irregular, and regularity predicted it better than sleep length did (Windred et al., Sleep 2024). Within ±30 minutes is very regular."))
        val jet = Metrics.socialJetlag(nights.filter { !it.wakeDay.isBefore(today.minusDays(27)) })
        add(StatRow("Weekend shift", jet?.let { (if (it >= 0) "+" else "−") + minutesText(abs(it).toLong()) } ?: NEED, "\"social jetlag\": weekend vs. work-night sleep timing",
            when { jet == null -> Mark.NONE; abs(jet) <= 60 -> Mark.GOOD; else -> Mark.WATCH },
            "How much later the middle of your sleep falls on weekends. Over an hour of this \"social jetlag\" is linked to higher BMI and worse metabolic health (Roenneberg et al., Current Biology 2012). Needs work nights and weekend nights."))
        sleep.result?.let { r ->
            add(StatRow("Sleep efficiency", "${(100.0 * r.asleepMin / r.inBedMin.coerceAtLeast(1)).roundToInt()}%", "time asleep out of time in bed",
                if (r.asleepMin * 100 / r.inBedMin.coerceAtLeast(1) >= 85) Mark.GOOD else Mark.WATCH,
                "85% or more is considered good sleep efficiency in sleep medicine. Lower usually means a lot of tossing and turning or waking."))
        }
    }

    // ---------------- recovery & warnings
    val recovery = buildList {
        val strain = last?.let { Metrics.strain(it, earlier) }
        add(StatRow("Body check", when { strain == null || earlier.size < 3 -> NEED; strain.raised -> "Strained"; strain.flags.isNotEmpty() -> "1 sign"; else -> "Normal" },
            when { strain == null || earlier.size < 3 -> "needs 4+ nights to learn your baseline"; strain.flags.isEmpty() -> "last night matched your usual"; else -> strain.flags.joinToString(", ") },
            when { strain == null || earlier.size < 3 -> Mark.NONE; strain.raised -> Mark.WATCH; else -> Mark.GOOD },
            "Compares last night with your own previous nights. A sleeping heart rate 5+ bpm higher, breathing 1.5+ breaths/min faster, skin temperature 0.4 °C+ warmer or HRV 20%+ lower are each common signs of strain: illness coming on, alcohol, a hard workout, a late heavy meal or stress. When two or more appear together, research on wearables (e.g. the TemPredict study, 2022) found they often show up before you feel sick. It's a hint to rest, not a diagnosis."))
        fun dev(name: String, unit: String, cur: Double?, prev: List<Double>, warn: Double, fmt: String, info: String) {
            val d = Metrics.vsBaseline(cur, prev)
            add(StatRow(name, cur?.let { fmt.format(it) + unit } ?: NEED, d?.let { "vs. your usual: " + (if (it >= 0) "+" else "") + fmt.format(it) } ?: "learning your usual",
                when { d == null -> Mark.NONE; d >= warn -> Mark.WATCH; else -> Mark.GOOD }, info))
        }
        dev("Breathing asleep", "/min", last?.breathing, earlier.mapNotNull { it.breathing }, 1.5, "%.1f",
            "Breaths per minute while asleep, from your band's sleep log. A healthy adult range is roughly 12–20; what matters most is your own normal, which is very stable night to night. A jump of 1.5+ often comes with illness, especially chest infections.")
        dev("Skin temperature", " °C", last?.skinTemp, earlier.mapNotNull { it.skinTemp }, 0.4, "%.1f",
            "Wrist skin temperature overnight. The absolute number isn't body temperature and varies with the room, but changes against your own usual are meaningful: a rise of 0.4 °C or more is a common early sign of fever or infection.")
        val hrvBase = earlier.mapNotNull { it.hrv }
        val hrvD = Metrics.vsBaseline(last?.hrv, hrvBase)
        add(StatRow("HRV asleep", last?.hrv?.let { "%.0f ms".format(it) } ?: NEED, hrvD?.let { "vs. your usual: %+.0f ms".format(it) } ?: "learning your usual",
            when { hrvD == null -> Mark.NONE; hrvD < -0.2 * hrvBase.sorted()[hrvBase.size / 2] -> Mark.WATCH; else -> Mark.GOOD },
            "Heart-rate variability: the tiny changes in time between heartbeats. Higher usually means your body is relaxed and recovered. It varies hugely between people, so only compare it with your own baseline; a drop of 20%+ suggests strain, poor sleep or illness."))
    }

    // ---------------- activity & load
    val load = buildList {
        val male = profile.sex == com.kaya.health.engine.Sex.MALE
        // Only days the band actually recorded count; a missing day isn't a rest day.
        val daily = (0L..27L).mapNotNull { i ->
            val d = today.minusDays(i)
            hrOn(d).takeIf { it.size > 100 }?.let { r -> d to Metrics.trimp(r.map { it.second }, restBase, maxHr, male) }
        }.toMap()
        val haveDays = (0L..27L).count { hrOn(today.minusDays(it)).size > 100 }
        val t = daily[today] ?: 0.0
        add(StatRow("Training load today", "%.0f".format(t), "TRIMP: minutes weighted by how hard your heart worked",
            when { t >= 50 -> Mark.GOOD; t >= 15 -> Mark.OK; else -> Mark.NONE },
            "Banister's TRIMP (training impulse), used in sports science since 1975: each minute your heart rate is above 30% of your heart-rate reserve counts, weighted more the harder it was. Rough guide: under 15 = rest day, 15–50 light, 50–100 a solid workout, 100+ hard. Uses your sleeping heart rate as resting and 208 − 0.7 × age as maximum."))
        val week = (0L..6L).sumOf { daily[today.minusDays(it)] ?: 0.0 }
        add(StatRow("Load this week", "%.0f".format(week), "last 7 days of TRIMP",
            when { week >= 300 -> Mark.GOOD; week >= 100 -> Mark.OK; else -> Mark.WATCH },
            "The week's total training load. About 150 minutes a week of moderate exercise (WHO guideline) usually adds up to roughly 300+ here; under 100 means very little time with your heart rate up."))
        val ratio = Metrics.loadRatio(daily, today)
        add(StatRow("Load trend", ratio?.let { "%.1f×".format(it) } ?: NEED,
            if (ratio == null) "needs 14+ days of band data ($haveDays so far)" else "this week vs. your 4-week average",
            when { ratio == null -> Mark.NONE; ratio in 0.8..1.3 -> Mark.GOOD; ratio > 1.5 -> Mark.WATCH; else -> Mark.OK },
            "Acute:chronic workload ratio (Gabbett 2016): this week's load against your last 4 weeks. 0.8–1.3 means steady progress; jumping above 1.5 means you've ramped up fast, which in athletes goes with more injuries (the exact thresholds are debated). Under 0.8 means you're doing less than usual."))
        var mod = 0; var vig = 0; var hrDays = 0
        for (i in 0L..6L) {
            val r = hrOn(today.minusDays(i)).map { it.second }
            if (r.size > 100) { hrDays++; val (m, v) = Metrics.activeMinutes(r, restBase, maxHr); mod += m; vig += v }
        }
        val who = mod + 2 * vig
        add(StatRow("Active minutes this week", if (hrDays == 0) NEED else "$who / 150", "moderate $mod + vigorous $vig (counts double) · $hrDays day${if (hrDays == 1) "" else "s"} of data",
            when { hrDays == 0 -> Mark.NONE; who >= 150 -> Mark.GOOD; who >= 75 -> Mark.OK; else -> Mark.WATCH },
            "The World Health Organization's main guideline: 150 minutes of moderate or 75 of vigorous activity a week (WHO 2020). Measured from your heart rate: moderate = 40–59% of heart-rate reserve (a brisk walk where you can talk but not sing), vigorous = 60%+ (ACSM). Vigorous minutes count double."))
        val stepsWeek = body.motivation?.stepsByDay?.filterKeys { !it.isBefore(today.minusDays(6)) }?.values?.sum() ?: 0L
        add(StatRow("Steps this week", "%,d".format(stepsWeek), "goal %,d".format(profile.weeklySteps),
            if (stepsWeek >= profile.weeklySteps) Mark.GOOD else if (stepsWeek >= profile.weeklySteps / 2) Mark.OK else Mark.WATCH,
            "In a meta-analysis of 15 studies (Paluch et al., Lancet Public Health 2022), adults under 60 had the lowest mortality at about 8,000–10,000 steps a day, with most of the benefit coming early: every extra 1,000 steps a day helps."))
    }

    // ---------------- body & food
    val bodyRows = buildList {
        val kg = body.latestKg ?: profile.fallbackWeightKg
        val h = profile.heightCm / 100
        val waist = log.waist().entries.lastOrNull()?.value
        add(StatRow("Waist-to-height", waist?.let { "%.2f".format(it / profile.heightCm) } ?: NEED, if (waist == null) "log your waist on the Body page" else "waist ÷ height",
            when { waist == null -> Mark.NONE; waist / profile.heightCm < 0.5 -> Mark.GOOD; waist / profile.heightCm < 0.6 -> Mark.OK; else -> Mark.WATCH },
            "Keep your waist under half your height (NICE guideline, 2022). It tracks belly fat, the kind most linked to diabetes and heart disease, better than BMI. 0.5–0.6 = increased risk, over 0.6 = high."))
        add(StatRow("BMI", "%.1f".format(kg / (h * h)), "weight ÷ height²", if (kg / (h * h) < 25) Mark.GOOD else if (kg / (h * h) < 30) Mark.OK else Mark.WATCH,
            "Body mass index. A rough population measure (it can't tell muscle from fat), but useful for tracking. For South Asian adults, WHO and Indian guidelines use lower cut-offs: 23+ overweight, 25+ obese."))
        val intake = body.intake.filter { !it.date.isBefore(today.minusDays(7)) && it.date.isBefore(today) }.map { it.kcal }
        val tdee = body.estimate?.tdee
        val bal = tdee?.let { Metrics.energyBalance(intake, it) }
        add(StatRow("Energy balance", bal?.let { "%+,d kcal".format(it.first.roundToInt()) } ?: NEED,
            bal?.let { "a day, last week · ≈ %+.2f kg/week".format(it.second) } ?: "needs 3+ logged days this week",
            when { bal == null -> Mark.NONE; profile.goalWeightKg != null && profile.goalWeightKg < kg && bal.first < 0 -> Mark.GOOD; else -> Mark.OK },
            "What you ate minus what you burned, averaged over the last week's logged days. About 7,700 kcal = 1 kg of body fat, so a 500 kcal daily deficit is roughly 0.45 kg a week. Uses Kaya's burn estimate, which becomes your real measured burn after about two weeks of food and weight logs."))
        val food = vm.food.value
        val proteinToday = if (food.date == today) food.totalProtein else 0.0
        val refKg = profile.goalWeightKg ?: kg
        add(StatRow("Protein today", "%.1f g/kg".format(proteinToday / refKg), "${proteinToday.roundToInt()} g · goal ${profile.proteinTarget} g",
            when { proteinToday / refKg >= 1.2 -> Mark.GOOD; proteinToday / refKg >= 0.8 -> Mark.OK; else -> Mark.WATCH },
            "Grams of protein per kg of your goal weight. 0.8 g/kg is the minimum for health; 1.2–1.6 g/kg while losing weight helps keep muscle and keeps you fuller (ISSN position stand, 2017)."))
        val fiber = if (food.date == today) food.totalFiber else 0.0
        add(StatRow("Fiber today", "${fiber.roundToInt()} g", "goal 25–30 g",
            when { fiber >= 25 -> Mark.GOOD; fiber >= 15 -> Mark.OK; else -> Mark.WATCH },
            "People eating 25–29 g of fiber a day had 15–30% lower risk of heart disease, diabetes and early death than those eating little (Reynolds et al., Lancet 2019). Dal, sprouts, vegetables, fruit and whole grains are the easiest sources."))
    }
    val rhythmRows = buildList {
        val nowH = java.time.LocalTime.now().hour
        val todaySteps = band.savedHourlySteps(today)
        val (activeH, still) = todaySteps?.let { Metrics.activeHours(it, nowH) } ?: (0 to 0)
        add(StatRow("Active hours today", if (todaySteps == null) NEED else "$activeH", "hours with 250+ steps, 8 am – 10 pm",
            when { todaySteps == null -> Mark.NONE; activeH >= minOf(10, maxOf(1, nowH - 8)) -> Mark.GOOD; else -> Mark.OK },
            "Breaking up sitting matters on its own, even for people who exercise: long unbroken sitting is linked to worse blood sugar and heart health, and short walks every hour help (e.g. Dunstan et al., Diabetes Care 2012). Aim for some movement in most waking hours."))
        add(StatRow("Longest still stretch", if (todaySteps == null) NEED else "$still h", "hours in a row under 250 steps today",
            when { todaySteps == null -> Mark.NONE; still <= 2 -> Mark.GOOD; still <= 4 -> Mark.OK; else -> Mark.WATCH },
            "The longest run of hours you barely moved. Under 2 hours is great; getting up for 2–5 minutes every 30–60 minutes is the usual advice."))
        val past = (1L..14L).mapNotNull { band.savedHourlySteps(today.minusDays(it)) }
        val rh = Metrics.rhythm(past)
        add(StatRow("Routine stability", rh?.let { "%.2f".format(it.first) } ?: NEED, rh?.let { "interdaily stability (0–1) · ${past.size} days" } ?: "needs 3+ full days of steps (${past.size} so far)",
            when { rh == null -> Mark.NONE; rh.first >= 0.5 -> Mark.GOOD; rh.first >= 0.35 -> Mark.OK; else -> Mark.WATCH },
            "How similar your daily activity pattern is from one day to the next (Van Someren et al. 1999). Regular routines (higher numbers) go with better sleep, mood and metabolic health in large studies such as UK Biobank; very irregular days are linked to higher risk of depression and heart disease."))
        add(StatRow("Activity fragmentation", rh?.let { "%.2f".format(it.second) } ?: NEED, "intradaily variability (0–2), lower = smoother",
            when { rh == null -> Mark.NONE; rh.second <= 0.8 -> Mark.GOOD; rh.second <= 1.1 -> Mark.OK; else -> Mark.WATCH },
            "How broken-up your day is between activity and rest. Lower means solid stretches of being active and resting; high values (often seen with poor sleep or a disrupted routine) are linked to worse health outcomes."))
        val stress = band.vitals.value[Vital.STRESS.metric]?.filter { it.first.toLocalDate() == today }?.map { it.second }
        add(StatRow("High stress today", stress?.takeIf { it.isNotEmpty() }?.let { minutesText((it.count { v -> v >= 60 } * 5).toLong()) } ?: NEED,
            stress?.takeIf { it.isNotEmpty() }?.let { "calm (under 30): " + minutesText((it.count { v -> v < 30 } * 5).toLong()) } ?: "",
            when { stress.isNullOrEmpty() -> Mark.NONE; stress.count { it >= 60 } * 5 <= 60 -> Mark.GOOD; stress.count { it >= 60 } * 5 <= 180 -> Mark.OK; else -> Mark.WATCH },
            "Time today your band rated your stress 60+ (from heart-rate variability), and time under 30. Stress spikes are normal; what matters is getting calm stretches in between. Long high stretches are a cue for a breathing break or a walk."))
        val glasses = log.water(today); val goal = com.kaya.health.engine.Insights.waterGlasses(body.latestKg ?: profile.fallbackWeightKg, profile.waterGoalGlasses)
        add(StatRow("Water today", "$glasses / $goal", "glasses of 250 ml",
            if (glasses >= goal) Mark.GOOD else if (glasses >= goal / 2) Mark.OK else Mark.WATCH,
            "About 30–35 ml per kg of body weight a day from drinks (EFSA, 2010), more in heat or when active. Drinking water before meals also helps with weight loss in trials (Dennis et al., Obesity 2010)."))
    }

    val foodRows = buildList {
        val food = vm.food.value
        val todayEntries = if (food.date == today) food.entries else emptyList()
        val win = Metrics.eatingWindow(todayEntries.map { it.time.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime() })
        add(StatRow("Eating window", win?.let { minutesText(it.toLong()) } ?: NEED, "first to last food logged today",
            when { win == null -> Mark.NONE; win <= 12 * 60 -> Mark.GOOD; win <= 14 * 60 -> Mark.OK; else -> Mark.WATCH },
            "Keeping meals within about 10–12 hours a day (time-restricted eating) helped with weight and blood sugar in several trials, mostly because it cuts late-night snacking (e.g. Sutton et al., Cell Metabolism 2018). It only counts if you log everything, drinks included."))
        val prev = if (food.date == today) food.previousDay else emptyList()
        val lastMeal = prev.maxOfOrNull { it.time }?.atZone(java.time.ZoneId.systemDefault())?.toLocalDateTime()
        val gap = lastMeal?.let { lm -> sleep.result?.let { java.time.Duration.between(lm, it.bedtime).toMinutes() } }?.takeIf { it > -120 }
        add(StatRow("Last meal before bed", gap?.let { if (it < 0) "after bedtime" else minutesText(it) } ?: NEED, "yesterday's last food to last night's sleep",
            when { gap == null -> Mark.NONE; gap >= 180 -> Mark.GOOD; gap >= 120 -> Mark.OK; else -> Mark.WATCH },
            "Eating within 2–3 hours of sleep raises night-time heart rate, cuts deep sleep and is linked to weight gain (late eaters lost less weight in Garaulet et al., Int J Obesity 2013). Finishing dinner 3+ hours before bed is a simple fix."))
        val late = Metrics.lateShare(prev.map { it.time.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime() to it.kcal })
        add(StatRow("Late-evening calories", late?.let { "${(it * 100).roundToInt()}%" } ?: NEED, "share of yesterday's calories after 8 pm",
            when { late == null -> Mark.NONE; late <= 0.25 -> Mark.GOOD; late <= 0.4 -> Mark.OK; else -> Mark.WATCH },
            "Eating more of the day's food late in the evening goes with higher body weight and worse blood sugar, independent of total calories, in several studies. Under a quarter after 8 pm is a good target."))
        val days7 = body.intake.filter { !it.date.isBefore(today.minusDays(7)) && it.date.isBefore(today) }.map { it.kcal }
        val cv = Metrics.calorieVariation(days7)
        add(StatRow("Calorie consistency", cv?.let { "±${(it * 100).roundToInt()}%" } ?: NEED, "how much daily calories swing, last week",
            when { cv == null -> Mark.NONE; cv <= 0.2 -> Mark.GOOD; cv <= 0.35 -> Mark.OK; else -> Mark.WATCH },
            "Big swings (strict days, then a feast) make weight loss harder and cravings stronger than eating a similar amount each day. Within ±20% is steady."))
        val logged = body.intake.filter { it.kcal > 0 }.map { it.date }.toSet() + (if (todayEntries.isNotEmpty()) setOf(today) else emptySet())
        val st = Metrics.streak(logged, today)
        add(StatRow("Logging streak", "$st day${if (st == 1) "" else "s"}", "days in a row with food logged",
            if (st >= 7) Mark.GOOD else if (st >= 3) Mark.OK else Mark.WATCH,
            "People who log what they eat consistently lose about twice as much weight as those who don't (Hollis et al., Am J Prev Med 2008). Even rough logs count."))
    }
    return StatsData(heart, sleepRows, recovery, load, bodyRows, rhythmRows, foodRows)
}
