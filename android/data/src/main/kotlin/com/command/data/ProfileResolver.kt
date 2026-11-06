package com.command.data

import com.command.data.db.ActivityProfileRow
import com.command.data.db.CommandDb
import com.command.data.db.MissionRow
import com.command.data.db.ProfileRow
import com.command.domain.model.MissionSource
import com.command.domain.model.SettingKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Which profile a mission rings with, the one answer that the ring ([OccurrenceRepository]) and the screens ([ProfileRepository.observeInUse])
 * both read. A mission the person made: the profile it names, then a rule of `activity_profiles`, then the active profile. A mission copied from
 * a calendar: a rule first, because the profile it names is not the person's choice for it but the calendar's default, copied when it was
 * imported (and an imported mission cannot be edited, so nothing else can have put one there), then that default, then the active profile.
 * A name that no profile has counts as no name, at every step.
 */
class ProfileResolver(private val db: CommandDb) {

    /** The profile in force for [mission]. With no mission, or nothing that fits and no usable active profile, it can be nothing: the caller has its own default. */
    suspend fun resolve(mission: MissionRow?): ProfileRow? {
        // The rules were applied to what was typed when a test alarm was started (resolveTyped): the hidden mission's own title and category say nothing about it.
        val rules = if (mission?.id == MissionRepository.TEST_MISSION_ID) emptyList() else db.activityProfiles().all()
        val id = pickProfileId(mission?.let(::subjectOf), rules, db.profiles().ids().toSet(), activeProfileIdOf(db.settings().get(SettingKeys.ACTIVE_PROFILE_ID)))
        return id?.let { db.profiles().get(it) }
    }

    /**
     * The profile a mission the person is still typing would name or get from a rule, for a test alarm: its hidden mission has a category of its
     * own, so the rule has to be applied here. Null leaves it to the active profile, when the alarm rings.
     */
    suspend fun resolveTyped(title: String, category: String, profileId: String?): String? =
        pickProfileId(ProfileSubject(title, category, manual = true, named = profileId), db.activityProfiles().all(), db.profiles().ids().toSet(), active = null)

    /**
     * The ids of the profiles that alarms are fired with: the active profile, if it exists (every mission with nothing else to go by, and so
     * every new one, has it), and the one each mission that is not gone resolves to. It is read again whenever the setting, a mission, a rule
     * or a profile changes. The hidden mission behind a test alarm counts as gone: it carries the profile of what the test was started from.
     */
    fun observeInUse(): Flow<Set<String>> = combine(
        db.settings().observe(SettingKeys.ACTIVE_PROFILE_ID),
        db.missions().observeActive(),
        db.activityProfiles().observeAll(),
        db.profiles().observeIds(),
    ) { setting, missions, rules, ids ->
        val known = ids.toSet()
        val active = activeProfileIdOf(setting)
        buildSet {
            pickProfileId(null, rules, known, active)?.let { add(it) }
            missions.mapNotNullTo(this) { pickProfileId(subjectOf(it), rules, known, active) }
        }
    }

    private fun subjectOf(mission: MissionRow) =
        ProfileSubject(mission.title, mission.category, manual = mission.source == MissionSource.MANUAL, named = mission.profileId)
}

/** What choosing a profile needs to know about a mission. [named] is its `profile_id`: the person's choice on a [manual] mission, the calendar's default on any other. */
internal class ProfileSubject(val title: String, val category: String, val manual: Boolean, val named: String?)

/** The order itself (see [ProfileResolver]); [known] is every id a profile has. */
internal fun pickProfileId(subject: ProfileSubject?, rules: List<ActivityProfileRow>, known: Set<String>, active: String?): String? {
    val fallback = active?.takeIf { it in known }
    if (subject == null) return fallback
    val named = subject.named?.takeIf { it in known }
    val rule = rules.asSequence()
        .filter { it.profileId in known && it.matches(subject) }
        .minWithOrNull(compareByDescending<ActivityProfileRow> { it.weight }.thenBy { it.id })
        ?.profileId
    return if (subject.manual) named ?: rule ?: fallback else rule ?: named ?: fallback
}

private val ActivityProfileRow.category get() = matchCategory.trim()
private val ActivityProfileRow.words get() = matchTitleContains?.trim().orEmpty()

/** A rule asks for a category, words in the title, or both, whatever their case. One that asks for neither matches nothing, rather than every mission there is. */
private fun ActivityProfileRow.matches(subject: ProfileSubject): Boolean {
    if (category.isEmpty() && words.isEmpty()) return false
    return (category.isEmpty() || subject.category.trim().equals(category, ignoreCase = true)) &&
        (words.isEmpty() || subject.title.contains(words, ignoreCase = true))
}

/** The rule that says most about the mission wins: words and a category, then words, then a category. Equal ones go by id, so the answer does not depend on the order of the rows. */
private val ActivityProfileRow.weight get() = (if (words.isEmpty()) 0 else 2) + (if (category.isEmpty()) 0 else 1)
