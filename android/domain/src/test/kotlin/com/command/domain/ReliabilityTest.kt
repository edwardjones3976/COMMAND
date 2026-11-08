package com.command.domain

import com.command.domain.model.ConsentBoundaries
import com.command.domain.model.ConsentCascade
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentOwner
import com.command.domain.reliability.AlarmOutcomeKind
import com.command.domain.reliability.AlarmOutcomes
import com.command.domain.reliability.Fix
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.PreconditionSnapshot
import com.command.domain.reliability.Reliability
import com.command.domain.reliability.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityTest {

    private fun healthy() = mapOf(
        Precondition.EXACT_ALARMS to "granted", Precondition.NOTIFICATIONS to "granted", Precondition.FULL_SCREEN_INTENT to "granted",
        Precondition.ALARM_CHANNEL to "HIGH", Precondition.CHECK_IN_CHANNEL to "HIGH", Precondition.ALARM_VOLUME to "audible", Precondition.DO_NOT_DISTURB to "free",
        Precondition.BATTERY_OPTIMIZATION to "exempt",
        Precondition.BACKGROUND_RESTRICTED to "no", Precondition.STANDBY_BUCKET to "ACTIVE",
    )

    private fun report(vararg changed: Pair<Precondition, String>) = Reliability.assess(PreconditionSnapshot(healthy() + mapOf(*changed)))

    @Test
    fun `when everything is as it should be the report is healthy and offers nothing to fix`() {
        val report = report()
        assertTrue(report.isHealthy)
        assertEquals(Severity.OK, report.worst)
        assertTrue(report.items.all { it.fix == null })
        assertEquals(Precondition.values().size, report.items.size)
    }

    @Test
    fun `what stops an alarm comes first, then what may disturb one, then advice`() {
        val report = report(
            Precondition.BATTERY_OPTIMIZATION to "optimized", Precondition.STANDBY_BUCKET to "RARE",
            Precondition.NOTIFICATIONS to "denied", Precondition.ALARM_VOLUME to "muted",
        )
        assertEquals(
            listOf(Severity.PROBLEM, Severity.PROBLEM, Severity.WARNING, Severity.INFO),
            report.items.take(4).map { it.severity },
        )
        assertTrue(report.items.drop(4).all { it.severity == Severity.OK })
        assertEquals("within a severity the order is the order the preconditions are declared", Precondition.NOTIFICATIONS, report.problems.first().precondition)
        assertEquals(2, report.problems.size)
        assertEquals(1, report.warnings.size)
        assertFalse(report.isHealthy)
        assertEquals(Severity.PROBLEM, report.worst)
    }

    @Test
    fun `advice alone does not make a report unhealthy`() {
        val report = report(Precondition.BATTERY_OPTIMIZATION to "optimized")
        assertTrue(report.isHealthy)
        assertEquals(Severity.INFO, report.worst)
        assertEquals(Fix.OPEN_BATTERY_SETTINGS, report.of(Precondition.BATTERY_OPTIMIZATION)!!.fix)
    }

    @Test
    fun `each thing that can be fixed points to the screen that fixes it, and the standby bucket points nowhere`() {
        val report = report(
            Precondition.EXACT_ALARMS to "denied", Precondition.NOTIFICATIONS to "denied", Precondition.FULL_SCREEN_INTENT to "denied",
            Precondition.ALARM_CHANNEL to "LOW", Precondition.ALARM_VOLUME to "muted", Precondition.BACKGROUND_RESTRICTED to "yes",
            Precondition.STANDBY_BUCKET to "RESTRICTED",
        )
        assertEquals(Fix.OPEN_EXACT_ALARM_SETTINGS, report.of(Precondition.EXACT_ALARMS)!!.fix)
        assertEquals(Fix.ASK_NOTIFICATIONS, report.of(Precondition.NOTIFICATIONS)!!.fix)
        assertEquals(Fix.OPEN_FULL_SCREEN_SETTINGS, report.of(Precondition.FULL_SCREEN_INTENT)!!.fix)
        assertEquals(Fix.OPEN_ALARM_CHANNEL_SETTINGS, report.of(Precondition.ALARM_CHANNEL)!!.fix)
        assertEquals(Fix.OPEN_SOUND_SETTINGS, report.of(Precondition.ALARM_VOLUME)!!.fix)
        assertEquals(Fix.OPEN_APP_SETTINGS, report.of(Precondition.BACKGROUND_RESTRICTED)!!.fix)
        assertNull(report.of(Precondition.STANDBY_BUCKET)!!.fix)
        assertEquals(Severity.WARNING, report.of(Precondition.STANDBY_BUCKET)!!.severity)
    }

    @Test
    fun `a precondition the platform did not report is left out rather than guessed`() {
        val partial = Reliability.assess(PreconditionSnapshot(mapOf(Precondition.NOTIFICATIONS to "denied")))
        assertEquals(1, partial.items.size)
        assertNull(partial.of(Precondition.EXACT_ALARMS))
    }

    @Test
    fun `every consent the center lists is either Android's, with a setting read and a screen to go to, or the app's own, with no setting standing for it`() {
        assertEquals(ConsentBoundaries.all.size, ConsentBoundaries.all.map { it.key }.toSet().size)
        for (boundary in ConsentBoundaries.all) {
            val preconditions = Precondition.values().filter { it.consent == boundary.key }
            when (boundary.owner) {
                ConsentOwner.PLATFORM -> {
                    assertEquals("${boundary.key} is read from exactly one setting", 1, preconditions.size)
                    assertTrue("${boundary.key} has somewhere to send the person", preconditions.single().fix != null)
                }
                ConsentOwner.APP -> assertTrue("${boundary.key} is the app's to keep, so no Android setting stands for it", preconditions.isEmpty())
            }
        }
    }

    @Test
    fun `the camera consent for the QR handshake is listed after the calendar, is the app's own, and withdraws nothing else`() {
        assertEquals(
            listOf(ConsentKey.NOTIFICATIONS, ConsentKey.EXACT_ALARM, ConsentKey.FULL_SCREEN, ConsentKey.VOLUME_CONTROL, ConsentKey.CALENDAR_READ, ConsentKey.CAMERA_QR),
            ConsentBoundaries.all.map { it.key },
        )
        assertEquals(ConsentOwner.APP, ConsentBoundaries.all.single { it.key == ConsentKey.CAMERA_QR }.owner)
        assertEquals(emptyList<ConsentKey>(), ConsentCascade.dependents(ConsentKey.CAMERA_QR))
    }

    // --- how recent alarms are read --------------------------------------------------------------------------

    private fun outcome(fired: Long?, missedOff: Boolean = false, recorded: Long? = null) =
        AlarmOutcomes.classify("id", "Standup", scheduledAtUtc = 1_000_000, firedAtUtc = fired, missedWhileOff = missedOff, recordedAtUtc = recorded, isTest = false)

    @Test
    fun `a ring within fifteen seconds of its time is on time, a later one says how late`() {
        assertEquals(AlarmOutcomeKind.ON_TIME, outcome(fired = 1_002_500)!!.kind)
        assertEquals(AlarmOutcomeKind.ON_TIME, outcome(fired = 1_000_000 + AlarmOutcomes.ON_TIME_MS)!!.kind)
        val late = outcome(fired = 1_000_000 + AlarmOutcomes.ON_TIME_MS + 1)!!
        assertEquals(AlarmOutcomeKind.LATE, late.kind)
        assertEquals(15L, late.lateSeconds)
        assertEquals(125L, outcome(fired = 1_125_900)!!.lateSeconds)
        assertNull(outcome(fired = 1_002_500)!!.lateSeconds)
    }

    @Test
    fun `an alarm that never rang is reported only when it was recorded as missed while the phone was off`() {
        val missed = outcome(fired = null, missedOff = true, recorded = 11_800_000)!!
        assertEquals(AlarmOutcomeKind.MISSED_WHILE_OFF, missed.kind)
        assertEquals(10_800L, missed.lateSeconds)
        assertNull("nothing rang and nothing was lost, so there is nothing to say", outcome(fired = null))
    }

    @Test
    fun `a ring is never reported as early`() {
        val early = outcome(fired = 999_000)!!
        assertEquals(AlarmOutcomeKind.ON_TIME, early.kind)
    }

    @Test
    fun `Do Not Disturb that blocks alarms is a problem with a way to the sound settings, and one that lets them through is not`() {
        val blocked = report(Precondition.DO_NOT_DISTURB to "blocks_alarms")
        assertEquals(Severity.PROBLEM, blocked.of(Precondition.DO_NOT_DISTURB)!!.severity)
        assertEquals(Fix.OPEN_SOUND_SETTINGS, blocked.of(Precondition.DO_NOT_DISTURB)!!.fix)
        assertFalse(blocked.isHealthy)

        assertEquals(Severity.OK, report().of(Precondition.DO_NOT_DISTURB)!!.severity)
    }
}
