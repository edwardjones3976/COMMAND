package com.command.consents

import androidx.compose.ui.test.assertIsDisplayed
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

    private fun item(key: ConsentKey, history: List<ConsentChange>, fix: Fix? = Fix.OPEN_SOUND_SETTINGS) = ConsentItem(
        boundary = ConsentBoundary(key, ConsentOwner.PLATFORM),
        granted = history.firstOrNull()?.granted,
        latest = history.firstOrNull(),
        history = history,
        fix = fix,
    )

    private fun show(state: ConsentCenterUiState, opened: MutableList<Fix> = ArrayList(), back: () -> Unit = {}) {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                ConsentCenterScreen(state = state, onBack = back, onOpenSettings = { opened += it }, zone = ZoneOffset.UTC)
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
}
