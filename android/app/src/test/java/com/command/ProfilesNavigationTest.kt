package com.command

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.command.data.AppSettings
import com.command.handshake.HandshakeTags
import com.command.rest.RestWindowsTags
import com.command.profiles.DialsTags
import com.command.profiles.ProfilesTags
import com.command.profiles.RulesTags
import com.command.schedule.ScheduleTags
import com.command.settings.SettingsTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The way from Settings to the profiles and from there to each screen they lead to, through the real activity. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class ProfilesNavigationTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var settings: AppSettings

    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking { settings.markOnboardingDone() }
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.waitUntil(timeoutMillis = 30_000) { exists(tag) }

    private fun tap(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    @Test
    fun `from Settings to the profiles, then to dials, a handshake and the rules, and back each time`() {
        val c = Robolectric.buildActivity(MainActivity::class.java, Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)).setup().also { controller = it }
        fun back() = c.get().onBackPressedDispatcher.onBackPressed()

        tap(ScheduleTags.LINK_SETTINGS)
        tap(SettingsTags.LINK_PROFILES)
        waitFor(ProfilesTags.LIST)

        tap(ProfilesTags.dials("builtin_steady"))
        waitFor(DialsTags.NAME)
        back()

        tap(ProfilesTags.handshake("builtin_steady"))
        waitFor(HandshakeTags.EDITOR)
        back()

        tap(ProfilesTags.RULES)
        waitFor(RulesTags.LIST)
        tap(RulesTags.ADD)
        waitFor(RulesTags.FORM)
        back() // leaves the form, not the screen
        waitFor(RulesTags.LIST)
        back()

        waitFor(ProfilesTags.LIST)
    }

    @Test
    fun `from Settings to the rest windows and their form, and back each time`() {
        val c = Robolectric.buildActivity(MainActivity::class.java, Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)).setup().also { controller = it }
        fun back() = c.get().onBackPressedDispatcher.onBackPressed()

        tap(ScheduleTags.LINK_SETTINGS)
        tap(SettingsTags.LINK_REST_WINDOWS)
        waitFor(RestWindowsTags.LIST)
        tap(RestWindowsTags.ADD)
        waitFor(RestWindowsTags.FORM)
        back() // leaves the form, not the screen
        waitFor(RestWindowsTags.LIST)
        back()
        waitFor(SettingsTags.LINK_REST_WINDOWS)
    }
}
