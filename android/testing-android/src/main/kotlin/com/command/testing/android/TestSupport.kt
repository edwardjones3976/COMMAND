package com.command.testing.android

import android.content.Context
import com.command.alarm.AlarmBindings
import com.command.alarm.AudioRoutes
import com.command.alarm.PendingPorts
import com.command.alarm.ReliabilityMonitor
import com.command.alarm.RingOutput
import com.command.alarm.VoiceProviders
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.command.data.ConsentRevocationListener
import com.command.data.DataModule
import com.command.data.NoOpConsentRevocationListener
import com.command.data.PreconditionRecorder
import com.command.data.db.CommandDb
import com.command.data.db.MissionRow
import com.command.domain.alarm.AlarmScheduler
import com.command.domain.alarm.CheckInPoster
import com.command.domain.alarm.CheckInPrompt
import com.command.domain.alarm.PauseEffects
import com.command.domain.alarm.RingController
import com.command.domain.alarm.WindowRoller
import com.command.domain.alarm.WorkEnqueuer
import com.command.domain.dials.Flexibility
import com.command.domain.model.MissionSource
import com.command.domain.model.StartKind
import com.command.domain.model.ZoneMode
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.PreconditionSnapshot
import com.command.domain.time.Clock
import com.command.domain.voice.Line
import com.command.domain.voice.RoutePolicy
import com.command.domain.voice.SpeakResult
import com.command.domain.voice.Speaker
import com.command.domain.voice.SpeechLevel
import com.command.domain.voice.SpeechUsage
import com.command.domain.voice.SpeechVoice
import com.command.testing.FakeAlarmScheduler
import com.command.testing.FakeClock
import com.command.testing.FakePauseEffects
import com.command.testing.FakeRingController
import com.command.testing.FakeWorkEnqueuer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.coroutines.resume
import javax.inject.Singleton

class FakeRingOutput : RingOutput {
    var starts = 0
    var stops = 0
    var ringing = false
        private set

    var ducked = false
        private set
    val ducks = ArrayList<Boolean>()

    override fun start() { starts++; ringing = true }
    override fun stop() { stops++; ringing = false }
    override fun duck(ducked: Boolean) { this.ducked = ducked; ducks += ducked }
}

/**
 * A phone whose settings the test controls. It starts healthy (everything allowed, the alarm stream audible) and records every
 * reading with the real recorder, so the audit log and the consent history behave as they do on a device.
 */
class FakeReliabilityMonitor(private val recorder: PreconditionRecorder) : ReliabilityMonitor {
    var snapshot: PreconditionSnapshot = healthy()
    var refreshes = 0
        private set

    override suspend fun refresh(): PreconditionSnapshot {
        refreshes++
        return snapshot.also { recorder.record(it) }
    }

    /** Changes one precondition, the way the person would in Android's settings. */
    fun set(precondition: Precondition, value: String) {
        snapshot = PreconditionSnapshot(snapshot.values + (precondition to value))
    }

    companion object {
        fun healthy() = PreconditionSnapshot(
            mapOf(
                Precondition.EXACT_ALARMS to "granted", Precondition.NOTIFICATIONS to "granted", Precondition.FULL_SCREEN_INTENT to "granted",
                Precondition.ALARM_CHANNEL to "HIGH", Precondition.CHECK_IN_CHANNEL to "HIGH", Precondition.ALARM_VOLUME to "audible", Precondition.DO_NOT_DISTURB to "free",
                Precondition.BATTERY_OPTIMIZATION to "exempt",
                Precondition.BACKGROUND_RESTRICTED to "no", Precondition.STANDBY_BUCKET to "ACTIVE",
            ),
        )
    }
}

/**
 * Says nothing and remembers what it was asked to say. The result is the test's to choose, and a line can be held mid-speech
 * ([hold]) so that a ring can end under it.
 */
class FakeSpeaker : Speaker {
    val spoken = ArrayList<Pair<Line, SpeechUsage>>()

    /** The route each line was asked to be heard on, in the order the lines were asked for. */
    val routes = ArrayList<RoutePolicy>()

    /** How loud each line was asked to be. */
    val levels = ArrayList<SpeechLevel>()

    /** Who each line was asked to be said by. */
    val voices = ArrayList<SpeechVoice>()
    var result: SpeakResult = SpeakResult.Spoken(1_000)

    /** When set, speaking throws it, as a broken engine might. */
    var failWith: Exception? = null
    var hold = false
    var stops = 0
        private set
    var releases = 0
        private set
    var prepares = 0
        private set
    private var held: CancellableContinuation<Unit>? = null

    override suspend fun speak(line: Line, usage: SpeechUsage, route: RoutePolicy, level: SpeechLevel, voice: SpeechVoice, onAudible: () -> Unit): SpeakResult {
        spoken += line to usage
        routes += route
        levels += level
        voices += voice
        failWith?.let { throw it }
        // A phone that cannot speak makes no sound, so nobody is told to make room for it.
        if (result !is SpeakResult.Unavailable) onAudible()
        if (hold) suspendCancellableCoroutine<Unit> { held = it }
        return result
    }

    /** Runs when the engine is asked to get ready, so that a test can look at what else has happened by then (and not by when it asks). */
    var onPrepare: () -> Unit = {}

    override fun prepare() {
        prepares++
        onPrepare()
    }

    override fun stop() {
        stops++
        finish()
    }

    override fun release() {
        releases++
    }

    /** Lets a held line finish. */
    fun finish() {
        held?.takeIf { it.isActive }?.resume(Unit)
        held = null
    }
}

class FakeWindowRoller : WindowRoller {
    @Volatile var rolls = 0
    override fun roll() { rolls++ }
}

class FakeAudioRoutes : AudioRoutes {
    var headset = false
    override fun headsetConnected(): Boolean = headset
    override fun describe(): String = if (headset) "speaker+wired" else "speaker"
}

class RecordingPoster : CheckInPoster {
    val posted = ArrayList<CheckInPrompt>()
    val cancelled = ArrayList<String>()
    override fun post(prompt: CheckInPrompt) { posted += prompt }
    override fun cancel(occurrenceId: String) { cancelled += occurrenceId }
}

/** Replaces every real binding with a fake, so the service, receivers and repository run against an in-memory database. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class, AlarmBindings::class, PendingPorts::class, VoiceProviders::class])
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
    @Provides @Singleton fun fakeRoller(): FakeWindowRoller = FakeWindowRoller()
    @Provides @Singleton fun roller(fake: FakeWindowRoller): WindowRoller = fake
    @Provides @Singleton fun fakePause(): FakePauseEffects = FakePauseEffects()
    @Provides @Singleton fun pauseEffects(fake: FakePauseEffects): PauseEffects = fake
    @Provides @Singleton fun fakeMonitor(recorder: PreconditionRecorder): FakeReliabilityMonitor = FakeReliabilityMonitor(recorder)
    @Provides @Singleton fun monitor(fake: FakeReliabilityMonitor): ReliabilityMonitor = fake
    @Provides @Singleton fun fakeSpeaker(): FakeSpeaker = FakeSpeaker()
    @Provides @Singleton fun speaker(fake: FakeSpeaker): Speaker = fake
    @Provides @Singleton fun fakeRoutes(): FakeAudioRoutes = FakeAudioRoutes()
    @Provides @Singleton fun routes(fake: FakeAudioRoutes): AudioRoutes = fake
}

fun testMission(title: String = "Write the report", profileId: String? = null): MissionRow = MissionRow(
    id = UUID.randomUUID().toString(), title = title, firstAction = "Open the document", category = "Work", priority = 2,
    flexibility = Flexibility.FLEXIBLE, rigidWeekdays = null, source = MissionSource.MANUAL, startKind = StartKind.ONE_OFF,
    startAtUtc = FakeAlarmModule.T0, dtstartLocal = null, rrule = null, zoneMode = ZoneMode.DEVICE, zoneId = null,
    durationMin = 30, starterMinutes = 5, profileId = profileId, lockPolicy = null, launcherPackage = null,
    firstActionVariantsJson = "[]", enabled = true, deletedAt = null, syncRev = 0, syncDirty = false,
)

/**
 * Lets coroutines that resume on the main looper make progress while the test waits for [condition]. The generous default is
 * for the first test on an Android version in a test worker, which loads a whole new sandbox and has taken twenty seconds on a
 * busy machine; a test that passes does not wait for it.
 */
fun awaitUntil(timeoutMs: Long = 45_000, message: String = "condition", condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        shadowOf(Looper.getMainLooper()).idle()
        if (System.currentTimeMillis() > end) fail("timed out waiting for $message")
        Thread.sleep(5)
    }
}

