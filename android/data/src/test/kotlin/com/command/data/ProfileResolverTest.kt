package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.ActivityProfileRow
import com.command.data.db.MissionRow
import com.command.data.db.SettingRow
import com.command.domain.dials.Flexibility
import com.command.domain.dials.Personality
import com.command.domain.model.MissionSource
import com.command.domain.model.SettingKeys
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.model.EventSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileResolverTest {

    private lateinit var f: Fixture
    private lateinit var resolver: ProfileResolver

    private val active = "builtin_steady"

    @Before
    fun setUp() {
        f = Fixture(ApplicationProvider.getApplicationContext<Context>())
        resolver = ProfileResolver(f.db)
    }

    @After fun tearDown() { f.close() }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private suspend fun rule(id: String, category: String, title: String?, profile: String) =
        f.db.activityProfiles().upsert(ActivityProfileRow(id, category, title, profile))

    /** The fixture's missions are in the category "Work". */
    private suspend fun resolved(m: MissionRow?) = resolver.resolve(m)?.id

    @Test
    fun `a mission the person made rings with the profile it names, ahead of a rule and of the active one`() = run {
        rule("r", "Work", null, "builtin_command")
        assertEquals("builtin_nudge", resolved(f.mission(profileId = "builtin_nudge")))
    }

    @Test
    fun `a mission the person made that names none takes the rule that matches, and with none the active profile`() = run {
        val m = f.mission()
        assertEquals(active, resolved(m))
        rule("r", "Work", null, "builtin_command")
        assertEquals("builtin_command", resolved(m))
    }

    @Test
    fun `a mission the person made that names a profile that is gone falls to the rule, and then to the active one`() = run {
        val m = f.mission(profileId = "builtin_ghost")
        assertEquals(active, resolved(m))
        rule("r", "Work", null, "builtin_command")
        assertEquals("builtin_command", resolved(m))
    }

    @Test
    fun `an imported mission that matches a rule takes the rule, because its own profile is only the calendar's default`() = run {
        val imported = f.mission(profileId = "builtin_nudge", source = MissionSource.DEVICE_CALENDAR)
        rule("r", "Work", null, "builtin_command")
        assertEquals("builtin_command", resolved(imported))
    }

    @Test
    fun `an imported mission that matches no rule takes the calendar's default, then the active profile`() = run {
        rule("r", "Sport", null, "builtin_command")
        assertEquals("builtin_nudge", resolved(f.mission(profileId = "builtin_nudge", source = MissionSource.DEVICE_CALENDAR)))
        assertEquals(active, resolved(f.mission(source = MissionSource.DEVICE_CALENDAR)))
        assertEquals(active, resolved(f.mission(profileId = "builtin_ghost", source = MissionSource.DEVICE_CALENDAR)))
    }

    @Test
    fun `a rule asks for a category and, when it names words, for those words in the title, whatever their case`() = run {
        val m = f.mission(title = "Write the REPORT")
        rule("wrong words", "Work", "invoice", "builtin_command")
        rule("wrong category", "Sport", "report", "builtin_command")
        assertEquals(active, resolved(m))
        rule("right", " work ", " report ", "builtin_nudge")
        assertEquals("builtin_nudge", resolved(m))
    }

    @Test
    fun `a rule with no category goes by the title alone, and a rule that asks for nothing matches nothing`() = run {
        val m = f.mission(title = "Write the report")
        rule("empty", " ", null, "builtin_command")
        rule("blank words", "", "  ", "builtin_command")
        assertEquals(active, resolved(m))
        rule("title", "", "report", "builtin_nudge")
        assertEquals("builtin_nudge", resolved(m))
    }

    @Test
    fun `of several rules the one that says most wins, words over a category, both over either, and then the first by id`() = run {
        val m = f.mission(title = "Write the report")
        rule("a category", "Work", null, "builtin_steady")
        assertEquals("builtin_steady", resolved(m))
        rule("b words", "", "report", "builtin_nudge")
        assertEquals("builtin_nudge", resolved(m))
        rule("z both", "Work", "report", "builtin_command")
        assertEquals("builtin_command", resolved(m))
        rule("c both", "Work", "write", "builtin_reveille_max")
        assertEquals("the same weight: the first by id, not the order they were added", "builtin_reveille_max", resolved(m))
    }

    @Test
    fun `a rule that names a profile that is gone is passed over for the next one that fits`() = run {
        val m = f.mission()
        rule("a", "Work", "report", "builtin_ghost")
        assertEquals(active, resolved(m))
        rule("b", "Work", null, "builtin_command")
        assertEquals("builtin_command", resolved(m))
    }

    @Test
    fun `with no mission it is the active profile, and with no usable active profile it is nothing`() = run {
        assertEquals(active, resolved(null))
        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"builtin_ghost\""))
        assertNull(resolved(null))
        assertNull(resolved(f.mission()))
        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"\""))
        assertNull(resolved(null))
    }

    @Test
    fun `the ring takes everything from the profile the rule picks, and that is the profile said to be in use`() = run {
        val profiles = ProfileRepository(f.db)
        rule("r", "Work", null, "builtin_reveille_max")
        val maxDials = f.db.profiles().get("builtin_reveille_max")!!
        val m = f.mission()
        assertEquals(setOf(active, "builtin_reveille_max"), profiles.observeInUse().first())

        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        f.repo.apply(o.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        assertEquals("before it fires too", maxDials.alertIntensity, f.repo.speechLevelOf(f.repo.get(o.id)!!).alertIntensity)
        assertEquals(maxDials.firmness, f.repo.dialsOf(f.repo.get(o.id)!!).firmness)
        assertEquals(Personality.DRILL_SERGEANT, f.repo.handshakeOf(f.repo.get(o.id)!!).personality)
        assertEquals("the trim of that profile's pack", 4.5f, f.repo.speechLevelOf(f.repo.get(o.id)!!).trimDb, 0f)
        f.repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SYSTEM)

        val fired = f.repo.get(o.id)!!
        val plan = f.repo.handshakeOf(fired)
        assertEquals(Personality.DRILL_SERGEANT, plan.personality)
        assertEquals(maxDials.handshakeDifficulty, Codec.decodeSession(fired.sessionJson).handshakeDifficulty)
        assertEquals(maxDials.alertIntensity, f.repo.speechLevelOf(fired).alertIntensity)
    }

    @Test
    fun `in use follows the resolver, so a mission whose rule picks a profile counts it and one that names a ghost does not`() = run {
        val profiles = ProfileRepository(f.db)
        f.mission(profileId = "builtin_ghost")
        assertEquals("rings with the active one", setOf(active), profiles.observeInUse().first())

        val imported = f.mission(profileId = "builtin_nudge", source = MissionSource.DEVICE_CALENDAR)
        assertEquals(setOf(active, "builtin_nudge"), profiles.observeInUse().first())
        rule("r", "Work", null, "builtin_command")
        assertEquals("the rule takes over from the calendar's default", setOf(active, "builtin_command"), profiles.observeInUse().first())
        f.db.missions().softDelete(imported.id, f.t0)
        assertEquals(setOf(active, "builtin_command"), profiles.observeInUse().first())
    }

    @Test
    fun `in use hears about a rule and about a profile that appears, in the same collection`() = run {
        coroutineScope {
            val profiles = ProfileRepository(f.db)
            val seen = Channel<Set<String>>(Channel.UNLIMITED)
            val collecting = launch { profiles.observeInUse().collect { seen.send(it) } }
            suspend fun arrives(expected: Set<String>) {
                withTimeout(10_000) { while (seen.receive() != expected) Unit }
            }
            f.mission(profileId = "mine")
            arrives(setOf(active))
            f.db.profiles().upsert(f.db.profiles().get(active)!!.copy(id = "mine", name = "Mine", isBuiltin = false))
            arrives(setOf(active, "mine"))
            f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"builtin_ghost\""))
            arrives(setOf("mine"))
            rule("r", "Work", null, "builtin_nudge")
            arrives(setOf("mine"))
            f.db.missions().insert(Fixture.missionRow(title = "Another", startAt = f.t0))
            arrives(setOf("mine", "builtin_nudge"))
            collecting.cancel()
        }
    }

    @Test
    fun `a profile row nobody asked for that cannot be read changes nothing, for the ring or for what is in use`() = run {
        val m = f.mission(profileId = "builtin_nudge")
        f.db.openHelper.writableDatabase.execSQL("UPDATE execution_profiles SET personality = 'FROM_THE_FUTURE' WHERE id = 'builtin_command'")
        assertEquals("builtin_nudge", resolved(m))
        assertEquals(active, resolved(null))
        assertEquals(setOf(active, "builtin_nudge"), ProfileRepository(f.db).observeInUse().first())
    }

    private fun missions() = MissionRepository(f.db, f.repo, f.scheduler, f.clock, f.pauseEffects)

    @Test
    fun `a test alarm rings with the profile the mission would, by its rule or its own choice, although the hidden mission has a category of its own`() = run {
        rule("r", "Work", null, "builtin_command")
        val repo = missions()
        suspend fun ringsWith(spec: TestRingSpec): Personality {
            val id = repo.testRing(spec)
            f.repo.apply(id, OccurrenceCommand.Fire, EventSource.SYSTEM)
            val plan = f.repo.handshakeOf(f.repo.get(id)!!)
            f.repo.apply(id, OccurrenceCommand.SafetySilence, EventSource.USER)
            f.clock.advance(60_000)
            return plan.personality
        }
        assertEquals(Personality.ACCOUNTABILITY_PARTNER, ringsWith(TestRingSpec(category = "Work")))
        assertEquals(Personality.EXECUTIVE_DIRECTOR, ringsWith(TestRingSpec(category = "Sport")))
        assertEquals(Personality.SUPPORTIVE_COACH, ringsWith(TestRingSpec(category = "Work", profileId = "builtin_nudge")))
    }

    /** The personality a test alarm started from [spec] rings with. */
    private suspend fun testRingsWith(spec: TestRingSpec): Personality {
        val id = missions().testRing(spec)
        f.repo.apply(id, OccurrenceCommand.Fire, EventSource.SYSTEM)
        return f.repo.handshakeOf(f.repo.get(id)!!).personality
    }

    @Test
    fun `a test alarm with a blank category rings as the saved mission will, in the default category`() = run {
        rule("r", MissionDraft.DEFAULT_CATEGORY, null, "builtin_command")
        assertEquals(Personality.ACCOUNTABILITY_PARTNER, testRingsWith(TestRingSpec(category = "   ")))
    }

    @Test
    fun `the hidden mission's own category is not matched against the rules`() = run {
        rule("r", MissionRepository.TEST_CATEGORY, null, "builtin_command")
        assertEquals("the typed mission has no rule, so the active profile", Personality.EXECUTIVE_DIRECTOR, testRingsWith(TestRingSpec()))
    }

    @Test
    fun `nor is the title a test alarm gets when none was typed`() = run {
        rule("r", "", "test alarm", "builtin_command")
        assertEquals(Personality.EXECUTIVE_DIRECTOR, testRingsWith(TestRingSpec()))
    }

    @Test
    fun `the category is stored as typed but trimmed, an empty one is the default, and the word kept for test alarms is refused`() = run {
        val repo = missions()
        val at = LocalDateTime.ofEpochSecond(f.t0 / 1000 + 86_400, 0, ZoneOffset.UTC)
        fun draft(category: String) = MissionDraft(title = "Run", firstAction = "Shoes", flexibility = Flexibility.FLEXIBLE, schedule = ScheduleDraft.Once(at), category = category)

        val saved = repo.save(draft("  Sport ")) as SaveResult.Saved
        assertEquals("Sport", f.db.missions().get(saved.missionId)!!.category)
        val blank = repo.save(draft("   ")) as SaveResult.Saved
        assertEquals(MissionDraft.DEFAULT_CATEGORY, f.db.missions().get(blank.missionId)!!.category)
        assertEquals(SaveResult.Invalid(setOf(DraftProblem.CATEGORY_RESERVED)), repo.save(draft(" ${MissionRepository.TEST_CATEGORY} ")))
    }
}
