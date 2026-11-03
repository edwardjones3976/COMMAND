package com.command

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.command.alarm.AlarmContract
import com.command.nav.CommandNav
import com.command.startup.AppStartup
import com.command.ui.theme.CommandTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app proper: the schedule and the mission editor. It never appears over the lock screen; that is the job of
 * [ReveilleActivity], which only the alarm brings up.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var startup: AppStartup

    /** The occurrence a check-in notification asked to open, until the navigation has taken it. */
    private val openOccurrence = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Each time the app comes to the front: make sure the coming week is scheduled and every alarm is really set.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { startup.run() } }
        // Only when the activity is made, not when it is made again after a rotation or the process dying: those restore where it was.
        if (savedInstanceState == null) handle(intent)
        setContent {
            CommandTheme {
                val pending by openOccurrence.collectAsState()
                CommandNav(openOccurrence = pending, onOpenedOccurrence = { openOccurrence.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == AlarmContract.ACTION_OPEN_CHECK_IN) {
            AlarmContract.occurrenceId(intent)?.takeIf(AlarmContract::isOccurrenceId)?.let { openOccurrence.value = it }
        }
    }
}
