package com.command.domain.handshake

/**
 * The confirmation sequence: four targets labelled 1 to 4, in a jumbled order on the screen, to be tapped in the order of their numbers.
 * The numbers need no language and are read out by a screen reader as they are. It takes a moment of attention that a hold does not, and
 * nothing a person with one finger and no clear head cannot do.
 */
object ConfirmSequence {
    const val TARGETS = 4

    /** What a tap on the target numbered `pressed` does when [tapped] targets have been tapped rightly so far. */
    enum class Press { RIGHT, DONE, WRONG }

    private val IN_ORDER = (1..TARGETS).toList()

    /** Every arrangement of the numbers except the one in order, which would make the challenge a tap along the row. */
    private val ARRANGEMENTS: List<List<Int>> by lazy { arrangements(IN_ORDER).filter { it != IN_ORDER } }

    /**
     * Where the numbers stand, left to right and top to bottom, for [seed] and the try number [attempt]. The same for the same alarm and try
     * (turning the phone does not shuffle it), another after a wrong tap (the answer cannot be learnt by position: each try moves at least
     * three of the four numbers to other cells, which a step of 7 through the 23 arrangements does and a step of 1 does not), never in order.
     */
    fun layout(seed: String, attempt: Int = 0): List<Int> =
        ARRANGEMENTS[Math.floorMod(seed.hashCode() * 31 + attempt * TRY_STEP, ARRANGEMENTS.size)]

    /** From one try to the next the layout moves this far along the list of arrangements; it is prime to its length, so no arrangement repeats before all have been used. */
    private const val TRY_STEP = 7

    /** Once all four are tapped there is nothing left to tap, and a number that is not on the screen is no tap at all. */
    fun press(tapped: Int, pressed: Int): Press = when {
        tapped !in 0 until TARGETS || pressed != tapped + 1 -> Press.WRONG
        pressed == TARGETS -> Press.DONE
        else -> Press.RIGHT
    }

    private fun arrangements(items: List<Int>): List<List<Int>> =
        if (items.size <= 1) listOf(items) else items.flatMap { head -> arrangements(items - head).map { listOf(head) + it } }
}
