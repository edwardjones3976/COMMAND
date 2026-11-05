package com.command.reliability

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.command.alarm.AlarmContract
import com.command.domain.reliability.Fix

/**
 * The Android screens that fix what the reliability page reports. They are only ever opened because the person tapped a
 * button, and the app changes nothing itself: Android keeps these settings.
 */
object SystemLinks {

    /** The screen for [fix], or null when this version of Android has none (the full-screen permission before Android 14). */
    fun intentFor(context: Context, fix: Fix): Intent? {
        val intent = when (fix) {
            Fix.ASK_NOTIFICATIONS -> notificationSettings(context)
            Fix.OPEN_EXACT_ALARM_SETTINGS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)) else appDetails(context)
            Fix.OPEN_FULL_SCREEN_SETTINGS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context)) else null
            Fix.OPEN_ALARM_CHANNEL_SETTINGS -> channelSettings(context, AlarmContract.CHANNEL_ALARM)
            Fix.OPEN_CHECK_IN_CHANNEL_SETTINGS -> channelSettings(context, AlarmContract.CHANNEL_CHECK_IN)
            Fix.OPEN_SOUND_SETTINGS -> Intent(Settings.ACTION_SOUND_SETTINGS)
            // The list of apps, not the request that asks to exempt this one: that request needs a permission that is meant for
            // a narrower set of apps, and the person can find COMMAND in the list.
            Fix.OPEN_BATTERY_SETTINGS -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            Fix.OPEN_APP_SETTINGS -> appDetails(context)
        }
        return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** One notification channel's own page; before Android 8 there are no channels, and the app's notification page is all there is. */
    private fun channelSettings(context: Context, channelId: String): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        } else {
            notificationSettings(context)
        }

    /** The app's own notification page; before Android 8 there is none, and the app's details page has the switch. */
    fun notificationSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appDetails(context)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens [intent]; false if nothing on this phone can handle it. */
    fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun packageUri(context: Context) = "package:${context.packageName}".toUri()
}
