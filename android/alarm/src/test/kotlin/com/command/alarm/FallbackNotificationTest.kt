package com.command.alarm

import android.app.Notification
import android.content.Context
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fallback ring is the alarm when the service cannot start, and its sound belongs to the notification. From Android 8
 * the channel carries it; before that there are no channels, so the notification itself must.
 */
@RunWith(RobolectricTestRunner::class)
class FallbackNotificationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun fallback(): Notification = RingNotifications.ring(context, "occurrence", 7, "Standup", fallback = true, timeoutMs = 60_000)

    @Test
    @Config(sdk = [24, 25])
    fun `before Android 8 the fallback notification carries the alarm sound and vibration itself`() {
        val n = fallback()
        @Suppress("DEPRECATION")
        run {
            assertNotNull("a sound", n.sound)
            assertEquals("on the alarm stream", AudioAttributes.USAGE_ALARM, n.audioAttributes.usage)
            assertNotNull("a vibration", n.vibrate)
        }
        assertTrue(n.flags and Notification.FLAG_INSISTENT != 0)
    }

    @Test
    @Config(sdk = [26, 36])
    fun `from Android 8 the channel carries the sound and the notification does not repeat it`() {
        val n = fallback()
        @Suppress("DEPRECATION")
        assertNull(n.sound)
        assertEquals(AlarmContract.CHANNEL_FALLBACK, n.channelId)
        assertTrue(n.flags and Notification.FLAG_INSISTENT != 0)
    }

    @Test
    @Config(sdk = [24, 36])
    fun `the ordinary ring notification never carries a sound of its own`() {
        val n = RingNotifications.ring(context, "occurrence", 7, "Standup")
        @Suppress("DEPRECATION")
        assertNull("the service plays the sound", n.sound)
    }
}
