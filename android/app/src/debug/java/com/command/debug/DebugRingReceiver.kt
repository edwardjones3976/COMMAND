package com.command.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.command.alarm.AlarmEntryPoint
import com.command.data.ConsentStore
import com.command.data.OccurrenceRepository
import com.command.data.db.MissionRow
import com.command.domain.dials.Flexibility
import com.command.domain.model.ConsentKey
import com.command.domain.model.EventSource
import com.command.domain.model.MissionSource
import com.command.domain.model.StartKind
import com.command.domain.model.ZoneMode
import com.command.domain.occurrence.ArmMode
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
import java.util.UUID

/**
 * Debug builds only (this file is in the debug source set, so a release build does not contain it): creates a mission and
 * arms its occurrence a few seconds from now, so a ring can be started from a shell on the emulator.
 *
 *   adb shell am broadcast -a com.command.debug.RING -p com.aistudio.command.vtxrpl --ei seconds 20 --es title "Stand up"
 *   optional: --es profile builtin_reveille_max   --ez rigid true
 *   optional: --ez volume true   gives the volume consent first, as a person would on the consent screen (the volume restore case of
 *             scripts/reliability.sh needs it); without it the consent is left as it is
 */
class DebugRingReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Graph {
        fun repository(): OccurrenceRepository
        fun clock(): Clock
        fun database(): com.command.data.db.CommandDb
        fun consents(): ConsentStore
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val seconds = intent.getIntExtra("seconds", 15).coerceIn(5, 3600)
        val title = intent.getStringExtra("title") ?: "Debug ring"
        val profile = intent.getStringExtra("profile")
        val rigid = intent.getBooleanExtra("rigid", false)
        val volume = intent.getBooleanExtra("volume", false)
        val pending = goAsync()
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, Graph::class.java)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (volume) graph.consents().grant(ConsentKey.VOLUME_CONTROL)
                val clock = graph.clock()
                val at = clock.nowMs() + seconds * 1000L
                val mission = MissionRow(
                    id = UUID.randomUUID().toString(), title = title, firstAction = "Open the first thing and give it five minutes",
                    category = "Debug", priority = 2, flexibility = if (rigid) Flexibility.RIGID else Flexibility.FLEXIBLE, rigidWeekdays = null,
                    source = MissionSource.MANUAL, startKind = StartKind.ONE_OFF, startAtUtc = at, dtstartLocal = null, rrule = null,
                    zoneMode = ZoneMode.DEVICE, zoneId = null, durationMin = 30, starterMinutes = 5, profileId = profile, lockPolicy = null,
                    launcherPackage = null, firstActionVariantsJson = "[]", enabled = true, deletedAt = null, syncRev = 0, syncDirty = false,
                )
                graph.database().missions().insert(mission)
                val occurrence = graph.repository().createOccurrence(mission.id, at, clock.zone().id).occurrence
                val entry = AlarmEntryPoint.from(context)
                val mode = if (entry.scheduler().canScheduleExact()) ArmMode.EXACT else ArmMode.INEXACT
                graph.repository().apply(occurrence.id, OccurrenceCommand.Arm(mode), EventSource.SYSTEM)
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.command.debug.RING"
    }
}
