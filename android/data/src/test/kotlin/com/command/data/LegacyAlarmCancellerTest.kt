package com.command.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.SettingRow
import com.command.domain.model.SettingKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyAlarmCancellerTest {

    private lateinit var context: Context
    private lateinit var f: Fixture
    private lateinit var canceller: LegacyAlarmCanceller

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        f = Fixture(context)
        canceller = LegacyAlarmCanceller(context, f.db)
    }

    @After
    fun tearDown() = f.close()

    private val alarms: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    private val inAnHour: Long get() = System.currentTimeMillis() + 3_600_000L

    /** An alarm exactly as the previous version made it: its receiver class, the mission's old row id as request code. */
    private fun legacyMissionAlarm(missionId: Long) {
        val intent = Intent().setComponent(ComponentName(context.packageName, LegacyAlarmCanceller.MISSION_RECEIVER))
        val pending = PendingIntent.getBroadcast(context, missionId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(inAnHour, pending), pending)
    }

    /** The previous version's repeating calendar wake-up. */
    private fun legacyCalendarAlarm() {
        val intent = Intent().setComponent(ComponentName(context.packageName, LegacyAlarmCanceller.CALENDAR_RECEIVER))
            .setAction(LegacyAlarmCanceller.CALENDAR_ACTION)
        val pending = PendingIntent.getBroadcast(context, LegacyAlarmCanceller.CALENDAR_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.set(AlarmManager.RTC_WAKEUP, inAnHour, pending)
    }

    private fun legacyDbWithMissions(vararg ids: Long) {
        val legacy = LegacyDb.create(context, wal = false)
        for (id in ids) legacy.insert("missions", "id" to id, "title" to "Old mission $id", "firstActionPrompt" to "Go", "scheduledEpochMs" to inAnHour)
        legacy.close()
    }

    @Test
    fun `it cancels the mission alarms and the calendar wake-up of the previous version, once`() = runBlocking {
        legacyDbWithMissions(7, 12)
        legacyMissionAlarm(7)
        legacyMissionAlarm(12)
        legacyCalendarAlarm()
        assertEquals(3, shadowOf(alarms).scheduledAlarms.size)

        assertEquals(3, canceller.cancelIfNeeded())

        assertTrue("nothing of the old version is left in the system's list", shadowOf(alarms).scheduledAlarms.isEmpty())
        assertEquals("true", f.db.settings().get(SettingKeys.LEGACY_ALARMS_CANCELLED))
        assertNull("and it does not run twice", canceller.cancelIfNeeded())
    }

    @Test
    fun `it leaves alarms that are not the previous version's alone`() = runBlocking {
        legacyDbWithMissions(7)
        legacyMissionAlarm(7)
        // An alarm of this version: a different component and a data URI.
        val own = PendingIntent.getBroadcast(
            context, 7, Intent(context, javaClass).setData(android.net.Uri.parse("command://occurrence/abc")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(inAnHour, own), own)

        assertEquals(1, canceller.cancelIfNeeded())
        assertEquals("this version's alarm with the same request code is untouched", 1, shadowOf(alarms).scheduledAlarms.size)
    }

    @Test
    fun `with no old database it still takes down the calendar wake-up`() = runBlocking {
        legacyCalendarAlarm()
        assertEquals(1, canceller.cancelIfNeeded())
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test
    fun `an old database that cannot be read never stops the app, is left as it was, and is looked at again next time`() = runBlocking {
        val file: File = context.getDatabasePath(LegacyImporter.LEGACY_DB_NAME)
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(2048) { (it % 251).toByte() })
        val before = file.readBytes()
        legacyCalendarAlarm()

        assertEquals("the calendar wake-up needs no database", 1, canceller.cancelIfNeeded())

        assertTrue("the user's old file is untouched", before.contentEquals(file.readBytes()))
        assertNull("it could not look, so it is not finished", f.db.settings().get(SettingKeys.LEGACY_ALARMS_CANCELLED))

        // The file becomes readable (say, restored); the next start finishes the job.
        legacyDbWithMissions(7)
        legacyMissionAlarm(7)
        assertEquals(1, canceller.cancelIfNeeded())
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        assertEquals("true", f.db.settings().get(SettingKeys.LEGACY_ALARMS_CANCELLED))
    }

    @Test
    fun `the old notification channel is removed so settings show no dead entry`() = runBlocking {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(LegacyAlarmCanceller.CHANNEL_ID, "Reveille", NotificationManager.IMPORTANCE_HIGH))
        assertNotNull(manager.getNotificationChannel(LegacyAlarmCanceller.CHANNEL_ID))

        canceller.cancelIfNeeded()

        assertNull(manager.getNotificationChannel(LegacyAlarmCanceller.CHANNEL_ID))
    }

    @Test
    fun `a finished run is remembered across starts`() = runBlocking {
        f.db.settings().put(SettingRow(SettingKeys.LEGACY_ALARMS_CANCELLED, "true"))
        legacyCalendarAlarm()
        assertNull(canceller.cancelIfNeeded())
        assertEquals("nothing was cancelled the second time", 1, shadowOf(alarms).scheduledAlarms.size)
    }
}
