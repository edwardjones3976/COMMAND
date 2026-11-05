package com.command.startup

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.command.alarm.AlarmContract
import com.command.alarm.ReconcileAlarmsWorker
import com.command.data.MissionDraft
import com.command.data.MissionRepository
import com.command.data.SaveResult
import com.command.data.ScheduleDraft
import com.command.data.db.CommandDb
import com.command.domain.dials.Flexibility
import com.command.domain.model.SettingKeys
import com.command.testing.FakeAlarmScheduler
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class AppStartupTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var startup: AppStartup
    @Inject lateinit var missions: MissionRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var scheduler: FakeAlarmScheduler

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `opening the app sets everything up, puts back dropped alarms, and is safe to repeat`() = runBlocking {
        val id = (missions.save(
            MissionDraft(
                title = "Standup", firstAction = "Open the notes", flexibility = Flexibility.FLEXIBLE,
                schedule = ScheduleDraft.Once(LocalDateTime.of(2026, 11, 2, 11, 0)),
            ),
        ) as SaveResult.Saved).missionId
        scheduler.armed.clear() // a reboot dropped every alarm

        startup.run()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull("the ring channel exists", manager.getNotificationChannel(AlarmContract.CHANNEL_ALARM))
        assertEquals("the previous version's data was looked for, once", "true", db.settings().get(SettingKeys.LEGACY_IMPORT_DONE))
        assertEquals("and its dead alarms were taken down, once", "true", db.settings().get(SettingKeys.LEGACY_ALARMS_CANCELLED))
        assertTrue("the alarm is back", scheduler.armed.keys.any { it.startsWith("command://occurrence/") })
        assertNotNull("what the platform allows was written down", db.settings().get(SettingKeys.PERMISSION_SNAPSHOT))
        assertEquals("the six-hourly backstop is queued", 1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReconcileAlarmsWorker.NAME_PERIODIC).get().size)

        startup.run()
        assertEquals("nothing was duplicated", 1, db.occurrences().forMission(id).size)
        assertEquals(1, db.missions().count())
    }
}
