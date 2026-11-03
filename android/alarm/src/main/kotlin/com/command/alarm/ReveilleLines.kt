package com.command.alarm

import android.content.Context
import com.command.data.MissionBrief
import com.command.domain.voice.Line
import com.command.domain.voice.Segment
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * What a ring says when it starts. Short on purpose: it is heard over an alarm tone, by someone who has just woken, and the full
 * words are on the screen. The mission's own words (its title, its first step) are kept in segments of their own, because they are
 * the person's text and are only ever spoken by the phone's own voice. The coaching engine replaces this with lines that follow the
 * person's dials. The words themselves are string resources, like every other text.
 */
class ReveilleLines @Inject constructor(@ApplicationContext private val context: Context) {

    fun reveille(brief: MissionBrief?): Line {
        val title = spoken(brief?.title)
        val step = spoken(brief?.firstAction)
        val segments = ArrayList<Segment>()
        segments += Segment(context.getString(if (title.isEmpty()) R.string.say_start else R.string.say_start_titled))
        if (title.isNotEmpty()) segments += Segment(context.getString(R.string.say_user_text, title), carriesUserText = true)
        if (step.isNotEmpty()) {
            segments += Segment(context.getString(R.string.say_first_step))
            segments += Segment(context.getString(R.string.say_user_text, step), carriesUserText = true)
        }
        return Line(segments)
    }

    /** The person's words, trimmed and without a closing full stop, because the sentence they are put in has its own. */
    private fun spoken(text: String?): String = text.orEmpty().trim().trimEnd('.').trim()
}
