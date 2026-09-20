package com.andebugulin.awareen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The reset is the app's core correctness claim and has three independent
 * trigger paths (tick loop, Doze alarm, defensive check on resume) that must
 * all agree. These pin the arithmetic they share.
 */
class ResetMathTest {

    private val newYork = ZoneId.of("America/New_York")
    private val utc = ZoneId.of("UTC")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // --- basic ordering -----------------------------------------------------

    @Test
    fun `most recent reset is today's when the reset hour has already passed`() {
        val now = at(utc, 2026, 5, 20, 10, 0)
        assertEquals(at(utc, 2026, 5, 20, 4, 0), ResetMath.mostRecentReset(now, 4, 0, utc))
    }

    @Test
    fun `most recent reset is yesterday's when the reset hour is still ahead`() {
        val now = at(utc, 2026, 5, 20, 2, 0)
        assertEquals(at(utc, 2026, 5, 19, 4, 0), ResetMath.mostRecentReset(now, 4, 0, utc))
    }

    @Test
    fun `next reset is strictly in the future, never now`() {
        val exactly = at(utc, 2026, 5, 20, 4, 0)
        assertEquals(at(utc, 2026, 5, 21, 4, 0), ResetMath.nextReset(exactly, 4, 0, utc))
    }

    @Test
    fun `standing exactly on the reset instant counts it as already reached`() {
        val exactly = at(utc, 2026, 5, 20, 4, 0)
        assertEquals(exactly, ResetMath.mostRecentReset(exactly, 4, 0, utc))
    }

    @Test
    fun `most recent and next reset are always exactly one day apart`() {
        val now = at(utc, 2026, 5, 20, 10, 0)
        val span = ResetMath.nextReset(now, 4, 0, utc) - ResetMath.mostRecentReset(now, 4, 0, utc)
        assertEquals(24 * 60 * 60 * 1000L, span)
    }

    // --- midnight -----------------------------------------------------------

    @Test
    fun `a reset time of exactly midnight resolves to the start of today`() {
        val now = at(utc, 2026, 5, 20, 0, 30)
        assertEquals(at(utc, 2026, 5, 20, 0, 0), ResetMath.mostRecentReset(now, 0, 0, utc))
        assertEquals(at(utc, 2026, 5, 21, 0, 0), ResetMath.nextReset(now, 0, 0, utc))
    }

    @Test
    fun `one second before midnight the reset is still yesterday's`() {
        val now = at(utc, 2026, 5, 20, 23, 59) + 59_000
        assertEquals(at(utc, 2026, 5, 20, 0, 0), ResetMath.mostRecentReset(now, 0, 0, utc))
    }

    // --- DST ----------------------------------------------------------------

    @Test
    fun `spring forward still yields exactly one reset that day`() {
        // 2026-03-08, America/New_York: 02:00 jumps to 03:00, so a 02:30 reset
        // time does not exist on this date. It resolves forward to 03:30 EDT —
        // still exactly one reset on the 8th, just an hour later than usual.
        val beforeGap = at(newYork, 2026, 3, 8, 1, 0)
        val next = ResetMath.nextReset(beforeGap, 2, 30, newYork)
        val following = ResetMath.nextReset(next, 2, 30, newYork)

        assertTrue("reset must be after the instant it was computed from", next > beforeGap)
        assertEquals(at(newYork, 2026, 3, 8, 3, 30), next)
        assertEquals(at(newYork, 2026, 3, 9, 2, 30), following)
        // The gap-shifted reset ran an hour late, so the interval to the next
        // one is 23h rather than 24h. One reset per calendar day is preserved,
        // which is the property that matters.
        assertEquals(23 * 60 * 60 * 1000L, following - next)
    }

    @Test
    fun `fall back does not produce two resets in one day`() {
        // 2026-11-01, America/New_York: 02:00 happens twice.
        val before = at(newYork, 2026, 11, 1, 0, 30)
        val next = ResetMath.nextReset(before, 1, 30, newYork)
        val following = ResetMath.nextReset(next, 1, 30, newYork)
        assertTrue(following > next)
        // A fall-back day is 25 hours long, so consecutive resets span 25h.
        assertEquals(25 * 60 * 60 * 1000L, following - next)
    }

    @Test
    fun `day arithmetic across DST is calendar-day based, not 24-hour based`() {
        val now = at(newYork, 2026, 3, 8, 12, 0)
        val recent = ResetMath.mostRecentReset(now, 9, 0, newYork)
        // 09:00 local on the spring-forward day, not 09:00-minus-an-hour.
        assertEquals(at(newYork, 2026, 3, 8, 9, 0), recent)
    }

    // --- shouldReset --------------------------------------------------------

    @Test
    fun `no reset is due immediately after one was performed`() {
        val now = at(utc, 2026, 5, 20, 10, 0)
        assertFalse(ResetMath.shouldReset(now, now, 4, 0, utc))
    }

    @Test
    fun `a reset is due once the boundary has been crossed`() {
        val lastReset = at(utc, 2026, 5, 19, 10, 0)
        val now = at(utc, 2026, 5, 20, 5, 0)
        assertTrue(ResetMath.shouldReset(lastReset, now, 4, 0, utc))
    }

    @Test
    fun `a never-reset device resets on first check`() {
        val now = at(utc, 2026, 5, 20, 10, 0)
        assertTrue(ResetMath.shouldReset(0L, now, 4, 0, utc))
    }

    @Test
    fun `a clock moved backwards does not trigger a spurious reset`() {
        // Reset happened at 04:00; user then winds the clock back to 03:00.
        val lastReset = at(utc, 2026, 5, 20, 4, 0)
        val now = at(utc, 2026, 5, 20, 3, 0)
        assertFalse(ResetMath.shouldReset(lastReset, now, 4, 0, utc))
    }

    @Test
    fun `moving the reset hour later in the day does not re-trigger today`() {
        // Reset ran at 04:00 today; user changes reset time to 06:00 at 05:00.
        val lastReset = at(utc, 2026, 5, 20, 4, 0)
        val now = at(utc, 2026, 5, 20, 5, 0)
        assertFalse(ResetMath.shouldReset(lastReset, now, 6, 0, utc))
    }

    @Test
    fun `moving the reset hour earlier makes an already-passed boundary due`() {
        // Reset ran at 04:00 yesterday; user sets reset to 02:00, now 05:00.
        val lastReset = at(utc, 2026, 5, 19, 4, 0)
        val now = at(utc, 2026, 5, 20, 5, 0)
        assertTrue(ResetMath.shouldReset(lastReset, now, 2, 0, utc))
    }

    // --- corrupt input ------------------------------------------------------

    @Test
    fun `an out-of-range stored hour is coerced instead of raising`() {
        // Reaches this on the one-second tick loop; a throw here is a crash loop.
        val now = at(utc, 2026, 5, 20, 10, 0)
        // 99 coerces to 23:00, which is still ahead of 10:00, so the most
        // recent occurrence is yesterday's.
        assertEquals(at(utc, 2026, 5, 19, 23, 0), ResetMath.mostRecentReset(now, 99, 0, utc))
        assertEquals(at(utc, 2026, 5, 20, 0, 0), ResetMath.mostRecentReset(now, -5, 0, utc))
    }

    @Test
    fun `an out-of-range stored minute is coerced instead of raising`() {
        val now = at(utc, 2026, 5, 20, 10, 0)
        assertEquals(at(utc, 2026, 5, 20, 4, 59), ResetMath.mostRecentReset(now, 4, 240, utc))
    }
}
