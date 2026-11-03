package com.kaya.health.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Small personal logs that live only on the phone: glasses of water per day, waist measurements,
 * hours slept per night (for insights) and progress photos (files in app storage, never uploaded).
 */
class BodyLog(private val context: Context) {
    private val p = context.getSharedPreferences("kaya_bodylog", Context.MODE_PRIVATE)
    private fun obj(key: String) = runCatching { JSONObject(p.getString(key, "{}")) }.getOrDefault(JSONObject())

    // ---- water, in 250 ml glasses
    fun water(date: LocalDate): Int = obj("water").optInt(date.toString(), 0)
    fun setWater(date: LocalDate, glasses: Int) = p.edit { putString("water", obj("water").put(date.toString(), glasses.coerceIn(0, 40)).toString()) }

    // ---- waist, cm
    fun waist(): Map<LocalDate, Double> = obj("waist").let { o -> o.keys().asSequence().associate { LocalDate.parse(it) to o.getDouble(it) } }.toSortedMap()
    fun setWaist(date: LocalDate, cm: Double?) = p.edit {
        val o = obj("waist"); if (cm == null) o.remove(date.toString()) else o.put(date.toString(), cm); putString("waist", o.toString())
    }

    // ---- minutes asleep, keyed by the day you woke up
    fun nights(): Map<LocalDate, Int> = obj("nights").let { o -> o.keys().asSequence().associate { LocalDate.parse(it) to o.getInt(it) } }
    fun saveNight(wakeDay: LocalDate, asleepMin: Int) = p.edit {
        val o = obj("nights").put(wakeDay.toString(), asleepMin)
        // keep about a year
        o.keys().asSequence().toList().filter { LocalDate.parse(it).isBefore(LocalDate.now().minusDays(400)) }.forEach { o.remove(it) }
        putString("nights", o.toString())
    }

    // ---- full night records (for baselines and trends), keyed by wake-up day
    fun nightRecords(): List<com.kaya.health.engine.NightRecord> = obj("nightRecs").let { o ->
        o.keys().asSequence().mapNotNull { k ->
            runCatching {
                val j = o.getJSONObject(k)
                com.kaya.health.engine.NightRecord(
                    LocalDate.parse(k), java.time.LocalDateTime.parse(j.getString("bed")), java.time.LocalDateTime.parse(j.getString("wake")),
                    j.getInt("min"), j.getInt("hr"),
                    j.optDouble("br").takeIf { !it.isNaN() }, j.optDouble("temp").takeIf { !it.isNaN() }, j.optDouble("hrv").takeIf { !it.isNaN() },
                )
            }.getOrNull()
        }.sortedBy { it.wakeDay }.toList()
    }
    fun saveNightRecord(n: com.kaya.health.engine.NightRecord) = p.edit {
        val o = obj("nightRecs").put(n.wakeDay.toString(), JSONObject()
            .put("bed", n.bed.toString()).put("wake", n.wake.toString()).put("min", n.asleepMin).put("hr", n.avgHr)
            .put("br", n.breathing ?: JSONObject.NULL).put("temp", n.skinTemp ?: JSONObject.NULL).put("hrv", n.hrv ?: JSONObject.NULL))
        o.keys().asSequence().toList().filter { LocalDate.parse(it).isBefore(LocalDate.now().minusDays(400)) }.forEach { o.remove(it) }
        putString("nightRecs", o.toString())
    }

    // ---- naps, keyed by the day they started: ["start|end", ...]
    fun naps(date: LocalDate): List<Pair<java.time.LocalDateTime, java.time.LocalDateTime>> =
        obj("naps").optJSONArray(date.toString())?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                runCatching { a.getString(i).split("|").let { java.time.LocalDateTime.parse(it[0]) to java.time.LocalDateTime.parse(it[1]) } }.getOrNull()
            }
        } ?: emptyList()
    /** Adds [found] to what's saved for [date] (overlaps merged). Never forgets a nap because a later pass saw less. */
    fun addNaps(date: LocalDate, found: List<Pair<java.time.LocalDateTime, java.time.LocalDateTime>>): List<Pair<java.time.LocalDateTime, java.time.LocalDateTime>> {
        val merged = com.kaya.health.engine.NapFinder.merge(naps(date), found)
        if (found.isNotEmpty()) p.edit {
            val o = obj("naps").put(date.toString(), org.json.JSONArray(merged.map { "${it.first}|${it.second}" }))
            o.keys().asSequence().toList().filter { LocalDate.parse(it).isBefore(LocalDate.now().minusDays(400)) }.forEach { o.remove(it) }
            putString("naps", o.toString())
        }
        return merged
    }

    // ---- progress photos
    private val photoDir get() = File(context.filesDir, "progress").apply { mkdirs() }
    fun photos(): List<File> = photoDir.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()
    /** Copies a picked image into Kaya's private storage, named by date. */
    fun addPhoto(uri: Uri, date: LocalDate): File? = runCatching {
        var f = File(photoDir, "$date.jpg"); var n = 2
        while (f.exists()) f = File(photoDir, "$date-${n++}.jpg")
        // Downscale to ~1280 px and re-encode, so a photo is ~200 KB and backups stay small.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)!!.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Not an image")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
        f
    }.getOrNull()
    fun deletePhoto(f: File) { if (f.parentFile == photoDir) f.delete() }
    fun photoDate(f: File): LocalDate? = runCatching { LocalDate.parse(f.nameWithoutExtension.take(10)) }.getOrNull()
}
