package com.command.voice

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.command.domain.voice.RoutePolicy
import com.command.domain.voice.SpeechUsage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays a rendered line through an AudioTrack. For an alarm the track carries the alarm usage, so the alarm volume governs it and the media
 * volume does not; a preview is ordinary media. The whole line is in memory, so playback is a static track that is watched until its last
 * frame has been played.
 *
 * The alarm stream is played on the speaker as well as on a headset that is plugged in, so a line that must stay private is pinned to a
 * headset ([HeadsetDevices.playbackTarget]) before it is made audible, and is cut off at once if the platform ever moves it elsewhere, as it
 * does when the headset is pulled out. With no headset to pin it to, nothing is played. This needs a real output device, so it is checked
 * on a phone rather than by a unit test.
 */
class AudioTrackPlayer(private val audio: AudioManager?) : PcmPlayer {

    @Volatile private var current: AudioTrack? = null

    private fun outputs(): List<AudioDeviceInfo> =
        runCatching { audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() }.getOrNull().orEmpty()

    @Suppress("DEPRECATION") // the routing listener that exists on every version this app runs on
    override suspend fun play(pcm: ShortArray, sampleRate: Int, usage: SpeechUsage, route: RoutePolicy): Long {
        if (pcm.isEmpty()) return 0
        val target = if (route == RoutePolicy.HEADSET_ONLY) HeadsetDevices.playbackTarget(outputs()) ?: throw RouteUnavailableException() else null
        val attributes = AudioAttributes.Builder()
            .setUsage(if (usage == SpeechUsage.ALARM) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        current = track
        val lostHeadset = AtomicBoolean(false)
        val listener = AudioTrack.OnRoutingChangedListener { changed ->
            // The sound was moved off the headset (it was pulled out, say): it stops this instant.
            val routed = changed.routedDevice
            if (target != null && routed != null && !HeadsetDevices.isHeadset(routed)) {
                lostHeadset.set(true)
                runCatching { changed.pause() }
            }
        }
        try {
            if (target != null) {
                if (!track.setPreferredDevice(target)) throw RouteUnavailableException()
                track.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper()))
            }
            if (track.write(pcm, 0, pcm.size) != pcm.size) error("the audio did not fit in its buffer")
            track.play()
            while (currentCoroutineContext().isActive && track.playState == AudioTrack.PLAYSTATE_PLAYING && track.playbackHeadPosition < pcm.size) {
                delay(POLL_MS)
            }
            if (lostHeadset.get()) throw RouteUnavailableException()
            return track.playbackHeadPosition.toLong() * 1000 / sampleRate
        } finally {
            if (target != null) runCatching { track.removeOnRoutingChangedListener(listener) }
            runCatching { track.stop() }
            track.release()
            if (current === track) current = null
        }
    }

    override fun stop() {
        // Pausing and flushing ends the sound at once; the play call notices that the track is no longer playing and returns.
        current?.let { runCatching { it.pause() } }
    }

    private companion object {
        const val POLL_MS = 20L
    }
}
