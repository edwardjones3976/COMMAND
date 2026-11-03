package com.example.service

import android.content.Context
import com.example.data.MissionEntity

/**
 * CalendarSyncManager coordinates both immediate in-process and background service
 * synchronizations for Google Calendar events and execution scheduling.
 */
class CalendarSyncManager(private val context: Context) {

    private val service = GoogleCalendarSyncService(context)

    fun hasCalendarPermission(): Boolean = service.hasCalendarPermission()

    fun syncGoogleCalendar(
        existingMissions: List<MissionEntity>,
        defaultPersonalityId: String,
        defaultVoicePackId: String,
        windowHours: Int = 72
    ): CalendarSyncResult {
        return service.fetchGoogleCalendarMissions(
            windowHours = windowHours,
            existingMissions = existingMissions,
            defaultPersonalityId = defaultPersonalityId,
            defaultVoicePackId = defaultVoicePackId
        )
    }

    suspend fun performFullSyncAndSchedule(
        windowHours: Int = 72,
        personalityId: String? = null,
        voicePackId: String? = null
    ): CalendarSyncResult {
        return CalendarBackgroundSyncService.performCalendarSyncAndScheduling(
            context = context,
            windowHours = windowHours,
            targetPersonalityId = personalityId,
            targetVoicePackId = voicePackId
        )
    }

    fun triggerBackgroundService() {
        CalendarSyncScheduler.triggerImmediateSync(context)
    }

    fun schedulePeriodicBackgroundSync(intervalMinutes: Int = 120) {
        CalendarSyncScheduler.schedulePeriodicSync(context, intervalMinutes)
    }

    fun cancelPeriodicBackgroundSync() {
        CalendarSyncScheduler.cancelPeriodicSync(context)
    }

    fun isPeriodicSyncScheduled(): Boolean = CalendarSyncScheduler.isPeriodicSyncScheduled(context)

    fun getPeriodicIntervalMinutes(): Int = CalendarSyncScheduler.getPeriodicIntervalMinutes(context)

    fun getLastSyncTimestamp(): Long = CalendarSyncScheduler.getLastSyncTimestamp(context)
}
