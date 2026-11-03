package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personality_presets")
data class PersonalityPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val basePersonalityId: String, // drill_sergeant, executive_director, supportive_coach, accountability_partner, minimalist, custom
    val voicePackId: String,
    // 5 Core Behavioral Axes (1..5)
    val instructionFirmness: Int = 5,
    val obnoxiousness: Int = 5,
    val alertIntensity: Int = 5,
    val persistence: Int = 4,
    val handshakeDifficulty: Int = 3,
    // Additional Structure Axes
    val scheduleRigidity: Int = 4,
    val recoveryStructure: Int = 4,
    // Handshake & Escalation Linkage
    val preferredHandshakeType: String = HandshakeType.HOLD.name,
    val chainedHandshakeType: String = "NONE", // NONE, SEQUENCE, TYPING
    val holdDurationSeconds: Int = 3,
    val graceWindowMinutes: Int = 5,
    val escalationIntervalSeconds: Int = 45,
    // AI Custom Persona & Wording Rules
    val customPersonaPrompt: String = "",
    val vocabularyRules: String = "",
    val catchphrasesCsv: String = "",
    // Phase-Specific Script Guidance / Templates
    val reveillePhaseTemplate: String = "",
    val starterPhaseTemplate: String = "",
    val accountabilityPhaseTemplate: String = "",
    val recoveryPhaseTemplate: String = "",
    // Vocal Delivery & DSP Parameters
    val cadenceStyle: String = CadenceStyle.CLIPPED_TACTICAL.name,
    val eqPreset: String = EqPreset.TACTICAL_HUD.name,
    val customPitch: Float = 1.0f,
    val customSpeechRate: Float = 1.0f,
    val vocalGritPercent: Int = 50,
    val aggressionPercent: Int = 50,
    // Operational Flags
    val isActiveGlobal: Boolean = false,
    val isPreloaded: Boolean = false,
    val assignedCategoriesCsv: String = "",
    val sampleDirective: String = "",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
