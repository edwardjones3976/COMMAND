package com.command.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.command.domain.reliability.Fix
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.PreconditionSnapshot
import com.command.domain.reliability.Reliability
import com.command.testing.android.FakeReliabilityMonitor
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp-xhdpi")
class OnboardingScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun permissions(canAsk: Boolean = true, vararg changed: Pair<Precondition, String>): PermissionsUiState.Ready {
        val report = Reliability.assess(PreconditionSnapshot(FakeReliabilityMonitor.healthy().values + mapOf(*changed)))
        val items = listOf(Precondition.NOTIFICATIONS, Precondition.EXACT_ALARMS, Precondition.FULL_SCREEN_INTENT).mapNotNull { report.of(it) }
        return PermissionsUiState.Ready(items, canAsk)
    }

    @Test
    fun `the welcome says what COMMAND does and that the person stays in charge, and carries on`() {
        var continued = 0
        compose.setContent { CommandTheme(darkTheme = true) { WelcomeScreen(onContinue = { continued++ }) } }

        compose.onNodeWithText("Welcome to COMMAND").assertIsDisplayed()
        compose.onNodeWithText("You stay in charge", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Everything stays on this phone", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(OnboardingTags.WELCOME_CONTINUE).performClick()
        assertEquals(1, continued)
    }

    @Test
    fun `while the permissions are being read there is a spinner and Continue is still there`() {
        compose.setContent { CommandTheme(darkTheme = true) { PermissionsScreen(PermissionsUiState.Loading, onFix = {}, onContinue = {}) } }
        compose.onNodeWithTag(OnboardingTags.PERMISSIONS_LIST).assertDoesNotExist()
        compose.onNodeWithTag(OnboardingTags.PERMISSIONS_CONTINUE).assertIsEnabled()
    }

    @Test
    fun `a permission that is off says what that costs and has a button that asks for it`() {
        val fixes = ArrayList<Fix>()
        compose.setContent {
            CommandTheme(darkTheme = true) {
                PermissionsScreen(permissions(true, Precondition.NOTIFICATIONS to "denied"), onFix = { fixes += it }, onContinue = {})
            }
        }
        compose.onNodeWithText("Without them an alarm cannot appear over the lock screen", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Allow notifications").performClick()
        assertEquals(listOf(Fix.ASK_NOTIFICATIONS), fixes)
        compose.onNodeWithTag(OnboardingTags.PERMISSIONS_ALL_SET).assertDoesNotExist()
    }

    @Test
    fun `once the request has been answered the same row opens settings instead`() {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                PermissionsScreen(permissions(false, Precondition.NOTIFICATIONS to "denied"), onFix = {}, onContinue = {})
            }
        }
        compose.onNodeWithText("Open notification settings").assertIsDisplayed()
    }

    @Test
    fun `with everything allowed it says so, lists each permission as allowed and offers nothing to fix`() {
        compose.setContent { CommandTheme(darkTheme = true) { PermissionsScreen(permissions(), onFix = {}, onContinue = {}) } }
        compose.onNodeWithTag(OnboardingTags.PERMISSIONS_ALL_SET).assertIsDisplayed()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.onNodeWithText("Exact alarms").assertIsDisplayed()
        compose.onNodeWithText("Full-screen alarms").assertIsDisplayed()
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
    }

    @Test
    fun `Continue is always there, even when everything was refused`() {
        var continued = 0
        compose.setContent {
            CommandTheme(darkTheme = true) {
                PermissionsScreen(
                    permissions(true, Precondition.NOTIFICATIONS to "denied", Precondition.EXACT_ALARMS to "denied", Precondition.FULL_SCREEN_INTENT to "denied"),
                    onFix = {}, onContinue = { continued++ },
                )
            }
        }
        compose.onNodeWithTag(OnboardingTags.PERMISSIONS_CONTINUE).assertIsEnabled().performClick()
        assertEquals(1, continued)
    }

    @Test
    fun `with no missions it invites a first one, or lets the person leave it for later`() {
        var created = 0
        var skipped = 0
        compose.setContent { CommandTheme(darkTheme = true) { FirstMissionScreen(hasMissions = false, onCreate = { created++ }, onSkip = { skipped++ }) } }

        compose.onNodeWithText("Set your first mission").assertIsDisplayed()
        compose.onNodeWithTag(OnboardingTags.FIRST_CREATE).performClick()
        compose.onNodeWithTag(OnboardingTags.FIRST_SKIP).performClick()
        assertEquals(1, created)
        assertEquals(1, skipped)
    }

    @Test
    fun `missions that came across from the previous version are acknowledged instead of asking for a first one`() {
        var done = 0
        compose.setContent { CommandTheme(darkTheme = true) { FirstMissionScreen(hasMissions = true, onCreate = {}, onSkip = { done++ }) } }

        compose.onNodeWithText("You already have missions").assertIsDisplayed()
        compose.onNodeWithTag(OnboardingTags.FIRST_CREATE).assertDoesNotExist()
        compose.onNodeWithTag(OnboardingTags.FIRST_DONE).performClick()
        assertEquals(1, done)
    }

    @Test
    fun `until it is known whether there are missions neither question is asked`() {
        compose.setContent { CommandTheme(darkTheme = true) { FirstMissionScreen(hasMissions = null, onCreate = {}, onSkip = {}) } }
        compose.onNodeWithTag(OnboardingTags.FIRST_CREATE).assertDoesNotExist()
        compose.onNodeWithTag(OnboardingTags.FIRST_DONE).assertDoesNotExist()
    }
}
