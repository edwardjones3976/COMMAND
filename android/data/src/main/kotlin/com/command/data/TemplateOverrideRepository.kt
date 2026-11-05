package com.command.data

import com.command.data.db.CommandDb
import com.command.data.db.TemplateOverrideRow
import com.command.domain.coaching.CoachingPhase
import com.command.domain.coaching.DirectiveLint
import com.command.domain.coaching.LintViolation
import com.command.domain.coaching.TemplateBank
import com.command.domain.coaching.TemplateOverrides
import com.command.domain.coaching.TemplateProblem
import com.command.domain.dials.Personality
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SaveTemplateResult {
    data object Saved : SaveTemplateResult

    /** The form is wrong, or the words are ones the app may not say, or the variant is not one of the bank's. Nothing was saved. */
    data class Rejected(
        val problems: Set<TemplateProblem> = emptySet(),
        val violations: List<LintViolation> = emptyList(),
        val noSuchVariant: Boolean = false,
    ) : SaveTemplateResult
}

/** The person's own wording for one variant of the bank. Every save goes through the lint. */
@Singleton
class TemplateOverrideRepository @Inject constructor(private val db: CommandDb) {

    suspend fun save(personality: Personality, phase: CoachingPhase, variant: Int, text: String): SaveTemplateResult {
        val problems = TemplateBank.problems(text)
        val violations = DirectiveLint.check(text)
        val noSuchVariant = variant !in TemplateBank.variants(personality, phase).indices
        if (problems.isNotEmpty() || violations.isNotEmpty() || noSuchVariant) return SaveTemplateResult.Rejected(problems, violations, noSuchVariant)
        db.templateOverrides().upsert(TemplateOverrideRow(personality, phase.name, variant, text.trim()))
        return SaveTemplateResult.Saved
    }

    /** Back to the bank's words for this variant. */
    suspend fun reset(personality: Personality, phase: CoachingPhase, variant: Int) = db.templateOverrides().delete(personality, phase.name, variant)

    suspend fun all(): TemplateOverrides = db.templateOverrides().all().toOverrides()
}

/** A row whose phase is not one this version knows (a newer backup, say) is left out. */
internal fun List<TemplateOverrideRow>.toOverrides(): TemplateOverrides = TemplateOverrides(
    mapNotNull { row ->
        CoachingPhase.values().firstOrNull { it.name == row.phase }?.let { TemplateOverrides.Key(row.personality, it, row.variant) to row.text }
    }.toMap(),
)
