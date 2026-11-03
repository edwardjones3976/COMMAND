package com.command.followup

import com.command.data.OccurrenceRepository
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.domain.dials.Flexibility
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.RecoveryOption
import com.command.domain.dials.SkipReasonCode
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class FollowUpViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb

    private lateinit var vm: FollowUpViewModel

    @Before
    fun setUp() {
        hilt.inject()
        vm = FollowUpViewModel(repo)
    }

    /** An occurrence taken through the given commands, as a mission with the given profile and flexibility. */
    private fun occurrence(
        vararg commands: OccurrenceCommand,
        title: String = "Write the report",
        profile: String = "builtin_steady",
        flexibility: Flexibility = Flexibility.FLEXIBLE,
        lock: LockPolicy? = null,
    ): OccurrenceRow = runBlocking {
        val mission = testMission(title = title, profileId = profile).copy(flexibility = flexibility, lockPolicy = lock)
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        for (c in commands) repo.apply(o.id, c, EventSource.SYSTEM)
        repo.get(o.id)!!
    }

    private fun guided(profile: String = "builtin_steady", flexibility: Flexibility = Flexibility.FLEXIBLE, lock: LockPolicy? = null, title: String = "Write the report") =
        occurrence(
            OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance,
            title = title, profile = profile, flexibility = flexibility, lock = lock,
        )

    private fun stateOf(o: OccurrenceRow) = runBlocking { repo.get(o.id)!!.state }

    private fun awaitUi(message: String, check: (FollowUpUiState) -> Boolean) = awaitUntil(message = message) { check(vm.state.value) }

    // --- what the screen shows -------------------------------------------------------------------------------------

    @Test
    fun `an unknown occurrence is reported as not found`() {
        vm.show("does-not-exist")
        awaitUi("not found") { it == FollowUpUiState.NotFound }
    }

    @Test
    fun `a start that was never reported is asked about, with the mission's title and its first step`() {
        val o = guided()
        vm.show(o.id)
        awaitUi("ask") { it is FollowUpUiState.Ask }
        assertEquals(FollowUpUiState.Ask("Write the report", "Open the document", started = false, unconfirmed = false), vm.state.value)
    }

    @Test
    fun `once a start has been reported the question is how it is going`() {
        val o = guided()
        vm.show(o.id)
        awaitUi("ask") { it is FollowUpUiState.Ask }
        vm.started()
        awaitUi("started") { (it as? FollowUpUiState.Ask)?.started == true }
        assertEquals(OccurrenceState.STARTED_SELF_REPORTED, stateOf(o))
        vm.working()
        awaitUntil(message = "working") { stateOf(o) == OccurrenceState.WORKING_SELF_REPORTED }
        assertTrue((vm.state.value as FollowUpUiState.Ask).started)
    }

    @Test
    fun `done ends the follow-up and says it is the person's own record`() {
        val o = guided()
        vm.show(o.id)
        awaitUi("ask") { it is FollowUpUiState.Ask }
        vm.done()
        awaitUi("done") { it == FollowUpUiState.Finished("Write the report", FollowUpOutcome.DONE) }
        assertEquals(OccurrenceState.COMPLETED_SELF_REPORTED, stateOf(o))
    }

    @Test
    fun `questions that went unanswered leave it unconfirmed, and the question says so`() {
        val o = guided()
        runBlocking { repo.apply(o.id, OccurrenceCommand.NoResponse, EventSource.WORKER) }
        vm.show(o.id)
        awaitUi("unconfirmed") { (it as? FollowUpUiState.Ask)?.unconfirmed == true }
        vm.started() // an unconfirmed one can still be picked up
        awaitUntil(message = "started") { stateOf(o) == OccurrenceState.STARTED_SELF_REPORTED }
    }

    @Test
    fun `an occurrence that has not started or is ringing has nothing to ask yet`() {
        val armed = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT))
        vm.show(armed.id)
        awaitUi("waiting") { it == FollowUpUiState.Finished("Write the report", FollowUpOutcome.WAITING) }
    }

    @Test
    fun `after the app was killed a new view model lands where the occurrence is`() {
        val o = guided()
        runBlocking { repo.apply(o.id, OccurrenceCommand.ReportStarted, EventSource.USER) }
        val fresh = FollowUpViewModel(repo)
        fresh.show(o.id)
        awaitUntil(message = "ask") { (fresh.state.value as? FollowUpUiState.Ask)?.started == true }
    }

    @Test
    fun `showing another occurrence stops watching the first, so it cannot draw over the new one`() {
        val first = guided(title = "First")
        val second = guided(title = "Second")
        vm.show(first.id)
        awaitUi("first") { (it as? FollowUpUiState.Ask)?.title == "First" }
        vm.show(second.id)
        awaitUi("second") { (it as? FollowUpUiState.Ask)?.title == "Second" }

        runBlocking { repo.apply(first.id, OccurrenceCommand.ReportDone, EventSource.USER) }
        awaitUntil(message = "first done") { stateOf(first) == OccurrenceState.COMPLETED_SELF_REPORTED }
        shadowIdle()
        assertEquals("Second", (vm.state.value as FollowUpUiState.Ask).title)
    }

    // --- a reset ----------------------------------------------------------------------------------------------------

    @Test
    fun `asking for a reset shows the menu the person's recovery setting allows`() {
        val o = guided(profile = "builtin_steady") // recovery structure 2: a restart, or later at 30 or 60 minutes, once
        vm.show(o.id)
        awaitUi("ask") { it is FollowUpUiState.Ask }
        vm.needReset()
        awaitUi("menu") { it is FollowUpUiState.Recovery }
        val menu = vm.state.value as FollowUpUiState.Recovery
        assertEquals(listOf(RecoveryOption.RESET, RecoveryOption.RESCHEDULE), menu.options)
        assertEquals(listOf(30, 60), menu.rescheduleMinutes)
        assertEquals(OccurrenceState.RECOVERY_OFFERED, stateOf(o))
    }

    @Test
    fun `starting again after a reset goes back to asking, and a second reset is refused with a word about it`() {
        val o = guided(profile = "builtin_steady")
        vm.show(o.id)
        awaitUi("ask") { it is FollowUpUiState.Ask }
        vm.needReset()
        awaitUi("menu") { it is FollowUpUiState.Recovery }
        vm.restart()
        awaitUi("asking again") { it is FollowUpUiState.Ask }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))

        vm.needReset() // steady allows one reset
        awaitUntil(message = "notice") { vm.notice.value == FollowUpNotice.RESETS_USED_UP }
        assertTrue("still asking", vm.state.value is FollowUpUiState.Ask)
        vm.dismissNotice()
        assertNull(vm.notice.value)
    }

    @Test
    fun `trying again later delays it, and not now leaves it unconfirmed`() {
        val later = guided(profile = "builtin_steady", title = "Later")
        vm.show(later.id)
        awaitUi("ask") { it is FollowUpUiState.Ask }
        vm.needReset()
        awaitUi("menu") { it is FollowUpUiState.Recovery }
        vm.rescheduleTo(30)
        awaitUntil(message = "delayed") { stateOf(later) == OccurrenceState.DELAYED }
        awaitUi("finished") { it == FollowUpUiState.Finished("Later", FollowUpOutcome.DELAYED) }
        assertEquals("a child alarm waits for it", 1, runBlocking { db.occurrences().forMission(later.missionId).count { it.parentOccurrenceId == later.id } })

        val notNow = guided(profile = "builtin_steady", title = "Not now")
        vm.show(notNow.id)
        awaitUi("ask again") { (it as? FollowUpUiState.Ask)?.title == "Not now" }
        vm.needReset()
        awaitUi("menu again") { it is FollowUpUiState.Recovery }
        vm.notNow()
        awaitUntil(message = "unconfirmed") { stateOf(notNow) == OccurrenceState.UNCONFIRMED }
    }

    // --- delaying and skipping -----------------------------------------------------------------------------------------

    @Test
    fun `a fixed commitment asks for a little more before it is delayed, and nothing changes until then`() {
        val o = guided(flexibility = Flexibility.RIGID, lock = LockPolicy.CONFIRM)
        vm.show(o.id)
        awaitUi("ask") { it is FollowUpUiState.Ask }
        vm.delay(10)
        awaitUntil(message = "friction") { vm.friction.value != null }
        assertEquals(LockPolicy.CONFIRM, vm.friction.value!!.required)
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))

        vm.confirmFriction(FrictionEvidence.Confirmed)
        awaitUntil(message = "delayed") { stateOf(o) == OccurrenceState.DELAYED }
        assertNull(vm.friction.value)
    }

    @Test
    fun `the dialog goes at the tap, and a confirmation is not a reason, so a mission that wants a reason asks again`() {
        val o = guided(flexibility = Flexibility.RIGID, lock = LockPolicy.REASON)
        vm.show(o.id)
        awaitUi("ask") { it is FollowUpUiState.Ask }
        vm.skip()
        awaitUntil(message = "friction") { vm.friction.value != null }
        assertEquals(LockPolicy.REASON, vm.friction.value!!.required)
        val first = vm.friction.value

        vm.confirmFriction(FrictionEvidence.Confirmed)
        assertNull("it is gone the moment it was answered", vm.friction.value)
        awaitUntil(message = "asked again") { vm.friction.value != null }
        assertNotSame(first, vm.friction.value)
        assertEquals(LockPolicy.REASON, vm.friction.value!!.required)
        assertEquals("nothing changed", OccurrenceState.GUIDED_START, stateOf(o))

        vm.confirmFriction(FrictionEvidence.Reason(SkipReasonCode.NOT_RELEVANT_TODAY))
        awaitUntil(message = "skipped") { stateOf(o) == OccurrenceState.SKIPPED }
        assertNull(vm.friction.value)
    }

    @Test
    fun `a flexible one is delayed or skipped with one tap, and a skip is recorded as skipped`() {
        val delayed = guided(title = "Delayed")
        vm.show(delayed.id)
        awaitUi("ask") { it is FollowUpUiState.Ask }
        vm.delay(30)
        awaitUntil(message = "delayed") { stateOf(delayed) == OccurrenceState.DELAYED }

        val skipped = guided(title = "Skipped")
        vm.show(skipped.id)
        awaitUi("ask again") { (it as? FollowUpUiState.Ask)?.title == "Skipped" }
        vm.skip()
        awaitUntil(message = "skipped") { stateOf(skipped) == OccurrenceState.SKIPPED }
        awaitUi("finished") { it == FollowUpUiState.Finished("Skipped", FollowUpOutcome.SKIPPED) }
    }

    @Test
    fun `backing out of the friction leaves the occurrence as it was`() {
        val o = guided(flexibility = Flexibility.RIGID, lock = LockPolicy.CONFIRM)
        vm.show(o.id)
        awaitUi("ask") { it is FollowUpUiState.Ask }
        vm.skip()
        awaitUntil(message = "friction") { vm.friction.value != null }
        vm.dismissFriction()
        assertNull(vm.friction.value)
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
        assertNotNull(runBlocking { repo.get(o.id) })
    }

    private fun shadowIdle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
}
