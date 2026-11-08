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
import com.command.domain.handshake.TagRead
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
 * The NFC handshake through the view model, against a real in-memory database and a phone that can or cannot read a tag ([FixedCapability]):
 * what a touch does, what is not a try, what is written down, and what happens when the phone stops being able to read. Only the fingerprint
 * of a tag ([TagRead]) ever reaches the view model.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class ReveilleNfcViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var tokens: TokenRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock

    private val phrases = FixedPhrases(listOf(PHRASE))
    private val phone = FixedCapability(HandshakeCapability(setOf(HandshakeMode.NFC)))
    private lateinit var vm: ReveilleViewModel

    @Before
    fun setUp() {
        hilt.inject()
        vm = model()
        runBlocking { check(tokens.registerTag("Bathroom", MINE.hash) is RegisterResult.Saved) }
    }

    private fun model(capability: FixedCapability = phone) = ReveilleViewModel(repo, clock, phrases, capability, tokens)

    private fun step(mode: HandshakeMode, tries: Int = 3) = StepSpec(mode, tries, 90, 0)

    /** A ringing occurrence whose profile asks for [chain]. */
    private fun ringingWith(chain: ChainSpec = ChainSpec(ChainOp.SEQUENCE, listOf(step(HandshakeMode.NFC)))): OccurrenceRow = runBlocking {
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
    private fun nfc(model: ReveilleViewModel = vm) = firing(model).challenge as Challenge.Nfc

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

    private fun awaitTry(tryNo: Int) = awaitChallenge("try $tryNo") { it is Challenge.Nfc && it.tryId.tryNo == tryNo }

    @Test
    fun `a phone that can read a tag is asked for one, with every try ahead and nothing written`() {
        val o = ringingWith()
        show(o)

        assertEquals(Challenge.Nfc(TryId(0, 1), 3, mistaken = false), firing().challenge)
        assertTrue(rows(o).isEmpty())
    }

    @Test
    fun registeredTagPasses() {
        val o = ringingWith()
        show(o)

        vm.nfcRead(nfc().tryId, MINE)

        awaitUntil(message = "direction") { vm.state.value is ReveilleUiState.Direction }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
        val row = rows(o).single()
        assertEquals(HandshakeMode.NFC, row.mode)
        assertEquals(ChallengeOutcome.SUCCESS, row.outcome)
        assertEquals(1, row.attemptNo)
    }

    @Test
    fun wrongTagFailsOnce() {
        val o = ringingWith()
        show(o)

        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(2)

        assertEquals(Challenge.Nfc(TryId(0, 2), 2, mistaken = true), firing().challenge)
        assertEquals(listOf(HandshakeMode.NFC to ChallengeOutcome.FAILED), rows(o).map { it.mode to it.outcome })
    }

    @Test
    fun sameWrongTagInsideWindowIgnored() {
        val o = ringingWith()
        show(o)
        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(2)

        repeat(10) { vm.nfcRead(nfc().tryId, OTHER) }
        clock.advance(2_999)
        vm.nfcRead(nfc().tryId, OTHER)
        settle()

        assertEquals("one fumble, one try", 1, rows(o).size)
        assertEquals(2, nfc().triesLeft)
    }

    @Test
    fun sameWrongTagAfterWindowCounts() {
        val o = ringingWith()
        show(o)
        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(2)

        clock.advance(3_000)
        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(3)

        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.FAILED), rows(o).map { it.outcome })
    }

    @Test
    fun eachSightingRestartsWindow() {
        val o = ringingWith()
        show(o)
        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(2)

        clock.advance(2_900)
        vm.nfcRead(nfc().tryId, OTHER)
        clock.advance(2_900)
        vm.nfcRead(nfc().tryId, OTHER)
        settle()
        assertEquals("each sighting started the wait again", 1, rows(o).size)

        clock.advance(3_100)
        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(3)
        assertEquals(2, rows(o).size)
    }

    @Test
    fun twoDifferentWrongTagsCountEach() {
        val o = ringingWith()
        show(o)

        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(2)
        vm.nfcRead(nfc().tryId, ANOTHER)
        awaitTry(3)

        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.FAILED), rows(o).map { it.outcome })
        assertEquals(1, nfc().triesLeft)
    }

    @Test
    fun wrongThenRightPasses() {
        val o = ringingWith()
        show(o)

        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(2)
        vm.nfcRead(nfc().tryId, MINE)

        awaitUntil(message = "direction") { vm.state.value is ReveilleUiState.Direction }
        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.SUCCESS), rows(o).map { it.outcome })
    }

    @Test
    fun noIdNotATry() {
        val o = ringingWith()
        show(o)

        repeat(5) { vm.nfcRead(nfc().tryId, TagRead.NoId) }
        vm.nfcRead(nfc().tryId, TagRead.of(null))
        settle()

        assertTrue(rows(o).isEmpty())
        assertEquals(Challenge.Nfc(TryId(0, 1), 3, mistaken = false), firing().challenge)
    }

    @Test
    fun changingNotATry() {
        val o = ringingWith()
        show(o)

        repeat(5) { vm.nfcRead(nfc().tryId, TagRead.Changing) }
        vm.nfcRead(nfc().tryId, TagRead.of(byteArrayOf(0x08, 0x01, 0x02, 0x03)))
        settle()

        assertTrue(rows(o).isEmpty())
        assertEquals(Challenge.Nfc(TryId(0, 1), 3, mistaken = false), firing().challenge)
    }

    @Test
    fun staleTryIdIgnored() {
        val other = ringingWith()
        val o = ringingWith()
        show(o)
        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(2)

        vm.nfcRead(TryId(0, 1), MINE) // a late read from the try before
        vm.nfcRead(TryId(0, 3), MINE) // a try that is not on screen
        vm.nfcRead(other.id, nfc().tryId, MINE) // the screen of another ring
        settle()

        assertEquals(1, rows(o).size)
        assertTrue(rows(other).isEmpty())
        assertEquals(OccurrenceState.FIRING, stateOf(o))
    }

    @Test
    fun readWhileQrShownIgnored() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.QR), step(HandshakeMode.NFC))))
        val both = model(FixedCapability(HandshakeCapability.ALL))
        show(o, both)
        val qr = firing(both).challenge as Challenge.Qr

        both.nfcRead(qr.tryId, MINE)
        settle(both)

        assertTrue(rows(o).isEmpty())
        assertEquals(OccurrenceState.FIRING, stateOf(o))
        assertTrue(firing(both).challenge is Challenge.Qr)
    }

    @Test
    fun nfcUnavailableReplacesWithOneFallbackSameTriesLeft() {
        val o = ringingWith()
        show(o)
        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(2)

        vm.nfcUnavailable(nfc().tryId) // the cannot-touch button, or the adapter turning off
        awaitChallenge("typing") { it is Challenge.Typing }
        val typing = firing().challenge as Challenge.Typing
        assertEquals("the fallback has its own tries, and none was used", 3, typing.triesLeft)
        assertFalse("a new way starts clean", typing.mistaken)
        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.FALLBACK), rows(o).map { it.outcome })
        assertEquals("the row names the way that was left, numbered with its tries", HandshakeMode.NFC to 1, rows(o).last().let { it.mode to it.attemptNo })

        vm.nfcUnavailable(TryId(0, 2)) // a second report about the same try
        settle()
        assertEquals(2, rows(o).size)
    }

    @Test
    fun qrUnavailableWhileNfcShownChangesNothing() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.NFC), step(HandshakeMode.QR))))
        val both = model(FixedCapability(HandshakeCapability.ALL))
        show(o, both)
        val at = nfc(both).tryId

        both.qrUnavailable(at) // a camera error that arrives late, after the person turned to the tag
        settle(both)

        assertTrue("nothing was written", rows(o).isEmpty())
        assertEquals(Challenge.Nfc(at, 3, mistaken = false), firing(both).challenge)
    }

    @Test
    fun nfcUnavailableWhileQrShownChangesNothing() {
        val o = ringingWith(ChainSpec(ChainOp.OR, listOf(step(HandshakeMode.QR), step(HandshakeMode.NFC))))
        val both = model(FixedCapability(HandshakeCapability.ALL))
        show(o, both)
        val qr = firing(both).challenge as Challenge.Qr

        both.nfcUnavailable(qr.tryId)
        settle(both)

        assertTrue(rows(o).isEmpty())
        assertEquals(qr, firing(both).challenge)
    }

    @Test
    fun staleTryUnavailableChangesNothing() {
        val o = ringingWith()
        show(o)
        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(2)

        vm.nfcUnavailable(TryId(0, 1))
        vm.qrUnavailable(TryId(0, 1))
        settle()

        assertEquals(1, rows(o).size)
        assertTrue(firing().challenge is Challenge.Nfc)
    }

    @Test
    fun recheckSecureLockedDropsToTyping() {
        val o = ringingWith()
        show(o)
        assertTrue(firing().challenge is Challenge.Nfc)

        phone.now = HandshakeCapability.NONE // the phone locked with Secure NFC on, while the ring screen was away
        vm.recheckCapability()
        awaitChallenge("typing") { it is Challenge.Typing }
        assertEquals(listOf(HandshakeMode.NFC to ChallengeOutcome.FALLBACK), rows(o).map { it.mode to it.outcome })

        phone.now = HandshakeCapability(setOf(HandshakeMode.NFC)) // unlocked again
        vm.recheckCapability()
        settle()
        assertTrue("a step that fell to typing stays typing", firing().challenge is Challenge.Typing)
        assertEquals(1, rows(o).size)
    }

    @Test
    fun `a phone that cannot read a tag is asked to type from the start, and the record says so once`() {
        val o = ringingWith()
        val cannot = model(FixedCapability(HandshakeCapability.NONE))
        show(o, cannot)

        assertTrue(firing(cannot).challenge is Challenge.Typing)
        assertEquals(listOf(Triple(HandshakeMode.NFC, ChallengeOutcome.FALLBACK, 0)), rows(o).map { Triple(it.mode, it.outcome, it.attemptNo) })
    }

    @Test
    fun wrongMapClearedOnShow() {
        val a = ringingWith()
        val b = ringingWith()
        show(a)
        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(2)

        show(b)
        vm.nfcRead(nfc().tryId, OTHER) // inside the window, but on another ring
        awaitTry(2)

        assertEquals(1, rows(a).size)
        assertEquals(listOf(ChallengeOutcome.FAILED), rows(b).map { it.outcome })
    }

    @Test
    fun noTimerWhileNfcShown() {
        val o = ringingWith()
        show(o)
        vm.attemptTimer = {} // any clock that were started would run out at once
        vm.nfcRead(nfc().tryId, TagRead.NoId)
        vm.nfcRead(nfc().tryId, OTHER)
        awaitTry(2)

        clock.advance(60 * 60 * 1000L)
        settle()
        settle()

        assertEquals("only the wrong tag counted", listOf(ChallengeOutcome.FAILED), rows(o).map { it.outcome })
        assertEquals(2, nfc().triesLeft)
        assertEquals(OccurrenceState.FIRING, stateOf(o))
    }

    private companion object {
        const val PHRASE = "Starting now"
        val MINE = TagRead.of(byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)) as TagRead.Seen
        val OTHER = TagRead.of(byteArrayOf(0x04, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22)) as TagRead.Seen
        val ANOTHER = TagRead.of(byteArrayOf(0x04, 0x01, 0x02, 0x03)) as TagRead.Seen
    }
}
