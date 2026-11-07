package com.command

import android.content.Intent
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.command.alarm.AlarmContract
import com.command.data.OccurrenceRepository
import com.command.data.ProfileRepository
import com.command.data.SaveChainResult
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.data.db.ProfileRow
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.dials.StepSpec
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.reveille.ReveilleTags
import com.command.reveille.ReveilleViewModel
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
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
import java.time.Duration
import javax.inject.Inject

/**
 * The ring screen through the real activity, the real view model and a real database: a person who is asked to type a phrase or to tap the
 * numbers does it, and the alarm is acknowledged, written down, and handed on to the direction. This is what the screens and the view model
 * tests cannot show on their own: that they are wired to each other, and to the activity that survives being turned.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h2400dp-xhdpi")
class ReveilleActivityHandshakeTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var profiles: ProfileRepository

    @Before
    fun setUp() {
        hilt.inject()
    }

    private fun ringing(profile: String): OccurrenceRow = runBlocking {
        val mission = testMission(profileId = profile)
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        repo.get(o.id)!!
    }

    /** A profile whose handshake difficulty is [difficulty] (the built-in ones are 1 to 4), and its id. */
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

    /** Every activity a test opens, ended whatever the test did: a failed assertion must not leave one running into the next test. */
    private val opened = ArrayList<ActivityController<ReveilleActivity>>()

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.pause().stop().destroy() } }
    }

    private fun intentFor(o: OccurrenceRow) =
        Intent(ApplicationProvider.getApplicationContext(), ReveilleActivity::class.java).putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, o.id)

    private fun open(o: OccurrenceRow): ActivityController<ReveilleActivity> =
        Robolectric.buildActivity(ReveilleActivity::class.java, intentFor(o)).setup().also { opened += it }

    private fun seenTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun seenText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitForTag(tag: String) = compose.waitUntil(timeoutMillis = 30_000) { seenTag(tag) }
    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 30_000) { seenText(text) }

    private fun phraseOnScreen(): String =
        compose.onNodeWithTag(ReveilleTags.TYPING_PHRASE).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun stateOf(o: OccurrenceRow) = runBlocking { repo.get(o.id)!!.state }
    private fun outcomes(o: OccurrenceRow) = runBlocking { db.challenges().forOccurrence(o.id) }.map { it.outcome }

    /**
     * Waits until every event the screen has sent to its view model has been handled, so that a test of something that must not have happened yet
     * looks after it would have happened, and not before.
     */
    private fun settled(controller: ActivityController<ReveilleActivity>) {
        val model = ViewModelProvider(controller.get())[ReveilleViewModel::class.java]
        var handled = false
        model.afterPendingEvents { handled = true }
        compose.waitUntil(timeoutMillis = 30_000) { handled }
    }

    @Test
    fun `typing the phrase on the screen acknowledges the alarm and shows what to do first, and it is written down`() {
        val o = ringing("builtin_command")
        val controller = open(o)
        waitForTag(ReveilleTags.TYPING_PHRASE)

        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput(phraseOnScreen())
        settled(controller)
        assertEquals("typing the phrase is not sending it: the alarm is still ringing", OccurrenceState.FIRING, stateOf(o))
        assertEquals("and nothing is written down yet", emptyList<ChallengeOutcome>(), outcomes(o))

        compose.onNodeWithTag(ReveilleTags.TYPING_DONE).performClick()

        waitForText("Open the document")
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
        assertEquals(listOf(ChallengeOutcome.SUCCESS), outcomes(o))
    }

    @Test
    fun `a wrong phrase is said to be wrong with the tries left, the next try starts empty, and the right one gets through`() {
        val o = ringing("builtin_command")
        open(o)
        waitForTag(ReveilleTags.TYPING_PHRASE)
        val phrase = phraseOnScreen()

        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput("something else")
        compose.onNodeWithTag(ReveilleTags.TYPING_DONE).performClick()
        waitForText("That is not it. 2 tries left.")
        assertEquals("still ringing", OccurrenceState.FIRING, stateOf(o))

        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput(phrase)
        compose.onNodeWithTag(ReveilleTags.TYPING_DONE).performClick()

        waitForText("Open the document")
        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.SUCCESS), outcomes(o))
    }

    @Test
    fun `the numbers in order lead to the typing, and turning the phone in between loses nothing`() {
        val o = ringing("builtin_reveille_max")
        val controller = open(o)
        waitForTag(ReveilleTags.SEQUENCE_PROGRESS)
        assertTrue("step 1 of 2", seenText("Step 1 of 2"))

        for (n in 1..4) compose.onNodeWithTag(ReveilleTags.sequenceTarget(n)).performClick()
        waitForTag(ReveilleTags.TYPING_PHRASE)
        assertTrue("step 2 of 2", seenText("Step 2 of 2"))
        assertEquals("one of two is not enough", OccurrenceState.FIRING, stateOf(o))

        controller.recreate()
        waitForTag(ReveilleTags.TYPING_PHRASE)
        assertTrue("and the screen that comes back is on the second step, not the first", seenText("Step 2 of 2"))

        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput(phraseOnScreen())
        compose.onNodeWithTag(ReveilleTags.TYPING_DONE).performClick()

        waitForText("Open the document")
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
        assertEquals(listOf(ChallengeOutcome.SUCCESS, ChallengeOutcome.SUCCESS), outcomes(o))
    }

    @Test
    fun `the two-step asks what comes first after the phrase, and one tap on it acknowledges without starting`() {
        val o = ringing(profileOfDifficulty(5))
        open(o)
        waitForTag(ReveilleTags.TYPING_PHRASE)
        assertTrue("step 1 of 2", seenText("Step 1 of 2"))

        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput(phraseOnScreen())
        compose.onNodeWithTag(ReveilleTags.TYPING_DONE).performClick()
        waitForTag(ReveilleTags.TWO_STEP_INDICATE)
        assertTrue("it asks about the mission's own first step", seenText("I am doing: Open the document"))
        compose.onNodeWithTag(ReveilleTags.TWO_STEP_INDICATE).performClick()

        compose.waitUntil(timeoutMillis = 30_000) { stateOf(o) == OccurrenceState.GUIDED_START }
        assertEquals("never straight to started", OccurrenceState.GUIDED_START, stateOf(o))
        assertEquals(listOf(ChallengeOutcome.SUCCESS, ChallengeOutcome.SUCCESS), outcomes(o))
    }

    @Test
    fun `with a screen reader on, three deliberate taps stand in for the hold and are written down as the alternative`() {
        val accessibility = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        shadowOf(accessibility).setTouchExplorationEnabled(true) // a screen reader is on
        val o = ringing("builtin_steady")
        open(o)
        waitForTag(ReveilleTags.ACCESSIBLE)

        repeat(3) { compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).performClick() }

        waitForText("Open the document")
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
        assertEquals(listOf(ChallengeOutcome.ACCESSIBLE_ALT), outcomes(o))
    }

    private fun accessibility() = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(AccessibilityManager::class.java)

    private fun editableText(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** One press on the hold target, on the test clock: it lasts [ms] of frames, and then the real clock takes over again. */
    private fun press(ms: Long) {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(ms)
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { up() }
        compose.mainClock.autoAdvance = true
    }

    // --- the hold, which is what the default profile asks for -----------------------------------------------------------

    @Test
    fun `a hold of the full time on the default profile acknowledges the alarm, and the press is written down`() {
        val o = ringing("builtin_steady") // three seconds
        open(o)
        waitForTag(ReveilleTags.HOLD)

        press(3_300)

        waitForText("Open the document")
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
        assertEquals(listOf(ChallengeOutcome.SUCCESS), outcomes(o))
    }

    @Test
    fun `a press let go early is told to be too short, and is written down as a failed try that leaves the alarm ringing`() {
        val o = ringing("builtin_steady")
        open(o)
        waitForTag(ReveilleTags.HOLD)

        press(1_000)

        waitForText("Released early. Hold the full 3 seconds.")
        assertEquals(listOf(ChallengeOutcome.FAILED), outcomes(o))
        assertEquals(OccurrenceState.FIRING, stateOf(o))
    }

    @Test
    fun `presses let go too early use the tries up, the three taps take the place of the press, and they are written down as the alternative`() {
        val o = ringing("builtin_steady") // four tries
        open(o)
        waitForTag(ReveilleTags.HOLD)
        repeat(4) { n ->
            // The screen for this try is drawn: a press on the one before it is about a try that is over, and counts as nothing. The row of the
            // try before commits before the screen is drawn again, and Compose cannot see the database, so its going idle says nothing about this.
            // The hint is drawn with the new try, and a press takes it away again. It can only be waited for once there has been a press.
            if (n > 0) waitForText("Released early. Hold the full 3 seconds.")
            press(400)
            compose.waitUntil(timeoutMillis = 30_000) { outcomes(o).count { it == ChallengeOutcome.FAILED } == n + 1 }
        }
        waitForTag(ReveilleTags.ACCESSIBLE)
        assertFalse("the press is gone", seenTag(ReveilleTags.HOLD))

        repeat(3) { compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).performClick() }

        waitForText("Open the document")
        assertEquals(ChallengeOutcome.ACCESSIBLE_ALT, outcomes(o).last())
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
    }

    // --- who is offered the three taps from the start -----------------------------------------------------------------------

    @Test
    fun `without a screen reader or a keyboard the press is the only way on offer`() {
        val o = ringing("builtin_steady")
        open(o)
        waitForTag(ReveilleTags.HOLD)
        assertFalse(seenTag(ReveilleTags.ACCESSIBLE))
    }

    @Test
    fun `a screen reader turned on while the alarm rings puts the three taps on the screen at once, and turned off takes them away`() {
        val o = ringing("builtin_steady")
        open(o)
        waitForTag(ReveilleTags.HOLD)
        assertFalse(seenTag(ReveilleTags.ACCESSIBLE))

        shadowOf(accessibility()).setTouchExplorationEnabled(true)
        waitForTag(ReveilleTags.ACCESSIBLE)

        shadowOf(accessibility()).setTouchExplorationEnabled(false)
        compose.waitUntil(timeoutMillis = 30_000) { !seenTag(ReveilleTags.ACCESSIBLE) }
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi-qwerty")
    fun `with a hardware keyboard the three taps are on the screen from the start`() {
        val o = ringing("builtin_steady")
        open(o)
        waitForTag(ReveilleTags.ACCESSIBLE)
        assertTrue("and the press is still there for a hand that can use it", seenTag(ReveilleTags.HOLD))
    }

    @Test
    fun `an action on the hold target, for assistive technology that clicks, shows the three taps and passes nothing`() {
        val o = ringing("builtin_steady")
        open(o)
        waitForTag(ReveilleTags.HOLD)

        compose.onNodeWithTag(ReveilleTags.HOLD).performSemanticsAction(SemanticsActions.OnClick)

        waitForTag(ReveilleTags.ACCESSIBLE)
        assertEquals(OccurrenceState.FIRING, stateOf(o))
        assertTrue("nothing is written until a try ends", outcomes(o).isEmpty())
    }

    // --- the clock of a begun try, with the real clock -----------------------------------------------------------------------

    @Test
    fun `a phrase that was begun and left runs out after ninety seconds and counts as a try, and doing nothing never does`() {
        val o = ringing("builtin_command")
        open(o)
        waitForTag(ReveilleTags.TYPING_FIELD)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        compose.waitForIdle()
        assertTrue("ten minutes of nothing is no try", outcomes(o).isEmpty())

        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput("Up")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(100))
        waitForText("Time ran out. 2 tries left.")
        assertEquals(listOf(ChallengeOutcome.TIMEOUT), outcomes(o))
        assertEquals("the time ran out, the alarm did not", OccurrenceState.FIRING, stateOf(o))
    }

    // --- the screens after the ring, and the intent that announces the same alarm again ------------------------------------

    @Test
    fun `the same alarm announced again while its screen is up leaves what was typed, and the tries, alone`() {
        val o = ringing("builtin_command")
        val controller = open(o)
        waitForTag(ReveilleTags.TYPING_FIELD)
        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput("up and")

        controller.newIntent(intentFor(o))
        compose.waitForIdle()

        assertEquals("up and", editableText(ReveilleTags.TYPING_FIELD))
        assertTrue(outcomes(o).isEmpty())
        assertEquals(OccurrenceState.FIRING, stateOf(o))
    }

    @Test
    fun `Resume on the silenced screen picks the alarm up again`() {
        val o = ringing("builtin_command")
        runBlocking { repo.apply(o.id, OccurrenceCommand.SafetySilence, EventSource.USER) }
        open(o)
        waitForTag(ReveilleTags.RESUME)

        compose.onNodeWithTag(ReveilleTags.RESUME).performClick()

        compose.waitUntil(timeoutMillis = 30_000) { stateOf(o) == OccurrenceState.GUIDED_START }
        waitForText("Open the document")
    }

    @Test
    fun `Skip on the silenced screen lets the alarm go, recorded as a skip`() {
        val o = ringing("builtin_command")
        runBlocking { repo.apply(o.id, OccurrenceCommand.SafetySilence, EventSource.USER) }
        open(o)
        waitForTag(ReveilleTags.SKIP)

        compose.onNodeWithTag(ReveilleTags.SKIP).performClick()

        compose.waitUntil(timeoutMillis = 30_000) { stateOf(o) == OccurrenceState.SKIPPED }
    }

    private fun acknowledged(profile: String): OccurrenceRow {
        val o = ringing(profile)
        runBlocking {
            repo.apply(o.id, OccurrenceCommand.Acknowledge(), EventSource.USER)
            repo.apply(o.id, OccurrenceCommand.StartGuidance, EventSource.USER)
        }
        return o
    }

    @Test
    fun `Started on the direction records that the person started`() {
        val o = acknowledged("builtin_command")
        open(o)
        waitForTag(ReveilleTags.STARTED)

        compose.onNodeWithTag(ReveilleTags.STARTED).performClick()

        compose.waitUntil(timeoutMillis = 30_000) { stateOf(o) == OccurrenceState.STARTED_SELF_REPORTED }
    }

    @Test
    fun `Delay on the direction puts the alarm off`() {
        val o = acknowledged("builtin_command")
        open(o)
        waitForTag(ReveilleTags.DELAY)

        compose.onNodeWithTag(ReveilleTags.DELAY).performClick()

        compose.waitUntil(timeoutMillis = 30_000) { stateOf(o) == OccurrenceState.DELAYED }
    }

    @Test
    fun `Skip on the direction lets the alarm go`() {
        val o = acknowledged("builtin_command")
        open(o)
        waitForTag(ReveilleTags.SKIP)

        compose.onNodeWithTag(ReveilleTags.SKIP).performClick()

        compose.waitUntil(timeoutMillis = 30_000) { stateOf(o) == OccurrenceState.SKIPPED }
    }

    @Test
    fun `a chain of the person's own that allows another way offers it, and taking it leads to the same acknowledgement`() {
        val chain = ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.HOLD, 3, 90, 3), StepSpec(HandshakeMode.TYPING, 3, 90)))
        assertEquals(SaveChainResult.Saved, runBlocking { profiles.saveChain("builtin_steady", chain) })
        val o = ringing("builtin_steady")
        val controller = open(o)
        waitForTag(ReveilleTags.HOLD)
        waitForTag(ReveilleTags.alternative(1))
        assertTrue("or try another way", seenText("Or try another way:"))

        compose.onNodeWithTag(ReveilleTags.alternative(1)).performClick()
        waitForTag(ReveilleTags.TYPING_PHRASE)
        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput(phraseOnScreen())
        compose.onNodeWithTag(ReveilleTags.TYPING_DONE).performClick()

        waitForText("Open the document")
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
        assertEquals(listOf(ChallengeOutcome.SUCCESS), outcomes(o))
        controller.pause().stop().destroy()
    }

    @Test
    fun `Safety silence from the typing screen silences, and nothing is counted as done`() {
        val o = ringing("builtin_command")
        open(o)
        waitForTag(ReveilleTags.TYPING_PHRASE)
        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput("Up")

        compose.onNodeWithTag(ReveilleTags.SILENCE).performClick()

        waitForText("Silenced is not started. Pick it up when you are ready.")
        assertEquals(OccurrenceState.SAFETY_SILENCED, stateOf(o))
        assertEquals(listOf(ChallengeOutcome.SAFETY_SILENCE), outcomes(o))
    }
}
