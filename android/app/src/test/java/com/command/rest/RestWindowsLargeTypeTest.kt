package com.command.rest

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.command.data.RestWindowLine
import com.command.domain.model.RestKind
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.LocalTime

/** The list and the form at large type on a narrow phone, with text that is really measured: every control can be reached and is as big as a finger. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 2f)
class RestWindowsLargeTypeTest {

    @get:Rule val compose = createComposeRule()

    private var fontScale = 0f
    private val actions = RestWindowsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun show(state: RestWindowsUiState) = compose.setContent {
        fontScale = LocalDensity.current.fontScale
        CommandTheme { RestWindowsScreen(state, actions) }
    }

    private fun reachable(list: String, tag: String) {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `every control of the form can be reached and tapped`() {
        show(RestWindowsUiState.Ready(emptyList(), RestWindowDraft(emptyList(), emptySet(), LocalTime.of(22, 0), LocalTime.of(6, 0), RestKind.SLEEP)))
        assertEquals(2f, fontScale)
        for (day in DayOfWeek.values()) reachable(RestWindowsTags.FORM, RestWindowsTags.day(day))
        reachable(RestWindowsTags.FORM, RestWindowsTags.START)
        reachable(RestWindowsTags.FORM, RestWindowsTags.END)
        for (kind in RestKind.values()) reachable(RestWindowsTags.FORM, RestWindowsTags.kind(kind))
        reachable(RestWindowsTags.FORM, RestWindowsTags.SAVE)
    }

    @Test
    fun `every control of the list can be reached and tapped`() {
        val lines = (1L..3L).map { RestWindowLine(listOf(it), DayOfWeek.values().toSet(), LocalTime.of(22, 0), LocalTime.of(6, 0), RestKind.SLEEP) }
        show(RestWindowsUiState.Ready(lines, null))
        for (id in 1L..3L) {
            reachable(RestWindowsTags.LIST, RestWindowsTags.edit(id))
            reachable(RestWindowsTags.LIST, RestWindowsTags.delete(id))
        }
        reachable(RestWindowsTags.LIST, RestWindowsTags.ADD)
    }
}
