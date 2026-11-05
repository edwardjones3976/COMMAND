package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.domain.coaching.CoachingPhase
import com.command.domain.coaching.TemplateProblem
import com.command.domain.dials.Personality
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every save of a person's template goes through the lint, and nothing rejected reaches the table. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemplateOverrideRepositoryTest {
    private val f = Fixture(ApplicationProvider.getApplicationContext<Context>())
    private val repo = TemplateOverrideRepository(f.db)

    @After fun close() = f.db.close()

    private fun rows() = runBlocking { f.db.templateOverrides().all() }

    @Test
    fun `a clean template is saved, and a second save of the variant replaces it`() = runBlocking {
        assertEquals(SaveTemplateResult.Saved, repo.save(Personality.CUSTOM, CoachingPhase.REVEILLE, 0, "  Up. {title}.  "))
        assertEquals("Up. {title}.", rows().single().text)
        assertEquals(SaveTemplateResult.Saved, repo.save(Personality.CUSTOM, CoachingPhase.REVEILLE, 0, "Go."))
        assertEquals("Go.", rows().single().text)
        repo.reset(Personality.CUSTOM, CoachingPhase.REVEILLE, 0)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `words the app may not say are refused and not stored`() = runBlocking {
        val result = repo.save(Personality.CUSTOM, CoachingPhase.REVEILLE, 0, "Up, or this alarm never stops.")
        assertTrue((result as SaveTemplateResult.Rejected).violations.isNotEmpty())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a bad form and a variant the bank does not have are refused and not stored`() = runBlocking {
        assertEquals(setOf(TemplateProblem.UNKNOWN_PLACEHOLDER), (repo.save(Personality.CUSTOM, CoachingPhase.REVEILLE, 0, "Go. {who}.") as SaveTemplateResult.Rejected).problems)
        assertTrue((repo.save(Personality.CUSTOM, CoachingPhase.REVEILLE, 99, "Go.") as SaveTemplateResult.Rejected).noSuchVariant)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a row of a phase this version does not know is left out`() = runBlocking {
        f.db.openHelper.writableDatabase.execSQL("INSERT INTO template_overrides (personality, phase, variant, text) VALUES ('CUSTOM', 'FUTURE', 0, 'Go.')")
        assertEquals(1, rows().size)
        val empty = com.command.domain.coaching.CoachingEngine.compose(Personality.CUSTOM, CoachingPhase.CHECK_IN, com.command.domain.dials.Dials(3, 1, 3, 3, 3, 3, 3), com.command.domain.coaching.CoachingContext(), 0, "s")
        assertEquals(empty, com.command.domain.coaching.CoachingEngine.compose(Personality.CUSTOM, CoachingPhase.CHECK_IN, com.command.domain.dials.Dials(3, 1, 3, 3, 3, 3, 3), com.command.domain.coaching.CoachingContext(), 0, "s", repo.all()))
    }
}
