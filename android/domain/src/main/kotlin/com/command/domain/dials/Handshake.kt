package com.command.domain.dials

/** The seven acknowledgement challenges from the spec (section 4). */
enum class HandshakeMode {
    HOLD, CONFIRM_SEQUENCE, TYPING, QR, NFC, VOICE, TWO_STEP;

    /** The deterministic fallback used when this mode is unavailable or exhausted. */
    val fallback: HandshakeMode
        get() = when (this) {
            HOLD -> HOLD // the accessible alternate is the same mode with an assistive confirm
            CONFIRM_SEQUENCE -> TYPING
            TYPING -> CONFIRM_SEQUENCE
            QR, NFC, VOICE -> TYPING
            TWO_STEP -> HOLD
        }

    /**
     * Whether this challenge is built: its rules are in :domain and the app has a screen that asks for it. Hold, the confirmation sequence,
     * typing, two-step and QR are; NFC and voice are not yet (each needs a tag reader or a microphone, and its own consent, spec section 4).
     * A chain that names one that is not built is run with the mode [offered] in its place, and the record says so. This is a static rule:
     * it never depends on the phone, so what is saved reads the same on every phone.
     */
    val built: Boolean
        get() = when (this) {
            HOLD, CONFIRM_SEQUENCE, TYPING, TWO_STEP, QR -> true
            NFC, VOICE -> false
        }

    /**
     * Whether this challenge needs something of the phone that it may not have right now (a camera, a consent, a permission, a registered
     * code). A ring asks for such a mode only when [com.command.domain.handshake.HandshakeCapability] says the phone can do it; otherwise the
     * step runs its fallback and a row says so. QR, for now (NFC and voice join it when they are built).
     */
    val needsDevice: Boolean
        get() = this == QR

    /**
     * Whether this challenge is not yet signed off on hardware: built or not, it has not been checked on a phone. The screens use it for the
     * label; it changes nothing the engine does. QR, NFC and voice.
     */
    val planned: Boolean
        get() = when (this) {
            QR, NFC, VOICE -> true
            HOLD, CONFIRM_SEQUENCE, TYPING, TWO_STEP -> false
        }

    /** The mode that is really asked for in place of this one: itself when it is [built], otherwise its fallback, and so on until one is. */
    val offered: HandshakeMode
        get() {
            var mode = this
            var hops = 0
            while (!mode.built && hops++ < values().size) mode = mode.fallback
            return mode
        }
}

enum class ChainOp { AND, OR, SEQUENCE }

data class StepSpec(
    val mode: HandshakeMode,
    val maxRetries: Int,
    val timeoutSec: Int,
    val holdSeconds: Int = 0,
)

data class ChainSpec(val op: ChainOp, val steps: List<StepSpec>) {
    init { require(steps.isNotEmpty()) }
}
