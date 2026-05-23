package com.andebugulin.awareen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * Owns daily-reset scheduling and the wall-clock math behind it.
 *
 * Two callers share this:
 *   - ScreenTimeService — primary path; schedules the Doze-proof alarm and
 *     checks on every tick / wake / settings change / time change.
 *   - MainActivity.performDefensiveResetCheck — runs on every onResume as a
 *     safety net for when the service was killed and the alarm didn't fire.
 *
 * Reset hour/minute are read from [prefs] on each call rather than cached, so
 * the scheduler stays correct across settings changes without an explicit
 * reconfigure step.
 */
class ResetScheduler(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val repo: ScreenTimeRepository,
) {
    companion object {
        const val ACTION_ALARM_RESET = "com.andebugulin.awareen.ALARM_RESET"
        private const val ALARM_REQUEST_CODE = 1001
        private const val TAG = "ResetScheduler"
    }

    // =========================================================================
    // DECISION + RESET
    // =========================================================================

    /**
     * True if a scheduled reset moment has passed since the last actual reset.
     * Deterministic — no grace periods.
     */
    fun shouldReset(): Boolean {
        val lastReset = repo.getLastResetTimestamp()
        val mostRecentScheduled = getMostRecentResetMillis()
        val needed = lastReset < mostRecentScheduled
        if (needed) {
            Log.d(TAG, "Reset needed: lastReset=$lastReset < scheduledAt=$mostRecentScheduled")
        }
        return needed
    }

    /**
     * If a reset is overdue, zero today's counter and stamp the moment.
     * Returns true if a reset was performed (callers may want to update UI).
     */
    fun checkAndReset(): Boolean {
        if (!shouldReset()) return false
        Log.d(TAG, "Performing reset — clearing screen time to 0")
        repo.markReset()
        Log.d(TAG, "Reset complete. Next reset at ${getNextResetMillis()}")
        return true
    }

    // =========================================================================
    // ALARM
    // =========================================================================

    /**
     * Schedule an exact alarm at the next reset moment. Survives Doze on
     * API 23+; falls back to setExact below that.
     */
    fun scheduleNextAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildAlarmPendingIntent()
        val nextReset = getNextResetMillis()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextReset,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                nextReset,
                pendingIntent
            )
        }

        Log.d(TAG, "Exact alarm scheduled for $nextReset (in ${(nextReset - System.currentTimeMillis()) / 1000 / 60} min)")
    }

    fun cancelAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildAlarmPendingIntent())
    }

    private fun buildAlarmPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_ALARM_RESET).apply {
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // =========================================================================
    // WALL-CLOCK MATH
    // =========================================================================

    /**
     * Most recent wall-clock instant matching the configured reset hour:minute
     * that is <= now.
     */
    fun getMostRecentResetMillis(): Long {
        val (h, m) = readResetTime()
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis > now) cal.add(Calendar.DAY_OF_YEAR, -1)
        return cal.timeInMillis
    }

    /**
     * Next future wall-clock instant matching the reset hour:minute.
     */
    fun getNextResetMillis(): Long {
        val (h, m) = readResetTime()
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun readResetTime(): Pair<Int, Int> = Pair(
        prefs.getInt(AppSettings.RESET_HOUR, AppSettings.DEFAULT_RESET_HOUR),
        prefs.getInt(AppSettings.RESET_MINUTE, AppSettings.DEFAULT_RESET_MINUTE)
    )
}
