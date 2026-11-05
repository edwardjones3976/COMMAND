package com.command.alarm

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.command.domain.alarm.CheckInPrompt
import com.command.domain.model.CheckInAnswer

/**
 * The follow-up question, as a notification: who is asking about what, and the answers that need only one tap. The rest of the
 * answers (a reset, a delay, a skip) need a screen, so they are behind Open check-in. Like every notification here it has a public
 * version without the mission's title, with the same actions, for a locked screen that hides what is private.
 */
object CheckInNotifications {

    /** The answers that can be given from the notification itself. */
    enum class Quick(val answer: CheckInAnswer, val label: Int) {
        STARTED(CheckInAnswer.STARTED, R.string.action_started),
        WORKING(CheckInAnswer.WORKING, R.string.action_still_working),
        DONE(CheckInAnswer.DONE, R.string.action_done),
    }

    /** The first question asks whether the person started; once a start was reported the question is how it is going. */
    fun question(context: Context, prompt: CheckInPrompt): Notification {
        RingNotifications.ensureChannels(context)
        val open = open(context, prompt.occurrenceId, prompt.requestCode)
        val first = if (prompt.started) Quick.WORKING else Quick.STARTED

        fun builder(withTitle: Boolean) = NotificationCompat.Builder(context, AlarmContract.CHANNEL_CHECK_IN)
            .setSmallIcon(R.drawable.ic_stat_command)
            .setContentTitle(if (withTitle && prompt.title.isNotBlank()) prompt.title else context.getString(R.string.checkin_title_generic))
            // The coaching line may hold words the person wrote (a template of their own), so the version for a locked screen asks the plain question.
            .setContentText(prompt.line?.text?.takeIf { withTitle && it.isNotBlank() } ?: context.getString(if (prompt.started) R.string.checkin_text_progress else R.string.checkin_text_start))
            .apply { prompt.line?.text?.takeIf { withTitle && it.isNotBlank() }?.let { setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            // From Android 8 the channel says that a question is to be heard. Before it there is no channel, so the notification asks itself.
            .apply { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) setDefaults(NotificationCompat.DEFAULT_SOUND) }
            .addAction(0, context.getString(first.label), answer(context, prompt, first))
            .addAction(0, context.getString(Quick.DONE.label), answer(context, prompt, Quick.DONE))
            .addAction(0, context.getString(R.string.action_open_check_in), open)

        return builder(withTitle = true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(builder(withTitle = false).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build())
            .build()
    }

    /** Opens the app on this occurrence's question. The app proper lives in :app, so it is addressed by name. */
    fun open(context: Context, occurrenceId: String, requestCode: Int): PendingIntent {
        val intent = Intent(AlarmContract.ACTION_OPEN_CHECK_IN)
            .setClassName(context.packageName, AlarmContract.MAIN_ACTIVITY_CLASS)
            .setData("command://checkin-open/$occurrenceId".toUri())
            .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, occurrenceId)
            .putExtra(AlarmContract.EXTRA_REQUEST_CODE, requestCode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun answer(context: Context, prompt: CheckInPrompt, quick: Quick): PendingIntent {
        val intent = Intent(context, CheckInAnswerReceiver::class.java)
            .setAction(AlarmContract.ACTION_CHECK_IN_ANSWER)
            .setData("command://checkin-answer/${prompt.occurrenceId}/${quick.name}".toUri())
            .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, prompt.occurrenceId)
            .putExtra(AlarmContract.EXTRA_REQUEST_CODE, prompt.requestCode)
            .putExtra(AlarmContract.EXTRA_ANSWER, quick.answer.name)
        return PendingIntent.getBroadcast(context, prompt.requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
