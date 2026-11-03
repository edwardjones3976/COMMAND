package com.command.alarm

import android.media.AudioManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

/**
 * Robolectric lets any app set any volume. Android does not: with Do Not Disturb on, changing the alarm stream can be refused
 * with a SecurityException, so a test decides whether the platform refuses.
 */
@Implements(AudioManager::class)
class VolumeRefusalShadow : ShadowAudioManager() {

    @Implementation
    override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
        if (refuse) throw SecurityException("Not allowed to change Do Not Disturb state")
        super.setStreamVolume(streamType, index, flags)
    }

    companion object {
        @JvmStatic
        var refuse = false
    }
}
