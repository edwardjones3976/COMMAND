package com.command.profiles

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.command.domain.dials.Dial
import com.command.domain.dials.DialEffects
import com.command.domain.dials.Flexibility
import com.command.domain.dials.ImperativeTier
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.RecoveryOption
import com.command.handshake.chainWords
import com.command.R

/** The name of a dial. */
@Composable
internal fun dialName(dial: Dial): String = stringResource(
    when (dial) {
        Dial.FIRMNESS -> R.string.dial_firmness
        Dial.OBNOXIOUSNESS -> R.string.dial_obnoxiousness
        Dial.ALERT_INTENSITY -> R.string.dial_alert
        Dial.FOLLOW_UP_PERSISTENCE -> R.string.dial_persistence
        Dial.HANDSHAKE_DIFFICULTY -> R.string.dial_difficulty
        Dial.SCHEDULE_RIGIDITY -> R.string.dial_rigidity
        Dial.RECOVERY_STRUCTURE -> R.string.dial_recovery
    },
)

/**
 * What a setting of a dial does, in plain words. Every sentence is built from what [DialEffects] says for that value, the same answer the ring,
 * the follow-up questions and the recovery menu act on, so the words cannot say anything else.
 */
@Composable
internal fun dialEffectWords(dial: Dial, value: Int): String = when (dial) {
    Dial.FIRMNESS -> firmnessWords(value)
    Dial.OBNOXIOUSNESS -> obnoxiousnessWords(value)
    Dial.ALERT_INTENSITY -> alertWords(value)
    Dial.FOLLOW_UP_PERSISTENCE -> persistenceWords(value)
    Dial.HANDSHAKE_DIFFICULTY -> difficultyWords(value)
    Dial.SCHEDULE_RIGIDITY -> rigidityWords(value)
    Dial.RECOVERY_STRUCTURE -> recoveryWords(value)
}

@Composable
private fun firmnessWords(value: Int): String = stringResource(
    when (DialEffects.imperativeTier(value)) {
        ImperativeTier.SUGGEST -> R.string.dial_firmness_suggest
        ImperativeTier.INVITE -> R.string.dial_firmness_invite
        ImperativeTier.DIRECT -> R.string.dial_firmness_direct
        ImperativeTier.FIRM -> R.string.dial_firmness_firm
        ImperativeTier.COMMANDING -> R.string.dial_firmness_commanding
    },
)

@Composable
private fun obnoxiousnessWords(value: Int): String {
    val noise = DialEffects.interjections(value)
    if (noise.density == 0) return stringResource(R.string.dial_obnoxiousness_none)
    val density = pluralStringResource(R.plurals.dial_obnoxiousness_density, noise.density, noise.density)
    return if (noise.maxRepeats == 0) density else density + " " + pluralStringResource(R.plurals.dial_obnoxiousness_repeats, noise.maxRepeats, noise.maxRepeats)
}

@Composable
private fun alertWords(value: Int): String {
    val plan = DialEffects.alertPlan(value)
    val ring = duration(plan.cycleMs)
    val rings = if (plan.cycles == 1) {
        stringResource(R.string.dial_alert_once, ring)
    } else {
        stringResource(R.string.dial_alert_times, plan.cycles, ring, duration(plan.gapMs))
    }
    val voice = when (DialEffects.loudnessTargetLufs(value)) {
        -14 -> stringResource(R.string.dial_alert_voice_loudest)
        -15 -> stringResource(R.string.dial_alert_voice_louder)
        else -> stringResource(R.string.dial_alert_voice_loud)
    }
    return "$rings $voice"
}

@Composable
private fun persistenceWords(value: Int): String {
    val schedule = DialEffects.checkIns(value)
    val gap = duration(schedule.gapsMs.first())
    return if (schedule.count == 1) stringResource(R.string.dial_persistence_once, gap) else stringResource(R.string.dial_persistence_times, schedule.count, gap)
}

@Composable
private fun difficultyWords(value: Int): String {
    val tries = DialEffects.maxRetries(value)
    return chainWords(DialEffects.defaultChain(value)) + ". " + pluralStringResource(R.plurals.dial_difficulty_tries, tries, tries)
}

@Composable
private fun rigidityWords(value: Int): String = stringResource(
    when (DialEffects.lockPolicy(Flexibility.RIGID, override = null, scheduleRigidity = value)) {
        LockPolicy.NONE -> R.string.dial_rigidity_none
        LockPolicy.CONFIRM -> R.string.dial_rigidity_confirm
        LockPolicy.REASON -> R.string.dial_rigidity_reason
        LockPolicy.RECOVERY_OFFER -> R.string.dial_rigidity_recovery
    },
)

@Composable
private fun recoveryWords(value: Int): String {
    val policy = DialEffects.recoveryPolicy(value)
    val options = policy.menu.map { option ->
        when (option) {
            RecoveryOption.RESET -> stringResource(R.string.dial_recovery_reset)
            RecoveryOption.SMALLER -> stringResource(R.string.dial_recovery_smaller)
            RecoveryOption.RESCHEDULE -> {
                val later = policy.rescheduleMinutes.joinToString(stringResource(R.string.handshake_list_separator))
                stringResource(if (policy.offerTomorrow) R.string.dial_recovery_later_or_tomorrow else R.string.dial_recovery_later, later)
            }
        }
    }
    return pluralStringResource(R.plurals.dial_recovery_offers, policy.maxOffers, policy.maxOffers, options.joinToString(stringResource(R.string.handshake_list_separator)))
}

/** A length of time as the plans have them: whole minutes, or else seconds. */
@Composable
private fun duration(ms: Long): String {
    val seconds = (ms / 1_000).toInt()
    return if (seconds % 60 == 0) pluralStringResource(R.plurals.dial_minutes, seconds / 60, seconds / 60) else pluralStringResource(R.plurals.dial_seconds, seconds, seconds)
}
