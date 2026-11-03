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
import androidx.core.app.ServiceCompat
import com.command.data.ApplyResult
import com.command.data.OccurrenceRepository
import com.command.data.SessionPlan
import com.command.domain.alarm.RingController
import com.command.domain.model.EventSource
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.time.Clock
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Rings one occurrence. Started by the alarm receiver with `startForegroundService`, so the first thing it does, before any
 * database work, is call `startForeground`. Then it fires the occurrence (the state machine decides whether that is legal),
 * and plays the alarm sound in the cycles the user's alert intensity prescribes, stopping at the end of the plan whatever
 * else happens. Restarted after the process died mid-ring, it works out from the stored plan whether there is any ring left.
 */
@AndroidEntryPoint
class ReveilleService : Service() {

    @Inject lateinit var repository: OccurrenceRepository
    @Inject lateinit var clock: Clock
    @Inject lateinit var output: RingOutput
    @Inject lateinit var resolver: RingModeResolver

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ringJob: Job? = null
    private var currentId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AlarmContract.ACTION_STOP -> {
                // A stop names the occurrence whose ring ended. If a different one is ringing now, it is left alone.
                val target = intent.getStringExtra(AlarmContract.EXTRA_OCCURRENCE_ID)
                if (target == null || currentId == null || target == currentId) stopRinging()
                return START_NOT_STICKY
            }
            else -> ring(intent)
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        output.stop()
        scope.cancel()
        releaseHolds()
        super.onDestroy()
    }

    private fun ring(intent: Intent?) {
        val id = AlarmContract.occurrenceId(intent)
        val code = AlarmContract.requestCode(intent)
        if (id == null || code == 0) {
            // Started with nothing to ring for. A foreground start must still be answered with startForeground.
            runCatching { ServiceCompat.startForeground(this, FALLBACK_ID, RingNotifications.ring(this, "none", FALLBACK_ID, null), FGS_TYPE) }
            stopRinging()
            return
        }
        // First: become a foreground service (a deadline of a few seconds). Everything else comes after.
        try {
            ServiceCompat.startForeground(this, code, RingNotifications.ring(this, id, code, null), FGS_TYPE)
        } catch (e: Exception) {
            // Not allowed to run as a foreground service right now. Ring from a full-screen notification instead.
            scope.launch {
                resolver.degrade(id, code, reasonOf(e))
                stopRinging()
            }
            return
        }
        currentId = id
        holdWakeLock()
        // Sound first: opening the database after a cold start takes a second or more, and an alarm should not wait for it.
        // If the database then says this alarm is stale or its plan is over, the sound is stopped again within a moment.
        output.start()
        ringJob?.cancel()
        ringJob = scope.launch { begin(id, code) }
    }

    private suspend fun begin(id: String, code: Int) {
        val fire = repository.apply(id, OccurrenceCommand.Fire, EventSource.SERVICE)
        val row = when (fire) {
            is ApplyResult.Applied -> fire.occurrence
            else -> {
                val current = repository.get(id)
                if (current?.state == OccurrenceState.FIRING) {
                    current // restarted in the middle of a ring
                } else {
                    // A stale alarm: the occurrence is not armed any more. A sub-second flash of the notification is accepted.
                    current?.let { repository.logEvent(EventType.ALARM_FIRED_STALE, it, EventSource.SERVICE, EventPayload.EMPTY) }
                    stopRinging()
                    return
                }
            }
        }

        val plan = SessionPlan.of(row)
        val now = clock.nowMs()
        if (plan == null || plan.endUtc <= now) {
            // The plan ended while nothing was alive to enforce it: record the miss and do not ring at all.
            repository.apply(id, OccurrenceCommand.Miss(MissReason.TIMEOUT), EventSource.SERVICE)
            stopRinging()
            return
        }

        val title = repository.missionTitle(row.missionId)
        RingNotifications.post(this, null, code, RingNotifications.ring(this, id, code, title))
        requestFocus()
        runCycles(plan)
        // Out of ring time and still unanswered: that is a miss. (The cycle-end alarm would do the same.)
        repository.apply(id, OccurrenceCommand.Miss(MissReason.TIMEOUT), EventSource.SERVICE)
        stopRinging()
    }

    /** Sound during each ring cycle, silence in the gaps, ending exactly when the plan does. */
    private suspend fun runCycles(plan: SessionPlan) {
        for (window in plan.cycleWindows) {
            val startAt = plan.startUtc + window.first
            val endAt = plan.startUtc + window.last + 1
            val now = clock.nowMs()
            if (endAt <= now) continue
            if (startAt > now) {
                output.stop()
                delay(startAt - now)
            }
            output.start()
            delay((endAt - clock.nowMs()).coerceAtLeast(0))
        }
        output.stop()
    }

    private fun stopRinging() {
        ringJob?.cancel()
        ringJob = null
        currentId = null
        output.stop()
        releaseHolds()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun holdWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "command:ring")
            ?.apply { acquire(MAX_RING_MS + 5_000) } // bounded: never longer than the longest possible plan
    }

    private fun requestFocus() {
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
    }
}

/** Sends the stop request to the service when a state change has ended a ring. */
class AndroidRingController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : RingController {
    override fun stop(occurrenceId: String) {
        val intent = Intent(context, ReveilleService::class.java)
            .setAction(AlarmContract.ACTION_STOP)
            .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, occurrenceId)
        // A plain start of a service that is already foreground is allowed from the background; if it is not running, ignore.
        runCatching { context.startService(intent) }
    }
}
