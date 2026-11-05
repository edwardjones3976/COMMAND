package com.command.calendar

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.command.data.db.CalendarSourceRow
import com.command.domain.dials.Flexibility
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class CalendarSourcesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun source(selected: Boolean = false) = CalendarSourceRow(7, "me@example.test", "Work", selected, null, Flexibility.FLEXIBLE, null)

    private fun show(
        state: CalendarSourcesUiState,
        onAllow: () -> Unit = {},
        onSelect: (Long, Boolean) -> Unit = { _, _ -> },
        onDefaults: (CalendarSourceRow, Flexibility, String?) -> Unit = { _, _, _ -> },
    ) = compose.setContent { CalendarSourcesScreen(state, {}, onAllow, onSelect, onDefaults) }

    @Test
    fun `without the consent it asks and lists nothing`() {
        var asked = 0
        show(CalendarSourcesUiState.Ready(false, false, listOf(source()), emptyList()), onAllow = { asked++ })
        compose.onNodeWithTag(CalendarTags.source(7)).assertDoesNotExist()
        compose.onNodeWithTag(CalendarTags.PERMISSION).assertDoesNotExist()
        compose.onNodeWithTag(CalendarTags.ALLOW).performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `the consent without the permission says why and asks again`() {
        show(CalendarSourcesUiState.Ready(true, false, listOf(source()), emptyList()))
        compose.onNodeWithTag(CalendarTags.PERMISSION).assertIsDisplayed()
        compose.onNodeWithTag(CalendarTags.ALLOW).assertIsDisplayed()
    }

    @Test
    fun `a calendar starts off and shows its defaults only once chosen`() {
        val picked = ArrayList<Pair<Long, Boolean>>()
        show(CalendarSourcesUiState.Ready(true, true, listOf(source()), emptyList()), onSelect = { id, on -> picked += id to on })
        compose.onNodeWithTag(CalendarTags.select(7)).assertIsOff()
        compose.onNodeWithTag(CalendarTags.fixed(7)).assertDoesNotExist()
        compose.onNodeWithTag(CalendarTags.select(7)).performClick()
        assertEquals(listOf(7L to true), picked)
    }

    @Test
    fun `a chosen calendar's default flexibility and profile can be set`() {
        val set = ArrayList<Pair<Flexibility, String?>>()
        show(
            CalendarSourcesUiState.Ready(true, true, listOf(source(selected = true)), listOf(ProfileChoice("p1", "Steady"))),
            onDefaults = { _, flexibility, profile -> set += flexibility to profile },
        )
        compose.onNodeWithTag(CalendarTags.fixed(7)).performClick()
        compose.onNodeWithTag(CalendarTags.profile(7, "p1")).performClick()
        assertEquals(listOf(Flexibility.RIGID to null, Flexibility.FLEXIBLE to "p1"), set)
    }

    @Test
    fun `no calendars on the phone is said, nothing is made up`() {
        show(CalendarSourcesUiState.Ready(true, true, emptyList(), emptyList()))
        compose.onNodeWithTag(CalendarTags.EMPTY).assertIsDisplayed()
    }
}
