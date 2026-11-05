package com.command.startup

import android.content.Context
import android.util.Log
import com.command.alarm.RingNotifications
import com.command.data.LegacyAlarmCanceller
import com.command.data.LegacyImporter
import com.command.data.MissionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * What has to be true whenever the app comes to the front: the notification channels exist, the previous version's data
 * has been brought across and its dead alarms taken down (both once only), and the coming week of every mission is
 * scheduled with a real alarm behind each one. It does not run in the alarm path, which must never wait for it.
 *
 * Every step stands alone, so one failing never stops the next: a mission list that cannot be refreshed must not also
 * leave the alarms of the previous version in place. A step that fails leaves a line in the log; the app still opens.
 */
@Singleton
class AppStartup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: LegacyImporter,
    private val legacyAlarms: LegacyAlarmCanceller,
    private val missions: MissionRepository,
) {
    suspend fun run() {
        step("channels") { RingNotifications.ensureChannels(context) }
        step("legacy import") { importer.importIfNeeded() }
        step("legacy alarms") { legacyAlarms.cancelIfNeeded() }
        step("window") { missions.refreshWindow() }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "startup step failed: $name", e)
        }
    }

    private companion object {
        const val TAG = "AppStartup"
    }
}
