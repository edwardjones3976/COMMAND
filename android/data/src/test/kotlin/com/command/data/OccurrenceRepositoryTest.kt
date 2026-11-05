package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.OccurrenceRow
import com.command.data.db.SettingRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.alarm.CheckInPoster
import com.command.domain.alarm.CheckInPrompt
import com.command.domain.dials.AlertPlan
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.DialEffects
import com.command.domain.dials.Dials
import com.command.domain.dials.Flexibility
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.StepSpec
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.handshake.Attempt
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.CheckInAnswer
import com.command.domain.model.EventSource
import com.command.domain.model.SettingKeys
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.domain.occurrence.WorkKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs on API 24 as well as 36: the same SQL must behave on the oldest SQLite the app supports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36])
class OccurrenceRepositoryTest {

    private lateinit var f: Fixture

    @Before fun setUp() { f = Fixture(ApplicationProvider.getApplicationContext<Context>()) }
    @After fun tearDown() { f.close() }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private suspend fun scheduled(missionTitle: String = "Write the report", atOffsetMs: Long = 60_000): OccurrenceRow {
        val m = f.mission(missionTitle)
        return f.repo.createOccurrence(m.id, f.t0 + atOffsetMs, "UTC").occurrence
    }

    private suspend fun apply(o: OccurrenceRow, c: OccurrenceCommand, extras: ApplyExtras = ApplyExtras.NONE) =
        f.repo.apply(o.id, c, EventSource.USER, extras)

    private suspend fun state(o: OccurrenceRow) = f.repo.get(o.id)!!.state

    // --- creation ----------------------------------------------------------------------------------------------

    @Test
    fun `creating the same occurrence twice returns the first, and request codes are unique and increasing`() = run {
        val m = f.mission()
        val a = f.repo.createOccurrence(m.id, f.t0 + 1_000, "UTC")
        val again = f.repo.createOccurrence(m.id, f.t0 + 1_000, "UTC")
        val b = f.repo.createOccurrence(m.id, f.t0 + 2_000, "UTC")
        assertTrue(a.isNew)
        assertFalse(again.isNew)
        assertEquals(a.occurrence.id, again.occurrence.id)
        assertEquals(a.occurrence.requestCode, again.occurrence.requestCode)
        assertEquals(a.occurrence.requestCode + 1, b.occurrence.requestCode)
        assertEquals(OccurrenceState.SCHEDULED, a.occurrence.state)
        assertEquals(ArmMode.NONE, a.occurrence.armMode)
    }

    @Test
    fun `the database is seeded with the four built-in profiles and the settings the app relies on`() = run {
        val profiles = f.db.profiles().all().map { it.id }
        assertEquals(setOf("builtin_nudge", "builtin_steady", "builtin_command", "builtin_reveille_max"), profiles.toSet())
        assertEquals("\"builtin_steady\"", f.db.settings().get("active_profile_id"))
        assertEquals("1", f.db.settings().get("next_request_code"))
    }

    // --- the alarm lifecycle --------------------------------------------------------------------------------

    @Test
    fun `arming records the alarm, the work and an event`() = run {
        val o = scheduled()
        val r = apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        assertTrue(r is ApplyResult.Applied)
        val row = f.repo.get(o.id)!!
        assertEquals(OccurrenceState.ARMED, row.state)
        assertEquals(1L, row.stateVersion)
        assertEquals(ArmMode.EXACT, row.armMode)
        assertEquals(setOf("command://occurrence/${o.id}"), f.scheduler.armed.keys)
        assertEquals(listOf(WorkKind.PRERENDER to o.id, WorkKind.PREFETCH to o.id), f.work.enqueued)
        assertTrue(f.db.events().forOccurrence(o.id).any { it.type == EventType.ALARM_ARMED })
    }

    @Test
    fun `an inexact result from the platform is stored and logged as adjusted`() = run {
        f.scheduler.exactAllowed = false
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)) // the caller asked for exact; the platform could only do inexact
        assertEquals(ArmMode.INEXACT, f.repo.get(o.id)!!.armMode)
        assertTrue(f.db.events().forOccurrence(o.id).any { it.type == EventType.ALARM_ARMED && it.payloadJson.contains("adjusted") })
    }

    @Test
    fun `firing arms the cycle-end backstop and remembers when the ring plan ends`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        apply(o, OccurrenceCommand.Fire)
        val row = f.repo.get(o.id)!!
        assertEquals(OccurrenceState.FIRING, row.state)
        val backstop = f.scheduler.armed.values.single { it.kind == AlarmKind.CYCLE_END }
        assertEquals(f.t0 + 2 * 60_000L, backstop.atUtcMs)
        assertEquals(f.t0 + 2 * 60_000L, Codec.decodeSession(row.sessionJson).planEndUtc)
        assertEquals(f.clock.elapsedMs + 2 * 60_000L, Codec.decodeSession(row.sessionJson).planEndElapsed)
        assertEquals(1, f.roller.rolls)
    }

    @Test
    fun `acknowledging cancels the backstop and stores the challenge attempts`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        apply(o, OccurrenceCommand.Fire)
        val attempts = listOf(
            ChallengeRecord(0, HandshakeMode.TYPING, 1, ChallengeOutcome.FAILED, f.t0, f.t0 + 4_000),
            ChallengeRecord(0, HandshakeMode.TYPING, 2, ChallengeOutcome.SUCCESS, f.t0 + 4_000, f.t0 + 9_000),
        )
        apply(o, OccurrenceCommand.Acknowledge(), ApplyExtras(challenges = attempts))
        assertEquals(OccurrenceState.ACKNOWLEDGED, state(o))
        assertTrue(f.scheduler.armed.values.none { it.kind == AlarmKind.CYCLE_END })
        val rows = f.db.challenges().forOccurrence(o.id)
        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.SUCCESS), rows.map { it.outcome })
    }

    // --- what a ring asks of the person, and what it has been told so far -------------------------------------------------

    @Test
    fun `the handshake an occurrence asks for is the default chain of its profile's difficulty, in the words of its personality`() = run {
        val expected = mapOf(
            "builtin_nudge" to Triple(ChainOp.OR, listOf(HandshakeMode.HOLD), Personality.SUPPORTIVE_COACH),
            "builtin_steady" to Triple(ChainOp.OR, listOf(HandshakeMode.HOLD), Personality.EXECUTIVE_DIRECTOR),
            "builtin_command" to Triple(ChainOp.OR, listOf(HandshakeMode.TYPING), Personality.ACCOUNTABILITY_PARTNER),
            "builtin_reveille_max" to Triple(ChainOp.AND, listOf(HandshakeMode.CONFIRM_SEQUENCE, HandshakeMode.TYPING), Personality.DRILL_SERGEANT),
        )
        for ((profile, want) in expected) {
            val m = f.mission(profileId = profile)
            val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
            val plan = f.repo.handshakeOf(o)
            assertEquals("$profile: how the steps combine", want.first, plan.chain.op)
            assertEquals("$profile: which steps", want.second, plan.chain.steps.map { it.mode })
            assertEquals("$profile: whose words", want.third, plan.personality)
        }
    }

    @Test
    fun `a mission with no profile of its own gets the active one's handshake`() = run {
        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"builtin_command\""))
        val m = f.mission()
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        val plan = f.repo.handshakeOf(o)
        assertEquals(listOf(HandshakeMode.TYPING), plan.chain.steps.map { it.mode })
        assertEquals(Personality.ACCOUNTABILITY_PARTNER, plan.personality)
    }

    @Test
    fun `the handshake is pinned when the alarm fires, so a profile edited while it sounds changes nothing for the person in the middle of it`() = run {
        val m = f.mission(profileId = "builtin_command") // typing, in the accountability partner's words
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)

        f.db.profiles().upsert(f.db.profiles().get("builtin_command")!!.copy(handshakeDifficulty = 1, personality = Personality.MINIMALIST))

        val plan = f.repo.handshakeOf(f.repo.get(o.id)!!)
        assertEquals("still the typing it was fired with", listOf(HandshakeMode.TYPING), plan.chain.steps.map { it.mode })
        assertEquals("and in the same words", Personality.ACCOUNTABILITY_PARTNER, plan.personality)
        assertEquals("it was the profile that was edited", 1, f.db.profiles().get("builtin_command")!!.handshakeDifficulty)
    }

    @Test
    fun `a chain the person saved is pinned when the alarm fires, so editing it, or going back to the standard, while it sounds changes nothing for the person in the middle of it`() = run {
        val profiles = ProfileRepository(f.db)
        val mine = ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.TYPING, 3, 90), StepSpec(HandshakeMode.HOLD, 2, 45, 5)))
        assertEquals(SaveChainResult.Saved, profiles.saveChain("builtin_command", mine))
        val m = f.mission(profileId = "builtin_command")
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)

        val other = ChainSpec(ChainOp.SEQUENCE, listOf(StepSpec(HandshakeMode.CONFIRM_SEQUENCE, 4, 60)))
        assertEquals(SaveChainResult.Saved, profiles.saveChain("builtin_command", other))
        assertEquals("still the chain it was fired with", mine, f.repo.handshakeOf(f.repo.get(o.id)!!).chain)

        assertTrue(profiles.useDefault("builtin_command"))
        assertEquals("and still, with the profile back on the standard handshake", mine, f.repo.handshakeOf(f.repo.get(o.id)!!).chain)
    }

    @Test
    fun `an alarm fired on the standard handshake keeps it when the profile gets a chain of its own afterwards`() = run {
        val profiles = ProfileRepository(f.db)
        val m = f.mission(profileId = "builtin_command")
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)

        val mine = ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.HOLD, 2, 45, 5)))
        assertEquals(SaveChainResult.Saved, profiles.saveChain("builtin_command", mine))

        assertEquals("still the standard chain of the difficulty it was fired with", DialEffects.defaultChain(3), f.repo.handshakeOf(f.repo.get(o.id)!!).chain)
        val next = f.repo.createOccurrence(m.id, f.t0 + 120_000, "UTC").occurrence
        assertEquals("while one that has not fired follows the profile", mine, f.repo.handshakeOf(next).chain)
    }

    @Test
    fun `a mission that names no profile, which is every mission the app can make, is pinned to the active profile's chain and words when the alarm fires`() = run {
        val profiles = ProfileRepository(f.db)
        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"builtin_command\""))
        val mine = ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.TYPING, 3, 90), StepSpec(HandshakeMode.HOLD, 2, 45, 5)))
        assertEquals(SaveChainResult.Saved, profiles.saveChain("builtin_command", mine))
        val m = f.mission() // names no profile: it has the active one's
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)

        val written = Codec.decodeSession(f.repo.get(o.id)!!.sessionJson)
        assertNotNull("the chain is written into the ring when it fires", written.handshakeChainJson)
        assertEquals("the person's own words, not a plain default", Personality.ACCOUNTABILITY_PARTNER.name, written.handshakePersonality)
        assertEquals("what was saved for the active profile is what the ring asks for", mine, f.repo.handshakeOf(f.repo.get(o.id)!!).chain)

        // Whatever happens to the profile, or to which profile is active, while it sounds changes nothing for the person in front of it.
        val other = ChainSpec(ChainOp.SEQUENCE, listOf(StepSpec(HandshakeMode.CONFIRM_SEQUENCE, 4, 60)))
        assertEquals(SaveChainResult.Saved, profiles.saveChain("builtin_command", other))
        f.db.profiles().upsert(f.db.profiles().get("builtin_command")!!.copy(personality = Personality.MINIMALIST))
        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"builtin_reveille_max\""))
        val after = f.repo.handshakeOf(f.repo.get(o.id)!!)
        assertEquals("still the chain it was fired with", mine, after.chain)
        assertEquals("and still in the same words", Personality.ACCOUNTABILITY_PARTNER, after.personality)
        assertTrue(profiles.useDefault("builtin_command"))
        assertEquals("and with the profile back on the standard handshake", mine, f.repo.handshakeOf(f.repo.get(o.id)!!).chain)
    }

    @Test
    fun `on a fresh install the first alarm of a mission that names no profile asks for the chain saved on the profile that was active from the start`() = run {
        val profiles = ProfileRepository(f.db)
        val mine = ChainSpec(ChainOp.AND, listOf(StepSpec(HandshakeMode.TYPING, 2, 45), StepSpec(HandshakeMode.HOLD, 2, 45, 4)))
        // Nothing was chosen: the profile that is active is the one the database was created with.
        assertEquals(SaveChainResult.Saved, profiles.saveChain("builtin_steady", mine))
        val m = f.mission()
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)

        val plan = f.repo.handshakeOf(f.repo.get(o.id)!!)
        assertEquals(mine, plan.chain)
        assertEquals(Personality.EXECUTIVE_DIRECTOR, plan.personality)
        assertTrue(profiles.useDefault("builtin_steady"))
        assertEquals("pinned: going back to the standard while it sounds changes nothing", mine, f.repo.handshakeOf(f.repo.get(o.id)!!).chain)
    }

    @Test
    fun `a chain that could not be used when the alarm fired stays the standard one when the profile is fixed while it sounds`() = run {
        val profiles = ProfileRepository(f.db)
        // Text that a newer version wrote, or that went wrong: the standard chain of the difficulty is what runs.
        f.db.profiles().setChainJson("builtin_command", "{\"op\":\"OR\",\"steps\":[{\"mode\":\"HOLOGRAM\",\"tries\":3,\"timeoutSeconds\":90}]}")
        val m = f.mission(profileId = "builtin_command")
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        assertEquals("the standard chain of the difficulty runs", DialEffects.defaultChain(3), f.repo.handshakeOf(f.repo.get(o.id)!!).chain)

        // The person fixes it in the editor while the alarm sounds: the chain under them must not change in the middle of the handshake.
        val mine = ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.HOLD, 2, 45, 5)))
        assertEquals(SaveChainResult.Saved, profiles.saveChain("builtin_command", mine))

        assertEquals("still the standard chain it was fired with", DialEffects.defaultChain(3), f.repo.handshakeOf(f.repo.get(o.id)!!).chain)
        val next = f.repo.createOccurrence(m.id, f.t0 + 120_000, "UTC").occurrence
        assertEquals("while one that has not fired follows the profile", mine, f.repo.handshakeOf(next).chain)
    }

    @Test
    fun `a mission that names no profile is fired with the dials of the active profile and not with Steady's, the difficulty and the length of the ring included`() = run {
        // The common case: the active profile has saved no chain, so everything the ring asks for comes from its dials (it is also what a
        // person who came from the previous version with dials of their own gets, because the importer makes that profile the active one).
        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"builtin_reveille_max\""))
        val m = f.mission() // names no profile
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)

        val fired = f.repo.get(o.id)!!
        val written = Codec.decodeSession(fired.sessionJson)
        assertEquals("Reveille Max asks for difficulty 4, Steady for 2", 4, written.handshakeDifficulty)
        assertEquals(DialEffects.defaultChain(4), f.repo.handshakeOf(fired).chain)
        assertNotEquals("the two plans differ, or this proves nothing", AlertPlan.forIntensity(2).totalMs, AlertPlan.forIntensity(5).totalMs)
        assertEquals("and it rings for as long as alert intensity 5 does", f.t0 + AlertPlan.forIntensity(5).totalMs, written.planEndUtc)
        assertEquals("in the cycles the service sounds", AlertPlan.forIntensity(5).cycleWindows(), SessionPlan.of(fired)!!.cycleWindows)

        // Which profile is active while it sounds changes nothing for the person in front of it.
        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"builtin_nudge\""))
        assertEquals(DialEffects.defaultChain(4), f.repo.handshakeOf(f.repo.get(o.id)!!).chain)
        assertEquals(4, Codec.decodeSession(f.repo.get(o.id)!!.sessionJson).handshakeDifficulty)
    }

    @Test
    fun `and it is the row's own dials that count, as the importer writes them for a person who came from the previous version`() = run {
        // The importer makes a profile of the person's own the active one when the old dials had changed; no preset has these.
        val imported = f.db.profiles().get("builtin_steady")!!.copy(
            id = "imported_settings", name = "Imported settings", origin = PresetOrigin.CUSTOM, isBuiltin = false, handshakeChainJson = null,
            firmness = 5, obnoxiousness = 1, alertIntensity = 4, followUpPersistence = 5, handshakeDifficulty = 5, scheduleRigidity = 1, recoveryStructure = 5,
        )
        f.db.profiles().upsert(imported)
        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"imported_settings\""))
        val m = f.mission() // the importer's missions name no profile
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)

        val fired = f.repo.get(o.id)!!
        assertEquals(5, Codec.decodeSession(fired.sessionJson).handshakeDifficulty)
        assertEquals(DialEffects.defaultChain(5), f.repo.handshakeOf(fired).chain)
        assertEquals(AlertPlan.forIntensity(4).cycleWindows(), SessionPlan.of(fired)!!.cycleWindows)
        assertEquals(f.t0 + AlertPlan.forIntensity(4).totalMs, SessionPlan.of(fired)!!.endUtc)
    }

    @Test
    fun `a mission that is switched off but whose alarm is armed still rings with its profile, and the profile counts as in use`() = run {
        val profiles = ProfileRepository(f.db)
        val mine = ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.TYPING, 4, 60)))
        assertEquals(SaveChainResult.Saved, profiles.saveChain("builtin_nudge", mine))
        val m = f.mission(profileId = "builtin_nudge")
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        f.db.missions().update(m.copy(enabled = false)) // nothing in the app does this yet; the alarm is armed all the same

        apply(o, OccurrenceCommand.Fire)

        assertEquals("the ring is fired with the profile the mission names", mine, f.repo.handshakeOf(f.repo.get(o.id)!!).chain)
        assertTrue("and that profile is in use", "builtin_nudge" in profiles.observeInUse().first())
    }

    @Test
    fun `the profile a ring is fired with is the profile that is said to be in use, however the setting or the mission names it`() = run {
        val profiles = ProfileRepository(f.db)
        // Every built-in profile gets a chain of its own, so that the chain a ring is pinned to says which profile it came from.
        val own = listOf("builtin_nudge", "builtin_steady", "builtin_command", "builtin_reveille_max").withIndex().associate { (i, id) ->
            id to ChainSpec(ChainOp.OR, listOf(StepSpec(HandshakeMode.TYPING, 2 + i, 45 + i)))
        }
        for ((id, chain) in own) assertEquals(SaveChainResult.Saved, profiles.saveChain(id, chain))

        // rings: the profile that the ring of the mission that names none came from, then that of the mission that names [named] (null: none)
        class Setup(val label: String, val setting: String?, val named: String?, val rings: List<String?>)
        val setups = listOf(
            Setup("the setting as it is stored, in quotes", "\"builtin_command\"", null, listOf("builtin_command", "builtin_command")),
            Setup("the setting without quotes", "builtin_command", null, listOf("builtin_command", "builtin_command")),
            Setup("a blank setting in quotes", "\"\"", null, listOf(null, null)),
            Setup("a setting with nothing in it", "", null, listOf(null, null)),
            Setup("no setting at all", null, null, listOf(null, null)),
            Setup("a setting that names a profile that is not there", "\"builtin_ghost\"", null, listOf(null, null)),
            Setup("a mission that names another profile", "\"builtin_command\"", "builtin_nudge", listOf("builtin_command", "builtin_nudge")),
            Setup("a mission that names a profile that is not there rings with the active one", "\"builtin_command\"", "builtin_ghost", listOf("builtin_command", "builtin_command")),
            Setup("a mission that names a profile that is not there, and no active profile", null, "builtin_ghost", listOf(null, null)),
        )
        var at = f.t0
        for (setup in setups) {
            if (setup.setting == null) f.db.settings().remove(SettingKeys.ACTIVE_PROFILE_ID)
            else f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, setup.setting))
            val missions = listOf(f.mission(), f.mission(profileId = setup.named))
            val rings = mutableListOf<String?>()
            for (m in missions) {
                at += 60_000
                val o = f.repo.createOccurrence(m.id, at, "UTC").occurrence
                assertTrue("${setup.label}: armed", apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)) is ApplyResult.Applied)
                assertTrue("${setup.label}: fired", apply(o, OccurrenceCommand.Fire) is ApplyResult.Applied)
                val chain = f.repo.handshakeOf(f.repo.get(o.id)!!).chain
                rings += own.entries.firstOrNull { it.value == chain }?.key
            }

            assertEquals("${setup.label}: the profile each ring was fired with", setup.rings, rings)
            assertEquals("${setup.label}: and those are the profiles in use", rings.filterNotNull().toSet(), profiles.observeInUse().first().filter { it in own.keys }.toSet())
            for (m in missions) f.db.missions().softDelete(m.id, f.t0) // the next setup starts from the missions of its own
        }
    }

    @Test
    fun `an alarm that has not fired follows its profile as it is, and one that fires later is pinned to what the profile was then`() = run {
        val m = f.mission(profileId = "builtin_command")
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        f.db.profiles().upsert(f.db.profiles().get("builtin_command")!!.copy(handshakeDifficulty = 1, personality = Personality.MINIMALIST))

        val before = f.repo.handshakeOf(o)
        assertEquals("not fired: the profile as it is", listOf(HandshakeMode.HOLD), before.chain.steps.map { it.mode })
        assertEquals(Personality.MINIMALIST, before.personality)

        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        f.db.profiles().upsert(f.db.profiles().get("builtin_command")!!.copy(handshakeDifficulty = 3, personality = Personality.DRILL_SERGEANT))

        val after = f.repo.handshakeOf(f.repo.get(o.id)!!)
        assertEquals("fired: what the profile was when it fired", listOf(HandshakeMode.HOLD), after.chain.steps.map { it.mode })
        assertEquals(Personality.MINIMALIST, after.personality)
    }

    @Test
    fun `a pinned personality this version does not know gives way to the profile's, and a pinned difficulty still holds`() = run {
        val m = f.mission(profileId = "builtin_command")
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        val row = f.repo.get(o.id)!!
        f.db.occurrences().compareAndSet(
            id = row.id, expectedVersion = row.stateVersion, state = row.state,
            sessionJson = Codec.encodeSession(SessionData(handshakeDifficulty = 1, handshakePersonality = "ROBOT")),
            skipReason = row.skipReason, missReason = row.missReason, armMode = row.armMode, ringMode = row.ringMode,
        )

        val plan = f.repo.handshakeOf(f.repo.get(o.id)!!)
        assertEquals(listOf(HandshakeMode.HOLD), plan.chain.steps.map { it.mode })
        assertEquals(Personality.ACCOUNTABILITY_PARTNER, plan.personality)
    }

    /** An occurrence that is ringing: armed and fired. */
    private suspend fun ringing(missionTitle: String = "Write the report"): OccurrenceRow {
        val o = scheduled(missionTitle)
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        return f.repo.get(o.id)!!
    }

    @Test
    fun `the tries of a ring come back in the order they were written, as the engine reads them`() = run {
        val o = ringing()
        f.repo.recordChallenge(o.id, ChallengeRecord(0, HandshakeMode.TYPING, 1, ChallengeOutcome.FAILED, f.t0, f.t0 + 1_000))
        assertTrue(
            f.repo.recordChallenges(
                o.id,
                listOf(
                    ChallengeRecord(0, HandshakeMode.TYPING, 2, ChallengeOutcome.TIMEOUT, f.t0 + 1_000, f.t0 + 2_000),
                    ChallengeRecord(0, HandshakeMode.TYPING, 2, ChallengeOutcome.FALLBACK, f.t0 + 2_000, f.t0 + 2_000),
                ),
            ),
        )
        assertEquals(
            listOf(
                Attempt(0, HandshakeMode.TYPING, 1, ChallengeOutcome.FAILED),
                Attempt(0, HandshakeMode.TYPING, 2, ChallengeOutcome.TIMEOUT),
                Attempt(0, HandshakeMode.TYPING, 2, ChallengeOutcome.FALLBACK),
            ),
            f.repo.attemptsOf(o.id),
        )
        assertEquals("another ring's tries are not this one's", emptyList<Attempt>(), f.repo.attemptsOf(ringing("Another").id))
    }

    @Test
    fun `a write for an occurrence that does not exist writes nothing and says so, and an empty one writes nothing`() = run {
        val o = ringing()
        val good = ChallengeRecord(0, HandshakeMode.CONFIRM_SEQUENCE, 3, ChallengeOutcome.FAILED, f.t0, f.t0 + 1_000)
        val orphan = ChallengeRecord(0, HandshakeMode.CONFIRM_SEQUENCE, 3, ChallengeOutcome.FALLBACK, f.t0, f.t0 + 1_000)
        assertFalse("nothing is written for an occurrence that does not exist", f.repo.recordChallenges("no-such-occurrence", listOf(good, orphan)))
        assertEquals(emptyList<Attempt>(), f.repo.attemptsOf("no-such-occurrence"))

        assertTrue("nothing to write is written", f.repo.recordChallenges(o.id, emptyList()))
        assertEquals("no rows is no write", emptyList<Attempt>(), f.repo.attemptsOf(o.id))
    }

    @Test
    fun `an alarm that is no longer ringing takes no more rows, however its ring ended`() = run {
        val row = ChallengeRecord(0, HandshakeMode.TYPING, 1, ChallengeOutcome.FAILED, f.t0, f.t0 + 1_000)
        val endings = listOf(
            "Safety silence" to OccurrenceCommand.SafetySilence,
            "a skip" to OccurrenceCommand.Skip(SkipReason.USER),
            "a delay" to OccurrenceCommand.Delay(10),
            "the ring running out" to OccurrenceCommand.Miss(MissReason.TIMEOUT),
            "the acknowledgement" to OccurrenceCommand.Acknowledge(),
        )
        for ((way, ending) in endings) {
            val o = ringing(way)
            assertTrue("$way: while the ring goes on the row is written", f.repo.recordChallenges(o.id, listOf(row)))
            val before = f.repo.attemptsOf(o.id)
            assertTrue("$way: the ring ends", apply(o, ending) is ApplyResult.Applied)

            assertFalse("$way: a row that was waiting for its turn is refused", f.repo.recordChallenges(o.id, listOf(row.copy(attemptNo = 2))))
            assertEquals("$way: and nothing was added to the history", before, f.repo.attemptsOf(o.id))
        }
    }

    @Test
    fun `a stale or illegal command changes nothing and is logged`() = run {
        val o = scheduled()
        val r = apply(o, OccurrenceCommand.Fire) // still SCHEDULED, never armed
        assertTrue(r is ApplyResult.Rejected)
        assertEquals(OccurrenceState.SCHEDULED, state(o))
        assertEquals(0L, f.repo.get(o.id)!!.stateVersion)
        assertTrue(f.scheduler.armed.isEmpty())
        val rejected = f.db.events().ofType(EventType.REJECTED_TRANSITION).single()
        assertEquals(o.id, rejected.occurrenceId)
    }

    @Test
    fun `compare-and-set refuses a writer that read an older version`() = run {
        val o = scheduled()
        val dao = f.db.occurrences()
        val first = dao.compareAndSet(o.id, 0, OccurrenceState.ARMED, "{}", null, null, ArmMode.EXACT, null)
        val stale = dao.compareAndSet(o.id, 0, OccurrenceState.SKIPPED, "{}", SkipReason.USER, null, ArmMode.NONE, null)
        assertEquals(1, first)
        assertEquals("a second writer holding version 0 must lose", 0, stale)
        assertEquals(OccurrenceState.ARMED, state(o))
    }

    @Test
    fun `concurrent applies serialise and exactly one of two competing fires wins`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        val results = kotlinx.coroutines.coroutineScope {
            (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { apply(o, OccurrenceCommand.Fire) } }.awaitAll()
        }
        assertEquals(1, results.count { it is ApplyResult.Applied })
        assertEquals(7, results.count { it is ApplyResult.Rejected })
        assertEquals(1, f.db.events().ofType(EventType.ALARM_FIRED).size)
    }

    // --- delay, recovery, follow-up --------------------------------------------------------------------------

    @Test
    fun `delay creates and arms the child in the same step (#8)`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        apply(o, OccurrenceCommand.Fire)
        val r = apply(o, OccurrenceCommand.Delay(30))
        assertTrue(r is ApplyResult.Applied)
        assertEquals(OccurrenceState.DELAYED, state(o))
        val children = f.db.occurrences().forMission(o.missionId).filter { it.parentOccurrenceId == o.id }
        assertEquals(1, children.size)
        val child = children.single()
        assertEquals(OccurrenceState.ARMED, child.state)
        assertEquals(f.t0 + 30 * 60_000L, child.scheduledAtUtc)
        assertNotEquals(o.requestCode, child.requestCode)
        assertTrue("child alarm is armed", f.scheduler.armed.containsKey("command://occurrence/${child.id}"))
    }

    @Test
    fun `follow-up questions are stored and answered`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        assertTrue(f.scheduler.armed.containsKey("command://checkin/${o.id}/0"))
        apply(o, OccurrenceCommand.CheckInDue(0))
        val asked = f.poster.posted.single()
        assertEquals(listOf(o.id, o.requestCode, 0), listOf(asked.occurrenceId, asked.requestCode, asked.index))
        assertEquals("Write the report", asked.title)
        assertFalse(asked.started)
        var rows = f.db.checkIns().forOccurrence(o.id)
        assertEquals(1, rows.size)
        assertNull(rows.single().answer)
        apply(o, OccurrenceCommand.ReportStarted)
        rows = f.db.checkIns().forOccurrence(o.id)
        assertEquals(CheckInAnswer.STARTED, rows.single().answer)
        assertNotNull(rows.single().answeredAt)
        assertEquals(OccurrenceState.STARTED_SELF_REPORTED, state(o))
    }

    @Test
    fun `protected time defers the follow-up question until the window ends`() = run {
        f.restWindowCoveringNow() // Monday 08:00-10:00 UTC; it is 09:00
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        val firstQuestion = f.scheduler.armed.values.single { it.kind == AlarmKind.CHECK_IN }
        // Steady asks 10 minutes after guidance; here that is 10 minutes after the window closes at 10:00.
        assertEquals(f.t0 + 60 * 60_000L + 10 * 60_000L, firstQuestion.atUtcMs)
        assertTrue(f.db.events().ofType(EventType.FOLLOWUP_SUPPRESSED).isNotEmpty())
    }

    @Test
    fun `every way out of a ring tells the service to stop the sound`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        assertTrue(f.ringer.stopped.isEmpty())
        apply(o, OccurrenceCommand.SafetySilence)
        assertEquals(listOf(o.id), f.ringer.stopped)
    }

    // --- friction ------------------------------------------------------------------------------------------

    @Test
    fun `a rigid mission needs its friction and a flexible one never does`() = run {
        val rigid = f.mission("Standup", Flexibility.RIGID, lock = LockPolicy.REASON)
        val ro = f.repo.createOccurrence(rigid.id, f.t0 + 1_000, "UTC").occurrence
        apply(ro, OccurrenceCommand.Arm(ArmMode.EXACT))
        val denied = apply(ro, OccurrenceCommand.Skip(SkipReason.USER, FrictionEvidence.Confirmed))
        assertEquals(ApplyResult.NeedsFriction(LockPolicy.REASON), denied)
        assertEquals(OccurrenceState.ARMED, state(ro))
        val ok = apply(ro, OccurrenceCommand.Skip(SkipReason.USER, FrictionEvidence.Reason(com.command.domain.dials.SkipReasonCode.NEED_REST)))
        assertTrue(ok is ApplyResult.Applied)
        assertEquals(SkipReason.USER, f.repo.get(ro.id)!!.skipReason)

        val flexible = scheduled("Stretch", atOffsetMs = 90_000)
        apply(flexible, OccurrenceCommand.Arm(ArmMode.EXACT))
        assertTrue(apply(flexible, OccurrenceCommand.Skip(SkipReason.USER)) is ApplyResult.Applied)
    }

    @Test
    fun `recovery is bounded and reports exhaustion without changing state`() = run {
        val m = f.mission(profileId = "builtin_command") // recovery structure 3 allows two offers
        val o = f.repo.createOccurrence(m.id, f.t0 + 1_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        repeat(2) {
            apply(o, OccurrenceCommand.RequestRecovery)
            apply(o, OccurrenceCommand.AcceptRecovery(com.command.domain.occurrence.RecoveryChoice.RESET))
        }
        val third = apply(o, OccurrenceCommand.RequestRecovery)
        assertTrue(third is ApplyResult.RecoveryExhausted)
        assertEquals(OccurrenceState.GUIDED_START, state(o))
    }

    // --- misses and calendar moves -----------------------------------------------------------------------------

    @Test
    fun `a missed occurrence records why`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        apply(o, OccurrenceCommand.Miss(MissReason.DOWNTIME))
        val row = f.repo.get(o.id)!!
        assertEquals(OccurrenceState.MISSED, row.state)
        assertEquals(MissReason.DOWNTIME, row.missReason)
        assertTrue(f.scheduler.armed.isEmpty())
    }

    @Test
    fun `a moved calendar instance keeps its occurrence and replaces its alarm (#12)`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        val moved = f.repo.reschedule(o.id, f.t0 + 3_600_000, "UTC")
        assertTrue(moved)
        val row = f.repo.get(o.id)!!
        assertEquals(f.t0 + 3_600_000, row.scheduledAtUtc)
        assertEquals(o.requestCode, row.requestCode)
        assertEquals(1, f.scheduler.armed.size)
        assertEquals(f.t0 + 3_600_000, f.scheduler.armed.values.single().atUtcMs)
        assertEquals(1, f.db.occurrences().count())
    }

    @Test
    fun `an occurrence that has fired cannot be moved`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        assertFalse(f.repo.reschedule(o.id, f.t0 + 3_600_000, "UTC"))
        assertEquals(o.scheduledAtUtc, f.repo.get(o.id)!!.scheduledAtUtc)
    }

    // --- audit log ------------------------------------------------------------------------------------------

    @Test
    fun `the event log holds ids and enums only, never the mission title`() = run {
        val title = "Secret Project Plan"
        val o = scheduled(title)
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        apply(o, OccurrenceCommand.Skip(SkipReason.USER))
        val all = f.db.events().all()
        assertTrue(all.size >= 6)
        val token = Regex("[A-Za-z0-9_.:+-]{0,64}")
        for (e in all) {
            assertFalse(e.payloadJson.contains(title, ignoreCase = true))
            for ((k, v) in Codec.decodePayload(e.payloadJson)) {
                assertTrue("key $k", token.matches(k))
                assertTrue("value $v", token.matches(v))
            }
        }
        // And every event carries the monotonic clock reading.
        assertTrue(all.all { it.elapsedRealtime == f.clock.elapsedMs })
    }

    @Test
    fun `a mission that names a profile is fired with that profile's dials`() = run {
        val m = f.mission(profileId = "builtin_reveille_max")
        val o = f.repo.createOccurrence(m.id, f.t0 + 1_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        // REVEILLE_MAX alert intensity 5 is nine minutes of ringing plus gaps, ten minutes in all.
        assertEquals(f.t0 + 10 * 60_000L, f.scheduler.armed.values.single { it.kind == AlarmKind.CYCLE_END }.atUtcMs)
        val dials = f.db.profiles().get("builtin_reveille_max")!!
        assertEquals(Dials(5, 5, 5, 4, 4, 4, 4), Dials(dials.firmness, dials.obnoxiousness, dials.alertIntensity, dials.followUpPersistence, dials.handshakeDifficulty, dials.scheduleRigidity, dials.recoveryStructure))
    }

    @Test
    fun `request identities are unique per kind`() {
        val a = AlarmRequest("occ", 7, AlarmKind.FIRE, 0)
        val b = AlarmRequest("occ", 7, AlarmKind.CYCLE_END, 0)
        val c0 = AlarmRequest("occ", 7, AlarmKind.CHECK_IN, 0, index = 0)
        val c1 = AlarmRequest("occ", 7, AlarmKind.CHECK_IN, 0, index = 1)
        assertEquals(4, setOf(a.identity, b.identity, c0.identity, c1.identity).size)
    }

    @Test
    fun `rearm puts back an armed alarm and leaves anything that has moved on alone`() = run {
        val o = scheduled()
        assertNull("not armed yet", f.repo.rearm(o.id))
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        val afterArm = f.scheduler.calls.size

        assertEquals(ArmMode.EXACT, f.repo.rearm(o.id))
        assertEquals("one more arm call", afterArm + 1, f.scheduler.calls.size)

        apply(o, OccurrenceCommand.Fire)
        val afterFire = f.scheduler.calls.size
        assertNull("it is ringing: a reconcile that read the row a moment earlier must not touch it", f.repo.rearm(o.id))
        assertEquals(afterFire, f.scheduler.calls.size)
    }

    @Test
    fun `a question is taken out of the shade when it is answered, and the next one is asked as a progress question`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        f.poster.cancelled.clear()
        apply(o, OccurrenceCommand.CheckInDue(0))
        assertTrue("asking does not take the question away", f.poster.cancelled.isEmpty())

        apply(o, OccurrenceCommand.ReportStarted)
        assertEquals("answering does", listOf(o.id), f.poster.cancelled.distinct())

        apply(o, OccurrenceCommand.CheckInDue(1))
        assertTrue("after a start was reported the question is about progress", f.poster.posted.last().started)
    }

    @Test
    fun `a question that reached the person late is stored with how late, and noted in the log`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        apply(o, OccurrenceCommand.CheckInDue(0, lateMs = 95_000))
        assertEquals(95_000L, f.db.checkIns().forOccurrence(o.id).single().lateMs)
        val late = f.db.events().forOccurrence(o.id).single { it.type == EventType.CHECKIN_LATE }
        assertTrue(late.payloadJson.contains("\"late_s\":\"95\""))
    }

    @Test
    fun `a question that came on time is stored as on time and leaves no late note`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        apply(o, OccurrenceCommand.CheckInDue(0, lateMs = 800))
        assertEquals(800L, f.db.checkIns().forOccurrence(o.id).single().lateMs)
        assertTrue(f.db.events().forOccurrence(o.id).none { it.type == EventType.CHECKIN_LATE })
    }

    // --- effects of two commands never interleave -------------------------------------------------------------------------------------

    /** Holds the question on its way to the shade until the test lets go of it, and writes down what happened in what order. */
    private class HeldPoster : CheckInPoster {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        override fun post(prompt: CheckInPrompt) {
            order += "post"
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
            order += "posted"
        }

        override fun cancel(occurrenceId: String) {
            order += "cancel"
        }
    }

    @Test
    fun `an answer that arrives while the question is still on its way waits for it, so that taking the question away is the last word`() = run {
        val poster = HeldPoster()
        val repo = OccurrenceRepository(f.db, f.clock, f.scheduler, f.work, poster, f.roller, f.ringer)
        val m = f.mission()
        val o = repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        for (c in listOf(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance)) {
            repo.apply(o.id, c, EventSource.SYSTEM)
        }
        poster.order.clear() // getting here took a cancel or two of its own; what counts is what happens from now on

        val threads = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val question = threads.async { repo.apply(o.id, OccurrenceCommand.CheckInDue(0), EventSource.RECEIVER) }
        assertTrue("the question reached the poster", poster.entered.await(30, TimeUnit.SECONDS))

        // The person answers while that is still going on.
        val answer = threads.async { repo.apply(o.id, OccurrenceCommand.ReportDone, EventSource.USER) }
        assertNull("it has to wait its turn", withTimeoutOrNull(700) { answer.await() })

        poster.release.countDown()
        assertTrue(question.await() is ApplyResult.Applied)
        assertTrue(answer.await() is ApplyResult.Applied)

        assertEquals(OccurrenceState.COMPLETED_SELF_REPORTED, repo.get(o.id)!!.state)
        assertEquals("the question was posted first and taken away last", listOf("post", "posted", "cancel"), poster.order.toList())
        assertTrue(
            "and the follow-up alarm the question armed is gone",
            f.scheduler.armed.keys.none { it.startsWith("command://checkin/${o.id}") },
        )
    }

    @Test
    fun `tomorrow from the recovery menu is the same local time in the occurrence's zone`() = run {
        val ny = java.time.ZoneId.of("America/New_York")
        fun at(y: Int, m: Int, d: Int) = java.time.ZonedDateTime.of(y, m, d, 9, 0, 0, 0, ny).toInstant().toEpochMilli()
        val m = f.mission(profileId = "builtin_reveille_max") // its recovery setting offers tomorrow
        f.clock.nowMs = at(2026, 10, 31)
        val o = f.repo.createOccurrence(m.id, f.clock.nowMs + 60_000, "America/New_York").occurrence
        for (c in listOf(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance, OccurrenceCommand.RequestRecovery)) {
            apply(o, c)
        }
        assertEquals(OccurrenceState.RECOVERY_OFFERED, state(o))

        val r = apply(o, OccurrenceCommand.RescheduleRecovery(OccurrenceCommand.TOMORROW_MINUTES))
        assertTrue(r is ApplyResult.Applied)
        val child = f.db.occurrences().forMission(m.id).single { it.parentOccurrenceId == o.id }
        assertEquals("09:00 the next morning, 25 hours later because the clocks went back that night", at(2026, 11, 1), child.scheduledAtUtc)
    }

    @Test
    fun `a caller that blocks the main thread is never stuck behind a command that was started on it`() = run {
        val o = scheduled()
        for (c in listOf(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance)) apply(o, c)
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())

        // A command that starts on the main looper takes the occurrence's lock and goes into its transaction. To go on it needs the main looper
        // again, so nothing it holds may be waited for by a thread that is blocking the main looper.
        val onMain = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).async { f.repo.apply(o.id, OccurrenceCommand.ReportStarted, EventSource.USER) }
        looper.idle()

        // This is the main thread, and it blocks here until the second command has run, as a test or a caller using runBlocking does.
        val second = withTimeoutOrNull(10_000) { f.repo.apply(o.id, OccurrenceCommand.ReportWorking, EventSource.USER) }
        assertNotNull("the second command ran", second)

        val end = System.currentTimeMillis() + 10_000
        while (!onMain.isCompleted && System.currentTimeMillis() < end) {
            looper.idle()
            Thread.sleep(5)
        }
        assertTrue("and so did the first", onMain.isCompleted)
    }

    @Test
    fun `the detail of an occurrence has its events, its questions and its attempts, and an unknown one has none`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        apply(o, OccurrenceCommand.CheckInDue(0))
        apply(o, OccurrenceCommand.ReportStarted)

        val d = f.repo.detail(o.id)!!
        assertEquals("Write the report", d.title)
        assertEquals(OccurrenceState.STARTED_SELF_REPORTED, d.occurrence.state)
        assertTrue(d.events.any { it.type == EventType.ALARM_ARMED })
        assertTrue(d.events.any { it.type == EventType.CHECK_IN_ASKED })
        assertEquals(CheckInAnswer.STARTED, d.checkIns.single().answer)
        assertNull(f.repo.detail("no-such-occurrence"))
    }

    private suspend fun armedAt(): OccurrenceRow {
        val o = scheduled()
        apply(o, com.command.domain.occurrence.OccurrenceCommand.Arm(ArmMode.EXACT))
        return f.repo.get(o.id)!!
    }

    @Test
    fun `a Fire for a time the occurrence has left is rejected and the alarm is set for the new time`() = run {
        val o = armedAt()
        val moved = o.scheduledAtUtc + 3_600_000L
        f.repo.reschedule(o.id, moved, "UTC")
        f.scheduler.armed.clear()
        val r = apply(o, com.command.domain.occurrence.OccurrenceCommand.Fire, ApplyExtras(setForUtc = o.scheduledAtUtc))
        assertTrue(r is ApplyResult.Rejected)
        assertEquals(OccurrenceState.ARMED, state(o))
        assertEquals(listOf(moved), f.scheduler.armed.values.map { it.atUtcMs })
    }

    @Test
    fun `a Fire for the occurrence's own time, or that names no time, fires`() = run {
        val a = armedAt()
        assertTrue(apply(a, com.command.domain.occurrence.OccurrenceCommand.Fire, ApplyExtras(setForUtc = a.scheduledAtUtc)) is ApplyResult.Applied)
        val b = scheduled(atOffsetMs = 120_000)
        apply(b, com.command.domain.occurrence.OccurrenceCommand.Arm(ArmMode.EXACT))
        assertTrue(apply(b, com.command.domain.occurrence.OccurrenceCommand.Fire) is ApplyResult.Applied)
    }

    @Test
    fun `a Fire delivered again to a ring that is going sets no alarm, whatever time it names`() = run {
        val o = armedAt()
        apply(o, com.command.domain.occurrence.OccurrenceCommand.Fire)
        f.scheduler.armed.keys.filter { it.startsWith("command://occurrence/") }.forEach { f.scheduler.armed.remove(it) }
        val r = apply(o, com.command.domain.occurrence.OccurrenceCommand.Fire, ApplyExtras(setForUtc = o.scheduledAtUtc - 1))
        assertTrue(r is ApplyResult.Rejected)
        assertTrue(f.scheduler.armed.keys.none { it.startsWith("command://occurrence/") })
    }

    @Test
    fun `a rejected Fire that is not about a moved time sets no alarm`() = run {
        val o = scheduled()
        val r = apply(o, com.command.domain.occurrence.OccurrenceCommand.Fire, ApplyExtras(setForUtc = o.scheduledAtUtc))
        assertTrue(r is ApplyResult.Rejected)
        assertTrue(f.scheduler.armed.isEmpty())
    }
}
