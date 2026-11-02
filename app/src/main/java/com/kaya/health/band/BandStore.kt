package com.kaya.health.band

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Keeps a copy of everything read from the band, on the phone only (app-private storage).
 * The band deletes a day once any app has synced it, so this is Kaya's own history.
 * A day is only overwritten by a copy holding at least as much data, never a smaller one.
 */
class BandStore(context: Context) {
    private val dir = File(context.filesDir, "band").apply { mkdirs() }

    private fun file(date: LocalDate, metric: Int) = File(dir, "$date-m$metric.json")

    fun save(date: LocalDate, metric: Int, s: Series) {
        val existing = load(date, metric)
        if (existing != null && weight(existing) > weight(s)) return
        val o = JSONObject()
            .put("start", s.start.toString())
            .put("metric", s.metric)
            .put("interval", s.interval ?: JSONObject.NULL)
            .put("samples", s.samples.joinToString(","))
            .put("hourly", JSONObject(s.hourly.mapValues { it.value.joinToString(",") }))
            .put("totals", JSONObject(s.totals))
        file(date, metric).writeText(o.toString())
    }

    /** Every day Kaya has saved for [metric], newest first. */
    fun dates(metric: Int): List<LocalDate> =
        dir.listFiles()?.mapNotNull { f -> Regex("""(\d{4}-\d{2}-\d{2})-m$metric\.json""").matchEntire(f.name)?.groupValues?.get(1) }
            ?.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }?.sortedDescending() ?: emptyList()

    fun load(date: LocalDate, metric: Int): Series? = try {
        val f = file(date, metric)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            fun ints(s: String) = if (s.isBlank()) IntArray(0) else s.split(",").map { it.toInt() }.toIntArray()
            val hourly = o.optJSONObject("hourly")?.let { h -> h.keys().asSequence().associateWith { k -> ints(h.getString(k)) } } ?: emptyMap()
            val totals = o.optJSONObject("totals")?.let { t -> t.keys().asSequence().associateWith { k -> t.getLong(k) } } ?: emptyMap()
            Series(
                start = LocalDateTime.parse(o.getString("start")),
                metric = o.getInt("metric"),
                interval = if (o.isNull("interval")) null else o.getInt("interval"),
                samples = ints(o.getString("samples")),
                hourly = hourly,
                totals = totals,
            )
        }
    } catch (_: Exception) { null }

    private fun weight(s: Series) = s.samples.count { it > 0 } + s.hourly.values.sumOf { a -> a.count { it > 0 } }
}
