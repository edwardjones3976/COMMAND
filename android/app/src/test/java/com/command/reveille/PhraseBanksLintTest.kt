package com.command.reveille

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.domain.coaching.DirectiveLint
import com.command.domain.dials.Personality
import com.example.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phrases a person is asked to type are words the app puts in their mouth: they answer to the same lint as everything it says. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhraseBanksLintTest {
    @Test
    fun `every typing phrase and the fallback pass the lint`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = ResourcePhraseSource(context)
        for (p in Personality.values()) for (phrase in source.phrases(p)) assertTrue("$p: $phrase", DirectiveLint.passes(phrase))
        assertTrue(DirectiveLint.passes(context.getString(R.string.handshake_phrase_fallback)))
    }
}
