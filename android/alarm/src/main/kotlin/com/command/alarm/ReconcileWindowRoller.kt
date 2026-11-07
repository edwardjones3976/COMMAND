package com.command.alarm

import android.content.Context
import android.util.Log
import com.command.data.ReconcileTrigger
import com.command.domain.alarm.WindowRoller
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * A ring began, so one of the week's alarms is used up: the reconcile that keeps the week full is queued. It only queues, and it never stands
 * in the way of the ring: this runs among the effects of the Fire, and whatever goes wrong here is put right by the next reconcile. Nothing is
 * queued behind an erase.
 */
class ReconcileWindowRoller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val latch: ErasureLatch,
) : WindowRoller {
    override fun roll() {
        if (latch.sealed) return
        try {
            ReconcileScheduler.enqueueNow(context, ReconcileTrigger.RING_FIRED)
        } catch (e: Exception) {
            Log.w(TAG, "the reconcile after a ring could not be queued; the next one does its work", e)
        }
    }

    private companion object {
        const val TAG = "ReconcileWindowRoller"
    }
}
