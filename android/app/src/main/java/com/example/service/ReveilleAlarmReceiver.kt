package com.example.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.MissionEntity

class ReveilleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val missionTitle = intent?.getStringExtra(EXTRA_MISSION_TITLE) ?: "Scheduled Commitment"
        val missionId = intent?.getLongExtra(EXTRA_MISSION_ID, -1L) ?: -1L
        ReveilleAlarmScheduler.postReveilleNotification(context, missionId, missionTitle)
    }

    companion object {
        const val EXTRA_MISSION_ID = "extra_mission_id"
        const val EXTRA_MISSION_TITLE = "extra_mission_title"
        const val CHANNEL_ID = "command_reveille_channel"
    }
}

object ReveilleAlarmScheduler {

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                ReveilleAlarmReceiver.CHANNEL_ID,
                context.getString(R.string.reveille_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.reveille_channel_desc)
                enableVibration(true)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    fun scheduleMissionAlarm(context: Context, mission: MissionEntity): Boolean {
        ensureNotificationChannel(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val intent = Intent(context, ReveilleAlarmReceiver::class.java).apply {
            putExtra(ReveilleAlarmReceiver.EXTRA_MISSION_ID, mission.id)
            putExtra(ReveilleAlarmReceiver.EXTRA_MISSION_TITLE, mission.title)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            mission.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java)
        val showPending = PendingIntent.getActivity(
            context,
            mission.id.toInt(),
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return try {
            val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            if (canScheduleExact) {
                val info = AlarmManager.AlarmClockInfo(mission.scheduledEpochMs, showPending)
                alarmManager.setAlarmClock(info, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    mission.scheduledEpochMs,
                    pendingIntent
                )
            }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun cancelMissionAlarm(context: Context, missionId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ReveilleAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            missionId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun postReveilleNotification(context: Context, missionId: Long, missionTitle: String) {
        ensureNotificationChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ReveilleAlarmReceiver.EXTRA_MISSION_ID, missionId)
        }
        val pendingTap = PendingIntent.getActivity(
            context,
            missionId.toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ReveilleAlarmReceiver.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("COMMAND REVEILLE: $missionTitle")
            .setContentText("Complete your handshake and start the 5-minute action block now!")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingTap)
            .build()

        try {
            manager.notify(missionId.toInt().coerceAtLeast(100), notification)
        } catch (_: SecurityException) {
        }
    }
}
