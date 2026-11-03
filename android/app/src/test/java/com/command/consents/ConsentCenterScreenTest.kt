package com.command.consents

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.command.domain.model.ConsentBoundaries
import com.command.domain.model.ConsentBoundary
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentOwner
import com.command.domain.model.ConsentSource
import com.command.domain.reliability.Fix
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class ConsentCenterScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun at(day: Int, hour: Int): Long = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun item(key: ConsentKey, history: List<ConsentChange>, fix: Fix? = Fix.OPEN_SOUND_SETTINGS): ConsentItem {
        val boundary: ConsentBoundary = ConsentBoundaries.all.single { it.key == key }
        val app = boundary.owner == ConsentOwner.APP
        return ConsentItem(
            boundary = boundary,
            // As the view model has it: what the app keeps is off until it is turned on, what Android keeps is unknown until it is read.
            granted = history.firstOrNull()?.granted ?: if (app) false else null,
            latest = history.firstOrNull(),
            history = history,
            fix = if (app) null else fix,
        )
    }

    private fun show(
        state: ConsentCenterUiState,
        opened: MutableList<Fix> = ArrayList(),
        back: () -> Unit = {},
        onToggle: (ConsentKey, Boolean) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                ConsentCenterScreen(state = state, onBack = back, onOpenSettings = { opened += it }, onToggle = onToggle, zone = ZoneOffset.UTC)
            }
        }
    }

    private fun everyConsent(history: List<ConsentChange> = emptyList()) =
        ConsentCenterUiState.Ready(ConsentBoundaries.all.map { item(it.key, history) })

    @Test
    fun `every consent in the list has words, and the page says nothing else is switched on`() {
        show(everyConsent())
        compose.onNodeWithText("Permissions and privacy").assertIsDisplayed()
        compose.onNodeWithText("Nothing else is switched on", substring = true).assertIsDisplayed()
        for (boundary in ConsentBoundaries.all) compose.onNodeWithTag(ConsentTags.item(boundary.key)).assertIsDisplayed()
        compose.onNodeWithText("Lets an alarm appear over the lock screen", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Lets an alarm ring at the minute you set", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Lets a ringing alarm take over a locked screen.").assertIsDisplayed()
        compose.onNodeWithText("may turn the alarm volume up", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the footer says that optional features are not in this version`() {
        show(everyConsent())
        compose.onNodeWithTag(ConsentTags.FOOTER).assertIsDisplayed()
        compose.onNodeWithText("are not in this version", substring = true).assertIsDisplayed()
    }

    @Test
    fun `before anything has been recorded the state is not claimed`() {
        show(everyConsent())
        compose.onNodeWithTag(ConsentTags.state(ConsentKey.NOTIFICATIONS)).assertIsDisplayed()
        compose.onNodeWithTag(ConsentTags.state(ConsentKey.NOTIFICATIONS)).assertTextEquals("Not checked yet")
    }

    @Test
    fun `a consent says whether it is allowed, and when and by whom it last changed`() {
        val state = ConsentCenterUiState.Ready(
            listOf(
                item(ConsentKey.NOTIFICATIONS, listOf(ConsentChange(at(2, 9), granted = false, source = ConsentSource.SYSTEM), ConsentChange(at(1, 9), granted = true, source = ConsentSource.SYSTEM))),
                item(ConsentKey.EXACT_ALARM, listOf(ConsentChange(at(1, 9), granted = true, source = ConsentSource.USER))),
            ),
        )
        show(state)
        compose.onNodeWithTag(ConsentTags.state(ConsentKey.NOTIFICATIONS)).assertTextEquals("Not allowed")
        compose.onNodeWithTag(ConsentTags.state(ConsentKey.EXACT_ALARM)).assertTextEquals("Allowed")
        compose.onNodeWithText("in Android's settings", substring = true).assertIsDisplayed()
        compose.onNodeWithText("by you", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the history is hidden until it is asked for, then lists every change, and can be hidden again`() {
        val history = listOf(ConsentChange(at(2, 9), granted = false, source = ConsentSource.SYSTEM), ConsentChange(at(1, 9), granted = true, source = ConsentSource.SYSTEM))
        show(ConsentCenterUiState.Ready(listOf(item(ConsentKey.NOTIFICATIONS, history))))

        compose.onNodeWithTag(ConsentTags.history(ConsentKey.NOTIFICATIONS)).assertDoesNotExist()
        compose.onNodeWithTag(ConsentTags.historyToggle(ConsentKey.NOTIFICATIONS)).performClick()
        compose.onNodeWithTag(ConsentTags.history(ConsentKey.NOTIFICATIONS)).assertIsDisplayed()
        compose.onNodeWithText("Turned off", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Hide history").assertIsDisplayed()

        compose.onNodeWithTag(ConsentTags.historyToggle(ConsentKey.NOTIFICATIONS)).performClick()
        compose.onNodeWithTag(ConsentTags.history(ConsentKey.NOTIFICATIONS)).assertDoesNotExist()
    }

    @Test
    fun `a consent with no history has no history button`() {
        show(everyConsent())
        compose.onNodeWithTag(ConsentTags.historyToggle(ConsentKey.NOTIFICATIONS)).assertDoesNotExist()
    }

    @Test
    fun `the button opens the Android screen where the permission is changed`() {
        val opened = ArrayList<Fix>()
        show(ConsentCenterUiState.Ready(listOf(item(ConsentKey.EXACT_ALARM, emptyList(), Fix.OPEN_EXACT_ALARM_SETTINGS))), opened)
        compose.onNodeWithTag(ConsentTags.settings(ConsentKey.EXACT_ALARM)).performClick()
        assertEquals(listOf(Fix.OPEN_EXACT_ALARM_SETTINGS), opened)
    }

    @Test
    fun `a consent that has no screen to go to has no button`() {
        show(ConsentCenterUiState.Ready(listOf(item(ConsentKey.EXACT_ALARM, emptyList(), fix = null))))
        compose.onNodeWithTag(ConsentTags.settings(ConsentKey.EXACT_ALARM)).assertDoesNotExist()
    }

    @Test
    fun `the back arrow goes back`() {
        var back = 0
        show(everyConsent(), back = { back++ })
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    @Test
    fun `the volume consent says what it allows and what it never does`() {
        show(everyConsent())
        compose.onNodeWithText("While an alarm rings, COMMAND may turn the alarm volume up", substring = true).assertIsDisplayed()
        compose.onNodeWithText("never changes the ring or media volume", substring = true).assertIsDisplayed()
        compose.onNodeWithText("only around an alarm", substring = true).assertIsDisplayed()
        compose.onNodeWithText("This is yours to change here", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a consent the app keeps is a switch that starts off, and flipping it reports the choice to turn it on`() {
        val flips = ArrayList<Pair<ConsentKey, Boolean>>()
        show(ConsentCenterUiState.Ready(listOf(item(ConsentKey.VOLUME_CONTROL, emptyList()))), onToggle = { key, on -> flips += key to on })

        compose.onNodeWithTag(ConsentTags.state(ConsentKey.VOLUME_CONTROL)).assertTextEquals("Not allowed")
        compose.onNodeWithTag(ConsentTags.switch(ConsentKey.VOLUME_CONTROL)).assertIsOff()
        compose.onNodeWithText("Off").assertIsDisplayed()

        compose.onNodeWithTag(ConsentTags.switch(ConsentKey.VOLUME_CONTROL)).performClick()
        assertEquals(listOf(ConsentKey.VOLUME_CONTROL to true), flips)
    }

    @Test
    fun `once the consent is on the switch says so, and flipping it reports the choice to turn it off`() {
        val flips = ArrayList<Pair<ConsentKey, Boolean>>()
        val on = listOf(ConsentChange(at(2, 9), granted = true, source = ConsentSource.USER))
        show(ConsentCenterUiState.Ready(listOf(item(ConsentKey.VOLUME_CONTROL, on))), onToggle = { key, now -> flips += key to now })

        compose.onNodeWithTag(ConsentTags.state(ConsentKey.VOLUME_CONTROL)).assertTextEquals("Allowed")
        compose.onNodeWithTag(ConsentTags.switch(ConsentKey.VOLUME_CONTROL)).assertIsOn()
        compose.onNodeWithText("On").assertIsDisplayed()

        compose.onNodeWithTag(ConsentTags.switch(ConsentKey.VOLUME_CONTROL)).performClick()
        assertEquals(listOf(ConsentKey.VOLUME_CONTROL to false), flips)
    }

    @Test
    fun `a consent Android keeps has a button to its screen and no switch, and one the app keeps is the other way round`() {
        show(ConsentCenterUiState.Ready(listOf(item(ConsentKey.EXACT_ALARM, emptyList(), Fix.OPEN_EXACT_ALARM_SETTINGS), item(ConsentKey.VOLUME_CONTROL, emptyList()))))

        compose.onNodeWithTag(ConsentTags.settings(ConsentKey.EXACT_ALARM)).assertIsDisplayed()
        compose.onNodeWithTag(ConsentTags.switch(ConsentKey.EXACT_ALARM)).assertDoesNotExist()
        compose.onNodeWithTag(ConsentTags.switch(ConsentKey.VOLUME_CONTROL)).assertIsDisplayed()
        compose.onNodeWithTag(ConsentTags.settings(ConsentKey.VOLUME_CONTROL)).assertDoesNotExist()
    }
}
