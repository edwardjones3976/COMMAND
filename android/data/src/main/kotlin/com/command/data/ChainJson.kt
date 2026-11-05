package com.command.data

import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.StepSpec
import com.command.domain.dials.issues
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * How a profile's own chain of handshake steps is kept in `execution_profiles.handshake_chain_json`. Only a chain that can be used comes
 * back: text that cannot be read, a way this version does not know (a newer version may have more), or a chain that breaks a limit
 * ([com.command.domain.dials.ChainLimits]) is null, and the profile then uses the default chain of its difficulty. Nothing here throws.
 */
object ChainJson {
    @Serializable
    private class StepDto(val mode: String, val tries: Int, val timeoutSeconds: Int, val holdSeconds: Int = 0)

    @Serializable
    private class ChainDto(val op: String, val steps: List<StepDto>)

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(spec: ChainSpec): String =
        json.encodeToString(ChainDto(spec.op.name, spec.steps.map { StepDto(it.mode.name, it.maxRetries, it.timeoutSec, it.holdSeconds) }))

    /** The chain in [text], or null when there is none or it cannot be used. */
    fun decode(text: String?): ChainSpec? {
        if (text.isNullOrBlank()) return null
        return try {
            val dto = json.decodeFromString<ChainDto>(text)
            val op = ChainOp.values().firstOrNull { it.name == dto.op } ?: return null
            val steps = dto.steps.map { step ->
                val mode = HandshakeMode.values().firstOrNull { it.name == step.mode } ?: return null
                StepSpec(mode, step.tries, step.timeoutSeconds, step.holdSeconds)
            }
            if (steps.isEmpty()) return null
            ChainSpec(op, steps).takeIf { it.issues().isEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
