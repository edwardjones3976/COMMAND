package com.command.alarm

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.command.domain.alarm.RingController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The link from the rest of the app to the ring service. The service lives in this process, so ending a ring is a plain
 * call and never another service start: starting a service just to tell it to stop is how a stop can race with the start
 * of the next ring. With no service running there is nothing to end, and nothing is started.
 */
@Singleton
class RingSession @Inject constructor() {
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var service: ReveilleService? = null

    internal fun attach(service: ReveilleService) {
        this.service = service
    }

    internal fun detach(service: ReveilleService) {
        if (this.service === service) this.service = null
    }

    /** Ends the sound for [occurrenceId] if that is what is ringing. Safe from any thread. */
    fun end(occurrenceId: String) {
        main.post { service?.endRing(occurrenceId) }
    }
}

/** Ends a ring after a state change: the sound (through the service) and the notifications the ring left behind. */
class AndroidRingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: RingSession,
) : RingController {
    override fun stop(occurrenceId: String, requestCode: Int) {
        // A ring that went out as a fallback notification has no service: its sound belongs to the notification. A ring that
        // a later one displaced is an ordinary notification by now. Neither may outlive its occurrence. (The notification of
        // the ring that is sounding is the service's foreground one; it goes when the service stops.)
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(occurrenceId, AlarmContract.ID_FALLBACK)
            manager.cancel(requestCode)
        }
        session.end(occurrenceId)
    }
}
