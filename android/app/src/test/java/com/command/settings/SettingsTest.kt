package com.command.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.InvalidationTracker
import com.command.alarm.RingPreferences
import com.command.data.AppSettings
import com.command.data.MissionRepository
import com.command.data.db.CommandDb
import com.command.domain.voice.RoutePolicy
import com.command.testing.FakeClock
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.awaitUntil
import com.command.ui.theme.CommandTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** Where the spoken line is heard: chosen on a screen, written the moment it is chosen. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class SettingsViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var settings: AppSettings
    @Inject lateinit var db: CommandDb
    @Inject lateinit var missions: MissionRepository
    @Inject lateinit var clock: FakeClock
    @Inject lateinit var ring: RingPreferences

    @Before
    fun setUp() {
        hilt.inject()
    }

    private fun ready(vm: SettingsViewModel): SettingsUiState.Ready? = vm.state.value as? SettingsUiState.Ready

    /** Waits [ms] while keeping the main looper moving, which the view model's coroutines need to run. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun `a pause the repository refuses is logged and ends nothing but itself`() {
        org.robolectric.shadows.ShadowLog.reset()
        val vm = SettingsViewModel(settings, missions, clock, ring)
        awaitUntil(message = "ready") { ready(vm) != null }

        vm.pauseFor(1_000) // longer than a pause may last: the repository throws

        awaitUntil(message = "logged") { org.robolectric.shadows.ShadowLog.getLogsForTag("ScreenWork").isNotEmpty() }
        vm.pauseFor(1)
        awaitUntil(message = "the next one works") { ready(vm)?.pausedUntilUtcMs != null }
    }

    @Test
    fun `gradual start is off until it is turned on, and is written the moment it is chosen`() {
        val vm = SettingsViewModel(settings, missions, clock, ring)
        awaitUntil(message = "ready") { ready(vm) != null }
        assertEquals(false, ready(vm)!!.gradualStart)

        vm.setGradualStart(true)

        awaitUntil(message = "shown") { ready(vm)?.gradualStart == true }
        assertEquals(true, RingPreferences(androidx.test.core.app.ApplicationProvider.getApplicationContext()).gradualStart)
    }

    @Test
    fun `the line is heard on the speaker and a headset until the person says otherwise`() {
        val vm = SettingsViewModel(settings, missions, clock, ring)
        awaitUntil(message = "ready") { ready(vm) != null }
        assertEquals(RoutePolicy.SPEAKER_AND_HEADSET, ready(vm)!!.routePolicy)
    }

    @Test
    fun `choosing headset only is written down, and then shown`() {
        val vm = SettingsViewModel(settings, missions, clock, ring)
        awaitUntil(message = "ready") { ready(vm) != null }

        vm.chooseRoutePolicy(RoutePolicy.HEADSET_ONLY)
        awaitUntil(message = "written and shown") { ready(vm)?.routePolicy == RoutePolicy.HEADSET_ONLY }
        assertEquals("what is shown is what is stored", RoutePolicy.HEADSET_ONLY, runBlocking { settings.routePolicy() })

        val reopened = SettingsViewModel(settings, missions, clock, ring)
        awaitUntil(message = "ready again") { ready(reopened) != null }
        assertEquals("and it is still chosen when the screen is opened again", RoutePolicy.HEADSET_ONLY, ready(reopened)!!.routePolicy)
    }

    @Test
    fun `choosing what is already chosen writes nothing`() {
        val vm = SettingsViewModel(settings, missions, clock, ring)
        awaitUntil(message = "ready") { ready(vm) != null }
        // The database seeds the default route when it is created, so a write is told from the seed by watching the settings table.
        var writes = 0
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer("settings") {
            override fun onInvalidated(tables: Set<String>) { writes++ }
        })
        settle(100)
        writes = 0

        vm.chooseRoutePolicy(RoutePolicy.SPEAKER_AND_HEADSET)
        settle(300) // a write would have been seen by now; the main looper must be pumped for it to arrive
        assertEquals(RoutePolicy.SPEAKER_AND_HEADSET, ready(vm)!!.routePolicy)
        assertEquals("choosing what is chosen is not a write", 0, writes)

        vm.chooseRoutePolicy(RoutePolicy.HEADSET_ONLY)
        awaitUntil(message = "the same watch sees a real write") { writes > 0 }
    }

    @Test
    fun `pausing for some hours is shown until its end, and ending the pause brings the choices back`() {
        val vm = SettingsViewModel(settings, missions, clock, ring)
        awaitUntil(message = "ready") { ready(vm) != null }
        assertNull(ready(vm)!!.pausedUntilUtcMs)

        vm.pauseFor(4)
        awaitUntil(message = "paused") { ready(vm)?.pausedUntilUtcMs == FakeAlarmModule.T0 + 4 * 3_600_000L }

        vm.endPause()
        awaitUntil(message = "resumed") { ready(vm)?.pausedUntilUtcMs == null }
    }

    @Test
    fun `a pause whose end has passed is no longer shown, before anything has closed it`() {
        val vm = SettingsViewModel(settings, missions, clock, ring)
        awaitUntil(message = "ready") { ready(vm) != null }
        vm.pauseFor(1)
        awaitUntil(message = "paused") { ready(vm)?.pausedUntilUtcMs != null }

        clock.advance(2 * 3_600_000L)
        vm.chooseRoutePolicy(RoutePolicy.HEADSET_ONLY) // anything that makes the screen look again
        awaitUntil(message = "shown as over") { ready(vm)?.routePolicy == RoutePolicy.HEADSET_ONLY && ready(vm)?.pausedUntilUtcMs == null }
    }
}

// A tall screen, so that nothing needs scrolling to be found.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Taps {
        val gradual = ArrayList<Boolean>()
        val routes = ArrayList<RoutePolicy>()
        var history = 0
        var reliability = 0
        var consents = 0
        var data = 0
        var back = 0
        val pauses = ArrayList<Int>()
        var endPause = 0
        var handshakes = 0
        var restWindows = 0
    }

    private val today = LocalDate.of(2026, 11, 2)

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        java.time.ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun timeOf(utcMs: Long) = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC))

    private fun show(policy: RoutePolicy = RoutePolicy.SPEAKER_AND_HEADSET, taps: Taps = Taps(), pausedUntil: Long? = null, gradual: Boolean = false): Taps {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                SettingsScreen(
                    state = SettingsUiState.Ready(policy, pausedUntil, gradual),
                    onBack = { taps.back++ },
                    onChooseRoute = { taps.routes += it },
                    onOpenHistory = { taps.history++ },
                    onOpenReliability = { taps.reliability++ },
                    onOpenConsents = { taps.consents++ },
                    onOpenData = { taps.data++ },
                    onOpenHandshakes = { taps.handshakes++ },
                    onOpenRestWindows = { taps.restWindows++ },
                    version = "4.0",
                    onPause = { taps.pauses += it },
                    onEndPause = { taps.endPause++ },
                    onSetGradual = { taps.gradual += it },
                    zone = ZoneOffset.UTC,
                    today = today,
                )
            }
        }
        return taps
    }

    @Test
    fun `the two routes are offered with what each means, and the chosen one is selected`() {
        show(RoutePolicy.HEADSET_ONLY)
        compose.onNodeWithText("Where the spoken line is heard").assertIsDisplayed()
        compose.onNodeWithText("An alarm always plays its tone and vibrates.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.ROUTE_HEADSET).assertIsSelected()
        compose.onNodeWithTag(SettingsTags.ROUTE_SPEAKER).assertIsNotSelected()
        compose.onNodeWithText("Without one, the tone and the vibration still ring.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `gradual start says what it does and what stays as it is, is off by default, and reports the choice`() {
        val taps = show()
        compose.onNodeWithText("Bring the alarm in gradually").assertIsDisplayed()
        compose.onNodeWithText("your alarm volume is not changed", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.GRADUAL).assertIsOff()
        compose.onNodeWithTag(SettingsTags.GRADUAL).performClick()
        assertEquals(listOf(true), taps.gradual)
    }

    @Test
    fun `gradual start shows what is stored, and turning it off reports that`() {
        val taps = show(gradual = true)
        compose.onNodeWithTag(SettingsTags.GRADUAL).assertIsOn()
        compose.onNodeWithTag(SettingsTags.GRADUAL).performClick()
        assertEquals(listOf(false), taps.gradual)
    }

    @Test
    fun `choosing a route reports it`() {
        val taps = show(RoutePolicy.SPEAKER_AND_HEADSET)
        compose.onNodeWithTag(SettingsTags.ROUTE_HEADSET).performClick()
        assertEquals(listOf(RoutePolicy.HEADSET_ONLY), taps.routes)
    }

    @Test
    fun `history, reliability and permissions and privacy are one tap away`() {
        val taps = show()
        compose.onNodeWithTag(SettingsTags.LINK_HISTORY).performClick()
        compose.onNodeWithTag(SettingsTags.LINK_RELIABILITY).performClick()
        compose.onNodeWithTag(SettingsTags.LINK_CONSENTS).performClick()
        assertEquals(1, taps.history)
        assertEquals(1, taps.reliability)
        assertEquals(1, taps.consents)
    }

    @Test
    fun `the rest windows are one tap away`() {
        val taps = show()
        compose.onNodeWithTag(SettingsTags.LINK_REST_WINDOWS).performClick()
        assertEquals(1, taps.restWindows)
    }

    @Test
    fun `the handshakes are one tap away`() {
        val taps = show()
        compose.onNodeWithTag(SettingsTags.LINK_HANDSHAKES).performClick()
        assertEquals(1, taps.handshakes)
    }

    @Test
    fun `saving a copy of the data or erasing it is one tap away`() {
        val taps = show()
        compose.onNodeWithText("Your data: save a copy or erase everything").assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.LINK_DATA).performClick()
        assertEquals(1, taps.data)
    }

    @Test
    fun `the page says which version this is, and that everything stays on the phone`() {
        show()
        compose.onNodeWithText("COMMAND, version 4.0").assertIsDisplayed()
        compose.onNodeWithText("Everything stays on this phone unless a feature you turn on says otherwise.").assertIsDisplayed()
    }

    @Test
    fun `without a pause, three lengths are offered and each reports its hours`() {
        val taps = show()
        compose.onNodeWithText("Pause COMMAND").assertIsDisplayed()
        compose.onNodeWithText("For 1 hour").assertIsDisplayed()
        compose.onNodeWithText("For 4 hours").assertIsDisplayed()
        compose.onNodeWithText("For 12 hours").assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.PAUSED).assertDoesNotExist()

        compose.onNodeWithTag(SettingsTags.PAUSE_1H).performClick()
        compose.onNodeWithTag(SettingsTags.PAUSE_4H).performClick()
        compose.onNodeWithTag(SettingsTags.PAUSE_12H).performClick()
        assertEquals(listOf(1, 4, 12), taps.pauses)
    }

    @Test
    fun `while paused it says until when, what that means, and offers only to resume`() {
        val until = at(2, 15, 42)
        val taps = show(pausedUntil = until)
        compose.onNodeWithText("Paused until ${timeOf(until)}").assertIsDisplayed()
        compose.onNodeWithText("History says it was paused, not missed.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.PAUSE_1H).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.PAUSE_12H).assertDoesNotExist()

        compose.onNodeWithTag(SettingsTags.RESUME).performClick()
        assertEquals(1, taps.endPause)
        assertEquals(emptyList<Int>(), taps.pauses)
    }

    @Test
    fun `a pause that ends on another day names the day`() {
        val until = at(3, 8)
        show(pausedUntil = until)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(until).atZone(ZoneOffset.UTC))
        compose.onNodeWithText("Paused until $date at ${timeOf(until)}").assertIsDisplayed()
    }
}
