package com.command.domain

import com.command.domain.dials.AlertPlan
import com.command.domain.dials.ChainOp
import com.command.domain.dials.CheckInSchedule
import com.command.domain.dials.Dial
import com.command.domain.dials.DialEffects
import com.command.domain.dials.Dials
import com.command.domain.dials.Flexibility
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.ImperativeTier
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.PresetOrigin
import com.command.domain.dials.Presets
import com.command.domain.dials.RecoveryOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DialEffectsTest {

    private val minute = 60_000L
    private val second = 1_000L

    @Test
    fun `dials accept only 1 to 5`() {
        Dials(1, 1, 1, 1, 1, 1, 1)
        Dials(5, 5, 5, 5, 5, 5, 5)
        for (bad in listOf(0, 6, -1)) {
            try {
                Dials(bad, 1, 1, 1, 1, 1, 1)
                fail("$bad must be rejected")
            } catch (expected: IllegalArgumentException) {
                // ok
            }
        }
    }

    @Test
    fun `stored values outside the range are clamped rather than crashing`() {
        assertEquals(Dials(1, 5, 3, 1, 5, 2, 4), Dials.coerced(listOf(-7, 99, 3, 0, 6, 2, 4)))
    }

    @Test
    fun `the five presets start where the spec says`() {
        assertEquals(Dials(1, 1, 1, 1, 1, 1, 1), Presets.dialsFor(PresetOrigin.NUDGE))
        assertEquals(Dials(3, 1, 2, 2, 2, 2, 2), Presets.dialsFor(PresetOrigin.STEADY))
        assertEquals(Dials(4, 2, 3, 3, 3, 3, 3), Presets.dialsFor(PresetOrigin.COMMAND))
        assertEquals(Dials(5, 5, 5, 4, 4, 4, 4), Presets.dialsFor(PresetOrigin.REVEILLE_MAX))
        val mine = Dials(2, 5, 1, 3, 5, 1, 2)
        assertEquals("custom keeps whatever is active", mine, Presets.dialsFor(PresetOrigin.CUSTOM, mine))
    }

    @Test
    fun `reveille max never grants anything beyond the dials`() {
        // MAX is five numbers. Evidence, money and tracking are separate opt-ins that no dial can switch on.
        val max = Presets.dialsFor(PresetOrigin.REVEILLE_MAX)
        assertTrue(Dial.values().all { max[it] in 1..5 })
        assertEquals(7, Dial.values().size)
    }

    @Test
    fun `changing one dial leaves the other six alone`() {
        val base = Presets.dialsFor(PresetOrigin.COMMAND)
        for (dial in Dial.values()) {
            val changed = base.with(dial, 5)
            assertEquals(5, changed[dial])
            for (other in Dial.values().filter { it != dial }) assertEquals("$dial moved $other", base[other], changed[other])
        }
    }

    @Test
    fun `a gentle voice with a hard challenge and an obnoxious voice with a single alert are both expressible`() {
        val gentleHard = Dials(firmness = 1, obnoxiousness = 1, alertIntensity = 1, followUpPersistence = 1, handshakeDifficulty = 5, scheduleRigidity = 1, recoveryStructure = 1)
        assertEquals(ImperativeTier.SUGGEST, DialEffects.imperativeTier(gentleHard.firmness))
        assertEquals(ChainOp.SEQUENCE, DialEffects.defaultChain(gentleHard.handshakeDifficulty).op)

        val loudSingle = Dials(firmness = 5, obnoxiousness = 5, alertIntensity = 1, followUpPersistence = 1, handshakeDifficulty = 1, scheduleRigidity = 1, recoveryStructure = 1)
        assertEquals(2, DialEffects.interjections(loudSingle.obnoxiousness).maxRepeats)
        assertEquals(1, DialEffects.alertPlan(loudSingle.alertIntensity).cycles)
    }

    @Test
    fun `firmness picks the imperative tier`() {
        assertEquals(
            listOf(ImperativeTier.SUGGEST, ImperativeTier.INVITE, ImperativeTier.DIRECT, ImperativeTier.FIRM, ImperativeTier.COMMANDING),
            (1..5).map { DialEffects.imperativeTier(it) },
        )
    }

    @Test
    fun `obnoxiousness adds noise but caps repetition at two and never reaches degradation`() {
        assertEquals((1..5).map { it - 1 }, (1..5).map { DialEffects.interjections(it).density })
        assertEquals(listOf(0, 0, 1, 1, 2), (1..5).map { DialEffects.interjections(it).maxRepeats })
        assertTrue((1..5).all { DialEffects.interjections(it).maxRepeats <= 2 })
    }

    @Test
    fun `alert intensity sets the ring plan and no plan exceeds ten minutes`() {
        assertEquals(listOf(60 * second, 2 * minute, 5 * minute, 8 * minute, 10 * minute), (1..5).map { DialEffects.alertPlan(it).totalMs })
        assertTrue((1..5).all { DialEffects.alertPlan(it).totalMs <= AlertPlan.MAX_TOTAL_MS })
        assertEquals(listOf(1, 1, 2, 3, 3), (1..5).map { DialEffects.alertPlan(it).cycles })
        assertEquals(3, DialEffects.alertPlan(5).cycleWindows().size)
        assertEquals(0L until 3 * minute, DialEffects.alertPlan(5).cycleWindows()[0])
        assertEquals(3 * minute + 30 * second until 6 * minute + 30 * second, DialEffects.alertPlan(5).cycleWindows()[1])
    }

    @Test
    fun `an alert plan over ten minutes is rejected`() {
        try {
            AlertPlan(cycles = 4, cycleMs = 3 * minute, gapMs = 0)
            fail("12 minutes must be rejected")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `alert intensity sets the loudness target of a spoken line`() {
        assertEquals(listOf(-16, -16, -16, -15, -14), (1..5).map { DialEffects.loudnessTargetLufs(it) })
    }

    @Test
    fun `follow-up persistence sets how many questions and how far apart`() {
        assertEquals(listOf(10 * minute), CheckInSchedule.forPersistence(1).gapsMs)
        assertEquals(listOf(10 * minute, 10 * minute), CheckInSchedule.forPersistence(2).gapsMs)
        assertEquals(listOf(7 * minute, 7 * minute), CheckInSchedule.forPersistence(3).gapsMs)
        assertEquals(listOf(5 * minute, 5 * minute, 5 * minute), CheckInSchedule.forPersistence(4).gapsMs)
        assertEquals(listOf(5 * minute, 5 * minute, 5 * minute, 5 * minute), CheckInSchedule.forPersistence(5).gapsMs)
        assertEquals(listOf(1, 2, 2, 3, 4), (1..5).map { DialEffects.checkIns(it).count })
    }

    @Test
    fun `handshake difficulty picks the default chain and the retry budget`() {
        assertEquals(listOf(5, 4, 3, 3, 2), (1..5).map { DialEffects.maxRetries(it) })

        val d1 = DialEffects.defaultChain(1)
        assertEquals(listOf(HandshakeMode.HOLD), d1.steps.map { it.mode })
        assertEquals(2, d1.steps.single().holdSeconds)
        assertEquals(3, DialEffects.defaultChain(2).steps.single().holdSeconds)
        assertEquals(listOf(HandshakeMode.TYPING), DialEffects.defaultChain(3).steps.map { it.mode })

        val d4 = DialEffects.defaultChain(4)
        assertEquals(ChainOp.AND, d4.op)
        assertEquals(listOf(HandshakeMode.CONFIRM_SEQUENCE, HandshakeMode.TYPING), d4.steps.map { it.mode })

        val d5 = DialEffects.defaultChain(5)
        assertEquals(ChainOp.SEQUENCE, d5.op)
        assertEquals(listOf(HandshakeMode.TYPING, HandshakeMode.TWO_STEP), d5.steps.map { it.mode })
        assertEquals(2, d5.steps.first().maxRetries)
    }

    @Test
    fun `P0 handshakes are all reachable from the defaults and every mode has a fallback`() {
        val used = (1..5).flatMap { DialEffects.defaultChain(it).steps.map { s -> s.mode } }.toSet()
        assertTrue(used.containsAll(listOf(HandshakeMode.HOLD, HandshakeMode.TYPING, HandshakeMode.TWO_STEP)))
        for (mode in HandshakeMode.values()) assertTrue(mode.fallback in HandshakeMode.values())
        assertEquals(HandshakeMode.TYPING, HandshakeMode.QR.fallback)
    }

    @Test
    fun `schedule rigidity sets the friction of rigid missions only`() {
        assertEquals(
            listOf(LockPolicy.NONE, LockPolicy.NONE, LockPolicy.CONFIRM, LockPolicy.REASON, LockPolicy.RECOVERY_OFFER),
            (1..5).map { DialEffects.lockPolicy(Flexibility.RIGID, null, it) },
        )
        assertTrue((1..5).all { DialEffects.lockPolicy(Flexibility.FLEXIBLE, null, it) == LockPolicy.NONE })
    }

    @Test
    fun `recovery structure picks a finite menu`() {
        assertEquals(listOf(RecoveryOption.RESET), DialEffects.recoveryPolicy(1).menu)
        assertEquals(listOf(RecoveryOption.RESET, RecoveryOption.RESCHEDULE), DialEffects.recoveryPolicy(2).menu)
        assertEquals(listOf(RecoveryOption.RESET, RecoveryOption.SMALLER, RecoveryOption.RESCHEDULE), DialEffects.recoveryPolicy(3).menu)
        assertEquals(RecoveryOption.SMALLER, DialEffects.recoveryPolicy(5).menu.first())
        assertEquals(listOf(1, 1, 2, 2, 2), (1..5).map { DialEffects.recoveryPolicy(it).maxOffers })
        assertTrue((1..5).all { DialEffects.recoveryPolicy(it).maxOffers <= 2 })
        assertTrue(DialEffects.recoveryPolicy(4).offerTomorrow)
        assertTrue(!DialEffects.recoveryPolicy(3).offerTomorrow)
    }

    @Test
    fun `out-of-range arguments are clamped by every effect`() {
        assertEquals(DialEffects.alertPlan(1), DialEffects.alertPlan(-3))
        assertEquals(DialEffects.alertPlan(5), DialEffects.alertPlan(42))
        assertEquals(DialEffects.checkIns(5), DialEffects.checkIns(9))
        assertEquals(DialEffects.defaultChain(1), DialEffects.defaultChain(0))
    }
}
