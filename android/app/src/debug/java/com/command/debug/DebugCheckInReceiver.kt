package com.command.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.command.data.OccurrenceRepository
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.alarm.AlarmScheduler
import com.command.domain.model.EventSource
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.time.Clock
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug builds only (this file is in the debug source set): moves the follow-up questions of an occurrence along from a shell, so that
 * the loop can be walked through on the emulator without waiting for ten-minute gaps.
 *
 *   adb shell am broadcast -a com.command.debug.CHECKIN_DUE -p com.aistudio.command.vtxrpl --es id <occurrence>
 *       asks the next question now, as if its alarm had just fired (--ei index N to name the question)
 *   adb shell am broadcast -a com.command.debug.CHECKIN_ARM -p com.aistudio.command.vtxrpl --es id <occurrence> --ei seconds 30
 *       arms the real follow-up alarm for that many seconds from now, which is how its lateness under Doze is measured
 */
class DebugCheckInReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Graph {
        fun repository(): OccurrenceRepository
        fun clock(): Clock
        fun scheduler(): AlarmScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, Graph::class.java)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val occurrence = graph.repository().get(id) ?: return@launch
                val index = intent.getIntExtra("index", askedSoFar(occurrence.sessionJson))
                when (intent.action) {
                    DUE -> graph.repository().apply(id, OccurrenceCommand.CheckInDue(index), EventSource.RECEIVER)
                    ARM -> {
                        val seconds = intent.getIntExtra("seconds", 30).coerceIn(5, 3600)
                        val at = graph.clock().nowMs() + seconds * 1000L
                        graph.scheduler().arm(AlarmRequest(id, occurrence.requestCode, AlarmKind.CHECK_IN, at, index))
                    }
                }
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val DUE = "com.command.debug.CHECKIN_DUE"
        const val ARM = "com.command.debug.CHECKIN_ARM"

        /**
         * The questions asked in the occurrence's current session, which is the index the next one has. A reset begins a new session while
         * the check_ins table keeps every row ever written, so counting rows would name a question the state machine refuses.
         */
        fun askedSoFar(sessionJson: String): Int =
            Regex("\"checkInsAsked\"\\s*:\\s*(\\d+)").find(sessionJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
