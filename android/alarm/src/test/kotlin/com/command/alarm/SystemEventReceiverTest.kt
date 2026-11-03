package com.command.alarm

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.command.data.ReconcileTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemEventReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun send(action: String) = SystemEventReceiver().onReceive(context, Intent(action))

    private fun queued() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReconcileAlarmsWorker.NAME_NOW).get()

    @Test
    fun `each event that can leave alarms wrong queues a reconcile that says why`() {
        val expected = mapOf(
            Intent.ACTION_BOOT_COMPLETED to ReconcileTrigger.BOOT,
            Intent.ACTION_MY_PACKAGE_REPLACED to ReconcileTrigger.PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED to ReconcileTrigger.TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED to ReconcileTrigger.ZONE_CHANGED,
            SystemEventReceiver.ACTION_EXACT_ALARM_PERMISSION_CHANGED to ReconcileTrigger.EXACT_ALARM_CHANGED,
        )
        for ((action, trigger) in expected) {
            WorkManagerTestInitHelper.initializeTestWorkManager(context) // a clean queue for each event
            send(action)
            val infos = queued()
            assertEquals("$action queues one run", 1, infos.size)
            assertTrue("$action is tagged ${ReconcileScheduler.tagFor(trigger)}", ReconcileScheduler.tagFor(trigger) in infos.single().tags)
        }
    }

    @Test
    fun `an event it does not know queues nothing`() {
        send(Intent.ACTION_BATTERY_LOW)
        send("com.example.SOMETHING")
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `two events in a row queue two runs, one behind the other`() {
        send(Intent.ACTION_BOOT_COMPLETED)
        send(Intent.ACTION_TIME_CHANGED)
        assertEquals(2, queued().size)
    }

    @Test
    fun `the periodic backstop is queued once, however often it is asked for`() {
        ReconcileScheduler.ensurePeriodic(context)
        ReconcileScheduler.ensurePeriodic(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReconcileAlarmsWorker.NAME_PERIODIC).get()
        assertEquals(1, infos.size)
    }

    @Test
    fun `by the time the enqueue has finished the request is stored, which is what the receiver waits for`() {
        val operation = ReconcileScheduler.enqueueNow(context, ReconcileTrigger.BOOT)
        operation.result.get()
        assertEquals(1, queued().size)
    }
}
