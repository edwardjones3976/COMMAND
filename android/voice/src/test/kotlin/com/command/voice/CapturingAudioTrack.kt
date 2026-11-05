package com.command.voice

import android.media.AudioTrack
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAudioTrack
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Robolectric doesn't implement 16-bit writes, so a static AudioTrack never leaves STATE_NO_STATIC_DATA and
 * play() throws. Accepting the write lets AudioTrack's own Java code mark the track initialized, and records
 * every buffer the engine plays (cue tones and mastered lines) so tests can measure what the listener hears.
 */
@Implements(AudioTrack::class)
class CapturingAudioTrack : ShadowAudioTrack() {

    @RealObject
    private lateinit var track: AudioTrack

    @Implementation
    protected fun native_write_short(
        audioData: ShortArray,
        offsetInShorts: Int,
        sizeInShorts: Int,
        format: Int,
        isBlocking: Boolean
    ): Int {
        writes += Write(track.sampleRate, audioData.copyOfRange(offsetInShorts, offsetInShorts + sizeInShorts))
        return sizeInShorts
    }

    class Write(val sampleRate: Int, val pcm: ShortArray)

    companion object {
        val writes = CopyOnWriteArrayList<Write>()
    }
}
