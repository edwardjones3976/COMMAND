package com.command.consents

import com.command.data.ConsentStore
import com.command.domain.model.ConsentBoundaries
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentSource
import com.command.domain.reliability.Fix
import com.command.domain.reliability.Precondition
import com.command.testing.android.FakeReliabilityMonitor
import com.command.testing.android.awaitUntil
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class ConsentCenterViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var monitor: FakeReliabilityMonitor
    @Inject lateinit var consents: ConsentStore

    private lateinit var vm: ConsentCenterViewModel
    private lateinit var collector: Job

    @Before
    fun setUp() {
        hilt.inject()
        vm = ConsentCenterViewModel(monitor, consents)
        // The state is only live while someone is collecting it, as on screen.
        collector = CoroutineScope(Dispatchers.Main.immediate).launch { vm.state.collect { } }
    }

    @After
    fun tearDown() = collector.cancel()

    private fun ready(): ConsentCenterUiState.Ready? = vm.state.value as? ConsentCenterUiState.Ready

    private fun item(key: ConsentKey) = ready()!!.items.single { it.boundary.key == key }

    @Test
    fun `every consent that exists in this version is listed, in order, and none is known before the phone has been asked`() {
        awaitUntil(message = "ready") { ready() != null }
        assertEquals(ConsentBoundaries.all.map { it.key }, ready()!!.items.map { it.boundary.key })
        for (item in ready()!!.items) {
            assertNull("nothing recorded yet for ${item.boundary.key}", item.granted)
            assertTrue(item.history.isEmpty())
        }
    }

    @Test
    fun `asking the phone fills each consent from what Android says, recorded as the system's doing`() {
        awaitUntil(message = "ready") { ready() != null }
        vm.refresh()
        awaitUntil(message = "recorded") { ready()?.items?.all { it.granted == true } == true }

        for (key in listOf(ConsentKey.NOTIFICATIONS, ConsentKey.EXACT_ALARM, ConsentKey.FULL_SCREEN)) {
            assertEquals(ConsentSource.SYSTEM, item(key).latest!!.source)
            assertEquals(1, item(key).history.size)
        }
    }

    @Test
    fun `a permission turned off in settings becomes a second line of history, newest first`() {
        vm.refresh()
        awaitUntil(message = "first") { ready()?.items?.all { it.granted == true } == true }

        monitor.set(Precondition.NOTIFICATIONS, "denied")
        vm.refresh()
        awaitUntil(message = "off") { ready()?.let { item(ConsentKey.NOTIFICATIONS).granted == false } == true }

        assertEquals(listOf(false, true), item(ConsentKey.NOTIFICATIONS).history.map { it.granted })
        assertEquals("the others are untouched", true, item(ConsentKey.EXACT_ALARM).granted)
    }

    @Test
    fun `each consent that Android keeps points to the screen where it is changed`() {
        awaitUntil(message = "ready") { ready() != null }
        assertEquals(Fix.ASK_NOTIFICATIONS, item(ConsentKey.NOTIFICATIONS).fix)
        assertEquals(Fix.OPEN_EXACT_ALARM_SETTINGS, item(ConsentKey.EXACT_ALARM).fix)
        assertEquals(Fix.OPEN_FULL_SCREEN_SETTINGS, item(ConsentKey.FULL_SCREEN).fix)
    }
}
