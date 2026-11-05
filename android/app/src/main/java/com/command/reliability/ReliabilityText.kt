package com.command.reliability

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.command.domain.reliability.Fix
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.PreconditionValue
import com.command.domain.reliability.ReliabilityItem
import com.command.domain.reliability.Severity
import com.example.R

/** What the reliability page and the first-run permissions screen say about each precondition, in plain words. */

@Composable
internal fun itemTitle(precondition: Precondition): String = stringResource(
    when (precondition) {
        Precondition.EXACT_ALARMS -> R.string.reliability_exact_title
        Precondition.NOTIFICATIONS -> R.string.reliability_notifications_title
        Precondition.FULL_SCREEN_INTENT -> R.string.reliability_fullscreen_title
        Precondition.ALARM_CHANNEL -> R.string.reliability_channel_title
        Precondition.ALARM_VOLUME -> R.string.reliability_volume_title
        Precondition.BATTERY_OPTIMIZATION -> R.string.reliability_battery_title
        Precondition.BACKGROUND_RESTRICTED -> R.string.reliability_background_title
        Precondition.STANDBY_BUCKET -> R.string.reliability_standby_title
    },
)

/** The one-line state: "Allowed", "Off", "Set too low (low)". Severity is also said in words by the section it is listed in. */
@Composable
internal fun itemState(item: ReliabilityItem): String {
    val ok = item.severity == Severity.OK
    return when (item.precondition) {
        Precondition.EXACT_ALARMS -> stringResource(if (ok) R.string.reliability_exact_ok else R.string.reliability_exact_problem)
        Precondition.NOTIFICATIONS -> stringResource(if (ok) R.string.reliability_notifications_ok else R.string.reliability_notifications_problem)
        Precondition.FULL_SCREEN_INTENT -> stringResource(if (ok) R.string.reliability_fullscreen_ok else R.string.reliability_fullscreen_problem)
        Precondition.ALARM_CHANNEL ->
            if (ok) {
                stringResource(R.string.reliability_channel_ok)
            } else {
                stringResource(R.string.reliability_channel_problem, stringResource(channelLevel(item.value)))
            }
        Precondition.ALARM_VOLUME -> stringResource(if (ok) R.string.reliability_volume_ok else R.string.reliability_volume_problem)
        Precondition.BATTERY_OPTIMIZATION -> stringResource(if (item.value == PreconditionValue.EXEMPT) R.string.reliability_battery_ok else R.string.reliability_battery_info)
        Precondition.BACKGROUND_RESTRICTED -> stringResource(if (ok) R.string.reliability_background_ok else R.string.reliability_background_problem)
        Precondition.STANDBY_BUCKET -> stringResource(standbyName(item.value))
    }
}

private fun channelLevel(value: String): Int = when (value) {
    "DEFAULT" -> R.string.reliability_channel_level_default
    "LOW" -> R.string.reliability_channel_level_low
    "MIN" -> R.string.reliability_channel_level_min
    "NONE" -> R.string.reliability_channel_level_none
    else -> R.string.reliability_channel_level_missing
}

private fun standbyName(value: String): Int = when (value) {
    "ACTIVE" -> R.string.reliability_standby_active
    "WORKING_SET" -> R.string.reliability_standby_working_set
    "FREQUENT" -> R.string.reliability_standby_frequent
    "EXEMPTED" -> R.string.reliability_standby_exempted
    "RARE" -> R.string.reliability_standby_rare
    "RESTRICTED" -> R.string.reliability_standby_restricted
    else -> R.string.reliability_standby_unknown
}

/** What not having it costs, or null for something that is as it should be. */
@Composable
internal fun itemBody(item: ReliabilityItem): String? {
    if (item.severity == Severity.OK) return null
    return stringResource(
        when (item.precondition) {
            Precondition.EXACT_ALARMS -> R.string.reliability_exact_body
            Precondition.NOTIFICATIONS -> R.string.reliability_notifications_body
            Precondition.FULL_SCREEN_INTENT -> R.string.reliability_fullscreen_body
            Precondition.ALARM_CHANNEL -> R.string.reliability_channel_body
            Precondition.ALARM_VOLUME -> R.string.reliability_volume_body
            Precondition.BATTERY_OPTIMIZATION -> R.string.reliability_battery_body
            Precondition.BACKGROUND_RESTRICTED -> R.string.reliability_background_body
            Precondition.STANDBY_BUCKET -> R.string.reliability_standby_body
        },
    )
}

/** The label of the button that fixes it, or null when there is nothing the person can do. */
@Composable
internal fun itemAction(item: ReliabilityItem, canAskNotifications: Boolean): String? {
    val fix = item.fix ?: return null
    return stringResource(
        when (fix) {
            Fix.ASK_NOTIFICATIONS -> if (canAskNotifications) R.string.reliability_notifications_action_ask else R.string.reliability_notifications_action_settings
            Fix.OPEN_EXACT_ALARM_SETTINGS -> R.string.reliability_exact_action
            Fix.OPEN_FULL_SCREEN_SETTINGS -> R.string.reliability_fullscreen_action
            Fix.OPEN_ALARM_CHANNEL_SETTINGS -> R.string.reliability_channel_action
            Fix.OPEN_SOUND_SETTINGS -> R.string.reliability_volume_action
            Fix.OPEN_BATTERY_SETTINGS -> R.string.reliability_battery_action
            Fix.OPEN_APP_SETTINGS -> R.string.reliability_background_action
        },
    )
}
