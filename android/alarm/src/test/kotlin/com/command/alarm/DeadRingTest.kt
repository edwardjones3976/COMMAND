package com.command.alarm

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.data.OccurrenceRepository
import com.command.data.ReconcileTrigger
import com.command.data.SessionPlan
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.testing.FakeAlarmScheduler
import com.command.testing.FakeClock
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * A force-stop ends the ring's process and takes its cycle-end alarm with it, and nothing delivers the service's intent again. Its occurrence
 * would stay FIRING for ever: not on the schedule's list of what is over, and with nothing left that could end it. A reconcile finishes it.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34, 36])
class DeadRingTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var reconciler: ArmingReconciler
    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock
    @Inject lateinit var scheduler: FakeAlarmScheduler

    private lateinit var context: Context

    @Before
    fun setUp() {
        hilt.inject()
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /** A ring that began and whose process was then force-stopped: it is FIRING in the database and its alarms are gone. */
    private fun abandoned(): OccurrenceRow = runBlocking {
        val mission = testMission(profileId = "builtin_steady")
        db.missions().insert(mission)
        val created = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(created.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        clock.advance(60_000)
        repo.apply(created.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        scheduler.armed.clear()
        repo.get(created.id)!!.also { assertEquals(OccurrenceState.FIRING, it.state) }
    }

    private fun reconcile() = runBlocking { reconciler.run(ReconcileTrigger.OPEN) }

    private fun current(o: OccurrenceRow) = runBlocking { repo.get(o.id)!! }

    private fun planOf(o: OccurrenceRow) = SessionPlan.of(o)!!

    private fun cycleEnd(o: OccurrenceRow) = scheduler.armed[AlarmRequest(o.id, o.requestCode, AlarmKind.CYCLE_END, 0).identity]

    private fun note(o: OccurrenceRow) =
        shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(o.id, AlarmContract.ID_MISSED)

    @Test
    fun `a ring whose plan ran out while nothing was alive is recorded as missed, with the quiet note`() {
        val o = abandoned()
        clock.advance(planOf(o).totalMs + 1)

        reconcile()

        assertEquals(OccurrenceState.MISSED, current(o).state)
        assertEquals(MissReason.TIMEOUT, current(o).missReason)
        assertNotNull("the person is told, as when a ring times out by itself", note(o))
        assertNull("and no alarm for it is left behind", cycleEnd(o))
    }

    @Test
    fun `a ring with some of its plan left stays what it is, and gets the alarm back that will end it`() {
        val o = abandoned()
        val left = 20_000L
        clock.advance(planOf(o).totalMs - left)

        reconcile()

        assertEquals("it can still be answered", OccurrenceState.FIRING, current(o).state)
        assertEquals("the alarm that ends it", clock.nowMs + left, cycleEnd(o)?.atUtcMs)
        assertNull(note(o))
    }

    @Test
    fun `the alarm that ends it is worked out from the monotonic deadline, so a clock set back cannot stretch the ring`() {
        val o = abandoned()
        val total = planOf(o).totalMs
        clock.advance(10_000)
        clock.nowMs -= 3_600_000

        reconcile()

        assertEquals(clock.nowMs + total - 10_000, cycleEnd(o)?.atUtcMs)
        assertEquals(OccurrenceState.FIRING, current(o).state)
    }

    @Test
    fun `a reboot makes the stored deadline mean nothing, even when the new boot has been up longer than the ring had`() {
        clock.bootId = "1"
        val o = abandoned()
        val total = planOf(o).totalMs
        clock.bootId = "2"
        clock.elapsedMs += 10_000 // read as this boot's clock, only 10 seconds of the ring have gone
        clock.nowMs += total + 3_600_000 // by the wall clock it ended an hour ago

        reconcile()

        assertEquals(OccurrenceState.MISSED, current(o).state)
        assertNull(cycleEnd(o))
    }

    @Test
    fun `after a reboot the time left comes from the wall clock`() {
        clock.bootId = "1"
        val o = abandoned()
        val total = planOf(o).totalMs
        clock.bootId = "2"
        clock.elapsedMs += 10_000
        clock.nowMs += 30_000

        reconcile()

        assertEquals(OccurrenceState.FIRING, current(o).state)
        assertEquals(clock.nowMs + total - 30_000, cycleEnd(o)?.atUtcMs)
    }

    @Test
    fun `in the same boot a clock set forward cannot end a ring that may be sounding`() {
        clock.bootId = "1"
        val o = abandoned()
        val total = planOf(o).totalMs
        clock.advance(10_000)
        clock.nowMs += total + 3_600_000

        reconcile()

        assertEquals(OccurrenceState.FIRING, current(o).state)
        assertEquals(clock.nowMs + total - 10_000, cycleEnd(o)?.atUtcMs)
    }

    @Test
    fun `a ring whose plan cannot be read is over, because nothing could ever say when it ends`() {
        val o = abandoned()
        db.openHelper.writableDatabase.execSQL("UPDATE occurrences SET session_json = '{}' WHERE id = '${o.id}'")

        reconcile()

        assertEquals(OccurrenceState.MISSED, current(o).state)
    }

    @Test
    fun `the next reconcile finds nothing more to do, and leaves no second note`() {
        val o = abandoned()
        clock.advance(planOf(o).totalMs + 1)
        reconcile()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(o.id, AlarmContract.ID_MISSED)
        val callsBefore = scheduler.calls.size

        reconcile()

        assertNull(note(o))
        assertFalse(scheduler.calls.drop(callsBefore).any { it.contains("cycle-end") })
    }

    @Test
    fun `a ring that was answered is no ring any more and is left alone`() {
        val o = abandoned()
        runBlocking { repo.apply(o.id, OccurrenceCommand.SafetySilence, EventSource.USER) }
        clock.advance(planOf(o).totalMs + 1)

        reconcile()

        assertEquals(OccurrenceState.SAFETY_SILENCED, current(o).state)
        assertNull(note(o))
        assertNull(cycleEnd(o))
    }

    @Test
    fun `a ring that was answered with time still on its plan gets no alarm back`() {
        val o = abandoned()
        runBlocking { repo.apply(o.id, OccurrenceCommand.SafetySilence, EventSource.USER) }
        scheduler.armed.clear()
        clock.advance(10_000)

        reconcile()

        assertEquals(OccurrenceState.SAFETY_SILENCED, current(o).state)
        assertNull(cycleEnd(o))
    }

    @Test
    fun `the worker is what does it after a reboot`() {
        val o = abandoned()
        clock.advance(planOf(o).totalMs + 1)
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val worker = androidx.work.testing.TestListenableWorkerBuilder<ReconcileAlarmsWorker>(context)
            .setInputData(androidx.work.workDataOf(ReconcileAlarmsWorker.KEY_TRIGGER to "BOOT")).build()
        runBlocking { worker.doWork() }

        assertEquals(OccurrenceState.MISSED, current(o).state)
    }
}
