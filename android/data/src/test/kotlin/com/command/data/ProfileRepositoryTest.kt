package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.ProfileRow
import com.command.data.db.SettingRow
import com.command.domain.dials.ChainIssue
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainProblem
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.DialEffects
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.PresetOrigin
import com.command.domain.dials.StepSpec
import com.command.domain.model.SettingKeys
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class ProfileRepositoryTest {

    private lateinit var f: Fixture
    private lateinit var profiles: ProfileRepository

    @Before
    fun setUp() {
        f = Fixture(ApplicationProvider.getApplicationContext<Context>())
        profiles = ProfileRepository(f.db)
    }

    @After fun tearDown() { f.close() }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private val mine = ChainSpec(
        ChainOp.SEQUENCE,
        listOf(StepSpec(HandshakeMode.HOLD, 2, 45, 6), StepSpec(HandshakeMode.TYPING, 3, 120)),
    )

    @Test
    fun `the four built-in profiles ask for the standard chain of their difficulty until a chain is saved`() = run {
        val all = profiles.observe().first()
        assertEquals(listOf("builtin_nudge", "builtin_steady", "builtin_command", "builtin_reveille_max").toSet(), all.map { it.id }.toSet())
        for (p in all) {
            assertNull("${p.id} has no chain of its own", p.own)
            assertFalse(p.storedUnusable)
            assertEquals(DialEffects.defaultChain(p.difficulty), p.chain)
        }
    }

    @Test
    fun `a saved chain is the profile's own, and is what the ring asks for`() = run {
        assertEquals(SaveChainResult.Saved, profiles.saveChain("builtin_steady", mine))

        val p = profiles.get("builtin_steady")!!
        assertEquals(mine, p.own)
        assertEquals(mine, p.chain)
        assertFalse(p.storedUnusable)

        val mission = f.mission(profileId = "builtin_steady")
        val occurrence = f.repo.createOccurrence(mission.id, f.t0 + 60_000, "UTC").occurrence
        assertEquals("the ring asks for it", mine, f.repo.handshakeOf(occurrence).chain)
    }

    @Test
    fun `another profile keeps its own handshake`() = run {
        profiles.saveChain("builtin_steady", mine)
        assertNull(profiles.get("builtin_command")!!.own)
    }

    @Test
    fun `a chain with a problem is refused with every problem, and nothing is written`() = run {
        val bad = ChainSpec(ChainOp.AND, listOf(StepSpec(HandshakeMode.HOLD, 0, 90, 3), StepSpec(HandshakeMode.HOLD, 3, 5)))

        val result = profiles.saveChain("builtin_steady", bad)

        assertEquals(
            SaveChainResult.Invalid(
                listOf(
                    ChainIssue(ChainProblem.TRIES_OUT_OF_RANGE, 0),
                    ChainIssue(ChainProblem.TIME_OUT_OF_RANGE, 1),
                    ChainIssue(ChainProblem.SAME_WAY_TWICE, 1),
                ),
            ),
            result,
        )
        assertNull(profiles.get("builtin_steady")!!.own)
    }

    @Test
    fun `a profile that is not there cannot be saved to or reset`() = run {
        assertEquals(SaveChainResult.NotFound, profiles.saveChain("no-such-profile", mine))
        assertFalse(profiles.useDefault("no-such-profile"))
        assertNull(profiles.get("no-such-profile"))
    }

    @Test
    fun `saving a chain, or going back to the standard, changes nothing else of the profile`() = run {
        val before = f.db.profiles().get("builtin_steady")!!

        profiles.saveChain("builtin_steady", mine)
        assertEquals(before.copy(handshakeChainJson = ChainJson.encode(mine)), f.db.profiles().get("builtin_steady"))

        profiles.useDefault("builtin_steady")
        assertEquals(before, f.db.profiles().get("builtin_steady"))
    }

    @Test
    fun `going back to the standard clears the chain of its own`() = run {
        profiles.saveChain("builtin_steady", mine)

        assertTrue(profiles.useDefault("builtin_steady"))

        val p = profiles.get("builtin_steady")!!
        assertNull(p.own)
        assertEquals(DialEffects.defaultChain(p.difficulty), p.chain)
    }

    @Test
    fun `something stored that cannot be used is said to be unusable, and the standard chain is in force`() = run {
        val row = f.db.profiles().get("builtin_command")!!
        f.db.profiles().upsert(row.copy(handshakeChainJson = "this is not a chain"))

        val p = profiles.get("builtin_command")!!

        assertNull(p.own)
        assertTrue(p.storedUnusable)
        assertEquals(DialEffects.defaultChain(p.difficulty), p.chain)
        val occurrence = f.repo.createOccurrence(f.mission(profileId = "builtin_command").id, f.t0 + 60_000, "UTC").occurrence
        assertEquals("and the ring asks for the standard one", DialEffects.defaultChain(p.difficulty), f.repo.handshakeOf(occurrence).chain)
    }

    @Test
    fun `the list follows the database, so a saved chain shows at once`() = run {
        val before = profiles.observe().first().single { it.id == "builtin_nudge" }
        assertNull(before.own)

        profiles.saveChain("builtin_nudge", mine)

        assertEquals(mine, profiles.observe().first().single { it.id == "builtin_nudge" }.own)
    }

    @Test
    fun `the list is a flow of the database, so a chain saved while it is collected arrives in that same collection`() = run {
        coroutineScope {
            val seen = Channel<List<ProfileHandshake>>(Channel.UNLIMITED)
            val collecting = launch { profiles.observe().collect { seen.send(it) } }
            val first = withTimeout(10_000) { seen.receive() }
            assertNull(first.single { it.id == "builtin_nudge" }.own)

            profiles.saveChain("builtin_nudge", mine)

            val second = withTimeout(10_000) { seen.receive() }
            assertEquals(mine, second.single { it.id == "builtin_nudge" }.own)
            collecting.cancel()
        }
    }

    @Test
    fun `the list is in the order a person thinks of the profiles, the built-in ones by difficulty, then the others, each by difficulty and name`() = run {
        val base = f.db.profiles().get("builtin_steady")!!
        fun other(id: String, name: String, difficulty: Int) =
            base.copy(id = id, name = name, origin = PresetOrigin.CUSTOM, handshakeDifficulty = difficulty, handshakeChainJson = null, isBuiltin = false)
        f.db.profiles().upsert(other("z1", "Zed", 1))
        f.db.profiles().upsert(other("a5", "Alpha", 5))
        f.db.profiles().upsert(other("b5", "Bravo", 5))
        f.db.profiles().upsert(other("m3", "Mid", 3))

        val order = profiles.observe().first().map { it.id }

        assertEquals(listOf("builtin_nudge", "builtin_steady", "builtin_command", "builtin_reveille_max", "z1", "m3", "a5", "b5"), order)
    }

    @Test
    fun `alarms are fired with the active profile, which is the one that is active when nothing else is said`() = run {
        assertEquals(setOf("builtin_steady"), profiles.observeInUse().first())
        assertTrue(profiles.observeInUse("builtin_steady").first())
        assertFalse(profiles.observeInUse("builtin_command").first())
    }

    @Test
    fun `and with any profile a mission names, and not with one that only a mission that is gone named`() = run {
        f.mission() // names none: it gets the active one
        assertEquals(setOf("builtin_steady"), profiles.observeInUse().first())

        val named = f.mission(profileId = "builtin_command")
        assertEquals(setOf("builtin_steady", "builtin_command"), profiles.observeInUse().first())
        assertTrue(profiles.observeInUse("builtin_command").first())

        f.db.missions().softDelete(named.id, f.t0)
        assertEquals("a mission that is gone uses nothing", setOf("builtin_steady"), profiles.observeInUse().first())
        assertFalse(profiles.observeInUse("builtin_command").first())
    }

    @Test
    fun `the active profile is the one the setting names, and it moves with the setting`() = run {
        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"builtin_reveille_max\""))

        assertEquals(setOf("builtin_reveille_max"), profiles.observeInUse().first())
        assertFalse("no longer the one that was active", profiles.observeInUse("builtin_steady").first())
        assertTrue(profiles.observeInUse("builtin_reveille_max").first())
    }

    @Test
    fun `when the active-profile setting is blank no profile is active, and only the ones that missions name are in use`() = run {
        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"\""))
        assertEquals(emptySet<String>(), profiles.observeInUse().first())

        f.mission() // names none, so it gets the active one, and there is none
        assertEquals(emptySet<String>(), profiles.observeInUse().first())

        f.mission(profileId = "builtin_command")
        assertEquals(setOf("builtin_command"), profiles.observeInUse().first())

        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, ""))
        assertEquals("a setting with nothing in it names nothing either", setOf("builtin_command"), profiles.observeInUse().first())
    }

    @Test
    fun `in use is a flow of the database, so a mission added or removed and another profile made active arrive in that same collection`() = run {
        coroutineScope {
            val seen = Channel<Set<String>>(Channel.UNLIMITED)
            val collecting = launch { profiles.observeInUse().collect { seen.send(it) } }
            // A change can be announced more than once, so each step waits for the set it expects.
            suspend fun arrives(expected: Set<String>) {
                withTimeout(10_000) { while (seen.receive() != expected) Unit }
            }
            arrives(setOf("builtin_steady"))

            val m = f.mission(profileId = "builtin_command")
            arrives(setOf("builtin_steady", "builtin_command"))

            f.db.missions().softDelete(m.id, f.t0)
            arrives(setOf("builtin_steady"))

            f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"builtin_reveille_max\""))
            arrives(setOf("builtin_reveille_max"))
            collecting.cancel()
        }
    }

    @Test
    fun `saving a chain, or going back to the standard, is one UPDATE of that column, so no row is written as a whole and no other column is touched`() = run {
        val sql = f.db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER no_row_written BEFORE INSERT ON execution_profiles BEGIN SELECT RAISE(ABORT, 'a profile was written as a whole'); END")
        val others = listOf(
            "id", "name", "origin", "personality", "voice_pack_id", "firmness", "obnoxiousness", "alert_intensity", "follow_up_persistence",
            "handshake_difficulty", "schedule_rigidity", "recovery_structure", "is_builtin",
        )
        sql.execSQL("CREATE TRIGGER no_other_column BEFORE UPDATE OF ${others.joinToString()} ON execution_profiles BEGIN SELECT RAISE(ABORT, 'another column was written'); END")
        val before = f.db.profiles().get("builtin_steady")!!

        assertEquals(SaveChainResult.Saved, profiles.saveChain("builtin_steady", mine))
        assertEquals(mine, profiles.get("builtin_steady")!!.own)
        assertTrue(profiles.useDefault("builtin_steady"))
        assertEquals(before, f.db.profiles().get("builtin_steady"))

        // The triggers are live, or this proves nothing: a write of the whole row, or of another column, is refused.
        assertTrue("a row written as a whole is refused", runCatching { f.db.profiles().upsert(before.copy(handshakeChainJson = "x")) }.isFailure)
        assertTrue("another column is refused", runCatching { sql.execSQL("UPDATE execution_profiles SET name = 'x' WHERE id = 'builtin_steady'") }.isFailure)
    }

    @Test
    fun `a profile with a personality of its own keeps the words of that personality for the ring`() = run {
        val row: ProfileRow = f.db.profiles().get("builtin_reveille_max")!!
        val occurrence = f.repo.createOccurrence(f.mission(profileId = row.id).id, f.t0 + 60_000, "UTC").occurrence
        assertEquals(row.personality, f.repo.handshakeOf(occurrence).personality)
    }
}
