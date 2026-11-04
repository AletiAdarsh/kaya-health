package com.kaya.health.data

import android.content.Context
import androidx.core.content.edit
import com.kaya.health.engine.SleepEpoch
import com.kaya.health.engine.SleepResult
import com.kaya.health.engine.SleepStage
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Each night's full analysis, keyed by the day you woke up, so the Sleep page can show any earlier day exactly as it
 * looked that morning. Lives in the "kaya_bodylog" prefs, so it is included in backups. About 1 KB a night.
 */
class SleepArchive(context: Context) {
    private val p = context.getSharedPreferences("kaya_bodylog", Context.MODE_PRIVATE)
    private fun all() = runCatching { JSONObject(p.getString("sleepDays", "{}")) }.getOrDefault(JSONObject())

    data class Day(val result: SleepResult, val breathingAvg: Double?, val breathingRange: Pair<Int, Int>?)

    fun days(): Set<LocalDate> = all().keys().asSequence().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSortedSet()

    fun save(wakeDay: LocalDate, r: SleepResult, breathingAvg: Double?, breathingRange: Pair<Int, Int>?) = p.edit {
        val j = JSONObject()
            .put("bed", r.bedtime.toString()).put("wake", r.wake.toString())
            .put("stages", r.epochs.joinToString("") { it.stage.name.take(1) })
            .put("epoch0", r.epochs.firstOrNull()?.start?.toString() ?: r.bedtime.toString())
            .put("epochSec", r.epochs.zipWithNext().firstOrNull()?.let { (a, b) -> java.time.Duration.between(a.start, b.start).seconds } ?: 30)
            .put("asleep", r.asleepMin).put("inBed", r.inBedMin).put("awakenings", r.awakenings)
            .put("hr", r.avgSleepingHr).put("low", r.lowestHr).put("awakeHr", r.awakeHr)
            .put("score", r.score).put("coverage", r.dataCoverage)
            .put("min", JSONObject().apply { r.minutes.forEach { (s, m) -> put(s.name, m) } })
            .put("parts", JSONArray(r.scoreParts.map { JSONArray(listOf(it.first, it.second)) }))
            .put("br", breathingAvg ?: JSONObject.NULL)
            .put("brMin", breathingRange?.first ?: JSONObject.NULL).put("brMax", breathingRange?.second ?: JSONObject.NULL)
        val o = all().put(wakeDay.toString(), j)
        o.keys().asSequence().toList().filter { LocalDate.parse(it).isBefore(LocalDate.now().minusDays(400)) }.forEach { o.remove(it) }
        putString("sleepDays", o.toString())
    }

    fun load(wakeDay: LocalDate): Day? = runCatching {
        val j = all().optJSONObject(wakeDay.toString()) ?: return null
        val byLetter = SleepStage.entries.associateBy { it.name.take(1) }
        val t0 = LocalDateTime.parse(j.getString("epoch0"))
        val step = j.optLong("epochSec", 30)
        val epochs = j.getString("stages").mapIndexedNotNull { i, c -> byLetter[c.toString()]?.let { SleepEpoch(t0.plusSeconds(step * i), null, it) } }
        val mins = j.getJSONObject("min").let { m -> SleepStage.entries.associateWith { m.optInt(it.name, 0) } }
        val parts = j.getJSONArray("parts").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { x -> x.getString(0) to x.getInt(1) } } }
        Day(
            SleepResult(
                LocalDateTime.parse(j.getString("bed")), LocalDateTime.parse(j.getString("wake")), epochs, mins,
                j.getInt("asleep"), j.getInt("inBed"), j.getInt("awakenings"), j.getInt("hr"), j.getInt("low"), j.getInt("awakeHr"),
                j.getInt("score"), parts, j.getDouble("coverage"),
            ),
            j.optDouble("br").takeIf { !it.isNaN() },
            if (j.isNull("brMin") || j.isNull("brMax")) null else j.getInt("brMin") to j.getInt("brMax"),
        )
    }.getOrNull()
}
