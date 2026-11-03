package com.command.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class NotificationIdsTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        RingNotifications.ensureChannels(context)
    }

    private fun titles(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun actionTitles(n: Notification) = n.actions.map { it.title.toString() }

    @Test
    fun `there are four channels and only the alarm ones are loud`() {
        val byId: Map<String, NotificationChannel> = manager.notificationChannels.associateBy { it.id }
        assertEquals(setOf("reveille_alarm", "reveille_fallback", "check_in", "status"), byId.keys)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, byId.getValue("reveille_alarm").importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, byId.getValue("reveille_fallback").importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, byId.getValue("check_in").importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, byId.getValue("status").importance)
    }

    @Test
    fun `the main ring channel is silent because the service plays the sound`() {
        val channel = manager.getNotificationChannel("reveille_alarm")
        assertNull(channel.sound)
        assertEquals(false, channel.shouldVibrate())
    }

    @Test
    fun `the fallback channel carries the alarm sound on the alarm stream`() {
        val channel = manager.getNotificationChannel("reveille_fallback")
        assertNotNull(channel.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
        assertTrue(channel.shouldVibrate())
    }

    @Test
    fun `the ring notification is a full-screen alarm with open and safety silence`() {
        val n = RingNotifications.ring(context, "occ", 42, "Write the report")
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertEquals(AlarmContract.CHANNEL_ALARM, n.channelId)
        assertNotNull(n.fullScreenIntent)
        assertNotNull("swiping it away must reach the silence receiver", n.deleteIntent)
        assertEquals(listOf("Open", "Safety silence"), actionTitles(n))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Write the report", titles(n))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
    }

    @Test
    fun `the lock screen version hides the title but keeps the same two actions`() {
        val n = RingNotifications.ring(context, "occ", 42, "Secret Project Plan")
        val public = n.publicVersion
        assertNotNull(public)
        assertEquals(Notification.VISIBILITY_PUBLIC, public.visibility)
        assertEquals("Reveille", titles(public))
        assertEquals(actionTitles(n), actionTitles(public))
        assertTrue("Secret Project Plan" !in titles(public))
        assertNotNull(public.actions[1].actionIntent)
    }

    @Test
    fun `the fallback ring insists, belongs to the fallback channel and can time itself out`() {
        val n = RingNotifications.ring(context, "occ", 42, null, fallback = true, timeoutMs = 90_000)
        assertEquals(AlarmContract.CHANNEL_FALLBACK, n.channelId)
        assertTrue(n.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(90_000L, n.timeoutAfter)
        assertEquals("Reveille", titles(n))
    }

    @Test
    fun `open, silence and dismissed are three different pending intents`() {
        val n = RingNotifications.ring(context, "occ", 42, "x")
        val open = shadowOf(n.contentIntent).savedIntent
        val silence = shadowOf(n.actions[1].actionIntent).savedIntent
        val dismissed = shadowOf(n.deleteIntent).savedIntent
        assertEquals(AlarmContract.ACTION_OPEN, open.action)
        assertEquals(AlarmContract.ACTION_SILENCE, silence.action)
        assertEquals(AlarmContract.ACTION_DISMISSED, dismissed.action)
        assertEquals(3, setOf(open.dataString, silence.dataString, dismissed.dataString).size)
        assertEquals(AlarmContract.ACTIVITY_CLASS, open.component!!.className)
        assertEquals("occ", open.getStringExtra(AlarmContract.EXTRA_OCCURRENCE_ID))
        assertEquals(SilenceReceiver::class.java.name, silence.component!!.className)
        assertEquals(SilenceReceiver::class.java.name, dismissed.component!!.className)
    }

    @Test
    fun `different occurrences never share a pending intent`() {
        val a = RingNotifications.ring(context, "occ-a", 1, "x")
        val b = RingNotifications.ring(context, "occ-b", 2, "x")
        assertNotEquals(shadowOf(a.contentIntent).savedIntent.dataString, shadowOf(b.contentIntent).savedIntent.dataString)
    }

    @Test
    fun `the missed note is quiet, hides its details when locked and offers open`() {
        val n = RingNotifications.missed(context, "occ", 42)
        assertEquals(AlarmContract.CHANNEL_STATUS, n.channelId)
        assertEquals(listOf("Open"), actionTitles(n))
        assertNotNull(n.publicVersion)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT == 0)
    }

    @Test
    fun `notification ids other than the ring are fixed and distinct from request codes`() {
        assertEquals(setOf(2, 3, 4), setOf(AlarmContract.ID_CHECK_IN, AlarmContract.ID_MISSED, AlarmContract.ID_FALLBACK))
        // Request codes start at 1 and count up, so a ring id could equal 2 or 3, but those ids are posted with a tag (the
        // occurrence id), which keeps them in a separate namespace from untagged ring notifications.
        assertTrue(AlarmContract.ID_CHECK_IN != AlarmContract.ID_MISSED)
    }

    @Test
    fun `the notification of the reconcile worker can never replace a ring, whose id is its occurrence's request code`() {
        assertTrue("request codes count up from 1", AlarmContract.ID_RECONCILE < 0)
    }
}
