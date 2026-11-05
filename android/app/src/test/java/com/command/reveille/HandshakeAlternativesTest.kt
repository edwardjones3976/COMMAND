package com.command.reveille

import com.command.data.OccurrenceRepository
import com.command.data.ProfileRepository
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.StepSpec
import com.command.domain.handshake.TryId
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.ringActions
import com.command.testing.FakeClock
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** A chain of a profile's own that allows another way: it is offered, taking it is not a try, and what it replaces is not lost. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class HandshakeAlternativesTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock

    private val phrases = FixedPhrases(listOf(PHRASE))
    private lateinit var vm: ReveilleViewModel

    @Before
    fun setUp() {
        hilt.inject()
        vm = ReveilleViewModel(repo, clock, phrases)
    }

    private fun step(mode: HandshakeMode, tries: Int = 3, hold: Int = 0) = StepSpec(mode, tries, 90, hold)

    /**
     * A ring of an occurrence whose profile asks for [chain]. The mission names no profile, which is every mission the app can make: it has
     * the active one's (Steady, until another is chosen), so every test here goes the way an alarm really goes.
     */
    private fun ringingWith(chain: ChainSpec): OccurrenceRow = runBlocking {
        check(profiles.saveChain("builtin_steady", chain) == com.command.data.SaveChainResult.Saved)
        val mission = testMission()
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        repo.get(o.id)!!
    }

    private fun firing() = vm.state.value as ReveilleUiState.Firing
    private fun rows(o: OccurrenceRow) = runBlocking { db.challenges().forOccurrence(o.id) }
    private fun stateOf(o: OccurrenceRow) = runBlocking { repo.get(o.id)!!.state }

    /** [times] wrong tries on the numbers (the first number to tap is 1), each waited for until the screen asks for the next. */
    private fun failNumbers(o: OccurrenceRow, times: Int) = repeat(times) {
        val before = rows(o).size
        val at = (firing().challenge as Challenge.Sequence).tryId
        vm.sequenceTapped(at, 2)
        awaitUntil(message = "the wrong try is written down") { rows(o).size > before }
        awaitUntil(message = "and the screen asks for the next") {
            val challenge = (vm.state.value as? ReveilleUiState.Firing)?.challenge
            challenge != null && !(challenge is Challenge.Sequence && challenge.tryId == at)
        }
    }

    /** [times] presses let go too early, each waited for until the screen asks for the next try. */
    private fun releaseHolds(o: OccurrenceRow, times: Int) = repeat(times) { n ->
        val before = rows(o).size
        val at = (firing().challenge as Challenge.Hold).tryId
        vm.holdReleasedEarly(at, 1_000L * n, 1_000L * n + 400)
        awaitUntil(message = "the early release is written down") { rows(o).size > before }
        awaitUntil(message = "and the screen asks for the next") {
            val challenge = (vm.state.value as? ReveilleUiState.Firing)?.challenge
            challenge != null && !(challenge is Challenge.Hold && challenge.tryId == at)
        }
    }

    private fun show(o: OccurrenceRow) {
        vm.show(o.id)
        awaitUntil(message = "firing") { vm.state.value is ReveilleUiState.Firing }
    }

    private fun awaitChallenge(message: String, check: (Challenge) -> Boolean) =
        awaitUntil(message = message) { (vm.state.value as? ReveilleUiState.Firing)?.challenge?.let(check) == true }

    /** Waits until every event that was sent before this call has been handled, for a test of something that must not happen. */
    private fun settle() {
        var handled = false
        vm.afterPendingEvents { handled = true }
        awaitUntil(message = "the events before this one have been handled") { handled }
    }

    @Test
    fun `a switch from the screen of another ring is not about the ring on screen, and one from its own screen is`() {
        val chain = ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, hold = 3), step(HandshakeMode.TYPING)))
        val a = ringingWith(chain)
        val b = ringingWith(chain)
        show(a)
        show(b) // another ring takes over

        vm.switchTo(a.id, 1) // a button of the old screen
        settle()
        assertTrue("the ring on screen is still at its first way", firing().challenge is Challenge.Hold)

        vm.switchTo(b.id, 1)
        awaitChallenge("typing") { it is Challenge.Typing }
    }

    @Test
    fun `the switch buttons are bound to the ring their screen was drawn for`() {
        val chain = ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, hold = 3), step(HandshakeMode.TYPING)))
        val a = ringingWith(chain)
        val b = ringingWith(chain)
        show(a)
        val oldScreen = ringActions(vm, a.id) {}
        show(b)
        val newScreen = ringActions(vm, b.id) {}

        oldScreen.onSwitch(1)
        settle()
        assertTrue("the old screen's button changes nothing", firing().challenge is Challenge.Hold)

        newScreen.onSwitch(1)
        awaitChallenge("typing") { it is Challenge.Typing }
    }

    @Test
    fun `a chain of alternatives offers the other ways, and taking one replaces the step on screen`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, hold = 3), step(HandshakeMode.TYPING))))
        show(o)
        assertTrue(firing().challenge is Challenge.Hold)
        assertEquals(listOf(Alternative(1, HandshakeMode.TYPING, instead = true)), firing().alternatives)

        vm.switchTo(1)

        awaitChallenge("typing") { it is Challenge.Typing }
        assertEquals("the way left behind is offered now", listOf(Alternative(0, HandshakeMode.HOLD, instead = true)), firing().alternatives)
        assertTrue("a switch is not a try", rows(o).isEmpty())
        assertEquals(TryId(1, 1), (firing().challenge as Challenge.Typing).tryId)
    }

    @Test
    fun `passing the way that was turned to answers an alternatives chain, and the row is that step's`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, hold = 3), step(HandshakeMode.TYPING))))
        show(o)
        vm.switchTo(1)
        awaitChallenge("typing") { it is Challenge.Typing }

        vm.typingSubmitted((firing().challenge as Challenge.Typing).tryId, PHRASE)

        awaitUntil(message = "direction") { vm.state.value is ReveilleUiState.Direction }
        val row = rows(o).single()
        assertEquals(HandshakeMode.TYPING, row.mode)
        assertEquals(1, row.chainPosition)
        assertEquals(ChallengeOutcome.SUCCESS, row.outcome)
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
    }

    @Test
    fun `a chain where all steps are needed offers the others to do first, and says they are not instead`() {
        val o = ringingWith(ChainSpec(ChainOp.AND, listOf(step(HandshakeMode.CONFIRM_SEQUENCE), step(HandshakeMode.TYPING))))
        show(o)
        assertTrue(firing().challenge is Challenge.Sequence)
        assertEquals(listOf(Alternative(1, HandshakeMode.TYPING, instead = false)), firing().alternatives)

        vm.switchTo(1)
        awaitChallenge("typing first") { it is Challenge.Typing }
        vm.typingSubmitted((firing().challenge as Challenge.Typing).tryId, PHRASE)

        awaitChallenge("then the numbers") { it is Challenge.Sequence }
        assertTrue("nothing is left to turn to", firing().alternatives.isEmpty())
        assertEquals(OccurrenceState.FIRING, stateOf(o))
        assertEquals("one of two is not enough", 2, firing().progress?.step)
    }

    @Test
    fun `an ordered chain offers no other way`() {
        val o = ringingWith(ChainSpec(ChainOp.SEQUENCE, listOf(step(HandshakeMode.TYPING), step(HandshakeMode.CONFIRM_SEQUENCE))))
        show(o)
        assertTrue(firing().alternatives.isEmpty())

        vm.switchTo(1)
        settle()

        assertTrue("the order is kept", firing().challenge is Challenge.Typing)
    }

    @Test
    fun `one step has nothing to turn to`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.TYPING))))
        show(o)
        assertTrue(firing().alternatives.isEmpty())
    }

    @Test
    fun `a position that is not offered changes nothing`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, hold = 3), step(HandshakeMode.TYPING))))
        show(o)

        vm.switchTo(0) // the one on screen
        vm.switchTo(7) // there is none
        vm.switchTo(-1)
        settle()

        assertTrue(firing().challenge is Challenge.Hold)
    }

    @Test
    fun `turning to another way leaves behind the hint and the typed words of the one before`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.TYPING), step(HandshakeMode.CONFIRM_SEQUENCE))))
        show(o)
        vm.typingSubmitted((firing().challenge as Challenge.Typing).tryId, "wrong")
        awaitChallenge("a second try") { it is Challenge.Typing && it.mistaken }

        vm.switchTo(1)
        awaitChallenge("the numbers") { it is Challenge.Sequence }

        assertFalse("the numbers start clean", (firing().challenge as Challenge.Sequence).mistaken)
        assertEquals("and the tries used on typing stay on the record", listOf(ChallengeOutcome.FAILED), rows(o).map { it.outcome })
    }

    @Test
    fun `a switch is not part of the record, so a screen that is shown again with no try made since begins at the first open step`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, hold = 3), step(HandshakeMode.TYPING))))
        show(o)
        vm.switchTo(1)
        awaitChallenge("typing") { it is Challenge.Typing }

        val fresh = ReveilleViewModel(repo, clock, phrases)
        fresh.show(o.id)
        awaitUntil(message = "restored") { (fresh.state.value as? ReveilleUiState.Firing)?.challenge is Challenge.Hold }
    }

    @Test
    fun `a try made after a switch is part of the record, so a screen that is shown again comes back to that step with its tries used`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, hold = 3), step(HandshakeMode.TYPING))))
        show(o)
        vm.switchTo(1)
        awaitChallenge("typing") { it is Challenge.Typing }
        vm.typingSubmitted((firing().challenge as Challenge.Typing).tryId, "wrong")
        awaitChallenge("a second try") { it is Challenge.Typing && it.tryId.tryNo == 2 }

        val fresh = ReveilleViewModel(repo, clock, phrases)
        fresh.show(o.id)
        awaitUntil(message = "restored") { ((fresh.state.value as? ReveilleUiState.Firing)?.challenge as? Challenge.Typing)?.tryId?.tryNo == 2 }

        val typing = (fresh.state.value as ReveilleUiState.Firing).challenge as Challenge.Typing
        assertTrue("and it says so", typing.mistaken)
        assertEquals(2, typing.triesLeft)
    }

    @Test
    fun `a step that would show what is on screen already is not offered, typing that took the place of the numbers beside a step of typing`() {
        val o = ringingWith(ChainSpec(ChainOp.AND, listOf(step(HandshakeMode.CONFIRM_SEQUENCE), step(HandshakeMode.TYPING))))
        show(o)
        assertEquals("while the numbers are on screen the step of typing is another way", listOf(Alternative(1, HandshakeMode.TYPING, instead = false)), firing().alternatives)

        failNumbers(o, 3) // three wrong tries, and typing takes the place of the numbers

        awaitChallenge("typing instead of the numbers") { it is Challenge.Typing }
        assertTrue("typing is on screen, so the other step of typing is not another way", firing().alternatives.isEmpty())
    }

    @Test
    fun `and the numbers that took the place of typing beside a step of the numbers`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.TYPING), step(HandshakeMode.CONFIRM_SEQUENCE))))
        show(o)
        repeat(3) {
            val at = (firing().challenge as Challenge.Typing).tryId
            vm.typingSubmitted(at, "wrong")
            awaitUntil(message = "the screen asks for the next") {
                val challenge = (vm.state.value as? ReveilleUiState.Firing)?.challenge
                challenge != null && !(challenge is Challenge.Typing && challenge.tryId == at)
            }
        }

        awaitChallenge("the numbers instead of typing") { it is Challenge.Sequence }
        assertTrue(firing().alternatives.isEmpty())
    }

    @Test
    fun `where every step is needed a two-step that begins with a hold is not offered beside a press of the same length, because passing the press acknowledges it too`() {
        val o = ringingWith(ChainSpec(ChainOp.AND, listOf(step(HandshakeMode.HOLD, hold = 3), step(HandshakeMode.TWO_STEP))))
        show(o)

        assertTrue(firing().challenge is Challenge.Hold)
        assertTrue("the press has to be passed anyway, and the two-step's hold would only be an extra press first", firing().alternatives.isEmpty())
    }

    @Test
    fun `where one step is enough it is offered, because it is a way of its own that asks what the person will do first`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, hold = 3), step(HandshakeMode.TWO_STEP))))
        show(o)

        assertTrue(firing().challenge is Challenge.Hold)
        assertEquals(listOf(Alternative(1, HandshakeMode.TWO_STEP, instead = true)), firing().alternatives)
    }

    @Test
    fun `the press is offered beside the hold that a two-step begins with in both kinds of chain, and the hold of the two-step is offered back only where one step is enough`() {
        for ((op, instead) in listOf(ChainOp.OR to true, ChainOp.AND to false)) {
            val o = ringingWith(ChainSpec(op, listOf(step(HandshakeMode.TWO_STEP), step(HandshakeMode.HOLD, hold = 3))))
            show(o)

            assertTrue("$op: the first step is the two-step, and it begins with a hold", (firing().challenge as Challenge.Hold).acknowledging)
            assertEquals("$op: the press of the same length", listOf(Alternative(1, HandshakeMode.HOLD, instead = instead)), firing().alternatives)

            vm.switchTo(1)
            awaitChallenge("$op: the press") { it is Challenge.Hold && !it.acknowledging }
            val back = if (op == ChainOp.OR) listOf(Alternative(0, HandshakeMode.TWO_STEP, instead = true)) else emptyList()
            assertEquals("$op: from the press, the hold of the two-step", back, firing().alternatives)
        }
    }

    @Test
    fun `a press of another length is another screen, so a two-step whose own hold is shorter is offered beside it, and the press beside the two-step`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, hold = 6), step(HandshakeMode.TWO_STEP))))
        show(o)
        assertEquals("the press on screen lasts 6 seconds", 6, (firing().challenge as Challenge.Hold).seconds)
        assertEquals(
            "the two-step begins with a hold of 3: not what is on screen",
            listOf(Alternative(1, HandshakeMode.TWO_STEP, instead = true)),
            firing().alternatives,
        )

        vm.switchTo(1)
        awaitChallenge("the hold in front of the two-step") { it is Challenge.Hold && it.acknowledging }
        assertEquals(3, (firing().challenge as Challenge.Hold).seconds)
        assertEquals("and the press of 6 is offered from there", listOf(Alternative(0, HandshakeMode.HOLD, instead = true)), firing().alternatives)
    }

    @Test
    fun `and a press of one second beside a two-step that begins with the default press, in either order`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.TWO_STEP), step(HandshakeMode.HOLD, hold = 1))))
        show(o)
        assertTrue("the two-step begins with a hold of its own", (firing().challenge as Challenge.Hold).acknowledging)
        assertEquals(3, (firing().challenge as Challenge.Hold).seconds)
        assertEquals("the press of one second is another screen: it is offered", listOf(Alternative(1, HandshakeMode.HOLD, instead = true)), firing().alternatives)

        vm.switchTo(1)
        awaitChallenge("the press of one second") { it is Challenge.Hold && !it.acknowledging && it.seconds == 1 }
        assertEquals(listOf(Alternative(0, HandshakeMode.TWO_STEP, instead = true)), firing().alternatives)
    }

    @Test
    fun `a press whose tries are used up is offered as the three taps it has become, not as the press, and taking it shows the taps`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, tries = 2, hold = 3), step(HandshakeMode.TYPING))))
        show(o)
        releaseHolds(o, 2)
        assertTrue("the press has given way to the three taps", (firing().challenge as Challenge.Hold).assistive)

        vm.switchTo(1)
        awaitChallenge("typing") { it is Challenge.Typing }
        assertEquals(
            "the button says what will be asked",
            listOf(Alternative(0, HandshakeMode.HOLD, instead = true, assistive = true)),
            firing().alternatives,
        )

        vm.switchTo(0)
        awaitChallenge("the three taps") { it is Challenge.Hold && it.assistive }
        assertEquals("and the way left behind is typing, as it was", listOf(Alternative(1, HandshakeMode.TYPING, instead = true)), firing().alternatives)
    }

    @Test
    fun `a press that has become the three taps is another screen than the press in front of a two-step, so the two-step is still offered`() {
        val o = ringingWith(ChainSpec(ChainOp.AND, listOf(step(HandshakeMode.HOLD, tries = 2, hold = 3), step(HandshakeMode.TWO_STEP), step(HandshakeMode.TYPING))))
        show(o)
        releaseHolds(o, 2)
        assertTrue((firing().challenge as Challenge.Hold).assistive)

        // The two-step's own hold is a press of the default length, not the three taps: this screen does not show it.
        assertEquals(
            listOf(Alternative(1, HandshakeMode.TWO_STEP, instead = false), Alternative(2, HandshakeMode.TYPING, instead = false)),
            firing().alternatives,
        )
    }

    @Test
    fun `in a chain of three the other steps are offered in order, each by its own name, and only a step that shows what is on screen is left out`() {
        val o = ringingWith(ChainSpec(ChainOp.AND, listOf(step(HandshakeMode.TYPING), step(HandshakeMode.TWO_STEP), step(HandshakeMode.HOLD, hold = 5))))
        show(o)
        assertTrue(firing().challenge is Challenge.Typing)
        assertEquals(
            "from typing: the two-step, which begins with its own hold of 3, and the press of 5, in the order of the chain",
            listOf(Alternative(1, HandshakeMode.TWO_STEP, instead = false), Alternative(2, HandshakeMode.HOLD, instead = false)),
            firing().alternatives,
        )

        vm.switchTo(1)
        awaitChallenge("the hold in front of the two-step") { it is Challenge.Hold && it.acknowledging }
        assertEquals(
            "from the two-step's hold of 3: typing, and the press of 5, which is another screen",
            listOf(Alternative(0, HandshakeMode.TYPING, instead = false), Alternative(2, HandshakeMode.HOLD, instead = false)),
            firing().alternatives,
        )

        vm.switchTo(2)
        awaitChallenge("the press of 5") { it is Challenge.Hold && !it.acknowledging && it.seconds == 5 }
        assertEquals(
            "from the press of 5: typing, and the two-step, whose hold lasts 3",
            listOf(Alternative(0, HandshakeMode.TYPING, instead = false), Alternative(1, HandshakeMode.TWO_STEP, instead = false)),
            firing().alternatives,
        )
    }

    @Test
    fun `a two-step and a press that last the same are two buttons beside typing, and where one step is enough each is offered beside the other`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.TYPING), step(HandshakeMode.TWO_STEP), step(HandshakeMode.HOLD, hold = 3))))
        show(o)
        assertEquals(
            "they show the same hold but they are not the same step: one passes the chain, the other only acknowledges",
            listOf(Alternative(1, HandshakeMode.TWO_STEP, instead = true), Alternative(2, HandshakeMode.HOLD, instead = true)),
            firing().alternatives,
        )

        vm.switchTo(2)
        awaitChallenge("the press") { it is Challenge.Hold && !it.acknowledging }
        assertEquals(
            "the two-step is a way of its own beside the press, as the press is beside the two-step",
            listOf(Alternative(0, HandshakeMode.TYPING, instead = true), Alternative(1, HandshakeMode.TWO_STEP, instead = true)),
            firing().alternatives,
        )

        vm.switchTo(1)
        awaitChallenge("the hold in front of the two-step") { it is Challenge.Hold && it.acknowledging }
        assertEquals(
            "and from the hold of the two-step the press is the way that ends the chain without the question",
            listOf(Alternative(0, HandshakeMode.TYPING, instead = true), Alternative(2, HandshakeMode.HOLD, instead = true)),
            firing().alternatives,
        )
    }

    @Test
    fun `the three taps in place of a two-step's own hold are another screen than a press, so a press beside them is offered`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.TWO_STEP, tries = 2), step(HandshakeMode.HOLD, hold = 3))))
        show(o)
        releaseHolds(o, 2)

        val taps = firing().challenge as Challenge.Hold
        assertTrue("the hold in front of the two-step has given way to the three taps", taps.assistive && taps.acknowledging)
        assertEquals(
            "the press of 3 seconds is not what is on screen: it is the way that ends the chain without the question",
            listOf(Alternative(1, HandshakeMode.HOLD, instead = true)),
            firing().alternatives,
        )
    }

    @Test
    fun `where every step is needed the taps of a two-step's own hold are offered beside a press that has tries left, and hidden beside the taps of the press`() {
        val o = ringingWith(ChainSpec(ChainOp.AND, listOf(step(HandshakeMode.HOLD, tries = 2, hold = 3), step(HandshakeMode.TWO_STEP, tries = 2))))
        show(o)
        vm.switchTo(1)
        awaitChallenge("the hold in front of the two-step") { it is Challenge.Hold && it.acknowledging }
        releaseHolds(o, 2)
        assertTrue("its hold has given way to its three taps", (firing().challenge as Challenge.Hold).let { it.assistive && it.acknowledging })

        vm.switchTo(0)
        awaitChallenge("the press") { it is Challenge.Hold && !it.acknowledging && !it.assistive }
        assertEquals(
            "the taps are not a press: they are offered, under the name of the two-step",
            listOf(Alternative(1, HandshakeMode.TWO_STEP, instead = false, assistive = false)),
            firing().alternatives,
        )

        releaseHolds(o, 2)
        assertTrue("the press has given way to its three taps", (firing().challenge as Challenge.Hold).assistive)
        assertTrue("the taps of the press acknowledge the two-step as well, so its taps are not offered", firing().alternatives.isEmpty())

        vm.switchTo(1)
        awaitChallenge("the taps of the two-step") { it is Challenge.Hold && it.assistive && it.acknowledging }
        assertEquals(
            "while the taps of the press are another way: they end that step",
            listOf(Alternative(0, HandshakeMode.HOLD, instead = false, assistive = true)),
            firing().alternatives,
        )
    }

    @Test
    fun `the taps show no length, so the taps of a two-step are hidden beside the taps of a press of another length`() {
        val o = ringingWith(ChainSpec(ChainOp.AND, listOf(step(HandshakeMode.HOLD, tries = 2, hold = 6), step(HandshakeMode.TWO_STEP, tries = 2))))
        show(o)
        releaseHolds(o, 2)
        assertTrue("the press of 6 seconds has given way to its taps", (firing().challenge as Challenge.Hold).assistive)
        vm.switchTo(1)
        awaitChallenge("the hold in front of the two-step") { it is Challenge.Hold && it.acknowledging }
        releaseHolds(o, 2)
        vm.switchTo(0)
        awaitChallenge("the taps of the press") { it is Challenge.Hold && it.assistive && !it.acknowledging }

        assertTrue("the hold of the two-step lasted 3 seconds, but the taps in its place show no length", firing().alternatives.isEmpty())
    }

    @Test
    fun `where one step is enough the taps of a two-step and the taps of a press are each offered beside the other`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.TWO_STEP, tries = 2), step(HandshakeMode.HOLD, tries = 2, hold = 6))))
        show(o)
        releaseHolds(o, 2)
        vm.switchTo(1)
        awaitChallenge("the press") { it is Challenge.Hold && !it.acknowledging }
        releaseHolds(o, 2)
        assertTrue("the press has given way to its taps", (firing().challenge as Challenge.Hold).assistive)

        assertEquals(
            "the two-step is a way of its own, and its hold is used up: under its name",
            listOf(Alternative(0, HandshakeMode.TWO_STEP, instead = true)),
            firing().alternatives,
        )
        vm.switchTo(0)
        awaitChallenge("the taps of the two-step") { it is Challenge.Hold && it.assistive && it.acknowledging }
        assertEquals(listOf(Alternative(1, HandshakeMode.HOLD, instead = true, assistive = true)), firing().alternatives)
    }

    @Test
    fun `passing a press, or its three taps, acknowledges the two-step, so that only its question is left`() {
        for (viaTaps in listOf(false, true)) {
            val o = ringingWith(ChainSpec(ChainOp.AND, listOf(step(HandshakeMode.HOLD, tries = 2, hold = 3), step(HandshakeMode.TWO_STEP))))
            show(o)
            if (viaTaps) {
                releaseHolds(o, 2)
                vm.assistiveConfirmed((firing().challenge as Challenge.Hold).tryId)
            } else {
                vm.holdCompleted((firing().challenge as Challenge.Hold).tryId, 0, 3_000)
            }

            awaitChallenge("viaTaps=$viaTaps: the question of the two-step, with no hold in front of it") { it is Challenge.TwoStep }
        }
    }

    @Test
    fun `passing the hold of a two-step only acknowledges the two-step, so that the press is still to be done`() {
        val o = ringingWith(ChainSpec(ChainOp.AND, listOf(step(HandshakeMode.TWO_STEP), step(HandshakeMode.HOLD, hold = 3))))
        show(o)
        vm.holdCompleted((firing().challenge as Challenge.Hold).tryId, 0, 3_000)
        awaitChallenge("the question of the two-step") { it is Challenge.TwoStep }

        vm.twoStepIndicated((firing().challenge as Challenge.TwoStep).tryId)

        awaitChallenge("the press, still to be done") { it is Challenge.Hold && !it.acknowledging }
        assertEquals("and the chain is not over", OccurrenceState.FIRING, stateOf(o))
    }

    @Test
    fun `a two-step whose own hold is used up is offered under its own name and not as the three taps, because the question follows them`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.TYPING), step(HandshakeMode.TWO_STEP, tries = 2))))
        show(o)
        vm.switchTo(1)
        awaitChallenge("the hold in front of the two-step") { it is Challenge.Hold && it.acknowledging }
        releaseHolds(o, 2)
        assertTrue("its hold has given way to the three taps", (firing().challenge as Challenge.Hold).assistive)

        vm.switchTo(0)
        awaitChallenge("typing") { it is Challenge.Typing }
        assertEquals(
            "'Confirm with three taps' is for a press that is used up; this leads to the taps and then to the question",
            listOf(Alternative(1, HandshakeMode.TWO_STEP, instead = true, assistive = false)),
            firing().alternatives,
        )
    }

    @Test
    fun `two steps that would show the same under the same name are one button, the first`() {
        val o = ringingWith(ChainSpec(ChainOp.AND, listOf(step(HandshakeMode.CONFIRM_SEQUENCE), step(HandshakeMode.TYPING), step(HandshakeMode.HOLD, hold = 3))))
        show(o)
        failNumbers(o, 3) // typing takes the place of the numbers, beside the step of typing
        awaitChallenge("typing instead of the numbers") { it is Challenge.Typing }
        assertEquals("the other step of typing would show the same, so only the press is offered", listOf(Alternative(2, HandshakeMode.HOLD, instead = false)), firing().alternatives)

        vm.switchTo(2)
        awaitChallenge("the press") { it is Challenge.Hold }
        assertEquals(
            "two steps of typing now: one button, and it is the first",
            listOf(Alternative(0, HandshakeMode.TYPING, instead = false)),
            firing().alternatives,
        )
    }

    @Test
    fun `a screen that is shown again comes back to the step of the last try if that left it open, and a turn made after that try is forgotten`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.CONFIRM_SEQUENCE), step(HandshakeMode.TYPING), step(HandshakeMode.HOLD, hold = 3))))
        show(o)
        vm.switchTo(1)
        awaitChallenge("typing") { it is Challenge.Typing }
        vm.typingSubmitted((firing().challenge as Challenge.Typing).tryId, "wrong")
        awaitChallenge("a second try at typing") { it is Challenge.Typing && it.tryId.tryNo == 2 }
        vm.switchTo(2)
        awaitChallenge("the press") { it is Challenge.Hold }

        val fresh = ReveilleViewModel(repo, clock, phrases)
        fresh.show(o.id)
        awaitUntil(message = "restored") { (fresh.state.value as? ReveilleUiState.Firing)?.challenge is Challenge.Typing }

        val typing = (fresh.state.value as ReveilleUiState.Firing).challenge as Challenge.Typing
        assertEquals("on the step of the last try, not the first open step, and not the press it turned to afterwards", 2, typing.tryId.tryNo)
        assertTrue(typing.mistaken)
    }

    @Test
    fun `a planned way is offered as the way that will be asked, and its replacement is written down once`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD, hold = 3), step(HandshakeMode.QR))))
        show(o)

        assertEquals("not 'Scan a code': the ring asks for typing", listOf(Alternative(1, HandshakeMode.TYPING, instead = true)), firing().alternatives)
        assertEquals(listOf(ChallengeOutcome.FALLBACK), rows(o).map { it.outcome })
        val fresh = ReveilleViewModel(repo, clock, phrases)
        fresh.show(o.id)
        awaitUntil(message = "shown again") { fresh.state.value is ReveilleUiState.Firing }
        assertEquals("and said once", listOf(ChallengeOutcome.FALLBACK), rows(o).map { it.outcome })
    }

    @Test
    fun `turning away from a try that was begun stops its clock, and coming back finds a clean try with a clock of its own`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.TYPING), step(HandshakeMode.CONFIRM_SEQUENCE))))
        show(o)
        val gate = CompletableDeferred<Unit>()
        val asked = ArrayList<Int>()
        vm.attemptTimer = { seconds ->
            asked += seconds
            gate.await()
        }
        vm.typingChanged((firing().challenge as Challenge.Typing).tryId, "S")
        awaitUntil(message = "the clock starts") { asked.size == 1 }

        vm.switchTo(1)
        awaitChallenge("the numbers") { it is Challenge.Sequence }
        vm.switchTo(0)
        awaitChallenge("typing again") { it is Challenge.Typing }
        gate.complete(Unit) // the clock of the first visit runs out now
        settle()

        assertTrue("and counts nothing", rows(o).isEmpty())
        assertEquals(3, (firing().challenge as Challenge.Typing).triesLeft)
        vm.attemptTimer = { seconds ->
            asked += seconds
            awaitCancellation()
        }
        vm.typingChanged((firing().challenge as Challenge.Typing).tryId, "S")
        awaitUntil(message = "a clock of its own") { asked.size == 2 }
    }

    @Test
    fun `numbers tapped before turning away are not there when the person comes back`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.CONFIRM_SEQUENCE), step(HandshakeMode.TYPING))))
        show(o)
        val numbers = firing().challenge as Challenge.Sequence
        vm.sequenceTapped(numbers.tryId, 1)
        vm.sequenceTapped(numbers.tryId, 2)
        awaitChallenge("two done") { it is Challenge.Sequence && it.tapped == 2 }

        vm.switchTo(1)
        awaitChallenge("typing") { it is Challenge.Typing }
        vm.switchTo(0)

        awaitChallenge("the numbers again") { it is Challenge.Sequence }
        assertEquals("and they start clean", 0, (firing().challenge as Challenge.Sequence).tapped)
    }

    @Test
    fun `a chain that cannot be used is not run, the standard one is`() {
        runBlocking { db.profiles().upsert(db.profiles().get("builtin_steady")!!.copy(handshakeChainJson = "{\"op\":\"OR\",\"steps\":[]}")) }
        val mission = testMission()
        runBlocking { db.missions().insert(mission) }
        val o = runBlocking {
            val created = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
            repo.apply(created.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
            repo.apply(created.id, OccurrenceCommand.Fire, EventSource.SERVICE)
            repo.get(created.id)!!
        }

        show(o)

        assertTrue("steady asks for a hold", firing().challenge is Challenge.Hold)
    }

    private companion object {
        const val PHRASE = "Starting now"
    }
}
