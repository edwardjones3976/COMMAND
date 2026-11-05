package com.command

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Proves Hilt (KSP, Gradle plugin) builds and injects on AGP 9 / Kotlin 2.2 under Robolectric. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class HiltGraphTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var info: BuildInfo
    @Inject lateinit var again: BuildInfo

    @Before fun inject() = hilt.inject()

    @Test
    fun `graph builds and singletons are shared`() {
        assertEquals("com.aistudio.command.vtxrpl", info.applicationId)
        assertSame(info, again)
    }
}
