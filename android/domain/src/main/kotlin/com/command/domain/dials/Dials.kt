package com.command.domain.dials

/** The seven independent 1-5 controls from the spec (section 3). There is deliberately no composite "seriousness". */
enum class Dial { FIRMNESS, OBNOXIOUSNESS, ALERT_INTENSITY, FOLLOW_UP_PERSISTENCE, HANDSHAKE_DIFFICULTY, SCHEDULE_RIGIDITY, RECOVERY_STRUCTURE }

data class Dials(
    val firmness: Int,
    val obnoxiousness: Int,
    val alertIntensity: Int,
    val followUpPersistence: Int,
    val handshakeDifficulty: Int,
    val scheduleRigidity: Int,
    val recoveryStructure: Int,
) {
    init {
        Dial.values().forEach { require(get(it) in RANGE) { "$it must be in $RANGE but was ${get(it)}" } }
    }

    operator fun get(dial: Dial): Int = when (dial) {
        Dial.FIRMNESS -> firmness
        Dial.OBNOXIOUSNESS -> obnoxiousness
        Dial.ALERT_INTENSITY -> alertIntensity
        Dial.FOLLOW_UP_PERSISTENCE -> followUpPersistence
        Dial.HANDSHAKE_DIFFICULTY -> handshakeDifficulty
        Dial.SCHEDULE_RIGIDITY -> scheduleRigidity
        Dial.RECOVERY_STRUCTURE -> recoveryStructure
    }

    /** A copy with one dial changed; the others are untouched (independence is a spec requirement). */
    fun with(dial: Dial, value: Int): Dials = when (dial) {
        Dial.FIRMNESS -> copy(firmness = value)
        Dial.OBNOXIOUSNESS -> copy(obnoxiousness = value)
        Dial.ALERT_INTENSITY -> copy(alertIntensity = value)
        Dial.FOLLOW_UP_PERSISTENCE -> copy(followUpPersistence = value)
        Dial.HANDSHAKE_DIFFICULTY -> copy(handshakeDifficulty = value)
        Dial.SCHEDULE_RIGIDITY -> copy(scheduleRigidity = value)
        Dial.RECOVERY_STRUCTURE -> copy(recoveryStructure = value)
    }

    companion object {
        val RANGE = 1..5

        /** For values read from storage: out-of-range numbers are clamped instead of crashing. */
        fun coerced(values: List<Int>): Dials {
            require(values.size == Dial.values().size) { "expected ${Dial.values().size} dial values" }
            val v = values.map { it.coerceIn(RANGE.first, RANGE.last) }
            return Dials(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
        }
    }
}

/** Coaching personalities (spec section 3). A personality changes wording and voice, never permissions. */
enum class Personality { DRILL_SERGEANT, EXECUTIVE_DIRECTOR, SUPPORTIVE_COACH, ACCOUNTABILITY_PARTNER, MINIMALIST, CUSTOM }

/** The five spec presets. All are editable; CUSTOM starts from whatever profile is active. */
enum class PresetOrigin { NUDGE, STEADY, COMMAND, REVEILLE_MAX, CUSTOM }

object Presets {
    /** Starting dials, in the order firmness, obnoxiousness, alert, persistence, difficulty, rigidity, recovery. */
    fun dialsFor(origin: PresetOrigin, current: Dials? = null): Dials = when (origin) {
        PresetOrigin.NUDGE -> Dials(1, 1, 1, 1, 1, 1, 1)
        PresetOrigin.STEADY -> Dials(3, 1, 2, 2, 2, 2, 2)
        PresetOrigin.COMMAND -> Dials(4, 2, 3, 3, 3, 3, 3)
        PresetOrigin.REVEILLE_MAX -> Dials(5, 5, 5, 4, 4, 4, 4)
        PresetOrigin.CUSTOM -> current ?: dialsFor(PresetOrigin.STEADY)
    }
}
