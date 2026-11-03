package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "preset_routing_rules")
data class PresetRoutingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleName: String,
    val targetCategory: String, // "Career Work", "Fitness", "Morning Routine", "Recurring Calendar", "ALL"
    val startHour: Int = 0, // 0..23
    val endHour: Int = 23,   // 0..23
    val targetPresetId: String,
    val priorityOrder: Int = 1,
    val isEnabled: Boolean = true,
    val notes: String = ""
)

enum class VolumeRampCurve(val label: String, val description: String) {
    IMMEDIATE_MAX("Immediate Max", "Instant 100% volume with initial attention chime"),
    STEADY_CLIMB("Steady Climb", "Ramps from 40% to 100% across grace period"),
    PULSE_SURGE("Pulse Surge", "Repeats intermittent high-intensity audio pulses every interval")
}

@Entity(tableName = "escalation_policies")
data class EscalationPolicyEntity(
    @PrimaryKey val id: String,
    val policyName: String,
    val graceWindowMinutes: Int = 5,
    val repeatIntervalSeconds: Int = 45,
    val volumeRampCurve: String = VolumeRampCurve.PULSE_SURGE.name,
    val maxStrikesBeforeRecovery: Int = 3,
    val autoTriggerFiveMinStarter: Boolean = true,
    val linkedPresetId: String? = null,
    val isSystemDefault: Boolean = false
)

@Entity(tableName = "handshake_calibrations")
data class HandshakeCalibrationEntity(
    @PrimaryKey val id: String,
    val profileName: String,
    val primaryHandshakeType: String = HandshakeType.HOLD.name,
    val secondaryHandshakeType: String = "NONE", // NONE, SEQUENCE, TYPING
    val holdSecondsRequired: Int = 3,
    val mathComplexityLevel: Int = 2, // 1..5
    val requiredVoicePhrase: String = "I AM READY",
    val strictVerification: Boolean = true,
    val isSystemDefault: Boolean = false
)

@Entity(tableName = "mission_blueprints")
data class MissionBlueprintEntity(
    @PrimaryKey val id: String,
    val title: String,
    val category: String,
    val defaultDurationMinutes: Int = 45,
    val firstActionInstruction: String,
    val spokenPromptTemplate: String,
    val assignedPresetId: String,
    val escalationPolicyId: String? = null,
    val handshakeProfileId: String? = null,
    val checklistStepsCsv: String = ""
)
