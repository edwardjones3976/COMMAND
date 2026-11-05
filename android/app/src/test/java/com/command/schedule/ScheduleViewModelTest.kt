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
import com.command.testing.FakeAlarmScheduler
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
    @Inject lateinit var scheduler: FakeAlarmScheduler

    private lateinit var vm: ScheduleViewModel
    private lateinit var collector: Job

    @Before
    fun setUp() {
        hilt.inject()
        vm = ScheduleViewModel(missions, scheduler)
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
        assertTrue(ready()!!.exactAlarmsAvailable)
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
        awaitUntil(message = "recent") { ready()?.recent?.size == 1 }
        assertTrue(ready()!!.upcoming.isEmpty())
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
    fun `the exact alarm warning follows what the system allows and is checked again on return`() {
        awaitUntil(message = "ready") { ready() != null }
        scheduler.exactAllowed = false
        vm.onResume()
        awaitUntil(message = "warning") { ready()?.exactAlarmsAvailable == false }
        scheduler.exactAllowed = true
        vm.onResume()
        awaitUntil(message = "cleared") { ready()?.exactAlarmsAvailable == true }
    }
}
