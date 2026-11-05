package com.command.testing.android

import android.content.Context
import com.command.alarm.AlarmBindings
import com.command.alarm.PendingPorts
import com.command.alarm.RingOutput
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.command.data.ConsentRevocationListener
import com.command.data.DataModule
import com.command.data.NoOpConsentRevocationListener
import com.command.data.db.CommandDb
import com.command.data.db.MissionRow
import com.command.domain.alarm.AlarmScheduler
import com.command.domain.alarm.CheckInPoster
import com.command.domain.alarm.RingController
import com.command.domain.alarm.WindowRoller
import com.command.domain.alarm.WorkEnqueuer
import com.command.domain.dials.Flexibility
import com.command.domain.model.MissionSource
import com.command.domain.model.StartKind
import com.command.domain.model.ZoneMode
import com.command.domain.time.Clock
import com.command.testing.FakeAlarmScheduler
import com.command.testing.FakeClock
import com.command.testing.FakeRingController
import com.command.testing.FakeWorkEnqueuer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Singleton

class FakeRingOutput : RingOutput {
    var starts = 0
    var stops = 0
    var ringing = false
        private set

    override fun start() { starts++; ringing = true }
    override fun stop() { stops++; ringing = false }
}

class RecordingPoster : CheckInPoster {
    val posted = ArrayList<Triple<String, Int, Int>>()
    override fun post(occurrenceId: String, requestCode: Int, index: Int) { posted += Triple(occurrenceId, requestCode, index) }
}

/** Replaces every real binding with a fake, so the service, receivers and repository run against an in-memory database. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class, AlarmBindings::class, PendingPorts::class])
object FakeAlarmModule {
    /** Monday 2026-11-02 09:00 UTC. */
    val T0: Long = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    @Provides @Singleton fun fakeClock(): FakeClock = FakeClock(nowMs = T0, elapsedMs = 5_000)
    @Provides @Singleton fun clock(fake: FakeClock): Clock = fake
    @Provides @Singleton fun database(): CommandDb = CommandDb.inMemory(ApplicationProvider.getApplicationContext<Context>())
    @Provides @Singleton fun consent(): ConsentRevocationListener = NoOpConsentRevocationListener
    @Provides @Singleton fun fakeScheduler(): FakeAlarmScheduler = FakeAlarmScheduler()
    @Provides @Singleton fun scheduler(fake: FakeAlarmScheduler): AlarmScheduler = fake
    @Provides @Singleton fun fakeRinger(): FakeRingController = FakeRingController()
    @Provides @Singleton fun ringController(fake: FakeRingController): RingController = fake
    @Provides @Singleton fun fakeOutput(): FakeRingOutput = FakeRingOutput()
    @Provides @Singleton fun output(fake: FakeRingOutput): RingOutput = fake
    @Provides @Singleton fun fakeWork(): FakeWorkEnqueuer = FakeWorkEnqueuer()
    @Provides @Singleton fun work(fake: FakeWorkEnqueuer): WorkEnqueuer = fake
    @Provides @Singleton fun poster(): RecordingPoster = RecordingPoster()
    @Provides @Singleton fun checkInPoster(fake: RecordingPoster): CheckInPoster = fake
    @Provides @Singleton fun roller(): WindowRoller = WindowRoller { }
}

fun testMission(title: String = "Write the report", profileId: String? = null): MissionRow = MissionRow(
    id = UUID.randomUUID().toString(), title = title, firstAction = "Open the document", category = "Work", priority = 2,
    flexibility = Flexibility.FLEXIBLE, rigidWeekdays = null, source = MissionSource.MANUAL, startKind = StartKind.ONE_OFF,
    startAtUtc = FakeAlarmModule.T0, dtstartLocal = null, rrule = null, zoneMode = ZoneMode.DEVICE, zoneId = null,
    durationMin = 30, starterMinutes = 5, profileId = profileId, lockPolicy = null, launcherPackage = null,
    firstActionVariantsJson = "[]", enabled = true, deletedAt = null, syncRev = 0, syncDirty = false,
)

/** Lets coroutines that resume on the main looper make progress while the test waits for [condition]. */
fun awaitUntil(timeoutMs: Long = 10_000, message: String = "condition", condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        shadowOf(Looper.getMainLooper()).idle()
        if (System.currentTimeMillis() > end) fail("timed out waiting for $message")
        Thread.sleep(5)
    }
}

