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
