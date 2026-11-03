package com.command.alarm

import com.command.data.PreconditionRecorder
import com.command.domain.reliability.PreconditionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the platform what an alarm depends on and writes down what changed since the last time anyone asked. The schedule, the
 * reliability page and the first-run permissions screen call it when they come to the front and after a permission request
 * returns, and the reconcile calls it before it puts alarms back. An interface, so a screen's view model can be tested with a
 * phone in any state.
 */
fun interface ReliabilityMonitor {
    suspend fun refresh(): PreconditionSnapshot
}

/** The real thing: reads Android through [PreconditionProbe] and records the difference with [PreconditionRecorder]. */
@Singleton
class PlatformReliabilityMonitor @Inject constructor(
    private val probe: PreconditionProbe,
    private val recorder: PreconditionRecorder,
) : ReliabilityMonitor {
    // The probe makes a handful of calls to system services; none of them belongs on the main thread, which is where a screen asks.
    override suspend fun refresh(): PreconditionSnapshot = withContext(Dispatchers.Default) { probe.read() }.also { recorder.record(it) }
}
