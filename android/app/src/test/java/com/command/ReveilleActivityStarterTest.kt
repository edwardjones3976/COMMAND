package com.command

import android.content.Intent
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.command.alarm.AlarmContract
import com.command.data.OccurrenceRepository
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.reveille.ReveilleTags
import com.command.testing.FakeClock
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
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

/** The starter and the line in large type through the real activity, the real view model and a real database: that they are wired to each other. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h2400dp-xhdpi")
class ReveilleActivityStarterTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock

    private val opened = ArrayList<ActivityController<ReveilleActivity>>()

    @Before fun setUp() { hilt.inject() }

    @After fun tearDown() { opened.forEach { runCatching { it.pause().stop().destroy() } } }

    private fun firing(): OccurrenceRow = runBlocking {
        val mission = testMission(profileId = "builtin_steady")
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        repo.get(o.id)!!
    }

    private fun guided(): OccurrenceRow = runBlocking {
        val o = firing()
        repo.apply(o.id, OccurrenceCommand.Acknowledge(), EventSource.USER)
        repo.apply(o.id, OccurrenceCommand.StartGuidance, EventSource.USER)
        repo.get(o.id)!!
    }

    private fun open(o: OccurrenceRow): ActivityController<ReveilleActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ReveilleActivity::class.java).putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, o.id)
        return Robolectric.buildActivity(ReveilleActivity::class.java, intent).setup().also { opened += it }
    }

    private fun waitForTag(tag: String) = compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the offer on the direction begins the countdown, and the activity comes back to it after it is gone`() {
        val o = guided()
        val first = open(o)
        waitForTag(ReveilleTags.STARTER_BEGIN)

        compose.onNodeWithTag(ReveilleTags.STARTER_BEGIN).performClick()

        waitForTag(ReveilleTags.STARTER_COUNTDOWN)
        compose.onNodeWithTag(ReveilleTags.STARTER_COUNTDOWN).assertTextEquals("5:00")
        assertEquals(OccurrenceState.GUIDED_START, runBlocking { repo.get(o.id)!!.state })

        first.pause().stop().destroy()
        opened -= first
        clock.advance(120_000)
        open(o)

        waitForTag(ReveilleTags.STARTER_COUNTDOWN)
        compose.onNodeWithTag(ReveilleTags.STARTER_COUNTDOWN).assertTextEquals("3:00")
    }

    @Test
    fun `the report of a start on the countdown goes through the activity`() {
        val o = guided()
        runBlocking { repo.apply(o.id, OccurrenceCommand.BeginStarter, EventSource.USER) }
        open(o)
        waitForTag(ReveilleTags.STARTER_COUNTDOWN)

        compose.onNodeWithTag(ReveilleTags.STARTED).performClick()

        compose.waitUntil(timeoutMillis = 30_000) { runBlocking { repo.get(o.id)!!.state } == OccurrenceState.STARTED_SELF_REPORTED }
    }

    @Test
    fun `a ring whose line was not heard shows it on the activity's ring screen as soon as that is written down`() {
        val o = firing()
        open(o)
        waitForTag(ReveilleTags.HANDSHAKE_PROMPT)
        assertEquals(0, compose.onAllNodesWithTag(ReveilleTags.UNSPOKEN_LINE).fetchSemanticsNodes().size)

        runBlocking { repo.logEvent(EventType.AUDIO_ROUTE, o, EventSource.SERVICE, EventPayload.of("devices" to "speaker", "policy" to "HEADSET_ONLY", "spoke" to false)) }

        waitForTag(ReveilleTags.UNSPOKEN_LINE)
    }
}
