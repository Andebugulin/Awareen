package com.andebugulin.awareen.domain

import java.time.LocalDate

/**
 * SharedPreferences key construction for screen-time and analytics records.
 *
 * These strings are a storage format, not an implementation detail: they name
 * rows already written on every user's device. A change to any of them
 * orphans existing history silently — the app simply starts counting from
 * zero and the old rows become unreachable.
 *
 * In particular the analytics month is **0-based**, inherited from
 * `Calendar.MONTH`: September 2026 is `analytics_2026_8_20`, not
 * `analytics_2026_9_20`. Tests pin this against Calendar directly.
 */
object AnalyticsKeys {

    /** `screen_time_YEAR_DAY_OF_YEAR` — today's running total. */
    fun dailyScreenTimeKey(date: LocalDate): String =
        "screen_time_${date.year}_${date.dayOfYear}"

    /** `analytics_YEAR_MONTH_DAY`, month 0-based for Calendar compatibility. */
    fun analyticsDateKey(date: LocalDate): String =
        "analytics_${date.year}_${date.monthValue - 1}_${date.dayOfMonth}"

    /** `analytics_YEAR_MONTH_DAY_hour_H` — one bucket of the hourly breakdown. */
    fun hourKey(dateKey: String, hour: Int): String = "${dateKey}_hour_$hour"
}
