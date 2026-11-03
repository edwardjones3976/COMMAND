package com.command.alarm

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.command.data.ReconcileTrigger
import java.util.concurrent.TimeUnit

/** Queues reconcile runs. Nothing here touches the database; the work does that when it runs. */
object ReconcileScheduler {
    private const val PERIOD_HOURS = 6L

    /** Names why a run was queued, so that the queue itself tells the story when someone looks at it. */
    fun tagFor(trigger: ReconcileTrigger): String = "reconcile:${trigger.name}"

    /** Queues one run now, expedited. Runs queue behind each other, so two triggers never reconcile at the same moment. */
    fun enqueueNow(context: Context, trigger: ReconcileTrigger) {
        val request = OneTimeWorkRequestBuilder<ReconcileAlarmsWorker>()
            .setInputData(workDataOf(ReconcileAlarmsWorker.KEY_TRIGGER to trigger.name))
            .addTag(tagFor(trigger))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ReconcileAlarmsWorker.NAME_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** The backstop: every six hours, whatever else happens. Calling it again leaves the existing schedule alone. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReconcileAlarmsWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setInputData(workDataOf(ReconcileAlarmsWorker.KEY_TRIGGER to ReconcileTrigger.PERIODIC.name))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(ReconcileAlarmsWorker.NAME_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
