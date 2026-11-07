package com.command.schedule

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.command.ui.theme.CommandTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Schedule's add button must carry its name on the node a screen reader focuses. DC1 found it unnamed on the emulator: uiautomator marked
 * it NAF (clickable, nothing to read). Material3's ExtendedFloatingActionButton clears the semantics of its text slot, so the merged button
 * node gets no text from it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp-xhdpi")
class ScheduleAddButtonAccessibilityTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `the add button has a name a screen reader can say`() {
        compose.setContent {
            CommandTheme(darkTheme = false) {
                ScheduleScreen(
                    state = ScheduleUiState.Ready(emptyList(), emptyList(), 0, null),
                    onAdd = {},
                    onOpen = {},
                    onOpenReliability = {},
                    onOpenConsents = {},
                    zone = ZoneOffset.UTC,
                    today = LocalDate.of(2026, 11, 2),
                )
            }
        }
        // The merged node is what TalkBack reads when it lands on the button.
        compose.onNodeWithTag(ScheduleTags.ADD).assert(hasText("New mission") or hasContentDescription("New mission"))
    }
}
