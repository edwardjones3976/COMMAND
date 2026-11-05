package com.command

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.command.alarm.AlarmContract
import com.command.reveille.ReveilleActions
import com.command.reveille.ReveilleContent
import com.command.reveille.ReveilleViewModel
import com.command.ui.theme.CommandTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The lock-screen face of a ring: acknowledge, hear the first step, say you are starting. It sits over the keyguard and
 * turns the screen on only for this flow; the rest of the app (including check-ins) never appears over the lock screen. It
 * finishes when the flow is over and never offers a way to leave that skips the record: Back does nothing while ringing,
 * and Safety silence is always on screen.
 */
@AndroidEntryPoint
class ReveilleActivity : ComponentActivity() {

    private val viewModel: ReveilleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val touchExploration = getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        viewModel.accessibilityTouchExploration = touchExploration
        handle(intent)

        setContent {
            CommandTheme {
                val state by viewModel.state.collectAsState()
                val friction by viewModel.friction.collectAsState()
                ReveilleContent(
                    state = state,
                    friction = friction,
                    reasons = viewModel.reasons,
                    actions = ReveilleActions(
                        onHoldStarted = viewModel::holdStarted,
                        onHoldCompleted = viewModel::holdCompleted,
                        onHoldReleasedEarly = viewModel::holdReleasedEarly,
                        onAccessibleConfirm = viewModel::accessibleConfirm,
                        onSilence = viewModel::silence,
                        onResume = viewModel::resume,
                        onStarted = viewModel::started,
                        onDelay = viewModel::delay,
                        onSkip = viewModel::skip,
                        onClose = { finishAndRemoveTask() },
                    ),
                    onFrictionConfirm = viewModel::confirmFriction,
                    onFrictionDismiss = viewModel::dismissFriction,
                )
            }
        }
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
