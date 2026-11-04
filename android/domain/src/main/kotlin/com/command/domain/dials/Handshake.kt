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
     * Whether a person can be asked for this challenge today. Hold, the confirmation sequence, typing and two-step are built and tested; QR,
     * NFC and voice are planned (each needs a camera, a tag reader or a microphone, and its own consent) and stay marked as planned until
     * their tests pass (spec section 4). A chain that names one is run with the mode [offered] in its place, and the record says so.
     */
    val available: Boolean
        get() = when (this) {
            HOLD, CONFIRM_SEQUENCE, TYPING, TWO_STEP -> true
            QR, NFC, VOICE -> false
        }

    /** The mode that is really asked for in place of this one: itself when it is [available], otherwise its fallback, and so on until one is. */
    val offered: HandshakeMode
        get() {
            var mode = this
            var hops = 0
            while (!mode.available && hops++ < values().size) mode = mode.fallback
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
