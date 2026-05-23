package com.andebugulin.awareen.overlay

/**
 * Visual configuration for a single level (color, position, font size, blink).
 */
data class LevelSettings(
    val color: Int,
    val position: String,
    val fontSize: Float,
    val blinkingEnabled: Boolean,
)

/**
 * Full snapshot of overlay-related settings. Built by SettingsRepository.
 * loadOverlaySettings() once per settings change; held by ScreenTimeService
 * and passed straight to OverlayController.render() each tick.
 *
 * Immutable — safe to share across the tick loop and the render call.
 */
data class OverlaySettings(
    val level1: LevelSettings,
    val level1MaxTimeSeconds: Int,
    val level2: LevelSettings,
    val level2DurationSeconds: Int,
    val level3: LevelSettings,
    val timerDisplayMode: String,
    val timerDisplayIntervalMinutes: Int,
    val timerDisplayDurationSeconds: Int,
)
