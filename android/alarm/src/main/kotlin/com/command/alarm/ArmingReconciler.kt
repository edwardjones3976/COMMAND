package com.command.alarm

import android.content.Context
import com.command.data.MissionRepository
import com.command.data.OccurrenceRepository
import com.command.data.ReconcileReport
import com.command.data.ReconcileTrigger
import com.command.data.SessionPlan
import com.command.domain.model.EventSource
import com.command.domain.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One pass of "make what is stored and what the platform holds agree again, and write down what the platform currently
 * allows". The app calls it when it opens; the background worker calls it after a reboot, an update, a clock or zone change,
 * a change of the exact-alarm permission, when a ring begins, and every few hours. It is safe to run at any time and as often as you like.
 */
@Singleton
class ArmingReconciler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val missions: MissionRepository,
    private val occurrences: OccurrenceRepository,
    private val monitor: ReliabilityMonitor,
    private val volume: AlarmVolumePolicy,
    private val clock: Clock,
) {
    suspend fun run(trigger: ReconcileTrigger): ReconcileReport {
        // What a ring that died raised is put back (a ring that is still going is left alone).
        volume.restoreStale()
        // Then what the platform allows now, so a permission that was just taken away is on the record before alarms are re-armed.
        monitor.refresh()
        val report = missions.reconcile(trigger)
        settleRings()
        return report
    }

    /**
     * A force-stop or a reboot ends a ring's process and takes its cycle-end alarm with it, and leaves its occurrence FIRING with nothing that
     * could ever end it. One whose plan is over goes the way of a ring that timed out, with the same quiet note. One with time left stays
     * what it is, so it can still be answered, and gets back the alarm that ends it. A ring that really is sounding is no different: its plan
     * says the same, and the alarm is only replaced.
     */
    private suspend fun settleRings() {
        for (row in occurrences.firing()) {
            val plan = SessionPlan.of(row)
            val elapsed = plan?.elapsedMs(clock.nowMs(), clock.elapsedRealtimeMs())
            if (plan == null || elapsed == null || elapsed >= plan.totalMs) {
                timeOutRing(context, occurrences, row.id, row.requestCode, EventSource.SYSTEM)
            } else {
                occurrences.armCycleEnd(row.id, clock.nowMs() + plan.totalMs - elapsed)
            }
        }
    }
}
