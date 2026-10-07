package com.andebugulin.awareen.overlay

/**
 * Visual configuration for a single level: the user's name for the stage,
 * text and badge-background colors, position, font size, blink.
 */
data class LevelSettings(
    val name: String,
    val color: Int,
    val backgroundColor: Int,
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
    // AppSettings.CORNER_STYLE_SQUARE / CORNER_STYLE_ROUNDED. Kept as a raw
    // string rather than an enum to match timerDisplayMode's convention.
    val cornerStyle: String,
    val borderEnabled: Boolean,
) {
    /** Settings for stage [level] (1..3). */
    fun level(level: Int): LevelSettings = when (level) {
        1 -> level1
        2 -> level2
        else -> level3
    }

    /** Copy with stage [level]'s settings replaced. */
    fun withLevel(level: Int, settings: LevelSettings): OverlaySettings = when (level) {
        1 -> copy(level1 = settings)
        2 -> copy(level2 = settings)
        else -> copy(level3 = settings)
    }
}
