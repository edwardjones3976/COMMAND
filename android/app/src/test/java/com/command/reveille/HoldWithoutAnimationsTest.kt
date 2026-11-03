package com.command.reveille

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Remove animations" (an accessibility setting) and a zero animator scale (a developer option) make every animation finish
 * at once. The hold is a deliberate act timed by the clock, so it must not shrink to a tap when animations are off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HoldWithoutAnimationsTest {

    private val noAnimations = object : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }

    @get:Rule val compose = createComposeRule(noAnimations)

    @Test
    fun `animations really are off in this test`() {
        var scale = -1f
        compose.setContent {
            scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        }
        compose.runOnIdle { assertEquals(0f, scale, 0f) }
    }

    @Test
    fun `a quick tap is not a hold, and a full hold still is`() {
        val completed = ArrayList<Long>()
        val early = ArrayList<Long>()
        compose.mainClock.autoAdvance = false
        compose.setContent { HoldButton(3, { a, _ -> completed += a }, { a, _ -> early += a }, {}) }

        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertTrue("a tap with animations off must not count as a hold", completed.isEmpty())
        assertEquals(1, early.size)

        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(3_300)
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        assertEquals("three seconds of holding completes it", 1, completed.size)
        assertEquals(1, early.size)
    }
}
