package com.command.domain.occurrence

/** The closed vocabulary of audit events. Stored as TEXT, so names must never be repurposed. */
enum class EventType {
    STATE_CHANGED, REJECTED_TRANSITION, OCCURRENCE_MOVED, OCCURRENCE_DISCARDED,
    ALARM_ARMED, ALARM_FIRED, ALARM_FIRED_STALE, ALARM_LOST, ALARM_CAP,
    RING_DEGRADED, TTS_UNAVAILABLE, FSI_UNAVAILABLE, DND_MAY_SUPPRESS,
    PRECONDITION, PERMISSION_CHANGED, NOTIFICATION_DISMISSED,
    MISSED_WHILE_OFF, BOOT_RECONCILE, PAUSED, UNPAUSED, TEST_RING,
    VOLUME_SET_REFUSED, VOLUME_RESTORE_SKIPPED, AUDIO_ROUTE,
    CHECK_IN_ASKED, CHECK_IN_ANSWERED, CHECKIN_UNANSWERED, CHECKIN_LATE, FOLLOWUP_SUPPRESSED,
    RECOVERY_OFFERED, RECOVERY_ACCEPTED, FIRST_ACTION_INDICATED,
    LEGACY_IMPORTED, CALENDAR_READ_FAILED, CALENDAR_PERMISSION_LOST,
    SYNC_CONFLICT_LOST, APPCHECK_TOKEN, STARTER_BEGUN,
}

/**
 * Event details: ids, enums, counts and timestamps only. Values are limited to a short token alphabet, so a
 * mission title, a note, or any other free text cannot be written into the history by accident.
 */
class EventPayload private constructor(val values: Map<String, String>) {
    override fun equals(other: Any?): Boolean = other is EventPayload && other.values == values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "EventPayload($values)"

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9_.:+-]{0,64}")
        val EMPTY = EventPayload(emptyMap())

        fun of(vararg pairs: Pair<String, Any?>): EventPayload {
            if (pairs.isEmpty()) return EMPTY
            val map = LinkedHashMap<String, String>()
            for ((key, value) in pairs) {
                require(TOKEN.matches(key) && key.isNotEmpty()) { "bad payload key: $key" }
                val text = value?.toString() ?: continue
                require(TOKEN.matches(text)) { "payload values must be ids, enums, counts or timestamps, not free text: key=$key" }
                map[key] = text
            }
            return EventPayload(map)
        }
    }
}

data class DomainEvent(val type: EventType, val payload: EventPayload = EventPayload.EMPTY)
