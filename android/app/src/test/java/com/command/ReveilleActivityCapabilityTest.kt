package com.command

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.ConsentKey
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.reveille.ReveilleTags
import com.command.reveille.ReveilleViewModel
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
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
import javax.inject.Inject

/**
 * The ring screen reads the phone again when it comes back to the front: a camera consent withdrawn while it was away turns a step that scans
 * into typing, through the real activity, view model, phone reading and database. The step that scans is not the one on screen, so the camera
 * (which cannot run here) is never drawn.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h2400dp-xhdpi")
class ReveilleActivityCapabilityTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var consents: ConsentStore
    @Inject lateinit var tokens: TokenRepository

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<ActivityController<ReveilleActivity>>()

    @Before
    fun setUp() {
        hilt.inject()
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.pause().stop().destroy() } }
    }

    /** A ring whose chain is the press or a code, on a phone that can scan. */
    private fun ringing(): OccurrenceRow = runBlocking {
        consents.grant(ConsentKey.CAMERA_QR)
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        check(tokens.register(HandshakeMode.QR, "Kitchen", "command-qr:one") is RegisterResult.Saved)
        check(profiles.saveChain("builtin_steady", ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.HOLD, 3, 90, 3), StepSpec(HandshakeMode.QR, 3, 90, 0)))) == SaveChainResult.Saved)
        val mission = testMission()
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        repo.get(o.id)!!
    }

    private fun rows(o: OccurrenceRow) = runBlocking { db.challenges().forOccurrence(o.id) }.map { it.mode to it.outcome }
    private fun seenTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun seenText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits until [condition] holds. Reading the screen on each look lets the main thread run what the view model posted to it (the waiting
     * itself does not, and a condition that only reads a variable would wait for ever).
     */
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 30_000) {
        seenTag(ReveilleTags.SILENCE)
        condition()
    }

    /** Waits until every event the screen has sent to its view model has been handled. */
    private fun settled(controller: ActivityController<ReveilleActivity>) {
        val model = ViewModelProvider(controller.get())[ReveilleViewModel::class.java]
        var handled = false
        model.afterPendingEvents { handled = true }
        waitFor { handled }
    }

    @Test
    fun `a camera consent withdrawn while the ring screen was away turns the step that scans into typing when it comes back, once`() {
        val o = ringing()
        val controller = Robolectric.buildActivity(
            ReveilleActivity::class.java,
            Intent(app, ReveilleActivity::class.java).putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, o.id),
        ).setup().also { opened += it }
        waitFor { seenTag(ReveilleTags.HOLD) }
        settled(controller)
        assertTrue("the phone can scan: the code is offered by its name", seenText("Scan a code"))
        assertEquals("and there is nothing to write", emptyList<Pair<HandshakeMode, ChallengeOutcome>>(), rows(o))

        controller.pause().stop()
        runBlocking { consents.revoke(ConsentKey.CAMERA_QR) }
        controller.start().resume()
        waitFor { rows(o).isNotEmpty() }
        settled(controller)

        assertEquals(listOf(HandshakeMode.QR to ChallengeOutcome.FALLBACK), rows(o))
        waitFor { seenText("Type a phrase") }
        assertFalse("the other way is typing now", seenText("Scan a code"))
        controller.pause().stop().start().resume()
        settled(controller)
        assertEquals("and said once", 1, rows(o).size)
    }
}
