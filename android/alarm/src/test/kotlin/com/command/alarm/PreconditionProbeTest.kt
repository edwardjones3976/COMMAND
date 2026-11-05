package com.command.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.PreconditionValue
import com.command.domain.reliability.Severity
import com.command.testing.FakeAlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The probe only reads. These tests change the platform's state and check that what it reports follows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [FullScreenIntentShadow::class])
class PreconditionProbeTest {

    private lateinit var context: Context
    private val scheduler = FakeAlarmScheduler()
    private lateinit var probe: PreconditionProbe

    @Before
    fun setUp() {
        FullScreenIntentShadow.allowed = true
        context = ApplicationProvider.getApplicationContext()
        probe = PreconditionProbe(context, scheduler)
    }

    private fun read(p: Precondition) = probe.read().values.getValue(p)

    @Test
    fun `it reports every precondition, and a plain install is healthy where the app can make it so`() {
        val snapshot = probe.read()
        assertEquals(Precondition.values().toSet(), snapshot.values.keys)
        for (p in listOf(Precondition.EXACT_ALARMS, Precondition.NOTIFICATIONS, Precondition.FULL_SCREEN_INTENT, Precondition.ALARM_CHANNEL)) {
            assertEquals(p.name, Severity.OK, p.severity(snapshot.values.getValue(p)))
        }
    }

    @Test
    fun `the full-screen permission of Android 14 is read from the system, in both directions`() {
        assertEquals(PreconditionValue.GRANTED, read(Precondition.FULL_SCREEN_INTENT))
        FullScreenIntentShadow.allowed = false
        assertEquals(PreconditionValue.DENIED, read(Precondition.FULL_SCREEN_INTENT))
        assertEquals(Severity.PROBLEM, Precondition.FULL_SCREEN_INTENT.severity(read(Precondition.FULL_SCREEN_INTENT)))
    }

    @Test
    fun `exact alarms follow what the scheduler is allowed to do`() {
        assertEquals(PreconditionValue.GRANTED, read(Precondition.EXACT_ALARMS))
        scheduler.exactAllowed = false
        assertEquals(PreconditionValue.DENIED, read(Precondition.EXACT_ALARMS))
    }

    @Test
    fun `notifications turned off are reported as denied`() {
        assertEquals(PreconditionValue.GRANTED, read(Precondition.NOTIFICATIONS))
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertEquals(PreconditionValue.DENIED, read(Precondition.NOTIFICATIONS))
    }

    @Test
    fun `the ring channel is read at its real importance, and created first if it is missing`() {
        assertEquals("HIGH", read(Precondition.ALARM_CHANNEL))
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(AlarmContract.CHANNEL_ALARM)
        manager.createNotificationChannel(NotificationChannel(AlarmContract.CHANNEL_ALARM, "Ring", NotificationManager.IMPORTANCE_LOW))
        assertEquals("LOW", read(Precondition.ALARM_CHANNEL))
        assertEquals(Severity.PROBLEM, Precondition.ALARM_CHANNEL.severity(read(Precondition.ALARM_CHANNEL)))
    }

    @Test
    fun `a silent alarm stream is reported as muted, and reading it changes nothing`() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        assertEquals(PreconditionValue.AUDIBLE, read(Precondition.ALARM_VOLUME))
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)
        assertEquals(PreconditionValue.MUTED, read(Precondition.ALARM_VOLUME))
        assertEquals("the volume is untouched", 0, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun `battery optimisation exemption is reported`() {
        assertEquals(PreconditionValue.OPTIMIZED, read(Precondition.BATTERY_OPTIMIZATION))
        shadowOf(context.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, true)
        assertEquals(PreconditionValue.EXEMPT, read(Precondition.BATTERY_OPTIMIZATION))
    }

    @Test
    fun `the standby bucket is named, and a rarely used app is a warning`() {
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_ACTIVE)
        assertEquals("ACTIVE", read(Precondition.STANDBY_BUCKET))
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE)
        assertEquals("RARE", read(Precondition.STANDBY_BUCKET))
        assertEquals(Severity.WARNING, Precondition.STANDBY_BUCKET.severity(read(Precondition.STANDBY_BUCKET)))
    }

    @Test
    fun `background restriction is read without throwing`() {
        assertNotNull(read(Precondition.BACKGROUND_RESTRICTED))
    }

    @Test
    @Config(sdk = [26])
    fun `on an old Android that has no such restrictions, it reports them as fine`() {
        assertEquals(PreconditionValue.GRANTED, read(Precondition.FULL_SCREEN_INTENT))
        assertEquals(PreconditionValue.NO, read(Precondition.BACKGROUND_RESTRICTED))
        assertEquals("ACTIVE", read(Precondition.STANDBY_BUCKET))
    }
}
