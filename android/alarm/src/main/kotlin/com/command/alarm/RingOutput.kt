package com.command.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
 */
class AndroidRingOutput @Inject constructor(
    @ApplicationContext private val context: Context,
) : RingOutput {

    private var player: MediaPlayer? = null
    private var ducked = false

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @Synchronized
    override fun start() {
        if (player != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        player = runCatching {
            MediaPlayer.create(context, R.raw.reveille_tone, attributes, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
                ?.apply {
                    isLooping = true
                    if (ducked) setVolume(DUCKED_LEVEL, DUCKED_LEVEL)
                    start()
                }
        }.getOrNull()
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
        val level = if (ducked) DUCKED_LEVEL else 1f
        player?.let { runCatching { it.setVolume(level, level) } }
    }

    @Synchronized
    override fun stop() {
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
    }
}
