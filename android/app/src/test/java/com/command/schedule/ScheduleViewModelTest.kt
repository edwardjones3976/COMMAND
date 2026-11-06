package com.command.schedule

import com.command.data.MissionDraft
import com.command.data.MissionRepository
import com.command.data.OccurrenceRepository
import com.command.data.SaveResult
import com.command.data.ScheduleDraft
import com.command.domain.dials.Flexibility
import com.command.domain.model.EventSource
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.reliability.Precondition
import com.command.testing.android.FakeReliabilityMonitor
import com.command.testing.android.awaitUntil
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class ScheduleViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var missions: MissionRepository
    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var monitor: FakeReliabilityMonitor
    @Inject lateinit var clock: com.command.testing.FakeClock

    private lateinit var vm: ScheduleViewModel
    private lateinit var collector: Job

    @Before
    fun setUp() {
        hilt.inject()
        vm = ScheduleViewModel(missions, monitor, clock)
        // The state is only live while someone is collecting it, as on screen.
        collector = CoroutineScope(Dispatchers.Main.immediate).launch { vm.state.collect { } }
    }

    @After
    fun tearDown() = collector.cancel()

    private fun ready(): ScheduleUiState.Ready? = vm.state.value as? ScheduleUiState.Ready

    /** The fake clock stands at Monday 2026-11-02 09:00 UTC. */
    private fun saveStandup(): String = runBlocking {
        val draft = MissionDraft(
            title = "Standup", firstAction = "Open the notes", flexibility = Flexibility.FLEXIBLE,
            schedule = ScheduleDraft.Once(LocalDateTime.of(2026, 11, 2, 11, 0)),
        )
        (missions.save(draft) as SaveResult.Saved).missionId
    }

    @Test
    fun `with nothing saved the screen is ready and empty`() {
        awaitUntil(message = "ready") { ready() != null }
        assertTrue(ready()!!.upcoming.isEmpty())
        assertTrue(ready()!!.recent.isEmpty())
        assertEquals(0, ready()!!.problems)
    }

    @Test
    fun `a saved mission is coming up, and once it rang unanswered it moves to recent as missed`() {
        saveStandup()
        awaitUntil(message = "coming up") { ready()?.upcoming?.size == 1 }
        val row = ready()!!.upcoming.single()
        assertEquals("Standup", row.title)
        assertEquals(OccurrenceState.ARMED, row.state)

        runBlocking {
            repo.apply(row.occurrenceId, OccurrenceCommand.Fire, EventSource.SERVICE)
            repo.apply(row.occurrenceId, OccurrenceCommand.Miss(MissReason.TIMEOUT), EventSource.SERVICE)
        }
        // The two lists are separate reads, so one can move before the other: wait for both, and a mission stuck in upcoming times out here.
        awaitUntil(message = "out of upcoming and into recent") { ready()?.let { it.recent.size == 1 && it.upcoming.isEmpty() } == true }
        assertEquals(OccurrenceState.MISSED, ready()!!.recent.single().state)
    }

    @Test
    fun `a deleted mission leaves the list`() {
        val id = saveStandup()
        awaitUntil(message = "coming up") { ready()?.upcoming?.size == 1 }
        runBlocking { missions.delete(id) }
        awaitUntil(message = "gone") { ready()?.upcoming?.isEmpty() == true }
    }

    @Test
    fun `the number of things that can stop an alarm follows what the phone says and is checked again on return`() {
        awaitUntil(message = "ready") { ready() != null }

        monitor.set(Precondition.EXACT_ALARMS, "denied")
        vm.onResume()
        awaitUntil(message = "one") { ready()?.problems == 1 }

        monitor.set(Precondition.NOTIFICATIONS, "denied")
        vm.onResume()
        awaitUntil(message = "two") { ready()?.problems == 2 }

        monitor.set(Precondition.EXACT_ALARMS, "granted")
        monitor.set(Precondition.NOTIFICATIONS, "granted")
        vm.onResume()
        awaitUntil(message = "cleared") { ready()?.problems == 0 }
    }

    @Test
    fun `advice and warnings are not counted, only what can stop an alarm`() {
        awaitUntil(message = "ready") { ready() != null }
        monitor.set(Precondition.BATTERY_OPTIMIZATION, "optimized")
        monitor.set(Precondition.BACKGROUND_RESTRICTED, "yes")
        monitor.set(Precondition.STANDBY_BUCKET, "RARE")
        vm.onResume()
        awaitUntil(message = "read") { monitor.refreshes >= 1 }
        assertEquals(0, ready()!!.problems)
    }

    @Test
    fun `a pause is part of the state until it ends, and ending it takes the banner away`() {
        awaitUntil(message = "ready") { ready() != null }
        assertEquals(null, ready()!!.pausedUntilUtcMs)

        runBlocking { missions.pause(2 * 3_600_000L) }
        awaitUntil(message = "paused") { ready()?.pausedUntilUtcMs == com.command.testing.android.FakeAlarmModule.T0 + 2 * 3_600_000L }

        vm.endPause()
        awaitUntil(message = "resumed") { ready()?.pausedUntilUtcMs == null }
    }

    @Test
    fun `the state carries today, so that the labels follow the clock even when nothing else changed`() {
        awaitUntil(message = "ready") { ready() != null }
        assertEquals(java.time.LocalDate.of(2026, 11, 2), ready()!!.today)

        clock.advance(24 * 3_600_000L)
        monitor.set(Precondition.EXACT_ALARMS, "denied") // makes the screen look again
        vm.onResume()
        awaitUntil(message = "the next day") { ready()?.today == java.time.LocalDate.of(2026, 11, 3) }
    }
}
