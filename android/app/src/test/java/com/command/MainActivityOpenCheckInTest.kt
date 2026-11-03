package com.command

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.command.alarm.AlarmContract
import com.command.data.AppSettings
import com.command.data.OccurrenceRepository
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.schedule.ScheduleTags
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * What a check-in notification's Open action does to the app, through the real activity: it lands on that occurrence's question, whether
 * the app was closed or already open, and recreating the activity neither loses the screen nor opens it a second time.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h2400dp-xhdpi")
class MainActivityOpenCheckInTest {

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

    private fun guided(title: String): OccurrenceRow = runBlocking {
        val mission = testMission(title = title)
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        for (c in listOf(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance)) {
            repo.apply(o.id, c, EventSource.SYSTEM)
        }
        repo.get(o.id)!!
    }

    /** The intent that CheckInNotifications.open builds, as far as the activity can tell. */
    private fun open(o: OccurrenceRow) = openId(o.id)

    private fun openId(id: String) = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        .setAction(AlarmContract.ACTION_OPEN_CHECK_IN)
        .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, id)

    private fun seen(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = compose.waitUntil(timeoutMillis = 30_000) { seen(text) }

    @Test
    fun `Open check-in with the app closed lands on that occurrence's question`() {
        val o = guided("Write the report")
        val controller = Robolectric.buildActivity(MainActivity::class.java, open(o)).setup()

        waitFor("Have you started?")
        compose.onNodeWithText("Write the report").assertIsDisplayed()
        controller.pause().stop().destroy()
    }

    @Test
    fun `Open check-in with the app already open moves to the other occurrence's question`() {
        val first = guided("First thing")
        val second = guided("Second thing")
        val controller = Robolectric.buildActivity(MainActivity::class.java, open(first)).setup()
        waitFor("Have you started?")
        compose.onNodeWithText("First thing").assertIsDisplayed()

        controller.newIntent(open(second)) // CLEAR_TOP and SINGLE_TOP hand a new intent to the activity that is already there
        compose.waitUntil(timeoutMillis = 30_000) { seen("Second thing") }
        compose.onNodeWithText("Have you started?").assertIsDisplayed()
        controller.pause().stop().destroy()
    }

    @Test
    fun `recreating the activity keeps the question on screen and does not open it a second time`() {
        val o = guided("Write the report")
        val controller = Robolectric.buildActivity(MainActivity::class.java, open(o)).setup()
        waitFor("Have you started?")

        controller.recreate()
        waitFor("Have you started?")

        controller.get().onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(timeoutMillis = 30_000) { !seen("Have you started?") }
        controller.pause().stop().destroy()
    }

    @Test
    fun `text that cannot be an occurrence's id is ignored, and the app opens as it always does`() {
        for (bad in listOf("a/b", "../x", "x?y=1", "", "{id}", "followup")) {
            val controller = Robolectric.buildActivity(MainActivity::class.java, openId(bad)).setup()
            compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithTag(ScheduleTags.ADD).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("no question opened for [$bad]", !seen("Have you started?"))
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `the id of an occurrence that is no longer there opens a screen that says so`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, openId("00000000-0000-4000-8000-000000000000")).setup()
        waitFor("This one is gone")
        controller.pause().stop().destroy()
    }
}
