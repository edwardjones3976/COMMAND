package com.command.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.command.domain.history.HistoryReason
import com.command.ui.theme.CommandTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

// A tall screen, so that nothing needs scrolling to be found.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class OccurrenceDetailScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 2)
    private val at = today.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun show(state: DetailUiState) {
        compose.setContent { CommandTheme(darkTheme = true) { OccurrenceDetailScreen(state = state, onBack = {}, today = today) } }
    }

    private fun ready(story: List<StoryItem> = emptyList(), technical: List<TechnicalItem> = emptyList(), outcome: HistoryReason = HistoryReason.STARTED) =
        DetailUiState.Ready("Write the report", at, ZoneOffset.UTC, outcome, story, technical)

    @Test
    fun `the page names the mission, when it was due and how it ended`() {
        show(ready())
        compose.onNodeWithTag(DetailTags.TITLE).assertIsDisplayed()
        compose.onNodeWithText("Write the report").assertIsDisplayed()
        compose.onNodeWithTag(DetailTags.OUTCOME).assertIsDisplayed()
        compose.onNodeWithText("Started").assertIsDisplayed()
    }

    @Test
    fun `what happened is told line by line, and a late question says how late`() {
        show(
            ready(
                story = listOf(
                    StoryItem(at, StoryKind.RANG),
                    StoryItem(at + 1_000, StoryKind.ANSWERED),
                    StoryItem(at + 2_000, StoryKind.QUESTION_LATE, "95"),
                    StoryItem(at + 3_000, StoryKind.QUESTION_LATE, "1"),
                    StoryItem(at + 4_000, StoryKind.STARTED),
                ),
            ),
        )
        compose.onNodeWithText("The alarm went off.").assertIsDisplayed()
        compose.onNodeWithText("You answered the alarm.").assertIsDisplayed()
        compose.onNodeWithText("The phone held the question back for 95 seconds.").assertIsDisplayed()
        compose.onNodeWithText("The phone held the question back for 1 second.").assertIsDisplayed()
        compose.onNodeWithText("You said you started.").assertIsDisplayed()
    }

    @Test
    fun `an occurrence with no story yet says so`() {
        show(ready(outcome = HistoryReason.OPEN))
        compose.onNodeWithTag(DetailTags.STORY_EMPTY).assertIsDisplayed()
    }

    @Test
    fun `the technical record is hidden until asked for, shown as written, and hidden again`() {
        show(ready(technical = listOf(TechnicalItem(at, "AUDIO_ROUTE", "devices=speaker spoke=true"))))
        compose.onNodeWithText("devices=speaker spoke=true").assertDoesNotExist()
        compose.onNodeWithText("Show technical details").performClick()
        compose.onNodeWithText("devices=speaker spoke=true").assertIsDisplayed()
        compose.onNodeWithText("Hide technical details").assertIsDisplayed()
        compose.onNodeWithText("Hide technical details").performClick()
        compose.onNodeWithText("devices=speaker spoke=true").assertDoesNotExist()
    }

    @Test
    fun `the technical record stays open when the activity is recreated`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CommandTheme(darkTheme = true) {
                OccurrenceDetailScreen(state = ready(technical = listOf(TechnicalItem(at, "AUDIO_ROUTE", "spoke=true"))), onBack = {}, today = today)
            }
        }
        compose.onNodeWithTag(DetailTags.TECHNICAL_TOGGLE).performClick()
        compose.onNodeWithText("spoke=true").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("spoke=true").assertIsDisplayed()
    }

    @Test
    fun `an occurrence that is gone says so`() {
        show(DetailUiState.NotFound)
        compose.onNodeWithTag(DetailTags.NOT_FOUND).assertIsDisplayed()
        compose.onNodeWithText("This one is gone").assertIsDisplayed()
    }
}
