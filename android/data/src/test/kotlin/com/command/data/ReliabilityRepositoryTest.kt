package com.command.data

import androidx.test.core.app.ApplicationProvider
import com.command.data.db.EventRow
import com.command.domain.model.EventSource
import com.command.domain.occurrence.EventType
import com.command.domain.reliability.AlarmOutcomeKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReliabilityRepositoryTest {

    private lateinit var f: Fixture
    private lateinit var repository: ReliabilityRepository

    @Before
    fun setUp() {
        f = Fixture(ApplicationProvider.getApplicationContext())
        repository = ReliabilityRepository(f.db, f.clock)
    }

    @After
    fun tearDown() = f.close()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    /** An alarm due [offsetMs] after the fixture's start, with the audit-log events the test gives it. */
    private suspend fun alarm(title: String, offsetMs: Long, fired: Long? = null, missedOffAt: Long? = null, category: String? = null): String {
        val mission = Fixture.missionRow(title, startAt = f.t0 + offsetMs).let { if (category != null) it.copy(category = category) else it }
        f.db.missions().insert(mission)
        val occurrence = f.repo.createOccurrence(mission.id, f.t0 + offsetMs, "UTC").occurrence
        if (fired != null) event(occurrence.id, EventType.ALARM_FIRED, fired)
        if (missedOffAt != null) event(occurrence.id, EventType.MISSED_WHILE_OFF, missedOffAt)
        return occurrence.id
    }

    private suspend fun event(occurrenceId: String, type: EventType, at: Long) {
        f.db.events().insert(
            EventRow(
                occurrenceId = occurrenceId, missionId = null, type = type, atUtc = at, elapsedRealtime = 0, bootId = null,
                source = EventSource.SERVICE, payloadJson = "{}",
            ),
        )
    }

    private suspend fun recent(limit: Int = 5) = repository.recentAlarms(limit).first()

    @Test
    fun `an alarm that rang within seconds of its time is on time`() = run {
        alarm("Standup", offsetMs = 60_000, fired = f.t0 + 62_500)
        val outcome = recent().single()
        assertEquals("Standup", outcome.title)
        assertEquals(AlarmOutcomeKind.ON_TIME, outcome.kind)
        assertNull(outcome.lateSeconds)
        assertFalse(outcome.isTest)
    }

    @Test
    fun `an alarm that rang late says how late`() = run {
        alarm("Standup", offsetMs = 60_000, fired = f.t0 + 60_000 + 95_000)
        val outcome = recent().single()
        assertEquals(AlarmOutcomeKind.LATE, outcome.kind)
        assertEquals(95L, outcome.lateSeconds)
    }

    @Test
    fun `an alarm found missed while the phone was off says so, and how late it was found`() = run {
        alarm("Standup", offsetMs = 60_000, missedOffAt = f.t0 + 60_000 + 3 * 3_600_000)
        val outcome = recent().single()
        assertEquals(AlarmOutcomeKind.MISSED_WHILE_OFF, outcome.kind)
        assertEquals(10_800L, outcome.lateSeconds)
    }

    @Test
    fun `an alarm that has neither rung nor been lost is not listed`() = run {
        alarm("Later", offsetMs = 3_600_000)
        assertTrue(recent().isEmpty())
    }

    @Test
    fun `the newest come first, only as many as asked for are returned, and a test alarm is marked as one`() = run {
        for (i in 1..7) alarm("Alarm $i", offsetMs = i * 60_000L, fired = f.t0 + i * 60_000L + 1_000)
        alarm("Try it", offsetMs = 8 * 60_000L, fired = f.t0 + 8 * 60_000L + 2_000, category = MissionRepository.TEST_CATEGORY)

        val outcomes = recent(limit = 5)
        assertEquals(listOf("Try it", "Alarm 7", "Alarm 6", "Alarm 5", "Alarm 4"), outcomes.map { it.title })
        assertTrue(outcomes.first().isTest)
        assertTrue(outcomes.drop(1).none { it.isTest })
    }

    @Test
    fun `a mission the person deleted is not listed, but the hidden mission behind a test alarm is, because it is deleted by design`() = run {
        val gone = alarm("Deleted", offsetMs = 60_000, fired = f.t0 + 61_000)
        f.db.missions().softDelete(f.db.occurrences().get(gone)!!.missionId, f.t0)
        val test = Fixture.missionRow("Try it", startAt = f.t0 + 120_000).copy(category = MissionRepository.TEST_CATEGORY, enabled = false, deletedAt = f.t0)
        f.db.missions().insert(test)
        val testOccurrence = f.repo.createOccurrence(test.id, f.t0 + 120_000, "UTC").occurrence
        event(testOccurrence.id, EventType.ALARM_FIRED, f.t0 + 121_000)

        val listed = recent()
        assertEquals(listOf("Try it"), listed.map { it.title })
        assertTrue(listed.single().isTest)
    }

    @Test
    fun `an alarm that rang and was also recorded as missed counts as rung, because ringing is what happened first`() = run {
        alarm("Both", offsetMs = 60_000, fired = f.t0 + 62_000, missedOffAt = f.t0 + 400_000)
        assertEquals(AlarmOutcomeKind.ON_TIME, recent().single().kind)
    }

    @Test
    fun `the list follows the audit log as new alarms ring`() = run {
        val id = alarm("Standup", offsetMs = 60_000)
        assertTrue(recent().isEmpty())
        event(id, EventType.ALARM_FIRED, f.t0 + 61_000)
        assertEquals(1, recent().size)
    }

    @Test
    fun `alarms refused in the last day are counted, each once, and what is older is not`() = run {
        val a = alarm("A", offsetMs = 60_000)
        val b = alarm("B", offsetMs = 120_000)
        val old = alarm("Old", offsetMs = 180_000)
        assertEquals(0, repository.refusedAlarms().first())

        event(a, EventType.ALARM_CAP, f.t0)
        event(a, EventType.ALARM_CAP, f.t0 + 1_000) // the same alarm, tried again
        event(b, EventType.ALARM_CAP, f.t0 - 3_600_000)
        event(old, EventType.ALARM_CAP, f.t0 - 25 * 3_600_000)

        assertEquals(2, repository.refusedAlarms().first())
    }
}
