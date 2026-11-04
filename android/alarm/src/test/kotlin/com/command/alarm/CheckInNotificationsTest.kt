package com.command.alarm

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.command.domain.alarm.CheckInPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The follow-up question as a notification: what it asks, what one tap can answer, and what a locked screen is allowed to show. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckInNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prompt(started: Boolean = false, title: String = "Write the report", id: String = "occ-1", code: Int = 7) =
        CheckInPrompt(occurrenceId = id, requestCode = code, index = 0, title = title, started = started)

    private fun titles(n: Notification) = n.actions.map { it.title.toString() }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun `the first question names the mission, asks whether it was started, and has Started, Done and Open check-in`() {
        val n = CheckInNotifications.question(context, prompt())
        assertEquals("Write the report", title(n))
        assertEquals("Have you started?", text(n))
        assertEquals(listOf("Started", "Done", "Open check-in"), titles(n))
        assertEquals(AlarmContract.CHANNEL_CHECK_IN, n.channelId)
        assertEquals(Notification.CATEGORY_REMINDER, n.category)
        assertTrue("tapping it takes it away", n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `once a start was reported the question is how it is going, and Started gives way to Still working`() {
        val n = CheckInNotifications.question(context, prompt(started = true))
        assertEquals("How is it going?", text(n))
        assertEquals(listOf("Still working", "Done", "Open check-in"), titles(n))
    }

    @Test
    fun `a mission without a title is asked about as a check-in`() {
        assertEquals("Check-in", title(CheckInNotifications.question(context, prompt(title = "   "))))
    }

    @Test
    fun `on a locked screen that hides what is private, the title goes and the same answers stay`() {
        val n = CheckInNotifications.question(context, prompt())
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion
        assertNotNull(public)
        assertEquals("the title is not on it", "Check-in", title(public))
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, public.visibility)
        assertEquals(titles(n), titles(public))
    }

    @Test
    fun `each quick answer is its own broadcast to the receiver, carrying the occurrence and what was answered`() {
        val n = CheckInNotifications.question(context, prompt(id = "occ-9", code = 12))
        val started = shadowOf(n.actions[0].actionIntent).savedIntent
        val done = shadowOf(n.actions[1].actionIntent).savedIntent
        assertEquals(CheckInAnswerReceiver::class.java.name, started.component!!.className)
        assertEquals(AlarmContract.ACTION_CHECK_IN_ANSWER, started.action)
        assertEquals("occ-9", started.getStringExtra(AlarmContract.EXTRA_OCCURRENCE_ID))
        assertEquals(12, started.getIntExtra(AlarmContract.EXTRA_REQUEST_CODE, 0))
        assertEquals("STARTED", started.getStringExtra(AlarmContract.EXTRA_ANSWER))
        assertEquals("DONE", done.getStringExtra(AlarmContract.EXTRA_ANSWER))
        assertNotEquals("the two answers cannot replace each other as pending intents", started.data, done.data)
    }

    @Test
    fun `answers of different occurrences are different pending intents`() {
        val a = shadowOf(CheckInNotifications.question(context, prompt(id = "occ-a", code = 1)).actions[0].actionIntent).savedIntent
        val b = shadowOf(CheckInNotifications.question(context, prompt(id = "occ-b", code = 2)).actions[0].actionIntent).savedIntent
        assertNotEquals(a.data, b.data)
    }

    @Test
    fun `Open check-in lands on that occurrence's questions in the app, not on the lock-screen activity`() {
        val n = CheckInNotifications.question(context, prompt(id = "occ-3", code = 5))
        val open = shadowOf(n.actions[2].actionIntent).savedIntent
        assertEquals(AlarmContract.MAIN_ACTIVITY_CLASS, open.component!!.className)
        assertEquals(AlarmContract.ACTION_OPEN_CHECK_IN, open.action)
        assertEquals("occ-3", open.getStringExtra(AlarmContract.EXTRA_OCCURRENCE_ID))
        assertEquals("tapping the notification itself does the same", open.data, shadowOf(n.contentIntent).savedIntent.data)
    }

    @Test
    fun `the poster tags a question by its occurrence, replaces the earlier one, and takes it away on request`() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val poster = AndroidCheckInPoster(context, ErasureLatch())

        poster.post(prompt(id = "occ-1"))
        poster.post(prompt(id = "occ-2", code = 8))
        assertNotNull(shadowOf(manager).getNotification("occ-1", AlarmContract.ID_CHECK_IN))
        assertNotNull(shadowOf(manager).getNotification("occ-2", AlarmContract.ID_CHECK_IN))

        poster.post(prompt(id = "occ-1", started = true))
        assertEquals("the later question replaced the earlier, it did not add to it", 2, shadowOf(manager).size())
        assertEquals("How is it going?", text(shadowOf(manager).getNotification("occ-1", AlarmContract.ID_CHECK_IN)))

        poster.cancel("occ-1")
        assertNull(shadowOf(manager).getNotification("occ-1", AlarmContract.ID_CHECK_IN))
        assertNotNull("the other occurrence's question is untouched", shadowOf(manager).getNotification("occ-2", AlarmContract.ID_CHECK_IN))
        poster.cancel("never-asked") // harmless
    }

    @Test
    fun `with notifications turned off nothing is posted and nothing breaks`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        AndroidCheckInPoster(context, ErasureLatch()).post(prompt())
        assertNull(shadowOf(manager).getNotification("occ-1", AlarmContract.ID_CHECK_IN))
    }

    @Test
    fun `a question that comes up while everything is being erased is not posted, and the title of a mission that is going is not shown`() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val sealed = ErasureLatch().also { it.seal() }

        AndroidCheckInPoster(context, sealed).post(prompt())

        assertTrue("nothing is posted behind the erase", shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `and one that the erase seals just as it is posted takes itself down again, because the erase cancels the notices once`() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        // Open at the first look, closed from the second on: the erase got in between the look and the post.
        val closesMeanwhile = object : ErasureLatch() {
            var looks = 0
            override val sealed: Boolean get() = ++looks > 1
        }

        AndroidCheckInPoster(context, closesMeanwhile).post(prompt())

        assertTrue("it was posted and then taken down, so nothing is left to sound", shadowOf(manager).allNotifications.isEmpty())
        assertTrue("and the latch was looked at after the post", closesMeanwhile.looks >= 2)
    }

    @Test
    @Config(sdk = [25])
    fun `before Android 8 there is no channel to make the question heard, so it asks for the default sound itself`() {
        val n = CheckInNotifications.question(context, prompt())
        assertTrue("sound", n.defaults and Notification.DEFAULT_SOUND != 0)
    }

    @Test
    fun `from Android 8 the channel decides how the question sounds, and the notification asks for nothing itself`() {
        assertEquals(0, CheckInNotifications.question(context, prompt()).defaults)
    }
}
