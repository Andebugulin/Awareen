package com.andebugulin.awareen.domain

/**
 * The two per-tick display decisions the overlay makes, lifted out of
 * rendering so they can be pinned at their boundaries.
 */
object OverlayDecisions {

    /**
     * Which of the three style levels [seconds] falls in.
     *
     * Boundaries are half-open: `seconds == level1MaxSeconds` is already
     * level 2. Widened to Long internally so an absurd stored threshold
     * cannot overflow the sum and wrap into the wrong level.
     */
    fun levelFor(seconds: Int, level1MaxSeconds: Int, level2DurationSeconds: Int): Int {
        val level2End = level1MaxSeconds.toLong() + level2DurationSeconds.toLong()
        return when {
            seconds < level1MaxSeconds.toLong() -> 1
            seconds < level2End -> 2
            else -> 3
        }
    }

    /**
     * Whether the timer should be visible on this tick.
     *
     * [intervalMinutes] is coerced to at least 1: it is a divisor, it is
     * reachable from an imported settings JSON with no validation, and a zero
     * here divides on the service's one-second tick — an ArithmeticException
     * there is a crash loop, not a single crash.
     */
    fun shouldShowOverlay(
        mode: String,
        seconds: Int,
        intervalMinutes: Int,
        durationSeconds: Int,
    ): Boolean = when (mode) {
        "never" -> false
        "interval" -> {
            val interval = intervalMinutes.coerceAtLeast(1)
            (seconds / 60) % interval == 0 && (seconds % 60) < durationSeconds
        }
        else -> true
    }
}
