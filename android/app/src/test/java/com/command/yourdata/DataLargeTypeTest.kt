package com.command.yourdata

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.command.ui.theme.CommandTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Your data screen where large type and a short window leave little room: a phone turned on its side with the type at twice the size.
 * Text is measured for real here (native graphics, with the type size set in the configuration of the window, which a dialog follows); in
 * the default graphics every label is a few pixels wide and nothing could be squeezed out of view. The partial-erase screen matters most:
 * Back is held there, so its two buttons are the only way out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h360dp-xxhdpi", fontScale = 2f)
class DataLargeTypeTest {

    @get:Rule val compose = createComposeRule()

    private fun show(erase: EraseStatus) {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                DataScreen(DataUiState(null, ExportStatus.Idle, erase), {}, {}, {}, {}, {}, {}, {})
            }
        }
    }

    @Test
    fun `when something could not be removed, Close and Try again can be reached on a short window at twice the type size`() {
        show(EraseStatus.Done(leftovers = 2, problems = 1))

        compose.onNodeWithTag(DataTags.CLOSE).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(DataTags.RETRY).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `the question about erasing keeps both answers in view, and what stays can be read to the end`() {
        show(EraseStatus.Confirming)

        compose.onNodeWithTag(DataTags.ERASE_KEEP).assertIsDisplayed()
        compose.onNodeWithTag(DataTags.ERASE_CONFIRM).assertIsDisplayed()
        compose.onNodeWithText("Copies you saved stay where you saved them.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the offer scrolls to the erase button`() {
        show(EraseStatus.Idle)

        // The offer is a lazy list: what is below the window is not even composed until the list is scrolled to it.
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(DataTags.SAVE))
        compose.onNodeWithTag(DataTags.SAVE).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(DataTags.ERASE))
        compose.onNodeWithTag(DataTags.ERASE).assertIsDisplayed()
    }
}
