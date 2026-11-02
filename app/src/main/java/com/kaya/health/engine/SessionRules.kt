package com.kaya.health.engine

import kotlin.math.roundToInt

/** Whether a recorded "workout" really was exercise, and why. */
data class SessionCheck(val counted: Boolean, val reason: String, val userDecided: Boolean)

/**
 * Bands auto-detect workouts from wrist movement, and bus, auto-rickshaw and scooter rides look a lot like
 * cycling. (Seen on the user's phone: three NoiseFit "cycling" sessions at 1–3 am on a sleeper bus, which
 * NoiseFit's own app doesn't even list as workouts.) Kaya counts a session only when there's evidence of effort.
 */
object SessionRules {
    fun judge(
        kind: ActivityKind,
        /** True when calories came from measured movement (distance or steps), not a generic average. */
        movementMeasured: Boolean,
        hrAvg: Long?,
        /** The person's average heart rate across the day, as a baseline for "normal". */
        typicalHr: Double?,
        /** The user's own decision for this session, if they made one. */
        override: Boolean?,
    ): SessionCheck {
        if (override != null) {
            return SessionCheck(
                override,
                if (override) "You marked this as a real workout." else "You marked this as not a workout.",
                userDecided = true,
            )
        }
        if (kind.onFeet && movementMeasured) {
            return SessionCheck(true, "Walking or running, measured from distance or steps.", false)
        }
        if (hrAvg == null) {
            return SessionCheck(
                false,
                "No heart-rate data for this time, so Kaya can't confirm it was exercise. Bands often log bus, auto or bike rides as workouts.",
                false,
            )
        }
        val baseline = typicalHr ?: 90.0
        if (hrAvg < baseline + 10) {
            return SessionCheck(
                false,
                "Heart rate averaged $hrAvg bpm, about your normal daytime level (${baseline.roundToInt()}). Looks like sitting or riding, not exercise.",
                false,
            )
        }
        return SessionCheck(true, "Heart rate averaged $hrAvg bpm, clearly above your normal (${baseline.roundToInt()}).", false)
    }
}
