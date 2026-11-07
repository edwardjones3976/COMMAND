package com.command.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.command.data.DraftProblem
import com.command.data.MissionDraft
import com.command.data.MissionRepository
import com.command.data.ProfileRepository
import com.command.data.RestWindowRepository
import com.command.data.SaveResult
import com.command.data.ScheduleDraft
import com.command.data.TestRingSpec
import com.command.domain.dials.Flexibility
import com.command.domain.model.MissionSource
import com.command.domain.schedule.Frequency
import com.command.domain.schedule.RRule
import com.command.domain.schedule.RestWindow
import com.command.domain.schedule.RestWindows
import com.command.domain.time.Clock
import com.command.nav.Routes
import com.command.ui.guardedScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

enum class RepeatKind { ONCE, DAILY, WEEKLY }

enum class EditorMessage { TEST_STARTED }

/** A profile the mission can name. */
data class ProfileChoice(val id: String, val name: String)

/** Everything the editor shows. Text is held as typed, so a rotation or a validation error never loses it. */
data class EditorState(
    val loading: Boolean,
    val notFound: Boolean = false,
    val editing: Boolean = false,
    val title: String = "",
    val firstAction: String = "",
    val flexibility: Flexibility = Flexibility.FLEXIBLE,
    val repeat: RepeatKind = RepeatKind.ONCE,
    val date: LocalDate,
    val time: LocalTime,
    val days: Set<DayOfWeek> = emptySet(),
    val starterMinutes: Int = 5,
    val category: String = MissionDraft.DEFAULT_CATEGORY,
    /** The profile this mission names; null leaves it to a profile rule, or the active profile. */
    val profileId: String? = null,
    val profiles: List<ProfileChoice> = emptyList(),
    val problems: Set<DraftProblem> = emptySet(),
    val busy: Boolean = false,
    val finished: Boolean = false,
    val message: EditorMessage? = null,
    /** A copy of a calendar event: only the calendar can change it, and the alarm can only be taken away. */
    val imported: Boolean = false,
    /** How many of this mission's coming alarms overlap something else. */
    val conflicts: Int = 0,
    /** The person's rest windows, which the warning below is worked out from. */
    val restWindows: List<RestWindow> = emptyList(),
    /** A calendar copy's alarms still to come. It has no rule to read, and its one start is only the first of them. */
    val comingAlarms: List<LocalDateTime> = emptyList(),
) {
    /** The days on which this alarm falls inside a rest window. It still rings on them. */
    val restWarningDays: Set<DayOfWeek>
        get() {
            if (imported) {
                return comingAlarms.filter { RestWindows.containsLocal(restWindows, it.dayOfWeek, it.toLocalTime()) }.mapTo(LinkedHashSet()) { it.dayOfWeek }
            }
            val ringing = when (repeat) {
                RepeatKind.ONCE -> setOf(date.dayOfWeek)
                RepeatKind.DAILY -> DayOfWeek.values().toSet()
                RepeatKind.WEEKLY -> days
            }
            return ringing.filterTo(LinkedHashSet()) { RestWindows.containsLocal(restWindows, it, time) }
        }
}

/** Creates a mission or edits one (the id arrives as the navigation argument). */
@HiltViewModel
class MissionEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val missions: MissionRepository,
    private val profiles: ProfileRepository,
    private val restWindows: RestWindowRepository,
    private val clock: Clock,
) : ViewModel() {

    private val missionId: String? = savedStateHandle.get<String>(Routes.ARG_ID)

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    init {
        guardedScope.launch {
            profiles.observe().collect { all -> _state.update { s -> s.copy(profiles = all.map { ProfileChoice(it.id, it.name) }) } }
        }
        guardedScope.launch {
            restWindows.observeWindows().collect { all -> _state.update { it.copy(restWindows = all) } }
        }
        if (missionId == null) {
            _state.update { it.copy(loading = false) }
        } else {
            guardedScope.launch {
                val mission = missions.get(missionId)
                val draft = mission?.let { missions.draftOf(it) }
                val conflicts = missions.conflictsOf(missionId)
                val coming = missions.comingAlarmsOf(missionId)
                _state.update {
                    if (draft == null) it.copy(loading = false, notFound = true)
                    else fill(it, draft).copy(imported = mission.source != MissionSource.MANUAL, conflicts = conflicts, comingAlarms = coming)
                }
            }
        }
    }

    private fun initial(): EditorState {
        // A new mission starts at the next full hour, on the phone's clock.
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.nowMs()), clock.zone())
        val next = now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
        return EditorState(loading = true, date = next.toLocalDate(), time = next.toLocalTime())
    }

    private fun fill(base: EditorState, draft: MissionDraft): EditorState {
        val (repeat, date, time, days) = when (val s = draft.schedule) {
            is ScheduleDraft.Once -> Quad(RepeatKind.ONCE, s.at.toLocalDate(), s.at.toLocalTime(), emptySet())
            is ScheduleDraft.Repeating -> Quad(
                if (s.rule.frequency == Frequency.DAILY) RepeatKind.DAILY else RepeatKind.WEEKLY,
                s.firstDate, s.time, s.rule.byDay,
            )
        }
        return base.copy(
            loading = false, editing = true, title = draft.title, firstAction = draft.firstAction, flexibility = draft.flexibility,
            repeat = repeat, date = date, time = time, days = days, starterMinutes = draft.starterMinutes,
            category = draft.category, profileId = draft.profileId,
        )
    }

    private data class Quad(val repeat: RepeatKind, val date: LocalDate, val time: LocalTime, val days: Set<DayOfWeek>)

    // --- edits ---------------------------------------------------------------------------------------------------

    fun onTitle(value: String) = _state.update { it.copy(title = value, problems = it.problems - DraftProblem.TITLE_EMPTY) }

    fun onFirstAction(value: String) = _state.update { it.copy(firstAction = value, problems = it.problems - DraftProblem.FIRST_ACTION_EMPTY) }

    fun onFlexibility(value: Flexibility) = _state.update { it.copy(flexibility = value) }

    fun onRepeat(value: RepeatKind) = _state.update { it.copy(repeat = value, problems = it.problems - DraftProblem.IN_THE_PAST - DraftProblem.NO_DAYS_SELECTED) }

    fun onDate(value: LocalDate) = _state.update { it.copy(date = value, problems = it.problems - DraftProblem.IN_THE_PAST) }

    fun onTime(value: LocalTime) = _state.update { it.copy(time = value, problems = it.problems - DraftProblem.IN_THE_PAST) }

    fun onDay(day: DayOfWeek) = _state.update {
        it.copy(days = if (day in it.days) it.days - day else it.days + day, problems = it.problems - DraftProblem.NO_DAYS_SELECTED)
    }

    fun onStarterMinutes(value: Int) = _state.update { it.copy(starterMinutes = value) }

    fun onCategory(value: String) = _state.update { it.copy(category = value, problems = it.problems - DraftProblem.CATEGORY_RESERVED) }

    fun onProfile(id: String?) = _state.update { it.copy(profileId = id) }

    // --- actions -------------------------------------------------------------------------------------------------

    fun save() {
        val s = _state.value
        if (s.busy || s.loading || s.imported) return
        _state.update { it.copy(busy = true) }
        guardedScope.launch {
            when (val result = missions.save(s.toDraft(missionId))) {
                is SaveResult.Saved -> _state.update { it.copy(busy = false, finished = true) }
                is SaveResult.Invalid -> _state.update { it.copy(busy = false, problems = result.problems) }
            }
        }
    }

    /** A real alarm a few seconds from now with what is typed so far, so the person can hear and see it before saving. */
    fun testRing() {
        val s = _state.value
        if (s.busy || s.loading) return
        guardedScope.launch {
            missions.testRing(TestRingSpec(s.title, s.firstAction, s.flexibility, s.starterMinutes, s.profileId, s.category))
            _state.update { it.copy(message = EditorMessage.TEST_STARTED) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun delete() {
        val id = missionId ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        guardedScope.launch {
            missions.delete(id)
            _state.update { it.copy(busy = false, finished = true) }
        }
    }

    private fun EditorState.toDraft(id: String?): MissionDraft = MissionDraft(
        id = id,
        title = title,
        firstAction = firstAction,
        flexibility = flexibility,
        schedule = when (repeat) {
            RepeatKind.ONCE -> ScheduleDraft.Once(LocalDateTime.of(date, time))
            RepeatKind.DAILY -> ScheduleDraft.Repeating(date, time, RRule(Frequency.DAILY))
            RepeatKind.WEEKLY -> ScheduleDraft.Repeating(date, time, RRule(Frequency.WEEKLY, byDay = days))
        },
        category = category,
        starterMinutes = starterMinutes,
        profileId = profileId,
    )
}
