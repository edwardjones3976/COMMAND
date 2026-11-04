package com.command.alarm

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.command.data.MissionRepository
import com.command.data.ReconcileTrigger
import com.command.testing.FakeClock
import com.command.testing.FakePauseEffects
import com.command.testing.android.awaitUntil
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Date
import javax.inject.Inject

/** The notice that stays in the shade while COMMAND is paused, and the run that is queued for the end of the pause. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PauseNoticeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val hour = 3_600_000L

    @Before
    fun setUp() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun runs() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReconcileAlarmsWorker.NAME_PAUSE_END).get()

    private fun shown() = manager.activeNotifications.filter { it.id == AlarmContract.ID_PAUSED }

    @Test
    fun `the notice says until when, names nothing private, and has a button that ends the pause`() {
        val until = 1_700_000_000_000L
        val n = PauseNotifications.notice(context, until)

        assertEquals("COMMAND is paused until ${DateFormat.getTimeFormat(context).format(Date(until))}", title(n))
        assertTrue(text(n).startsWith("Nothing rings until then."))
        assertEquals(listOf("Resume now"), n.actions.map { it.title.toString() })
        assertEquals(AlarmContract.CHANNEL_STATUS, n.channelId)
        assertEquals(Notification.CATEGORY_STATUS, n.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertTrue("it stays until the pause is over", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `it is shown without a tag and under an id no ring can have, and clearing takes it away`() {
        PauseNotifications.show(context, 1_700_000_000_000L)
        val posted = shown().single()
        assertNull(posted.tag)
        assertTrue("a ring uses its occurrence's request code, which counts up from 1", posted.id < 0)

        PauseNotifications.clear(context)
        assertTrue(shown().isEmpty())
    }

    @Test
    fun `with notifications off nothing is shown and nothing goes wrong`() {
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        PauseNotifications.show(context, 1_700_000_000_000L)
        assertTrue(shown().isEmpty())
        PauseNotifications.clear(context)
    }

    @Test
    fun `a pause queues one run for its end, a later pause replaces it, and it is tagged for what it is`() {
        val effects = AndroidPauseEffects(context, FakeClock(nowMs = 5 * hour))

        effects.started(5 * hour + 4 * hour)
        val first = runs().single()
        assertEquals(4 * hour, first.initialDelayMillis)
        assertTrue(ReconcileScheduler.tagFor(ReconcileTrigger.PAUSE_ENDED) in first.tags)
        assertNotNull(shown().singleOrNull())

        effects.started(5 * hour + 8 * hour)
        val second = runs().single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(8 * hour, second.initialDelayMillis)
    }

    @Test
    fun `ending the pause early takes the queued run and the notice away, and running out leaves the run alone`() {
        val effects = AndroidPauseEffects(context, FakeClock(nowMs = 0))
        effects.started(2 * hour)

        effects.expired()
        assertTrue("the notice goes", shown().isEmpty())
        assertEquals("the run that is reporting the end must not be cancelled from under itself", WorkInfo.State.ENQUEUED, runs().single().state)

        effects.started(2 * hour)
        effects.resumed()
        assertTrue(shown().isEmpty())
        assertTrue(runs().all { it.state == WorkInfo.State.CANCELLED })
    }
}

/** The Resume button of the notice, through the real object graph. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class PauseResumeReceiverTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var missions: MissionRepository
    @Inject lateinit var effects: FakePauseEffects

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hilt.inject()
    }

    private fun pause() = runBlocking { missions.observePause().first() }

    @Test
    fun `tapping Resume ends the pause`() {
        runBlocking { missions.pause(3_600_000L) }
        assertNotNull(pause())

        PauseResumeReceiver().onReceive(context, Intent(AlarmContract.ACTION_RESUME_PAUSE))

        awaitUntil(message = "the pause to end") { pause() == null }
        // The pause is forgotten before the platform is told, so the second thing is waited for too.
        awaitUntil(message = "the platform to be told") { effects.resumptions == 1 }
        assertEquals(1, effects.resumptions)
    }

    @Test
    fun `any other action does nothing`() {
        runBlocking { missions.pause(3_600_000L) }

        PauseResumeReceiver().onReceive(context, Intent("com.example.SOMETHING"))
        PauseResumeReceiver().onReceive(context, Intent(Intent.ACTION_BATTERY_LOW))

        // Negative: wait as long as a resume would need, with the main looper moving.
        val end = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < end) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertNotNull(pause())
        assertEquals(0, effects.resumptions)
    }
}
