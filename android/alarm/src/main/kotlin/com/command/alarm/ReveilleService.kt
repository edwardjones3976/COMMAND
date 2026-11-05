package com.command.alarm

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.command.data.AppSettings
import com.command.data.ApplyResult
import com.command.data.MissionBrief
import com.command.data.OccurrenceRepository
import com.command.data.SessionPlan
import com.command.data.db.OccurrenceRow
import com.command.domain.model.EventSource
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.time.Clock
import com.command.domain.voice.SpeakResult
import com.command.domain.voice.Speaker
import com.command.domain.voice.SpeechProblem
import com.command.domain.voice.SpeechUsage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Rings occurrences. Started by the alarm receiver with `startForegroundService`, so the first thing it does, before any
 * database work, is call `startForeground`. Then it fires the occurrence (the state machine decides whether that is legal),
 * and plays the alarm sound in the cycles the user's alert intensity prescribes, stopping at the end of the plan whatever
 * else happens. Restarted after the process died mid-ring, it works out from the stored plan whether there is any ring left.
 *
 * Several alarms can arrive close together. Deliveries are handled one at a time, in order, and each is checked against the
 * database before it touches a ring that is already going: a stale or repeated alarm leaves the current ring alone, and a
 * valid later one takes over the sound (the earlier occurrence stays as it is until it is answered or its own plan ends).
 * The database steps are never cancelled half-way, so a consumed alarm always ends up recorded.
 */
@AndroidEntryPoint
class ReveilleService : Service() {

    @Inject lateinit var repository: OccurrenceRepository
    @Inject lateinit var clock: Clock
    @Inject lateinit var output: RingOutput
    @Inject lateinit var resolver: RingModeResolver
    @Inject lateinit var session: RingSession
    @Inject lateinit var speaker: Speaker
    @Inject lateinit var routes: AudioRoutes
    @Inject lateinit var volume: AlarmVolumePolicy
    @Inject lateinit var appSettings: AppSettings
    @Inject lateinit var lines: ReveilleLines

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** One delivery at a time, in the order they arrived. */
    private val deliveries = Mutex()

    // Everything below is touched on the main thread only.
    private var pending = 0
    private var lastStartId = 0

    // Written on the main thread only, and read from others through RingSession.isRinging.
    @Volatile private var active: ActiveRing? = null

    /** Occurrences accepted but not yet checked against the database; the sound may already be playing for them. */
    private val inFlight = ArrayList<String>()

    /** Occurrences whose ring was ended while they were still in flight, so the check must not start it. */
    private val endedEarly = HashSet<String>()

    /** The occurrence that is sounding right now, if any. For tests. */
    internal val ringingOccurrenceId: String? get() = active?.id
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null

    private class ActiveRing(val id: String, val code: Int, val title: String?) {
        var playback: Job? = null
        var speech: Job? = null
    }

    override fun onCreate() {
        super.onCreate()
        session.attach(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        ring(intent)
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        session.detach(this)
        output.stop()
        speaker.stop()
        speaker.release()
        scope.cancel()
        // A service destroyed with a ring still in it (not a normal ending, which has already done this) leaves no ring to need the volume.
        volume.restoreAnySoon()
        releaseHolds()
        super.onDestroy()
    }

    private fun ring(intent: Intent?) {
        val id = AlarmContract.occurrenceId(intent)
        val code = AlarmContract.requestCode(intent)
        if (id == null || code == 0) {
            // Started with nothing to ring for. A foreground start must still be answered with startForeground.
            runCatching { ServiceCompat.startForeground(this, FALLBACK_ID, RingNotifications.starting(this), FGS_TYPE) }
            settle()
            return
        }
        val ringing = active
        try {
            // First: become a foreground service (a deadline of a few seconds). Everything else comes after. If something is
            // ringing already, its notification stays the foreground one until this delivery has proved itself.
            val notification = if (ringing == null) {
                RingNotifications.ring(this, id, code, null)
            } else {
                RingNotifications.ring(this, ringing.id, ringing.code, ringing.title)
            }
            ServiceCompat.startForeground(this, ringing?.code ?: code, notification, FGS_TYPE)
        } catch (e: Exception) {
            // Not allowed to run as a foreground service right now. Still answer the start if the platform will let us (its
            // deadline is a few seconds and the fallback needs the database), then ring from a full-screen notification.
            runCatching { ServiceCompat.startForeground(this, FALLBACK_ID, RingNotifications.starting(this), FGS_TYPE) }
            queue(null) { resolver.degrade(id, code, reasonOf(e)) }
            return
        }
        if (ringing == null) {
            // Sound first: opening the database after a cold start takes a second or more, and an alarm should not wait for
            // it. If the database then says this alarm is stale or its plan is over, the sound is stopped again.
            holdWakeLock()
            output.start()
            // The speech engine takes seconds to start on some phones, so it is started now, alongside the database work that comes
            // next, and not when the first line is asked for.
            runCatching { speaker.prepare() }
        }
        queue(id) { deliver(id, code) }
    }

    /**
     * Runs [work] after the deliveries before it, and lets the service go once there is nothing left to do. [occurrenceId]
     * is the occurrence being checked, so that an end requested before the check finishes is not lost.
     */
    private fun queue(occurrenceId: String?, work: suspend () -> Unit) {
        pending++
        if (occurrenceId != null) inFlight += occurrenceId
        scope.launch {
            try {
                deliveries.withLock { work() }
            } finally {
                if (occurrenceId != null) {
                    inFlight.remove(occurrenceId)
                    if (occurrenceId !in inFlight) endedEarly -= occurrenceId
                }
                pending--
                settle()
            }
        }
    }

    /** Stops the service when nothing rings and no delivery is waiting to be looked at. */
    private fun settle() {
        if (active == null && pending == 0) shutDown()
    }

    private suspend fun deliver(id: String, code: Int) {
        // The database work is never cancelled half-way: an alarm that has been consumed must end up recorded.
        val fire = withContext(NonCancellable) { repository.apply(id, OccurrenceCommand.Fire, EventSource.SERVICE) }
        val row: OccurrenceRow
        if (fire is ApplyResult.Applied) {
            row = fire.occurrence
        } else {
            val current = withContext(NonCancellable) { repository.get(id) }
            if (current == null || current.state != OccurrenceState.FIRING) {
                // A stale alarm: the occurrence is not armed any more. Whatever else is ringing is left alone.
                current?.let { repository.logEvent(EventType.ALARM_FIRED_STALE, it, EventSource.SERVICE, EventPayload.EMPTY) }
                if (active?.code != code) runCatching { NotificationManagerCompat.from(this).cancel(code) }
                return
            }
            row = current // restarted in the middle of this ring
        }

        // The same alarm delivered twice (two reconciles can do it): the ring that is going carries on, and its line is not said again.
        if (active?.id == row.id) return

        val plan = SessionPlan.of(row)
        if (plan == null || plan.isOver(clock.nowMs(), clock.elapsedRealtimeMs())) {
            // The plan ended while nothing was alive to enforce it: record the miss and do not ring at all.
            timeOutRing(this, repository, row.id, code, EventSource.SERVICE)
            return
        }
        // The last suspension before the ring is installed comes first, and the check for an end that arrived in the
        // meantime comes after it with nothing in between, so a silence can never slip through the gap.
        val brief = repository.missionBrief(row.missionId)
        if (id in endedEarly) return // silenced or answered in the first moments: there is nothing left to sound
        takeOver(row, code, plan, brief)
    }

    /** Makes [row] the ring that is sounding. An earlier ring's sound loop ends; its occurrence is left as it is. */
    private fun takeOver(row: OccurrenceRow, code: Int, plan: SessionPlan, brief: MissionBrief?) {
        val title = brief?.title
        val previous = active
        previous?.playback?.cancel()
        previous?.speech?.cancel()
        // The displaced ring's line is stopped, in the player and in the engine, before the new ring's is asked for.
        if (previous != null) speaker.stop()
        val ring = ActiveRing(row.id, code, title)
        active = ring
        // This ring's notification, now with its title, becomes the foreground one. An earlier ring's, if there was one,
        // stays an ordinary notification until that occurrence is handled.
        runCatching { ServiceCompat.startForeground(this, code, RingNotifications.ring(this, row.id, code, title), FGS_TYPE) }
        holdWakeLock()
        requestFocus()
        ring.playback = scope.launch {
            runCycles(plan)
            // Out of ring time and still unanswered: that is a miss. (The cycle-end alarm would do the same.)
            timeOutRing(this@ReveilleService, repository, ring.id, ring.code, EventSource.SERVICE)
            endRing(ring.id)
        }
        // The tone is already going. The first line joins it when the phone's voice is ready (it never holds the ring up), and the
        // volume is raised only if the person agreed to that. A ring that takes over from another finds the volume already raised and
        // adopts it, so that the first ring's delayed clean-up cannot lower it while this one sounds.
        ring.speech = launchExtra("speech") { say(row, brief) }
        launchExtra("raising the volume") { volume.raiseFor(row) }
    }

    /**
     * The spoken line and the volume are extras to the ring. Whatever goes wrong in them, the ring goes on, so nothing they throw is
     * allowed to reach the process, which an uncaught exception in a coroutine would kill in the middle of an alarm.
     */
    private fun launchExtra(what: String, work: suspend () -> Unit): Job = scope.launch {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed, and the ring carries on", e)
        }
    }

    /**
     * Says the opening line once, over the tone, unless the person asked for speech through a headset only and none is connected.
     * What happened is written down either way: that it was spoken and where, that the phone could not speak and why, or that the
     * route said no. The words are on the screen whatever happens here.
     */
    private suspend fun say(row: OccurrenceRow, brief: MissionBrief?) {
        val policy = appSettings.routePolicy()
        val devices = routes.describe()
        if (!policy.allowsSpeech(routes.headsetConnected())) {
            repository.logEvent(EventType.AUDIO_ROUTE, row, EventSource.SERVICE, EventPayload.of("devices" to devices, "policy" to policy, "spoke" to false))
            return
        }
        // The tone is turned down only once the line is about to be heard: while the engine is starting up or writing the audio, which
        // can take seconds, the alarm is as loud as it ever is.
        val result = try {
            speaker.speak(lines.reveille(brief), SpeechUsage.ALARM, policy) { output.duck(true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SpeakResult.Unavailable(SpeechProblem.UNEXPECTED)
        } finally {
            output.duck(false)
        }
        when (result) {
            is SpeakResult.Unavailable -> repository.logEvent(EventType.TTS_UNAVAILABLE, row, EventSource.SERVICE, EventPayload.of("reason" to result.reason))
            is SpeakResult.Spoken -> repository.logEvent(EventType.AUDIO_ROUTE, row, EventSource.SERVICE, EventPayload.of("devices" to devices, "policy" to policy, "spoke" to true))
            SpeakResult.Interrupted -> Unit
        }
    }

    /**
     * Sound during each ring cycle, silence in the gaps, ending exactly when the plan does. Where we are in the plan comes from
     * the stored monotonic deadline (or, after a reboot, one reading of the wall clock); every wait after that is measured on the
     * monotonic clock, so setting the phone's clock cannot stretch the ring or cut it short, even across a restart.
     */
    private suspend fun runCycles(plan: SessionPlan) {
        val sinceStart = plan.elapsedMs(clock.nowMs(), clock.elapsedRealtimeMs())
        val origin = clock.elapsedRealtimeMs() - sinceStart
        fun position() = clock.elapsedRealtimeMs() - origin
        for (window in plan.cycleWindows) {
            val end = window.last + 1
            if (end <= position()) continue
            if (window.first > position()) {
                output.stop()
                delay(window.first - position())
            }
            output.start()
            delay((end - position()).coerceAtLeast(0))
        }
        output.stop()
    }

    /**
     * A state change has ended the ring of [occurrenceId] (answered, silenced, missed, skipped). Ends the sound if that is
     * the one ringing; a different occurrence's ring is left alone. Called on the main thread.
     */
    internal fun endRing(occurrenceId: String) {
        val ring = active
        if (ring != null && ring.id == occurrenceId) {
            ring.playback?.cancel()
            ring.speech?.cancel()
            speaker.stop()
            active = null
            output.stop()
            volume.restoreSoonFor(occurrenceId)
            settle()
        } else if (occurrenceId in inFlight) {
            // Ended before its alarm had even been checked (a silence in the first moments, while the database is still
            // opening). Remember it, and stop the sound that was started ahead of the checks if nothing else is ringing.
            endedEarly += occurrenceId
            if (active == null) output.stop()
        }
    }

    private fun shutDown() {
        active?.playback?.cancel()
        active?.speech?.cancel()
        active = null
        output.stop()
        speaker.stop()
        speaker.release()
        volume.restoreAnySoon()
        releaseHolds()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        // Only stops the service if no newer start has arrived since: a delivery that raced this must not be cut off.
        stopSelf(lastStartId)
    }

    private fun holdWakeLock() {
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "command:ring")
                ?.apply { acquire(MAX_RING_MS + 5_000) } // bounded: never longer than the longest possible plan
        }
        // The service holds the CPU now, so the receiver's short hold has done its job.
        ReceiverWakeLock.release()
    }

    private fun requestFocus() {
        if (focus != null) return
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).build()
            runCatching { audio.requestAudioFocus(focus!!) }
        }
    }

    private fun releaseHolds() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        ReceiverWakeLock.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus?.let { f -> runCatching { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(f) } }
        }
        focus = null
    }

    companion object {
        /**
         * The foreground service type passed to the platform. Media playback is the honest description of an alarm tone and
         * needs no special permission; the manifest also declares systemExempted (for holders of an exact-alarm permission)
         * so the two can be swapped here if the emulator and devices show one behaving better on Android 15 and later.
         */
        @SuppressLint("InlinedApi") // a compile-time constant (2); platforms before API 29 ignore the type argument
        const val FGS_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

        const val MAX_RING_MS = 10 * 60_000L
        private const val FALLBACK_ID = 1
        private const val TAG = "ReveilleService"
    }
}
