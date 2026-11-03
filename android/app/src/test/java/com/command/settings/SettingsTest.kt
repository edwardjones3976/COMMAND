package com.command.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.command.data.AppSettings
import com.command.domain.voice.RoutePolicy
import com.command.testing.android.awaitUntil
import com.command.ui.theme.CommandTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Where the spoken line is heard: chosen on a screen, written the moment it is chosen. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class SettingsViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var settings: AppSettings

    @Before
    fun setUp() {
        hilt.inject()
    }

    private fun ready(vm: SettingsViewModel): SettingsUiState.Ready? = vm.state.value as? SettingsUiState.Ready

    @Test
    fun `the line is heard on the speaker and a headset until the person says otherwise`() {
        val vm = SettingsViewModel(settings)
        awaitUntil(message = "ready") { ready(vm) != null }
        assertEquals(RoutePolicy.SPEAKER_AND_HEADSET, ready(vm)!!.routePolicy)
    }

    @Test
    fun `choosing headset only is shown at once and is written down`() {
        val vm = SettingsViewModel(settings)
        awaitUntil(message = "ready") { ready(vm) != null }

        vm.chooseRoutePolicy(RoutePolicy.HEADSET_ONLY)
        assertEquals("what was chosen is what is shown", RoutePolicy.HEADSET_ONLY, ready(vm)!!.routePolicy)
        awaitUntil(message = "written") { runBlocking { settings.routePolicy() } == RoutePolicy.HEADSET_ONLY }

        val reopened = SettingsViewModel(settings)
        awaitUntil(message = "ready again") { ready(reopened) != null }
        assertEquals("and it is still chosen when the screen is opened again", RoutePolicy.HEADSET_ONLY, ready(reopened)!!.routePolicy)
    }

    @Test
    fun `choosing what is already chosen writes nothing`() {
        val vm = SettingsViewModel(settings)
        awaitUntil(message = "ready") { ready(vm) != null }
        vm.chooseRoutePolicy(RoutePolicy.SPEAKER_AND_HEADSET)
        assertEquals(RoutePolicy.SPEAKER_AND_HEADSET, ready(vm)!!.routePolicy)
    }
}

// A tall screen, so that nothing needs scrolling to be found.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Taps {
        val routes = ArrayList<RoutePolicy>()
        var history = 0
        var reliability = 0
        var consents = 0
        var back = 0
    }

    private fun show(policy: RoutePolicy = RoutePolicy.SPEAKER_AND_HEADSET, taps: Taps = Taps()): Taps {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                SettingsScreen(
                    state = SettingsUiState.Ready(policy),
                    onBack = { taps.back++ },
                    onChooseRoute = { taps.routes += it },
                    onOpenHistory = { taps.history++ },
                    onOpenReliability = { taps.reliability++ },
                    onOpenConsents = { taps.consents++ },
                    version = "4.0",
                )
            }
        }
        return taps
    }

    @Test
    fun `the two routes are offered with what each means, and the chosen one is selected`() {
        show(RoutePolicy.HEADSET_ONLY)
        compose.onNodeWithText("Where the spoken line is heard").assertIsDisplayed()
        compose.onNodeWithText("An alarm always plays its tone and vibrates.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.ROUTE_HEADSET).assertIsSelected()
        compose.onNodeWithTag(SettingsTags.ROUTE_SPEAKER).assertIsNotSelected()
        compose.onNodeWithText("Without one, the tone and the vibration still ring.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `choosing a route reports it`() {
        val taps = show(RoutePolicy.SPEAKER_AND_HEADSET)
        compose.onNodeWithTag(SettingsTags.ROUTE_HEADSET).performClick()
        assertEquals(listOf(RoutePolicy.HEADSET_ONLY), taps.routes)
    }

    @Test
    fun `history, reliability and permissions and privacy are one tap away`() {
        val taps = show()
        compose.onNodeWithTag(SettingsTags.LINK_HISTORY).performClick()
        compose.onNodeWithTag(SettingsTags.LINK_RELIABILITY).performClick()
        compose.onNodeWithTag(SettingsTags.LINK_CONSENTS).performClick()
        assertEquals(1, taps.history)
        assertEquals(1, taps.reliability)
        assertEquals(1, taps.consents)
    }

    @Test
    fun `the page says which version this is, and that everything stays on the phone`() {
        show()
        compose.onNodeWithText("COMMAND, version 4.0").assertIsDisplayed()
        compose.onNodeWithText("Everything stays on this phone unless a feature you turn on says otherwise.").assertIsDisplayed()
    }
}
