package com.command.instrumented

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.command.data.db.OccurrenceRow
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** The three faces of a ring that only a real system UI can show: the locked screen, the heads-up, and the swipe that dismisses it. */
@RunWith(AndroidJUnit4::class)
class RingSurfaceTest {
    private val h = RingHarness()
    private var ring: OccurrenceRow? = null
    private var pinSet = false

    @Before
    fun setUp() {
        h.allowRingSurfaces()
        h.unlockedAtHome()
    }

    @After
    fun tearDown() {
        ring?.let { row ->
            h.silence(row)
            h.waitFor("the ring to stop") { !h.isPlayingOnAlarmUsage() }
        }
        h.device.pressBack()
        if (pinSet) {
            h.shell("locksettings clear --old $PIN")
            h.shell("settings delete secure lock_screen_lock_after_timeout")
        }
        h.unlockedAtHome()
    }

    @Test
    fun aRingOnALockedSleepingPhoneTurnsTheScreenOnOverTheKeyguardAndSounds() {
        h.shell("settings put secure lock_screen_lock_after_timeout 0")
        h.shell("locksettings set-pin $PIN")
        pinSet = true
        val row = h.armRing("lockedring", seconds = 20).also { ring = it }

        h.shell("input keyevent KEYCODE_SLEEP")
        h.waitFor("the phone asleep and locked") { !h.isScreenOn && h.isLocked }
        assertEquals(OccurrenceState.ARMED, h.occurrence(row.id).state)

        h.awaitState(row.id, OccurrenceState.FIRING)
        h.waitFor("the screen to turn on") { h.isScreenOn }
        h.waitFor("the ring screen on top") { h.isRingScreenOnTop() }
        assertTrue("the ring must show over the keyguard, not after it", h.isLocked)
        assertTrue("the notification carries the way out on a locked screen", h.isRingNotificationPosted(row))
        h.waitFor("the tone on the alarm usage") { h.isPlayingOnAlarmUsage() }
        assertTrue("the ring must run in the foreground service", h.isRingServiceInForeground())
        assertTrue(h.events(row.id).any { it.type == EventType.ALARM_FIRED })
        assertFalse(h.events(row.id).any { it.type == EventType.RING_DEGRADED })
    }

    @Test
    fun aRingWithTheScreenOnIsAHeadsUpWithOpenAndSoundUntilOpenIsTapped() {
        val row = h.armRing("headsupring", seconds = 10).also { ring = it }
        h.awaitState(row.id, OccurrenceState.FIRING)

        val open = h.device.wait(Until.findObject(By.pkg(SYSTEM_UI).text(Pattern.compile("(?i)open"))), UI_WAIT_MS)
        assertNotNull("the heads-up must offer Open", open)
        assertFalse("with the screen on the ring must not take the screen over", h.isRingScreenOnTop())
        h.waitFor("the service's tone on the alarm usage") { h.isPlayingOnAlarmUsage() }
        assertTrue("the ring must run in the foreground service", h.isRingServiceInForeground())

        open.click()
        h.waitFor("Open to bring up the ring screen") { h.isRingScreenOnTop() }
    }

    @Test
    fun swipingTheRingNotificationAwayStopsTheSoundAndCountsAsSafetySilence() {
        val row = h.armRing("swipering", seconds = 10).also { ring = it }
        h.awaitState(row.id, OccurrenceState.FIRING)
        h.waitFor("the tone on the alarm usage") { h.isPlayingOnAlarmUsage() }

        h.device.openNotification()
        val card = h.device.wait(Until.findObject(By.text("swipering")), UI_WAIT_MS)
        assertNotNull("the ring must be in the shade", card)
        // Across the whole card: the object found is only the title's text, and a swipe as wide as that is too short to dismiss anything.
        val y = card.visibleCenter.y
        h.device.swipe(h.device.displayWidth / 10, y, h.device.displayWidth * 9 / 10, y, 25)

        h.awaitState(row.id, OccurrenceState.SAFETY_SILENCED, timeoutMs = 15_000)
        assertTrue("the swipe is on the record", h.events(row.id).any { it.type == EventType.NOTIFICATION_DISMISSED })
        h.waitFor("the sound to stop") { !h.isPlayingOnAlarmUsage() }
        ring = null
    }

    private companion object {
        const val PIN = "1234"
        const val SYSTEM_UI = "com.android.systemui"
        const val UI_WAIT_MS = 15_000L
    }
}
