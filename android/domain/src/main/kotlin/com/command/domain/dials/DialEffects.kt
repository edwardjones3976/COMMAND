package com.command.domain.dials

enum class ImperativeTier { SUGGEST, INVITE, DIRECT, FIRM, COMMANDING }

/** How much extra noise the wording carries: density 0..4 interjections; repeats capped at 2; never degradation. */
data class Interjections(val density: Int, val maxRepeats: Int)

/**
 * What each dial does, in one place. Every function reads exactly one dial, so the dials stay independent:
 * a gentle voice with a hard challenge, or an obnoxious voice with a single alert, are both expressible.
 */
object DialEffects {
    fun imperativeTier(firmness: Int): ImperativeTier = ImperativeTier.values()[firmness.coerceIn(1, 5) - 1]

    fun interjections(obnoxiousness: Int): Interjections {
        val level = obnoxiousness.coerceIn(1, 5) - 1
        return Interjections(density = level, maxRepeats = minOf(2, level / 2))
    }

    fun alertPlan(alertIntensity: Int): AlertPlan = AlertPlan.forIntensity(alertIntensity)

    fun checkIns(followUpPersistence: Int): CheckInSchedule = CheckInSchedule.forPersistence(followUpPersistence)

    /** Maximum attempts per step: 5, 4, 3, 3, 2 for difficulty 1..5. */
    fun maxRetries(handshakeDifficulty: Int): Int = intArrayOf(5, 4, 3, 3, 2)[handshakeDifficulty.coerceIn(1, 5) - 1]

    /** Default chain: 1 hold 2 s; 2 hold 3 s; 3 typing; 4 AND(confirm sequence, typing); 5 SEQUENCE(typing, two-step). */
    fun defaultChain(handshakeDifficulty: Int): ChainSpec {
        val retries = maxRetries(handshakeDifficulty)
        fun step(mode: HandshakeMode, hold: Int = 0) = StepSpec(mode, retries, timeoutSec = 90, holdSeconds = hold)
        return when (handshakeDifficulty.coerceIn(1, 5)) {
            1 -> ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, 2)))
            2 -> ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, 3)))
            3 -> ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.TYPING)))
            4 -> ChainSpec(ChainOp.AND, listOf(step(HandshakeMode.CONFIRM_SEQUENCE), step(HandshakeMode.TYPING)))
            else -> ChainSpec(ChainOp.SEQUENCE, listOf(step(HandshakeMode.TYPING), step(HandshakeMode.TWO_STEP)))
        }
    }

    fun lockPolicy(flexibility: Flexibility, override: LockPolicy?, scheduleRigidity: Int): LockPolicy =
        MissionLock.effectivePolicy(flexibility, override, scheduleRigidity)

    fun recoveryPolicy(recoveryStructure: Int): RecoveryPolicy = RecoveryPolicy.forStructure(recoveryStructure)
}
