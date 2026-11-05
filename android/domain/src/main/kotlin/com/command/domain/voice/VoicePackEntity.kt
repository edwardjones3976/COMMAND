package com.command.domain.voice

/*
 * A voice pack as the voice engine reads it: a plain data class (the database rows are VoicePackRow). It is here and not in :voice because
 * the JVM test module, which an Android library cannot be a dependency of, holds the fixtures. The licence, attribution, quality-tier and
 * recorded-voice fields of the previous version are removed on purpose: they were claims no recording ever backed.
 */

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

data class VoicePackEntity(
    val id: String,
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
    val voiceModelType: String = VoiceModelType.USMC_MCRD_BARK.name,
    val vocalGritPercent: Int = 85, // 0% to 100% laryngeal frog-voice rasp
    val aggressionPercent: Int = 95, // 0% to 100% brickwall compression & whistle cue
    val formantShiftPercent: Int = 0, // -50% (Deep Chest) to +50% (High Presence)
    val stemResourceId: String = "",
)
