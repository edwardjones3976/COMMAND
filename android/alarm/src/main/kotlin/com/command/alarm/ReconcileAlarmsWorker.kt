package com.command.alarm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.command.data.ReconcileTrigger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs one reconcile in the background. It exists because Android 15 does not let a boot receiver start a media-playback
 * foreground service, and because the database must not be opened on a receiver's few seconds: the receiver only queues this.
 * A failure is retried a few times, then given up on; the periodic run and the next app open will try again.
 */
class ReconcileAlarmsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)
            ?.let { name -> ReconcileTrigger.values().firstOrNull { it.name == name } }
            ?: ReconcileTrigger.PERIODIC
        // First, before anything that can fail: the backstop must exist even if this run gives up, and after an update from a version
        // that never installed it, before the app has been opened.
        ReconcileScheduler.ensurePeriodic(applicationContext)
        return try {
            AlarmEntryPoint.from(applicationContext).arming().run(trigger)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /**
     * Only used before Android 12, where expedited work runs as a foreground service. It names no service type on purpose: a
     * type would need a permission and a Play declaration for a service that never starts on the versions that require them.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(AlarmContract.ID_RECONCILE, RingNotifications.checking(applicationContext))

    companion object {
        const val KEY_TRIGGER = "trigger"
        const val NAME_NOW = "reconcile-now"
        const val NAME_PERIODIC = "reconcile-periodic"
        private const val MAX_ATTEMPTS = 3
    }
}
