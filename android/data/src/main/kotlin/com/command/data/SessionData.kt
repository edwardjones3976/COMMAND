package com.command.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What survives a process death for one occurrence besides its state: counters the state machine needs and the
 * deadlines the alarm service recomputes from after a restart. Stored as JSON in `occurrences.session_json`.
 */
@Serializable
data class SessionData(
    val recoveryOffers: Int = 0,
    val checkInsAsked: Int = 0,
    val phase: String? = null,
    val chainStep: Int = 0,
    val starterDeadlineUtc: Long? = null,
    /** When the ring plan ends, in wall-clock and monotonic time; a restart past this must not ring again. */
    val planEndUtc: Long? = null,
    val planEndElapsed: Long? = null,
    val bootId: String? = null,
    /** The ring plan (see AlertPlan), so a restarted service can work out how much ringing is left. */
    val ringStartUtc: Long? = null,
    val ringCycles: Int = 1,
    val ringCycleMs: Long = 0,
    val ringGapMs: Long = 0,
    /**
     * The handshake the ring was fired with: its difficulty and the personality whose phrases it asks for. A profile edited while it sounds
     * (or a process restarted in the middle) must not change what the person is in the middle of; a ring fired before this was pinned (null)
     * follows the profile.
     */
    val handshakeDifficulty: Int? = null,
    val handshakePersonality: String? = null,
    /** The chain the person saved for the profile when the alarm fired, as [ChainJson] reads it; null when the profile has none of its own. */
    val handshakeChainJson: String? = null,
)

internal object Codec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    fun decodeSession(text: String): SessionData =
        runCatching { json.decodeFromString(SessionData.serializer(), text.ifBlank { "{}" }) }.getOrDefault(SessionData())

    fun encodeSession(session: SessionData): String = json.encodeToString(SessionData.serializer(), session)

    fun encodePayload(values: Map<String, String>): String = json.encodeToString(mapSerializer, values)

    fun decodePayload(text: String): Map<String, String> =
        runCatching { json.decodeFromString(mapSerializer, text) }.getOrDefault(emptyMap())
}
