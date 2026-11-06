package com.command

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.command.alarm.AlarmContract
import com.command.reveille.ReveilleActions
import com.command.reveille.ReveilleContent
import com.command.reveille.ReveilleUiState
import com.command.reveille.ReveilleViewModel
import com.command.ui.theme.CommandTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The lock-screen face of a ring: acknowledge, hear the first step, say you are starting. It sits over the keyguard and
 * turns the screen on only for this flow; the rest of the app (including check-ins) never appears over the lock screen. It
 * finishes when the flow is over and never offers a way to leave that skips the record: while it rings, Back opens the way out
 * (silence, put it off, skip, each recorded as what it is), and Safety silence is always one tap away.
 */
@AndroidEntryPoint
class ReveilleActivity : ComponentActivity() {

    private val viewModel: ReveilleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The window is drawn behind the system bars on every version (Android 15 does it for an app that targets it), so that the insets are
        // the same everywhere and the screens give way to them: Safety silence, at the bottom, is never under a bar or behind the keyboard.
        enableEdgeToEdge()
        showOverLockScreen()
        viewModel.offerAccessibleConfirm = needsAnotherWayThanHolding()
        handle(intent)

        setContent {
            CommandTheme {
                val state by viewModel.state.collectAsState()
                val friction by viewModel.friction.collectAsState()
                ReveilleContent(
                    state = state,
                    friction = friction,
                    reasons = viewModel.reasons,
                    actions = ringActions(viewModel, (state as? ReveilleUiState.Firing)?.occurrenceId) { finishAndRemoveTask() },
                    onFrictionConfirm = viewModel::confirmFriction,
                    onFrictionDismiss = viewModel::dismissFriction,
                )
            }
        }
    }

    /**
     * Holding a finger down is not possible for everyone: a screen reader clicks, it does not press and hold, and a hardware keyboard has no
     * finger at all. For either the three deliberate taps are on the screen from the start. (Not for every accessibility service: many are
     * not about disability at all, and would soften the hold for everyone. Switch Access and Voice Access, which also click, find the same
     * way through the hold target's own action.)
     */
    private fun needsAnotherWayThanHolding(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        val keyboard = resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
        return manager?.isTouchExplorationEnabled == true || keyboard
    }

    /** A screen reader turned on (or off) while the alarm rings: the three taps appear (or go) at once, and not at the next ring. */
    private val screenReader = AccessibilityManager.TouchExplorationStateChangeListener {
        viewModel.offerAccessibleConfirm = needsAnotherWayThanHolding()
    }

    override fun onStart() {
        super.onStart()
        getSystemService(AccessibilityManager::class.java)?.addTouchExplorationStateChangeListener(screenReader)
        viewModel.offerAccessibleConfirm = needsAnotherWayThanHolding() // it may have changed while the activity was stopped
    }

    override fun onStop() {
        getSystemService(AccessibilityManager::class.java)?.removeTouchExplorationStateChangeListener(screenReader)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        AlarmContract.occurrenceId(intent)?.let(viewModel::show)
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/**
 * What the ring screen can ask of the view model, with everything that is about the handshake bound to [ring], the ring the screen is drawn
 * for. The screen of a ring that was replaced by another's may still deliver a callback; it carries its own ring, which is not the ring on
 * screen now, and the view model does nothing with it (the numbering of tries begins at 1 on every ring, so the try alone cannot tell). With
 * no ring on screen ([ring] null) a handshake callback does nothing.
 */
internal fun ringActions(viewModel: ReveilleViewModel, ring: String?, onClose: () -> Unit) = ReveilleActions(
    onHoldStarted = { at, startedAt -> ring?.let { viewModel.holdStarted(it, at, startedAt) } },
    onHoldCompleted = { at, startedAt, endedAt -> ring?.let { viewModel.holdCompleted(it, at, startedAt, endedAt) } },
    onHoldReleasedEarly = { at, startedAt, endedAt -> ring?.let { viewModel.holdReleasedEarly(it, at, startedAt, endedAt) } },
    onAssistiveBegun = { at -> ring?.let { viewModel.assistiveBegun(it, at) } },
    onAssistiveConfirmed = { at -> ring?.let { viewModel.assistiveConfirmed(it, at) } },
    onTypingChanged = { at, text -> ring?.let { viewModel.typingChanged(it, at, text) } },
    onTypingSubmitted = { at, text -> ring?.let { viewModel.typingSubmitted(it, at, text) } },
    onSequenceTapped = { at, number -> ring?.let { viewModel.sequenceTapped(it, at, number) } },
    onTwoStepIndicated = { at -> ring?.let { viewModel.twoStepIndicated(it, at) } },
    onSwitch = { position -> ring?.let { viewModel.switchTo(it, position) } },
    onSilence = viewModel::silence,
    onResume = viewModel::resume,
    onStarted = viewModel::started,
    onBeginStarter = viewModel::beginStarter,
    onDelay = viewModel::delay,
    onSkip = viewModel::skip,
    onClose = onClose,
)
