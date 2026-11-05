package com.command.domain.calendar

import com.command.domain.occurrence.SkipReason

/** An imported occurrence's link to its calendar instance. [unfired] is false once it has rung or ended: then it is never touched. */
data class CalendarLink(
    val occurrenceId: String,
    val calendarId: Long,
    val eventId: Long,
    val instanceBeginMs: Long,
    val originalId: Long?,
    val originalInstanceMs: Long?,
    val eventTz: String?,
    val misses: Int,
    val unfired: Boolean,
)

data class CalendarPlan(
    val creates: List<CalendarInstance> = emptyList(),
    val moves: List<Move> = emptyList(),
    val skips: List<Skip> = emptyList(),
    /** Links whose occurrence stays where it is: seen again ([seen]), or missed once more. */
    val linkUpdates: List<LinkUpdate> = emptyList(),
) {
    data class Move(val occurrenceId: String, val instance: CalendarInstance)
    data class Skip(val occurrenceId: String, val reason: SkipReason)
    data class LinkUpdate(val link: CalendarLink, val seen: Boolean)
}

/**
 * Decides what one successful read of the calendars changes. Pure: the only inputs are its arguments, and a failed read never gets here.
 *
 * An instance's identity is (calendar, [CalendarInstance.originalId] ?: event, [CalendarInstance.originalInstanceMs] ?: begin), so an
 * exception to a series is the same instance moved, never a new one. An ordinary event edited to another time keeps its event id but
 * loses that identity: instances that have lost their link and links that have lost their instance, of one event, are paired in time
 * order and moved too, because treating them as new and missing would ring both for up to two syncs. The numbers can differ when a
 * shift carries an instance over the edge of the window: what is left over is then new, or missing, as usual.
 *
 * An unfired occurrence goes only when its instance is cancelled, its event is gone or deleted, its calendar is no longer selected, or
 * two successful reads in a row did not find it. What lies outside the window cannot be found by the read, so that is not a miss.
 */
object CalendarReconciler {
    const val BEFORE_NOW_MS = 60 * 60 * 1000L
    const val AFTER_NOW_MS = 7 * 24 * 60 * 60 * 1000L
    const val MISSES_TO_REMOVE = 2

    private data class Identity(val calendarId: Long, val parent: Long, val instanceMs: Long)

    private val CalendarInstance.identity get() = Identity(calendarId, originalId ?: eventId, originalInstanceMs ?: beginMs)
    private val CalendarLink.identity get() = Identity(calendarId, originalId ?: eventId, originalInstanceMs ?: instanceBeginMs)

    fun reconcile(
        links: List<CalendarLink>,
        instances: List<CalendarInstance>,
        calendars: Set<Long>,
        now: Long,
        goneEventIds: Set<Long> = emptySet(),
    ): CalendarPlan {
        val windowStart = now - BEFORE_NOW_MS
        val windowEnd = now + AFTER_NOW_MS
        // One instance per identity, the exception first: it is what the calendar says about that instance now.
        val byIdentity = LinkedHashMap<Identity, CalendarInstance>()
        for (i in instances.filter { !it.allDay && it.calendarId in calendars }.sortedBy { it.originalId == null }) {
            byIdentity.putIfAbsent(i.identity, i)
        }

        val moves = ArrayList<CalendarPlan.Move>()
        val skips = ArrayList<CalendarPlan.Skip>()
        val updates = ArrayList<CalendarPlan.LinkUpdate>()
        val unmatched = ArrayList<CalendarLink>()

        fun move(link: CalendarLink, instance: CalendarInstance) {
            // Moved to a time already past: there is nothing left to ring for.
            if (instance.beginMs <= now) skips += CalendarPlan.Skip(link.occurrenceId, SkipReason.CALENDAR_REMOVED)
            else moves += CalendarPlan.Move(link.occurrenceId, instance)
        }

        for (link in links) {
            val instance = byIdentity.remove(link.identity)
            when {
                // What has rung or been answered is history, and its identity is spent: it is not imported a second time. What the calendar
                // itself took away has no link any more (CalendarSync drops it), so that it can come back if the calendar brings it back.
                !link.unfired -> Unit
                link.calendarId !in calendars -> skips += CalendarPlan.Skip(link.occurrenceId, SkipReason.CALENDAR_REMOVED)
                // An exception's own event can go because it was put back into its series: then the series' instance has been found.
                (link.originalId ?: link.eventId) in goneEventIds || (instance == null && link.eventId in goneEventIds) || instance?.deleted == true ->
                    skips += CalendarPlan.Skip(link.occurrenceId, SkipReason.CALENDAR_REMOVED)
                instance?.cancelled == true -> skips += CalendarPlan.Skip(link.occurrenceId, SkipReason.CALENDAR_CANCELLED)
                instance == null -> if (link.instanceBeginMs in windowStart until windowEnd) unmatched += link
                instance.beginMs != link.instanceBeginMs -> move(link, instance)
                else -> updates += CalendarPlan.LinkUpdate(link.copy(eventId = instance.eventId, originalId = instance.originalId, originalInstanceMs = instance.originalInstanceMs, eventTz = instance.eventTz, misses = 0), seen = true)
            }
        }

        // The edited ordinary events: only links and instances that are no exceptions can have lost their identity this way.
        val freeByEvent = byIdentity.values.filter { it.originalId == null && !it.cancelled && !it.deleted }.groupBy { it.calendarId to it.eventId }
        val missing = ArrayList<CalendarLink>()
        for ((key, group) in unmatched.groupBy { it.calendarId to (it.originalId ?: it.eventId) }) {
            val free = freeByEvent[key].orEmpty().sortedBy { it.beginMs }
            val sorted = group.sortedBy { it.instanceBeginMs }
            val pairable = sorted.filter { it.originalId == null }.take(free.size)
            pairable.zip(free).forEach { (link, instance) ->
                byIdentity.remove(instance.identity)
                move(link, instance)
            }
            missing += sorted - pairable.toSet()
        }
        for (link in missing) {
            val misses = link.misses + 1
            if (misses >= MISSES_TO_REMOVE) skips += CalendarPlan.Skip(link.occurrenceId, SkipReason.CALENDAR_REMOVED)
            else updates += CalendarPlan.LinkUpdate(link.copy(misses = misses), seen = false)
        }

        // A begin in the past is not a new alarm: it would only be recorded as missed.
        val creates = byIdentity.values.filter { !it.cancelled && !it.deleted && it.beginMs > now && it.beginMs < windowEnd }.sortedBy { it.beginMs }
        return CalendarPlan(creates, moves, skips, updates)
    }
}
