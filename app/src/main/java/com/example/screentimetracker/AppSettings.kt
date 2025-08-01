package com.example.screentimetracker

import android.graphics.Color

/**
 * Object to hold constants for SharedPreferences keys and default values.
 * This ensures consistency across the application.
 */
object AppSettings {
    // Name of the SharedPreferences file
    const val PREFS_NAME = "screen_time_prefs"

    // --- Level 1 Settings ---
    const val LEVEL_1_COLOR = "level_1_color"
    const val LEVEL_1_POSITION = "level_1_position" // String like "Top Left"
    const val LEVEL_1_FONT_SIZE = "level_1_font_size" // Integer
    const val LEVEL_1_MAX_TIME_SECONDS = "level_1_max_time_seconds" // Integer, in seconds

    // --- Level 2 Settings ---
    const val LEVEL_2_COLOR = "level_2_color"
    const val LEVEL_2_POSITION = "level_2_position"
    const val LEVEL_2_FONT_SIZE = "level_2_font_size"
    // Level 2 starts after Level 1 ends. This is the DURATION of Level 2.
    const val LEVEL_2_DURATION_SECONDS = "level_2_duration_seconds" // Integer, in seconds

    // --- Level 3 Settings ---
    // Level 3 starts after Level 1_MAX_TIME + Level 2_DURATION
    const val LEVEL_3_COLOR = "level_3_color"
    const val LEVEL_3_POSITION = "level_3_position"
    const val LEVEL_3_FONT_SIZE = "level_3_font_size"
    const val LEVEL_3_BLINKING_ENABLED = "level_3_blinking_enabled"

    // --- Timer Display Settings ---
    const val TIMER_DISPLAY_MODE = "timer_display_mode" // "always" or "interval"
    const val TIMER_DISPLAY_INTERVAL_MINUTES = "timer_display_interval_minutes" // How often to show (in minutes)
    const val TIMER_DISPLAY_DURATION_SECONDS = "timer_display_duration_seconds" // How long to show (in seconds)

    // --- Reset Time Settings ---
    const val RESET_HOUR = "reset_hour" // Integer (0-23)
    const val RESET_MINUTE = "reset_minute" // Integer (0-59)

    // --- Default Values ---
    // Default settings for Level 1
    val DEFAULT_LEVEL_1_COLOR = Color.GREEN
    val DEFAULT_LEVEL_1_POSITION = "Top Right" // Default position
    const val DEFAULT_LEVEL_1_FONT_SIZE = 24 // Default font size in sp
    const val DEFAULT_LEVEL_1_MAX_TIME_SECONDS = 30 * 60 // 30 minutes

    // Default settings for Level 2
    val DEFAULT_LEVEL_2_COLOR = Color.YELLOW
    val DEFAULT_LEVEL_2_POSITION = "Middle Left"
    const val DEFAULT_LEVEL_2_FONT_SIZE = 26 // Default font size in sp
    const val DEFAULT_LEVEL_2_DURATION_SECONDS = 30 * 60 // 30 minutes duration for level 2

    // Default settings for Level 3
    val DEFAULT_LEVEL_3_COLOR = Color.RED
    val DEFAULT_LEVEL_3_POSITION = "Middle Center"
    const val DEFAULT_LEVEL_3_FONT_SIZE = 28 // Default font size in sp
    const val DEFAULT_LEVEL_3_BLINKING_ENABLED = true

    // Default timer display settings
    const val DEFAULT_TIMER_DISPLAY_MODE = "always" // "always" or "interval"
    const val DEFAULT_TIMER_DISPLAY_INTERVAL_MINUTES = 1 // Show every 1 minute
    const val DEFAULT_TIMER_DISPLAY_DURATION_SECONDS = 5 // Show for 5 seconds

    const val MIN_DISPLAY_INTERVAL_MINUTES = 1
    const val MAX_DISPLAY_INTERVAL_MINUTES = 10
    const val MIN_DISPLAY_DURATION_SECONDS = 1
    const val MAX_DISPLAY_DURATION_SECONDS = 30

    // Default reset time (midnight)
    const val DEFAULT_RESET_HOUR = 0
    const val DEFAULT_RESET_MINUTE = 0

    // Action for broadcasting settings updates
    const val ACTION_SETTINGS_UPDATED = "com.example.screentimetracker.SETTINGS_UPDATED"
}