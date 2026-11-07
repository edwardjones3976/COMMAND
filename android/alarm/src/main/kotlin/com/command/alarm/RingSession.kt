package com.command.alarm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
open class RingSession @Inject constructor() {
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var service: ReveilleService? = null

    internal fun attach(service: ReveilleService) {
        this.service = service
    }

    internal fun detach(service: ReveilleService) {
        if (this.service === service) this.service = null
    }

    /**
     * Whether this process's service is ringing something right now. A ring that was killed (a force-stop, a crash) stays "firing" in the
     * database, so only the live service can say whether anything is sounding. Safe from any thread.
     */
    val isRinging: Boolean get() = service?.ringingOccurrenceId != null

    /** Whether the service is ringing something or still checking a delivery that may be about to. Safe from any thread. */
    open val isBusy: Boolean get() = service?.busy == true

    /** Told on the main thread that a ring has begun, once its sound is out. A preview uses it to get out of the way. */
    @Volatile internal var onRingStarting: (() -> Unit)? = null

    /**
     * From the service as a delivery begins, and from the fallback ring's worker, which comes over to the main thread: a preview's playback is set up
     * there, and a stop from elsewhere could fall between its last look and its track. Nothing here may stop the ring: what goes wrong is logged and goes no further.
     */
    internal fun ringStarting() {
        if (Looper.myLooper() == main.looper) cutPreview() else main.post { cutPreview() }
    }

    private fun cutPreview() {
        runCatching { onRingStarting?.invoke() }.onFailure { Log.w(TAG, "a preview could not be stopped, and the ring carries on", it) }
    }

    /** Ends the sound for [occurrenceId] if that is what is ringing. Safe from any thread. */
    fun end(occurrenceId: String) {
        main.post { service?.endRing(occurrenceId) }
    }

    /**
     * Ends whatever the service is ringing or still checking, without naming it: for the erase, which silences first and must not wait for
     * the database to say which alarm that is. Safe from any thread.
     */
    open fun endActive() {
        main.post { service?.endEverything() }
    }
}

private const val TAG = "RingSession"

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
