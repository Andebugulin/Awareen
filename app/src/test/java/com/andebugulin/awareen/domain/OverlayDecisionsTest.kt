package com.andebugulin.awareen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayDecisionsTest {

    // --- level selection ----------------------------------------------------

    @Test
    fun `time below the level one threshold is level one`() {
        assertEquals(1, OverlayDecisions.levelFor(0, 3600, 3600))
        assertEquals(1, OverlayDecisions.levelFor(3599, 3600, 3600))
    }

    @Test
    fun `the level one threshold itself is already level two`() {
        assertEquals(2, OverlayDecisions.levelFor(3600, 3600, 3600))
    }

    @Test
    fun `the end of the level two window is already level three`() {
        assertEquals(2, OverlayDecisions.levelFor(7199, 3600, 3600))
        assertEquals(3, OverlayDecisions.levelFor(7200, 3600, 3600))
    }

    @Test
    fun `a level one maximum of zero starts the user at level two`() {
        assertEquals(2, OverlayDecisions.levelFor(0, 0, 60))
        assertEquals(3, OverlayDecisions.levelFor(60, 0, 60))
    }

    @Test
    fun `a zero-length level two window is skipped entirely`() {
        assertEquals(1, OverlayDecisions.levelFor(59, 60, 0))
        assertEquals(3, OverlayDecisions.levelFor(60, 60, 0))
    }

    @Test
    fun `absurd stored thresholds cannot overflow into the wrong level`() {
        // level1Max + level2Duration overflows Int; must not wrap negative and
        // report level 3 for a user who is only a minute in.
        assertEquals(2, OverlayDecisions.levelFor(60, 1, Int.MAX_VALUE))
    }

    // --- interval visibility ------------------------------------------------

    @Test
    fun `always mode shows the timer regardless of elapsed time`() {
        assertTrue(OverlayDecisions.shouldShowOverlay("always", 0, 5, 10))
        assertTrue(OverlayDecisions.shouldShowOverlay("always", 99999, 5, 10))
    }

    @Test
    fun `never mode hides the timer regardless of elapsed time`() {
        assertFalse(OverlayDecisions.shouldShowOverlay("never", 0, 5, 10))
        assertFalse(OverlayDecisions.shouldShowOverlay("never", 99999, 5, 10))
    }

    @Test
    fun `an unrecognised mode falls back to showing the timer`() {
        assertTrue(OverlayDecisions.shouldShowOverlay("", 0, 5, 10))
        assertTrue(OverlayDecisions.shouldShowOverlay("garbage-from-import", 0, 5, 10))
    }

    @Test
    fun `interval mode shows for the configured duration at the top of each interval`() {
        // every 5 minutes, for 10 seconds
        assertTrue(OverlayDecisions.shouldShowOverlay("interval", 0, 5, 10))
        assertTrue(OverlayDecisions.shouldShowOverlay("interval", 9, 5, 10))
        assertFalse(OverlayDecisions.shouldShowOverlay("interval", 10, 5, 10))
        assertFalse(OverlayDecisions.shouldShowOverlay("interval", 299, 5, 10))
        assertTrue(OverlayDecisions.shouldShowOverlay("interval", 300, 5, 10))
    }

    @Test
    fun `interval mode does not show during minutes that are not interval boundaries`() {
        assertFalse(OverlayDecisions.shouldShowOverlay("interval", 60, 5, 10))
        assertFalse(OverlayDecisions.shouldShowOverlay("interval", 120, 5, 10))
        assertFalse(OverlayDecisions.shouldShowOverlay("interval", 240, 5, 10))
    }

    /**
     * Regression: the interval is a divisor and is reachable from an imported
     * settings JSON with no validation. A zero used to divide inside the
     * one-second tick loop, so the ArithmeticException recurred every second.
     */
    @Test
    fun `a zero interval from a corrupt import does not divide by zero`() {
        assertTrue(OverlayDecisions.shouldShowOverlay("interval", 0, 0, 10))
        assertTrue(OverlayDecisions.shouldShowOverlay("interval", 61, 0, 10))
        assertFalse(OverlayDecisions.shouldShowOverlay("interval", 70, 0, 10))
    }

    @Test
    fun `a negative interval from a corrupt import does not divide by zero`() {
        assertTrue(OverlayDecisions.shouldShowOverlay("interval", 0, -5, 10))
    }

    @Test
    fun `a zero duration means the timer never actually appears`() {
        assertFalse(OverlayDecisions.shouldShowOverlay("interval", 0, 5, 0))
        assertFalse(OverlayDecisions.shouldShowOverlay("interval", 300, 5, 0))
    }
}
