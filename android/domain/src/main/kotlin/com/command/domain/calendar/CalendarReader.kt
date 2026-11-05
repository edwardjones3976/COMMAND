package com.command.domain.calendar

/** A calendar on the phone, as the phone names it. */
data class DeviceCalendar(val id: Long, val account: String, val displayName: String)

/**
 * One instance of an event, already expanded by the calendar provider. [beginMs] and [endMs] are UTC milliseconds, so a change of
 * the clocks needs no handling here. There is no description: it is never read for import.
 */
data class CalendarInstance(
    val calendarId: Long,
    val eventId: Long,
    val beginMs: Long,
    val endMs: Long,
    val title: String,
    val allDay: Boolean = false,
    val eventTz: String? = null,
    /** The series this instance is an exception of, and the time it had in the series. */
    val originalId: Long? = null,
    val originalInstanceMs: Long? = null,
    val cancelled: Boolean = false,
    val deleted: Boolean = false,
)

enum class ReadFailure { NO_CURSOR, REFUSED, BAD_QUERY, NO_CALENDARS }

sealed interface CalendarRead {
    /** [goneEventIds] are the events asked about that the provider no longer has, or holds only as deleted. */
    data class Ok(
        val calendars: List<DeviceCalendar>,
        val instances: List<CalendarInstance>,
        val goneEventIds: Set<Long>,
    ) : CalendarRead

    /** The permission is not held. Nothing can be said about any event, so nothing may change because of this. */
    data object PermissionLost : CalendarRead

    /** The read did not work (no cursor, or the provider refused). As with a lost permission, it says nothing about any event. */
    data class Failed(val why: ReadFailure) : CalendarRead
}

/** Read-only access to the phone's calendars. Nothing is ever written to a calendar. */
interface CalendarReader {
    fun hasPermission(): Boolean

    /** The phone's calendars, or null when they cannot be read. */
    suspend fun calendars(): List<DeviceCalendar>?

    /** Instances beginning in [[fromMs], [toMs]) in the [calendarIds], and which of [askAbout] are gone. */
    suspend fun read(fromMs: Long, toMs: Long, calendarIds: Set<Long>, askAbout: Set<Long>): CalendarRead
}
