package com.command.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.command.data.ReconcileTrigger
import com.command.domain.alarm.WindowRoller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WindowRollerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val latch = ErasureLatch()

    private fun queued() = WorkManager.getInstance(context).getWorkInfosByTag(ReconcileScheduler.tagFor(ReconcileTrigger.RING_FIRED)).get()

    @Test
    fun `a roll queues the existing reconcile, in the queue reconciles wait in`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        ReconcileWindowRoller(context, latch).roll()

        assertEquals(1, queued().size)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReconcileAlarmsWorker.NAME_NOW).get().size)
    }

    @Test
    fun `nothing is queued behind an erase`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        latch.seal()

        ReconcileWindowRoller(context, latch).roll()

        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a queue that cannot be reached does not reach the ring`() {
        // An earlier test's WorkManager may still be there, so it is taken away. This runs among the effects of the Fire.
        val before = WorkManagerImpl.getInstance()
        WorkManagerImpl.setDelegate(null)
        try {
            assertThrows(IllegalStateException::class.java) { WorkManager.getInstance(context) }
            ReconcileWindowRoller(context, latch).roll()
        } finally {
            WorkManagerImpl.setDelegate(before)
        }
    }

    @Test
    fun `this is what the app binds, and a ring is not worth a line in the log by itself`() {
        val binding = AlarmBindings::class.java.declaredMethods.single { it.returnType == WindowRoller::class.java }
        assertEquals(ReconcileWindowRoller::class.java, binding.parameterTypes.single())
        assertFalse(ReconcileTrigger.RING_FIRED.alwaysLogged)
    }
}
