package com.command.reliability

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.command.domain.reliability.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each fix opens the Android screen that holds the setting it is about; none changes anything itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SystemLinksTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun intent(fix: Fix): Intent = SystemLinks.intentFor(context, fix)!!

    private val packageUri get() = "package:${context.packageName}"

    @Test
    fun `notifications go to the app's own notification page`() {
        val intent = intent(Fix.ASK_NOTIFICATIONS)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `exact alarms go to the page that grants them, for this app`() {
        val intent = intent(Fix.OPEN_EXACT_ALARM_SETTINGS)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
        assertEquals(packageUri, intent.dataString)
    }

    @Test
    fun `full-screen alarms go to the page that grants them, for this app`() {
        val intent = intent(Fix.OPEN_FULL_SCREEN_SETTINGS)
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, intent.action)
        assertEquals(packageUri, intent.dataString)
    }

    @Test
    fun `the alarm channel goes to that channel's own page`() {
        val intent = intent(Fix.OPEN_ALARM_CHANNEL_SETTINGS)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals("reveille_alarm", intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun `sound, battery and app settings go to the matching pages`() {
        assertEquals(Settings.ACTION_SOUND_SETTINGS, intent(Fix.OPEN_SOUND_SETTINGS).action)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intent(Fix.OPEN_BATTERY_SETTINGS).action)
        val app = intent(Fix.OPEN_APP_SETTINGS)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app.action)
        assertEquals(packageUri, app.dataString)
    }

    @Test
    fun `none of them asks Android to exempt the app from battery optimisation on its own`() {
        // That request needs a permission meant for a narrower set of apps; the person is sent to the list instead.
        for (fix in Fix.values()) {
            val intent = SystemLinks.intentFor(context, fix) ?: continue
            assertNotEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        }
    }

    @Test
    fun `every page opens in a task of its own, so back returns to the app`() {
        for (fix in Fix.values()) {
            val intent = SystemLinks.intentFor(context, fix) ?: continue
            assertTrue("$fix", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

    @Test
    @Config(sdk = [33])
    fun `before Android 14 there is no full-screen permission to open`() {
        assertNull(SystemLinks.intentFor(context, Fix.OPEN_FULL_SCREEN_SETTINGS))
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 12 exact alarms need no grant, so the app's own page is the nearest place`() {
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent(Fix.OPEN_EXACT_ALARM_SETTINGS).action)
    }

    @Test
    @Config(sdk = [24])
    fun `before Android 8 there is no page for an app's notifications, so the app's details page has the switch`() {
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, SystemLinks.notificationSettings(context).action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent(Fix.OPEN_ALARM_CHANNEL_SETTINGS).action)
    }

    @Test
    fun `opening a page that this phone does not have reports it instead of crashing`() {
        val phoneWithoutThatPage = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent?) = throw ActivityNotFoundException()
        }
        assertFalse(SystemLinks.open(phoneWithoutThatPage, intent(Fix.OPEN_SOUND_SETTINGS)))
        assertTrue(SystemLinks.open(context, intent(Fix.OPEN_SOUND_SETTINGS)))
        assertNotNull(SystemLinks.notificationSettings(context))
    }
}
