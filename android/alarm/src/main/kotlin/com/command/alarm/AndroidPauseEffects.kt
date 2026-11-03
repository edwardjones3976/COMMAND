package com.command.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.command.domain.alarm.PauseEffects
import com.command.domain.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a pause does to the phone: a reconcile is queued for the moment it ends (so what it held back is settled without the app being
 * opened), and a notice stays in the shade while it lasts. Ending it early takes both away; when it runs out the reconcile that closes it
 * may be the one reporting this, so it is left to finish.
 */
@Singleton
class AndroidPauseEffects @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : PauseEffects {

    override fun started(untilUtcMs: Long) {
        ReconcileScheduler.enqueuePauseEnd(context, untilUtcMs, clock.nowMs())
        PauseNotifications.show(context, untilUtcMs)
    }

    override fun expired() {
        PauseNotifications.clear(context)
    }

    override fun resumed() {
        ReconcileScheduler.cancelPauseEnd(context)
        PauseNotifications.clear(context)
    }
}

/** The Resume button of the pause notice: ends the pause now. */
class PauseResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmContract.ACTION_RESUME_PAUSE) return
        runAsync(context) { it.missions().resume() }
    }
}
