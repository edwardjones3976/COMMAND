package com.command.domain.model

/**
 * Withdrawing a consent also withdraws everything that depended on it, so a narrower grant can never outlive the
 * wider one it was a refinement of.
 */
object ConsentCascade {
    fun dependents(key: ConsentKey): List<ConsentKey> = when (key) {
        ConsentKey.GEMINI_TEXT -> listOf(ConsentKey.GEMINI_TEXT_TITLES)
        ConsentKey.GEMINI_VOICE -> listOf(ConsentKey.GEMINI_VOICE_TITLES)
        ConsentKey.MIC_VOICE -> listOf(ConsentKey.MIC_VOICE_NETWORK)
        ConsentKey.CALENDAR_READ -> listOf(ConsentKey.CALENDAR_EVENT_TEXT)
        else -> emptyList()
    }

    /** The key and everything that depends on it, the key first. */
    fun withDependents(key: ConsentKey): List<ConsentKey> = listOf(key) + dependents(key)
}
