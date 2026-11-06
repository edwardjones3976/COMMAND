package com.command.yourdata

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.command.data.DataOverview
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Your data screen with no view model: what it says in each state, and what each control reports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class DataScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Calls {
        var back = 0
        var save = 0
        var ask = 0
        var keep = 0
        var erase = 0
        var again = 0
        var close = 0
    }

    private val overview = DataOverview(missions = 3, alarms = 120, entries = 2114, records = 4000)

    private fun ready(
        export: ExportStatus = ExportStatus.Idle,
        erase: EraseStatus = EraseStatus.Idle,
        counted: DataOverview? = overview,
    ) = DataUiState(counted, export, erase)

    private fun show(state: DataUiState, calls: Calls = Calls()): Calls {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                DataScreen(
                    state = state,
                    onBack = { calls.back++ },
                    onSave = { calls.save++ },
                    onAskErase = { calls.ask++ },
                    onKeep = { calls.keep++ },
                    onErase = { calls.erase++ },
                    onEraseAgain = { calls.again++ },
                    onClose = { calls.close++ },
                )
            }
        }
        return calls
    }

    // --- what is offered --------------------------------------------------------------------------------------------

    @Test
    fun `it says what there is, and offers a copy and the erase`() {
        show(ready())

        compose.onNodeWithTag(DataTags.COUNTS).assertIsDisplayed()
        compose.onNodeWithText("Right now: 3 missions, 120 alarms with what happened to each and 2114 log entries.").assertIsDisplayed()
        compose.onNodeWithTag(DataTags.SAVE).assertIsDisplayed()
        compose.onNodeWithTag(DataTags.ERASE).assertIsDisplayed()
        compose.onNodeWithText("Everything COMMAND keeps is on this phone. Nothing leaves it unless you turn on a feature that says otherwise.").assertIsDisplayed()
    }

    @Test
    fun `when it could not be counted, it says nothing about numbers and still offers both`() {
        show(ready(counted = null))

        compose.onAllNodes(hasTestTag(DataTags.COUNTS)).let { assertEquals(0, it.fetchSemanticsNodes().size) }
        compose.onNodeWithTag(DataTags.SAVE).assertIsDisplayed()
        compose.onNodeWithTag(DataTags.ERASE).assertIsDisplayed()
    }

    @Test
    fun `one of each is said in the singular`() {
        show(ready(counted = DataOverview(missions = 1, alarms = 1, entries = 1, records = 3)))

        compose.onNodeWithText("Right now: 1 mission, 1 alarm with what happened to it and 1 log entry.").assertIsDisplayed()
    }

    // --- the copy -------------------------------------------------------------------------------------------------

    @Test
    fun `saving a copy is one tap on its button`() {
        val calls = show(ready())

        compose.onNodeWithTag(DataTags.SAVE).performClick()

        assertEquals(1, calls.save)
    }

    @Test
    fun `while a copy is being written there is no second one to start and no erase to ask for`() {
        show(ready(export = ExportStatus.Working))

        compose.onNodeWithTag(DataTags.SAVING).assertIsDisplayed()
        compose.onNodeWithTag(DataTags.SAVE).assertIsNotEnabled()
        compose.onNodeWithTag(DataTags.ERASE).assertIsNotEnabled()
    }

    @Test
    fun `a saved copy says how much was saved`() {
        show(ready(export = ExportStatus.Saved(records = 7, historyLines = 4)))

        compose.onNodeWithTag(DataTags.SAVED).assertIsDisplayed()
        compose.onNodeWithText("Saved: 7 records and 4 lines of history.").assertIsDisplayed()
        compose.onNodeWithTag(DataTags.SAVE).assertIsEnabled()
    }

    @Test
    fun `a copy that failed says nothing was changed and can be tried again`() {
        val calls = show(ready(export = ExportStatus.Failed))

        compose.onNodeWithTag(DataTags.SAVE_FAILED).assertIsDisplayed()
        compose.onNodeWithText("That did not save, and nothing was changed. Try again, or choose another place.").assertIsDisplayed()
        compose.onNodeWithTag(DataTags.SAVE).performClick()
        assertEquals(1, calls.save)
    }

    // --- the erase ------------------------------------------------------------------------------------------------

    @Test
    fun `the erase button only asks, and erases nothing`() {
        val calls = show(ready())

        compose.onNodeWithTag(DataTags.ERASE).performClick()

        assertEquals(1, calls.ask)
        assertEquals(0, calls.erase)
    }

    @Test
    fun `the question says what goes, that it cannot be undone and what stays, and each answer reports itself`() {
        val calls = show(ready(erase = EraseStatus.Confirming))

        compose.onNodeWithTag(DataTags.ERASE_DIALOG).assertIsDisplayed()
        compose.onNodeWithText("Erase everything?").assertIsDisplayed()
        compose.onNodeWithText(
            "This deletes everything COMMAND keeps on this phone, and it cannot be undone. Alarms that are set are taken back and COMMAND closes. Copies you saved stay where you saved them.",
        ).assertIsDisplayed()
        compose.onNodeWithTag(DataTags.ERASE_KEEP).performClick()
        assertEquals("keeping the data is one tap and erases nothing", 1, calls.keep)
        assertEquals(0, calls.erase)
        compose.onNodeWithTag(DataTags.ERASE_CONFIRM).performClick()
        assertEquals(1, calls.erase)
    }

    @Test
    fun `the safe answer comes first, above the one that erases, on a screen wide enough to put them side by side`() {
        show(ready(erase = EraseStatus.Confirming))

        val keep = compose.onNodeWithTag(DataTags.ERASE_KEEP).getUnclippedBoundsInRoot()
        val erase = compose.onNodeWithTag(DataTags.ERASE_CONFIRM).getUnclippedBoundsInRoot()
        assertTrue("keep ends at ${keep.bottom}, erase starts at ${erase.top}", keep.bottom <= erase.top)
        assertTrue("the answers stay large enough to hit", keep.bottom - keep.top >= 56.dp && erase.bottom - erase.top >= 56.dp)
    }

    @Test
    fun `there is no question when none was asked`() {
        show(ready())

        compose.onAllNodes(hasTestTag(DataTags.ERASE_DIALOG)).let { assertEquals(0, it.fetchSemanticsNodes().size) }
    }

    @Test
    fun `while it erases there is only the message, and Back is held`() {
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                DataScreen(ready(erase = EraseStatus.Working), {}, {}, {}, {}, {}, {}, {})
            }
        }

        compose.onNodeWithTag(DataTags.ERASING).assertIsDisplayed()
        compose.onNodeWithText("Erasing…").assertIsDisplayed()
        compose.onAllNodes(hasTestTag(DataTags.ERASE)).let { assertEquals(0, it.fetchSemanticsNodes().size) }
        compose.runOnIdle { assertTrue("Back is held while it erases", dispatcher!!.hasEnabledCallbacks()) }
    }

    @Test
    fun `Back is free when nothing is being erased`() {
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                DataScreen(ready(), {}, {}, {}, {}, {}, {}, {})
            }
        }

        compose.runOnIdle { assertFalse(dispatcher!!.hasEnabledCallbacks()) }
    }

    @Test
    fun `when everything is gone it says so, offers to close now, and closes by itself after a moment`() {
        compose.mainClock.autoAdvance = false
        val calls = show(ready(erase = EraseStatus.Done(leftovers = 0, problems = 0)))

        compose.onNodeWithTag(DataTags.ERASED).assertIsDisplayed()
        compose.onNodeWithText("Everything was deleted. COMMAND is closing.").assertIsDisplayed()
        compose.onAllNodes(hasTestTag(DataTags.RETRY)).let { assertEquals(0, it.fetchSemanticsNodes().size) }
        compose.mainClock.advanceTimeBy(AUTO_CLOSE_MS - 100)
        assertEquals("not yet", 0, calls.close)
        compose.mainClock.advanceTimeBy(300)
        assertEquals("closed by itself", 1, calls.close)
    }

    @Test
    fun `the person can close it now without waiting`() {
        compose.mainClock.autoAdvance = false
        val calls = show(ready(erase = EraseStatus.Done(0, 0)))

        compose.onNodeWithTag(DataTags.CLOSE).performClick()

        assertEquals(1, calls.close)
    }

    @Test
    fun `when something could not be removed it says how many, does not close by itself, and offers to try again`() {
        compose.mainClock.autoAdvance = false
        val calls = show(ready(erase = EraseStatus.Done(leftovers = 2, problems = 1)))

        compose.onNodeWithText("Almost everything was deleted. 3 things could not be removed. Closing COMMAND lets Android clear the rest.").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(AUTO_CLOSE_MS * 3)
        assertEquals("it waits for the person", 0, calls.close)
        compose.onNodeWithTag(DataTags.RETRY).performClick()
        assertEquals("trying again does not ask the question a second time", 1, calls.again)
        assertEquals(0, calls.ask)
        compose.onNodeWithTag(DataTags.CLOSE).performClick()
        assertEquals(1, calls.close)
    }

    @Test
    fun `when only the alarm volume is left it says that, and does not promise that closing the app clears the rest`() {
        compose.mainClock.autoAdvance = false
        val calls = show(ready(erase = EraseStatus.Done(leftovers = 0, problems = 1, volumeLeft = true)))

        compose.onNodeWithText("Everything was deleted, but the alarm volume could not be put back to what it was. You can set it in your phone's sound settings.").assertIsDisplayed()
        compose.onAllNodes(hasText("lets Android clear the rest", substring = true)).assertCountEquals(0)
        compose.mainClock.advanceTimeBy(AUTO_CLOSE_MS * 3)
        assertEquals("it waits for the person", 0, calls.close)
        compose.onNodeWithTag(DataTags.RETRY).assertIsDisplayed()
    }

    @Test
    fun `when the volume is left along with something else it says how many, and says the volume as well`() {
        show(ready(erase = EraseStatus.Done(leftovers = 2, problems = 1, volumeLeft = true)))

        compose.onNodeWithText("Almost everything was deleted. 3 things could not be removed. Closing COMMAND lets Android clear the rest.").assertIsDisplayed()
        compose.onNodeWithText("The alarm volume could not be put back to what it was. You can set it in your phone's sound settings.").assertIsDisplayed()
    }

    @Test
    fun `an erase that failed says so and the button is there again`() {
        val calls = show(ready(erase = EraseStatus.Failed))

        compose.onNodeWithTag(DataTags.ERASE_FAILED).assertIsDisplayed()
        compose.onNodeWithText("Erasing did not finish. Try again.").assertIsDisplayed()
        compose.onNodeWithTag(DataTags.ERASE).performClick()
        assertEquals(1, calls.ask)
    }

    @Test
    fun `the person can leave the screen when nothing is happening`() {
        val calls = show(ready())

        compose.onNode(hasContentDescription("Back")).performClick()

        assertEquals(1, calls.back)
    }

    @Test
    fun `the way back is not offered while it erases`() {
        show(ready(erase = EraseStatus.Working))
        compose.onNode(hasContentDescription("Back")).assertIsNotEnabled()
    }

    @Test
    fun `nor once it is erased, and Back is held there too, so that the closing is not cancelled`() {
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                DataScreen(ready(erase = EraseStatus.Done(leftovers = 0, problems = 0)), {}, {}, {}, {}, {}, {}, {})
            }
        }

        compose.onNode(hasContentDescription("Back")).assertIsNotEnabled()
        compose.runOnIdle { assertTrue("Back is held once it is erased", dispatcher!!.hasEnabledCallbacks()) }
    }

    @Test
    fun `before the data has been counted both are already offered, with no number`() {
        show(ready(counted = null))

        compose.onNodeWithTag(DataTags.SAVE).assertIsEnabled()
        compose.onNodeWithTag(DataTags.ERASE).assertIsEnabled()
        compose.onAllNodes(hasTestTag(DataTags.COUNTS)).let { assertEquals(0, it.fetchSemanticsNodes().size) }
    }
}
