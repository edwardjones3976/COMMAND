package com.command.domain.dials

import com.command.domain.handshake.ChainState

/** What is wrong with a chain a person has put together. A chain with a problem is not saved, and one read from storage that has one is not used. */
enum class ChainProblem {
    /** Nothing to do: an alarm would be acknowledged by nothing at all. */
    NO_STEPS,

    /** More steps than a person can be asked for while an alarm rings. */
    TOO_MANY_STEPS,

    /** A step must allow between [ChainLimits.MIN_TRIES] and [ChainLimits.MAX_TRIES] tries. */
    TRIES_OUT_OF_RANGE,

    /** The time a begun try may take is between [ChainLimits.MIN_TIMEOUT_SECONDS] and [ChainLimits.MAX_TIMEOUT_SECONDS]. */
    TIME_OUT_OF_RANGE,

    /** A press lasts between [ChainLimits.MIN_HOLD_SECONDS] and [ChainLimits.MAX_HOLD_SECONDS] (0 is the short default). */
    HOLD_OUT_OF_RANGE,

    /**
     * The same way twice in one chain asks for the same thing twice, and gives nothing to turn to. It is the way that will be asked that
     * counts: a planned way (QR, NFC, voice) runs as typing, so it and typing are the same way.
     */
    SAME_WAY_TWICE,
}

/** One problem, and the step it is about (null for the chain as a whole). */
data class ChainIssue(val problem: ChainProblem, val position: Int? = null)

/**
 * The limits of a chain, in one place so that the editor and the storage agree. The engine clamps on its own and accepts more (one try, no
 * cap on the tries, the steps or the time of a try); the two clamps it shares with the limits, a press at most [ChainState.MAX_HOLD_SECONDS]
 * and a try at least [ChainState.MIN_TIMEOUT_SECONDS], are the same constants, not copies.
 */
object ChainLimits {
    const val MIN_STEPS = 1
    const val MAX_STEPS = 4
    /**
     * At least two: one slip (a hold let go a moment early, a mistyped letter) must not take a person off the way they chose and straight to
     * the next; every default chain allows two or more. (The engine itself accepts one: it identifies a try by its way as well.)
     */
    const val MIN_TRIES = 2
    const val MAX_TRIES = 9
    /** The engine never gives a try less than this, whatever a chain says: the limit and the engine's clamp are one number. */
    const val MIN_TIMEOUT_SECONDS = ChainState.MIN_TIMEOUT_SECONDS
    const val MAX_TIMEOUT_SECONDS = 300
    const val MIN_HOLD_SECONDS = 1

    /** The engine never lets a press last longer than this, whatever a chain says: the limit and the engine's clamp are one number. */
    const val MAX_HOLD_SECONDS = ChainState.MAX_HOLD_SECONDS
}

object ChainValidation {
    /** Everything wrong with [steps] put together by [op], steps in order; empty when the chain can be saved. */
    fun issues(op: ChainOp, steps: List<StepSpec>): List<ChainIssue> {
        val found = ArrayList<ChainIssue>()
        if (steps.size < ChainLimits.MIN_STEPS) found += ChainIssue(ChainProblem.NO_STEPS)
        if (steps.size > ChainLimits.MAX_STEPS) found += ChainIssue(ChainProblem.TOO_MANY_STEPS)
        val seen = HashSet<HandshakeMode>()
        steps.forEachIndexed { position, step ->
            if (step.maxRetries !in ChainLimits.MIN_TRIES..ChainLimits.MAX_TRIES) found += ChainIssue(ChainProblem.TRIES_OUT_OF_RANGE, position)
            if (step.timeoutSec !in ChainLimits.MIN_TIMEOUT_SECONDS..ChainLimits.MAX_TIMEOUT_SECONDS) found += ChainIssue(ChainProblem.TIME_OUT_OF_RANGE, position)
            if (step.holdSeconds != 0 && step.holdSeconds !in ChainLimits.MIN_HOLD_SECONDS..ChainLimits.MAX_HOLD_SECONDS) found += ChainIssue(ChainProblem.HOLD_OUT_OF_RANGE, position)
            if (!seen.add(step.mode.offered)) found += ChainIssue(ChainProblem.SAME_WAY_TWICE, position)
        }
        return found
    }
}

/** What is wrong with this chain, if anything. (The operator has no limits of its own: all three combine any number of steps.) */
fun ChainSpec.issues(): List<ChainIssue> = ChainValidation.issues(op, steps)
