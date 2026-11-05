package com.command.domain

import com.command.domain.model.ConsentKey
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.PreconditionChange
import com.command.domain.reliability.PreconditionSnapshot
import com.command.domain.reliability.PreconditionValue
import com.command.domain.reliability.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreconditionsTest {

    private fun snapshot(vararg pairs: Pair<Precondition, String>) = PreconditionSnapshot(mapOf(*pairs))

    @Test
    fun `with nothing to compare to, everything is a change from nothing`() {
        val s = snapshot(Precondition.EXACT_ALARMS to "granted", Precondition.ALARM_VOLUME to "audible")
        assertEquals(
            listOf(
                PreconditionChange(Precondition.EXACT_ALARMS, null, "granted"),
                PreconditionChange(Precondition.ALARM_VOLUME, null, "audible"),
            ),
            s.changesFrom(null),
        )
    }

    @Test
    fun `an unchanged snapshot has no changes and a flipped value has one`() {
        val before = snapshot(Precondition.NOTIFICATIONS to "granted", Precondition.ALARM_VOLUME to "audible")
        assertTrue(before.changesFrom(before).isEmpty())
        val after = snapshot(Precondition.NOTIFICATIONS to "denied", Precondition.ALARM_VOLUME to "audible")
        assertEquals(listOf(PreconditionChange(Precondition.NOTIFICATIONS, "granted", "denied")), after.changesFrom(before))
    }

    @Test
    fun `only the three permissions are consents, and each names its consent key`() {
        val permissions = Precondition.values().filter { it.isPermission }
        assertEquals(setOf(Precondition.EXACT_ALARMS, Precondition.NOTIFICATIONS, Precondition.FULL_SCREEN_INTENT), permissions.toSet())
        assertEquals(ConsentKey.EXACT_ALARM, Precondition.EXACT_ALARMS.consent)
        assertEquals(ConsentKey.NOTIFICATIONS, Precondition.NOTIFICATIONS.consent)
        assertEquals(ConsentKey.FULL_SCREEN, Precondition.FULL_SCREEN_INTENT.consent)
        assertFalse(Precondition.ALARM_VOLUME.isPermission)
    }

    @Test
    fun `what stops an alarm is a problem, what merely makes it less likely is a warning, advice is info`() {
        assertEquals(Severity.PROBLEM, Precondition.EXACT_ALARMS.severity(PreconditionValue.DENIED))
        assertEquals(Severity.OK, Precondition.EXACT_ALARMS.severity(PreconditionValue.GRANTED))
        assertEquals(Severity.PROBLEM, Precondition.NOTIFICATIONS.severity(PreconditionValue.DENIED))
        assertEquals(Severity.PROBLEM, Precondition.FULL_SCREEN_INTENT.severity(PreconditionValue.DENIED))
        assertEquals(Severity.PROBLEM, Precondition.ALARM_VOLUME.severity(PreconditionValue.MUTED))
        assertEquals(Severity.OK, Precondition.ALARM_VOLUME.severity(PreconditionValue.AUDIBLE))
        assertEquals(Severity.OK, Precondition.ALARM_CHANNEL.severity("HIGH"))
        assertEquals(Severity.PROBLEM, Precondition.ALARM_CHANNEL.severity("LOW"))
        assertEquals(Severity.WARNING, Precondition.BACKGROUND_RESTRICTED.severity(PreconditionValue.YES))
        assertEquals(Severity.WARNING, Precondition.STANDBY_BUCKET.severity("RESTRICTED"))
        assertEquals(Severity.WARNING, Precondition.STANDBY_BUCKET.severity("RARE"))
        assertEquals(Severity.OK, Precondition.STANDBY_BUCKET.severity("ACTIVE"))
        assertEquals(Severity.INFO, Precondition.BATTERY_OPTIMIZATION.severity(PreconditionValue.OPTIMIZED))
        assertEquals(Severity.OK, Precondition.BATTERY_OPTIMIZATION.severity(PreconditionValue.EXEMPT))
    }

    @Test
    fun `tokens round trip and a name from a newer version is ignored`() {
        val s = snapshot(Precondition.STANDBY_BUCKET to "ACTIVE", Precondition.NOTIFICATIONS to "granted")
        val back = PreconditionSnapshot.fromTokens(s.toTokens() + ("SOMETHING_NEW" to "x"))
        assertEquals(s.values, back.values)
    }
}
