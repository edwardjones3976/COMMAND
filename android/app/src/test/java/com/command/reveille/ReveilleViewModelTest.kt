package com.command.reveille

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.command.data.OccurrenceRepository
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.domain.dials.Flexibility
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.SkipReasonCode
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.testing.FakeClock
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class ReveilleViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock

    private lateinit var vm: ReveilleViewModel

    @Before
    fun setUp() {
        hilt.inject()
        vm = ReveilleViewModel(repo, clock)
    }

    private fun ringing(profile: String = "builtin_steady", flexibility: Flexibility = Flexibility.FLEXIBLE, lock: LockPolicy? = null): OccurrenceRow = runBlocking {
        val mission = testMission(profileId = profile).copy(flexibility = flexibility, lockPolicy = lock)
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        repo.get(o.id)!!
    }

    private fun ringingTitled(title: String): OccurrenceRow = runBlocking {
        val mission = testMission(title = title, profileId = "builtin_steady")
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        repo.get(o.id)!!
    }

    private fun stateOf(o: OccurrenceRow) = runBlocking { repo.get(o.id)!!.state }
    private fun challenges(o: OccurrenceRow) = runBlocking { db.challenges().forOccurrence(o.id) }

    private fun awaitUi(message: String, check: (ReveilleUiState) -> Boolean) = awaitUntil(message = message) { check(vm.state.value) }

    @Test
    fun `a ringing occurrence shows the hold challenge with the mission's title`() {
        val o = ringing()
        vm.show(o.id)
        awaitUi("firing") { it is ReveilleUiState.Firing }
        val s = vm.state.value as ReveilleUiState.Firing
        assertEquals("Write the report", s.title)
        assertTrue("Steady asks for a hold, a few seconds long", s.holdSeconds in 2..5)
    }

    @Test
    fun `an unknown occurrence is reported as not found`() {
        vm.show("does-not-exist")
        awaitUi("not found") { it == ReveilleUiState.NotFound }
    }

    @Test
    fun `showing another occurrence stops watching the first, so it cannot draw over the new one`() {
        val first = ringingTitled("First")
        val second = ringingTitled("Second")
        vm.show(first.id)
        awaitUi("first") { (it as? ReveilleUiState.Firing)?.title == "First" }
        vm.show(second.id)
        awaitUi("second") { (it as? ReveilleUiState.Firing)?.title == "Second" }

        // Something happens to the first one; the screen must keep showing the second.
        runBlocking { repo.apply(first.id, OccurrenceCommand.SafetySilence, EventSource.USER) }
        awaitUntil(message = "first silenced") { stateOf(first) == OccurrenceState.SAFETY_SILENCED }
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals("Second", (vm.state.value as ReveilleUiState.Firing).title)

        // And what the user does goes to the second occurrence, not the first.
        vm.silence()
        awaitUntil(message = "second silenced") { stateOf(second) == OccurrenceState.SAFETY_SILENCED }
    }

    @Test
    fun `an occurrence a dead process left acknowledged is moved on to the direction when it is reopened`() {
        val o = ringing()
        // The acknowledgement committed, then the process died before the guidance did.
        runBlocking { repo.apply(o.id, OccurrenceCommand.Acknowledge(), EventSource.USER) }
        assertEquals(OccurrenceState.ACKNOWLEDGED, stateOf(o))

        vm.show(o.id)
        awaitUi("direction") { it is ReveilleUiState.Direction }
        awaitUntil(message = "guidance started") { stateOf(o) == OccurrenceState.GUIDED_START }
        vm.started()
        awaitUi("closed") { it is ReveilleUiState.Closed }
        assertEquals("the button works: starting is accepted", OccurrenceState.STARTED_SELF_REPORTED, stateOf(o))
    }

    @Test
    fun `holding long enough acknowledges, records a success and moves on to the direction, never straight to started`() {
        val o = ringing()
        vm.show(o.id)
        awaitUi("firing") { it is ReveilleUiState.Firing }
        vm.holdCompleted(100, 3_100)
        awaitUi("direction") { it is ReveilleUiState.Direction }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
        val direction = vm.state.value as ReveilleUiState.Direction
        assertEquals("Open the document", direction.firstAction)
        val row = challenges(o).single()
        assertEquals(HandshakeMode.HOLD, row.mode)
        assertEquals(ChallengeOutcome.SUCCESS, row.outcome)
        assertEquals(3_000L, row.endedAt - row.startedAt)
    }

    @Test
    fun `letting go early is recorded as a failed attempt and leaves the alarm ringing`() {
        val o = ringing()
        vm.show(o.id)
        awaitUi("firing") { it is ReveilleUiState.Firing }
        vm.holdReleasedEarly(100, 400)
        awaitUntil(message = "challenge row") { challenges(o).isNotEmpty() }
        assertEquals(ChallengeOutcome.FAILED, challenges(o).single().outcome)
        assertEquals(OccurrenceState.FIRING, stateOf(o))
        assertTrue("the screen says why", (vm.state.value as ReveilleUiState.Firing).earlyRelease)
        vm.holdStarted()
        assertFalse("a new press clears the hint", (vm.state.value as ReveilleUiState.Firing).earlyRelease)
    }

    @Test
    fun `the accessible confirm acknowledges and is recorded as the alternative, not as a plain success`() {
        val o = ringing()
        vm.show(o.id)
        awaitUi("firing") { it is ReveilleUiState.Firing }
        vm.accessibleConfirm()
        awaitUi("direction") { it is ReveilleUiState.Direction }
        assertEquals(ChallengeOutcome.ACCESSIBLE_ALT, challenges(o).single().outcome)
    }

    @Test
    fun `safety silence is not starting, and the screen offers to pick it up again`() {
        val o = ringing()
        vm.show(o.id)
        awaitUi("firing") { it is ReveilleUiState.Firing }
        vm.silence()
        awaitUi("silenced") { it is ReveilleUiState.Silenced }
        assertEquals(OccurrenceState.SAFETY_SILENCED, stateOf(o))
        vm.resume()
        awaitUi("direction") { it is ReveilleUiState.Direction }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
    }

    @Test
    fun `saying you are starting is the only way to a started status`() {
        val o = ringing()
        vm.show(o.id)
        awaitUi("firing") { it is ReveilleUiState.Firing }
        vm.holdCompleted(0, 3_000)
        awaitUi("direction") { it is ReveilleUiState.Direction }
        vm.started()
        awaitUi("closed") { it is ReveilleUiState.Closed }
        assertEquals(ClosedOutcome.STARTED, (vm.state.value as ReveilleUiState.Closed).outcome)
        assertEquals(OccurrenceState.STARTED_SELF_REPORTED, stateOf(o))
    }

    @Test
    fun `a flexible mission skips and delays with no friction`() {
        val o = ringing()
        vm.show(o.id)
        awaitUi("firing") { it is ReveilleUiState.Firing }
        vm.holdCompleted(0, 3_000)
        awaitUi("direction") { it is ReveilleUiState.Direction }
        vm.skip()
        awaitUi("closed") { it is ReveilleUiState.Closed }
        assertEquals(OccurrenceState.SKIPPED, stateOf(o))
        assertEquals(SkipReason.USER, runBlocking { repo.get(o.id)!!.skipReason })
        assertNull(vm.friction.value)
    }

    @Test
    fun `a fixed mission asks for its friction before it will skip, and only then`() {
        val o = ringing(flexibility = Flexibility.RIGID, lock = LockPolicy.REASON)
        vm.show(o.id)
        awaitUi("firing") { it is ReveilleUiState.Firing }
        vm.holdCompleted(0, 3_000)
        awaitUi("direction") { it is ReveilleUiState.Direction }

        vm.skip()
        awaitUntil(message = "friction prompt") { vm.friction.value != null }
        assertEquals(LockPolicy.REASON, vm.friction.value!!.required)
        assertEquals("nothing happened yet", OccurrenceState.GUIDED_START, stateOf(o))

        vm.confirmFriction(FrictionEvidence.Confirmed) // a bare confirm is not a reason
        awaitUntil(message = "still asking") { vm.friction.value != null }
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))

        vm.confirmFriction(FrictionEvidence.Reason(SkipReasonCode.NEED_REST))
        awaitUi("closed") { it is ReveilleUiState.Closed }
        assertEquals(OccurrenceState.SKIPPED, stateOf(o))
        assertNull(vm.friction.value)
    }

    @Test
    fun `dismissing the friction prompt changes nothing`() {
        val o = ringing(flexibility = Flexibility.RIGID, lock = LockPolicy.CONFIRM)
        vm.show(o.id)
        awaitUi("firing") { it is ReveilleUiState.Firing }
        vm.holdCompleted(0, 3_000)
        awaitUi("direction") { it is ReveilleUiState.Direction }
        vm.skip()
        awaitUntil(message = "prompt") { vm.friction.value != null }
        vm.dismissFriction()
        assertNull(vm.friction.value)
        assertEquals(OccurrenceState.GUIDED_START, stateOf(o))
    }

    @Test
    fun `delaying closes this alarm and schedules the next`() {
        val o = ringing()
        vm.show(o.id)
        awaitUi("firing") { it is ReveilleUiState.Firing }
        vm.holdCompleted(0, 3_000)
        awaitUi("direction") { it is ReveilleUiState.Direction }
        vm.delay(10)
        awaitUi("closed") { it is ReveilleUiState.Closed }
        assertEquals(ClosedOutcome.DELAYED, (vm.state.value as ReveilleUiState.Closed).outcome)
        val children = runBlocking { db.occurrences().forMission(o.missionId) }.filter { it.parentOccurrenceId == o.id }
        assertEquals(1, children.size)
        assertNotNull(children.single().scheduledAtUtc)
    }

    @Test
    fun `reopening mid-flow lands on the right step`() {
        val o = ringing()
        runBlocking {
            repo.apply(o.id, OccurrenceCommand.Acknowledge(), EventSource.USER)
            repo.apply(o.id, OccurrenceCommand.StartGuidance, EventSource.USER)
        }
        val fresh = ReveilleViewModel(repo, clock) // a new process, a new view model, the same stored occurrence
        fresh.show(o.id)
        awaitUntil(message = "direction") { fresh.state.value is ReveilleUiState.Direction }
    }

    @Test
    fun `an occurrence that is not ringing shows nothing to do`() {
        val mission = testMission()
        runBlocking { db.missions().insert(mission) }
        val o = runBlocking { repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence }
        vm.show(o.id)
        awaitUi("closed") { it is ReveilleUiState.Closed }
        assertEquals(ClosedOutcome.NOT_RINGING, (vm.state.value as ReveilleUiState.Closed).outcome)
    }

    @Suppress("unused")
    private val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
}
