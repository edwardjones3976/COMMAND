package com.command.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.command.data.MissionRepository
import com.command.data.OccurrenceRepository
import com.command.domain.alarm.CheckInPoster
import com.command.domain.alarm.PauseEffects
import com.command.domain.alarm.RingController
import com.command.domain.time.Clock
import com.command.domain.voice.Speaker
import com.command.voice.AndroidSpeechSynthesizer
import com.command.voice.AudioTrackPlayer
import com.command.voice.LocalTtsSpeaker
import dagger.Module
import dagger.Binds
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Singleton

/** Lets plain broadcast receivers reach the object graph without being Hilt receivers themselves. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlarmEntryPoint {
    fun repository(): OccurrenceRepository
    fun missions(): MissionRepository
    fun resolver(): RingModeResolver
    fun arming(): ArmingReconciler
    fun ringController(): RingController
    fun scheduler(): com.command.domain.alarm.AlarmScheduler
    fun checkInPoster(): CheckInPoster
    fun clock(): Clock
    fun latch(): ErasureLatch

    companion object {
        fun from(context: Context): AlarmEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, AlarmEntryPoint::class.java)
    }
}

private const val RECEIVER_TAG = "AlarmReceivers"

/**
 * Runs [block] off the main thread while keeping the receiver alive until it finishes. Nothing that goes wrong in it may take the
 * process down: the same process may be ringing another alarm, and a receiver is not worth that. A failure is logged; the next
 * reconcile, or the next alarm, puts right what it can.
 */
internal fun BroadcastReceiver.runAsync(context: Context, block: suspend (AlarmEntryPoint) -> Unit) {
    val pending = goAsync() // null when a test calls onReceive directly
    val entry = AlarmEntryPoint.from(context)
    val failed = CoroutineExceptionHandler { _, e -> Log.w(RECEIVER_TAG, "a broadcast could not be handled: ${e.javaClass.simpleName}", e) }
    CoroutineScope(SupervisorJob() + Dispatchers.Default + failed).launch {
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
    @Binds abstract fun audioRoutes(impl: AndroidAudioRoutes): AudioRoutes
    @Binds abstract fun checkInPoster(impl: AndroidCheckInPoster): CheckInPoster
    @Binds abstract fun pauseEffects(impl: AndroidPauseEffects): PauseEffects
}

/**
 * The phone's own voice for the ring. Nothing is started until a ring begins: the service starts the engine alongside the ring and lets
 * it go when the ring is over. The player is given the audio manager so that a line meant for a headset only can be pinned to one.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceProviders {
    @Provides
    @Singleton
    fun speaker(@ApplicationContext context: Context): Speaker =
        LocalTtsSpeaker(AndroidSpeechSynthesizer(context), AudioTrackPlayer(context.getSystemService(AudioManager::class.java)))
}

/**
 * Ports whose real implementation arrives in a later milestone. They do nothing, on purpose and visibly: the state
 * machine's effects for them are simply not carried out yet (work enqueueing and window top-up).
 */
@Module
@InstallIn(SingletonComponent::class)
object PendingPorts {
    @Provides fun work(): com.command.domain.alarm.WorkEnqueuer = object : com.command.domain.alarm.WorkEnqueuer {
        override fun enqueue(kind: com.command.domain.occurrence.WorkKind, occurrenceId: String) = Unit
    }

    @Provides fun roller(): com.command.domain.alarm.WindowRoller = com.command.domain.alarm.WindowRoller { }
}
