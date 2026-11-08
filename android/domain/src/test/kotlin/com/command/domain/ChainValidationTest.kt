package com.command.domain

import com.command.domain.dials.ChainIssue
import com.command.domain.dials.ChainLimits
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainProblem
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.ChainValidation
import com.command.domain.dials.DialEffects
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.StepSpec
import com.command.domain.dials.issues
import com.command.domain.handshake.ChainRunner
import com.command.domain.handshake.ChainState
import com.command.domain.handshake.HandshakeCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainValidationTest {

    private fun step(
        mode: HandshakeMode = HandshakeMode.TYPING,
        tries: Int = 3,
        timeout: Int = 90,
        hold: Int = 0,
    ) = StepSpec(mode, tries, timeout, hold)

    private fun issues(vararg steps: StepSpec, op: ChainOp = ChainOp.OR) = ChainValidation.issues(op, steps.toList())

    @Test
    fun `the default chain of every difficulty is a chain that can be saved`() {
        for (difficulty in 1..5) {
            assertEquals("difficulty $difficulty", emptyList<ChainIssue>(), DialEffects.defaultChain(difficulty).issues())
        }
    }

    @Test
    fun `a chain with no steps asks for nothing and is refused`() {
        assertEquals(listOf(ChainIssue(ChainProblem.NO_STEPS)), ChainValidation.issues(ChainOp.AND, emptyList()))
    }

    @Test
    fun `no more steps than a person can be asked for while an alarm rings`() {
        val modes = listOf(HandshakeMode.HOLD, HandshakeMode.CONFIRM_SEQUENCE, HandshakeMode.TYPING, HandshakeMode.TWO_STEP, HandshakeMode.QR)
        assertEquals(emptyList<ChainIssue>(), issues(*modes.take(ChainLimits.MAX_STEPS).map { step(it) }.toTypedArray()))
        // Five different ways are one step too many.
        assertEquals(listOf(ChainIssue(ChainProblem.TOO_MANY_STEPS)), issues(*modes.map { step(it) }.toTypedArray()))
        // And a fifth step that is also a way that is there already (voice, not built, runs as typing): both are told.
        assertEquals(
            listOf(ChainIssue(ChainProblem.TOO_MANY_STEPS), ChainIssue(ChainProblem.SAME_WAY_TWICE, 4)),
            issues(*(modes.take(4) + HandshakeMode.VOICE).map { step(it) }.toTypedArray()),
        )
    }

    @Test
    fun `tries are between two and nine`() {
        assertEquals(emptyList<ChainIssue>(), issues(step(tries = ChainLimits.MIN_TRIES)))
        assertEquals(emptyList<ChainIssue>(), issues(step(tries = ChainLimits.MAX_TRIES)))
        assertEquals(listOf(ChainIssue(ChainProblem.TRIES_OUT_OF_RANGE, 0)), issues(step(tries = 0)))
        assertEquals("one try would let a repeated event pass for the first try of the next way", listOf(ChainIssue(ChainProblem.TRIES_OUT_OF_RANGE, 0)), issues(step(tries = 1)))
        assertEquals(listOf(ChainIssue(ChainProblem.TRIES_OUT_OF_RANGE, 0)), issues(step(tries = -4)))
        assertEquals(listOf(ChainIssue(ChainProblem.TRIES_OUT_OF_RANGE, 0)), issues(step(tries = ChainLimits.MAX_TRIES + 1)))
    }

    @Test
    fun `the time a try may take is between ten seconds and five minutes`() {
        assertEquals(emptyList<ChainIssue>(), issues(step(timeout = ChainLimits.MIN_TIMEOUT_SECONDS)))
        assertEquals(emptyList<ChainIssue>(), issues(step(timeout = ChainLimits.MAX_TIMEOUT_SECONDS)))
        assertEquals(listOf(ChainIssue(ChainProblem.TIME_OUT_OF_RANGE, 0)), issues(step(timeout = ChainLimits.MIN_TIMEOUT_SECONDS - 1)))
        assertEquals(listOf(ChainIssue(ChainProblem.TIME_OUT_OF_RANGE, 0)), issues(step(timeout = ChainLimits.MAX_TIMEOUT_SECONDS + 1)))
    }

    @Test
    fun `a press lasts one to ten seconds, and nought means the short default`() {
        assertEquals(emptyList<ChainIssue>(), issues(step(HandshakeMode.HOLD, hold = 0)))
        assertEquals(emptyList<ChainIssue>(), issues(step(HandshakeMode.HOLD, hold = ChainLimits.MIN_HOLD_SECONDS)))
        assertEquals(emptyList<ChainIssue>(), issues(step(HandshakeMode.HOLD, hold = ChainLimits.MAX_HOLD_SECONDS)))
        assertEquals(listOf(ChainIssue(ChainProblem.HOLD_OUT_OF_RANGE, 0)), issues(step(HandshakeMode.HOLD, hold = -1)))
        assertEquals(listOf(ChainIssue(ChainProblem.HOLD_OUT_OF_RANGE, 0)), issues(step(HandshakeMode.HOLD, hold = ChainLimits.MAX_HOLD_SECONDS + 1)))
        assertEquals("a two-step's own hold is held to the same limit", listOf(ChainIssue(ChainProblem.HOLD_OUT_OF_RANGE, 0)), issues(step(HandshakeMode.TWO_STEP, hold = 99)))
    }

    @Test
    fun `the same way twice is a problem at the second one`() {
        assertEquals(
            listOf(ChainIssue(ChainProblem.SAME_WAY_TWICE, 1)),
            issues(step(HandshakeMode.TYPING), step(HandshakeMode.TYPING), op = ChainOp.OR),
        )
        assertEquals(
            listOf(ChainIssue(ChainProblem.SAME_WAY_TWICE, 2)),
            issues(step(HandshakeMode.HOLD), step(HandshakeMode.TYPING), step(HandshakeMode.HOLD), op = ChainOp.SEQUENCE),
        )
    }

    @Test
    fun `every problem is told, in the order of the steps, not only the first`() {
        val found = issues(step(HandshakeMode.HOLD, tries = 0, timeout = 5, hold = 11), step(HandshakeMode.HOLD, tries = 10), op = ChainOp.AND)
        assertEquals(
            listOf(
                ChainIssue(ChainProblem.TRIES_OUT_OF_RANGE, 0),
                ChainIssue(ChainProblem.TIME_OUT_OF_RANGE, 0),
                ChainIssue(ChainProblem.HOLD_OUT_OF_RANGE, 0),
                ChainIssue(ChainProblem.TRIES_OUT_OF_RANGE, 1),
                ChainIssue(ChainProblem.SAME_WAY_TWICE, 1),
            ),
            found,
        )
    }

    @Test
    fun `the operator has no limits of its own`() {
        for (op in ChainOp.values()) {
            assertTrue("$op", ChainValidation.issues(op, listOf(step(HandshakeMode.HOLD, hold = 3), step(HandshakeMode.TYPING))).isEmpty())
        }
    }

    @Test
    fun `the limits are these numbers, written out, so that a change of one is a change of this test`() {
        assertEquals(1, ChainLimits.MIN_STEPS)
        assertEquals(4, ChainLimits.MAX_STEPS)
        assertEquals(2, ChainLimits.MIN_TRIES)
        assertEquals(9, ChainLimits.MAX_TRIES)
        assertEquals(10, ChainLimits.MIN_TIMEOUT_SECONDS)
        assertEquals(300, ChainLimits.MAX_TIMEOUT_SECONDS)
        assertEquals(1, ChainLimits.MIN_HOLD_SECONDS)
        assertEquals(10, ChainLimits.MAX_HOLD_SECONDS)
    }

    @Test
    fun `the edges are at these plain numbers, on both sides`() {
        for (tries in listOf(2, 5, 9)) assertEquals("$tries tries", emptyList<ChainIssue>(), issues(step(tries = tries)))
        for (tries in listOf(1, 10)) assertEquals("$tries tries", listOf(ChainIssue(ChainProblem.TRIES_OUT_OF_RANGE, 0)), issues(step(tries = tries)))
        for (seconds in listOf(10, 90, 300)) assertEquals("$seconds s", emptyList<ChainIssue>(), issues(step(timeout = seconds)))
        for (seconds in listOf(9, 301, 3000)) assertEquals("$seconds s", listOf(ChainIssue(ChainProblem.TIME_OUT_OF_RANGE, 0)), issues(step(timeout = seconds)))
        for (seconds in listOf(0, 1, 5, 10)) assertEquals("a press of $seconds s", emptyList<ChainIssue>(), issues(step(HandshakeMode.HOLD, hold = seconds)))
        for (seconds in listOf(-1, 11, 12)) assertEquals("a press of $seconds s", listOf(ChainIssue(ChainProblem.HOLD_OUT_OF_RANGE, 0)), issues(step(HandshakeMode.HOLD, hold = seconds)))
    }

    @Test
    fun `the limits are the engine's own, so the ring asks for exactly what a chain that passes says`() {
        assertEquals(ChainState.MAX_HOLD_SECONDS, ChainLimits.MAX_HOLD_SECONDS)
        assertEquals(ChainState.MIN_TIMEOUT_SECONDS, ChainLimits.MIN_TIMEOUT_SECONDS)
        val edge = ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, tries = ChainLimits.MAX_TRIES, timeout = ChainLimits.MIN_TIMEOUT_SECONDS, hold = ChainLimits.MAX_HOLD_SECONDS)))
        assertTrue(edge.issues().isEmpty())

        val state = ChainRunner.start(edge, HandshakeCapability.ALL).state

        assertEquals("a press as long as the limit allows", ChainLimits.MAX_HOLD_SECONDS, state.holdSeconds(0))
        assertEquals("a try as short as the limit allows", ChainLimits.MIN_TIMEOUT_SECONDS, state.timeoutSeconds(0))
        assertEquals(ChainLimits.MAX_TRIES, state.allowance(0))
    }

    @Test
    fun `a way that is not built is the same way as the one that takes its place, because that is what the ring asks for`() {
        assertEquals(listOf(ChainIssue(ChainProblem.SAME_WAY_TWICE, 1)), issues(step(HandshakeMode.VOICE), step(HandshakeMode.TYPING)))
        assertEquals(listOf(ChainIssue(ChainProblem.SAME_WAY_TWICE, 1)), issues(step(HandshakeMode.TYPING), step(HandshakeMode.VOICE)))
        assertEquals(listOf(ChainIssue(ChainProblem.SAME_WAY_TWICE, 1)), issues(step(HandshakeMode.VOICE), step(HandshakeMode.VOICE)))
        assertEquals(listOf(ChainIssue(ChainProblem.SAME_WAY_TWICE, 2)), issues(step(HandshakeMode.VOICE), step(HandshakeMode.HOLD), step(HandshakeMode.TYPING)))
        assertEquals("a way that is not built beside a way that is not typing is two different asks", emptyList<ChainIssue>(), issues(step(HandshakeMode.VOICE), step(HandshakeMode.HOLD)))
    }

    @Test
    fun nfcPlusTypingValid() {
        // M13b: NFC is built, so it and typing are two ways, as QR and typing are. Nothing saved before can become invalid by this:
        // NFC beside typing could not be saved until now.
        assertEquals(emptyList<ChainIssue>(), issues(step(HandshakeMode.NFC), step(HandshakeMode.TYPING)))
        assertEquals(emptyList<ChainIssue>(), issues(step(HandshakeMode.TYPING), step(HandshakeMode.NFC)))
        assertEquals(emptyList<ChainIssue>(), issues(step(HandshakeMode.QR), step(HandshakeMode.NFC), step(HandshakeMode.TYPING)))
        assertEquals(listOf(ChainIssue(ChainProblem.SAME_WAY_TWICE, 1)), issues(step(HandshakeMode.NFC), step(HandshakeMode.NFC)))
    }

    @Test
    fun nfcPlusVoiceIsTwoWays() {
        // The design expected NFC beside voice to stay SAME_WAY_TWICE; the rule compares HandshakeMode.offered, and NFC is now offered as
        // itself while voice is still offered as typing, so the two are two ways (as QR beside voice already was). NFC beside voice beside
        // typing still asks for typing twice.
        assertEquals(emptyList<ChainIssue>(), issues(step(HandshakeMode.NFC), step(HandshakeMode.VOICE)))
        assertEquals(emptyList<ChainIssue>(), issues(step(HandshakeMode.QR), step(HandshakeMode.VOICE)))
        assertEquals(listOf(ChainIssue(ChainProblem.SAME_WAY_TWICE, 2)), issues(step(HandshakeMode.NFC), step(HandshakeMode.VOICE), step(HandshakeMode.TYPING)))
    }

    @Test
    fun `QR is built, so it and typing are two ways, whatever the phone can do, because what is saved never depends on the phone`() {
        assertEquals(emptyList<ChainIssue>(), issues(step(HandshakeMode.QR), step(HandshakeMode.TYPING)))
        assertEquals(emptyList<ChainIssue>(), issues(step(HandshakeMode.TYPING), step(HandshakeMode.QR)))
        assertEquals("QR beside a way that is not built is two ways too", emptyList<ChainIssue>(), issues(step(HandshakeMode.QR), step(HandshakeMode.VOICE)))
        assertEquals(listOf(ChainIssue(ChainProblem.SAME_WAY_TWICE, 1)), issues(step(HandshakeMode.QR), step(HandshakeMode.QR)))
        assertEquals(listOf(ChainIssue(ChainProblem.SAME_WAY_TWICE, 2)), issues(step(HandshakeMode.QR), step(HandshakeMode.TYPING), step(HandshakeMode.VOICE)))
    }

    @Test
    fun `a way that is not built, or not yet checked on a phone, can be named, because the engine runs its fallback and says so`() {
        for (mode in listOf(HandshakeMode.QR, HandshakeMode.NFC, HandshakeMode.VOICE)) {
            assertEquals("$mode", emptyList<ChainIssue>(), ChainSpec(ChainOp.OR, listOf(step(mode))).issues())
        }
    }
}
