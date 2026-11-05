package com.command.onboarding

import android.Manifest
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.command.data.AppSettings
import com.command.data.MissionRepository
import com.command.domain.reliability.Precondition
import com.command.testing.android.FakeReliabilityMonitor
import com.command.testing.android.awaitUntil
import com.command.ui.theme.CommandTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The grant and the refusal of the notification request, end to end through the first-run permissions screen on Android 13 and
 * later: the button asks Android, the answer comes back through the activity-result machinery, the screen reads the phone
 * again, and a refusal turns the same button into one that opens Android's notification settings (issue #6).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33, 36], qualifiers = "w411dp-h2400dp-xhdpi")
class PermissionsRouteTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    @Inject lateinit var monitor: FakeReliabilityMonitor
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var missions: MissionRepository

    /** Stands in for Android's permission dialog: it records what was asked, and the test decides the answer. */
    private class Registry : ActivityResultRegistry() {
        val asked = ArrayList<Pair<Int, Any?>>()

        override fun <I : Any?, O : Any?> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            asked += requestCode to input
        }
    }

    private val registry = Registry()
    private var continued = 0

    @Before
    fun setUp() {
        hilt.inject()
    }

    private fun show() {
        val vm = OnboardingViewModel(monitor, settings, missions)
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                CommandTheme(darkTheme = true) { PermissionsRoute(onContinue = { continued++ }, viewModel = vm) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount(OnboardingTags.PERMISSIONS_LIST) > 0 }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun `allowing notifications asks Android, and when the answer is yes the screen shows everything allowed`() {
        monitor.set(Precondition.NOTIFICATIONS, "denied")
        show()
        compose.onNodeWithText("Allow notifications").assertIsDisplayed()

        compose.onNodeWithText("Allow notifications").performClick()
        assertEquals("one request, for exactly this permission", listOf(Manifest.permission.POST_NOTIFICATIONS), registry.asked.map { it.second })

        monitor.set(Precondition.NOTIFICATIONS, "granted") // what Android reports once the person has tapped Allow
        registry.dispatchResult(registry.asked.single().first, true)

        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount(OnboardingTags.PERMISSIONS_ALL_SET) > 0 }
        compose.onNodeWithTag(OnboardingTags.PERMISSIONS_ALL_SET).assertIsDisplayed()
        assertTrue("the answer is remembered", runBlocking { settings.notificationsAsked() })
    }

    @Test
    fun `refusing leaves notifications off and turns the button into one that opens Android's settings`() {
        monitor.set(Precondition.NOTIFICATIONS, "denied")
        show()

        compose.onNodeWithText("Allow notifications").performClick()
        registry.dispatchResult(registry.asked.single().first, false)

        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Open notification settings")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Open notification settings").assertIsDisplayed()
        compose.onNodeWithTag(OnboardingTags.PERMISSIONS_ALL_SET).assertDoesNotExist()

        compose.onNodeWithText("Open notification settings").performClick()
        assertEquals("the second tap asks nothing: Android would not show the dialog again", 1, registry.asked.size)
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertNotNull(opened)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(compose.activity.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `a refusal does not stop the person from carrying on, and carrying on remembers the first run is over`() {
        monitor.set(Precondition.NOTIFICATIONS, "denied")
        show()

        compose.onNodeWithTag(OnboardingTags.PERMISSIONS_CONTINUE).performClick()
        awaitUntil(message = "carried on") { continued == 1 }
        assertTrue(runBlocking { settings.onboardingDone.first() })
    }
}
