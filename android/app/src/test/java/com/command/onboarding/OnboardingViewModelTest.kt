package com.command.onboarding

import com.command.data.AppSettings
import com.command.data.MissionDraft
import com.command.data.MissionRepository
import com.command.data.SaveResult
import com.command.data.ScheduleDraft
import com.command.domain.dials.Flexibility
import com.command.domain.reliability.Precondition
import com.command.testing.android.FakeReliabilityMonitor
import com.command.testing.android.awaitUntil
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class OnboardingViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var monitor: FakeReliabilityMonitor
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var missions: MissionRepository

    private lateinit var vm: OnboardingViewModel
    private lateinit var collector: Job

    @Before
    fun setUp() {
        hilt.inject()
    }

    @After
    fun tearDown() {
        if (this::collector.isInitialized) collector.cancel()
    }

    private fun start() {
        vm = OnboardingViewModel(monitor, settings, missions)
        // The state is only live while someone is collecting it, as on screen.
        collector = CoroutineScope(Dispatchers.Main.immediate).launch { vm.permissions.collect { } }
    }

    private fun ready(): PermissionsUiState.Ready? = vm.permissions.value as? PermissionsUiState.Ready

    @Test
    fun `nothing is listed until the phone has been read, then the three permissions, notifications first`() {
        start()
        assertEquals(PermissionsUiState.Loading, vm.permissions.value)
        vm.refresh()
        awaitUntil(message = "ready") { ready() != null }
        assertEquals(
            listOf(Precondition.NOTIFICATIONS, Precondition.EXACT_ALARMS, Precondition.FULL_SCREEN_INTENT),
            ready()!!.items.map { it.precondition },
        )
        assertTrue(ready()!!.allAllowed)
    }

    @Test
    @Config(sdk = [33])
    fun `before Android 14 there is no full-screen permission to ask about`() {
        start()
        vm.refresh()
        awaitUntil(message = "ready") { ready() != null }
        assertEquals(listOf(Precondition.NOTIFICATIONS, Precondition.EXACT_ALARMS), ready()!!.items.map { it.precondition })
    }

    @Test
    fun `a refusal is reported as something not yet allowed, and the request can be asked until it was answered`() {
        monitor.set(Precondition.NOTIFICATIONS, "denied")
        start()
        vm.refresh()
        awaitUntil(message = "ready") { ready() != null }
        assertFalse(ready()!!.allAllowed)
        assertTrue(ready()!!.canAskNotifications)

        vm.onNotificationRequestResult()
        awaitUntil(message = "answered") { ready()?.canAskNotifications == false }
        assertTrue("the answer is remembered", runBlocking { settings.notificationsAsked() })
        assertFalse("a refusal stays a refusal", ready()!!.allAllowed)
    }

    @Test
    fun `granting the request shows as allowed the moment the answer comes back`() {
        monitor.set(Precondition.NOTIFICATIONS, "denied")
        start()
        vm.refresh()
        awaitUntil(message = "ready") { ready() != null }
        assertFalse(ready()!!.allAllowed)

        monitor.set(Precondition.NOTIFICATIONS, "granted") // what Android reports once the person taps Allow
        vm.onNotificationRequestResult()
        awaitUntil(message = "allowed") { ready()?.allAllowed == true }
    }

    @Test
    @Config(sdk = [32])
    fun `before Android 13 there is no request to ask`() {
        start()
        vm.refresh()
        awaitUntil(message = "ready") { ready() != null }
        assertFalse(ready()!!.canAskNotifications)
    }

    @Test
    fun `finishing remembers it, so these screens are not shown again, and then carries on`() {
        start()
        var carried = false
        assertFalse(runBlocking { settings.onboardingDone.first() })

        vm.finish { carried = true }
        awaitUntil(message = "carried on") { carried }
        assertTrue(runBlocking { settings.onboardingDone.first() })
    }

    @Test
    fun `missions are known to exist once there is one of the person's own`() {
        start()
        awaitUntil(message = "known") { vm.hasMissions.value != null }
        assertEquals(false, vm.hasMissions.value)

        runBlocking {
            val draft = MissionDraft(
                title = "Standup", firstAction = "Open the notes", flexibility = Flexibility.FLEXIBLE,
                schedule = ScheduleDraft.Once(LocalDateTime.of(2026, 11, 2, 11, 0)),
            )
            assertTrue(missions.save(draft) is SaveResult.Saved)
        }
        val later = OnboardingViewModel(monitor, settings, missions)
        awaitUntil(message = "known again") { later.hasMissions.value != null }
        assertEquals(true, later.hasMissions.value)
    }

    @Test
    fun `before it is known whether there are missions the answer is unknown, not no`() {
        start()
        assertNull(vm.hasMissions.value)
    }
}
