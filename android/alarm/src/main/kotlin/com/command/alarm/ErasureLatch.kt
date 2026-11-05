package com.command.alarm

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Set when an erase begins. From then on nothing arms an alarm and no ring starts (and the app's start-up, the queued reconcile and the
 * fallback ring stand aside too), so that a reconcile, a check-in or an app start that was already running cannot put back what the erase
 * has just taken, and an alarm that fires while it runs stays quiet. Once the erase has deleted the database it stays closed for the rest
 * of the process's life: the app is ended afterwards, and what the latch guards is gone. An erase that fails before it has deleted anything
 * opens it again ([unseal]), because then the app goes on as it was and must go on ringing.
 */
@Singleton
open class ErasureLatch @Inject constructor() {
    @Volatile private var closed = false

    /** Open so that a test can stand for the erase closing the latch between two looks at it. */
    open val sealed: Boolean get() = closed

    fun seal() {
        closed = true
    }

    /** Only for an erase that failed while the database was still there; the next reconcile sets back whatever it had taken back. */
    fun unseal() {
        closed = false
    }
}
