package com.command

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.command.nav.CommandNav
import com.command.startup.AppStartup
import com.command.ui.theme.CommandTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app proper: the schedule and the mission editor. It never appears over the lock screen; that is the job of
 * [ReveilleActivity], which only the alarm brings up.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var startup: AppStartup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Each time the app comes to the front: make sure the coming week is scheduled and every alarm is really set.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { startup.run() } }
        setContent { CommandTheme { CommandNav() } }
    }
}
