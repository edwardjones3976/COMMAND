package com.command

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.command.data.AppSettings
import com.command.data.OccurrenceRepository
import com.command.data.db.CommandDb
import com.command.detail.DetailTags
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.voice.RoutePolicy
import com.command.history.HistoryTags
import com.command.schedule.ScheduleTags
import com.command.settings.SettingsTags
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The way through the app to what is recorded: from the schedule to History and the details of one alarm, and to Settings. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class HistoryAndSettingsNavigationTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var settings: AppSettings

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking { settings.markOnboardingDone() }
    }

    /** An alarm that rang two hours ago, was answered and started. */
    private fun started(title: String): String = runBlocking {
        val mission = testMission(title)
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 - 2 * 3_600_000, "UTC").occurrence
        val commands = listOf(
            OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(),
            OccurrenceCommand.StartGuidance, OccurrenceCommand.ReportStarted,
        )
        for (c in commands) repo.apply(o.id, c, EventSource.SYSTEM)
        o.id
    }

    private fun open(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)).setup()

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = compose.waitUntil(timeoutMillis = 30_000) { exists(tag) }

    private fun tap(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    private fun back(controller: ActivityController<MainActivity>) = controller.get().onBackPressedDispatcher.onBackPressed()

    @Test
    fun `from the schedule to History, to the details of one alarm, and back again`() {
        val id = started("Morning pages")
        val controller = open()

        tap(ScheduleTags.LINK_HISTORY)
        waitFor(HistoryTags.WEEK)
        compose.onAllNodesWithText("You started on 1 of the 1 day that asked something of you.").assertCountEquals(2) // the week and the month
        tap(HistoryTags.entry(id))

        waitFor(DetailTags.TITLE)
        compose.onNodeWithText("Morning pages").assertIsDisplayed()
        compose.onNodeWithText("You said you started.").assertIsDisplayed()

        back(controller)
        waitFor(HistoryTags.WEEK)
        back(controller)
        waitFor(ScheduleTags.ADD)
        controller.pause().stop().destroy()
    }

    @Test
    fun `from the schedule to Settings, where the route is chosen and written down, and on to History`() {
        val controller = open()

        tap(ScheduleTags.LINK_SETTINGS)
        tap(SettingsTags.ROUTE_HEADSET)
        awaitUntil(message = "written") { runBlocking { settings.routePolicy() } == RoutePolicy.HEADSET_ONLY }
        assertEquals(RoutePolicy.HEADSET_ONLY, runBlocking { settings.routePolicy() })

        tap(SettingsTags.LINK_HISTORY)
        waitFor(HistoryTags.EMPTY)
        controller.pause().stop().destroy()
    }

    @Test
    fun `from Settings a pause for an hour is on the schedule too, and ending it there brings the schedule back`() {
        val controller = open()

        tap(ScheduleTags.LINK_SETTINGS)
        tap(SettingsTags.PAUSE_1H)
        waitFor(SettingsTags.PAUSED)
        assertEquals("the lengths to choose from are gone while it lasts", false, exists(SettingsTags.PAUSE_1H))

        back(controller)
        waitFor(ScheduleTags.PAUSED_BANNER)
        tap(ScheduleTags.PAUSED_ACTION)
        compose.waitUntil(timeoutMillis = 30_000) { !exists(ScheduleTags.PAUSED_BANNER) }
        controller.pause().stop().destroy()
    }
}
