package com.command.handshake

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.command.domain.dials.ChainLimits
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainProblem
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.StepSpec
import com.command.domain.handshake.ChainState.Companion.DEFAULT_HOLD_SECONDS
import com.example.R

/** The name of a way of acknowledging an alarm. */
@Composable
internal fun modeName(mode: HandshakeMode): String = stringResource(
    when (mode) {
        HandshakeMode.HOLD -> R.string.handshake_mode_hold
        HandshakeMode.CONFIRM_SEQUENCE -> R.string.handshake_mode_sequence
        HandshakeMode.TYPING -> R.string.handshake_mode_typing
        HandshakeMode.TWO_STEP -> R.string.handshake_mode_two_step
        HandshakeMode.QR -> R.string.handshake_mode_qr
        HandshakeMode.NFC -> R.string.handshake_mode_nfc
        HandshakeMode.VOICE -> R.string.handshake_mode_voice
    },
)

/**
 * One step in a sentence: a press says for how long, and a way that is only planned says what the ring asks for in its place (the ring does
 * not ask for what is named: [HandshakeMode.offered]).
 */
@Composable
internal fun stepWords(step: StepSpec): String = when {
    step.mode == HandshakeMode.HOLD -> {
        val seconds = step.holdSeconds.takeIf { it > 0 } ?: DEFAULT_HOLD_SECONDS
        pluralStringResource(R.plurals.handshake_step_hold, seconds, seconds)
    }
    step.mode.available -> modeName(step.mode)
    else -> stringResource(R.string.handshake_step_runs_as, modeName(step.mode), modeName(step.mode.offered))
}

/** A whole chain in a sentence, in the order of its steps. */
@Composable
internal fun chainWords(spec: ChainSpec): String {
    val steps = spec.steps.map { stepWords(it) }
    if (steps.size == 1) return steps.single()
    val list = steps.joinToString(stringResource(R.string.handshake_list_separator))
    return stringResource(
        when (spec.op) {
            ChainOp.OR -> R.string.handshake_chain_or
            ChainOp.AND -> R.string.handshake_chain_and
            ChainOp.SEQUENCE -> R.string.handshake_chain_sequence
        },
        list,
    )
}

/** What a problem with a chain says, in words; the numbers are the limits themselves ([ChainLimits]), not copies of them. */
@Composable
internal fun problemWords(problem: ChainProblem): String = when (problem) {
    ChainProblem.NO_STEPS -> stringResource(R.string.handshake_problem_no_steps)
    ChainProblem.TOO_MANY_STEPS -> stringResource(R.string.handshake_problem_too_many, ChainLimits.MAX_STEPS)
    ChainProblem.TRIES_OUT_OF_RANGE -> stringResource(R.string.handshake_problem_tries, ChainLimits.MIN_TRIES, ChainLimits.MAX_TRIES)
    ChainProblem.TIME_OUT_OF_RANGE -> stringResource(R.string.handshake_problem_time, ChainLimits.MIN_TIMEOUT_SECONDS, ChainLimits.MAX_TIMEOUT_SECONDS)
    ChainProblem.HOLD_OUT_OF_RANGE -> stringResource(R.string.handshake_problem_hold, ChainLimits.MIN_HOLD_SECONDS, ChainLimits.MAX_HOLD_SECONDS)
    ChainProblem.SAME_WAY_TWICE -> stringResource(R.string.handshake_problem_same)
}
