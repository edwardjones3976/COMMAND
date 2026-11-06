package com.command.followup

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.command.domain.dials.SkipReasonCode
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The closing message at twice the type size in a short window, measured for real: however long the words are, they and Close can be reached. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h400dp-xxhdpi", fontScale = 2f)
class FollowUpLargeTypeTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `a long acknowledgement and Close can both be reached`() {
        val words = "Heard you. This one is skipped for now, and it stays recorded as skipped. It is your call, and it can wait for the next time it comes round."
        var closed = 0
        compose.setContent {
            CommandTheme(darkTheme = true) {
                FollowUpScreen(
                    state = FollowUpUiState.Finished("Write the whole of the quarterly report", FollowUpOutcome.SKIPPED, words),
                    friction = null,
                    notice = null,
                    reasons = SkipReasonCode.values().toList(),
                    actions = FollowUpActions(
                        onStarted = {}, onWorking = {}, onDone = {}, onNeedReset = {}, onDelay = {}, onSkip = {}, onRestart = {},
                        onRescheduleTo = {}, onNotNow = {}, onFrictionConfirm = {}, onFrictionDismiss = {},
                    ),
                    onClose = { closed++ },
                )
            }
        }
        compose.onNodeWithText(words).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.CLOSE).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, closed)
    }
}
