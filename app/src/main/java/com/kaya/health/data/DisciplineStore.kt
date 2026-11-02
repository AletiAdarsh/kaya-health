package com.kaya.health.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

data class Habit(val id: String, val name: String, val created: LocalDate)
data class Intention(val goal: String, val ifThen: String, val done: Boolean?)
data class MoodEntry(val mood: Int, val energy: Int, val note: String)
data class Craving(val at: LocalDateTime, val food: String, val kcal: Double?, val trigger: String, val outcome: String)
data class FocusSession(val start: LocalDateTime, val minutes: Int, val label: String)
data class Challenge(val id: String, val name: String, val start: LocalDate, val days: Int, val done: Set<LocalDate>)
data class Reward(val kg: Double, val text: String)
data class WeekReview(val wentWell: String, val slipped: String, val change: String)

/**
 * Everything for the Discipline page, on the phone only (and in backups). One SharedPreferences file
 * ("kaya_discipline") holding small JSON blobs.
 */
class DisciplineStore(context: Context) {
    private val p = context.getSharedPreferences("kaya_discipline", Context.MODE_PRIVATE)

    private fun arr(key: String) = runCatching { JSONArray(p.getString(key, "[]")) }.getOrDefault(JSONArray())
    private fun obj(key: String) = runCatching { JSONObject(p.getString(key, "{}")) }.getOrDefault(JSONObject())
    private fun put(key: String, v: Any) = p.edit { putString(key, v.toString()) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    // ---- habits
    fun habits(): List<Habit> = arr("habits").objects().map { Habit(it.getString("id"), it.getString("name"), LocalDate.parse(it.getString("created"))) }
    fun saveHabits(list: List<Habit>) = put("habits", JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("created", it.created.toString())) }
    })
    /** "habitId|yyyy-mm-dd" for every tick. */
    fun checks(): Set<String> = p.getStringSet("checks", emptySet()) ?: emptySet()
    fun setCheck(habitId: String, date: LocalDate, on: Boolean) {
        val k = "$habitId|$date"
        p.edit { putStringSet("checks", if (on) checks() + k else checks() - k) }
    }

    // ---- intention per day
    fun intention(date: LocalDate): Intention? = obj("intentions").optJSONObject(date.toString())?.let {
        Intention(it.optString("goal"), it.optString("ifThen"), if (it.has("done") && !it.isNull("done")) it.getBoolean("done") else null)
    }
    fun saveIntention(date: LocalDate, i: Intention) = put("intentions", obj("intentions").put(date.toString(),
        JSONObject().put("goal", i.goal).put("ifThen", i.ifThen).put("done", i.done ?: JSONObject.NULL)))

    // ---- mood per day
    fun moods(): Map<LocalDate, MoodEntry> = obj("moods").let { o ->
        o.keys().asSequence().associate { k -> LocalDate.parse(k) to o.getJSONObject(k).let { MoodEntry(it.getInt("mood"), it.getInt("energy"), it.optString("note")) } }
    }
    fun saveMood(date: LocalDate, m: MoodEntry) = put("moods", obj("moods").put(date.toString(),
        JSONObject().put("mood", m.mood).put("energy", m.energy).put("note", m.note)))

    // ---- cravings
    fun cravings(): List<Craving> = arr("cravings").objects().map {
        Craving(LocalDateTime.parse(it.getString("at")), it.optString("food"), it.optDouble("kcal").takeIf { k -> !k.isNaN() }, it.optString("trigger"), it.optString("outcome"))
    }
    fun addCraving(c: Craving) = put("cravings", JSONArray().apply {
        (cravings() + c).takeLast(500).forEach {
            put(JSONObject().put("at", it.at.toString()).put("food", it.food).put("kcal", it.kcal ?: JSONObject.NULL).put("trigger", it.trigger).put("outcome", it.outcome))
        }
    })

    // ---- focus sessions
    fun focus(): List<FocusSession> = arr("focus").objects().map { FocusSession(LocalDateTime.parse(it.getString("start")), it.getInt("min"), it.optString("label")) }
    fun addFocus(f: FocusSession) = put("focus", JSONArray().apply {
        (focus() + f).takeLast(1000).forEach { put(JSONObject().put("start", it.start.toString()).put("min", it.minutes).put("label", it.label)) }
    })

    // ---- challenges
    fun challenges(): List<Challenge> = arr("challenges").objects().map { o ->
        val d = o.optJSONArray("done") ?: JSONArray()
        Challenge(o.getString("id"), o.getString("name"), LocalDate.parse(o.getString("start")), o.getInt("days"), (0 until d.length()).map { LocalDate.parse(d.getString(it)) }.toSet())
    }
    fun saveChallenges(list: List<Challenge>) = put("challenges", JSONArray().apply {
        list.forEach { c -> put(JSONObject().put("id", c.id).put("name", c.name).put("start", c.start.toString()).put("days", c.days)
            .put("done", JSONArray().apply { c.done.sorted().forEach { put(it.toString()) } })) }
    })

    // ---- why and rewards
    var why: String
        get() = p.getString("why", "") ?: ""
        set(v) = p.edit { putString("why", v) }
    fun rewards(): List<Reward> = arr("rewards").objects().map { Reward(it.getDouble("kg"), it.getString("text")) }.sortedByDescending { it.kg }
    fun saveRewards(list: List<Reward>) = put("rewards", JSONArray().apply { list.forEach { put(JSONObject().put("kg", it.kg).put("text", it.text)) } })

    // ---- weekly reviews, keyed by the Monday of the week
    fun review(monday: LocalDate): WeekReview? = obj("reviews").optJSONObject(monday.toString())?.let {
        WeekReview(it.optString("well"), it.optString("slipped"), it.optString("change"))
    }
    fun saveReview(monday: LocalDate, r: WeekReview) = put("reviews", obj("reviews").put(monday.toString(),
        JSONObject().put("well", r.wentWell).put("slipped", r.slipped).put("change", r.change)))
}
