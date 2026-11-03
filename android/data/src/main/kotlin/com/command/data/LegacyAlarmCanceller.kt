package com.command.data

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import com.command.data.db.CommandDb
import com.command.data.db.SettingRow
import com.command.domain.model.SettingKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The previous version armed one platform alarm per mission and one repeating calendar alarm, and nothing in this version
 * will ever cancel them: their receivers no longer exist, so each would fire into nothing, and the calendar alarm would
 * sit in the system's list forever. This finds them by the identity they were created with (the component, the action
 * and the request code, which was the mission's old row id) and cancels them, once, on the first start after the
 * upgrade. It also removes the old notification channel so the system settings do not show a dead entry.
 *
 * The old database is only read, never changed.
 */
class LegacyAlarmCanceller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CommandDb,
) {
    /** Returns how many old alarms it cancelled, or null if it had already run. */
    suspend fun cancelIfNeeded(): Int? {
        if (db.settings().get(SettingKeys.LEGACY_ALARMS_CANCELLED) == "true") return null
        val cancelled = withContext(Dispatchers.IO) { cancelAll() }
        db.settings().put(SettingRow(SettingKeys.LEGACY_ALARMS_CANCELLED, "true"))
        return cancelled
    }

    private fun cancelAll(): Int {
        var cancelled = 0
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (alarms != null) {
            for (id in legacyMissionIds()) {
                if (cancel(alarms, id.toInt(), MISSION_RECEIVER, action = null)) cancelled++
            }
            if (cancel(alarms, CALENDAR_REQUEST_CODE, CALENDAR_RECEIVER, CALENDAR_ACTION)) cancelled++
        }
        deleteChannel()
        return cancelled
    }

    /**
     * The old row ids, which were the request codes. A database that cannot be read yields none: the old alarms then
     * fire into nothing, which is harmless, and this must never stop the app from starting.
     */
    private fun legacyMissionIds(): List<Long> {
        val file = context.getDatabasePath(LegacyImporter.LEGACY_DB_NAME)
        if (!file.isFile) return emptyList()
        return try {
            // Android's default corruption handler deletes a database it cannot open; these are the user's old files.
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { legacy ->
                legacy.rawQuery("SELECT id FROM missions", null).use { c ->
                    val ids = ArrayList<Long>()
                    while (c.moveToNext()) ids += c.getLong(0)
                    ids
                }
            }
        } catch (e: RuntimeException) {
            emptyList()
        }
    }

    private fun cancel(alarms: AlarmManager, requestCode: Int, receiver: String, action: String?): Boolean {
        val intent = Intent().setComponent(ComponentName(context.packageName, receiver))
        if (action != null) intent.action = action
        // NO_CREATE: only an alarm that exists is found, so nothing is created just to be cancelled.
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            ?: return false
        alarms.cancel(pending)
        pending.cancel()
        return true
    }

    private fun deleteChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(CHANNEL_ID)
    }

    companion object {
        const val MISSION_RECEIVER = "com.example.service.ReveilleAlarmReceiver"
        const val CALENDAR_RECEIVER = "com.example.service.CalendarSyncAlarmReceiver"
        const val CALENDAR_ACTION = "com.example.action.CALENDAR_PERIODIC_WAKEUP"
        const val CALENDAR_REQUEST_CODE = 9021
        const val CHANNEL_ID = "command_reveille_channel"
    }
}
