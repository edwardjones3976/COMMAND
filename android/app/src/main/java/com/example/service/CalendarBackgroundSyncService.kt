package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.example.data.CommandDatabase
import com.example.data.MissionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android Background Service that queries upcoming events from Google Calendar
 * (via CalendarContract.Instances), maps them into the application's task queue
 * (MissionEntity in Room), detects conflicts, and schedules execution via AlarmManager.
 */
class CalendarBackgroundSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SYNC_CALENDAR
        val windowHours = intent?.getIntExtra(EXTRA_WINDOW_HOURS, 72) ?: 72

        when (action) {
            ACTION_SYNC_CALENDAR -> {
                serviceScope.launch {
                    try {
                        val result = performCalendarSyncAndScheduling(
                            context = applicationContext,
                            windowHours = windowHours
                        )

                        // Record telemetry
                        CalendarSyncScheduler.setLastSyncResult(
                            context = applicationContext,
                            timestamp = result.syncTimestampMs,
                            syncedCount = result.syncedCount,
                            conflictsCount = result.conflictsDetected
                        )

                        // Broadcast completion so running activities/viewmodels update UI
                        val completionIntent = Intent(ACTION_CALENDAR_SYNC_COMPLETED).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_SYNCED_COUNT, result.syncedCount)
                            putExtra(EXTRA_RECURRING_COUNT, result.recurringCount)
                            putExtra(EXTRA_CONFLICTS_COUNT, result.conflictsDetected)
                            putExtra(EXTRA_SOURCE_LABEL, result.sourceLabel)
                            putExtra(EXTRA_SYNC_TIMESTAMP_MS, result.syncTimestampMs)
                        }
                        sendBroadcast(completionIntent)
                    } catch (_: Exception) {
                    } finally {
                        stopSelf(startId)
                    }
                }
            }

            ACTION_SCHEDULE_PERIODIC -> {
                val interval = intent?.getIntExtra(
                    EXTRA_INTERVAL_MINUTES,
                    CalendarSyncScheduler.getPeriodicIntervalMinutes(this)
                ) ?: CalendarSyncScheduler.getPeriodicIntervalMinutes(this)
                CalendarSyncScheduler.schedulePeriodicSync(this, interval)
                stopSelf(startId)
            }

            ACTION_CANCEL_PERIODIC -> {
                CalendarSyncScheduler.cancelPeriodicSync(this)
                stopSelf(startId)
            }

            else -> {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_SYNC_CALENDAR = "com.example.action.SYNC_GOOGLE_CALENDAR"
        const val ACTION_SCHEDULE_PERIODIC = "com.example.action.SCHEDULE_PERIODIC_SYNC"
        const val ACTION_CANCEL_PERIODIC = "com.example.action.CANCEL_PERIODIC_SYNC"
        const val ACTION_CALENDAR_SYNC_COMPLETED = "com.example.action.CALENDAR_SYNC_COMPLETED"

        const val EXTRA_WINDOW_HOURS = "extra_window_hours"
        const val EXTRA_INTERVAL_MINUTES = "extra_interval_minutes"
        const val EXTRA_SYNCED_COUNT = "extra_synced_count"
        const val EXTRA_RECURRING_COUNT = "extra_recurring_count"
        const val EXTRA_CONFLICTS_COUNT = "extra_conflicts_count"
        const val EXTRA_SOURCE_LABEL = "extra_source_label"
        const val EXTRA_SYNC_TIMESTAMP_MS = "extra_sync_timestamp_ms"

        /**
         * Core synchronization and execution scheduling logic:
         * 1. Fetches upcoming events (recurring and timed) from Android Calendar Provider.
         * 2. Maps them to the application's task queue (MissionEntity).
         * 3. Checks schedule conflicts.
         * 4. Upserts missions into CommandDao.
         * 5. Schedules exact execution alarms via AlarmManager for every upcoming mission.
         */
        suspend fun performCalendarSyncAndScheduling(
            context: Context,
            windowHours: Int = 72,
            targetPersonalityId: String? = null,
            targetVoicePackId: String? = null
        ): CalendarSyncResult = withContext(Dispatchers.IO) {
            val database = CommandDatabase.getInstance(context)
            val dao = database.commandDao()
            val presetDao = database.personalityPresetDao()

            val currentProfile = dao.getStreakProfileOnce()
            val activeGlobalPreset = presetDao.getActiveGlobalPresetOnce()

            val resolvedPersonality = targetPersonalityId
                ?: activeGlobalPreset?.basePersonalityId
                ?: currentProfile?.selectedPersonalityId
                ?: "drill_sergeant"

            val resolvedVoicePack = targetVoicePackId
                ?: activeGlobalPreset?.voicePackId
                ?: currentProfile?.selectedVoicePackId
                ?: "pack_major_stone"

            val existingMissions = dao.getAllMissionsOnce()
            val syncEngine = GoogleCalendarSyncService(context)

            val syncResult = syncEngine.fetchGoogleCalendarMissions(
                windowHours = windowHours,
                existingMissions = existingMissions,
                defaultPersonalityId = resolvedPersonality,
                defaultVoicePackId = resolvedVoicePack
            )

            // Map and persist new/updated missions to the application's task queue
            val scheduledMissions = mutableListOf<MissionEntity>()
            for (candidate in syncResult.missionsToUpsert) {
                val missionWithPreset = candidate.copy(
                    presetId = activeGlobalPreset?.id
                )
                val insertedId = dao.insertMission(missionWithPreset)
                val finalMission = missionWithPreset.copy(id = insertedId)
                scheduledMissions.add(finalMission)

                // Schedule execution via AlarmManager for future events
                if (finalMission.scheduledEpochMs > System.currentTimeMillis()) {
                    ReveilleAlarmScheduler.scheduleMissionAlarm(context, finalMission)
                }
            }

            // Also re-verify and schedule alarms for existing scheduled missions if not yet past
            for (existing in existingMissions) {
                if (existing.scheduledEpochMs > System.currentTimeMillis()) {
                    ReveilleAlarmScheduler.scheduleMissionAlarm(context, existing)
                }
            }

            syncResult
        }
    }
}
