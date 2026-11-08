package com.command

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.nfc.NfcAdapter
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.command.alarm.AlarmContract
import com.command.data.ConsentStore
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
import com.command.domain.handshake.TagRead
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.ConsentKey
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.handshake.FakeNfcStatus
import com.command.handshake.NfcAvailability
import com.command.handshake.NfcStatus
import com.command.handshake.NfcStatusModule
import com.command.reveille.ReveilleTags
import com.command.reveille.ReveilleViewModel
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.testMission
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNfcAdapter
import javax.inject.Inject

/**
 * The NFC handshake through the real activity, view model, capability reading and database, with the phone's NFC as [FakeNfcStatus] says and
 * Robolectric's adapter under the real reader: reader mode is on only while a tag is asked for and the ring screen is resumed, a phone that
 * cannot read a tag asks a phrase from the start, and nothing of it touches the alarm's volume.
 */
@HiltAndroidTest
@UninstallModules(NfcStatusModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h2400dp-xhdpi")
class ReveilleActivityNfcTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @BindValue @JvmField val nfc: NfcStatus = FakeNfcStatus()
    private val fakeNfc get() = nfc as FakeNfcStatus

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var consents: ConsentStore
    @Inject lateinit var tokens: TokenRepository

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<ActivityController<ReveilleActivity>>()
    private lateinit var adapter: NfcAdapter

    @Before
    fun setUp() {
        hilt.inject()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        ShadowNfcAdapter.setNfcHardwareExists(true)
        adapter = NfcAdapter.getDefaultAdapter(app)
        shadow().setEnabled(true)
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.pause().stop().destroy() } }
    }

    private fun shadow(): ShadowNfcAdapter = Shadow.extract(adapter)

    /** A ring whose chain is [chain], with the tag consent given and one tag registered. */
    private fun ringing(chain: ChainSpec = ChainSpec(ChainOp.SEQUENCE, listOf(StepSpec(HandshakeMode.NFC, 3, 90, 0)))): OccurrenceRow = runBlocking {
        consents.grant(ConsentKey.NFC)
        val seen = TagRead.of(byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)) as TagRead.Seen
        check(tokens.registerTag("Bathroom", seen.hash) is RegisterResult.Saved)
        check(profiles.saveChain("builtin_steady", chain) == SaveChainResult.Saved)
        val mission = testMission()
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        repo.get(o.id)!!
    }

    private fun open(o: OccurrenceRow) = Robolectric.buildActivity(
        ReveilleActivity::class.java,
        Intent(app, ReveilleActivity::class.java).putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, o.id),
    ).setup().also { opened += it }

    private fun rows(o: OccurrenceRow) = runBlocking { db.challenges().forOccurrence(o.id) }.map { it.mode to it.outcome }
    private fun seenTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 30_000) {
        seenTag(ReveilleTags.SILENCE)
        condition()
    }

    private fun settled(controller: ActivityController<ReveilleActivity>) {
        val model = ViewModelProvider(controller.get())[ReveilleViewModel::class.java]
        var handled = false
        model.afterPendingEvents { handled = true }
        waitFor { handled }
    }

    @Test
    fun nfcChallengeEnablesReaderModeOnlyWhileResumed() {
        val o = ringing()
        val controller = open(o)
        waitFor { seenTag(ReveilleTags.NFC_AREA) }
        settled(controller)
        assertTrue("a tag is asked for and the screen is in front: the reader is on", shadow().isInReaderMode)
        assertTrue("the cannot-touch control is there from the start", seenTag(ReveilleTags.NFC_CANNOT_TOUCH))

        controller.pause()
        assertFalse("the screen is not in front: the reader is off", shadow().isInReaderMode)
        controller.resume()
        waitFor { shadow().isInReaderMode }
        assertTrue(rows(o).isEmpty())
    }

    @Test
    fun nonNfcChallengeDoesNotEnableReaderMode() {
        // A press on screen, with the tag offered beside it: nothing reads a tag until the person turns to it. (A code to scan would open the
        // camera, which cannot run here; the press shows the same thing: only the tag's own challenge turns the reader on.)
        val o = ringing(ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.HOLD, 3, 90, 3), StepSpec(HandshakeMode.NFC, 3, 90, 0))))
        val controller = open(o)
        waitFor { seenTag(ReveilleTags.HOLD) }
        settled(controller)

        assertFalse(shadow().isInReaderMode)
        assertTrue("the tag is offered by its name", seenTag(ReveilleTags.alternative(1)))
    }

    @Test
    fun noAdapterNfcRingShowsTypingWithOneFallback() {
        fakeNfc.now = NfcAvailability.NONE
        val o = ringing()
        val controller = open(o)
        waitFor { seenTag(ReveilleTags.TYPING_FIELD) }
        settled(controller)

        assertEquals(listOf(HandshakeMode.NFC to ChallengeOutcome.FALLBACK), rows(o))
        assertFalse(seenTag(ReveilleTags.NFC_AREA))
        assertFalse(shadow().isInReaderMode)
    }

    @Test
    fun secureLockedRingShowsTyping() {
        fakeNfc.now = NfcAvailability.SECURE_LOCKED
        val o = ringing()
        val controller = open(o)
        waitFor { seenTag(ReveilleTags.TYPING_FIELD) }
        settled(controller)

        assertEquals(listOf(HandshakeMode.NFC to ChallengeOutcome.FALLBACK), rows(o))
        assertFalse(shadow().isInReaderMode)
    }

    @Test
    fun phoneLockedWhileAwayDropsToTypingWhenItComesBack() {
        val o = ringing()
        val controller = open(o)
        waitFor { seenTag(ReveilleTags.NFC_AREA) }
        settled(controller)

        controller.pause().stop()
        fakeNfc.now = NfcAvailability.SECURE_LOCKED
        controller.start().resume()
        waitFor { seenTag(ReveilleTags.TYPING_FIELD) }
        settled(controller)

        assertEquals(listOf(HandshakeMode.NFC to ChallengeOutcome.FALLBACK), rows(o))
        assertFalse("the reader is let go with the tag's challenge", shadow().isInReaderMode)

        controller.pause().stop()
        fakeNfc.now = NfcAvailability.READY
        controller.start().resume()
        settled(controller)
        assertTrue("unlocked again, the step stays typing", seenTag(ReveilleTags.TYPING_FIELD))
        assertEquals(1, rows(o).size)
    }

    @Test
    fun readerModeLeavesAlarmVolumeAlone() {
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 6, 0)
        val before = audio.getStreamVolume(AudioManager.STREAM_ALARM)

        val o = ringing()
        val controller = open(o)
        waitFor { seenTag(ReveilleTags.NFC_AREA) }
        settled(controller)
        assertTrue(shadow().isInReaderMode)
        controller.pause()
        controller.resume()
        waitFor { shadow().isInReaderMode }

        assertEquals(before, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(6, before)
    }
}
