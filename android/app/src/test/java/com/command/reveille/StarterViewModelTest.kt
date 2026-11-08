package com.command.reveille

import android.os.Looper
import com.command.data.OccurrenceRepository
import com.command.data.TokenRepository
import com.command.data.REPEATED_ACK_NO_START_AFTER
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.coaching.CoachingPhase
import com.command.domain.dials.Flexibility
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.LockPolicy
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.ringActions
import com.command.testing.FakeAlarmScheduler
import com.command.testing.FakeClock
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** The starter on the lock-screen flow, and every coaching line of M10c: which state shows it, and which does not. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class StarterViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock
    @Inject lateinit var scheduler: FakeAlarmScheduler

    private lateinit var vm: ReveilleViewModel
    private val minute = 60_000L
    private val ticks = Channel<Unit>(Channel.UNLIMITED)
    private var at = FakeAlarmModule.T0 + 60_000

    @Before
    fun setUp() {
        hilt.inject()
        vm = model()
    }

    private fun model(tick: Channel<Unit> = ticks) = ReveilleViewModel(repo, clock, FixedPhrases(listOf("Starting now")), FixedCapability(), TokenRepository(db, clock)).also { it.starterTick = { tick.receive() } }

    private fun missionId(starterMinutes: Int = 5, title: String = "Write the report"): String = runBlocking {
        val mission = testMission(title = title, profileId = "builtin_steady").copy(starterMinutes = starterMinutes)
        db.missions().insert(mission)
        mission.id
    }

    private fun firing(mission: String = missionId()): OccurrenceRow = runBlocking {
        val o = repo.createOccurrence(mission, at, "UTC").occurrence
        at += 60_000
        repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        repo.get(o.id)!!
    }

    private fun guided(mission: String = missionId()): OccurrenceRow = runBlocking {
        val o = firing(mission)
        repo.apply(o.id, OccurrenceCommand.Acknowledge(), EventSource.USER)
        repo.apply(o.id, OccurrenceCommand.StartGuidance, EventSource.USER)
        repo.get(o.id)!!
    }

    private fun row(o: OccurrenceRow) = runBlocking { repo.get(o.id)!! }
    private fun line(o: OccurrenceRow, phase: CoachingPhase, minutes: Int? = null, words: Boolean = false) =
        runBlocking { repo.lineFor(row(o), phase, withMissionWords = words, minutes = minutes).text }

    private inline fun <reified T : ReveilleUiState> await(model: ReveilleViewModel = vm, what: String = T::class.java.simpleName, crossinline check: (T) -> Boolean = { true }): T {
        awaitUntil(message = what) { (model.state.value as? T)?.let(check) == true }
        return model.state.value as T
    }

    private fun running(o: OccurrenceRow, model: ReveilleViewModel = vm): ReveilleUiState.Starter {
        model.show(o.id)
        await<ReveilleUiState.Direction>(model) { it.offerStarter }
        model.beginStarter()
        return await(model)
    }

    /** Under Robolectric a test of something that must not happen has to run the main thread while it waits, or it passes without looking. */
    private fun pumpMainThread() = repeat(20) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }

    /** One tick of the screen's clock, after [ms] have gone by, seen on the screen. */
    private fun second(ms: Long, expectSeconds: Int): ReveilleUiState.Starter {
        clock.advance(ms)
        ticks.trySend(Unit)
        return await<ReveilleUiState.Starter>(what = "the clock at $expectSeconds") { it.remainingSeconds == expectSeconds }
    }

    // --- the offer ---------------------------------------------------------------------------------------------

    @Test
    fun `the direction offers the starter once guidance has begun, and it is taken only by a tap`() {
        val o = guided()
        vm.show(o.id)
        val s = await<ReveilleUiState.Direction> { it.offerStarter }
        assertEquals(5, s.starterMinutes)
        assertNull("it is an offer", repo.starterOf(row(o)))
    }

    @Test
    fun `a mission with no starter does not offer one`() {
        vm.show(guided(missionId(starterMinutes = 0)).id)
        assertFalse(await<ReveilleUiState.Direction>().offerStarter)
    }

    @Test
    fun `someone blocked, unanswered or offered a recovery gets no starter offer`() {
        val o = guided()
        runBlocking { repo.apply(o.id, OccurrenceCommand.ReportBlocked, EventSource.USER) }
        vm.show(o.id)
        assertFalse(await<ReveilleUiState.Direction>().offerStarter)
    }

    @Test
    fun `the screen's action reaches the command`() {
        val o = guided()
        vm.show(o.id)
        await<ReveilleUiState.Direction> { it.offerStarter }
        ringActions(vm, null) {}.onBeginStarter()
        await<ReveilleUiState.Starter>()
        assertEquals(clock.nowMs() + 5 * minute, repo.starterOf(row(o))?.deadlineUtc)
    }

    // --- the countdown -----------------------------------------------------------------------------------------

    @Test
    fun `the countdown starts full with the start line, and a start is still not reported`() {
        val o = guided()
        val s = running(o)
        assertEquals("Write the report", s.title)
        assertEquals(300, s.remainingSeconds)
        assertEquals(line(o, CoachingPhase.STARTER_START, 5), s.line)
        assertTrue("the line says the length", s.line.isNotBlank())
        assertEquals(OccurrenceState.GUIDED_START, row(o).state)
    }

    @Test
    fun `the clock counts down with each tick, and the midpoint line takes over half way`() {
        val o = guided()
        running(o)
        val start = line(o, CoachingPhase.STARTER_START, 5)
        val midpoint = line(o, CoachingPhase.STARTER_MIDPOINT, 5)

        assertEquals(start, second(minute, 240).line)
        assertEquals("just before half way", start, second(89_000, 151).line)
        assertEquals("half way", midpoint, second(1_000, 150).line)
        assertEquals(midpoint, second(minute, 90).line)
    }

    @Test
    fun `the clock stops at zero, and the person can still say they started`() {
        val o = guided()
        running(o)
        assertEquals(0, second(5 * minute, 0).remainingSeconds)
        assertEquals("it does not end anything by itself", OccurrenceState.GUIDED_START, row(o).state)

        clock.advance(minute)
        ticks.trySend(Unit)
        vm.started()
        await<ReveilleUiState.Closed> { it.outcome == ClosedOutcome.STARTED }
    }

    @Test
    fun `the countdown changes nothing in the record`() {
        val o = guided()
        running(o)
        val version = row(o).stateVersion
        val session = row(o).sessionJson
        val events = runBlocking { db.events().forOccurrence(o.id).size }
        second(minute, 240)
        second(2 * minute, 120)
        assertEquals(version, row(o).stateVersion)
        assertEquals(session, row(o).sessionJson)
        assertEquals(events, runBlocking { db.events().forOccurrence(o.id).size })
    }

    @Test
    fun `the check-in alarm set by guidance is the one still set when the starter is over`() {
        val o = guided()
        val before = scheduler.armed.values.filter { it.occurrenceId == o.id && it.kind == AlarmKind.CHECK_IN }
        assertTrue(before.isNotEmpty())
        running(o)
        second(5 * minute, 0)
        assertEquals(before, scheduler.armed.values.filter { it.occurrenceId == o.id && it.kind == AlarmKind.CHECK_IN })
    }

    @Test
    fun `a follow-up question during the starter leaves the countdown running`() {
        val o = guided()
        running(o)
        second(minute, 240)
        runBlocking { repo.apply(o.id, OccurrenceCommand.CheckInDue(0), EventSource.SYSTEM) }
        assertEquals(180, second(minute, 180).remainingSeconds)
    }

    // --- the restore -------------------------------------------------------------------------------------------

    @Test
    fun `a new screen restores the countdown from the session, and writes nothing`() {
        val o = guided()
        running(o)
        val session = row(o).sessionJson
        val version = row(o).stateVersion
        clock.advance(2 * minute)

        val fresh = model()
        fresh.show(o.id)

        val s = await<ReveilleUiState.Starter>(fresh)
        assertEquals(180, s.remainingSeconds)
        assertEquals(session, row(o).sessionJson)
        assertEquals(version, row(o).stateVersion)
    }

    @Test
    fun `a restore past the midpoint shows the midpoint line, and one past the deadline shows an empty clock`() {
        val o = guided()
        running(o)
        clock.advance(3 * minute)
        val after = model()
        after.show(o.id)
        assertEquals(line(o, CoachingPhase.STARTER_MIDPOINT, 5), await<ReveilleUiState.Starter>(after).line)

        clock.advance(10 * minute)
        val late = model()
        late.show(o.id)
        assertEquals(0, await<ReveilleUiState.Starter>(late).remainingSeconds)
    }

    @Test
    fun `the restored clock goes on counting`() {
        val o = guided()
        running(o)
        clock.advance(minute)
        val own = Channel<Unit>(Channel.UNLIMITED)
        val fresh = model(own)
        fresh.show(o.id)
        await<ReveilleUiState.Starter>(fresh) { it.remainingSeconds == 240 }
        clock.advance(minute)
        own.trySend(Unit)
        await<ReveilleUiState.Starter>(fresh) { it.remainingSeconds == 180 }
    }

    @Test
    fun `the line keeps the length the starter began with when the mission is edited under it`() {
        val mission = missionId()
        val o = guided(mission)
        running(o)
        runBlocking { db.missions().update(db.missions().get(mission)!!.copy(starterMinutes = 30)) }
        val fresh = model()
        fresh.show(o.id)
        val s = await<ReveilleUiState.Starter>(fresh)
        assertEquals(line(o, CoachingPhase.STARTER_START, 5), s.line)
        assertEquals(300, s.remainingSeconds)
    }

    // --- the ends ----------------------------------------------------------------------------------------------

    @Test
    fun `a start reported during the starter closes it, and no tick draws it back`() {
        val o = guided()
        running(o)
        vm.started()
        await<ReveilleUiState.Closed> { it.outcome == ClosedOutcome.STARTED }
        assertEquals(OccurrenceState.STARTED_SELF_REPORTED, row(o).state)
        clock.advance(minute)
        ticks.trySend(Unit)
        pumpMainThread()
        assertTrue(vm.state.value is ReveilleUiState.Closed)
    }

    @Test
    fun `a delay or a skip during the starter answers as it always does`() {
        val delayed = guided()
        running(delayed)
        vm.delay(10)
        await<ReveilleUiState.Closed> { it.outcome == ClosedOutcome.DELAYED }

        val skipped = guided()
        val other = model()
        running(skipped, other)
        other.skip()
        await<ReveilleUiState.Closed>(other) { it.outcome == ClosedOutcome.SKIPPED }
    }

    @Test
    fun `a Mission Lock question stays open over the running clock, and answering it skips`() {
        val mission = testMission(profileId = "builtin_steady").copy(flexibility = Flexibility.RIGID, lockPolicy = LockPolicy.CONFIRM)
        runBlocking { db.missions().insert(mission) }
        val o = guided(mission.id)
        running(o)

        vm.skip()
        awaitUntil(message = "the question") { vm.friction.value != null }
        assertEquals(240, second(minute, 240).remainingSeconds)
        assertEquals("a tick does not take the question away", LockPolicy.CONFIRM, vm.friction.value?.required)

        vm.confirmFriction(FrictionEvidence.Confirmed)
        await<ReveilleUiState.Closed> { it.outcome == ClosedOutcome.SKIPPED }
        assertNull(vm.friction.value)
    }

    @Test
    fun `dismissing the question leaves the starter running`() {
        val mission = testMission(profileId = "builtin_steady").copy(flexibility = Flexibility.RIGID, lockPolicy = LockPolicy.CONFIRM)
        runBlocking { db.missions().insert(mission) }
        val o = guided(mission.id)
        running(o)
        vm.skip()
        awaitUntil(message = "the question") { vm.friction.value != null }

        vm.dismissFriction()

        assertEquals(240, second(minute, 240).remainingSeconds)
        assertEquals(OccurrenceState.GUIDED_START, row(o).state)
    }

    @Test
    fun `silence going unanswered during the starter ends it, because the state says so`() {
        val o = guided()
        running(o)
        runBlocking { repo.apply(o.id, OccurrenceCommand.ReportBlocked, EventSource.SYSTEM) }
        await<ReveilleUiState.Direction>()
    }

    @Test
    fun `another ring taking over stops the countdown of the first`() {
        val first = guided()
        running(first)
        val second = guided(missionId(title = "Second"))
        vm.show(second.id)
        await<ReveilleUiState.Direction> { it.title == "Second" }
        vm.beginStarter()
        await<ReveilleUiState.Starter> { it.title == "Second" }
        assertEquals("the second one's clock is the second one's", clock.nowMs() + 5 * minute, repo.starterOf(row(second))?.deadlineUtc)
        clock.advance(minute)
        ticks.trySend(Unit)
        ticks.trySend(Unit)
        assertEquals("Second", second(0, 240).title)
    }

    @Test
    fun `a ring taking over with a clock of its own is counted to its own deadline, and not to the first one's`() {
        val first = guided()
        val second = guided(missionId(title = "Second"))
        runBlocking { repo.apply(first.id, OccurrenceCommand.BeginStarter, EventSource.USER) }
        clock.advance(4 * minute)
        runBlocking { repo.apply(second.id, OccurrenceCommand.BeginStarter, EventSource.USER) }
        vm.show(first.id)
        await<ReveilleUiState.Starter> { it.title == "Write the report" && it.remainingSeconds == 60 }
        vm.show(second.id)
        await<ReveilleUiState.Starter> { it.title == "Second" && it.remainingSeconds == 300 }

        assertEquals("the first one's deadline goes by", 180, second(2 * minute, 180).remainingSeconds)
        assertEquals("and the second one's clock goes on", 120, second(minute, 120).remainingSeconds)
    }

    @Test
    fun `a recovery restart comes back to the offer`() {
        val o = guided()
        running(o)
        runBlocking {
            repo.apply(o.id, OccurrenceCommand.RequestRecovery, EventSource.USER)
            repo.apply(o.id, OccurrenceCommand.AcceptRecovery(com.command.domain.occurrence.RecoveryChoice.RESET), EventSource.USER)
        }
        await<ReveilleUiState.Direction> { it.offerStarter }
    }

    // --- each line's wiring --------------------------------------------------------------------------------------

    @Test
    fun `the ring screen carries the handshake prompt`() {
        val o = firing()
        vm.show(o.id)
        val s = await<ReveilleUiState.Firing>()
        assertEquals(line(o, CoachingPhase.HANDSHAKE_PROMPT), s.prompt)
        assertTrue(s.prompt.isNotBlank())
    }

    @Test
    fun `an acknowledgement is met with the handshake success line`() {
        val o = guided()
        vm.show(o.id)
        val s = await<ReveilleUiState.Direction>()
        assertEquals(line(o, CoachingPhase.HANDSHAKE_SUCCESS), s.praise)
        assertTrue(s.praise.isNotBlank())
        assertEquals(line(o, CoachingPhase.DIRECTION), s.coaching)
    }

    @Test
    fun `a resumed silence passed no handshake, so nothing praises one`() {
        val o = firing()
        runBlocking {
            repo.apply(o.id, OccurrenceCommand.SafetySilence, EventSource.USER)
            repo.apply(o.id, OccurrenceCommand.Resume, EventSource.USER)
        }
        vm.show(o.id)
        assertEquals("", await<ReveilleUiState.Direction>().praise)
    }

    @Test
    fun `no direction line in any personality claims a handshake`() {
        for (personality in com.command.domain.dials.Personality.values()) {
            for (text in com.command.domain.coaching.TemplateBank.variants(personality, CoachingPhase.DIRECTION)) {
                assertFalse("$personality: $text", text.contains("handshake", ignoreCase = true))
            }
        }
    }

    @Test
    fun `a skip by the person is acknowledged in the coach's words`() {
        val o = guided()
        vm.show(o.id)
        await<ReveilleUiState.Direction>()
        vm.skip()
        val s = await<ReveilleUiState.Closed> { it.outcome == ClosedOutcome.SKIPPED }
        assertEquals(line(o, CoachingPhase.SKIP_ACK), s.coaching)
        assertTrue(s.coaching.isNotBlank())
    }

    @Test
    fun `a skip the calendar made is not answered as the person's own`() {
        val mission = missionId()
        val o = runBlocking {
            val row = repo.createOccurrence(mission, at, "UTC").occurrence
            repo.apply(row.id, OccurrenceCommand.Skip(SkipReason.CALENDAR_CANCELLED), EventSource.WORKER)
            repo.get(row.id)!!
        }
        vm.show(o.id)
        assertEquals("", await<ReveilleUiState.Closed>().coaching)
    }

    @Test
    fun `no other way of closing carries the skip line`() {
        val o = guided()
        vm.show(o.id)
        await<ReveilleUiState.Direction>()
        vm.started()
        assertEquals("", await<ReveilleUiState.Closed>().coaching)
    }

    @Test
    fun `the plain direction of someone blocked carries no line at all`() {
        val o = guided()
        runBlocking { repo.apply(o.id, OccurrenceCommand.ReportBlocked, EventSource.USER) }
        vm.show(o.id)
        val s = await<ReveilleUiState.Direction>()
        assertEquals("", s.coaching)
        assertEquals("", s.praise)
    }

    @Test
    fun `the repeated line takes the direction line's place from the threshold on, and not before`() {
        val mission = missionId()
        repeat(REPEATED_ACK_NO_START_AFTER - 1) { guided(mission) }
        val notYet = guided(mission)
        vm.show(notYet.id)
        assertEquals(line(notYet, CoachingPhase.DIRECTION), await<ReveilleUiState.Direction>().coaching)

        val reached = guided(mission)
        val fresh = model()
        fresh.show(reached.id)
        val s = await<ReveilleUiState.Direction>(fresh)
        assertEquals(line(reached, CoachingPhase.REPEATED_ACK_NO_START), s.coaching)
        assertEquals("the offer stands", true, s.offerStarter)
    }

    @Test
    fun `a reported start takes the repeated line away again`() {
        val mission = missionId()
        repeat(REPEATED_ACK_NO_START_AFTER) { guided(mission) }
        val started = guided(mission)
        runBlocking { repo.apply(started.id, OccurrenceCommand.ReportStarted, EventSource.USER) }
        val next = guided(mission)
        vm.show(next.id)
        assertEquals(line(next, CoachingPhase.DIRECTION), await<ReveilleUiState.Direction>().coaching)
    }

    @Test
    fun `the threshold is what M16's suggestion is written against`() {
        assertEquals(2, REPEATED_ACK_NO_START_AFTER)
    }

    // --- the line in large type ---------------------------------------------------------------------------------

    @Test
    fun `a ring whose voice is not reported as failed shows no line of its own`() {
        vm.show(firing().id)
        assertNull(await<ReveilleUiState.Firing>().unspokenLine)
    }

    @Test
    fun `a phone that could not speak puts the ring's line on the screen, with the mission's words`() {
        val o = firing()
        vm.show(o.id)
        await<ReveilleUiState.Firing>()
        runBlocking { repo.logEvent(EventType.TTS_UNAVAILABLE, row(o), EventSource.SERVICE, EventPayload.of("reason" to "NO_ENGINE")) }
        val s = await<ReveilleUiState.Firing> { it.unspokenLine != null }
        assertEquals(line(o, CoachingPhase.REVEILLE, words = true), s.unspokenLine)
    }

    @Test
    fun `a route that said no puts the ring's line on the screen too`() {
        val o = firing()
        vm.show(o.id)
        await<ReveilleUiState.Firing>()
        runBlocking { repo.logEvent(EventType.AUDIO_ROUTE, row(o), EventSource.SERVICE, EventPayload.of("devices" to "speaker", "policy" to "HEADSET_ONLY", "spoke" to false)) }
        val s = await<ReveilleUiState.Firing> { it.unspokenLine != null }
        assertEquals(line(o, CoachingPhase.REVEILLE, words = true), s.unspokenLine)
    }

    @Test
    fun `a line that was spoken stays off the screen`() {
        val o = firing()
        vm.show(o.id)
        await<ReveilleUiState.Firing>()
        runBlocking { repo.logEvent(EventType.AUDIO_ROUTE, row(o), EventSource.SERVICE, EventPayload.of("devices" to "speaker", "policy" to "ALWAYS", "spoke" to true)) }
        var settled = false
        vm.afterPendingEvents { settled = true }
        awaitUntil(message = "settled") { settled }
        assertNull(await<ReveilleUiState.Firing>().unspokenLine)
    }

    @Test
    fun `a screen shown after the voice failed has the line from the start`() {
        val o = firing()
        runBlocking { repo.logEvent(EventType.TTS_UNAVAILABLE, row(o), EventSource.SERVICE, EventPayload.of("reason" to "NO_OFFLINE_VOICE")) }
        vm.show(o.id)
        await<ReveilleUiState.Firing> { it.unspokenLine != null }
    }

    @Test
    fun `another ring taking over does not inherit the first one's silence`() {
        val first = firing()
        runBlocking { repo.logEvent(EventType.TTS_UNAVAILABLE, row(first), EventSource.SERVICE, EventPayload.of("reason" to "NO_ENGINE")) }
        vm.show(first.id)
        await<ReveilleUiState.Firing> { it.unspokenLine != null }
        val second = firing(missionId(title = "Second"))
        vm.show(second.id)
        val s = await<ReveilleUiState.Firing> { it.title == "Second" }
        assertNull(s.unspokenLine)
    }
}
