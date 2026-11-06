package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.domain.dials.Dial
import com.command.domain.dials.Dials
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the rules screen and the dials screen write: that the checks hold, and that what is written is what the ring reads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActivityRuleRepositoryTest {

    private lateinit var f: Fixture
    private lateinit var rules: ActivityRuleRepository
    private lateinit var profiles: ProfileRepository

    @Before
    fun setUp() {
        f = Fixture(ApplicationProvider.getApplicationContext<Context>())
        rules = ActivityRuleRepository(f.db)
        profiles = ProfileRepository(f.db)
    }

    @After fun tearDown() { f.close() }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private suspend fun saved(category: String, words: String, profile: String = "builtin_command", id: String? = null): String =
        (rules.save(id, category, words, profile) as SaveRuleResult.Saved).id

    private fun rejected(vararg problems: RuleProblem) = SaveRuleResult.Rejected(problems.toSet())

    @Test
    fun `a saved rule is what the ring reads for a mission it fits, and deleting it takes that away`() = run {
        val id = saved("  work ", "") // the fixture's missions are in the category "Work"
        val resolver = ProfileResolver(f.db)
        val mission = f.mission()
        assertEquals("builtin_command", resolver.resolve(mission)?.id)

        rules.delete(id)

        assertEquals("builtin_steady", resolver.resolve(mission)?.id)
        assertTrue(rules.observe().first().isEmpty())
    }

    @Test
    fun `what is typed is stored trimmed, and empty words are no words`() = run {
        val id = saved("  Exercise ", "   ")
        val row = f.db.activityProfiles().all().single { it.id == id }
        assertEquals("Exercise", row.matchCategory)
        assertNull(row.matchTitleContains)

        val other = saved("", " run ")
        assertEquals("run", f.db.activityProfiles().all().single { it.id == other }.matchTitleContains)
    }

    @Test
    fun `a rule that asks for nothing is refused, and nothing is written`() = run {
        assertEquals(rejected(RuleProblem.NOTHING_ASKED), rules.save(null, "  ", " ", "builtin_command"))
        assertTrue(f.db.activityProfiles().all().isEmpty())
    }

    @Test
    fun `a rule with no profile, or a profile that does not exist, is refused`() = run {
        assertEquals(rejected(RuleProblem.NO_PROFILE), rules.save(null, "Work", "", null))
        assertEquals(rejected(RuleProblem.NO_PROFILE), rules.save(null, "Work", "", "no_such_profile"))
        assertTrue(f.db.activityProfiles().all().isEmpty())
    }

    @Test
    fun `a rule that asks for the same as another, whatever the case, is refused, because the resolver would never use it`() = run {
        saved("Work", "report")
        assertEquals(rejected(RuleProblem.SAME_AS_ANOTHER), rules.save(null, " WORK ", "REPORT", "builtin_nudge"))
        assertEquals("a rule asking for less is another rule", true, rules.save(null, "Work", "", "builtin_nudge") is SaveRuleResult.Saved)
    }

    @Test
    fun `changing a rule can keep what it asks for, which is not the same as another rule`() = run {
        val id = saved("Work", "report")
        assertNotNull(saved("Work", "report", profile = "builtin_nudge", id = id))
        assertEquals("builtin_nudge", f.db.activityProfiles().all().single().profileId)
    }

    @Test
    fun `a rule deleted while it was being edited is not brought back by saving it`() = run {
        val id = saved("Work", "")
        rules.delete(id)

        assertEquals(SaveRuleResult.Gone, rules.save(id, "Work", "", "builtin_nudge"))
        assertTrue(f.db.activityProfiles().all().isEmpty())
    }

    @Test
    fun `setting a dial writes that dial and nothing else of the profile, for every dial`() = run {
        for (dial in Dial.values()) {
            val before = profiles.observeDials("builtin_steady").first()!!
            val rowBefore = f.db.profiles().get("builtin_steady")!!
            val value = if (before.dials[dial] == 5) 4 else 5

            assertTrue(dial.name, profiles.setDial("builtin_steady", dial, value))

            assertEquals(dial.name, before.dials.with(dial, value), profiles.observeDials("builtin_steady").first()!!.dials)
            val rowAfter = f.db.profiles().get("builtin_steady")!!
            assertEquals(dial.name, rowBefore.name, rowAfter.name)
            assertEquals(dial.name, rowBefore.voicePackId, rowAfter.voicePackId)
            assertEquals(dial.name, rowBefore.personality, rowAfter.personality)
        }
        assertEquals("no other profile was touched", com.command.domain.dials.Presets.dialsFor(com.command.domain.dials.PresetOrigin.NUDGE), profiles.observeDials("builtin_nudge").first()!!.dials)
    }

    @Test
    fun `setting a dial cannot undo what another writer wrote to the row, as it writes no other column`() = run {
        profiles.saveChain("builtin_steady", com.command.domain.dials.DialEffects.defaultChain(4))
        profiles.setDial("builtin_steady", Dial.FIRMNESS, 5)
        assertNotNull("the chain stays", profiles.get("builtin_steady")!!.own)
    }

    @Test
    fun `a value no dial can have is refused and not written, because the ring cannot read one`() = run {
        val before = profiles.observeDials("builtin_steady").first()!!.dials
        assertFalse(profiles.setDial("builtin_steady", Dial.FIRMNESS, Dials.RANGE.last + 1))
        assertFalse(profiles.setDial("builtin_steady", Dial.FIRMNESS, Dials.RANGE.first - 1))
        assertEquals(before, profiles.observeDials("builtin_steady").first()!!.dials)
    }

    @Test
    fun `setting a dial of a profile that is not there says so`() = run {
        assertFalse(profiles.setDial("no_such_profile", Dial.FIRMNESS, 3))
    }

    @Test
    fun `a profile that is not there is followed as nothing, and the active id is what the setting names`() = run {
        assertNull(profiles.observeHandshake("no_such_profile").first())
        assertNull(profiles.observeDials("no_such_profile").first())
        assertEquals("builtin_steady", profiles.observeActiveId().first())

        profiles.activate("builtin_nudge")

        assertEquals("builtin_nudge", profiles.observeActiveId().first())
    }
}
