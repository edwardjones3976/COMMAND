package com.command

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.command.data.AppSettings
import com.command.data.ProfileRepository
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.DialEffects
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.StepSpec
import com.command.handshake.HandshakeTags
import com.command.schedule.ScheduleTags
import com.command.settings.SettingsTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The way from Settings to the handshakes and into one profile's editor, through the real activity, and that what is saved there is stored. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class HandshakeNavigationTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var settings: AppSettings

    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking { settings.markOnboardingDone() }
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
    }

    private fun open(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)).setup().also { controller = it }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun textExists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.waitUntil(timeoutMillis = 30_000) { exists(tag) }
    private fun waitGone(tag: String) = compose.waitUntil(timeoutMillis = 30_000) { !exists(tag) }
    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 30_000) { textExists(text) }

    private fun tap(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    private fun back(controller: ActivityController<MainActivity>) = controller.get().onBackPressedDispatcher.onBackPressed()

    private fun toTheHandshakes() {
        tap(ScheduleTags.LINK_SETTINGS)
        tap(SettingsTags.LINK_HANDSHAKES)
        waitFor(HandshakeTags.LIST)
    }

    private fun toTheEditorOf(profile: String) {
        toTheHandshakes()
        tap(HandshakeTags.edit(profile))
        waitFor(HandshakeTags.EDITOR)
    }

    @Test
    fun `from Settings to the handshakes, into one profile, a step added and saved, and back to the list that now says it is its own`() {
        val controller = open()

        toTheHandshakes()
        compose.onNodeWithTag(HandshakeTags.profile("builtin_steady")).assertIsDisplayed() // Steady, the standard handshake
        waitForText("Hold the button for 3 seconds")

        tap(HandshakeTags.edit("builtin_steady"))
        waitFor(HandshakeTags.EDITOR)
        tap(HandshakeTags.ADD)
        waitFor(HandshakeTags.step(1))
        tap(HandshakeTags.OP_SEQUENCE)
        compose.waitUntil(timeoutMillis = 30_000) { runCatching { compose.onNodeWithTag(HandshakeTags.SAVE).assertIsEnabled() }.isSuccess }
        tap(HandshakeTags.SAVE)
        waitFor(HandshakeTags.SAVED)
        compose.onNodeWithTag(HandshakeTags.SAVED).assertTextEquals("Saved. The next alarm asks for this.") // Steady is the profile the alarms are fired with

        val saved = runBlocking { profiles.get("builtin_steady")!!.own!! }
        assertEquals(ChainOp.SEQUENCE, saved.op)
        assertEquals(listOf(HandshakeMode.HOLD, HandshakeMode.CONFIRM_SEQUENCE), saved.steps.map { it.mode })
        assertNull("the other profiles are as they were", runBlocking { profiles.get("builtin_command")!!.own })

        back(controller) // nothing is unsaved, so nothing is asked
        waitFor(HandshakeTags.LIST)
        waitForText("One after another: Hold the button for 3 seconds, Tap the numbers in order")
        waitForText("A handshake of your own")

        back(controller)
        waitFor(SettingsTags.LINK_HANDSHAKES)
    }

    @Test
    fun `another profile's editor shows that profile's steps, a change of how they combine alone is saved to it and to no other, and the list says which profiles alarms use`() {
        val controller = open()
        toTheHandshakes()
        compose.onNodeWithTag(HandshakeTags.inUse("builtin_steady")).assertTextEquals("Your alarms use this profile.")
        compose.onNodeWithTag(HandshakeTags.inUse("builtin_reveille_max")).assertTextEquals("No alarm uses this profile yet.")

        tap(HandshakeTags.edit("builtin_reveille_max"))
        waitFor(HandshakeTags.EDITOR)
        waitFor(HandshakeTags.step(1))
        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.CONFIRM_SEQUENCE)).assertIsSelected()
        compose.onNodeWithTag(HandshakeTags.mode(1, HandshakeMode.TYPING)).assertIsSelected()
        compose.onNodeWithTag(HandshakeTags.OP_AND).assertIsSelected()
        compose.onNodeWithTag(HandshakeTags.IN_USE).assertTextEquals("No alarm uses this profile yet.")

        tap(HandshakeTags.OP_SEQUENCE)
        compose.waitUntil(timeoutMillis = 30_000) { runCatching { compose.onNodeWithTag(HandshakeTags.SAVE).assertIsEnabled() }.isSuccess }
        tap(HandshakeTags.SAVE)
        waitFor(HandshakeTags.SAVED)

        compose.onNodeWithTag(HandshakeTags.SAVED).assertTextEquals("Saved. No alarm uses this profile yet, so no alarm asks for this yet.")
        val own = runBlocking { profiles.get("builtin_reveille_max")!!.own!! }
        assertEquals(ChainOp.SEQUENCE, own.op)
        assertEquals("the steps are as they were", DialEffects.defaultChain(4).steps, own.steps)
        assertNull("and no other profile was touched", runBlocking { profiles.get("builtin_steady")!!.own })
        back(controller)
        waitForText("One after another: Tap the numbers in order, Type a phrase")
    }

    @Test
    fun `leaving the editor with changes that were not saved asks first, keeping on editing stays, and leaving without saving stores nothing`() {
        val controller = open()
        toTheEditorOf("builtin_steady")
        tap(HandshakeTags.ADD)
        waitFor(HandshakeTags.step(1))

        back(controller)
        waitFor(HandshakeTags.LEAVE_DIALOG)
        tap(HandshakeTags.LEAVE_KEEP)
        waitGone(HandshakeTags.LEAVE_DIALOG)
        assertTrue("still in the editor, with the step that was added", exists(HandshakeTags.step(1)))

        back(controller)
        waitFor(HandshakeTags.LEAVE_DIALOG)
        tap(HandshakeTags.LEAVE_CONFIRM)

        waitFor(HandshakeTags.LIST)
        assertNull("nothing was stored", runBlocking { profiles.get("builtin_steady")!!.own })
    }

    // --- every control of the editor reaches the view model it belongs to, which the chain that is stored shows ---------------------------

    /**
     * Opens the editor of Steady (a hold), adds a second step (the numbers), does [change], saves, and returns the chain that was stored. The
     * second step is there because moving a step and how the steps go together show only with two.
     */
    private fun savedAfter(change: () -> Unit): ChainSpec {
        open()
        toTheEditorOf("builtin_steady")
        tap(HandshakeTags.ADD)
        waitFor(HandshakeTags.step(1))
        change()
        compose.waitUntil(timeoutMillis = 30_000) { runCatching { compose.onNodeWithTag(HandshakeTags.SAVE).assertIsEnabled() }.isSuccess }
        tap(HandshakeTags.SAVE)
        waitFor(HandshakeTags.SAVED)
        return runBlocking { profiles.get("builtin_steady")!!.own!! }
    }

    private val standardHold = DialEffects.defaultChain(2).steps.single()

    @Test
    fun `the stepper of tries reaches the view model, and changes the tries of that step and nothing else`() {
        val own = savedAfter { tap(HandshakeTags.triesMore(0)) }

        assertEquals(standardHold.maxRetries + 1, own.steps[0].maxRetries)
        assertEquals(standardHold.timeoutSec, own.steps[0].timeoutSec)
        assertEquals(standardHold.holdSeconds, own.steps[0].holdSeconds)
    }

    @Test
    fun `the stepper of the time reaches the view model, and changes the time of that step and nothing else`() {
        val own = savedAfter { repeat(2) { tap(HandshakeTags.timeMore(0)) } }

        assertEquals(standardHold.timeoutSec + 20, own.steps[0].timeoutSec)
        assertEquals(standardHold.maxRetries, own.steps[0].maxRetries)
        assertEquals(standardHold.holdSeconds, own.steps[0].holdSeconds)
    }

    @Test
    fun `the stepper of the press reaches the view model, and changes the press of that step and nothing else`() {
        val own = savedAfter { repeat(3) { tap(HandshakeTags.holdMore(0)) } }

        assertEquals(standardHold.holdSeconds + 3, own.steps[0].holdSeconds)
        assertEquals(standardHold.maxRetries, own.steps[0].maxRetries)
        assertEquals(standardHold.timeoutSec, own.steps[0].timeoutSec)
    }

    @Test
    fun `the arrow up and the arrow down reach the view model, and move the step the right way`() {
        val up = savedAfter { tap(HandshakeTags.up(1)) }
        assertEquals("the numbers went first", listOf(HandshakeMode.CONFIRM_SEQUENCE, HandshakeMode.HOLD), up.steps.map { it.mode })
    }

    @Test
    fun `the arrow down moves the first step after the second`() {
        val down = savedAfter { tap(HandshakeTags.down(0)) }
        assertEquals(listOf(HandshakeMode.CONFIRM_SEQUENCE, HandshakeMode.HOLD), down.steps.map { it.mode })
    }

    @Test
    fun `the button to remove reaches the view model, and takes away the step it is on`() {
        val own = savedAfter {
            tap(HandshakeTags.ADD) // a third step, only to be removed again
            waitFor(HandshakeTags.step(2))
            tap(HandshakeTags.remove(1)) // the numbers: the third step takes its place
            waitGone(HandshakeTags.step(2))
        }

        assertEquals("the hold stays, and what was added last (typing) has taken the place of the numbers that were removed", listOf(HandshakeMode.HOLD, HandshakeMode.TYPING), own.steps.map { it.mode })
    }

    @Test
    fun `the choice of a way reaches the view model, and changes the way of that step`() {
        val own = savedAfter { tap(HandshakeTags.mode(1, HandshakeMode.TYPING)) }

        assertEquals(listOf(HandshakeMode.HOLD, HandshakeMode.TYPING), own.steps.map { it.mode })
        assertEquals("a way that is not a hold has no press", 0, own.steps[1].holdSeconds)
    }

    @Test
    fun `the choice of how the steps go together reaches the view model`() {
        val own = savedAfter { tap(HandshakeTags.OP_AND) }

        assertEquals(ChainOp.AND, own.op)
    }

    @Test
    fun `going back to the standard handshake asks first, keeping the saved one deletes nothing, and the answer that goes back deletes it`() {
        runBlocking { profiles.saveChain("builtin_steady", ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.HOLD, 4, 90, 3), StepSpec(HandshakeMode.TYPING, 3, 90)))) }
        open()
        toTheEditorOf("builtin_steady")
        waitFor(HandshakeTags.step(1))

        tap(HandshakeTags.STANDARD)
        waitFor(HandshakeTags.STANDARD_DIALOG)
        tap(HandshakeTags.STANDARD_KEEP)
        waitGone(HandshakeTags.STANDARD_DIALOG)
        assertNotNull("what was saved is still there", runBlocking { profiles.get("builtin_steady")!!.own })

        tap(HandshakeTags.STANDARD)
        waitFor(HandshakeTags.STANDARD_DIALOG)
        tap(HandshakeTags.STANDARD_CONFIRM)

        waitGone(HandshakeTags.STANDARD)
        waitFor(HandshakeTags.RESTORED) // the button goes as soon as the editor shows Loading, before anything is back
        assertNull(runBlocking { profiles.get("builtin_steady")!!.own })
        assertTrue("and the editor shows the standard chain: one step", !exists(HandshakeTags.step(1)))
        compose.onNodeWithTag(HandshakeTags.mode(0, HandshakeMode.HOLD)).assertIsSelected()
        waitFor(HandshakeTags.RESTORED)
        compose.onNodeWithTag(HandshakeTags.RESTORED).assertTextEquals("Back on the standard handshake for difficulty 2.")
        compose.onNodeWithTag(HandshakeTags.IN_USE).assertTextEquals("Your alarms use this profile.")
    }

    @Test
    fun `after going back to the standard handshake a profile no alarm uses is still said to be one that no alarm uses`() {
        runBlocking { profiles.saveChain("builtin_command", ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.HOLD, 4, 90, 3), StepSpec(HandshakeMode.TYPING, 3, 90)))) }
        open()
        toTheEditorOf("builtin_command")
        waitFor(HandshakeTags.step(1))
        compose.onNodeWithTag(HandshakeTags.IN_USE).assertTextEquals("No alarm uses this profile yet.")

        tap(HandshakeTags.STANDARD)
        waitFor(HandshakeTags.STANDARD_DIALOG)
        tap(HandshakeTags.STANDARD_CONFIRM)

        waitFor(HandshakeTags.RESTORED)
        assertNull(runBlocking { profiles.get("builtin_command")!!.own })
        compose.onNodeWithTag(HandshakeTags.IN_USE).assertTextEquals("No alarm uses this profile yet.")
    }

    @Test
    fun `the arrow of the list of profiles goes back to Settings`() {
        open()
        toTheHandshakes()
        waitGone(SettingsTags.LINK_HANDSHAKES) // Settings has gone with its transition: its own arrow is not a second "Back" on screen

        compose.onNodeWithContentDescription("Back").performClick()

        waitFor(SettingsTags.LINK_HANDSHAKES)
        assertTrue("and the list is gone", !exists(HandshakeTags.LIST))
    }

    @Test
    fun `Back in the question about leaving keeps the editing, and a tap outside it does too, and nothing is stored`() {
        val controller = open()
        toTheEditorOf("builtin_steady")
        tap(HandshakeTags.ADD)
        waitFor(HandshakeTags.step(1))

        back(controller)
        waitFor(HandshakeTags.LEAVE_DIALOG)
        compose.runOnUiThread { org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed() }
        waitGone(HandshakeTags.LEAVE_DIALOG)
        assertTrue("still in the editor, with the step that was added", exists(HandshakeTags.step(1)))

        back(controller)
        waitFor(HandshakeTags.LEAVE_DIALOG)
        compose.runOnUiThread {
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val outside = android.view.MotionEvent.obtain(0L, 0L, action, -200f, -200f, 0)
                dialog.onTouchEvent(outside) // a press and a release outside the window: the dialog dismisses on the release
                outside.recycle()
            }
        }
        waitGone(HandshakeTags.LEAVE_DIALOG)
        assertTrue("and still", exists(HandshakeTags.step(1)))
        assertNull("nothing was stored", runBlocking { profiles.get("builtin_steady")!!.own })
    }
}
