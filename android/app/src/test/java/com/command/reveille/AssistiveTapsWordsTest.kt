package com.command.reveille

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two sentences spell out how many taps stand in for a press ('Confirm with three taps', 'the three confirming taps'), and the ring asks for
 * [ASSISTIVE_TAPS] of them. What the app says must be true of what it does: if the number changes, the words change with it, and this test
 * is what says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AssistiveTapsWordsTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `the words that spell out the number of confirming taps are about the number the ring asks for`() {
        val number = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")[ASSISTIVE_TAPS]
        val sentences = listOf(
            "the button for a press whose tries are used up" to resources.getString(R.string.handshake_alternative_taps),
            "the time of the taps, one second" to resources.getQuantityString(R.plurals.handshake_time_label_taps, 1, 1),
            "the time of the taps, several seconds" to resources.getQuantityString(R.plurals.handshake_time_label_taps, 90, 90),
        )
        for ((where, text) in sentences) {
            assertTrue("$where must spell '$number', because the ring asks for $ASSISTIVE_TAPS taps: \"$text\"", text.contains(number))
        }
    }
}
