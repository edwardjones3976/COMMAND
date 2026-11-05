package com.command.reveille

import android.content.Context
import com.command.domain.dials.Personality
import com.command.domain.handshake.TryId
import com.example.R
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * What the ring screen asks of the person now. Each challenge that takes tries carries the [TryId] it is about, which the screen hands back
 * with whatever the person does: an answer that is not about the try on screen (a double tap, a screen that was recreated) changes nothing.
 */
sealed interface Challenge {
    /**
     * A press of [seconds]. In front of a two-step ([acknowledging]) it is the hold that acknowledges the alarm; once the person has run out
     * of tries on it ([assistive]) it is three deliberate taps in its place.
     */
    data class Hold(val tryId: TryId, val seconds: Int, val earlyRelease: Boolean, val assistive: Boolean, val acknowledging: Boolean) : Challenge

    /**
     * The phrase to type. [mistaken] says that the last try was wrong (or took too long), [slow] that it was the time that ran out and not
     * the answer that was wrong, and [triesLeft] how many remain on this way.
     */
    data class Typing(val tryId: TryId, val phrase: String, val triesLeft: Int, val mistaken: Boolean, val slow: Boolean = false) : Challenge

    /** The numbers 1 to 4, to be tapped in order; [layout] is where they stand, [tapped] how many have been tapped rightly. */
    data class Sequence(val tryId: TryId, val layout: List<Int>, val tapped: Int, val triesLeft: Int, val mistaken: Boolean, val slow: Boolean = false) : Challenge

    /** The person says what they will do first: [firstAction] is what they are asked to stand behind. */
    data class TwoStep(val tryId: TryId, val firstAction: String) : Challenge

    /** Every way is used up. The alarm goes on until its time is up; silence, delay and skip are still there. */
    data object OutOfTries : Challenge
}

/** Where a chain of several steps stands, for the words "Step 1 of 2". */
data class ChainProgress(val step: Int, val steps: Int)

/**
 * The phrases a personality's typing challenge asks for. They are words, so they are resources: a phone in another language asks in it.
 * Never empty: the typing challenge must always have something to ask.
 */
interface PhraseSource {
    fun phrases(personality: Personality): List<String>
}

class ResourcePhraseSource @Inject constructor(@ApplicationContext private val context: Context) : PhraseSource {
    override fun phrases(personality: Personality): List<String> {
        val id = when (personality) {
            Personality.DRILL_SERGEANT -> R.array.handshake_phrases_drill_sergeant
            Personality.EXECUTIVE_DIRECTOR -> R.array.handshake_phrases_executive_director
            Personality.SUPPORTIVE_COACH -> R.array.handshake_phrases_supportive_coach
            Personality.ACCOUNTABILITY_PARTNER -> R.array.handshake_phrases_accountability_partner
            Personality.MINIMALIST -> R.array.handshake_phrases_minimalist
            Personality.CUSTOM -> R.array.handshake_phrases_custom
        }
        // A blank item could never be typed (an empty answer is never right), so it is not asked for; a bank with nothing in it asks one plain phrase.
        return context.resources.getStringArray(id).filter { it.isNotBlank() }.ifEmpty { listOf(context.getString(R.string.handshake_phrase_fallback)) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HandshakeModule {
    @Binds
    abstract fun phrases(impl: ResourcePhraseSource): PhraseSource
}
