package com.command.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.command.domain.voice.SpeechUsage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * Plays a rendered line through an AudioTrack. For an alarm the track carries the alarm usage, so the alarm volume governs it
 * and the media volume does not; a preview is ordinary media. The whole line is in memory, so playback is a static track that is
 * watched until its last frame has been played.
 */
class AudioTrackPlayer : PcmPlayer {

    @Volatile private var current: AudioTrack? = null

    override suspend fun play(pcm: ShortArray, sampleRate: Int, usage: SpeechUsage): Long {
        if (pcm.isEmpty()) return 0
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
        try {
            if (track.write(pcm, 0, pcm.size) != pcm.size) error("the audio did not fit in its buffer")
            track.play()
            while (currentCoroutineContext().isActive && track.playState == AudioTrack.PLAYSTATE_PLAYING && track.playbackHeadPosition < pcm.size) {
                delay(POLL_MS)
            }
            return track.playbackHeadPosition.toLong() * 1000 / sampleRate
        } finally {
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
