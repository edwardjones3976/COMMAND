package com.command.reveille

import com.command.domain.handshake.TryId

/*
 * The view model's events are about a ring: the screens hand back the ring they were drawn for, and an event from the screen of another ring
 * does nothing. A test of the view model alone speaks of "the ring on screen", as these do; the tests of the ring check call the real
 * signatures, with the ring they mean.
 */

private fun ReveilleViewModel.ring(): String = checkNotNull(ringOnScreen) { "no ring is on screen" }

internal fun ReveilleViewModel.holdStarted(at: TryId, startedAtMs: Long = 0L) = holdStarted(ring(), at, startedAtMs)

internal fun ReveilleViewModel.holdCompleted(at: TryId, startedAtMs: Long, endedAtMs: Long) = holdCompleted(ring(), at, startedAtMs, endedAtMs)

internal fun ReveilleViewModel.holdReleasedEarly(at: TryId, startedAtMs: Long, endedAtMs: Long) = holdReleasedEarly(ring(), at, startedAtMs, endedAtMs)

internal fun ReveilleViewModel.assistiveBegun(at: TryId) = assistiveBegun(ring(), at)

internal fun ReveilleViewModel.assistiveConfirmed(at: TryId) = assistiveConfirmed(ring(), at)

internal fun ReveilleViewModel.typingChanged(at: TryId, text: String) = typingChanged(ring(), at, text)

internal fun ReveilleViewModel.typingSubmitted(at: TryId, text: String) = typingSubmitted(ring(), at, text)

internal fun ReveilleViewModel.sequenceTapped(at: TryId, number: Int) = sequenceTapped(ring(), at, number)

internal fun ReveilleViewModel.twoStepIndicated(at: TryId) = twoStepIndicated(ring(), at)

internal fun ReveilleViewModel.switchTo(position: Int) = switchTo(ring(), position)
