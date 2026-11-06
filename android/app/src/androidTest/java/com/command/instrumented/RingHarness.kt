package com.command.instrumented

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.command.alarm.AlarmContract
import com.command.alarm.SilenceReceiver
import com.command.data.db.EventRow
import com.command.data.db.OccurrenceRow
import com.command.debug.DebugRingReceiver
import com.command.domain.occurrence.OccurrenceState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail

/**
 * What the ring tests share. They run in the app's own process on the real platform: a ring is started by the debug receiver, which arms a
 * real alarm clock, and what happened is read back from the app's real database and from the system's own view of the device.
 */
class RingHarness {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val graph = EntryPointAccessors.fromApplication(context.applicationContext, DebugRingReceiver.Graph::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    val isScreenOn: Boolean get() = power.isInteractive
    val isLocked: Boolean get() = keyguard.isKeyguardLocked

    fun shell(command: String): String = device.executeShellCommand(command)

    /** Lets the app post its ring and use its full-screen intent, which a fresh install on a current Android may not have yet. */
    fun allowRingSurfaces() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT allow")
    }

    fun unlockedAtHome() {
        device.wakeUp()
        // Right after a PIN is cleared one dismiss can leave a bouncer up with no credential behind it (seen on API 37), so it is repeated, and
        // past the first attempts the screen is cycled, which rebuilds the keyguard from the credential as it now is.
        var attempts = 0
        waitFor("the screen on and unlocked", timeoutMs = 60_000) {
            if (isScreenOn && !isLocked) return@waitFor true
            attempts++
            if (attempts % 20 == 0) {
                shell("input keyevent KEYCODE_SLEEP")
                Thread.sleep(1_000)
                device.wakeUp()
            }
            if (attempts % 8 == 1) shell("wm dismiss-keyguard")
            false
        }
        device.pressHome()
    }

    /** Arms a real alarm [seconds] from now, under a [title] of one word, and returns the occurrence once it is armed. */
    fun armRing(title: String, seconds: Int): OccurrenceRow {
        val known = runBlocking { graph.database().occurrences().all().map { it.id }.toSet() }
        context.sendBroadcast(
            Intent(DebugRingReceiver.ACTION).setClass(context, DebugRingReceiver::class.java).putExtra("seconds", seconds).putExtra("title", title),
        )
        lateinit var created: OccurrenceRow
        waitFor("an armed occurrence for $title") {
            val row = runBlocking { graph.database().occurrences().all() }.firstOrNull { it.id !in known && it.state == OccurrenceState.ARMED }
            if (row != null) created = row
            row != null
        }
        return created
    }

    fun occurrence(id: String): OccurrenceRow = runBlocking { graph.repository().get(id) } ?: error("no occurrence $id")

    fun events(id: String): List<EventRow> = runBlocking { graph.database().events().forOccurrence(id) }

    fun awaitState(id: String, state: OccurrenceState, timeoutMs: Long = 90_000) =
        waitFor("occurrence in $state", timeoutMs) { occurrence(id).state == state }

    /** The activity the window manager says is on top, in its own words. */
    fun topActivity(): String =
        shell("dumpsys activity activities").lineSequence().firstOrNull { "topResumedActivity" in it || "mResumedActivity" in it }.orEmpty()

    fun isRingScreenOnTop(): Boolean = "ReveilleActivity" in topActivity()

    fun isRingNotificationPosted(row: OccurrenceRow): Boolean = notifications.activeNotifications.any { it.id == row.requestCode }

    /** The platform's own list of what this app is playing right now, narrowed to the alarm usage. */
    fun isPlayingOnAlarmUsage(): Boolean =
        audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM }

    /** Whether the platform holds the ring service in the foreground. occurrences.ring_mode cannot say: nothing in the app writes it yet. */
    fun isRingServiceInForeground(): Boolean = "isForeground=true" in shell("dumpsys activity services ${context.packageName}")

    /** The notification's own Safety silence, so that a test leaves nothing ringing for the next one. */
    fun silence(row: OccurrenceRow) {
        context.sendBroadcast(
            Intent(context, SilenceReceiver::class.java).setAction(AlarmContract.ACTION_SILENCE)
                .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, row.id).putExtra(AlarmContract.EXTRA_REQUEST_CODE, row.requestCode),
        )
    }

    fun waitFor(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(250)
        }
        if (!condition()) fail("Timed out after $timeoutMs ms waiting for $what. Top: ${topActivity().trim()}")
    }
}
