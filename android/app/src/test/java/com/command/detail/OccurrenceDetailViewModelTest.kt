package com.command.detail

import com.command.data.OccurrenceRepository
import com.command.data.db.CommandDb
import com.command.domain.history.HistoryReason
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The page of one occurrence: what is recorded about it, kept up to date while it is open. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class OccurrenceDetailViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb

    private lateinit var vm: OccurrenceDetailViewModel

    @Before
    fun setUp() {
        hilt.inject()
        vm = OccurrenceDetailViewModel(repo)
    }

    private fun ready(): DetailUiState.Ready? = vm.state.value as? DetailUiState.Ready

    private fun occurrence(vararg commands: OccurrenceCommand, title: String = "Write the report"): String = runBlocking {
        val mission = testMission(title)
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 - 3_600_000, "UTC").occurrence
        for (c in commands) repo.apply(o.id, c, EventSource.SYSTEM)
        o.id
    }

    @Test
    fun `an unknown occurrence says it is gone`() {
        vm.show("no-such-occurrence")
        awaitUntil(message = "not found") { vm.state.value == DetailUiState.NotFound }
    }

    @Test
    fun `a morning that went well is told from the alarm being set to the start, with the title and the outcome`() {
        val id = occurrence(
            OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance,
            OccurrenceCommand.CheckInDue(0), OccurrenceCommand.ReportStarted,
        )
        vm.show(id)
        awaitUntil(message = "ready") { ready() != null }
        val r = ready()!!
        assertEquals("Write the report", r.title)
        assertEquals(HistoryReason.STARTED, r.outcome)
        assertEquals(
            listOf(StoryKind.SET, StoryKind.RANG, StoryKind.ANSWERED, StoryKind.DIRECTED, StoryKind.ASKED, StoryKind.STARTED),
            r.story.map { it.kind },
        )
        assertTrue("the technical record has more than the story", r.technical.size > r.story.size)
        assertTrue(r.technical.any { it.title == "ALARM_FIRED" })
    }

    @Test
    fun `the page follows the occurrence while it is open`() {
        val id = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance)
        vm.show(id)
        awaitUntil(message = "open") { ready()?.outcome == HistoryReason.OPEN }

        runBlocking { repo.apply(id, OccurrenceCommand.ReportDone, EventSource.USER) }
        awaitUntil(message = "done") { ready()?.outcome == HistoryReason.DONE }
        assertEquals(StoryKind.DONE, ready()!!.story.last().kind)
    }

    @Test
    fun `showing another occurrence replaces the first, and the first can no longer draw over it`() {
        val first = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT), title = "First")
        val second = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT), title = "Second")
        vm.show(first)
        awaitUntil(message = "first") { ready()?.title == "First" }
        vm.show(second)
        awaitUntil(message = "second") { ready()?.title == "Second" }

        runBlocking { repo.apply(first, OccurrenceCommand.Fire, EventSource.SERVICE) } // the first changes; the second is what is on screen
        repeat(20) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals("Second", ready()!!.title)
    }
}
