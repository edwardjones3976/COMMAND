package com.command.alarm

import com.command.data.MissionRepository
import com.command.data.ReconcileReport
import com.command.data.ReconcileTrigger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One pass of "make what is stored and what the platform holds agree again, and write down what the platform currently
 * allows". The app calls it when it opens; the background worker calls it after a reboot, an update, a clock or zone change,
 * a change of the exact-alarm permission, and every few hours. It is safe to run at any time and as often as you like.
 */
@Singleton
class ArmingReconciler @Inject constructor(
    private val missions: MissionRepository,
    private val monitor: ReliabilityMonitor,
    private val volume: AlarmVolumePolicy,
) {
    suspend fun run(trigger: ReconcileTrigger): ReconcileReport {
        // What a ring that died raised is put back (a ring that is still going is left alone).
        volume.restoreStale()
        // Then what the platform allows now, so a permission that was just taken away is on the record before alarms are re-armed.
        monitor.refresh()
        return missions.reconcile(trigger)
    }
}
