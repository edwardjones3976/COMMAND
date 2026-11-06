package com.command.domain

import com.command.domain.dials.LockPolicy
import com.command.domain.dials.PresetOrigin
import com.command.domain.dials.Presets
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceSnapshot
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.OccurrenceStateMachine
import com.command.domain.occurrence.RecoveryChoice
import com.command.domain.occurrence.StarterWindow
import com.command.domain.occurrence.TransitionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterStateMachineTest {
    private val now = 1_000_000L
    private val minute = 60_000L

    private fun snap(state: OccurrenceState, minutes: Int = 5, starter: StarterWindow? = null, recoveryOffers: Int = 0) =
        OccurrenceSnapshot(state, Presets.dialsFor(PresetOrigin.COMMAND), LockPolicy.NONE, recoveryOffers = recoveryOffers, starterMinutes = minutes, starter = starter)

    private fun begin(s: OccurrenceSnapshot) = OccurrenceStateMachine.transition(s, OccurrenceCommand.BeginStarter, now)

    @Test
    fun `the starter begun in guidance writes its deadline from the mission's length, and the state stays where it is`() {
        val r = begin(snap(OccurrenceState.GUIDED_START)) as TransitionResult.Applied
        assertEquals("a starter is not a start", OccurrenceState.GUIDED_START, r.state)
        assertEquals(StarterWindow(now, now + 5 * minute), r.snapshot.starter)
    }

    @Test
    fun `beginning the starter leaves the check-in schedule alone`() {
        val r = begin(snap(OccurrenceState.GUIDED_START)) as TransitionResult.Applied
        assertTrue("no alarm is set, moved or taken down: ${r.effects}", r.effects.isEmpty())
        assertEquals("the questions already asked still count", 0, r.snapshot.checkInsAsked)
    }

    @Test
    fun `the questions asked so far stay counted through the starter`() {
        val r = begin(snap(OccurrenceState.GUIDED_START).copy(checkInsAsked = 1)) as TransitionResult.Applied
        assertEquals(1, r.snapshot.checkInsAsked)
    }

    @Test
    fun `beginning the starter is written down as its own event and not as a change of state`() {
        val r = begin(snap(OccurrenceState.GUIDED_START)) as TransitionResult.Applied
        assertEquals(listOf(EventType.STARTER_BEGUN), r.events.map { it.type })
        assertEquals("5", r.events.single().payload.values["minutes"])
    }

    @Test
    fun `a second tap does not move the deadline`() {
        val running = StarterWindow(now - minute, now + 4 * minute)
        assertTrue(begin(snap(OccurrenceState.GUIDED_START, starter = running)) is TransitionResult.Illegal)
    }

    @Test
    fun `a mission with no starter has none to begin`() {
        assertTrue(begin(snap(OccurrenceState.GUIDED_START, minutes = 0)) is TransitionResult.Illegal)
    }

    @Test
    fun `the starter can be begun from guidance only`() {
        for (state in OccurrenceState.values().filter { it != OccurrenceState.GUIDED_START }) {
            assertTrue("$state", begin(snap(state)) is TransitionResult.Illegal)
        }
    }

    @Test
    fun `a recovery that restarts the guidance clears the old starter, so that a new one can be taken`() {
        val old = StarterWindow(now - 10 * minute, now - 5 * minute)
        val r = OccurrenceStateMachine.transition(
            snap(OccurrenceState.RECOVERY_OFFERED, starter = old, recoveryOffers = 1),
            OccurrenceCommand.AcceptRecovery(RecoveryChoice.RESET),
            now,
        ) as TransitionResult.Applied
        assertEquals(OccurrenceState.GUIDED_START, r.state)
        assertNull(r.snapshot.starter)
    }

    @Test
    fun `any other command keeps the starter as it was`() {
        val running = StarterWindow(now - minute, now + 4 * minute)
        val r = OccurrenceStateMachine.transition(snap(OccurrenceState.GUIDED_START, starter = running), OccurrenceCommand.ReportStarted, now) as TransitionResult.Applied
        assertEquals(running, r.snapshot.starter)
    }

    // --- the countdown -----------------------------------------------------------------------------------------

    private val window = StarterWindow(startUtc = 100_000L, deadlineUtc = 100_000L + 5 * minute)

    @Test
    fun `the clock starts full, rounds up, and is zero only at the deadline`() {
        assertEquals(300, window.remainingSeconds(window.startUtc))
        assertEquals(300, window.remainingSeconds(window.startUtc + 1))
        assertEquals(299, window.remainingSeconds(window.startUtc + 1_000))
        assertEquals(1, window.remainingSeconds(window.deadlineUtc - 1))
        assertEquals(0, window.remainingSeconds(window.deadlineUtc))
        assertEquals("it stays at zero", 0, window.remainingSeconds(window.deadlineUtc + minute))
    }

    @Test
    fun `a clock set back cannot make the countdown longer than the starter`() {
        assertEquals(300, window.remainingSeconds(window.startUtc - 60 * minute))
    }

    @Test
    fun `the midpoint is half way between the start and the deadline`() {
        val half = window.startUtc + (5 * minute) / 2
        assertFalse(window.pastMidpoint(half - 1))
        assertTrue(window.pastMidpoint(half))
        assertTrue(window.pastMidpoint(window.deadlineUtc))
    }

    @Test
    fun `it is over at the deadline and not before`() {
        assertFalse(window.isOver(window.deadlineUtc - 1))
        assertTrue(window.isOver(window.deadlineUtc))
    }

    @Test
    fun `the length is read from the window and not from the mission`() {
        assertEquals(5, window.minutes)
    }
}
