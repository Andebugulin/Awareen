package com.andebugulin.awareen.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.andebugulin.awareen.overlay.LevelSettings
import com.andebugulin.awareen.overlay.OverlayController
import com.andebugulin.awareen.overlay.OverlaySettings

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

    /**
     * Atomic wholesale write of overlay settings. The three writeLevelXPosition
     * flags let the caller preserve a previously-dragged custom position when
     * the user's spinner is on "Custom" — for those levels, pass false and
     * the LEVEL_X_POSITION key is left untouched.
     */
    fun saveOverlaySettings(
        settings: OverlaySettings,
        writeLevel1Position: Boolean = true,
        writeLevel2Position: Boolean = true,
        writeLevel3Position: Boolean = true,
    ) {
        prefs.edit().apply {
            // Level 1
            putInt(AppSettings.LEVEL_1_COLOR, settings.level1.color)
            if (writeLevel1Position) putString(AppSettings.LEVEL_1_POSITION, settings.level1.position)
            putInt(AppSettings.LEVEL_1_FONT_SIZE, settings.level1.fontSize.toInt())
            putBoolean(AppSettings.LEVEL_1_BLINKING_ENABLED, settings.level1.blinkingEnabled)
            putInt(AppSettings.LEVEL_1_MAX_TIME_SECONDS, settings.level1MaxTimeSeconds)
            // Level 2
            putInt(AppSettings.LEVEL_2_COLOR, settings.level2.color)
            if (writeLevel2Position) putString(AppSettings.LEVEL_2_POSITION, settings.level2.position)
            putInt(AppSettings.LEVEL_2_FONT_SIZE, settings.level2.fontSize.toInt())
            putBoolean(AppSettings.LEVEL_2_BLINKING_ENABLED, settings.level2.blinkingEnabled)
            putInt(AppSettings.LEVEL_2_DURATION_SECONDS, settings.level2DurationSeconds)
            // Level 3
            putInt(AppSettings.LEVEL_3_COLOR, settings.level3.color)
            if (writeLevel3Position) putString(AppSettings.LEVEL_3_POSITION, settings.level3.position)
            putInt(AppSettings.LEVEL_3_FONT_SIZE, settings.level3.fontSize.toInt())
            putBoolean(AppSettings.LEVEL_3_BLINKING_ENABLED, settings.level3.blinkingEnabled)
            // Timer display
            putString(AppSettings.TIMER_DISPLAY_MODE, settings.timerDisplayMode)
            putInt(AppSettings.TIMER_DISPLAY_INTERVAL_MINUTES, settings.timerDisplayIntervalMinutes)
            putInt(AppSettings.TIMER_DISPLAY_DURATION_SECONDS, settings.timerDisplayDurationSeconds)
            apply()
        }
    }

    // =========================================================================
    // RESET TIME
    // =========================================================================

    fun getResetHour(): Int =
        prefs.getInt(AppSettings.RESET_HOUR, AppSettings.DEFAULT_RESET_HOUR)

    fun getResetMinute(): Int =
        prefs.getInt(AppSettings.RESET_MINUTE, AppSettings.DEFAULT_RESET_MINUTE)

    fun saveResetTime(hour: Int, minute: Int) {
        prefs.edit()
            .putInt(AppSettings.RESET_HOUR, hour)
            .putInt(AppSettings.RESET_MINUTE, minute)
            .apply()
    }

    // =========================================================================
    // CUSTOM DRAG POSITIONS
    // The key constants live on OverlayController because the touch handler
    // also writes them; this is the same set of keys, just exposed here so
    // SettingsActivity (import + spinner-switch-from-Custom) doesn't have to
    // know the layout.
    // =========================================================================

    fun setCustomPosition(level: Int, x: Int, y: Int) {
        val (useKey, xKey, yKey) = customPositionKeys(level)
        prefs.edit()
            .putBoolean(useKey, true)
            .putInt(xKey, x)
            .putInt(yKey, y)
            .apply()
    }

    fun clearCustomPosition(level: Int) {
        val (useKey, xKey, yKey) = customPositionKeys(level)
        prefs.edit()
            .putBoolean(useKey, false)
            .remove(xKey)
            .remove(yKey)
            .apply()
    }

    private fun customPositionKeys(level: Int): Triple<String, String, String> = when (level) {
        1 -> Triple(OverlayController.LEVEL_1_USE_CUSTOM, OverlayController.LEVEL_1_CUSTOM_X, OverlayController.LEVEL_1_CUSTOM_Y)
        2 -> Triple(OverlayController.LEVEL_2_USE_CUSTOM, OverlayController.LEVEL_2_CUSTOM_X, OverlayController.LEVEL_2_CUSTOM_Y)
        else -> Triple(OverlayController.LEVEL_3_USE_CUSTOM, OverlayController.LEVEL_3_CUSTOM_X, OverlayController.LEVEL_3_CUSTOM_Y)
    }

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
