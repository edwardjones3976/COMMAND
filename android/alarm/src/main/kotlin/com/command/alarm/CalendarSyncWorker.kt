package com.command.alarm

import android.content.Context
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.command.data.CalendarSyncOutcome
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * One import from the phone's calendars. A read that fails is retried and changes nothing; so does a lost permission, which also leaves
 * everything armed. When import is off (no consent, nothing selected) the queued work is taken away again and nothing is read.
 */
class CalendarSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entry = AlarmEntryPoint.from(applicationContext)
        if (runCatching { entry.latch().sealed }.getOrDefault(false)) return Result.success()
        val observing = inputData.getBoolean(KEY_OBSERVER, false)
        val outcome = try {
            entry.calendarSync().run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CalendarSyncOutcome.RETRY
        }
        return when (outcome) {
            CalendarSyncOutcome.RETRY -> {
                if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
                // A failure would also fail the run queued behind it, and with that the watching of the calendar.
                if (observing) {
                    CalendarSyncScheduler.observeAgain(applicationContext)
                    Result.success()
                } else {
                    Result.failure()
                }
            }
            CalendarSyncOutcome.OFF -> {
                CalendarSyncScheduler.cancel(applicationContext)
                Result.success()
            }
            CalendarSyncOutcome.DONE, CalendarSyncOutcome.PERMISSION_LOST -> {
                // A content-URI trigger fires once: the next change needs a new request.
                if (observing) CalendarSyncScheduler.observeAgain(applicationContext)
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_OBSERVER = "observer"
        const val NAME_PERIODIC = "calendar-sync-periodic"
        const val NAME_OBSERVER = "calendar-sync-observer"
        const val NAME_NOW = "calendar-sync-now"
        private const val MAX_ATTEMPTS = 3
    }
}

/** Queues calendar imports. Nothing here touches the database or a calendar. */
object CalendarSyncScheduler {
    private const val PERIOD_HOURS = 1L
    private const val UPDATE_DELAY_S = 30L
    private const val MAX_DELAY_S = 300L

    /** The hourly run and the run for the next change in a calendar. Calling it again leaves what is queued alone. */
    fun ensure(context: Context) {
        val manager = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequestBuilder<CalendarSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS).build()
        manager.enqueueUniquePeriodicWork(CalendarSyncWorker.NAME_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        manager.enqueueUniqueWork(CalendarSyncWorker.NAME_OBSERVER, ExistingWorkPolicy.KEEP, observerRequest())
    }

    /** Run from inside the observer: it follows the run that is ending, which REPLACE would cancel. */
    internal fun observeAgain(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(CalendarSyncWorker.NAME_OBSERVER, ExistingWorkPolicy.APPEND_OR_REPLACE, observerRequest())
    }

    /** One run now, for a choice the person just made. */
    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(CalendarSyncWorker.NAME_NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<CalendarSyncWorker>().build())
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(CalendarSyncWorker.NAME_PERIODIC)
        manager.cancelUniqueWork(CalendarSyncWorker.NAME_OBSERVER)
    }

    internal fun observerRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<CalendarSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(UPDATE_DELAY_S, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(MAX_DELAY_S, TimeUnit.SECONDS)
                    .build(),
            )
            .setInputData(workDataOf(CalendarSyncWorker.KEY_OBSERVER to true))
            .build()
}
