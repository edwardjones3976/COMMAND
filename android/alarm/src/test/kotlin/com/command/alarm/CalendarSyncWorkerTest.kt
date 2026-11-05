package com.command.alarm

import android.content.Context
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.command.data.CalendarReaderModule
import com.command.data.ConsentStore
import com.command.data.db.CalendarSourceRow
import com.command.data.db.CommandDb
import com.command.domain.calendar.CalendarInstance
import com.command.domain.calendar.CalendarRead
import com.command.domain.calendar.CalendarReader
import com.command.domain.calendar.DeviceCalendar
import com.command.domain.dials.Flexibility
import com.command.domain.model.ConsentKey
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceState
import com.command.testing.FakeAlarmScheduler
import com.command.testing.FakeCalendarReader
import com.command.testing.FakeClock
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidTest
@UninstallModules(CalendarReaderModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class CalendarSyncWorkerTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @BindValue @JvmField val fake = FakeCalendarReader()
    @BindValue @JvmField val reader: CalendarReader = fake

    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock
    @Inject lateinit var consents: ConsentStore
    @Inject lateinit var scheduler: FakeAlarmScheduler
    @Inject lateinit var latch: ErasureLatch

    private lateinit var context: Context
    private val work = DeviceCalendar(7, "me@example.test", "Work")

    @Before
    fun setUp() {
        hilt.inject()
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun worker(observer: Boolean = false, attempt: Int = 0) =
        TestListenableWorkerBuilder<CalendarSyncWorker>(context)
            .setInputData(workDataOf(CalendarSyncWorker.KEY_OBSERVER to observer))
            .setRunAttemptCount(attempt)
            .build()

    private fun doWork(observer: Boolean = false, attempt: Int = 0) = runBlocking { worker(observer, attempt).doWork() }

    private fun switchOn() = runBlocking {
        consents.grant(ConsentKey.CALENDAR_READ)
        db.calendarDao().insertSource(CalendarSourceRow(7, work.account, work.displayName, true, null, Flexibility.FLEXIBLE, null))
        val begin = clock.nowMs() + 3_600_000L
        fake.result = CalendarRead.Ok(listOf(work), listOf(CalendarInstance(7, 100, begin, begin + 1_800_000L, "Standup")), emptySet())
    }

    private fun queued(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

    @Test
    fun `without the consent it reads nothing and takes its queued work away`() {
        CalendarSyncScheduler.ensure(context)
        assertEquals(ListenableWorker.Result.success(), doWork())
        assertEquals(0, fake.reads)
        assertTrue(queued(CalendarSyncWorker.NAME_PERIODIC).isEmpty())
        assertTrue(queued(CalendarSyncWorker.NAME_OBSERVER).isEmpty())
    }

    @Test
    fun `a run imports and arms`() {
        switchOn()
        assertEquals(ListenableWorker.Result.success(), doWork())
        val occ = runBlocking { db.occurrences().all() }.single()
        assertEquals(OccurrenceState.ARMED, occ.state)
    }

    @Test
    fun `a failed read asks for a retry and logs it, then gives up until the next run`() {
        switchOn()
        fake.result = CalendarRead.Failed(com.command.domain.calendar.ReadFailure.NO_CURSOR)
        assertEquals(ListenableWorker.Result.retry(), doWork())
        assertEquals(ListenableWorker.Result.failure(), doWork(attempt = 3))
        assertEquals(2, runBlocking { db.events().all() }.count { it.type == EventType.CALENDAR_READ_FAILED })
    }

    @Test
    fun `the permission lost mid-window leaves the occurrence armed and the run succeeds`() {
        switchOn()
        doWork()
        fake.permission = false
        assertEquals(ListenableWorker.Result.success(), doWork())
        val occ = runBlocking { db.occurrences().all() }.single()
        assertEquals(OccurrenceState.ARMED, occ.state)
        assertTrue(scheduler.armed.isNotEmpty())
        assertEquals(1, runBlocking { db.events().all() }.count { it.type == EventType.CALENDAR_PERMISSION_LOST })
    }

    @Test
    fun `everything being erased means nothing is read`() {
        switchOn()
        latch.seal()
        assertEquals(ListenableWorker.Result.success(), doWork())
        assertEquals(0, fake.reads)
    }

    @Test
    fun `the observer request watches the calendar with the agreed delays`() {
        val constraints = CalendarSyncScheduler.observerRequest().workSpec.constraints
        val trigger = constraints.contentUriTriggers.single()
        assertEquals(CalendarContract.CONTENT_URI, trigger.uri)
        assertTrue(trigger.isTriggeredForDescendants)
        assertEquals(TimeUnit.SECONDS.toMillis(30), constraints.contentTriggerUpdateDelayMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(5), constraints.contentTriggerMaxDelayMillis)
    }

    @Test
    fun `the periodic run is hourly and asking twice queues it once`() {
        // Switched on: this test's WorkManager runs a periodic request at once, and with import off that run takes the work away again.
        switchOn()
        CalendarSyncScheduler.ensure(context)
        CalendarSyncScheduler.ensure(context)
        val periodic = queued(CalendarSyncWorker.NAME_PERIODIC).single()
        assertEquals(TimeUnit.HOURS.toMillis(1), periodic.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(1, queued(CalendarSyncWorker.NAME_OBSERVER).size)
    }

    @Test
    fun `an observer run asks for the next one, a plain run does not`() {
        switchOn()
        doWork(observer = false)
        assertFalse(queued(CalendarSyncWorker.NAME_OBSERVER).isNotEmpty())
        doWork(observer = true)
        assertEquals(1, queued(CalendarSyncWorker.NAME_OBSERVER).size)
    }

    @Test
    fun `an observer out of retries keeps the chain alive, because a failure would fail the run queued behind it`() {
        switchOn()
        fake.result = CalendarRead.Failed(com.command.domain.calendar.ReadFailure.NO_CURSOR)
        assertEquals(ListenableWorker.Result.success(), doWork(observer = true, attempt = 3))
        assertEquals(1, queued(CalendarSyncWorker.NAME_OBSERVER).size)
    }
}
