package com.kaya.health.band

import android.content.Context
import androidx.core.content.edit
import com.kaya.health.engine.Walk
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** Walks you recorded with Start walk, plus which auto-detected walks you said weren't walks. On the phone only. */
class WalkStore(context: Context) {
    private val prefs = context.getSharedPreferences("kaya_walks", Context.MODE_PRIVATE)

    fun recorded(): List<Walk> = runCatching {
        val a = JSONArray(prefs.getString("walks", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Walk(
                LocalDateTime.parse(o.getString("start")), LocalDateTime.parse(o.getString("end")), o.getInt("steps"),
                o.optInt("avgHr", -1).takeIf { it > 0 }, o.optInt("maxHr", -1).takeIf { it > 0 }, detected = false,
            )
        }
    }.getOrDefault(emptyList())

    fun add(w: Walk) {
        val all = (recorded() + w).takeLast(500)
        val a = JSONArray()
        all.forEach {
            a.put(JSONObject().put("start", it.start.toString()).put("end", it.end.toString()).put("steps", it.steps)
                .put("avgHr", it.avgHr ?: -1).put("maxHr", it.maxHr ?: -1))
        }
        prefs.edit { putString("walks", a.toString()) }
    }

    fun recordedOn(date: LocalDate) = recorded().filter { it.start.toLocalDate() == date }

    fun dismissed(): Set<String> = prefs.getStringSet("dismissed", emptySet()) ?: emptySet()
    fun dismiss(key: String) = prefs.edit { putStringSet("dismissed", dismissed() + key) }
}
