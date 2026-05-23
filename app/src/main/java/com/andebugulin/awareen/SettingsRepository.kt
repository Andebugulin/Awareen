package com.andebugulin.awareen

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * Single I/O layer for user-configurable settings: per-level overlay style
 * (color / position / font size / blinking / threshold), timer display mode,
 * and reset time. Also owns the ACTION_SETTINGS_UPDATED broadcast so every
 * caller emits it with [setPackage] — required on API 34+ for receivers
 * registered with RECEIVER_NOT_EXPORTED.
 *
 * Custom drag positions are NOT owned here — those live with [OverlayController]
 * because they're written by touch events, not by the settings UI.
 */
class SettingsRepository(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context,
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
    )

    // =========================================================================
    // OVERLAY SETTINGS — read as a single typed snapshot
    // =========================================================================

    /**
     * Build the full overlay-settings snapshot from prefs. Allocates fresh on
     * every call; cheap (~10 small objects) and immutable, so callers can
     * hold the result for as long as they like.
     */
    fun loadOverlaySettings(): OverlaySettings = OverlaySettings(
        level1 = LevelSettings(
            color = prefs.getInt(AppSettings.LEVEL_1_COLOR, AppSettings.DEFAULT_LEVEL_1_COLOR),
            position = prefs.getString(AppSettings.LEVEL_1_POSITION, AppSettings.DEFAULT_LEVEL_1_POSITION)
                ?: AppSettings.DEFAULT_LEVEL_1_POSITION,
            fontSize = prefs.getInt(AppSettings.LEVEL_1_FONT_SIZE, AppSettings.DEFAULT_LEVEL_1_FONT_SIZE).toFloat(),
            blinkingEnabled = prefs.getBoolean(AppSettings.LEVEL_1_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_1_BLINKING_ENABLED),
        ),
        level1MaxTimeSeconds = prefs.getInt(AppSettings.LEVEL_1_MAX_TIME_SECONDS, AppSettings.DEFAULT_LEVEL_1_MAX_TIME_SECONDS),
        level2 = LevelSettings(
            color = prefs.getInt(AppSettings.LEVEL_2_COLOR, AppSettings.DEFAULT_LEVEL_2_COLOR),
            position = prefs.getString(AppSettings.LEVEL_2_POSITION, AppSettings.DEFAULT_LEVEL_2_POSITION)
                ?: AppSettings.DEFAULT_LEVEL_2_POSITION,
            fontSize = prefs.getInt(AppSettings.LEVEL_2_FONT_SIZE, AppSettings.DEFAULT_LEVEL_2_FONT_SIZE).toFloat(),
            blinkingEnabled = prefs.getBoolean(AppSettings.LEVEL_2_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_2_BLINKING_ENABLED),
        ),
        level2DurationSeconds = prefs.getInt(AppSettings.LEVEL_2_DURATION_SECONDS, AppSettings.DEFAULT_LEVEL_2_DURATION_SECONDS),
        level3 = LevelSettings(
            color = prefs.getInt(AppSettings.LEVEL_3_COLOR, AppSettings.DEFAULT_LEVEL_3_COLOR),
            position = prefs.getString(AppSettings.LEVEL_3_POSITION, AppSettings.DEFAULT_LEVEL_3_POSITION)
                ?: AppSettings.DEFAULT_LEVEL_3_POSITION,
            fontSize = prefs.getInt(AppSettings.LEVEL_3_FONT_SIZE, AppSettings.DEFAULT_LEVEL_3_FONT_SIZE).toFloat(),
            blinkingEnabled = prefs.getBoolean(AppSettings.LEVEL_3_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_3_BLINKING_ENABLED),
        ),
        timerDisplayMode = prefs.getString(AppSettings.TIMER_DISPLAY_MODE, AppSettings.DEFAULT_TIMER_DISPLAY_MODE)
            ?: AppSettings.DEFAULT_TIMER_DISPLAY_MODE,
        timerDisplayIntervalMinutes = prefs.getInt(AppSettings.TIMER_DISPLAY_INTERVAL_MINUTES, AppSettings.DEFAULT_TIMER_DISPLAY_INTERVAL_MINUTES),
        timerDisplayDurationSeconds = prefs.getInt(AppSettings.TIMER_DISPLAY_DURATION_SECONDS, AppSettings.DEFAULT_TIMER_DISPLAY_DURATION_SECONDS),
    )

    // =========================================================================
    // RESET TIME
    // =========================================================================

    fun getResetHour(): Int =
        prefs.getInt(AppSettings.RESET_HOUR, AppSettings.DEFAULT_RESET_HOUR)

    fun getResetMinute(): Int =
        prefs.getInt(AppSettings.RESET_MINUTE, AppSettings.DEFAULT_RESET_MINUTE)

    // =========================================================================
    // BROADCAST
    // =========================================================================

    /**
     * Broadcast ACTION_SETTINGS_UPDATED to the service. setPackage(packageName)
     * is required — the service registers its receiver with RECEIVER_NOT_EXPORTED,
     * so on API 34+ broadcasts without an explicit target package are dropped.
     */
    fun notifySettingsUpdated() {
        val intent = Intent(AppSettings.ACTION_SETTINGS_UPDATED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}
