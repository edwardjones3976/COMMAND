package com.command.domain.alarm

/**
 * What the platform does around a global pause: a reconcile is queued for the moment it ends, and a notice stays in the shade while it
 * lasts, with a way to end it early. Everything here is harmless to repeat and none of it touches the database.
 */
interface PauseEffects {
    /** A pause began, or its end moved: nothing that is due before [untilUtcMs] rings. */
    fun started(untilUtcMs: Long)

    /** The pause ended on its own. The run queued for its end is either the one reporting this or about to find nothing to do. */
    fun expired()

    /** The person ended the pause early, so the run queued for its end is not needed any more. */
    fun resumed()
}
