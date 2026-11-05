package com.command.alarm

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.command.domain.alarm.CheckInPoster
import com.command.domain.alarm.CheckInPrompt
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts the follow-up question in the shade, tagged by its occurrence so that a later question replaces the earlier one and an answer
 * can find it again knowing only the occurrence. With notifications turned off nothing is posted: the question is still recorded as
 * asked, the schedule shows the occurrence waiting for an answer, and the Reliability page already says that notifications are off.
 */
@Singleton
class AndroidCheckInPoster @Inject constructor(
    @ApplicationContext private val context: Context,
) : CheckInPoster {

    override fun post(prompt: CheckInPrompt) {
        RingNotifications.post(context, prompt.occurrenceId, AlarmContract.ID_CHECK_IN, CheckInNotifications.question(context, prompt))
    }

    override fun cancel(occurrenceId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(occurrenceId, AlarmContract.ID_CHECK_IN) }
    }
}
