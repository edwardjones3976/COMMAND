package com.command.data

import androidx.room.withTransaction
import com.command.data.db.ActivityProfileRow
import com.command.data.db.CommandDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A rule of `activity_profiles`: missions in [category], or with [words] in the title, or both, ring with [profileId]. Blank means it does not ask for that (see [ProfileResolver]). */
data class ActivityRule(val id: String, val category: String, val words: String, val profileId: String)

/**
 * [NOTHING_ASKED]: a rule with no category and no words matches no mission. [NO_PROFILE]: there is no such profile. [SAME_AS_ANOTHER]: another rule
 * asks for the same category and words, whatever their case; the resolver goes by id between equal rules, so one of the two would never decide anything.
 */
enum class RuleProblem { NOTHING_ASKED, NO_PROFILE, SAME_AS_ANOTHER }

sealed interface SaveRuleResult {
    data class Saved(val id: String) : SaveRuleResult
    data class Rejected(val problems: Set<RuleProblem>) : SaveRuleResult

    /** The rule being edited was deleted meanwhile, and is not brought back. */
    data object Gone : SaveRuleResult
}

/** The rules of `activity_profiles`. [ProfileResolver] reads them for the ring; this is where a person writes them. */
@Singleton
class ActivityRuleRepository @Inject constructor(private val db: CommandDb) {

    /** In an order that does not change as rules are added: by category, then by words. */
    fun observe(): Flow<List<ActivityRule>> = db.activityProfiles().observeAll().map { rows ->
        rows.map { ActivityRule(it.id, it.matchCategory.trim(), it.matchTitleContains?.trim().orEmpty(), it.profileId) }
            .sortedWith(compareBy({ it.category.lowercase() }, { it.words.lowercase() }, { it.id }))
    }

    /** Writes a new rule ([id] is null) or changes one, in one transaction with the checks. */
    suspend fun save(id: String?, category: String, words: String, profileId: String?): SaveRuleResult {
        val wantedCategory = category.trim()
        val wantedWords = words.trim()
        return db.withTransaction {
            val rules = db.activityProfiles()
            val problems = buildSet {
                if (wantedCategory.isEmpty() && wantedWords.isEmpty()) add(RuleProblem.NOTHING_ASKED)
                if (profileId == null || db.profiles().ids().none { it == profileId }) add(RuleProblem.NO_PROFILE)
                val same = rules.all().any {
                    it.id != id && it.matchCategory.trim().equals(wantedCategory, ignoreCase = true) && it.matchTitleContains?.trim().orEmpty().equals(wantedWords, ignoreCase = true)
                }
                if (same) add(RuleProblem.SAME_AS_ANOTHER)
            }
            if (problems.isNotEmpty()) return@withTransaction SaveRuleResult.Rejected(problems)
            val profile = checkNotNull(profileId)
            val storedWords = wantedWords.ifEmpty { null }
            if (id == null) {
                val newId = UUID.randomUUID().toString()
                rules.upsert(ActivityProfileRow(newId, wantedCategory, storedWords, profile))
                SaveRuleResult.Saved(newId)
            } else if (rules.update(id, wantedCategory, storedWords, profile) > 0) {
                SaveRuleResult.Saved(id)
            } else {
                SaveRuleResult.Gone
            }
        }
    }

    suspend fun delete(id: String) {
        db.activityProfiles().delete(id)
    }
}
