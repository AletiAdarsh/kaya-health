package com.kaya.health.data

import android.content.Context
import androidx.core.content.edit
import com.kaya.health.engine.Profile
import com.kaya.health.engine.Sex
import org.json.JSONArray
import org.json.JSONObject

/** Profile lives only on the phone (SharedPreferences). */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("kaya_profile", Context.MODE_PRIVATE)

    fun load(): Profile = Profile(
        heightCm = prefs.getFloat("heightCm", 175f).toDouble(),
        age = prefs.getInt("age", 25),
        sex = if (prefs.getString("sex", "MALE") == "FEMALE") Sex.FEMALE else Sex.MALE,
        fallbackWeightKg = prefs.getFloat("weightKg", 80f).toDouble(),
        deficitKcal = prefs.getInt("deficit", 500),
        setupDone = prefs.getBoolean("setupDone", false),
        goalWeightKg = prefs.getFloat("goalKg", -1f).takeIf { it > 0 }?.toDouble(),
        rateKgPerWeek = prefs.getFloat("rate", 1f).toDouble(),
        goalStartDate = prefs.getString("goalStart", null),
        goalStartWeightKg = prefs.getFloat("goalStartKg", -1f).takeIf { it > 0 }?.toDouble(),
        stepGoal = prefs.getInt("stepGoal", 8000),
        intakeKcal = prefs.getInt("intake", -1).takeIf { it > 0 },
        proteinGoalG = prefs.getInt("proteinGoal", 0),
        weeklySteps = prefs.getInt("weeklySteps", 49000),
        sleepGoalMin = prefs.getInt("sleepGoalMin", 450),
        waterGoalGlasses = prefs.getInt("waterGoal", 0),
        maxHrOverride = prefs.getInt("maxHr", 0),
        showMotivation = prefs.getBoolean("motivation", true),
        confetti = prefs.getBoolean("confetti", true),
        heightMetric = prefs.getBoolean("heightCm_unit", false),
        look = prefs.getString("look", "CLASSIC") ?: "CLASSIC",
        checkpointKg = prefs.getFloat("cpKg", -1f).takeIf { it > 0 }?.toDouble(),
        checkpointDate = prefs.getString("cpDate", null),
        checkpointName = prefs.getString("cpName", null),
    )

    /** Saves just the look, so picking a theme doesn't touch unsaved edits elsewhere in Settings. */
    fun saveLook(name: String) = prefs.edit { putString("look", name) }

    fun save(p: Profile) = prefs.edit {
        putFloat("heightCm", p.heightCm.toFloat())
        putInt("age", p.age)
        putString("sex", p.sex.name)
        putFloat("weightKg", p.fallbackWeightKg.toFloat())
        putInt("deficit", p.deficitKcal)
        putBoolean("setupDone", p.setupDone)
        putFloat("goalKg", (p.goalWeightKg ?: -1.0).toFloat())
        putFloat("rate", p.rateKgPerWeek.toFloat())
        putString("goalStart", p.goalStartDate)
        putFloat("goalStartKg", (p.goalStartWeightKg ?: -1.0).toFloat())
        putInt("stepGoal", p.stepGoal)
        putInt("intake", p.intakeKcal ?: -1)
        putInt("proteinGoal", p.proteinGoalG)
        putInt("weeklySteps", p.weeklySteps)
        putInt("sleepGoalMin", p.sleepGoalMin)
        putInt("waterGoal", p.waterGoalGlasses)
        putInt("maxHr", p.maxHrOverride)
        putBoolean("motivation", p.showMotivation)
        putBoolean("confetti", p.confetti)
        putBoolean("heightCm_unit", p.heightMetric)
        putString("look", p.look)
        putFloat("cpKg", (p.checkpointKg ?: -1.0).toFloat())
        putString("cpDate", p.checkpointDate)
        putString("cpName", p.checkpointName)
    }
}

/** The user's "real workout / not a workout" decisions, keyed by Health Connect session id. */
class SessionOverrideStore(context: Context) {
    private val prefs = context.getSharedPreferences("kaya_sessions", Context.MODE_PRIVATE)

    fun get(id: String): Boolean? = when (prefs.getString(id, null)) {
        "count" -> true
        "ignore" -> false
        else -> null
    }

    fun set(id: String, count: Boolean?) = prefs.edit {
        if (count == null) remove(id) else putString(id, if (count) "count" else "ignore")
    }
}

data class FoodFavourite(val name: String, val kcal: Double, val proteinG: Double?)

/**
 * One-tap foods. New installs start from a list that suits the phone's region (Indian portions in India,
 * international ones elsewhere); anyone can switch lists or edit them. Numbers are approximate.
 */
class FavouritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("kaya_food", Context.MODE_PRIVATE)

    fun load(): List<FoodFavourite> {
        val raw = prefs.getString("favourites", null) ?: return regionalDefault()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FoodFavourite(
                    o.getString("name"),
                    o.getDouble("kcal"),
                    if (o.has("protein") && !o.isNull("protein")) o.getDouble("protein") else null,
                )
            }
        }.getOrDefault(regionalDefault())
    }

    fun save(list: List<FoodFavourite>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(JSONObject().put("name", f.name).put("kcal", f.kcal).put("protein", f.proteinG ?: JSONObject.NULL))
        }
        prefs.edit { putString("favourites", arr.toString()) }
    }

    companion object {
        fun regionalDefault(): List<FoodFavourite> =
            if (java.util.Locale.getDefault().country.equals("IN", ignoreCase = true)) DEFAULTS else INTERNATIONAL

        /** Starter lists anyone can switch to from Food → Quick add → Edit. */
        val PRESETS: Map<String, List<FoodFavourite>> by lazy {
            linkedMapOf("Indian" to DEFAULTS, "International" to INTERNATIONAL, "Empty" to emptyList())
        }

        val INTERNATIONAL = listOf(
            FoodFavourite("Egg (1 large)", 75.0, 6.0),
            FoodFavourite("Chicken breast, cooked (100 g)", 165.0, 31.0),
            FoodFavourite("Greek yogurt, plain (170 g)", 100.0, 17.0),
            FoodFavourite("Oats, cooked (1 cup)", 155.0, 6.0),
            FoodFavourite("Bread, wholegrain (1 slice)", 80.0, 4.0),
            FoodFavourite("Rice, cooked (1 cup)", 200.0, 4.0),
            FoodFavourite("Milk, semi-skimmed (250 ml)", 125.0, 8.0),
            FoodFavourite("Banana (1 medium)", 105.0, 1.0),
            FoodFavourite("Apple (1 medium)", 95.0, 0.5),
            FoodFavourite("Coffee with milk (1 cup)", 40.0, 2.0),
        )

        val DEFAULTS = listOf(
            FoodFavourite("Roti / phulka (1)", 80.0, 3.0),
            FoodFavourite("Rice, cooked (1 cup)", 200.0, 4.0),
            FoodFavourite("Dal (1 katori)", 150.0, 8.0),
            FoodFavourite("Curd (1 katori)", 100.0, 5.0),
            FoodFavourite("Boiled egg (1)", 75.0, 6.0),
            FoodFavourite("Chicken curry (1 katori)", 250.0, 20.0),
            FoodFavourite("Paneer (100 g)", 265.0, 18.0),
            FoodFavourite("Chai with sugar (1 cup)", 90.0, 3.0),
        )
    }
}
