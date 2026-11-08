package com.command

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
import com.command.domain.handshake.TagRead
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.reveille.Challenge
import com.command.reveille.FixedCapability
import com.command.reveille.FixedPhrases
import com.command.reveille.ReveilleUiState
import com.command.reveille.ReveilleViewModel
import com.command.testing.FakeClock
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * What the activity gives the ring screen: everything about the handshake bound to the ring the screen was drawn for. The screen of a ring
 * that was replaced may still deliver a callback, and every ring begins at try 1, so the try alone cannot tell it from this ring's.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class RingActionsTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var tokens: TokenRepository

    private val phrases = FixedPhrases(listOf(PHRASE))
    private lateinit var vm: ReveilleViewModel

    @Before
    fun setUp() {
        hilt.inject()
        vm = ReveilleViewModel(repo, clock, phrases, FixedCapability(), tokens)
    }

    private fun ringing(): OccurrenceRow = runBlocking {
        val mission = testMission(profileId = "builtin_command") // typing
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        repo.get(o.id)!!
    }

    private fun stateOf(o: OccurrenceRow) = runBlocking { repo.get(o.id)!!.state }
    private fun rows(o: OccurrenceRow) = runBlocking { db.challenges().forOccurrence(o.id) }

    private fun show(o: OccurrenceRow) {
        vm.show(o.id)
        awaitUntil(message = "the ring is on screen") { (vm.state.value as? ReveilleUiState.Firing)?.challenge is Challenge.Typing }
    }

    private fun settle() {
        var handled = false
        vm.afterPendingEvents { handled = true }
        awaitUntil(message = "the events before this one have been handled") { handled }
    }

    @Test
    fun `the callbacks of a screen drawn for another ring are not about the ring on screen, and those of its own screen are`() {
        val a = ringing()
        val b = ringing()
        val asked = ArrayList<Int>()
        vm.attemptTimer = { seconds ->
            asked += seconds
            awaitCancellation()
        }
        show(a)
        val oldScreen = ringActions(vm, a.id) {} // what the screen of ring A was given
        val tryId = ((vm.state.value as ReveilleUiState.Firing).challenge as Challenge.Typing).tryId
        show(b) // ring B takes over, and begins at the same try
        val newScreen = ringActions(vm, b.id) {}

        oldScreen.onTypingChanged(tryId, "S")
        oldScreen.onTypingSubmitted(tryId, PHRASE)
        oldScreen.onSequenceTapped(tryId, 1)
        oldScreen.onHoldCompleted(tryId, 1_000, 4_000)
        oldScreen.onAssistiveBegun(tryId)
        oldScreen.onAssistiveConfirmed(tryId)
        oldScreen.onTwoStepIndicated(tryId)
        settle()
        assertEquals("the ring on screen is still ringing", OccurrenceState.FIRING, stateOf(b))
        assertTrue("and nothing was written for it", rows(b).isEmpty())
        assertTrue("and no clock was started for it", asked.isEmpty())

        newScreen.onTypingChanged(tryId, "S")
        awaitUntil(message = "its own screen's key starts its clock") { asked.isNotEmpty() }
        newScreen.onTypingSubmitted(tryId, PHRASE)
        awaitUntil(message = "and its own screen's answer is heard") { vm.state.value is ReveilleUiState.Direction }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(b))
        assertEquals("the other ring was never touched", OccurrenceState.FIRING, stateOf(a))
    }

    @Test
    fun `with no ring on the screen a handshake callback does nothing`() {
        val o = ringing()
        show(o)
        val tryId = ((vm.state.value as ReveilleUiState.Firing).challenge as Challenge.Typing).tryId
        val loading = ringActions(vm, null) {}

        loading.onTypingSubmitted(tryId, PHRASE)
        loading.onHoldCompleted(tryId, 1_000, 4_000)
        settle()

        assertEquals(OccurrenceState.FIRING, stateOf(o))
        assertTrue(rows(o).isEmpty())
    }

    /** Two rings that ask for a code, on a phone that can scan one, and a view model for that phone with a code registered. */
    private fun scanning(): Pair<OccurrenceRow, OccurrenceRow> = runBlocking {
        check(profiles.saveChain("builtin_steady", ChainSpec(ChainOp.SEQUENCE, listOf(StepSpec(HandshakeMode.QR, 3, 90, 0)))) == SaveChainResult.Saved)
        check(tokens.register(HandshakeMode.QR, "Kitchen", CODE) is RegisterResult.Saved)
        vm = ReveilleViewModel(repo, clock, phrases, FixedCapability(HandshakeCapability.ALL), tokens)
        suspend fun one(): OccurrenceRow {
            val mission = testMission()
            db.missions().insert(mission)
            val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
            repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
            repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
            return repo.get(o.id)!!
        }
        one() to one()
    }

    private fun showScanning(o: OccurrenceRow) {
        vm.show(o.id)
        awaitUntil(message = "the code is asked for") { (vm.state.value as? ReveilleUiState.Firing)?.challenge is Challenge.Qr }
    }

    @Test
    fun `a code read, or a camera that fails, on the screen of another ring is not about the ring on screen, and on its own screen it is`() {
        val (a, b) = scanning()
        showScanning(a)
        val oldScreen = ringActions(vm, a.id) {}
        val tryId = ((vm.state.value as ReveilleUiState.Firing).challenge as Challenge.Qr).tryId
        showScanning(b)
        val newScreen = ringActions(vm, b.id) {}

        oldScreen.onQrScanned(tryId, CODE)
        oldScreen.onQrUnavailable(tryId)
        settle()
        assertEquals(OccurrenceState.FIRING, stateOf(b))
        assertTrue("nothing was written for the ring on screen", rows(b).isEmpty())
        assertTrue("it still asks for the code", (vm.state.value as ReveilleUiState.Firing).challenge is Challenge.Qr)

        newScreen.onQrUnavailable(tryId)
        awaitUntil(message = "its own screen's camera failure is heard") { (vm.state.value as? ReveilleUiState.Firing)?.challenge is Challenge.Typing }
        assertEquals(listOf(HandshakeMode.QR to ChallengeOutcome.FALLBACK), rows(b).map { it.mode to it.outcome })
        assertEquals("the other ring was never touched", OccurrenceState.FIRING, stateOf(a))
        assertTrue(rows(a).isEmpty())
    }

    @Test
    fun `a code read on the ring's own screen is heard, and with no ring on the screen it does nothing`() {
        val (a, _) = scanning()
        showScanning(a)
        val tryId = ((vm.state.value as ReveilleUiState.Firing).challenge as Challenge.Qr).tryId

        ringActions(vm, null) {}.onQrScanned(tryId, CODE)
        ringActions(vm, null) {}.onQrUnavailable(tryId)
        settle()
        assertTrue(rows(a).isEmpty())

        ringActions(vm, a.id) {}.onQrScanned(tryId, CODE)
        awaitUntil(message = "the code is heard") { vm.state.value is ReveilleUiState.Direction }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(a))
    }

    /** Two rings that ask for a tag, on a phone that can read one, and a view model for that phone with a tag registered. */
    private fun touching(): Pair<OccurrenceRow, OccurrenceRow> = runBlocking {
        check(profiles.saveChain("builtin_steady", ChainSpec(ChainOp.SEQUENCE, listOf(StepSpec(HandshakeMode.NFC, 3, 90, 0)))) == SaveChainResult.Saved)
        check(tokens.registerTag("Bathroom", TAG.hash) is RegisterResult.Saved)
        vm = ReveilleViewModel(repo, clock, phrases, FixedCapability(HandshakeCapability.ALL), tokens)
        suspend fun one(): OccurrenceRow {
            val mission = testMission()
            db.missions().insert(mission)
            val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
            repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
            repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
            return repo.get(o.id)!!
        }
        one() to one()
    }

    private fun showTouching(o: OccurrenceRow) {
        vm.show(o.id)
        awaitUntil(message = "the tag is asked for") { (vm.state.value as? ReveilleUiState.Firing)?.challenge is Challenge.Nfc }
    }

    @Test
    fun `a tag read, or a reader that stops, on the screen of another ring or of no ring does nothing, and on its own screen it is heard`() {
        val (a, b) = touching()
        showTouching(a)
        val oldScreen = ringActions(vm, a.id) {}
        val tryId = ((vm.state.value as ReveilleUiState.Firing).challenge as Challenge.Nfc).tryId
        showTouching(b)
        val newScreen = ringActions(vm, b.id) {}

        oldScreen.onNfcRead(tryId, TAG)
        oldScreen.onNfcUnavailable(tryId)
        ringActions(vm, null) {}.onNfcRead(tryId, TAG)
        ringActions(vm, null) {}.onNfcUnavailable(tryId)
        settle()
        assertEquals(OccurrenceState.FIRING, stateOf(b))
        assertTrue("nothing was written for the ring on screen", rows(b).isEmpty())
        assertTrue("it still asks for the tag", (vm.state.value as ReveilleUiState.Firing).challenge is Challenge.Nfc)

        newScreen.onNfcUnavailable(tryId)
        awaitUntil(message = "its own screen's cannot-touch is heard") { (vm.state.value as? ReveilleUiState.Firing)?.challenge is Challenge.Typing }
        assertEquals(listOf(HandshakeMode.NFC to ChallengeOutcome.FALLBACK), rows(b).map { it.mode to it.outcome })
        assertEquals("the other ring was never touched", OccurrenceState.FIRING, stateOf(a))
        assertTrue(rows(a).isEmpty())
    }

    @Test
    fun `a registered tag read on the ring's own screen acknowledges it`() {
        val (a, _) = touching()
        showTouching(a)
        val tryId = ((vm.state.value as ReveilleUiState.Firing).challenge as Challenge.Nfc).tryId

        ringActions(vm, a.id) {}.onNfcRead(tryId, TAG)
        awaitUntil(message = "the tag is heard") { vm.state.value is ReveilleUiState.Direction }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(a))
    }

    private companion object {
        const val PHRASE = "Starting now"
        const val CODE = "command-qr:0123456789abcdefghijkl"
        val TAG = TagRead.of(byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)) as TagRead.Seen
    }
}
