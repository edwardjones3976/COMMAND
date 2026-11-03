package com.command.alarm

import android.content.BroadcastReceiver
import android.content.Context
import com.command.data.OccurrenceRepository
import com.command.domain.alarm.RingController
import dagger.Module
import dagger.Binds
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Lets plain broadcast receivers reach the object graph without being Hilt receivers themselves. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlarmEntryPoint {
    fun repository(): OccurrenceRepository
    fun resolver(): RingModeResolver
    fun arming(): ArmingReconciler
    fun ringController(): RingController
    fun scheduler(): com.command.domain.alarm.AlarmScheduler

    companion object {
        fun from(context: Context): AlarmEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, AlarmEntryPoint::class.java)
    }
}

/** Runs [block] off the main thread while keeping the receiver alive until it finishes. */
internal fun BroadcastReceiver.runAsync(context: Context, block: suspend (AlarmEntryPoint) -> Unit) {
    val pending = goAsync() // null when a test calls onReceive directly
    val entry = AlarmEntryPoint.from(context)
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            block(entry)
        } finally {
            pending?.finish()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AlarmBindings {
    @Binds abstract fun scheduler(impl: AndroidAlarmScheduler): com.command.domain.alarm.AlarmScheduler
    @Binds abstract fun ringController(impl: AndroidRingController): RingController
    @Binds abstract fun ringOutput(impl: AndroidRingOutput): RingOutput
    @Binds abstract fun reliabilityMonitor(impl: PlatformReliabilityMonitor): ReliabilityMonitor
}

/**
 * Ports whose real implementation arrives in a later milestone. They do nothing, on purpose and visibly: the state
 * machine's effects for them are simply not carried out yet (work enqueueing and window top-up in M5, follow-up
 * notifications in M7).
 */
@Module
@InstallIn(SingletonComponent::class)
object PendingPorts {
    @Provides fun work(): com.command.domain.alarm.WorkEnqueuer = object : com.command.domain.alarm.WorkEnqueuer {
        override fun enqueue(kind: com.command.domain.occurrence.WorkKind, occurrenceId: String) = Unit
    }

    @Provides fun roller(): com.command.domain.alarm.WindowRoller = com.command.domain.alarm.WindowRoller { }

    @Provides fun poster(): com.command.domain.alarm.CheckInPoster = object : com.command.domain.alarm.CheckInPoster {
        override fun post(occurrenceId: String, requestCode: Int, index: Int) = Unit
    }
}
