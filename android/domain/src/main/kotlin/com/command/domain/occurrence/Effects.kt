package com.command.domain.occurrence

/**
 * Side effects a transition asks for. The domain never performs them; the repository runs them, in the same
 * transaction as the state change, through the AlarmScheduler and WorkEnqueuer interfaces.
 */
sealed interface Effect {
    data class ArmAlarm(val mode: ArmMode) : Effect
    data object DisarmAlarm : Effect

    /** Stop the sound and vibration now. Emitted on every way out of FIRING, so silence never waits on a database poll. */
    data object StopRinging : Effect

    /** A backstop alarm that ends the ring after [totalMs] even if the process is dead. */
    data class ArmCycleEnd(val totalMs: Long) : Effect
    data object CancelCycleEnd : Effect

    /** Ask the user a status question at [atUtcMs]; [index] counts from 0. index == count is the final timeout. */
    data class ScheduleCheckIn(val index: Int, val atUtcMs: Long) : Effect
    data class PostCheckIn(val index: Int) : Effect
    data object CancelCheckIns : Effect

    /** Create a child occurrence at [atUtcMs] (a delay) and arm it. */
    data class ArmChild(val kind: ChildKind, val atUtcMs: Long) : Effect

    /** Top up the rolling window of armed alarms. */
    data object RollWindow : Effect
    data class EnqueueWork(val kind: WorkKind) : Effect
}
