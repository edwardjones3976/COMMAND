package com.command.alarm

import android.media.AudioManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

/**
 * Robolectric lets any app set any volume. Android does not: with Do Not Disturb on, changing the alarm stream can be refused
 * with a SecurityException, and an app without the privileged audio permission cannot go below a floor of the platform's own, so a test
 * decides whether the platform refuses and where its floor is.
 */
@Implements(AudioManager::class)
class VolumeRefusalShadow : ShadowAudioManager() {

    @Implementation
    override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
        if (refuse) throw SecurityException("Not allowed to change Do Not Disturb state")
        // Android 17's background-audio hardening drops a volume change from outside a foreground service without an error (measured on API 37).
        if (ignore) return
        // ... and with `cmd audio set-hardening throw` it throws instead (measured on API 37: the BOOT reconcile then failed and was retried).
        if (hardeningThrows) throw IllegalStateException("AudioHardening volume control for api 100 ignored")
        broken?.let { throw it }
        super.setStreamVolume(streamType, maxOf(index, floor), flags)
    }

    companion object {
        @JvmStatic
        var refuse = false

        @JvmStatic
        var floor = 0

        @JvmStatic
        var ignore = false

        @JvmStatic
        var hardeningThrows = false

        /** Something nobody expected, thrown by the platform's volume call. */
        @JvmStatic
        var broken: RuntimeException? = null
    }
}
