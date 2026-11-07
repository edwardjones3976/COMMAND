package com.command.data

import androidx.room.withTransaction
import com.command.data.db.CommandDb
import com.command.data.db.RestWindowRow
import com.command.domain.model.RestKind
import com.command.domain.schedule.RestWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** One row of the table as a window, or null if it cannot be read, which makes it no window. The one place a row is read, so that everything that obeys the windows sees the same ones. */
internal fun RestWindowRow.toWindow(): RestWindow? = runCatching {
    RestWindow(DayOfWeek.of(weekday), LocalTime.parse(startLocal), LocalTime.parse(endLocal), kind)
}.getOrNull()

/** The hours and the purpose a person thinks of as one window, on all the days they chose. The table has a row a day; [ids] are those rows. */
data class RestWindowLine(val ids: List<Long>, val days: Set<DayOfWeek>, val start: LocalTime, val end: LocalTime, val kind: RestKind)

/**
 * [NO_DAYS]: a window on no day protects nothing. [SAME_TIMES]: a window whose end is not after its start runs into the next day, so equal times would
 * mean 24 hours without anyone having asked for it.
 */
enum class RestWindowProblem { NO_DAYS, SAME_TIMES }

sealed interface SaveRestWindowResult {
    data object Saved : SaveRestWindowResult
    data class Rejected(val problems: Set<RestWindowProblem>) : SaveRestWindowResult

    /** The window being changed was deleted meanwhile, and is not brought back. */
    data object Gone : SaveRestWindowResult
}

/** Where a person writes `rest_windows`. [OccurrenceRepository] reads the rows when it decides a follow-up question, so a change counts from the next decision. */
@Singleton
class RestWindowRepository @Inject constructor(private val db: CommandDb) {

    /** In week order, then by start, so that the list does not move about as windows are added. */
    fun observe(): Flow<List<RestWindowLine>> = db.restWindows().observeAll().map { rows ->
        rows.mapNotNull { row -> row.toWindow()?.let { row.id to it } }
            .groupBy({ (_, w) -> Triple(w.start, w.end, w.kind) })
            .map { (key, members) ->
                RestWindowLine(members.map { it.first }.sorted(), members.map { it.second.weekday }.toSet(), key.first, key.second, key.third)
            }
            .sortedWith(compareBy({ it.days.min() }, { it.start }, { it.end }, { it.kind }))
    }

    /** Every window, for the mission editor's warning. */
    fun observeWindows(): Flow<List<RestWindow>> = db.restWindows().observeAll().map { rows -> rows.mapNotNull { it.toWindow() } }

    /** Writes a new window ([ids] is empty) or replaces the rows of one, in one transaction with the checks. */
    suspend fun save(ids: List<Long>, days: Set<DayOfWeek>, start: LocalTime, end: LocalTime, kind: RestKind): SaveRestWindowResult {
        val problems = buildSet {
            if (days.isEmpty()) add(RestWindowProblem.NO_DAYS)
            if (start == end) add(RestWindowProblem.SAME_TIMES)
        }
        if (problems.isNotEmpty()) return SaveRestWindowResult.Rejected(problems)
        val startLocal = STORED.format(start)
        val endLocal = STORED.format(end)
        return db.withTransaction {
            val dao = db.restWindows()
            if (ids.isNotEmpty() && dao.delete(ids) == 0) return@withTransaction SaveRestWindowResult.Gone
            val there = dao.all().filter { it.startLocal == startLocal && it.endLocal == endLocal && it.kind == kind }.map { it.weekday }
            for (day in days) {
                if (day.value !in there) dao.insert(RestWindowRow(weekday = day.value, startLocal = startLocal, endLocal = endLocal, kind = kind))
            }
            SaveRestWindowResult.Saved
        }
    }

    suspend fun delete(ids: List<Long>) {
        db.restWindows().delete(ids)
    }

    private companion object {
        val STORED: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
