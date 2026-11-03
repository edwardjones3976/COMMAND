package com.command.alarm

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** What the alarm module asks the platform for, pinned so a change is a deliberate one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmManifestTest {

    private val manifest: String by lazy { File("src/main/AndroidManifest.xml").readText() }

    @Test
    fun `the service is a foreground service of the types the plan names, and private`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, ReveilleService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertFalse(info.exported)
        val expected = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        assertEquals(expected, info.foregroundServiceType)
        assertTrue("the type passed at runtime is one of the declared ones", ReveilleService.FGS_TYPE and expected == ReveilleService.FGS_TYPE)
    }

    @Test
    fun `no component of the alarm module can be started by another app`() {
        val exported = Regex("""android:exported="true"""")
        assertFalse(exported.containsMatchIn(manifest))
        for (name in listOf(".ReveilleService", ".ReveilleAlarmReceiver", ".CycleEndReceiver", ".CheckInReceiver", ".SilenceReceiver")) {
            assertTrue("$name must be declared", manifest.contains("""android:name="$name""""))
        }
    }

    @Test
    fun `the permissions are exactly the planned ones`() {
        val declared = Regex("""<uses-permission android:name="android\.permission\.([A-Z_]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "POST_NOTIFICATIONS", "USE_FULL_SCREEN_INTENT", "FOREGROUND_SERVICE",
                "FOREGROUND_SERVICE_MEDIA_PLAYBACK", "FOREGROUND_SERVICE_SYSTEM_EXEMPTED", "WAKE_LOCK", "VIBRATE",
            ),
            declared,
        )
    }

    @Test
    fun `the old exact-alarm permission is limited to the versions that need it`() {
        assertTrue(manifest.contains("""android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32""""))
    }

    @Test
    fun `nothing here can read the user's data`() {
        for (forbidden in listOf("READ_CALENDAR", "RECORD_AUDIO", "CAMERA", "ACCESS_FINE_LOCATION", "QUERY_ALL_PACKAGES", "INTERNET")) {
            assertFalse(forbidden, manifest.contains(forbidden))
        }
    }
}
