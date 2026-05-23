package com.andebugulin.awareen.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * Single source of truth for screen-time persistence: today's running total,
 * reset bookkeeping, and analytics writes/reads.
 *
 * Pure I/O. Holds no business logic about *when* to reset — that decision
 * belongs to the caller (the service's tick loop and the activity's defensive
 * check both call [markReset] when they decide a reset is due).
 */
class ScreenTimeRepository(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
    )

    // =========================================================================
    // TODAY'S SCREEN TIME
    // =========================================================================

    fun getTodayScreenTime(): Int = prefs.getInt(todayDateKey(), 0)

    fun saveTodayScreenTime(seconds: Int) {
        val todayKey = todayDateKey()
        prefs.edit()
            .putInt(todayKey, seconds)
            .putString(KEY_LAST_DATE, todayKey)
            .putLong(KEY_LAST_SAVE, System.currentTimeMillis())
            .apply()
    }

    // =========================================================================
    // RESET BOOKKEEPING
    // =========================================================================

    fun getLastResetTimestamp(): Long = prefs.getLong(KEY_LAST_RESET, 0L)

    /**
     * Zero today's counter and stamp the reset moment. Used by both the
     * service's main reset path and the activity's defensive check — keeping
     * the write here ensures both paths produce identical state.
     */
    fun markReset() {
        val todayKey = todayDateKey()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_LAST_DATE, todayKey)
            .putInt(todayKey, 0)
            .putLong(KEY_LAST_SAVE, now)
            .putLong(KEY_LAST_RESET, now)
            .apply()
    }

    // =========================================================================
    // ANALYTICS — WRITES
    // =========================================================================

    /**
     * Record one tick (one second of screen-on time) into the analytics
     * tables: today's daily total, today's hourly bucket, and the date index.
     */
    fun recordAnalyticsTick(seconds: Int) {
        val cal = Calendar.getInstance()
        val dateKey = analyticsDateKey(cal)
        val hourKey = "${dateKey}_hour_${cal.get(Calendar.HOUR_OF_DAY)}"
        val currentHour = prefs.getInt(hourKey, 0)
        // getStringSet returns a backing set whose mutation is undefined —
        // copy before edit.
        val dates = prefs.getStringSet(KEY_ANALYTICS_DATES, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        dates.add(dateKey)
        prefs.edit()
            .putInt(dateKey, seconds)
            .putInt(hourKey, currentHour + 1)
            .putStringSet(KEY_ANALYTICS_DATES, dates)
            .apply()
    }

    // =========================================================================
    // ANALYTICS — READS (used by AnalyticsActivity)
    // =========================================================================

    fun getAnalyticsDateKeys(): Set<String> =
        prefs.getStringSet(KEY_ANALYTICS_DATES, emptySet())?.toSet() ?: emptySet()

    fun getDailyScreenTime(dateKey: String): Int = prefs.getInt(dateKey, 0)

    fun getHourlyBreakdown(dateKey: String): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        for (h in 0..23) {
            val v = prefs.getInt("${dateKey}_hour_$h", 0)
            if (v > 0) result[h] = v
        }
        return result
    }

    /**
     * Bulk write for analytics import. The caller decides whether to import
     * (e.g. only if greater than existing) — this just writes.
     */
    fun importDay(dateKey: String, screenTimeSeconds: Int, hourlyBreakdown: Map<Int, Int>) {
        val dates = prefs.getStringSet(KEY_ANALYTICS_DATES, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        dates.add(dateKey)
        val editor = prefs.edit()
            .putInt(dateKey, screenTimeSeconds)
            .putStringSet(KEY_ANALYTICS_DATES, dates)
        hourlyBreakdown.forEach { (hour, value) ->
            editor.putInt("${dateKey}_hour_$hour", value)
        }
        editor.apply()
    }

    // =========================================================================
    // KEY HELPERS
    // =========================================================================

    private fun todayDateKey(): String {
        val cal = Calendar.getInstance()
        return "screen_time_${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun analyticsDateKey(cal: Calendar): String =
        "analytics_${cal.get(Calendar.YEAR)}_${cal.get(Calendar.MONTH)}_${cal.get(Calendar.DAY_OF_MONTH)}"

    companion object {
        private const val KEY_LAST_DATE = "last_date_key"
        private const val KEY_LAST_SAVE = "last_save_timestamp"
        private const val KEY_LAST_RESET = "last_actual_reset_timestamp"
        private const val KEY_ANALYTICS_DATES = "analytics_dates"
    }
}
