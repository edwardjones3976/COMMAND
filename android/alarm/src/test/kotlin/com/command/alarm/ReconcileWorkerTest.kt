package com.command.alarm

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.command.data.MissionDraft
import com.command.data.MissionRepository
import com.command.data.SaveResult
import com.command.data.ScheduleDraft
import com.command.data.db.CommandDb
import com.command.domain.dials.Flexibility
import com.command.domain.model.SettingKeys
import com.command.domain.occurrence.EventType
import com.command.testing.FakeAlarmScheduler
import com.command.testing.FakeClock
import com.command.testing.android.FakeAlarmModule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
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
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34, 36])
class ReconcileWorkerTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var missions: MissionRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock
    @Inject lateinit var scheduler: FakeAlarmScheduler

    private lateinit var context: Context

    @Before
    fun setUp() {
        hilt.inject()
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun worker(trigger: String? = null, attempt: Int = 0) =
        TestListenableWorkerBuilder<ReconcileAlarmsWorker>(context)
            .apply { if (trigger != null) setInputData(workDataOf(ReconcileAlarmsWorker.KEY_TRIGGER to trigger)) }
            .setRunAttemptCount(attempt)
            .build()

    /** The fake clock stands at Monday 2026-11-02 09:00 UTC. */
    private fun saveStandup(): String = runBlocking {
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(FakeAlarmModule.T0 + 2 * 3_600_000), ZoneOffset.UTC)
        val draft = MissionDraft(title = "Standup", firstAction = "Open the notes", flexibility = Flexibility.FLEXIBLE, schedule = ScheduleDraft.Once(at))
        (missions.save(draft) as SaveResult.Saved).missionId
    }

    private fun events(type: EventType) = runBlocking { db.events().ofType(type) }

    /** A fresh Robolectric device has its alarm stream at zero, which is itself a thing worth recording; these tests are about a healthy one. */
    private fun makeAlarmAudible() = context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)

    @Test
    fun `after a boot the worker puts back the alarm the reboot dropped and says it ran`() {
        saveStandup()
        scheduler.armed.clear() // what a reboot does

        val result = runBlocking { worker("BOOT").doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("the alarm is back", scheduler.armed.keys.any { it.startsWith("command://occurrence/") })
        assertEquals(1, events(EventType.ALARM_LOST).size)
        val summary = events(EventType.BOOT_RECONCILE).single()
        assertTrue(summary.payloadJson.contains("BOOT"))
    }

    @Test
    fun `it also writes down what the platform allows, once`() {
        runBlocking { worker("BOOT").doWork() }
        assertTrue(events(EventType.PRECONDITION).isNotEmpty())
        assertNotNull(runBlocking { db.settings().get(SettingKeys.PERMISSION_SNAPSHOT) })
        val first = events(EventType.PRECONDITION).size

        runBlocking { worker("PERIODIC").doWork() }
        assertEquals("nothing changed, so nothing new is written", first, events(EventType.PRECONDITION).size)
    }

    @Test
    fun `without a trigger it runs as the periodic backstop, which is silent when nothing was wrong`() {
        makeAlarmAudible()
        saveStandup()
        val before = runBlocking { db.events().all() }.size

        val result = runBlocking { worker().doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("no summary line for a quiet periodic run", events(EventType.BOOT_RECONCILE).isEmpty())
        // The only new rows are the first reading of the platform's state.
        val added = runBlocking { db.events().all() }.drop(before).map { it.type }
        assertEquals(emptyList<EventType>(), added.filter { it != EventType.PRECONDITION })
    }

    @Test
    fun `a failure is retried a few times and then given up on`() {
        db.openHelper.writableDatabase.execSQL("DROP TABLE missions") // the next reconcile cannot read its missions
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker("BOOT", attempt = 0).doWork() })
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker("BOOT", attempt = 2).doWork() })
        assertEquals(ListenableWorker.Result.failure(), runBlocking { worker("BOOT", attempt = 3).doWork() })
    }

    @Test
    fun `an unknown trigger name is treated as the periodic run`() {
        val result = runBlocking { worker("SOMETHING_FROM_A_NEWER_VERSION").doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertNull("not a boot", events(EventType.BOOT_RECONCILE).firstOrNull())
    }

    @Test
    fun `every run makes sure the six-hourly backstop exists, even one that then gives up`() {
        fun backstops() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReconcileAlarmsWorker.NAME_PERIODIC).get()
        assertTrue("an update from a version that never installed it, before the app is opened", backstops().isEmpty())

        runBlocking { worker("PACKAGE_REPLACED").doWork() }
        assertEquals(1, backstops().size)

        db.openHelper.writableDatabase.execSQL("DROP TABLE missions")
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker("BOOT", attempt = 0).doWork() })
        assertEquals("asking again leaves the one that is there", 1, backstops().size)
    }
}
