package com.command.domain

import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.DialEffects
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.HandshakeMode.CONFIRM_SEQUENCE
import com.command.domain.dials.HandshakeMode.HOLD
import com.command.domain.dials.HandshakeMode.NFC
import com.command.domain.dials.HandshakeMode.QR
import com.command.domain.dials.HandshakeMode.TWO_STEP
import com.command.domain.dials.HandshakeMode.TYPING
import com.command.domain.dials.HandshakeMode.VOICE
import com.command.domain.dials.StepSpec
import com.command.domain.handshake.Attempt
import com.command.domain.handshake.ChainEvent
import com.command.domain.handshake.ChainRunner
import com.command.domain.handshake.ChainState
import com.command.domain.handshake.ChainStatus
import com.command.domain.handshake.StepPart
import com.command.domain.handshake.StepStatus
import com.command.domain.handshake.TryId
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.ChallengeOutcome.ACCESSIBLE_ALT
import com.command.domain.model.ChallengeOutcome.FAILED
import com.command.domain.model.ChallengeOutcome.FALLBACK
import com.command.domain.model.ChallengeOutcome.SUCCESS
import com.command.domain.model.ChallengeOutcome.TIMEOUT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainRunnerTest {

    private fun spec(op: ChainOp, vararg modes: HandshakeMode, retries: Int = 3) =
        ChainSpec(op, modes.map { StepSpec(it, retries, timeoutSec = 90, holdSeconds = if (it == HOLD) 2 else 0) })

    /** A chain being run: the state, and every row the events have produced so far. Each thing a person does is about the try that is on screen. */
    private class Run(val spec: ChainSpec) {
        val start = ChainRunner.start(spec)
        var state: ChainState = start.state
        val rows = ArrayList<Attempt>()

        fun send(event: ChainEvent): Run {
            val step = ChainRunner.advance(state, event)
            state = step.state
            rows += step.attempts
            return this
        }

        /** The try on screen; once the chain is over there is none, and any id will do (nothing is accepted any more). */
        fun here(): TryId = state.tryOnScreen ?: TryId(-1, 0)

        fun pass(times: Int = 1): Run { repeat(times) { send(ChainEvent.Passed(here())) }; return this }
        fun fail(times: Int = 1): Run { repeat(times) { send(ChainEvent.Failed(here())) }; return this }
        fun timeOut(times: Int = 1): Run { repeat(times) { send(ChainEvent.TimedOut(here())) }; return this }
        fun assist(): Run = send(ChainEvent.PassedAssistively(here()))
        fun switchTo(position: Int): Run = send(ChainEvent.Switch(position))
    }

    private fun row(position: Int, mode: HandshakeMode, attemptNo: Int, outcome: ChallengeOutcome) = Attempt(position, mode, attemptNo, outcome)

    // --- which modes there are -----------------------------------------------------------------------------------

    @Test
    fun `hold, the sequence, typing and two-step can be asked for and QR, NFC and voice are planned`() {
        assertEquals(setOf(HOLD, CONFIRM_SEQUENCE, TYPING, TWO_STEP), HandshakeMode.values().filter { it.available }.toSet())
        for (mode in HandshakeMode.values()) assertTrue("$mode must lead to a mode that can be asked for", mode.offered.available)
        for (planned in listOf(QR, NFC, VOICE)) {
            assertFalse(planned.available)
            assertEquals(TYPING, planned.offered)
        }
        for (built in listOf(HOLD, CONFIRM_SEQUENCE, TYPING, TWO_STEP)) assertEquals(built, built.offered)
    }

    @Test
    fun `the dial's own chains only ask for modes that can be asked for, and start on their first step`() {
        for (difficulty in 1..5) {
            val chain = DialEffects.defaultChain(difficulty)
            assertTrue("difficulty $difficulty", chain.steps.all { it.mode.available })
            val start = ChainRunner.start(chain)
            assertTrue("nothing to note when nothing was replaced", start.attempts.isEmpty())
            assertEquals(0, start.state.current)
            assertEquals(TryId(0, 1), start.state.tryOnScreen)
            assertEquals(ChainStatus.RUNNING, start.state.status)
            assertEquals(chain.steps.map { it.mode }, start.state.steps.map { it.mode })
        }
    }

    // --- one step ----------------------------------------------------------------------------------------------

    @Test
    fun `passing the only step satisfies the chain and the row says which try it was`() {
        val run = Run(spec(ChainOp.OR, HOLD)).fail().pass()
        assertEquals(listOf(row(0, HOLD, 1, FAILED), row(0, HOLD, 2, SUCCESS)), run.rows)
        assertEquals(ChainStatus.SATISFIED, run.state.status)
        assertEquals(-1, run.state.current)
        assertNull(run.state.step)
        assertNull(run.state.tryOnScreen)
        assertFalse(run.state.accessible)
        assertFalse(run.state.firstActionIndicated)
    }

    @Test
    fun `a hold that cannot be held offers its assistive confirm once the tries are used, and the confirm is recorded as the alternative`() {
        val run = Run(spec(ChainOp.OR, HOLD)).fail(3)
        assertEquals(
            listOf(row(0, HOLD, 1, FAILED), row(0, HOLD, 2, FAILED), row(0, HOLD, 3, FAILED), row(0, HOLD, 3, FALLBACK)),
            run.rows,
        )
        assertEquals(ChainStatus.RUNNING, run.state.status)
        assertTrue(run.state.step!!.assistive)
        assertEquals("a fresh allowance for the new way", 3, run.state.triesLeft)
        assertEquals("and its tries are numbered from one, on a way of their own", TryId(0, 1, 1), run.state.tryOnScreen)
        run.assist()
        assertEquals(row(0, HOLD, 1, ACCESSIBLE_ALT), run.rows.last())
        assertEquals(ChainStatus.SATISFIED, run.state.status)
        assertTrue(run.state.accessible)
    }

    @Test
    fun `the assistive confirm is bounded like everything else, and a chain that ends there ends`() {
        val run = Run(spec(ChainOp.OR, HOLD, retries = 2)).fail(2)
        assertTrue(run.state.step!!.assistive)

        run.fail(2)

        assertEquals("two tries on the press, two on the confirm", 5, run.rows.size)
        assertEquals(ChainStatus.EXHAUSTED, run.state.status)
        val before = run.state
        run.assist()
        assertSame("and the confirm is not accepted after that", before, run.state)
    }

    @Test
    fun `the assistive confirm is accepted on a hold at any time, and on no other challenge`() {
        val hold = Run(spec(ChainOp.OR, HOLD)).assist()
        assertEquals(listOf(row(0, HOLD, 1, ACCESSIBLE_ALT)), hold.rows)
        assertEquals(ChainStatus.SATISFIED, hold.state.status)

        for (other in listOf(TYPING, CONFIRM_SEQUENCE)) {
            val run = Run(spec(ChainOp.OR, other))
            val before = run.state
            run.assist()
            assertSame("$other must not be passed by the hold's confirm", before, run.state)
            assertTrue(run.rows.isEmpty())
        }

        // A two-step has a hold only in front of it, and only while nothing has acknowledged the alarm.
        val acknowledged = Run(DialEffects.defaultChain(5)).pass()
        assertEquals(StepPart.MAIN, acknowledged.state.step!!.part)
        val before = acknowledged.state
        acknowledged.assist()
        assertSame("the question of what comes first is not answered by a hold", before, acknowledged.state)
    }

    @Test
    fun `typing falls back to the sequence after its tries, and then the chain is out of tries`() {
        val run = Run(spec(ChainOp.OR, TYPING)).fail(3)
        assertEquals(
            listOf(row(0, TYPING, 1, FAILED), row(0, TYPING, 2, FAILED), row(0, TYPING, 3, FAILED), row(0, TYPING, 3, FALLBACK)),
            run.rows,
        )
        assertEquals(CONFIRM_SEQUENCE, run.state.step!!.mode)
        assertEquals(0, run.state.step!!.attempts)
        assertTrue(run.state.step!!.changedMode)
        assertEquals(ChainStatus.RUNNING, run.state.status)

        run.fail(3)
        assertEquals(
            listOf(row(0, CONFIRM_SEQUENCE, 1, FAILED), row(0, CONFIRM_SEQUENCE, 2, FAILED), row(0, CONFIRM_SEQUENCE, 3, FAILED)),
            run.rows.drop(4),
        )
        assertEquals("a mode changes only once", 7, run.rows.size)
        assertEquals(ChainStatus.EXHAUSTED, run.state.status)
        assertEquals(StepStatus.STUCK, run.state.steps.single().status)
        assertNull(run.state.step)
        assertEquals(0, run.state.triesLeft)
    }

    @Test
    fun `a chain that is over takes no more events`() {
        val satisfied = Run(spec(ChainOp.OR, HOLD)).pass()
        val exhausted = Run(spec(ChainOp.OR, TYPING)).fail(6)
        for (run in listOf(satisfied, exhausted)) {
            val before = run.state
            val count = run.rows.size
            run.pass().fail().timeOut().assist().switchTo(0)
            run.send(ChainEvent.Passed(TryId(0, 1)))
            assertSame(before, run.state)
            assertEquals(count, run.rows.size)
        }
    }

    @Test
    fun `a try that was begun and not finished is a try, and is recorded as a timeout`() {
        val run = Run(spec(ChainOp.OR, TYPING, retries = 2)).timeOut()
        assertEquals(listOf(row(0, TYPING, 1, TIMEOUT)), run.rows)
        assertEquals(1, run.state.step!!.attempts)
        assertEquals(1, run.state.triesLeft)
        run.timeOut()
        assertEquals(listOf(row(0, TYPING, 1, TIMEOUT), row(0, TYPING, 2, TIMEOUT), row(0, TYPING, 2, FALLBACK)), run.rows)
        assertEquals(CONFIRM_SEQUENCE, run.state.step!!.mode)
    }

    @Test
    fun `tries count down, and a step with no tries to give still gets one`() {
        val run = Run(spec(ChainOp.OR, TYPING, retries = 3))
        assertEquals(3, run.state.triesLeft)
        run.fail()
        assertEquals(2, run.state.triesLeft)
        run.fail(2)
        assertEquals("the new mode starts with its own allowance", 3, run.state.triesLeft)

        val none = Run(ChainSpec(ChainOp.OR, listOf(StepSpec(TYPING, maxRetries = 0, timeoutSec = 90))))
        assertEquals("zero is read as one", 1, none.state.triesLeft)
        none.fail()
        assertEquals(listOf(row(0, TYPING, 1, FAILED), row(0, TYPING, 1, FALLBACK)), none.rows)
        assertEquals(1, none.state.triesLeft)
    }

    @Test
    fun `how long a press and a try may take is bounded whatever the profile says, and asking about a step there is not is safe`() {
        val chain = ChainSpec(
            ChainOp.OR,
            listOf(StepSpec(HOLD, 3, timeoutSec = 90, holdSeconds = 50), StepSpec(TYPING, 3, timeoutSec = 0), StepSpec(TWO_STEP, 3, timeoutSec = 90, holdSeconds = 0)),
        )
        val state = ChainRunner.start(chain).state
        assertEquals(10, state.holdSeconds(0))
        assertEquals("a hold that took the place of another mode has a short default", 3, state.holdSeconds(2))
        assertEquals(10, state.timeoutSeconds(1))
        assertEquals(90, state.timeoutSeconds(0))
        assertEquals(3, state.holdSeconds(7))
        val negative = ChainRunner.start(ChainSpec(ChainOp.OR, listOf(StepSpec(TYPING, maxRetries = -3, timeoutSec = -5, holdSeconds = -5)))).state
        assertEquals("a negative number of tries is one", 1, negative.allowance(0))
        assertEquals(1, negative.triesLeft(0))
        assertEquals("a negative time is the shortest there is", 10, negative.timeoutSeconds(0))
        assertEquals("and a negative press is the short default, not a press that is over before it began", 3, negative.holdSeconds(0))
        assertEquals("a finished chain reports no step, and asking about it throws nothing", 1, state.allowance(-1))
        assertEquals(1, state.allowance(9))
        assertEquals(0, state.triesLeft(-1))
    }

    // --- modes that are planned -----------------------------------------------------------------------------------

    @Test
    fun `a mode that is not built yet is replaced when the chain starts, and the row says so`() {
        for (planned in listOf(QR, NFC, VOICE)) {
            val run = Run(spec(ChainOp.OR, planned))
            assertEquals(listOf(row(0, planned, 0, FALLBACK)), run.start.attempts)
            assertEquals(TYPING, run.state.step!!.mode)
            assertFalse("the replacement may still fall back once, if it fails", run.state.step!!.changedMode)
            run.fail(3)
            assertEquals(CONFIRM_SEQUENCE, run.state.step!!.mode)
            assertEquals(row(0, TYPING, 3, FALLBACK), run.rows.last())
        }
        val two = ChainRunner.start(spec(ChainOp.OR, QR, HOLD, NFC))
        assertEquals(listOf(row(0, QR, 0, FALLBACK), row(2, NFC, 0, FALLBACK)), two.attempts)
        assertEquals(listOf(TYPING, HOLD, TYPING), two.state.steps.map { it.mode })
    }

    // --- the operators ------------------------------------------------------------------------------------------

    @Test
    fun `AND needs every step, one at a time, in the order listed`() {
        val run = Run(DialEffects.defaultChain(4))
        assertEquals(CONFIRM_SEQUENCE, run.state.step!!.mode)
        assertEquals("another open step can be turned to", listOf(1), run.state.alternatives)
        run.pass()
        assertEquals(ChainStatus.RUNNING, run.state.status)
        assertEquals(1, run.state.current)
        assertEquals(TYPING, run.state.step!!.mode)
        assertTrue(run.state.alternatives.isEmpty())
        run.pass()
        assertEquals(ChainStatus.SATISFIED, run.state.status)
        assertEquals(listOf(row(0, CONFIRM_SEQUENCE, 1, SUCCESS), row(1, TYPING, 1, SUCCESS)), run.rows)
        assertFalse(run.state.firstActionIndicated)
    }

    @Test
    fun `AND lets the person do the open steps in the order they like`() {
        val run = Run(spec(ChainOp.AND, TYPING, CONFIRM_SEQUENCE)).switchTo(1)
        assertEquals(1, run.state.current)
        run.pass()
        assertEquals("what is left comes up next", 0, run.state.current)
        assertTrue("a step that is done cannot be turned to", run.state.alternatives.isEmpty())
        val before = run.state
        run.switchTo(1)
        assertSame(before, run.state)
        run.pass()
        assertEquals(ChainStatus.SATISFIED, run.state.status)
        assertEquals(listOf(row(1, CONFIRM_SEQUENCE, 1, SUCCESS), row(0, TYPING, 1, SUCCESS)), run.rows)
    }

    @Test
    fun `SEQUENCE keeps the order of three steps, not only of two`() {
        val run = Run(spec(ChainOp.SEQUENCE, TYPING, CONFIRM_SEQUENCE, HOLD))
        for (expected in listOf(TYPING, CONFIRM_SEQUENCE, HOLD)) {
            assertEquals(expected, run.state.step!!.mode)
            assertTrue("no turning to another step in an ordered chain", run.state.alternatives.isEmpty())
            val before = run.state
            run.switchTo(2).switchTo(1).switchTo(0)
            assertSame(before, run.state)
            run.pass()
        }
        assertEquals(ChainStatus.SATISFIED, run.state.status)
        assertEquals(listOf(row(0, TYPING, 1, SUCCESS), row(1, CONFIRM_SEQUENCE, 1, SUCCESS), row(2, HOLD, 1, SUCCESS)), run.rows)
    }

    @Test
    fun `a two-step after another step only asks what comes first, and is passed as a two-step`() {
        val run = Run(DialEffects.defaultChain(5))
        assertEquals(listOf(TYPING, TWO_STEP), run.state.steps.map { it.mode })
        assertEquals("still to come, and nothing has acknowledged the alarm yet", StepPart.ACKNOWLEDGE, run.state.steps[1].part)

        run.pass()

        assertEquals(1, run.state.current)
        assertEquals("typing acknowledged the alarm, so the two-step only asks what comes first", StepPart.MAIN, run.state.step!!.part)
        assertEquals(HandshakeMode.TWO_STEP, run.state.shown)
        assertEquals(TryId(1, 1, 1), run.state.tryOnScreen)
        assertFalse(run.state.firstActionIndicated)

        run.pass()

        assertEquals(ChainStatus.SATISFIED, run.state.status)
        assertTrue(run.state.firstActionIndicated)
        assertFalse(run.state.accessible)
        assertEquals(listOf(row(0, TYPING, 1, SUCCESS), row(1, TWO_STEP, 1, SUCCESS)), run.rows)
    }

    @Test
    fun `a two-step with nothing before it begins with a hold of its own, with rows, tries and an assistive confirm`() {
        val run = Run(spec(ChainOp.OR, TWO_STEP, retries = 2))
        assertEquals(StepPart.ACKNOWLEDGE, run.state.step!!.part)
        assertEquals("the hold is what is on screen", HOLD, run.state.shown)
        assertEquals(TryId(0, 1), run.state.tryOnScreen)

        run.pass()

        assertEquals(listOf(row(0, HOLD, 1, SUCCESS)), run.rows)
        assertEquals("acknowledged, and the question is still to be answered", ChainStatus.RUNNING, run.state.status)
        assertEquals(StepPart.MAIN, run.state.step!!.part)
        assertEquals(TWO_STEP, run.state.shown)
        assertEquals("the question has tries of its own, from one, on a way of its own", TryId(0, 1, 1), run.state.tryOnScreen)
        assertFalse(run.state.firstActionIndicated)

        run.pass()

        assertEquals(listOf(row(0, HOLD, 1, SUCCESS), row(0, TWO_STEP, 1, SUCCESS)), run.rows)
        assertEquals(ChainStatus.SATISFIED, run.state.status)
        assertTrue(run.state.firstActionIndicated)
        assertFalse(run.state.accessible)
    }

    @Test
    fun `a person who cannot hold acknowledges a two-step with the assistive confirm, and it is recorded as that`() {
        val run = Run(spec(ChainOp.OR, TWO_STEP)).assist()

        assertEquals(listOf(row(0, HOLD, 1, ACCESSIBLE_ALT)), run.rows)
        assertEquals(StepPart.MAIN, run.state.step!!.part)
        assertFalse("not yet: the question is still to be answered", run.state.accessible)

        run.pass()

        assertEquals(ChainStatus.SATISFIED, run.state.status)
        assertTrue("the acknowledgement that stopped the alarm was the assistive one", run.state.accessible)
        assertTrue(run.state.firstActionIndicated)
        assertEquals(row(0, TWO_STEP, 1, SUCCESS), run.rows.last())
    }

    @Test
    fun `the hold in front of a two-step has the hold's own allowance, assistive after the tries and stuck after that, and no fall back to another mode`() {
        val run = Run(spec(ChainOp.OR, TWO_STEP, retries = 2)).fail(2)

        assertEquals(listOf(row(0, HOLD, 1, FAILED), row(0, HOLD, 2, FAILED), row(0, HOLD, 2, FALLBACK)), run.rows)
        assertTrue(run.state.step!!.assistive)
        assertEquals("still a two-step, still its first part", StepPart.ACKNOWLEDGE, run.state.step!!.part)
        assertEquals(TWO_STEP, run.state.step!!.mode)

        run.timeOut(2)

        assertEquals(ChainStatus.EXHAUSTED, run.state.status)
        assertEquals(listOf(row(0, HOLD, 1, TIMEOUT), row(0, HOLD, 2, TIMEOUT)), run.rows.drop(3))
        assertFalse(run.state.firstActionIndicated)
    }

    @Test
    fun `a step that passed acknowledges the two-step that is still to come, and one that got stuck does not`() {
        // AND(two-step, typing): the person does typing first, and the two-step then needs no hold of its own.
        val and = Run(spec(ChainOp.AND, TWO_STEP, TYPING)).switchTo(1).pass()
        assertEquals(0, and.state.current)
        assertEquals(StepPart.MAIN, and.state.step!!.part)
        and.pass()
        assertEquals(ChainStatus.SATISFIED, and.state.status)
        assertEquals(listOf(row(1, TYPING, 1, SUCCESS), row(0, TWO_STEP, 1, SUCCESS)), and.rows)

        // The same chain the other way round: the two-step first, with its hold, and then typing.
        val first = Run(spec(ChainOp.AND, TWO_STEP, TYPING)).pass().pass()
        assertEquals(listOf(row(0, HOLD, 1, SUCCESS), row(0, TWO_STEP, 1, SUCCESS)), first.rows)
        assertEquals(1, first.state.current)
        first.pass()
        assertEquals(ChainStatus.SATISFIED, first.state.status)

        // OR(typing, two-step) with typing out of tries: nothing passed, so the two-step begins with its hold.
        val stuck = Run(spec(ChainOp.OR, TYPING, TWO_STEP)).fail(6)
        assertEquals(StepStatus.STUCK, stuck.state.steps[0].status)
        assertEquals(1, stuck.state.current)
        assertEquals(StepPart.ACKNOWLEDGE, stuck.state.step!!.part)
        assertFalse(stuck.state.steps[1].acknowledged)
    }

    @Test
    fun `a two-step that runs out of tries on its question becomes a plain hold, and saying what comes first is not claimed`() {
        val run = Run(DialEffects.defaultChain(5)).pass().fail(2)
        assertEquals(row(1, TWO_STEP, 2, FALLBACK), run.rows.last())
        assertEquals(HOLD, run.state.step!!.mode)
        assertEquals(3, run.state.holdSeconds())
        run.pass()
        assertEquals(ChainStatus.SATISFIED, run.state.status)
        assertFalse("a hold acknowledges; it does not say what comes first", run.state.firstActionIndicated)

        // And the hold has its own assistive confirm after that.
        val again = Run(DialEffects.defaultChain(5)).pass().fail(2).fail(2)
        assertEquals(row(1, HOLD, 2, FALLBACK), again.rows.last())
        assertTrue(again.state.step!!.assistive)
        again.assist()
        assertTrue(again.state.accessible)
        assertFalse(again.state.firstActionIndicated)
    }

    @Test
    fun `OR turns to another step when one is out of tries, and ends only when every step is`() {
        val run = Run(spec(ChainOp.OR, TYPING, HOLD))
        assertEquals(listOf(1), run.state.alternatives)
        run.fail(6)
        assertEquals(StepStatus.STUCK, run.state.steps[0].status)
        assertEquals(ChainStatus.RUNNING, run.state.status)
        assertEquals(1, run.state.current)
        assertTrue(run.state.alternatives.isEmpty())
        run.pass()
        assertEquals(ChainStatus.SATISFIED, run.state.status)

        val all = Run(spec(ChainOp.OR, TYPING, CONFIRM_SEQUENCE, retries = 1)).fail(4)
        assertEquals(ChainStatus.EXHAUSTED, all.state.status)
        assertTrue(all.state.steps.all { it.status == StepStatus.STUCK })
        assertEquals(
            listOf(
                row(0, TYPING, 1, FAILED), row(0, TYPING, 1, FALLBACK), row(0, CONFIRM_SEQUENCE, 1, FAILED),
                row(1, CONFIRM_SEQUENCE, 1, FAILED), row(1, CONFIRM_SEQUENCE, 1, FALLBACK), row(1, TYPING, 1, FAILED),
            ),
            all.rows,
        )
    }

    @Test
    fun `OR lets the person pass by any one of its steps`() {
        val run = Run(spec(ChainOp.OR, TYPING, CONFIRM_SEQUENCE, HOLD)).switchTo(2)
        assertEquals(2, run.state.current)
        assertEquals(listOf(0, 1), run.state.alternatives)
        run.pass()
        assertEquals(ChainStatus.SATISFIED, run.state.status)
        assertEquals(listOf(row(2, HOLD, 1, SUCCESS)), run.rows)
        assertTrue("one step needs no choice", Run(spec(ChainOp.OR, TYPING)).state.alternatives.isEmpty())
    }

    @Test
    fun `AND and SEQUENCE are out of tries as soon as one step is, because it can never be passed`() {
        for (op in listOf(ChainOp.AND, ChainOp.SEQUENCE)) {
            val run = Run(spec(op, TYPING, HOLD)).fail(6)
            assertEquals(op.name, ChainStatus.EXHAUSTED, run.state.status)
            assertEquals(StepStatus.OPEN, run.state.steps[1].status)
            assertEquals(-1, run.state.current)
            assertTrue(run.state.alternatives.isEmpty())
        }
    }

    // --- an event is about a try ----------------------------------------------------------------------------------

    @Test
    fun `a second event for a try that has been answered changes nothing, so a duplicate can never pass the step after it`() {
        // Difficulty 5: typing, then the two-step. One correct phrase delivered twice (the keyboard's Done and the button) must pass typing once.
        val run = Run(DialEffects.defaultChain(5))
        val phrase = run.here()
        run.send(ChainEvent.Passed(phrase))
        val after = run.state

        run.send(ChainEvent.Passed(phrase))

        assertSame("the second one is a duplicate", after, run.state)
        assertEquals(1, run.state.current)
        assertFalse("nothing was said about what comes first", run.state.firstActionIndicated)
        assertEquals(listOf(row(0, TYPING, 1, SUCCESS)), run.rows)

        // A wrong phrase delivered twice is one wrong try, not two.
        val wrong = Run(spec(ChainOp.OR, TYPING, retries = 2))
        val at = wrong.here()
        wrong.send(ChainEvent.Failed(at)).send(ChainEvent.Failed(at))
        assertEquals(1, wrong.state.step!!.attempts)
        assertEquals(listOf(row(0, TYPING, 1, FAILED)), wrong.rows)
        assertEquals("and the next try is the second", TryId(0, 2), wrong.state.tryOnScreen)
    }

    @Test
    fun `an event for another step, or for a try the step has left, changes nothing`() {
        val run = Run(spec(ChainOp.AND, TYPING, CONFIRM_SEQUENCE))
        val typing = run.here()
        run.switchTo(1)
        val before = run.state

        run.send(ChainEvent.TimedOut(typing)) // the typing timer outlived the person's turn to the other step
        run.send(ChainEvent.Passed(typing))
        run.send(ChainEvent.Failed(TryId(5, 1)))
        run.send(ChainEvent.Passed(TryId(1, 9)))

        assertSame(before, run.state)
        assertTrue(run.rows.isEmpty())

        // A try the step has left: after the change of mode the allowance is new, and the old tries are history.
        val changed = Run(spec(ChainOp.OR, TYPING, retries = 2)).fail(2)
        val stale = TryId(0, 2)
        val after = changed.state
        changed.send(ChainEvent.Failed(stale))
        assertSame(after, changed.state)
        assertEquals(TryId(0, 1, 1), changed.state.tryOnScreen)
    }

    // --- a try is identified by its way as well ---------------------------------------------------------------------

    @Test
    fun `a repeated event about the hold in front of a two-step is not taken for the answer to its question`() {
        val run = Run(spec(ChainOp.OR, TWO_STEP, retries = 2))
        val hold = run.here()

        run.send(ChainEvent.Passed(hold)).send(ChainEvent.Passed(hold)) // the same press, delivered twice

        assertEquals(listOf(row(0, HOLD, 1, SUCCESS)), run.rows)
        assertEquals("the question is still waiting for its answer", ChainStatus.RUNNING, run.state.status)
        assertFalse("and nothing was said about what comes first", run.state.firstActionIndicated)
        assertEquals(StepPart.MAIN, run.state.step!!.part)
        assertEquals("the question has an identity of its own", TryId(0, 1, 1), run.state.tryOnScreen)
        assertTrue(hold != run.state.tryOnScreen)
    }

    @Test
    fun `a late event about a way the step has left is not taken for a try of the way that took its place`() {
        // The press is used up and the assistive confirm takes its place: a late release of the press's first try is not the confirm's first try.
        val hold = Run(spec(ChainOp.OR, HOLD, retries = 2))
        val press = hold.here()
        hold.fail(2)
        val onTheConfirm = hold.state
        assertTrue(onTheConfirm.step!!.assistive)
        hold.send(ChainEvent.Failed(press))
        assertSame("a try of the press cannot use up a try of the confirm", onTheConfirm, hold.state)

        // The same for a fallback: typing is used up and the numbers take its place.
        val typing = Run(spec(ChainOp.OR, TYPING, retries = 2))
        val phrase = typing.here()
        typing.fail(2)
        val onTheNumbers = typing.state
        assertEquals(CONFIRM_SEQUENCE, onTheNumbers.shown)
        typing.send(ChainEvent.Failed(phrase)).send(ChainEvent.TimedOut(phrase)).send(ChainEvent.Passed(phrase))
        assertSame("a try of typing cannot be a try of the numbers", onTheNumbers, typing.state)
        assertEquals(3, typing.rows.size)
    }

    @Test
    fun `a step remembers how its last try ended until its way changes, and a replay leads to the same memory`() {
        val run = Run(spec(ChainOp.OR, TYPING, retries = 3))
        assertNull(run.state.step!!.lastMiss)

        run.fail()
        assertEquals(FAILED, run.state.step!!.lastMiss)
        run.timeOut()
        assertEquals("a try that took too long is not a wrong one", TIMEOUT, run.state.step!!.lastMiss)
        assertEquals(run.state, ChainRunner.restore(run.spec, run.rows))

        run.fail() // the third try: typing is used up, and the numbers take its place
        assertEquals(CONFIRM_SEQUENCE, run.state.shown)
        assertNull("a new way starts clean", run.state.step!!.lastMiss)
        assertEquals(run.state, ChainRunner.restore(run.spec, run.rows))

        val press = Run(spec(ChainOp.OR, HOLD, retries = 2)).fail(2) // the press is used up and the three taps take its place
        assertTrue(press.state.step!!.assistive)
        assertNull("the confirm starts clean too", press.state.step!!.lastMiss)
        assertEquals(press.state, ChainRunner.restore(press.spec, press.rows))

        val hold = Run(spec(ChainOp.OR, HOLD, retries = 2)).fail().pass()
        assertNull("a step that passed has nothing wrong to remember on screen", hold.state.step)
        assertEquals(FAILED, hold.state.steps[0].lastMiss)
    }

    @Test
    fun `a step that another step acknowledged asks its question under an identity of its own`() {
        val run = Run(spec(ChainOp.SEQUENCE, TYPING, TWO_STEP))
        val waiting = run.state.steps[1]

        run.pass()

        assertEquals(StepPart.MAIN, run.state.step!!.part)
        assertTrue("the two-step's way changed when typing acknowledged the alarm", run.state.step!!.way > waiting.way)
    }

    @Test
    fun `a replay counts only the rows that were made on the way that is on screen`() {
        // Two copies of the hold's row do not answer the question that follows it.
        val chain = spec(ChainOp.OR, TWO_STEP, retries = 2)
        val twice = listOf(row(0, HOLD, 1, SUCCESS), row(0, HOLD, 1, SUCCESS))
        val restored = ChainRunner.restore(chain, twice)
        assertEquals(ChainStatus.RUNNING, restored.status)
        assertEquals(StepPart.MAIN, restored.step!!.part)
        assertFalse(restored.firstActionIndicated)
        assertEquals(ChainRunner.restore(chain, twice.take(1)), restored)

        // A row for typing does not count on a step that asks for a hold, whatever its numbers.
        val hold = spec(ChainOp.OR, HOLD)
        assertEquals(ChainRunner.start(hold).state, ChainRunner.restore(hold, listOf(row(0, TYPING, 1, FAILED), row(0, TYPING, 1, SUCCESS))))

        // After a change of mode, a row of the mode that was left cannot count on the new one.
        val live = Run(spec(ChainOp.OR, TYPING, retries = 2)).fail(2)
        assertEquals(live.state, ChainRunner.restore(live.spec, live.rows + row(0, TYPING, 1, SUCCESS)))

        // And a row that was refused does not leave its mark on the state: it is as if it had not been written.
        val mixed = listOf(row(0, HOLD, 1, FAILED), row(0, TYPING, 2, FAILED), row(0, HOLD, 2, FAILED))
        assertEquals(ChainRunner.restore(hold, listOf(mixed[0], mixed[2])), ChainRunner.restore(hold, mixed))
    }

    // --- the record is the truth -----------------------------------------------------------------------------------

    @Test
    fun `replaying the rows of a ring leads to the state they came from, and an event that is about another try leaves no row`() {
        val specs = (1..5).map { DialEffects.defaultChain(it) } + listOf(
            spec(ChainOp.OR, TYPING, CONFIRM_SEQUENCE, HOLD),
            spec(ChainOp.AND, HOLD, TYPING, TWO_STEP, retries = 2),
            spec(ChainOp.SEQUENCE, CONFIRM_SEQUENCE, TYPING, retries = 1),
            spec(ChainOp.OR, QR, NFC, retries = 2),
            spec(ChainOp.AND, VOICE, TWO_STEP),
            spec(ChainOp.SEQUENCE, TWO_STEP, HOLD, retries = 4),
            spec(ChainOp.OR, TWO_STEP, retries = 2),
            spec(ChainOp.AND, TWO_STEP, TYPING, retries = 2),
            spec(ChainOp.OR, TYPING, TWO_STEP, retries = 1),
        )
        var seed = 12345L
        fun next(bound: Int): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) % bound).toInt()
        }
        var finishedSatisfied = 0
        var finishedExhausted = 0
        var ignored = 0
        repeat(900) { round ->
            val chain = specs[round % specs.size]
            val start = ChainRunner.start(chain)
            var state = start.state
            val history = ArrayList<Attempt>(start.attempts)
            var events = 0
            val seen = HashMap<Int, HashSet<TryId>>()
            val lastSeen = HashMap<Int, TryId>()
            state.tryOnScreen?.let { first ->
                seen.getOrPut(first.position) { HashSet() }.add(first)
                lastSeen[first.position] = first
            }
            // Turning to another step leaves no row until the person does something there, so the step that was on screen is not part of
            // the record until then; everything else is.
            var turnedWithoutAct = false
            while (state.status == ChainStatus.RUNNING && events++ < 80) {
                val here = state.tryOnScreen!!
                // One event in ten is about some other try: a duplicate, a late timer, a screen that was recreated.
                val at = if (next(10) == 0) TryId(next(chain.steps.size), 1 + next(4), next(3)) else here
                val event = when (next(20)) {
                    in 0..8 -> ChainEvent.Failed(at)
                    in 9..11 -> ChainEvent.TimedOut(at)
                    in 12..14 -> ChainEvent.Switch(next(chain.steps.size))
                    in 15..16 -> ChainEvent.PassedAssistively(at)
                    else -> ChainEvent.Passed(at)
                }
                val step = ChainRunner.advance(state, event)
                if (event !is ChainEvent.Switch && at != here && step.state !== state) {
                    throw AssertionError("an event about $at changed the state while $here was on screen: $event")
                }
                if (event !is ChainEvent.Switch && at != here) ignored++
                step.state.tryOnScreen?.let { now ->
                    // The tries of every way are numbered from one, so what makes a try this one and no other is its identity: it is never
                    // used again for the same step, turning away and back being the same try and not a new one.
                    if (lastSeen[now.position] != now) {
                        assertTrue("round $round: $now was on screen before", seen.getOrPut(now.position) { HashSet() }.add(now))
                        lastSeen[now.position] = now
                    }
                }
                if (step.state.current != state.current && step.attempts.isEmpty()) turnedWithoutAct = true
                if (step.attempts.isNotEmpty()) turnedWithoutAct = false
                state = step.state
                for (row in step.attempts) {
                    assertTrue("a row names a step of the chain", row.position in chain.steps.indices)
                    assertTrue("and a try of it, counted from one", row.attemptNo >= 1)
                }
                history += step.attempts
                val restored = ChainRunner.restore(chain, history)
                assertEquals("round $round after $events events", state.steps, restored.steps)
                assertEquals("round $round after $events events", state.status, restored.status)
                if (!turnedWithoutAct) assertEquals("round $round after $events events", state, restored)
            }
            when (state.status) {
                ChainStatus.SATISFIED -> finishedSatisfied++
                ChainStatus.EXHAUSTED -> finishedExhausted++
                ChainStatus.RUNNING -> Unit
            }
        }
        assertTrue("the run should reach both endings ($finishedSatisfied, $finishedExhausted)", finishedSatisfied > 20 && finishedExhausted > 20)
        assertTrue("and meet events about other tries ($ignored)", ignored > 100)
    }

    @Test
    fun `rows that no event produced, that do not fit the chain, or that repeat a try, are skipped wherever they stand`() {
        val chain = DialEffects.defaultChain(4)
        val fresh = ChainRunner.start(chain).state
        val noise = listOf(
            row(0, CONFIRM_SEQUENCE, 1, ChallengeOutcome.SAFETY_SILENCE),
            row(0, CONFIRM_SEQUENCE, 1, ChallengeOutcome.CANCELLED),
            row(0, CONFIRM_SEQUENCE, 0, FALLBACK),
            row(9, TYPING, 1, FAILED),
            row(-1, TYPING, 1, SUCCESS),
        )
        assertEquals(fresh, ChainRunner.restore(chain, noise))
        assertEquals(fresh, ChainRunner.restore(chain, emptyList()))

        // Noise between real rows, and a row that repeats a try that was counted, change nothing.
        val real = listOf(row(0, CONFIRM_SEQUENCE, 1, FAILED), row(0, CONFIRM_SEQUENCE, 2, SUCCESS), row(1, TYPING, 1, FAILED))
        val clean = ChainRunner.restore(chain, real)
        val muddled = listOf(noise[0], real[0], real[0], noise[3], real[1], noise[1], real[1], noise[4], real[2], real[2], noise[2])
        assertEquals(clean, ChainRunner.restore(chain, muddled))
        assertEquals("one wrong try on the sequence, and one on typing", 1, clean.steps[1].attempts)

        // A row for the other open step of an AND chain is read as the person having turned to it; one for a step that cannot be turned to is not.
        val turned = ChainRunner.restore(chain, listOf(row(1, TYPING, 1, FAILED)))
        assertEquals(1, turned.current)
        assertEquals(1, turned.steps[1].attempts)
        val sequence = DialEffects.defaultChain(5)
        assertEquals(ChainRunner.start(sequence).state, ChainRunner.restore(sequence, listOf(row(1, TWO_STEP, 1, SUCCESS))))
    }

    @Test
    fun `a replay comes back to the step of the last try if that left it open, and otherwise to the first open step, because a turn that no try followed is not in the rows`() {
        val chain = spec(ChainOp.OR, CONFIRM_SEQUENCE, TYPING, HOLD)

        // Turned to typing, one wrong phrase, then turned to the press and did nothing more: the turn to the press is not in the rows.
        val live = Run(chain).switchTo(1).fail().switchTo(2)
        assertEquals("the person is on the press", 2, live.state.current)
        assertEquals(listOf(row(1, TYPING, 1, FAILED)), live.rows)
        val restored = ChainRunner.restore(chain, live.rows)
        assertEquals("but they come back on typing, where the last try was", 1, restored.current)
        assertEquals(TryId(1, 2), restored.tryOnScreen)
        assertEquals(live.state.steps, restored.steps)

        // A turn and no try at all: the first open step.
        val turned = Run(chain).switchTo(1).switchTo(2)
        assertEquals(0, ChainRunner.restore(chain, turned.rows).current)

        // The last try left its step passed, in a chain where every step is needed: the first open step, not the step that passed.
        val all = spec(ChainOp.AND, CONFIRM_SEQUENCE, TYPING, HOLD)
        val passed = Run(all).switchTo(1).pass()
        assertEquals("the person is on a step that is still open", 0, passed.state.current)
        assertEquals(0, ChainRunner.restore(all, passed.rows).current)
        val later = Run(all).switchTo(2).pass()
        assertEquals(0, ChainRunner.restore(all, later.rows).current)
    }

    @Test
    fun `a press that passes, by the hold or by the three taps, acknowledges a two-step that is still open, and the hold of a two-step that passes first leaves the press open`() {
        val all = spec(ChainOp.AND, HOLD, TWO_STEP, retries = 2)

        val held = Run(all).pass()
        assertEquals(ChainStatus.RUNNING, held.state.status)
        assertEquals("the two-step is next", TWO_STEP, held.state.step!!.mode)
        assertEquals("and it asks only the question", StepPart.MAIN, held.state.step!!.part)

        val assisted = Run(all).fail(2).assist()
        assertEquals(ChainStatus.RUNNING, assisted.state.status)
        assertEquals(TWO_STEP, assisted.state.step!!.mode)
        assertEquals("the three taps acknowledge it too", StepPart.MAIN, assisted.state.step!!.part)

        val first = Run(spec(ChainOp.AND, TWO_STEP, HOLD, retries = 2)).pass()
        assertEquals("the hold in front of the two-step only acknowledges it", StepPart.MAIN, first.state.steps[0].part)
        assertEquals("the press is still to be done", StepStatus.OPEN, first.state.steps[1].status)
    }

    @Test
    fun `the rows of a two-step, hold first, replay to the same place`() {
        val live = Run(spec(ChainOp.OR, TWO_STEP, retries = 2)).fail().assist()
        assertEquals(listOf(row(0, HOLD, 1, FAILED), row(0, HOLD, 2, ACCESSIBLE_ALT)), live.rows)

        val restored = ChainRunner.restore(live.spec, live.rows)

        assertEquals(live.state, restored)
        assertEquals(StepPart.MAIN, restored.step!!.part)
        assertEquals("the question begins its tries again", TryId(0, 1, 1), restored.tryOnScreen)
    }
}
