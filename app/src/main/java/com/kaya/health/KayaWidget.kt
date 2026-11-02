package com.kaya.health

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.edit
import com.kaya.health.band.BandStore
import com.kaya.health.data.BodyLog
import com.kaya.health.data.ProfileStore
import com.kaya.health.engine.Insights
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Home-screen widget: calories left, steps, water, a +1 glass button and Craving SOS.
 * The app saves today's food numbers into "kaya_widget" whenever it refreshes; the widget never reads Health Connect itself.
 */
class KayaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WATER) {
            val log = BodyLog(context)
            val today = LocalDate.now()
            log.setWater(today, log.water(today) + 1)
            refresh(context)
        }
    }

    companion object {
        private const val ACTION_WATER = "com.kaya.health.WIDGET_WATER"
        private const val PREFS = "kaya_widget"

        /** Called by the app after loading today's food. */
        fun saveFood(context: Context, eaten: Double, target: Double?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString("date", LocalDate.now().toString()); putFloat("eaten", eaten.toFloat())
                if (target != null) putFloat("target", target.toFloat()) else remove("target")
            }
            refresh(context)
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, KayaWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val today = LocalDate.now()
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val fresh = p.getString("date", "") == today.toString()
            val eaten = if (fresh) p.getFloat("eaten", 0f).toDouble() else 0.0
            val target = if (p.contains("target")) p.getFloat("target", 0f).toDouble() else null
            val steps = runCatching { BandStore(context).load(today, 0)?.totals?.get("steps") }.getOrNull() ?: 0L
            val profile = runCatching { ProfileStore(context).load() }.getOrNull()
            val water = BodyLog(context).water(today)
            val waterGoal = Insights.waterGlasses(profile?.fallbackWeightKg ?: 70.0, profile?.waterGoalGlasses ?: 0)

            val v = RemoteViews(context.packageName, R.layout.widget_kaya)
            v.setTextViewText(R.id.w_kcal, when {
                target == null -> "${eaten.roundToInt()} kcal eaten"
                eaten <= target -> "%,d kcal left".format((target - eaten).roundToInt())
                else -> "%,d kcal over".format((eaten - target).roundToInt())
            })
            v.setTextViewText(R.id.w_line, "%,d steps · water %d/%d".format(steps, water, waterGoal))
            v.setTextViewText(R.id.w_water, "+ Water ($water)")

            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(context, 10, open, flags))
            val sos = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, "sos")
            v.setOnClickPendingIntent(R.id.w_sos, PendingIntent.getActivity(context, 11, sos, flags))
            val addWater = Intent(context, KayaWidget::class.java).setAction(ACTION_WATER)
            v.setOnClickPendingIntent(R.id.w_water, PendingIntent.getBroadcast(context, 12, addWater, flags))
            manager.updateAppWidget(ids, v)
        }
    }
}
