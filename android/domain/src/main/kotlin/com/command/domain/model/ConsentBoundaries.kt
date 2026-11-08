package com.command.domain.model

/** Who can change a consent: Android itself (a permission granted in its own screens) or this app (a switch it owns). */
enum class ConsentOwner { PLATFORM, APP }

/** One consent the consent center lists. What it allows is said in words in the app module's string resources. */
data class ConsentBoundary(val key: ConsentKey, val owner: ConsentOwner)

object ConsentBoundaries {
    /**
     * The consents that exist in this version, in the order they are listed. A feature that needs a consent adds its own when it
     * arrives, so the list never promises anything that is not there.
     */
    val all: List<ConsentBoundary> = listOf(
        ConsentBoundary(ConsentKey.NOTIFICATIONS, ConsentOwner.PLATFORM),
        ConsentBoundary(ConsentKey.EXACT_ALARM, ConsentOwner.PLATFORM),
        ConsentBoundary(ConsentKey.FULL_SCREEN, ConsentOwner.PLATFORM),
        ConsentBoundary(ConsentKey.VOLUME_CONTROL, ConsentOwner.APP),
        ConsentBoundary(ConsentKey.CALENDAR_READ, ConsentOwner.APP),
        // Withdrawing it withdraws nothing else and keeps the registered codes: they are hashes only, the person can delete them, and
        // deleting everything removes them.
        ConsentBoundary(ConsentKey.CAMERA_QR, ConsentOwner.APP),
    )
}
