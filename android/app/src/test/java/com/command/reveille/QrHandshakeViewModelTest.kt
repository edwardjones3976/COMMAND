package com.command.reveille

import com.command.data.OccurrenceRepository
import com.command.data.ProfileRepository
import com.command.data.RegisterResult
import com.command.data.SaveChainResult
import com.command.data.TokenRepository
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.StepSpec
import com.command.domain.handshake.HandshakeCapability
import com.command.domain.handshake.TryId
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.testing.FakeClock
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
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

/**
 * The QR handshake through the view model, against a real in-memory database and a phone that can or cannot scan ([FixedCapability]): when
 * a ring asks for a code, what a scan does, what is written down, and what happens when the phone stops being able to scan.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class QrHandshakeViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var tokens: TokenRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock

    private val phrases = FixedPhrases(listOf(PHRASE))
    private val phone = FixedCapability(HandshakeCapability.ALL)
    private lateinit var vm: ReveilleViewModel

    @Before
    fun setUp() {
        hilt.inject()
        vm = model()
        runBlocking { check(tokens.register(HandshakeMode.QR, "Kitchen", CODE) is RegisterResult.Saved) }
    }

    private fun model(capability: FixedCapability = phone) = ReveilleViewModel(repo, clock, phrases, capability, tokens)

    private fun step(mode: HandshakeMode, tries: Int = 3) = StepSpec(mode, tries, 90, 0)

    /** A ringing occurrence whose profile asks for [chain]. */
    private fun ringingWith(chain: ChainSpec = ChainSpec(ChainOp.SEQUENCE, listOf(step(HandshakeMode.QR)))): OccurrenceRow = runBlocking {
        check(profiles.saveChain("builtin_steady", chain) == SaveChainResult.Saved)
        val mission = testMission()
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        repo.get(o.id)!!
    }

    private fun rows(o: OccurrenceRow) = runBlocking { db.challenges().forOccurrence(o.id) }
    private fun stateOf(o: OccurrenceRow) = runBlocking { repo.get(o.id)!!.state }
    private fun firing(model: ReveilleViewModel = vm) = model.state.value as ReveilleUiState.Firing
    private fun qr(model: ReveilleViewModel = vm) = firing(model).challenge as Challenge.Qr

    private fun show(o: OccurrenceRow, model: ReveilleViewModel = vm) {
        model.show(o.id)
        awaitUntil(message = "firing") { model.state.value is ReveilleUiState.Firing }
    }

    private fun awaitChallenge(message: String, model: ReveilleViewModel = vm, check: (Challenge) -> Boolean) =
        awaitUntil(message = message) { (model.state.value as? ReveilleUiState.Firing)?.challenge?.let(check) == true }

    private fun settle(model: ReveilleViewModel = vm) {
        var handled = false
        model.afterPendingEvents { handled = true }
        awaitUntil(message = "the events before this one have been handled") { handled }
    }

    // --- when a code is asked for -------------------------------------------------------------------------------

    @Test
    fun `a phone that can scan is asked for a code, with every try ahead and nothing written`() {
        val o = ringingWith()
        show(o)

        assertEquals(Challenge.Qr(TryId(0, 1), 3, mistaken = false), firing().challenge)
        assertTrue(rows(o).isEmpty())
        assertTrue("the phone was read", phone.reads > 0)
    }

    @Test
    fun `a phone that cannot scan is asked to type instead, and the record says so once`() {
        val o = ringingWith()
        val cannot = model(FixedCapability(HandshakeCapability.NONE))
        show(o, cannot)
        assertTrue(firing(cannot).challenge is Challenge.Typing)
        val fresh = model(FixedCapability(HandshakeCapability.NONE))
        show(o, fresh)

        assertTrue(firing(fresh).challenge is Challenge.Typing)
        val row = rows(o).single()
        assertEquals(HandshakeMode.QR, row.mode)
        assertEquals(ChallengeOutcome.FALLBACK, row.outcome)
        assertEquals(0, row.attemptNo)
    }

    @Test
    fun `a way that the phone can do is offered by its own name beside another step`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.TYPING), step(HandshakeMode.QR))))
        show(o)

        assertEquals(listOf(Alternative(1, HandshakeMode.QR, instead = true)), firing().alternatives)
        vm.switchTo(1)
        awaitChallenge("the code") { it is Challenge.Qr }
        assertTrue(rows(o).isEmpty())
    }

    @Test
    fun `a phone that stops being able to scan between a try and a restore writes one row for it, and a second restore writes none`() {
        val o = ringingWith()
        show(o)
        vm.qrScanned(qr().tryId, "not a registered code")
        awaitChallenge("a second try") { it is Challenge.Qr && it.tryId.tryNo == 2 }

        val cannot = FixedCapability(HandshakeCapability.NONE)
        val fresh = model(cannot)
        show(o, fresh)
        assertTrue("asked to type", firing(fresh).challenge is Challenge.Typing)
        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.FALLBACK), rows(o).map { it.outcome })
        assertEquals("the row names the way that was left, numbered with its tries", listOf(HandshakeMode.QR to 1), rows(o).drop(1).map { it.mode to it.attemptNo })

        val again = model(cannot)
        show(o, again)
        assertTrue(firing(again).challenge is Challenge.Typing)
        assertEquals("nothing more", 2, rows(o).size)

        val able = model(FixedCapability(HandshakeCapability.ALL))
        show(o, able)
        assertTrue("a phone that can scan again does not take the step back", firing(able).challenge is Challenge.Typing)
        assertEquals(2, rows(o).size)
    }

    // --- what a scan does -----------------------------------------------------------------------------------------

    @Test
    fun `a registered code acknowledges the alarm and leads to the direction`() {
        val o = ringingWith()
        show(o)

        vm.qrScanned(qr().tryId, "  $CODE ")

        awaitUntil(message = "direction") { vm.state.value is ReveilleUiState.Direction }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
        val row = rows(o).single()
        assertEquals(HandshakeMode.QR, row.mode)
        assertEquals(ChallengeOutcome.SUCCESS, row.outcome)
        assertEquals(1, row.attemptNo)
    }

    @Test
    fun `a code that is not registered uses one try, and the same code held in front of the lens uses none until it has been out of view for three seconds`() {
        val o = ringingWith()
        show(o)

        vm.qrScanned(qr().tryId, "someone else's code")
        awaitChallenge("a second try") { it is Challenge.Qr && it.tryId.tryNo == 2 }
        assertEquals(Challenge.Qr(TryId(0, 2), 2, mistaken = true), firing().challenge)
        repeat(30) { vm.qrScanned(qr().tryId, "someone else's code") }
        clock.advance(2_900)
        vm.qrScanned(qr().tryId, "someone else's code")
        clock.advance(2_900)
        vm.qrScanned(qr().tryId, "someone else's code")
        settle()
        assertEquals("one try, not thirty a second, and not one every three seconds", 1, rows(o).size)
        assertEquals(2, qr().triesLeft)

        clock.advance(3_100)
        vm.qrScanned(qr().tryId, "someone else's code")
        awaitChallenge("a third try") { it is Challenge.Qr && it.tryId.tryNo == 3 }
        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.FAILED), rows(o).map { it.outcome })
    }

    @Test
    fun `a wrong code held still for ten seconds, read ten times a second, uses exactly one try`() {
        val o = ringingWith()
        show(o)

        vm.qrScanned(qr().tryId, "someone else's code")
        awaitChallenge("a second try") { it is Challenge.Qr && it.tryId.tryNo == 2 }
        repeat(100) {
            clock.advance(100)
            vm.qrScanned(qr().tryId, "someone else's code")
        }
        settle()

        assertEquals(1, rows(o).size)
        assertEquals(2, qr().triesLeft)
    }

    @Test
    fun `two wrong codes alternating thirty times in three seconds use exactly two tries`() {
        val o = ringingWith()
        show(o)

        repeat(30) { i ->
            vm.qrScanned(qr().tryId, if (i % 2 == 0) "wrong one" else "wrong two")
            settle()
            clock.advance(100)
        }
        settle()

        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.FAILED), rows(o).map { it.outcome })
        assertEquals(1, qr().triesLeft)
    }

    @Test
    fun `a wrong code that leaves view for more than three seconds and comes back counts again`() {
        val o = ringingWith()
        show(o)

        vm.qrScanned(qr().tryId, "someone else's code")
        awaitChallenge("a second try") { it is Challenge.Qr && it.tryId.tryNo == 2 }
        clock.advance(3_001)
        vm.qrScanned(qr().tryId, "someone else's code")
        awaitChallenge("a third try") { it is Challenge.Qr && it.tryId.tryNo == 3 }

        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.FAILED), rows(o).map { it.outcome })
    }

    @Test
    fun `another wrong code right after the first is a try of its own, and the registered code always counts`() {
        val o = ringingWith()
        show(o)
        vm.qrScanned(qr().tryId, "wrong one")
        awaitChallenge("a second try") { it is Challenge.Qr && it.tryId.tryNo == 2 }

        vm.qrScanned(qr().tryId, "wrong two")
        awaitChallenge("a third try") { it is Challenge.Qr && it.tryId.tryNo == 3 }
        vm.qrScanned(qr().tryId, CODE)
        awaitUntil(message = "direction") { vm.state.value is ReveilleUiState.Direction }
        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.FAILED, ChallengeOutcome.SUCCESS), rows(o).map { it.outcome })
    }

    @Test
    fun `a blank scan is not a code, so it uses no try and writes nothing`() {
        val o = ringingWith()
        show(o)

        vm.qrScanned(qr().tryId, "")
        vm.qrScanned(qr().tryId, "   ")
        settle()

        assertTrue(rows(o).isEmpty())
        assertEquals(Challenge.Qr(TryId(0, 1), 3, mistaken = false), firing().challenge)
    }

    @Test
    fun `a scan for a try that is not on screen, or from the screen of another ring, does nothing`() {
        val other = ringingWith()
        val o = ringingWith()
        show(o)

        vm.qrScanned(TryId(0, 2), CODE)
        vm.qrScanned(other.id, qr().tryId, CODE)
        vm.qrUnavailable(other.id, qr().tryId)
        settle()

        assertTrue(rows(o).isEmpty())
        assertTrue(rows(other).isEmpty())
        assertEquals(OccurrenceState.FIRING, stateOf(o))
        assertTrue(firing().challenge is Challenge.Qr)
    }

    // --- when the phone stops being able to scan -------------------------------------------------------------------

    @Test
    fun `a camera that fails during the ring turns the step into typing, with a fresh allowance, and writes one row`() {
        val o = ringingWith()
        show(o)
        vm.qrScanned(qr().tryId, "wrong")
        awaitChallenge("a second try") { it is Challenge.Qr && it.tryId.tryNo == 2 }

        val failed = qr().tryId
        vm.qrUnavailable(failed)
        awaitChallenge("typing") { it is Challenge.Typing }
        val typing = firing().challenge as Challenge.Typing
        assertEquals(3, typing.triesLeft)
        assertFalse("a new way starts clean", typing.mistaken)
        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.FALLBACK), rows(o).map { it.outcome })

        vm.qrUnavailable(failed) // the camera's second report of the same failure
        settle()
        assertEquals(2, rows(o).size)
    }

    @Test
    fun `cannot scan, tapped before any scan, asks a phrase instead without using a try, writes one row, and a second tap does nothing`() {
        val o = ringingWith()
        show(o)

        val tapped = qr().tryId
        vm.qrUnavailable(tapped) // the "Cannot scan? Type a phrase instead" control
        awaitChallenge("typing") { it is Challenge.Typing }
        val typing = firing().challenge as Challenge.Typing
        assertEquals("no try was used", 3, typing.triesLeft)
        assertFalse(typing.mistaken)
        val row = rows(o).single()
        assertEquals(HandshakeMode.QR, row.mode)
        assertEquals(ChallengeOutcome.FALLBACK, row.outcome)
        assertEquals("not a try", 0, row.attemptNo)
        assertEquals(OccurrenceState.FIRING, stateOf(o))

        vm.qrUnavailable(tapped) // a second tap
        settle()
        assertEquals(1, rows(o).size)
        assertEquals(typing, firing().challenge)
    }

    @Test
    fun `a camera that fails while another step is on screen changes nothing`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.TYPING), step(HandshakeMode.QR))))
        show(o)

        vm.qrUnavailable((firing().challenge as Challenge.Typing).tryId)
        settle()

        assertTrue(rows(o).isEmpty())
        assertEquals(listOf(Alternative(1, HandshakeMode.QR, instead = true)), firing().alternatives)
    }

    @Test
    fun `coming back to the front with the camera consent withdrawn turns every step that scans into typing, once`() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.HOLD), step(HandshakeMode.QR))))
        show(o)
        assertEquals(listOf(Alternative(1, HandshakeMode.QR, instead = true)), firing().alternatives)

        phone.now = HandshakeCapability.NONE
        vm.recheckCapability()
        awaitUntil(message = "the other way is typing now") { firing().alternatives == listOf(Alternative(1, HandshakeMode.TYPING, instead = true)) }
        assertTrue("the press on screen is untouched", firing().challenge is Challenge.Hold)
        assertEquals(listOf(HandshakeMode.QR to ChallengeOutcome.FALLBACK), rows(o).map { it.mode to it.outcome })

        vm.recheckCapability()
        phone.now = HandshakeCapability.ALL
        vm.recheckCapability()
        settle()
        assertEquals(1, rows(o).size)
    }

    private companion object {
        const val PHRASE = "Starting now"
        const val CODE = "command-qr:0123456789abcdefghijkl"
    }
}
