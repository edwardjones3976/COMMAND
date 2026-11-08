package com.command.reveille

import android.os.Looper
import com.command.data.ChallengeRecord
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.data.db.ProfileRow
import com.command.data.OccurrenceRepository
import com.command.data.TokenRepository
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.handshake.TryId
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import javax.inject.Inject

/**
 * The challenges a ring asks for, driven through the view model against a real in-memory database: what is on the screen for each
 * difficulty, what each answer does, what is written down, and what a screen that is shown again finds.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class HandshakeViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock

    private val phrases = FixedPhrases(listOf(PHRASE))
    private lateinit var vm: ReveilleViewModel

    @Before
    fun setUp() {
        hilt.inject()
        vm = ReveilleViewModel(repo, clock, phrases, FixedCapability(), TokenRepository(db, clock))
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    /** A mission on the built-in [profile], and an occurrence of it that is ringing. */
    private fun ringing(profile: String): OccurrenceRow = runBlocking {
        val mission = testMission(profileId = profile)
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        repo.get(o.id)!!
    }

    /** A profile of the given handshake [difficulty] (the built-in ones are 1, 2, 3 and 4), and its id. */
    private fun profileOfDifficulty(difficulty: Int): String = runBlocking {
        val id = "custom_$difficulty"
        db.profiles().upsert(
            ProfileRow(
                id, "Custom $difficulty", PresetOrigin.CUSTOM, Personality.MINIMALIST, null,
                firmness = 3, obnoxiousness = 1, alertIntensity = 2, followUpPersistence = 2, handshakeDifficulty = difficulty,
                scheduleRigidity = 2, recoveryStructure = 2, handshakeChainJson = null, isBuiltin = false,
            ),
        )
        id
    }

    private fun stateOf(o: OccurrenceRow) = runBlocking { repo.get(o.id)!!.state }
    private fun rows(o: OccurrenceRow) = runBlocking { db.challenges().forOccurrence(o.id) }
    private fun events(o: OccurrenceRow, type: EventType) = runBlocking { db.events().forOccurrence(o.id) }.filter { it.type == type }

    private fun firing() = vm.state.value as ReveilleUiState.Firing
    private fun typing() = firing().challenge as Challenge.Typing
    private fun sequence() = firing().challenge as Challenge.Sequence
    private fun hold() = firing().challenge as Challenge.Hold

    private fun awaitUi(message: String, check: (ReveilleUiState) -> Boolean) = awaitUntil(message = message) { check(vm.state.value) }

    /** Waits until the ring on screen asks for something that [check] accepts. */
    private fun awaitChallenge(message: String, check: (Challenge) -> Boolean) =
        awaitUntil(message = message) { (vm.state.value as? ReveilleUiState.Firing)?.challenge?.let(check) == true }

    private fun show(o: OccurrenceRow) {
        vm.show(o.id)
        awaitUi("firing") { it is ReveilleUiState.Firing }
    }

    /**
     * Waits until every event that was sent to [model] before this call has been handled (they are handled in the order they came), for a
     * test of something that must not happen: whatever it was going to write, it has written.
     */
    private fun settle(model: ReveilleViewModel = vm) {
        var handled = false
        model.afterPendingEvents { handled = true }
        awaitUntil(message = "the events before this one have been handled") { handled }
    }

    private lateinit var ringingOccurrence: OccurrenceRow

    /** [times] wrong phrases, each one waited for until the screen asks for the next try (or the next way). */
    private fun failTyping(times: Int) = repeat(times) {
        val before = rows(ringingOccurrence).size
        val at = typing().tryId
        vm.typingSubmitted(at, "not it")
        awaitUntil(message = "the wrong try is written down") { rows(ringingOccurrence).size > before }
        awaitUntil(message = "and the screen asks for the next") {
            val challenge = (vm.state.value as? ReveilleUiState.Firing)?.challenge
            challenge != null && !(challenge is Challenge.Typing && challenge.tryId == at)
        }
    }

    // --- typing -----------------------------------------------------------------------------------------------------

    @Test
    fun `typing asks for a phrase of the profile's personality, with every try still ahead`() {
        val o = ringing("builtin_command")
        show(o)

        val t = typing()
        assertEquals(PHRASE, t.phrase)
        assertEquals(TryId(0, 1), t.tryId)
        assertEquals(3, t.triesLeft)
        assertFalse(t.mistaken)
        assertNull("one step: no 'step 1 of 2'", firing().progress)
        assertEquals("the words are the personality's", listOf(Personality.ACCOUNTABILITY_PARTNER), phrases.asked)
    }

    @Test
    fun `the right words acknowledge whatever the capitals and the spacing, and lead to the direction, never to started`() {
        val o = ringing("builtin_command")
        show(o)

        vm.typingSubmitted(typing().tryId, "  STARTING    now ")

        awaitUi("direction") { it is ReveilleUiState.Direction }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
        val row = rows(o).single()
        assertEquals(HandshakeMode.TYPING, row.mode)
        assertEquals(ChallengeOutcome.SUCCESS, row.outcome)
        assertEquals(1, row.attemptNo)
        assertTrue("saying what comes first is a two-step's, not typing's", events(o, EventType.FIRST_ACTION_INDICATED).isEmpty())
    }

    @Test
    fun `a wrong phrase is written down and said so, and the alarm keeps ringing with one try fewer`() {
        val o = ringing("builtin_command")
        show(o)

        vm.typingSubmitted(typing().tryId, "something else")

        awaitChallenge("second try") { it is Challenge.Typing && it.tryId.tryNo == 2 }
        val t = typing()
        assertTrue(t.mistaken)
        assertEquals(2, t.triesLeft)
        assertEquals(OccurrenceState.FIRING, stateOf(o))
        val row = rows(o).single()
        assertEquals(ChallengeOutcome.FAILED, row.outcome)
        assertEquals(1, row.attemptNo)
    }

    @Test
    fun `an empty answer is not an answer, so Done on an empty field uses no try and writes nothing`() {
        val o = ringing("builtin_command")
        show(o)

        vm.typingSubmitted(typing().tryId, "   ")
        vm.typingSubmitted(typing().tryId, "")
        settle()

        assertTrue(rows(o).isEmpty())
        assertEquals(TryId(0, 1), typing().tryId)
        assertEquals(3, typing().triesLeft)
        assertFalse(typing().mistaken)
        assertEquals(OccurrenceState.FIRING, stateOf(o))
    }

    @Test
    fun `a full stop at the end of what was typed is forgiven, as a keyboard puts one there for a doubled space`() {
        val o = ringing("builtin_command")
        show(o)

        vm.typingSubmitted(typing().tryId, "$PHRASE. ")

        awaitUi("direction") { it is ReveilleUiState.Direction }
        assertEquals(listOf(ChallengeOutcome.SUCCESS), rows(o).map { it.outcome })
    }

    @Test
    fun `the button and the keyboard's Done both send the phrase, and it counts once`() {
        val o = ringing("builtin_command")
        show(o)
        val at = typing().tryId

        vm.typingSubmitted(at, PHRASE)
        vm.typingSubmitted(at, PHRASE)

        awaitUi("direction") { it is ReveilleUiState.Direction }
        settle()
        assertEquals(listOf(ChallengeOutcome.SUCCESS), rows(o).map { it.outcome })
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
    }

    @Test
    fun `an answer to an earlier try changes nothing, only the try on screen can be answered`() {
        val o = ringing("builtin_command")
        show(o)
        val first = typing().tryId
        vm.typingSubmitted(first, "nope")
        awaitChallenge("second try") { it is Challenge.Typing && it.tryId.tryNo == 2 }

        vm.typingSubmitted(first, PHRASE) // the right words, for a try that is over

        settle()
        assertEquals(OccurrenceState.FIRING, stateOf(o))
        assertEquals(listOf(ChallengeOutcome.FAILED), rows(o).map { it.outcome })
        vm.typingSubmitted(typing().tryId, PHRASE)
        awaitUi("direction") { it is ReveilleUiState.Direction }
    }

    @Test
    fun `three wrong tries change the way, typing gives way to the numbers once, and the change is written down`() {
        val o = ringing("builtin_command")
        ringingOccurrence = o
        show(o)

        failTyping(3)

        awaitChallenge("the numbers instead") { it is Challenge.Sequence }
        assertEquals("the first try of the new way, which has an identity of its own", TryId(0, 1, 1), sequence().tryId)
        assertEquals("a fresh allowance", 3, sequence().triesLeft)
        assertFalse("the new way starts clean", sequence().mistaken)
        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.FAILED, ChallengeOutcome.FAILED, ChallengeOutcome.FALLBACK), rows(o).map { it.outcome })
        assertEquals(OccurrenceState.FIRING, stateOf(o))
    }

    @Test
    fun `a try that is begun and not finished runs out and counts as a try`() {
        val o = ringing("builtin_command")
        show(o)
        val gate = CompletableDeferred<Unit>()
        val asked = ArrayList<Int>()
        vm.attemptTimer = { seconds ->
            asked += seconds
            gate.await()
        }

        vm.typingChanged(typing().tryId, "S")
        awaitUntil(message = "the clock starts") { asked.isNotEmpty() }
        assertEquals("the step's own time", listOf(90), asked)
        gate.complete(Unit)

        awaitChallenge("second try") { it is Challenge.Typing && it.tryId.tryNo == 2 }
        assertEquals(listOf(ChallengeOutcome.TIMEOUT), rows(o).map { it.outcome })
        assertTrue(typing().mistaken)
        assertTrue("and it is told as time that ran out, not as a wrong answer", typing().slow)
        assertEquals(2, typing().triesLeft)
    }

    @Test
    fun `a wrong answer after one that ran out is told as wrong again`() {
        val o = ringing("builtin_command")
        show(o)
        val gate = CompletableDeferred<Unit>()
        vm.attemptTimer = { gate.await() }
        vm.typingChanged(typing().tryId, "S")
        gate.complete(Unit)
        awaitChallenge("second try") { it is Challenge.Typing && it.tryId.tryNo == 2 && it.slow }

        vm.typingSubmitted(typing().tryId, "nope")

        awaitChallenge("third try") { it is Challenge.Typing && it.tryId.tryNo == 3 }
        assertTrue(typing().mistaken)
        assertFalse("now it was the answer", typing().slow)
    }

    @Test
    fun `doing nothing is not a try, with no key there is no clock`() {
        val o = ringing("builtin_command")
        show(o)
        val asked = ArrayList<Int>()
        vm.attemptTimer = { seconds -> asked += seconds }

        vm.typingChanged(typing().tryId, "")
        settle()

        assertTrue(asked.isEmpty())
        assertEquals(3, typing().triesLeft)
        assertTrue(rows(o).isEmpty())
    }

    @Test
    fun `the clock starts once, with the first key, and a new try gets a clock of its own`() {
        val o = ringing("builtin_command")
        show(o)
        val asked = ArrayList<Int>()
        vm.attemptTimer = { seconds ->
            asked += seconds
            awaitCancellation()
        }
        val first = typing().tryId

        vm.typingChanged(first, "S")
        vm.typingChanged(first, "St")
        vm.typingChanged(first, "Sta")
        awaitUntil(message = "the clock starts") { asked.isNotEmpty() }
        settle()
        assertEquals("three keys, one clock", 1, asked.size)

        vm.typingSubmitted(first, "wrong")
        awaitChallenge("second try") { it is Challenge.Typing && it.tryId.tryNo == 2 }
        vm.typingChanged(typing().tryId, "S")
        awaitUntil(message = "the second try's clock") { asked.size == 2 }
    }

    @Test
    fun `a clock that outlived its try counts nothing`() {
        val o = ringing("builtin_command")
        show(o)
        val gate = CompletableDeferred<Unit>()
        vm.attemptTimer = { gate.await() }
        val first = typing().tryId
        vm.typingChanged(first, "S")
        vm.typingSubmitted(first, "wrong")
        awaitChallenge("second try") { it is Challenge.Typing && it.tryId.tryNo == 2 }

        gate.complete(Unit)
        settle()

        assertEquals("only the wrong answer was a try", listOf(ChallengeOutcome.FAILED), rows(o).map { it.outcome })
        assertEquals(2, typing().triesLeft)
    }

    // --- the confirmation sequence ---------------------------------------------------------------------------------

    @Test
    fun `the numbers are tapped in order, and the last one passes the step and asks for the next`() {
        val o = ringing("builtin_reveille_max")
        show(o)

        val first = sequence()
        assertEquals(1, firing().progress?.step)
        assertEquals(2, firing().progress?.steps)
        assertEquals(setOf(1, 2, 3, 4), first.layout.toSet())
        assertNotEquals("never in order", listOf(1, 2, 3, 4), first.layout)
        assertEquals(0, first.tapped)
        assertEquals(3, first.triesLeft)

        for (n in 1..3) {
            vm.sequenceTapped(first.tryId, n)
            awaitChallenge("$n done") { it is Challenge.Sequence && it.tapped == n }
        }
        assertTrue("nothing is written until the try ends", rows(o).isEmpty())
        vm.sequenceTapped(first.tryId, 4)

        awaitChallenge("the second step") { it is Challenge.Typing }
        assertEquals(2, firing().progress?.step)
        assertEquals(TryId(1, 1), typing().tryId)
        val row = rows(o).single()
        assertEquals(HandshakeMode.CONFIRM_SEQUENCE, row.mode)
        assertEquals(0, row.chainPosition)
        assertEquals(ChallengeOutcome.SUCCESS, row.outcome)
        assertEquals("one of two is not enough", OccurrenceState.FIRING, stateOf(o))
    }

    @Test
    fun `a wrong tap starts the numbers again in another arrangement, with one try fewer`() {
        val o = ringing("builtin_reveille_max")
        show(o)
        val first = sequence()
        vm.sequenceTapped(first.tryId, 1)
        awaitChallenge("one done") { it is Challenge.Sequence && it.tapped == 1 }

        vm.sequenceTapped(first.tryId, 3)

        awaitChallenge("second try") { it is Challenge.Sequence && it.tryId.tryNo == 2 }
        val second = sequence()
        assertEquals(0, second.tapped)
        assertTrue(second.mistaken)
        assertEquals(2, second.triesLeft)
        assertNotEquals("the answer cannot be learnt by position", first.layout, second.layout)
        assertEquals(listOf(ChallengeOutcome.FAILED), rows(o).map { it.outcome })
    }

    @Test
    fun `a number that is done cannot be tapped again, and a second delivery of the tap is not a wrong try`() {
        val o = ringing("builtin_reveille_max")
        show(o)
        val at = sequence().tryId

        vm.sequenceTapped(at, 1)
        vm.sequenceTapped(at, 1)

        awaitChallenge("one done") { it is Challenge.Sequence && it.tapped == 1 }
        settle()
        assertEquals(1, sequence().tapped)
        assertFalse(sequence().mistaken)
        assertTrue(rows(o).isEmpty())
    }

    @Test
    fun `a tap for an earlier try changes nothing`() {
        val o = ringing("builtin_reveille_max")
        show(o)
        val first = sequence().tryId
        vm.sequenceTapped(first, 2) // wrong
        awaitChallenge("second try") { it is Challenge.Sequence && it.tryId.tryNo == 2 }

        vm.sequenceTapped(first, 1) // late: about a try that is over

        settle()
        assertEquals(0, sequence().tapped)
        assertEquals(listOf(ChallengeOutcome.FAILED), rows(o).map { it.outcome })
    }

    @Test
    fun `a sequence that is begun and not finished runs out and counts as a try`() {
        val o = ringing("builtin_reveille_max")
        show(o)
        val gate = CompletableDeferred<Unit>()
        vm.attemptTimer = { gate.await() }
        val at = sequence().tryId
        vm.sequenceTapped(at, 1)
        awaitChallenge("one done") { it is Challenge.Sequence && it.tapped == 1 }

        gate.complete(Unit)

        awaitChallenge("second try") { it is Challenge.Sequence && it.tryId.tryNo == 2 }
        assertEquals(listOf(ChallengeOutcome.TIMEOUT), rows(o).map { it.outcome })
        assertEquals(0, sequence().tapped)
        assertTrue("told as time that ran out", sequence().mistaken && sequence().slow)
    }

    @Test
    fun `when every way is used up the screen says so, the alarm keeps ringing, and silence still works`() {
        val o = ringing("builtin_reveille_max")
        ringingOccurrence = o
        show(o)

        // The numbers: three wrong taps, then typing in their place; three wrong phrases; then nothing is left.
        repeat(3) {
            val before = rows(o).size
            vm.sequenceTapped(sequence().tryId, 2)
            awaitUntil(message = "the wrong tap is written down") { rows(o).size > before }
            settle()
        }
        awaitChallenge("typing instead") { it is Challenge.Typing }
        failTyping(3)

        awaitChallenge("nothing left") { it === Challenge.OutOfTries }
        assertEquals(OccurrenceState.FIRING, stateOf(o))
        vm.silence()
        awaitUi("silenced") { it is ReveilleUiState.Silenced }
        assertEquals(OccurrenceState.SAFETY_SILENCED, stateOf(o))
    }

    // --- the two-step ------------------------------------------------------------------------------------------------

    @Test
    fun `a two-step asks what comes first, and saying it acknowledges but never starts`() {
        val o = ringing(profileOfDifficulty(5))
        show(o)
        assertEquals(1, firing().progress?.step)

        vm.typingSubmitted(typing().tryId, PHRASE)

        awaitChallenge("the two-step") { it is Challenge.TwoStep }
        val two = firing().challenge as Challenge.TwoStep
        assertEquals("Open the document", two.firstAction)
        assertEquals(2, firing().progress?.step)
        assertEquals(OccurrenceState.FIRING, stateOf(o))

        vm.twoStepIndicated(two.tryId)

        awaitUi("direction") { it is ReveilleUiState.Direction }
        assertEquals("never straight to started", OccurrenceState.GUIDED_START, stateOf(o))
        assertEquals(1, events(o, EventType.FIRST_ACTION_INDICATED).size)
        assertEquals(listOf(HandshakeMode.TYPING, HandshakeMode.TWO_STEP), rows(o).map { it.mode })
        assertEquals(listOf(0, 1), rows(o).map { it.chainPosition })
    }

    @Test
    fun `the phrase delivered twice does not also answer the question that comes next`() {
        val o = ringing(profileOfDifficulty(5))
        show(o)
        val at = typing().tryId

        vm.typingSubmitted(at, PHRASE)
        vm.typingSubmitted(at, PHRASE)

        awaitChallenge("the two-step") { it is Challenge.TwoStep }
        settle()
        assertTrue("still asking what comes first", firing().challenge is Challenge.TwoStep)
        assertEquals(OccurrenceState.FIRING, stateOf(o))
        assertTrue(events(o, EventType.FIRST_ACTION_INDICATED).isEmpty())
        assertEquals(listOf(ChallengeOutcome.SUCCESS), rows(o).map { it.outcome })
    }

    // --- the hold: the press, and the way for a person who cannot hold ------------------------------------------------

    @Test
    fun `a hold reports its own times, and the row is the press`() {
        val o = ringing("builtin_steady")
        show(o)
        assertEquals(TryId(0, 1), hold().tryId)
        assertFalse(hold().assistive)
        assertFalse(hold().acknowledging)

        vm.holdCompleted(hold().tryId, 100, 3_100)

        awaitUi("direction") { it is ReveilleUiState.Direction }
        val row = rows(o).single()
        assertEquals(100L, row.startedAt)
        assertEquals(3_100L, row.endedAt)
    }

    @Test
    fun `holds let go too early use the tries up, and then the three taps take the place of the press`() {
        val o = ringing("builtin_steady")
        ringingOccurrence = o
        show(o)

        repeat(4) { n ->
            val before = rows(o).size
            vm.holdReleasedEarly(hold().tryId, 1_000L * n, 1_000L * n + 400)
            awaitUntil(message = "release ${n + 1} is written down") { rows(o).size > before }
            settle()
        }

        awaitChallenge("the assistive confirm") { it is Challenge.Hold && it.assistive }
        assertEquals(ChallengeOutcome.FALLBACK, rows(o).last().outcome)
        assertEquals(OccurrenceState.FIRING, stateOf(o))

        vm.assistiveConfirmed(hold().tryId)

        awaitUi("direction") { it is ReveilleUiState.Direction }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
        assertEquals(ChallengeOutcome.ACCESSIBLE_ALT, rows(o).last().outcome)
    }

    @Test
    fun `a screen reader's confirm is on the screen from the start, and is recorded as the alternative`() {
        vm.offerAccessibleConfirm = true
        val o = ringing("builtin_steady")
        show(o)
        assertTrue(firing().offerAccessibleConfirm)

        vm.assistiveConfirmed(hold().tryId)

        awaitUi("direction") { it is ReveilleUiState.Direction }
        assertEquals(listOf(ChallengeOutcome.ACCESSIBLE_ALT), rows(o).map { it.outcome })
    }

    @Test
    fun `a press that began on an earlier try cannot finish this one`() {
        val o = ringing("builtin_steady")
        show(o)
        val first = hold().tryId
        vm.holdReleasedEarly(first, 100, 400)
        awaitChallenge("second try") { it is Challenge.Hold && it.tryId.tryNo == 2 }

        vm.holdCompleted(first, 100, 3_100)

        settle()
        assertEquals(OccurrenceState.FIRING, stateOf(o))
        assertEquals(listOf(ChallengeOutcome.FAILED), rows(o).map { it.outcome })
    }

    // --- silence, and a screen that is shown again ---------------------------------------------------------------------

    @Test
    fun `silence while a challenge is in front is written down as that, with the try it ended, and is not a success`() {
        val o = ringing("builtin_command")
        show(o)
        val begun = clock.nowMs()
        vm.typingChanged(typing().tryId, "S") // the first key: the try begins
        settle()
        clock.advance(7_000)

        vm.silence()

        awaitUi("silenced") { it is ReveilleUiState.Silenced }
        val row = rows(o).single()
        assertEquals(ChallengeOutcome.SAFETY_SILENCE, row.outcome)
        assertEquals(HandshakeMode.TYPING, row.mode)
        assertEquals(0, row.chainPosition)
        assertEquals(1, row.attemptNo)
        assertEquals("from the first key", begun, row.startedAt)
        assertEquals("to the tap", begun + 7_000, row.endedAt)
        assertEquals(OccurrenceState.SAFETY_SILENCED, stateOf(o))
    }

    @Test
    fun `once the ring is over, however it ended, a clock that was still running writes nothing`() {
        val ways = listOf<Pair<String, (ReveilleViewModel, OccurrenceRow) -> Unit>>(
            "Safety silence" to { model, _ -> model.silence() },
            "a skip" to { model, _ -> model.skip() },
            "a delay" to { model, _ -> model.delay(10) },
            "the notification's silence" to { _, o -> runBlocking { repo.apply(o.id, OccurrenceCommand.SafetySilence, EventSource.USER) } },
            "the ring running out" to { _, o -> runBlocking { repo.apply(o.id, OccurrenceCommand.Miss(MissReason.TIMEOUT), EventSource.SERVICE) } },
        )
        for ((way, end) in ways) {
            val o = ringing("builtin_command")
            val model = ReveilleViewModel(repo, clock, phrases, FixedCapability(), TokenRepository(db, clock))
            val gate = CompletableDeferred<Unit>()
            val asked = ArrayList<Int>()
            model.attemptTimer = { seconds ->
                asked += seconds
                gate.await()
            }
            model.show(o.id)
            awaitUntil(message = "$way: the ring is on screen") { (model.state.value as? ReveilleUiState.Firing)?.challenge is Challenge.Typing }
            model.typingChanged(((model.state.value as ReveilleUiState.Firing).challenge as Challenge.Typing).tryId, "S")
            awaitUntil(message = "$way: the clock starts") { asked.isNotEmpty() }

            end(model, o)
            awaitUntil(message = "$way: the ring is over") { model.state.value !is ReveilleUiState.Firing }
            gate.complete(Unit)
            settle(model)

            val written = rows(o).map { it.outcome }
            assertTrue("$way: no time-out is written for a ring that is over, and this was: $written", ChallengeOutcome.TIMEOUT !in written)
        }
    }

    @Test
    fun `Safety silence stops the clock of a begun try at the tap, before the database has answered`() {
        val o = ringing("builtin_command")
        val model = ReveilleViewModel(repo, clock, phrases, FixedCapability(), TokenRepository(db, clock))
        val gate = CompletableDeferred<Unit>()
        val asked = ArrayList<Int>()
        model.attemptTimer = { seconds ->
            asked += seconds
            gate.await()
        }
        model.show(o.id)
        awaitUntil(message = "the ring is on screen") { (model.state.value as? ReveilleUiState.Firing)?.challenge is Challenge.Typing }
        model.typingChanged(((model.state.value as ReveilleUiState.Firing).challenge as Challenge.Typing).tryId, "S")
        awaitUntil(message = "the clock starts") { asked.isNotEmpty() }

        model.silence()
        gate.complete(Unit) // the time is up at the very moment the silence is being written: the ring is still FIRING in the database
        settle(model)
        awaitUntil(message = "the ring is silenced") { model.state.value is ReveilleUiState.Silenced }

        val written = rows(o).map { it.outcome }
        assertTrue("no time-out is written after the tap, and this was: $written", ChallengeOutcome.TIMEOUT !in written)
        assertEquals(listOf(ChallengeOutcome.SAFETY_SILENCE), written)
    }

    @Test
    fun `what is sent after an event is handled after it, even while that event's database write is still going`() {
        val o = ringing("builtin_command")
        show(o)
        val at = typing().tryId

        vm.typingSubmitted(at, "not it") // a wrong phrase: the screen is redrawn once it has been written down
        var screenAtTheFence: ReveilleUiState? = null
        vm.afterPendingEvents { screenAtTheFence = vm.state.value }

        awaitUntil(message = "the fence has run") { screenAtTheFence != null }
        val challenge = (screenAtTheFence as ReveilleUiState.Firing).challenge as Challenge.Typing
        assertEquals("the fence was handled after the wrong phrase, so it sees the second try", 2, challenge.tryId.tryNo)
        assertTrue(challenge.mistaken)
    }

    @Test
    fun `a try's clock is stopped when the try ends, and does not wait to run out`() {
        val o = ringing("builtin_command")
        val model = ReveilleViewModel(repo, clock, phrases, FixedCapability(), TokenRepository(db, clock))
        val gate = CompletableDeferred<Unit>()
        val asked = ArrayList<Int>()
        val stopped = java.util.concurrent.atomic.AtomicInteger(0)
        model.attemptTimer = { seconds ->
            asked += seconds
            try {
                gate.await()
            } finally {
                stopped.incrementAndGet()
            }
        }
        model.show(o.id)
        awaitUntil(message = "the ring is on screen") { (model.state.value as? ReveilleUiState.Firing)?.challenge is Challenge.Typing }
        val first = ((model.state.value as ReveilleUiState.Firing).challenge as Challenge.Typing).tryId
        model.typingChanged(first, "S") // the first key: the try begins and its clock starts
        awaitUntil(message = "the clock starts") { asked.size == 1 }

        model.typingSubmitted(first, "not it") // the try ends, wrongly
        awaitUntil(message = "the screen asks for the next try") {
            ((model.state.value as? ReveilleUiState.Firing)?.challenge as? Challenge.Typing)?.tryId?.tryNo == 2
        }

        awaitUntil(message = "the clock of the try that ended was stopped, and was not left to run out") { stopped.get() == 1 }
        gate.complete(Unit)
    }

    @Test
    fun `a try that was waiting for its turn when Safety silence was tapped is not written down after the silence`() {
        val o = ringing("builtin_command")
        show(o)
        val first = typing().tryId

        vm.typingSubmitted(first, "not it") // being written down: it holds the turn
        vm.typingSubmitted(TryId(first.position, first.tryNo + 1, first.way), "not it either") // the second try, waiting for its turn
        vm.silence() // the tap does not wait for anybody

        awaitUi("silenced") { it is ReveilleUiState.Silenced }
        settle()
        // The silence does not wait for anybody, and nothing waits for it: the second try may have its turn and commit first, because its answer did
        // come before the tap. What may never happen is a row after the silence, which ends the history.
        val outcomes = rows(o).map { it.outcome }
        assertEquals("the end of the ring is the end of its history: $outcomes", ChallengeOutcome.SAFETY_SILENCE, outcomes.last())
        assertTrue("nothing but the two tries before it: $outcomes", outcomes.dropLast(1).all { it == ChallengeOutcome.FAILED } && outcomes.size <= 3)
    }

    @Test
    fun `an answer from the screen of another ring is not about the ring on screen, even when its try is numbered the same`() {
        val a = ringing("builtin_command")
        val b = ringing("builtin_command")
        val asked = ArrayList<Int>()
        vm.attemptTimer = { seconds ->
            asked += seconds
            awaitCancellation()
        }
        show(a)
        val tryOfA = typing().tryId
        show(b) // another ring takes over; it begins at try 1 as well
        assertEquals("every ring begins at the same try", tryOfA, typing().tryId)

        vm.typingChanged(a.id, tryOfA, "S") // a key from the old screen: it would start this ring's clock
        vm.typingSubmitted(a.id, tryOfA, PHRASE) // and the right phrase from it: it would pass this ring
        settle()
        assertEquals(OccurrenceState.FIRING, stateOf(b))
        assertTrue("nothing is written for the ring on screen", rows(b).isEmpty())
        assertTrue("and its clock was not started", asked.isEmpty())
        assertTrue("nor was the other ring's", rows(a).isEmpty())

        vm.typingChanged(b.id, tryOfA, "S") // the same events from this ring's own screen are heard
        awaitUntil(message = "the clock starts") { asked.isNotEmpty() }
        vm.typingSubmitted(b.id, tryOfA, PHRASE)
        awaitUi("direction") { it is ReveilleUiState.Direction }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(b))
        assertEquals(OccurrenceState.FIRING, stateOf(a))
    }

    @Test
    fun `the first of the three taps begins the try, and a try that is begun and left runs out and counts, from the first tap`() {
        vm.offerAccessibleConfirm = true
        val o = ringing("builtin_steady")
        val gate = CompletableDeferred<Unit>()
        val asked = ArrayList<Int>()
        vm.attemptTimer = { seconds ->
            asked += seconds
            gate.await()
        }
        show(o)
        val first = hold().tryId
        assertTrue("nothing has been tapped: no clock", asked.isEmpty())

        val begun = clock.nowMs()
        vm.assistiveBegun(first) // the first tap
        awaitUntil(message = "the clock starts") { asked.isNotEmpty() }
        clock.advance(4_000)
        vm.assistiveBegun(first) // a second tap does not start another
        settle()
        assertEquals(1, asked.size)

        gate.complete(Unit) // the time is up
        awaitChallenge("the next try") { it is Challenge.Hold && it.tryId.tryNo == 2 }
        val row = rows(o).single()
        assertEquals(ChallengeOutcome.TIMEOUT, row.outcome)
        assertEquals("from the first tap", begun, row.startedAt)
        assertEquals(begun + 4_000, row.endedAt)
        assertEquals(OccurrenceState.FIRING, stateOf(o))
    }

    @Test
    fun `Safety silence tapped during a press ends a row that starts where the press began`() {
        val o = ringing("builtin_steady")
        show(o)
        val now = clock.nowMs()
        vm.holdStarted(hold().tryId, now - 1_500) // the finger went down a moment ago and is still down
        settle()

        vm.silence()

        awaitUi("silenced") { it is ReveilleUiState.Silenced }
        val row = rows(o).single()
        assertEquals(ChallengeOutcome.SAFETY_SILENCE, row.outcome)
        assertEquals("from the press", now - 1_500, row.startedAt)
        assertEquals("to the tap", now, row.endedAt)
    }

    @Test
    fun `with the real clock a begun try runs out after ninety seconds and not before, and doing nothing never does`() {
        val o = ringing("builtin_command")
        show(o)
        vm.typingChanged(typing().tryId, "") // nothing typed: nothing begun
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        settle()
        assertTrue("ten minutes of doing nothing is no try", rows(o).isEmpty())
        assertEquals(3, typing().triesLeft)

        vm.typingChanged(typing().tryId, "S") // the first key
        settle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        settle()
        assertTrue("sixty seconds is not yet ninety", rows(o).isEmpty())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        awaitChallenge("second try") { it is Challenge.Typing && it.tryId.tryNo == 2 }
        assertEquals(listOf(ChallengeOutcome.TIMEOUT), rows(o).map { it.outcome })
    }

    @Test
    fun `the times of a try's row are when its first key came and when it ended, and a try does not inherit the one before's`() {
        val o = ringing("builtin_command")
        show(o)
        val first = clock.nowMs()
        vm.typingChanged(typing().tryId, "S")
        settle()
        clock.advance(5_000)
        vm.typingSubmitted(typing().tryId, "wrong")
        awaitChallenge("second try") { it is Challenge.Typing && it.tryId.tryNo == 2 }
        assertEquals(first, rows(o)[0].startedAt)
        assertEquals(first + 5_000, rows(o)[0].endedAt)

        val gate = CompletableDeferred<Unit>()
        vm.attemptTimer = { gate.await() }
        clock.advance(1_000)
        val second = clock.nowMs()
        vm.typingChanged(typing().tryId, "S")
        settle()
        clock.advance(90_000)
        gate.complete(Unit)
        awaitChallenge("third try") { it is Challenge.Typing && it.tryId.tryNo == 3 }
        assertEquals(ChallengeOutcome.TIMEOUT, rows(o)[1].outcome)
        assertEquals(second, rows(o)[1].startedAt)
        assertEquals("a time-out ends the moment the clock ran out", second + 90_000, rows(o)[1].endedAt)

        vm.attemptTimer = { awaitCancellation() }
        clock.advance(2_000)
        val third = clock.nowMs()
        vm.typingChanged(typing().tryId, "S")
        settle()
        clock.advance(1_000)
        vm.typingSubmitted(typing().tryId, "wrong again")
        awaitUntil(message = "the third row") { rows(o).size >= 3 }
        assertEquals("the third try began with its own first key", third, rows(o)[2].startedAt)
        assertEquals(third + 1_000, rows(o)[2].endedAt)
    }

    @Test
    fun `a screen that is shown again goes on where the person was`() {
        val o = ringing("builtin_command")
        ringingOccurrence = o
        show(o)
        failTyping(2)
        awaitChallenge("third try") { it is Challenge.Typing && it.tryId.tryNo == 3 }

        val fresh = ReveilleViewModel(repo, clock, phrases, FixedCapability(), TokenRepository(db, clock)) // a new process, a new view model, the same stored occurrence
        fresh.show(o.id)
        awaitUntil(message = "restored") { ((fresh.state.value as? ReveilleUiState.Firing)?.challenge as? Challenge.Typing)?.tryId?.tryNo == 3 }

        val restored = (fresh.state.value as ReveilleUiState.Firing).challenge as Challenge.Typing
        assertEquals(1, restored.triesLeft)
        assertTrue("it says again that the last one was wrong", restored.mistaken)
        assertEquals("the same phrase", PHRASE, restored.phrase)
    }

    @Test
    fun `a chain with a step passed is restored on the step that is left`() {
        val o = ringing("builtin_reveille_max")
        show(o)
        val at = sequence().tryId
        for (n in 1..4) vm.sequenceTapped(at, n)
        awaitChallenge("typing") { it is Challenge.Typing }

        val fresh = ReveilleViewModel(repo, clock, phrases, FixedCapability(), TokenRepository(db, clock))
        fresh.show(o.id)
        awaitUntil(message = "restored") { ((fresh.state.value as? ReveilleUiState.Firing)?.challenge as? Challenge.Typing)?.tryId == TryId(1, 1) }

        assertEquals(2, (fresh.state.value as ReveilleUiState.Firing).progress?.step)
        assertEquals("no 'that is not it' for a try that was not made", false, ((fresh.state.value as ReveilleUiState.Firing).challenge as Challenge.Typing).mistaken)
    }

    @Test
    fun `a hold let go twice is restored with its hint and the third try`() {
        val o = ringing("builtin_steady")
        ringingOccurrence = o
        show(o)
        repeat(2) { n ->
            val before = rows(o).size
            vm.holdReleasedEarly(hold().tryId, 1_000L * n, 1_000L * n + 400)
            awaitUntil(message = "release ${n + 1} is written down") { rows(o).size > before }
            settle()
        }

        val fresh = ReveilleViewModel(repo, clock, phrases, FixedCapability(), TokenRepository(db, clock))
        fresh.show(o.id)
        awaitUntil(message = "restored") { ((fresh.state.value as? ReveilleUiState.Firing)?.challenge as? Challenge.Hold)?.tryId?.tryNo == 3 }

        assertTrue((((fresh.state.value as ReveilleUiState.Firing).challenge) as Challenge.Hold).earlyRelease)
    }

    @Test
    fun `a screen that is shown again finds what was there, whichever kind of try was the last`() {
        // After a wrong phrase, after a try that ran out, after a change of way, after the press is used up, at the end of the road and at
        // the question of a two-step: the screen that comes back is the screen that was there (the words under the challenge included).
        fun comesBackAs(live: ReveilleUiState, o: OccurrenceRow, what: String) {
            val fresh = ReveilleViewModel(repo, clock, phrases, FixedCapability(), TokenRepository(db, clock))
            fresh.show(o.id)
            awaitUntil(message = "$what: restored") { fresh.state.value::class == live::class }
            assertEquals("$what: the restored screen is the one that was there", live, fresh.state.value)
        }
        fun newRing(profile: String): OccurrenceRow {
            val o = ringing(profile)
            ringingOccurrence = o
            vm = ReveilleViewModel(repo, clock, phrases, FixedCapability(), TokenRepository(db, clock))
            show(o)
            return o
        }

        var o = newRing("builtin_command")
        failTyping(1)
        comesBackAs(vm.state.value, o, "after a wrong phrase")

        o = newRing("builtin_command")
        val gate = CompletableDeferred<Unit>()
        vm.attemptTimer = { gate.await() }
        vm.typingChanged(typing().tryId, "S")
        gate.complete(Unit)
        awaitChallenge("second try") { it is Challenge.Typing && it.tryId.tryNo == 2 && it.slow }
        comesBackAs(vm.state.value, o, "after a try that ran out")

        o = newRing("builtin_command")
        failTyping(3)
        awaitChallenge("the numbers instead") { it is Challenge.Sequence }
        comesBackAs(vm.state.value, o, "after a change of way")

        o = newRing("builtin_steady")
        repeat(4) { n ->
            val before = rows(o).size
            vm.holdReleasedEarly(hold().tryId, 1_000L * n, 1_000L * n + 400)
            awaitUntil(message = "release ${n + 1} is written down") { rows(o).size > before }
            settle()
        }
        awaitChallenge("the three taps") { it is Challenge.Hold && it.assistive }
        comesBackAs(vm.state.value, o, "after the press is used up")

        o = newRing("builtin_reveille_max")
        repeat(3) {
            val before = rows(o).size
            vm.sequenceTapped(sequence().tryId, 2)
            awaitUntil(message = "the wrong tap is written down") { rows(o).size > before }
            settle()
        }
        awaitChallenge("typing instead") { it is Challenge.Typing }
        failTyping(3)
        awaitChallenge("nothing left") { it === Challenge.OutOfTries }
        comesBackAs(vm.state.value, o, "at the end of the road")

        o = newRing(profileOfDifficulty(5))
        vm.typingSubmitted(typing().tryId, PHRASE)
        awaitChallenge("the two-step") { it is Challenge.TwoStep }
        comesBackAs(vm.state.value, o, "at the question of a two-step")
    }

    @Test
    fun `a row the replay refuses does not change what the restored screen says`() {
        val o = ringing("builtin_command") // typing
        runBlocking {
            repo.recordChallenge(o.id, ChallengeRecord(0, HandshakeMode.TYPING, 1, ChallengeOutcome.FAILED, 1_000, 2_000))
            // Not a try of the way that is on screen (a hold's row for a step that asks for typing): it counts for nothing, and says nothing.
            repo.recordChallenge(o.id, ChallengeRecord(0, HandshakeMode.HOLD, 2, ChallengeOutcome.SUCCESS, 3_000, 4_000))
        }

        vm.show(o.id)

        awaitChallenge("the second try") { it is Challenge.Typing && it.tryId.tryNo == 2 }
        assertTrue("the words still say that the last try was wrong", typing().mistaken)
        assertEquals(2, typing().triesLeft)
        assertEquals(OccurrenceState.FIRING, stateOf(o))
    }

    @Test
    fun `a ring whose rows already satisfy its chain is acknowledged at once, and not left on a spinner without a way out`() {
        val o = ringing("builtin_steady") // a hold
        runBlocking { repo.recordChallenge(o.id, ChallengeRecord(0, HandshakeMode.HOLD, 1, ChallengeOutcome.SUCCESS, 1_000, 4_000)) }

        vm.show(o.id)

        awaitUi("direction") { it is ReveilleUiState.Direction }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
        assertEquals("and nothing is written twice", listOf(ChallengeOutcome.SUCCESS), rows(o).map { it.outcome })
    }

    @Test
    fun `a profile edited while the ring sounds changes nothing for the person in the middle of it`() {
        val o = ringing("builtin_command") // typing, in the accountability partner's words
        ringingOccurrence = o
        show(o)
        failTyping(2)
        awaitChallenge("third try") { it is Challenge.Typing && it.tryId.tryNo == 3 }
        runBlocking { db.profiles().upsert(db.profiles().get("builtin_command")!!.copy(handshakeDifficulty = 1, personality = Personality.MINIMALIST)) }

        val later = FixedPhrases(listOf(PHRASE))
        val fresh = ReveilleViewModel(repo, clock, later, FixedCapability(), TokenRepository(db, clock)) // a new process, after the edit
        fresh.show(o.id)
        awaitUntil(message = "restored") { ((fresh.state.value as? ReveilleUiState.Firing)?.challenge as? Challenge.Typing)?.tryId?.tryNo == 3 }

        val restored = (fresh.state.value as ReveilleUiState.Firing).challenge as Challenge.Typing
        assertEquals("still typing, not the hold the edited profile asks for", 1, restored.triesLeft)
        assertEquals("and in the words it was fired with", listOf(Personality.ACCOUNTABILITY_PARTNER), later.asked)
    }

    @Test
    fun `a stale answer changes nothing, neither a row nor a refused command nor the words and tries on screen, and the clock keeps running`() {
        val o = ringing("builtin_command")
        show(o)
        val first = typing().tryId
        vm.typingSubmitted(first, "nope")
        awaitChallenge("second try") { it is Challenge.Typing && it.tryId.tryNo == 2 }
        val gate = CompletableDeferred<Unit>()
        vm.attemptTimer = { gate.await() }
        vm.typingChanged(typing().tryId, "S") // the clock of the try on screen is running
        settle()
        val before = vm.state.value

        vm.typingSubmitted(first, PHRASE) // the right words, for a try that is over
        vm.sequenceTapped(first, 1)
        vm.holdCompleted(first, 0, 3_000)
        vm.assistiveConfirmed(first)
        vm.twoStepIndicated(first)
        settle()

        assertEquals("the screen as it was: the words, the tries", before, vm.state.value)
        assertEquals(listOf(ChallengeOutcome.FAILED), rows(o).map { it.outcome })
        assertTrue("nothing was refused by the state machine either", events(o, EventType.REJECTED_TRANSITION).isEmpty())
        gate.complete(Unit)
        awaitChallenge("third try") { it is Challenge.Typing && it.tryId.tryNo == 3 }
        assertEquals("the clock of the try on screen still ran out", listOf(ChallengeOutcome.FAILED, ChallengeOutcome.TIMEOUT), rows(o).map { it.outcome })
    }

    @Test
    fun `a screen reader turned on while the alarm rings puts the three taps on the screen at once, and off takes them away`() {
        val o = ringing("builtin_steady")
        show(o)
        assertFalse(firing().offerAccessibleConfirm)

        vm.offerAccessibleConfirm = true
        awaitUi("offered") { (it as? ReveilleUiState.Firing)?.offerAccessibleConfirm == true }

        vm.offerAccessibleConfirm = false
        awaitUi("withdrawn") { (it as? ReveilleUiState.Firing)?.offerAccessibleConfirm == false }
    }

    @Test
    fun `an answer queued for the ring that was on screen is dropped when another ring takes over before its turn`() {
        val first = ringing("builtin_command")
        val second = ringing("builtin_command")
        show(first)
        val at = typing().tryId

        vm.typingSubmitted(at, "wrong") // takes its turn, and waits on the database
        vm.typingSubmitted(TryId(at.position, at.tryNo + 1), PHRASE) // queued behind it: the right words for the next try
        vm.show(second.id)
        awaitUi("the second ring") { (it as? ReveilleUiState.Firing)?.occurrenceId == second.id }
        settle()

        assertEquals("the queued answer was for the ring that is gone", OccurrenceState.FIRING, stateOf(first))
        assertEquals(OccurrenceState.FIRING, stateOf(second))
        assertTrue(rows(second).isEmpty())
        assertEquals(TryId(0, 1), typing().tryId)
    }

    private companion object {
        const val PHRASE = "Starting now"
    }
}
