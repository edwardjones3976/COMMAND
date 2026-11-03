package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class HandshakeType(val displayName: String, val shortDesc: String) {
    HOLD("Press & Hold", "Sustained 3-second touch acknowledgment"),
    SEQUENCE("Sequence Tap", "Ordered 4-node tactical verification"),
    TYPING("Phrase Typing", "Enter displayed commitment directive"),
    QR_CODE("QR Handshake", "Scan registered location token (with tap fallback)"),
    NFC_TAG("NFC Tap", "Tap authorized physical NFC tag (with tap fallback)"),
    VOICE_PROMPT("Voice Response", "Speak agreed verbal readiness token"),
    TWO_STEP("Two-Step Launch", "Acknowledge alert then confirm immediate action")
}

enum class MissionStatus(val label: String) {
    SCHEDULED("Scheduled"),
    STARTED("Started"),
    WORKING("Still working"),
    COMPLETED("Completed"),
    NEED_RESET("Need a reset"),
    DELAYED("Delayed"),
    SKIPPED("Skipped")
}

enum class EmotionalTone(val label: String, val badgeColorHex: Long) {
    STERN("Stern & Commanding", 0xFFFF3366),
    URGENT("MCRD Pick-Up Day Bark", 0xFFFF9F1C),
    CALM("Calm & Decisive", 0xFF00E5A3),
    ENERGETIC("Energetic & Practical", 0xFF38BDF8)
}

enum class EqPreset(val label: String, val description: String) {
    STUDIO_CRISP("Studio Crisp", "Airy high-shelf boost for crystalline vocal edge"),
    TACTICAL_HUD("MCRD Overdrive", "High-velocity vocal bite with laryngeal gravel & limiter"),
    DEEP_COMMAND("Deep Command", "Warm fundamental presence and chest resonance"),
    BROADCAST("Broadcast", "Balanced studio leveling with wide dynamic range")
}

enum class CadenceStyle(val label: String) {
    DELIBERATE_AI("Deliberate AI Cadence"),
    CLIPPED_TACTICAL("MCRD Staccato Bark"),
    PACE_LOCKED("Parade-Deck Cadence")
}

enum class VoiceModelType(
    val label: String,
    val badgeTag: String,
    val description: String,
    val defaultGrit: Int,
    val defaultAggression: Int,
    val defaultPitch: Float,
    val defaultRate: Float
) {
    USMC_MCRD_BARK(
        label = "USMC MCRD Pick-Up Day Bark",
        badgeTag = "MCRD OVERDRIVE • 44.1kHz",
        description = "Gravelly 'frog-voice' laryngeal overdrive, whistle blast cue, squad-bay slapback & rapid 1.28x bark",
        defaultGrit = 85,
        defaultAggression = 95,
        defaultPitch = 0.86f,
        defaultRate = 1.28f
    ),
    USMC_PARADE_CALLER(
        label = "USMC Parade-Deck Caller",
        badgeTag = "CHEST RESONATOR • 44.1kHz",
        description = "Deep chest sub-harmonic projection with rhythmic drill counts and parade-deck echo",
        defaultGrit = 60,
        defaultAggression = 80,
        defaultPitch = 0.82f,
        defaultRate = 1.14f
    ),
    TACTICAL_COMMS(
        label = "Tactical Overwatch Radio",
        badgeTag = "COMMS BANDPASS • 44.1kHz",
        description = "Crisp 300Hz–3.4kHz military headset filter with carrier squelch clicks and high compression",
        defaultGrit = 35,
        defaultAggression = 70,
        defaultPitch = 0.92f,
        defaultRate = 1.12f
    ),
    EXEC_NEURAL_HD(
        label = "Executive Studio Neural",
        badgeTag = "STUDIO NEURAL • 44.1kHz",
        description = "Ultra-clean high-shelf vocal clarity, zero distortion, surgical executive precision",
        defaultGrit = 0,
        defaultAggression = 35,
        defaultPitch = 1.01f,
        defaultRate = 1.04f
    ),
    KINETIC_COACH(
        label = "Kinetic Performance Coach",
        badgeTag = "DYNAMIC EXCITER • 44.1kHz",
        description = "Punchy mid-forward presence exciter with high-momentum athletic pacing",
        defaultGrit = 15,
        defaultAggression = 65,
        defaultPitch = 0.98f,
        defaultRate = 1.16f
    ),
    CALM_STRATEGIST(
        label = "Calm Focus Strategist",
        badgeTag = "VELVET LOW-PASS • 44.1kHz",
        description = "Warm low-pass filter and unhurried pacing for deep focus and guilt-free reset blocks",
        defaultGrit = 0,
        defaultAggression = 15,
        defaultPitch = 0.95f,
        defaultRate = 0.96f
    )
}

enum class RecoveryProtocol(val label: String, val description: String) {
    CLEAN_RESET_5M("5-Minute Clean Reset", "Pause timer, clear desk for 5 minutes, and restart without penalty"),
    AUTO_SHIFT_15M("Auto-Shift +15m", "Automatically push delayed commitment forward by 15 minutes"),
    MICRO_STEP_2M("2-Minute Micro-Starter", "Downshift to a tiny 2-minute action just to get off zero")
}

@Entity(tableName = "missions")
data class MissionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String,
    val firstActionPrompt: String,
    val scheduledEpochMs: Long,
    val durationMinutes: Int,
    val personalityId: String,
    val voicePackId: String,
    val handshakeType: String, // HandshakeType.name
    val isMissionLocked: Boolean = false,
    val status: String = MissionStatus.SCHEDULED.name,
    val calendarEventId: Long? = null,
    val isFromGoogleCalendar: Boolean = false,
    val isRecurring: Boolean = false,
    val recurrenceRule: String? = null,
    val hasConflict: Boolean = false,
    val conflictDescription: String? = null,
    val presetId: String? = null
)

@Entity(tableName = "voice_packs")
data class VoicePackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val actorName: String,
    val accentLabel: String,
    val emotionalTone: String, // EmotionalTone.name
    val languageTag: String, // e.g., "en-US", "en-GB", "en-AU"
    val pitch: Float,
    val speechRate: Float,
    val isCustom: Boolean = false,
    val usesDrillSergeantArt: Boolean = false,
    val sampleQuote: String,
    val reveilleScript: String,
    val handshakeSuccessScript: String,
    val starterScript: String,
    val isFavorite: Boolean = false,
    val eqPreset: String = EqPreset.TACTICAL_HUD.name,
    val volumeBoostPercent: Int = 135, // 100% to 150%
    val cadenceStyle: String = CadenceStyle.CLIPPED_TACTICAL.name,
    val qualityTierLabel: String = "MCRD OVERDRIVE • 44.1kHz",
    val voiceModelType: String = VoiceModelType.USMC_MCRD_BARK.name,
    val vocalGritPercent: Int = 85, // 0% to 100% laryngeal frog-voice rasp
    val aggressionPercent: Int = 95, // 0% to 100% brickwall compression & whistle cue
    val isHumanStem: Boolean = false,
    val licenseType: String = "Creative Commons Zero (CC0) / Public Domain",
    val legalAttribution: String = "Professional Studio Recording • Commercial & Derivative Rights Cleared",
    val formantShiftPercent: Int = 0, // -50% (Deep Chest) to +50% (High Presence)
    val stemResourceId: String = ""
)

@Entity(tableName = "streak_profile")
data class StreakProfileEntity(
    @PrimaryKey val id: Int = 1,
    val currentStreakDays: Int = 2,
    val longestStreakDays: Int = 6,
    val lastCompletedEpochDay: Long = 0L,
    val recoverableStreakDays: Int = 0,
    val isStreakMissed: Boolean = false,
    val commandCredits: Int = 125,
    val unlockedBadgesCsv: String = "",
    val activePresetName: String = "Reveille MAX",
    val selectedPersonalityId: String = "drill_sergeant",
    val selectedVoicePackId: String = "pack_major_stone",
    // 7 Independent 1-5 configuration dimensions
    val instructionFirmness: Int = 5,
    val obnoxiousness: Int = 5,
    val alertIntensity: Int = 5,
    val persistence: Int = 4,
    val handshakeDifficulty: Int = 3,
    val scheduleRigidity: Int = 4,
    val recoveryStructure: Int = 4,
    // Voice Studio Quality-of-Life Controls
    val headphoneOnlyMode: Boolean = false,
    val quietHoursDucking: Boolean = false,
    val customTestPhrase: String = "EYEBALLS! SNAP TO RIGHT NOW! GET OFF THAT STARTING LINE! OPEN YOUR PROJECT AND GIVE ME FIVE MINUTES! MOVE, AYE SIR!",
    val compareVoiceAId: String = "pack_major_stone",
    val compareVoiceBId: String = "pack_sgt_brody",
    // Onboarding & Recovery Configuration
    val hasCompletedOnboarding: Boolean = false,
    val defaultHandshakeType: String = HandshakeType.HOLD.name,
    val recoveryProtocol: String = RecoveryProtocol.CLEAN_RESET_5M.name,
    val activeCustomPresetId: String? = null,
    val activityPresetMapJson: String = "{}"
)

data class PersonalitySpec(
    val id: String,
    val name: String,
    val tagline: String,
    val defaultVoicePackId: String,
    val defaultFirmness: Int,
    val defaultObnoxiousness: Int,
    val sampleCallout: String
)

data class MilestoneBadgeSpec(
    val id: String,
    val requiredDays: Int,
    val title: String,
    val subtitle: String,
    val bonusCredits: Int
)

val DEFAULT_PERSONALITIES = listOf(
    PersonalitySpec(
        id = "drill_sergeant",
        name = "Drill Sergeant",
        tagline = "MCRD Pick-Up Day intensity — gravelly frog-voice bark, loud, urgent, zero excuses",
        defaultVoicePackId = "pack_major_stone",
        defaultFirmness = 5,
        defaultObnoxiousness = 5,
        sampleCallout = "EYEBALLS! SNAP TO! YOU FINISHED THE HANDSHAKE—OUTSTANDING! NOW GET OFF MY STARTING LINE! OPEN THAT PROJECT AND GIVE ME FIVE MINUTES! MOVE, MOVE, MOVE!"
    ),
    PersonalitySpec(
        id = "executive_director",
        name = "Corporate Executive",
        tagline = "Fortune 500 Executive — velvet-textured authority, surgical intellect, and bottom-line strategic clarity",
        defaultVoicePackId = "pack_eleanor_vance",
        defaultFirmness = 4,
        defaultObnoxiousness = 1,
        sampleCallout = "Let's look at the bottom line. Here is how we deliver on this, and we move forward with intention. Clear your workspace and execute step one."
    ),
    PersonalitySpec(
        id = "supportive_coach",
        name = "Personal & Life Coach",
        tagline = "Foundational Black American male fitness coach — effortless rich baritone, commanding presence, and contagious confidence",
        defaultVoicePackId = "pack_coach_ray",
        defaultFirmness = 4,
        defaultObnoxiousness = 1,
        sampleCallout = "Take a breath, reset your shoulders, and lock in. We’re not rushing this rep—we’re owning it. You’ve already done the hard part by showing up; now let's just execute one solid set at a time."
    ),
    PersonalitySpec(
        id = "motivational_guru",
        name = "Motivational Coach",
        tagline = "Radiant Morning Coach — magnetic optimism, vibrant cadence, and empowering high-stakes clarity",
        defaultVoicePackId = "pack_coach_maya",
        defaultFirmness = 4,
        defaultObnoxiousness = 2,
        sampleCallout = "Own your energy! Claim this morning! And step into your power right now! Make ambitious goals feel completely inevitable!"
    ),
    PersonalitySpec(
        id = "accountability_partner",
        name = "Accountability Partner",
        tagline = "Conversational, steady, and persistent",
        defaultVoicePackId = "pack_sgt_brody",
        defaultFirmness = 4,
        defaultObnoxiousness = 3,
        sampleCallout = "Left, right, lock it in! You put this on your calendar for a reason. Let's knock out the first five minutes together."
    ),
    PersonalitySpec(
        id = "minimalist",
        name = "Minimalist",
        tagline = "Few words, zero fluff, clear instructions",
        defaultVoicePackId = "pack_aris_thorne",
        defaultFirmness = 4,
        defaultObnoxiousness = 1,
        sampleCallout = "Time reached. Handshake verified. Begin first task now."
    ),
    PersonalitySpec(
        id = "custom",
        name = "Custom Director",
        tagline = "Individually configured wording, firmness, and voice model",
        defaultVoicePackId = "pack_major_stone",
        defaultFirmness = 4,
        defaultObnoxiousness = 3,
        sampleCallout = "Custom execution profile active. Complete handshake and initiate your five-minute starter block."
    )
)

val MILESTONE_BADGES = listOf(
    MilestoneBadgeSpec(
        id = "badge_3",
        requiredDays = 3,
        title = "Iron Will",
        subtitle = "3 consecutive days of mission initiation",
        bonusCredits = 50
    ),
    MilestoneBadgeSpec(
        id = "badge_7",
        requiredDays = 7,
        title = "Vanguard Initiator",
        subtitle = "7-day unbroken operational execution streak",
        bonusCredits = 100
    ),
    MilestoneBadgeSpec(
        id = "badge_14",
        requiredDays = 14,
        title = "Relentless Operator",
        subtitle = "14 days of zero-excuse schedule follow-through",
        bonusCredits = 200
    ),
    MilestoneBadgeSpec(
        id = "badge_30",
        requiredDays = 30,
        title = "Unstoppable Commander",
        subtitle = "30 days of master-level commitment execution",
        bonusCredits = 500
    )
)

data class StreakUpdateOutcome(
    val newStreakDays: Int,
    val creditsAdded: Int,
    val newlyUnlockedBadge: MilestoneBadgeSpec?
)

enum class StreakChartViewMode(val label: String) {
    WEEKLY_BARS("7D Bars"),
    HOURLY_CADENCE("24H Cadence")
}

data class DailyHandshakeRecord(
    val dayIndex: Int, // 0..6 (S, M, T, W, T, F, S)
    val dayShortLabel: String,
    val dateLabel: String,
    val verifiedHandshakes: Int,
    val scheduledTarget: Int,
    val avgResponseSeconds: Float,
    val lockedCompletionPercent: Int,
    val dominantHandshakeType: HandshakeType = HandshakeType.HOLD,
    val isMissedDay: Boolean = false,
    val isToday: Boolean = false,
    val completedMissionTitles: List<String> = emptyList()
)

data class HourlyHandshakeBucket(
    val hour: Int, // 0..23
    val label: String, // e.g. "6 AM", "12 PM"
    val count: Int
)

val DEFAULT_WEEKLY_RECORDS = listOf(
    DailyHandshakeRecord(0, "S", "Sun", 4, 4, 3.2f, 95),
    DailyHandshakeRecord(1, "M", "Mon", 5, 5, 3.4f, 96),
    DailyHandshakeRecord(2, "T", "Tue", 4, 4, 3.1f, 92),
    DailyHandshakeRecord(3, "W", "Wed", 5, 5, 3.5f, 94),
    DailyHandshakeRecord(4, "T", "Thu", 4, 4, 3.3f, 90),
    DailyHandshakeRecord(5, "F", "Fri", 5, 5, 3.6f, 95),
    DailyHandshakeRecord(6, "S", "Sat", 4, 4, 3.0f, 98)
)

val DEFAULT_HOURLY_BUCKETS = (0..23).map { h ->
    val label = when (h) {
        0 -> "12 AM"
        6 -> "6 AM"
        12 -> "12 PM"
        18 -> "6 PM"
        else -> ""
    }
    val count = when (h) {
        6, 13 -> 2
        7, 11, 15 -> 3
        9, 17 -> 4
        19 -> 2
        21 -> 1
        else -> 0
    }
    HourlyHandshakeBucket(hour = h, label = label, count = count)
}

data class StreakChartTelemetry(
    val weeklyRecords: List<DailyHandshakeRecord> = DEFAULT_WEEKLY_RECORDS,
    val hourlyBuckets: List<HourlyHandshakeBucket> = DEFAULT_HOURLY_BUCKETS,
    val averageHandshakesPerDay: Float = 4.1f,
    val weekOverWeekPercent: Int = 106,
    val avgResponseSecondsOverall: Float = 3.4f,
    val fastestResponseSeconds: Float = 1.2f,
    val lockedAdherencePercent: Int = 94,
    val totalHandshakesThisWeek: Int = 29,
    val selectedDayIndex: Int? = null,
    val viewMode: StreakChartViewMode = StreakChartViewMode.WEEKLY_BARS
)


