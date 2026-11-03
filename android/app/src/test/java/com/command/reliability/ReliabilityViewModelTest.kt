package com.command.reliability

import androidx.test.core.app.ApplicationProvider
import com.command.data.AppSettings
import com.command.data.OccurrenceRepository
import com.command.data.ReliabilityRepository
import com.command.data.db.CommandDb
import com.command.data.db.EventRow
import com.command.domain.model.EventSource
import com.command.domain.occurrence.EventType
import com.command.domain.reliability.AlarmOutcomeKind
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.Severity
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.FakeReliabilityMonitor
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class ReliabilityViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var monitor: FakeReliabilityMonitor
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var repository: ReliabilityRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var occurrences: OccurrenceRepository

    private lateinit var vm: ReliabilityViewModel
    private lateinit var collector: Job

    @Before
    fun setUp() {
        hilt.inject()
        vm = ReliabilityViewModel(monitor, settings, repository, OemAutostart(ApplicationProvider.getApplicationContext()))
        // The state is only live while someone is collecting it, as on screen.
        collector = CoroutineScope(Dispatchers.Main.immediate).launch { vm.state.collect { } }
    }

    @After
    fun tearDown() = collector.cancel()

    private fun ready(): ReliabilityUiState.Ready? = vm.state.value as? ReliabilityUiState.Ready

    @Test
    fun `nothing is shown until the phone has been read`() {
        assertEquals(ReliabilityUiState.Loading, vm.state.value)
        vm.refresh()
        awaitUntil(message = "ready") { ready() != null }
    }

    @Test
    fun `a healthy phone gives a healthy report`() {
        vm.refresh()
        awaitUntil(message = "ready") { ready() != null }
        assertTrue(ready()!!.report.isHealthy)
        assertEquals(Severity.OK, ready()!!.report.worst)
        assertNull("the Robolectric phone is not from a maker with an autostart page", ready()!!.maker)
    }

    @Test
    fun `a permission turned off in settings shows up the next time the page is looked at`() {
        vm.refresh()
        awaitUntil(message = "ready") { ready() != null }
        assertTrue(ready()!!.report.problems.isEmpty())

        monitor.set(Precondition.EXACT_ALARMS, "denied")
        vm.refresh()
        awaitUntil(message = "problem") { ready()?.report?.problems?.size == 1 }
        assertEquals(Precondition.EXACT_ALARMS, ready()!!.report.problems.single().precondition)
    }

    @Test
    fun `every reading goes through the monitor, which writes down what changed`() {
        vm.refresh()
        awaitUntil(message = "ready") { ready() != null }
        assertEquals(1, monitor.refreshes)
        val rows = runBlocking { db.events().ofType(EventType.PRECONDITION) }
        assertEquals("the first reading is every precondition once", Precondition.values().size, rows.size)
    }

    @Test
    fun `the notification request can be asked until it has been answered, and not after`() {
        vm.refresh()
        awaitUntil(message = "ready") { ready() != null }
        assertTrue(ready()!!.canAskNotifications)

        vm.onNotificationRequestResult()
        awaitUntil(message = "asked") { ready()?.canAskNotifications == false }
        assertTrue(runBlocking { settings.notificationsAsked() })
    }

    @Test
    @Config(sdk = [32])
    fun `before Android 13 there is no request to ask`() {
        vm.refresh()
        awaitUntil(message = "ready") { ready() != null }
        assertFalse(ready()!!.canAskNotifications)
    }

    @Test
    fun `recent alarms come from the audit log, newest first`() {
        runBlocking {
            val mission = testMission("Standup")
            db.missions().insert(mission)
            val id = occurrences.createOccurrence(mission.id, FakeAlarmModule.T0, "UTC").occurrence.id
            db.events().insert(
                EventRow(
                    occurrenceId = id, missionId = null, type = EventType.ALARM_FIRED, atUtc = FakeAlarmModule.T0 + 2_000, elapsedRealtime = 0,
                    bootId = null, source = EventSource.SERVICE, payloadJson = "{}",
                ),
            )
        }
        vm.refresh()
        awaitUntil(message = "recent") { ready()?.recent?.size == 1 }
        assertEquals(AlarmOutcomeKind.ON_TIME, ready()!!.recent.single().kind)
        assertEquals("Standup", ready()!!.recent.single().title)
    }
}
