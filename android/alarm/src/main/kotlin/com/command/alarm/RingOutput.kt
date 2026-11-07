package com.command.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.VisibleForTesting
import com.command.domain.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The sound and vibration of a ring. Behind an interface so the service can be tested without a speaker. */
interface RingOutput {
    fun start()
    fun stop()

    /** Turns the tone down while a line is spoken over it, and back up afterwards. The vibration is not affected. */
    fun duck(ducked: Boolean)
}

/**
 * The alarm tone on the alarm audio stream (so the alarm volume governs it and the media volume does not), looping,
 * plus a repeating vibration. Starting twice or stopping twice is harmless.
 *
 * With gradual start on, the tone's own gain rises to full over [GradualStart.RAMP_MS] from the first sound of this output, which belongs to one
 * service: the gaps between cycles count, so a later cycle is never quieter. The stream volume is not touched, and the vibration starts at once.
 * The setting is read without the database, which a cold start may need seconds to open, and if it cannot be read the tone starts at full.
 */
class AndroidRingOutput @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: RingPreferences,
    private val clock: Clock,
) : RingOutput {

    private var player: MediaPlayer? = null
    private var ducked = false

    /** When the first sound began, on the monotonic clock, if it is to rise; null when it plays at full. */
    private var risingFrom: Long? = null
    private var firstStart = true
    private val main = Handler(Looper.getMainLooper())
    private val rise = Runnable { rise() }

    /** The tone that is playing. For tests. */
    @VisibleForTesting
    internal val tone: MediaPlayer? get() = player

    private fun rampGain(): Float = risingFrom?.let { GradualStart.gainAt(clock.elapsedRealtimeMs() - it) } ?: 1f

    /** The gain the tone should have now. A spoken line turns it down to [DUCKED_LEVEL] and never further: what is still rising below that stays where it is. */
    private fun level(): Float = minOf(rampGain(), if (ducked) DUCKED_LEVEL else 1f)

    @Synchronized
    private fun rise() {
        val p = player ?: return
        val level = level()
        runCatching { p.setVolume(level, level) }
        if (rampGain() < 1f) main.postDelayed(rise, RISE_STEP_MS)
    }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @Synchronized
    override fun start() {
        if (player != null) return
        if (firstStart) {
            firstStart = false
            if (preferences.gradualStart) risingFrom = clock.elapsedRealtimeMs()
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        player = runCatching {
            MediaPlayer.create(context, R.raw.reveille_tone, attributes, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
                ?.apply {
                    isLooping = true
                    val level = level()
                    setVolume(level, level)
                    start()
                }
        }.getOrNull()
        if (rampGain() < 1f) main.postDelayed(rise, RISE_STEP_MS)
        runCatching { vibrate(attributes) }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(attributes: AudioAttributes) {
        val v = vibrator ?: return
        // Buzz 600 ms, pause 300 ms, repeating from the second element until cancelled.
        val pattern = longArrayOf(0, 600, 300)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> v.vibrate(VibrationEffect.createWaveform(pattern, 1), attributes)
            else -> v.vibrate(pattern, 1, attributes)
        }
    }

    @Synchronized
    override fun duck(ducked: Boolean) {
        this.ducked = ducked
        val level = level()
        player?.let { runCatching { it.setVolume(level, level) } }
    }

    @Synchronized
    override fun stop() {
        main.removeCallbacks(rise)
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        runCatching { vibrator?.cancel() }
    }

    private companion object {
        /** How loud the tone is while a line is spoken over it: still there, no longer in the way. */
        const val DUCKED_LEVEL = 0.3f
        const val RISE_STEP_MS = 250L
    }
}
