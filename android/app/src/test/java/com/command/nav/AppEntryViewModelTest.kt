package com.command.nav

import android.os.Looper
import com.command.data.AppSettings
import com.command.testing.android.awaitUntil
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class AppEntryViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var settings: AppSettings

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `a first run opens on the welcome, and not before the setting has been read`() {
        val vm = AppEntryViewModel(settings)
        assertNull("nothing is shown until it is known", vm.start.value)
        awaitUntil(message = "start") { vm.start.value != null }
        assertEquals(Routes.WELCOME, vm.start.value)
    }

    @Test
    fun `once the first-run screens are done the app opens on the schedule`() {
        runBlocking { settings.markOnboardingDone() }
        val vm = AppEntryViewModel(settings)
        awaitUntil(message = "start") { vm.start.value != null }
        assertEquals(Routes.SCHEDULE, vm.start.value)
    }

    @Test
    fun `the first-run screens finishing does not move the starting point of a navigation that is already running`() {
        val vm = AppEntryViewModel(settings)
        awaitUntil(message = "start") { vm.start.value != null }
        assertEquals(Routes.WELCOME, vm.start.value)

        runBlocking { settings.markOnboardingDone() }
        repeat(20) { // give a wrongly live flow time to show itself
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(Routes.WELCOME, vm.start.value)
    }
}
