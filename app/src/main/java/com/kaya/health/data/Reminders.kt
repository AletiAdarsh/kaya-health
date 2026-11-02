package com.kaya.health.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kaya.health.band.BandStore
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Optional nudges, each at a time the user picks. Every one checks first and stays quiet when there's
 * nothing to say (already weighed in, already moving, …). All on the phone; nothing is sent anywhere.
 */
object Reminders {
    enum class Kind(val label: String, val blurb: String, val defaultHour: Int, val defaultMinute: Int, val defaultOn: Boolean) {
        WEIGH_IN("Morning weigh-in", "Only if you haven't weighed in yet", 7, 30, true),
        FOOD_CHECK("Evening food check", "Calories and protein left for the day", 20, 0, true),
        BEDTIME("Bedtime", "A nudge to wind down", 23, 0, false),
        MOVE("Move nudge", "If the band saw almost no steps for 2 hours (10 am–8 pm)", 10, 0, false),
        CRAVING("Craving heads-up", "About an hour before your usual craving time, once Craving SOS sees a pattern", 0, 0, true),
    }

    data class Setting(val on: Boolean, val hour: Int, val minute: Int)

    private const val PREFS = "kaya_reminders"
    private const val CHANNEL = "kaya_reminders"

    fun get(context: Context, k: Kind): Setting {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Setting(p.getBoolean("${k.name}_on", k.defaultOn), p.getInt("${k.name}_h", k.defaultHour), p.getInt("${k.name}_m", k.defaultMinute))
    }

    fun set(context: Context, k: Kind, s: Setting) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("${k.name}_on", s.on); putInt("${k.name}_h", s.hour); putInt("${k.name}_m", s.minute)
        }
        schedule(context)
    }

    fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** (Re)plans every reminder. The move nudge checks every 2 hours; the others once a day at their time. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        Kind.entries.forEach { k ->
            val s = get(context, k)
            val name = "kaya-reminder-${k.name}"
            if (!s.on) { wm.cancelUniqueWork(name); return@forEach }
            val data = Data.Builder().putString("kind", k.name).build()
            val req = if (k == Kind.MOVE) {
                PeriodicWorkRequestBuilder<ReminderWorker>(2, TimeUnit.HOURS).setInputData(data).build()
            } else if (k == Kind.CRAVING) {
                PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.HOURS).setInputData(data).build()
            } else {
                val now = LocalDateTime.now()
                var next = now.toLocalDate().atTime(s.hour, s.minute)
                if (!next.isAfter(now)) next = next.plusDays(1)
                PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES).setInputData(data).build()
            }
            wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    fun notify(context: Context, id: Int, title: String, text: String, sos: Boolean = false) {
        if (!canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Weigh-in, food check, bedtime and move nudges you switched on in Kaya"
            })
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.apply { if (sos) putExtra(com.kaya.health.MainActivity.EXTRA_OPEN, "sos") }
        val pi = open?.let { PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val kind = runCatching { Reminders.Kind.valueOf(inputData.getString("kind") ?: "") }.getOrNull() ?: return Result.success()
        if (!Reminders.get(ctx, kind).on) return Result.success()
        val profile = ProfileStore(ctx).load()
        val repo = HealthRepository(ctx)
        val today = LocalDate.now()
        when (kind) {
            Reminders.Kind.WEIGH_IN -> {
                val weighed = runCatching {
                    repo.weights(today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(), Instant.now()).isNotEmpty()
                }.getOrDefault(false)
                if (!weighed) Reminders.notify(ctx, 1, "Morning weigh-in", "Before breakfast, after the toilet. Ten seconds, and Kaya keeps your plan honest.")
            }
            Reminders.Kind.FOOD_CHECK -> {
                val food = runCatching { repo.food(today) }.getOrDefault(emptyList())
                val kcal = food.sumOf { it.kcal }
                val protein = food.sumOf { it.proteinG ?: 0.0 }
                val target = profile.intakeKcal?.toDouble()
                val text = when {
                    food.isEmpty() -> "Nothing logged today yet. Even a rough log helps Kaya work out your real burn."
                    target == null -> "${kcal.roundToInt()} kcal and ${protein.roundToInt()} g protein logged today."
                    kcal <= target -> "${(target - kcal).roundToInt()} kcal left and ${(profile.proteinTarget - protein).coerceAtLeast(0.0).roundToInt()} g protein to go."
                    else -> "${(kcal - target).roundToInt()} kcal over today. No stress: tomorrow is a new day."
                }
                Reminders.notify(ctx, 2, "Food check", text)
            }
            Reminders.Kind.BEDTIME -> Reminders.notify(ctx, 3, "Time to wind down", "Good sleep makes tomorrow's hunger and energy easier. Phone down soon?")
            Reminders.Kind.CRAVING -> {
                val ds = DisciplineStore(ctx)
                val p = com.kaya.health.engine.Discipline.cravingPattern(ds.cravings().map { it.at to (it.trigger to it.outcome) }, today.minusDays(29))
                val hour = p.topHour ?: return Result.success()
                val now = LocalTime.now()
                // Fire once a day, in the hour before the usual craving time.
                val prefs = ctx.getSharedPreferences("kaya_reminders", Context.MODE_PRIVATE)
                if (now.hour != (hour + 23) % 24 || prefs.getString("craving_last", "") == today.toString()) return Result.success()
                prefs.edit { putString("craving_last", today.toString()) }
                val plan = ds.intention(today)?.ifThen?.takeIf { it.isNotBlank() }
                Reminders.notify(
                    ctx, 5, "Craving time is coming up",
                    "Around ${if (hour % 12 == 0) 12 else hour % 12} ${if (hour < 12) "am" else "pm"} is when cravings usually hit" +
                        (p.topTrigger?.let { ", often when ${it.lowercase()}" } ?: "") + ". " +
                        (plan?.let { "Your plan: $it" } ?: "Have a protein snack or a short walk ready. Craving SOS is one tap away."),
                    sos = true,
                )
            }
            Reminders.Kind.MOVE -> {
                val now = LocalTime.now()
                if (now.hour !in 10..20) return Result.success()
                val steps = BandStore(ctx).load(today, 0)?.hourly?.get("steps") ?: return Result.success()
                val last2 = (now.hour - 2 until now.hour).filter { it in steps.indices }.sumOf { steps[it] }
                if (last2 < 300) Reminders.notify(ctx, 4, "Time to move", "Only $last2 steps in the last 2 hours. A 5-minute walk now adds up over the week.")
            }
        }
        return Result.success()
    }
}

@Suppress("unused") private fun unusedChrono() = ChronoUnit.DAYS
