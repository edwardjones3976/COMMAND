package com.command.alarm

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.command.data.ConsentStore
import com.command.data.OccurrenceRepository
import com.command.data.ReconcileTrigger
import com.command.data.db.CommandDb
import com.command.domain.model.ConsentKey
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.testing.FakeAlarmScheduler
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The reconcile puts back a raised alarm volume first and re-arms the alarms after it. The volume is best effort; the alarms are not: whatever
 * the platform's volume call throws, the alarms are re-armed (amendment 39).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34], shadows = [VolumeRefusalShadow::class])
class ReconcileOutlivesVolumeTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var reconciler: ArmingReconciler
    @Inject lateinit var policy: AlarmVolumePolicy
    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var consents: ConsentStore
    @Inject lateinit var scheduler: FakeAlarmScheduler

    @Before
    fun setUp() {
        VolumeRefusalShadow.broken = null
        hilt.inject()
        ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
            .setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
    }

    @After
    fun tearDown() {
        VolumeRefusalShadow.broken = null
    }

    @Test
    fun `a volume call that throws something unexpected does not stop the reconcile re-arming the alarms`() {
        runBlocking { consents.grant(ConsentKey.VOLUME_CONTROL) }
        val mission = testMission().copy(startAtUtc = FakeAlarmModule.T0 + 3_600_000) // an hour ahead, so the reconcile keeps it
        runBlocking { db.missions().insert(mission) }
        val occurrence = runBlocking { repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 3_600_000, "UTC").occurrence }
        runBlocking { repo.apply(occurrence.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM) }
        runBlocking { policy.raiseFor(repo.get(occurrence.id)!!) } // a ring raised it and then died: the record is left
        scheduler.armed.clear() // and a reboot took the alarm

        VolumeRefusalShadow.broken = IllegalArgumentException("something nobody expected")
        runBlocking { reconciler.run(ReconcileTrigger.BOOT) }

        assertTrue("the alarm is armed again", scheduler.armed.values.any { it.occurrenceId == occurrence.id })
    }
}
