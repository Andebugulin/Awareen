package com.andebugulin.awareen.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * These keys name rows already written on every installed device. The
 * equivalence tests below are the guard against a refactor silently orphaning
 * a user's entire history.
 */
class AnalyticsKeysTest {

    private fun calendarFor(date: LocalDate): Calendar =
        GregorianCalendar(date.year, date.monthValue - 1, date.dayOfMonth)

    // --- format -------------------------------------------------------------

    @Test
    fun `the analytics month is zero-based, inherited from Calendar`() {
        assertEquals(
            "analytics_2026_8_20",
            AnalyticsKeys.analyticsDateKey(LocalDate.of(2026, 9, 20))
        )
    }

    @Test
    fun `january is month zero and december is month eleven`() {
        assertEquals("analytics_2026_0_1", AnalyticsKeys.analyticsDateKey(LocalDate.of(2026, 1, 1)))
        assertEquals("analytics_2026_11_31", AnalyticsKeys.analyticsDateKey(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `the daily key uses day-of-year, not day-of-month`() {
        assertEquals("screen_time_2026_1", AnalyticsKeys.dailyScreenTimeKey(LocalDate.of(2026, 1, 1)))
        assertEquals("screen_time_2026_365", AnalyticsKeys.dailyScreenTimeKey(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `a leap year has a day 366`() {
        assertEquals("screen_time_2028_366", AnalyticsKeys.dailyScreenTimeKey(LocalDate.of(2028, 12, 31)))
    }

    @Test
    fun `the year boundary rolls the day-of-year back to 1`() {
        assertEquals("screen_time_2026_365", AnalyticsKeys.dailyScreenTimeKey(LocalDate.of(2026, 12, 31)))
        assertEquals("screen_time_2027_1", AnalyticsKeys.dailyScreenTimeKey(LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun `hour keys suffix the date key`() {
        assertEquals("analytics_2026_8_20_hour_0", AnalyticsKeys.hourKey("analytics_2026_8_20", 0))
        assertEquals("analytics_2026_8_20_hour_23", AnalyticsKeys.hourKey("analytics_2026_8_20", 23))
    }

    // --- equivalence with the Calendar implementation these replaced --------

    @Test
    fun `analytics keys match the Calendar-based form for every day of a year`() {
        var date = LocalDate.of(2026, 1, 1)
        while (date.year == 2026) {
            val cal = calendarFor(date)
            val legacy = "analytics_${cal.get(Calendar.YEAR)}_" +
                "${cal.get(Calendar.MONTH)}_${cal.get(Calendar.DAY_OF_MONTH)}"
            assertEquals(legacy, AnalyticsKeys.analyticsDateKey(date))
            date = date.plusDays(1)
        }
    }

    @Test
    fun `daily keys match the Calendar-based form across a leap year`() {
        var date = LocalDate.of(2028, 1, 1)
        while (date.year == 2028) {
            val cal = calendarFor(date)
            val legacy = "screen_time_${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
            assertEquals(legacy, AnalyticsKeys.dailyScreenTimeKey(date))
            date = date.plusDays(1)
        }
    }
}
