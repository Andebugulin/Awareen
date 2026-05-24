package com.andebugulin.awareen.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.andebugulin.awareen.R
import kotlin.math.abs

class OverlayController(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    companion object {
        const val LEVEL_1_USE_CUSTOM = "level_1_use_custom_position"
        // Fraction of screen [0f, 1f]. Survives rotation because it scales with
        // the screen instead of being pinned to a pixel coordinate. Legacy keys
        // [LEGACY_LEVEL_*_X / _Y] hold the old absolute-pixel format and are
        // migrated to fractions on first read (see [loadCustomFraction]).
        const val LEVEL_1_CUSTOM_FX = "level_1_custom_position_fx"
        const val LEVEL_1_CUSTOM_FY = "level_1_custom_position_fy"

        const val LEVEL_2_USE_CUSTOM = "level_2_use_custom_position"
        const val LEVEL_2_CUSTOM_FX = "level_2_custom_position_fx"
        const val LEVEL_2_CUSTOM_FY = "level_2_custom_position_fy"

        const val LEVEL_3_USE_CUSTOM = "level_3_use_custom_position"
        const val LEVEL_3_CUSTOM_FX = "level_3_custom_position_fx"
        const val LEVEL_3_CUSTOM_FY = "level_3_custom_position_fy"

        // Legacy absolute-pixel keys; read once for one-shot migration then
        // wiped. New code never writes these.
        private const val LEGACY_LEVEL_1_X = "level_1_custom_position_x"
        private const val LEGACY_LEVEL_1_Y = "level_1_custom_position_y"
        private const val LEGACY_LEVEL_2_X = "level_2_custom_position_x"
        private const val LEGACY_LEVEL_2_Y = "level_2_custom_position_y"
        private const val LEGACY_LEVEL_3_X = "level_3_custom_position_x"
        private const val LEGACY_LEVEL_3_Y = "level_3_custom_position_y"

        private const val TAG = "OverlayController"
        private const val CLICK_THRESHOLD = 10f
        private const val TAP_HIDE_DURATION_MS = 5000L
        private const val TRANSLUCENT_BG = "#80000000"
    }

    // =========================================================================
    // OVERLAY STATE
    // =========================================================================

    private var overlayView: View? = null
    private var timeTextView: TextView? = null
    private var windowManager: WindowManager? = null
    private var currentLayoutParams: WindowManager.LayoutParams? = null

    private var currentLevel = 1

    // The preset position string for [currentLevel]. Cached so the rotation
    // hook can re-apply without re-deriving the level from a fresh settings
    // snapshot.
    private var currentPositionString: String = ""

    // Encodes the position we last pushed to the window manager. Lets render()
    // detect when settings change (preset → another preset, custom → preset,
    // or a fresh drag) and re-apply without calling updateViewLayout every
    // tick when nothing's changed. Null until the first render.
    private var lastAppliedPositionKey: String? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isHidden = false
    private var isIntervalVisible = true

    // Stashed at create() so ensureAttached can recreate without the service
    // re-plumbing the handler.
    private var savedHandler: Handler? = null

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    fun isCreated(): Boolean = overlayView != null && windowManager != null

    fun create(handler: Handler, level1Position: String) {
        savedHandler = handler

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        currentLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        currentPositionString = level1Position
        applyPositionForLevel(1, level1Position)

        overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_layout, null)
        timeTextView = overlayView?.findViewById(R.id.timeTextView)

        overlayView?.setOnTouchListener(buildTouchListener(handler))

        try {
            if (overlayView != null && currentLayoutParams != null) {
                windowManager?.addView(overlayView, currentLayoutParams)
                updateTimerVisibility()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view: ${e.message}", e)
        }
    }

    fun destroy() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view: ${e.message}", e)
            }
        }
        overlayView = null
        timeTextView = null
        windowManager = null
    }

    /**
     * Verify the overlay is still attached to the WindowManager. If it has been
     * detached (Doze, config change, memory pressure, task removal), recreate
     * and render fresh. Single most important defence against the
     * "timer disappears after days" bug.
     */
    fun ensureAttached(seconds: Int, settings: OverlaySettings) {
        try {
            if (overlayView?.parent != null && isCreated()) return
            Log.w(TAG, "Overlay detached — re-creating")
            destroy()
            val handler = savedHandler ?: return
            create(handler, settings.level1.position)
            render(seconds, settings)
        } catch (e: Exception) {
            Log.e(TAG, "ensureAttached failed: ${e.message}", e)
        }
    }

    // =========================================================================
    // RENDER — the single entry point for view updates.
    //
    // Called once per tick by the service. Reads [seconds] and [settings] and
    // applies every visible aspect of the overlay: text, level transitions,
    // colors (blink or static), font size, interval visibility. The service
    // does no view work of its own.
    // =========================================================================

    fun render(seconds: Int, settings: OverlaySettings) {
        if (!isCreated()) return

        // 1. Update displayed time
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        timeTextView?.text = String.format("%02d:%02d:%02d", hours, minutes, secs)

        // 2. Determine current level
        val level2EndSeconds = settings.level1MaxTimeSeconds + settings.level2DurationSeconds
        val newLevel = when {
            seconds < settings.level1MaxTimeSeconds -> 1
            seconds < level2EndSeconds -> 2
            else -> 3
        }
        val levelSettings = when (newLevel) {
            1 -> settings.level1
            2 -> settings.level2
            else -> settings.level3
        }

        // 3. Apply position when the level changes OR the position descriptor
        // for the current level changes (Settings save → new preset, switch
        // from custom → preset, fresh drag x/y, etc).
        val positionKey = positionKeyFor(newLevel, levelSettings.position)
        if (newLevel != currentLevel || positionKey != lastAppliedPositionKey) {
            currentLevel = newLevel
            currentPositionString = levelSettings.position
            lastAppliedPositionKey = positionKey
            applyPositionForLevel(newLevel, levelSettings.position)
            updateLayout()
        } else {
            // Keep the cached preset string in sync even when we skip the
            // re-apply (e.g. a settings save that only flipped color/font).
            currentPositionString = levelSettings.position
        }

        // 4. Font size
        timeTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, levelSettings.fontSize)

        // 5. Colors. Blink parity is keyed to `seconds`, so the color flip lands
        // on the same instant as the digit change.
        if (levelSettings.blinkingEnabled) {
            if (seconds % 2 == 0) {
                timeTextView?.setTextColor(Color.BLACK)
                overlayView?.setBackgroundColor(levelSettings.color)
            } else {
                timeTextView?.setTextColor(levelSettings.color)
                overlayView?.setBackgroundColor(Color.parseColor(TRANSLUCENT_BG))
            }
        } else {
            timeTextView?.setTextColor(levelSettings.color)
            overlayView?.setBackgroundColor(Color.parseColor(TRANSLUCENT_BG))
        }

        // 6. Interval visibility
        val shouldShow = if (settings.timerDisplayMode == "interval") {
            val currentMinute = (seconds / 60) % settings.timerDisplayIntervalMinutes
            currentMinute == 0 && (seconds % 60) < settings.timerDisplayDurationSeconds
        } else {
            true
        }
        setIntervalVisible(shouldShow)
    }

    // =========================================================================
    // VISIBILITY (internal — touch listener + render)
    // =========================================================================

    private fun setIntervalVisible(shouldShow: Boolean) {
        if (shouldShow == isIntervalVisible) return
        isIntervalVisible = shouldShow
        updateTimerVisibility()
    }

    private fun updateTimerVisibility() {
        val visible = !isHidden && isIntervalVisible
        overlayView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // =========================================================================
    // POSITION (internal)
    //
    // Custom drag positions are stored as a fraction [0f, 1f] of the screen
    // dimensions, NOT absolute pixels. That way they survive a rotation: a
    // timer dragged 80% across a portrait screen still sits 80% across the
    // landscape one. Old absolute-pixel prefs (LEGACY_LEVEL_*_X/Y) are
    // migrated lazily on first read inside [loadCustomFraction].
    // =========================================================================

    private data class CustomPosKeys(
        val useKey: String,
        val fxKey: String,
        val fyKey: String,
        val legacyXKey: String,
        val legacyYKey: String,
    )

    private fun customKeysFor(level: Int): CustomPosKeys = when (level) {
        1 -> CustomPosKeys(LEVEL_1_USE_CUSTOM, LEVEL_1_CUSTOM_FX, LEVEL_1_CUSTOM_FY, LEGACY_LEVEL_1_X, LEGACY_LEVEL_1_Y)
        2 -> CustomPosKeys(LEVEL_2_USE_CUSTOM, LEVEL_2_CUSTOM_FX, LEVEL_2_CUSTOM_FY, LEGACY_LEVEL_2_X, LEGACY_LEVEL_2_Y)
        else -> CustomPosKeys(LEVEL_3_USE_CUSTOM, LEVEL_3_CUSTOM_FX, LEVEL_3_CUSTOM_FY, LEGACY_LEVEL_3_X, LEGACY_LEVEL_3_Y)
    }

    /**
     * Current screen dimensions. Uses [WindowManager.currentWindowMetrics] on
     * API 30+ and [Display.getRealMetrics] on older versions. Returns (1, 1)
     * if the window manager isn't available — caller is responsible for
     * gating on isCreated() if needed.
     */
    private fun currentScreenSize(): Pair<Int, Int> {
        val wm = windowManager ?: return 1 to 1
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    /**
     * Read the stored fraction for [level]. If a legacy absolute-pixel pref
     * is present (pre-rotation-fix data), convert it to a fraction using the
     * current screen size and rewrite the prefs in the new format. This is a
     * one-shot migration per level.
     */
    private fun loadCustomFraction(level: Int): Pair<Float, Float> {
        val keys = customKeysFor(level)
        if (!prefs.contains(keys.fxKey) && prefs.contains(keys.legacyXKey)) {
            val (sw, sh) = currentScreenSize()
            val oldX = prefs.getInt(keys.legacyXKey, 0)
            val oldY = prefs.getInt(keys.legacyYKey, 0)
            val fx = (oldX.toFloat() / sw).coerceIn(0f, 1f)
            val fy = (oldY.toFloat() / sh).coerceIn(0f, 1f)
            prefs.edit()
                .putFloat(keys.fxKey, fx)
                .putFloat(keys.fyKey, fy)
                .remove(keys.legacyXKey)
                .remove(keys.legacyYKey)
                .apply()
            return fx to fy
        }
        return prefs.getFloat(keys.fxKey, 0f) to prefs.getFloat(keys.fyKey, 0f)
    }

    private fun applyPositionForLevel(level: Int, positionString: String) {
        val keys = customKeysFor(level)
        if (prefs.getBoolean(keys.useKey, false)) {
            val (fx, fy) = loadCustomFraction(level)
            val (sw, sh) = currentScreenSize()
            var px = (fx * sw).toInt()
            var py = (fy * sh).toInt()
            // Clamp so the overlay stays visible after rotation when an
            // edge-of-screen fraction (e.g. fx=0.98 in landscape) would map
            // to a coordinate that pushes the overlay off-screen in portrait.
            // overlayView width/height are 0 until first layout — skip then.
            val ow = overlayView?.width ?: 0
            val oh = overlayView?.height ?: 0
            if (ow > 0) px = px.coerceIn(0, (sw - ow).coerceAtLeast(0))
            if (oh > 0) py = py.coerceIn(0, (sh - oh).coerceAtLeast(0))
            currentLayoutParams?.gravity = Gravity.TOP or Gravity.START
            currentLayoutParams?.x = px
            currentLayoutParams?.y = py
        } else {
            currentLayoutParams?.gravity = getGravityForPosition(positionString)
            currentLayoutParams?.x = 0
            currentLayoutParams?.y = 0
        }
    }

    /**
     * Compact string descriptor of "where the overlay should sit right now" —
     * used by render() to decide if updateViewLayout needs to be called.
     * Two descriptors compare equal iff applying them would produce identical
     * layout params *for the current screen size*. Rotation does not change
     * the descriptor, so [onConfigurationChanged] forces an explicit re-apply.
     */
    private fun positionKeyFor(level: Int, positionString: String): String {
        val keys = customKeysFor(level)
        return if (prefs.getBoolean(keys.useKey, false)) {
            val fx = prefs.getFloat(keys.fxKey, 0f)
            val fy = prefs.getFloat(keys.fyKey, 0f)
            "custom:$fx:$fy"
        } else {
            "preset:$positionString"
        }
    }

    // =========================================================================
    // CONFIGURATION CHANGE (rotation, multi-window, foldable fold/unfold)
    //
    // Called by ScreenTimeService.onConfigurationChanged. Gravity-anchored
    // presets *should* re-position automatically, but updateViewLayout makes
    // it deterministic; for custom positions the fraction → pixel conversion
    // MUST happen here since the cached lastAppliedPositionKey doesn't change.
    // =========================================================================

    fun onConfigurationChanged() {
        if (!isCreated()) return
        applyPositionForLevel(currentLevel, currentPositionString)
        lastAppliedPositionKey = positionKeyFor(currentLevel, currentPositionString)
        updateLayout()
    }

    private fun updateLayout() {
        if (overlayView?.parent != null) {
            try {
                windowManager?.updateViewLayout(overlayView, currentLayoutParams)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating overlay layout: ${e.message}", e)
            }
        }
    }

    private fun getGravityForPosition(positionString: String): Int = when (positionString) {
        "Top Left" -> Gravity.TOP or Gravity.START
        "Top Center" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        "Top Right" -> Gravity.TOP or Gravity.END
        "Middle Left" -> Gravity.CENTER_VERTICAL or Gravity.START
        "Middle Center" -> Gravity.CENTER
        "Middle Right" -> Gravity.CENTER_VERTICAL or Gravity.END
        "Bottom Left" -> Gravity.BOTTOM or Gravity.START
        "Bottom Center" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        "Bottom Right" -> Gravity.BOTTOM or Gravity.END
        else -> Gravity.TOP or Gravity.END
    }

    private fun saveCustomPositionForLevel(level: Int, x: Int, y: Int) {
        val (sw, sh) = currentScreenSize()
        val fx = if (sw > 0) (x.toFloat() / sw).coerceIn(0f, 1f) else 0f
        val fy = if (sh > 0) (y.toFloat() / sh).coerceIn(0f, 1f) else 0f
        val keys = customKeysFor(level)
        prefs.edit()
            .putBoolean(keys.useKey, true)
            .putFloat(keys.fxKey, fx)
            .putFloat(keys.fyKey, fy)
            // Clean up any legacy absolute-pixel values left over from older
            // app versions so they can't shadow the new format later.
            .remove(keys.legacyXKey)
            .remove(keys.legacyYKey)
            .apply()
    }

    // =========================================================================
    // TOUCH LISTENER (internal)
    // =========================================================================

    private fun buildTouchListener(handler: Handler): View.OnTouchListener =
        object : View.OnTouchListener {
            private var touchStartTime = 0L

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (event == null || currentLayoutParams == null) return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartTime = System.currentTimeMillis()
                        initialX = currentLayoutParams!!.x
                        initialY = currentLayoutParams!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        if (abs(deltaX) > CLICK_THRESHOLD || abs(deltaY) > CLICK_THRESHOLD) {
                            isDragging = true
                            currentLayoutParams?.gravity = Gravity.TOP or Gravity.START
                            currentLayoutParams?.x = initialX + deltaX.toInt()
                            currentLayoutParams?.y = initialY + deltaY.toInt()
                            windowManager?.updateViewLayout(overlayView, currentLayoutParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val touchDuration = System.currentTimeMillis() - touchStartTime
                        if (!isDragging && touchDuration < 200) {
                            isHidden = !isHidden
                            updateTimerVisibility()
                            if (isHidden) {
                                handler.postDelayed({
                                    isHidden = false
                                    updateTimerVisibility()
                                }, TAP_HIDE_DURATION_MS)
                            }
                        } else if (isDragging) {
                            saveCustomPositionForLevel(
                                currentLevel,
                                currentLayoutParams!!.x,
                                currentLayoutParams!!.y
                            )
                        }
                        isDragging = false
                        return true
                    }
                }
                return false
            }
        }
}
