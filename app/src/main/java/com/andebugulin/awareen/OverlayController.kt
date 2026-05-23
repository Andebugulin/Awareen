package com.andebugulin.awareen

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

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
 * Full snapshot of overlay-related settings. Built fresh by the service on
 * every render call — cheap to allocate, keeps the controller stateless w.r.t.
 * configuration.
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

class OverlayController(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    companion object {
        const val LEVEL_1_USE_CUSTOM = "level_1_use_custom_position"
        const val LEVEL_1_CUSTOM_X = "level_1_custom_position_x"
        const val LEVEL_1_CUSTOM_Y = "level_1_custom_position_y"

        const val LEVEL_2_USE_CUSTOM = "level_2_use_custom_position"
        const val LEVEL_2_CUSTOM_X = "level_2_custom_position_x"
        const val LEVEL_2_CUSTOM_Y = "level_2_custom_position_y"

        const val LEVEL_3_USE_CUSTOM = "level_3_use_custom_position"
        const val LEVEL_3_CUSTOM_X = "level_3_custom_position_x"
        const val LEVEL_3_CUSTOM_Y = "level_3_custom_position_y"

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
            lastAppliedPositionKey = positionKey
            applyPositionForLevel(newLevel, levelSettings.position)
            updateLayout()
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
    // =========================================================================

    private fun applyPositionForLevel(level: Int, positionString: String) {
        val (useCustomKey, xKey, yKey) = when (level) {
            1 -> Triple(LEVEL_1_USE_CUSTOM, LEVEL_1_CUSTOM_X, LEVEL_1_CUSTOM_Y)
            2 -> Triple(LEVEL_2_USE_CUSTOM, LEVEL_2_CUSTOM_X, LEVEL_2_CUSTOM_Y)
            else -> Triple(LEVEL_3_USE_CUSTOM, LEVEL_3_CUSTOM_X, LEVEL_3_CUSTOM_Y)
        }

        if (prefs.getBoolean(useCustomKey, false)) {
            currentLayoutParams?.gravity = Gravity.TOP or Gravity.START
            currentLayoutParams?.x = prefs.getInt(xKey, 0)
            currentLayoutParams?.y = prefs.getInt(yKey, 0)
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
     * layout params.
     */
    private fun positionKeyFor(level: Int, positionString: String): String {
        val (useKey, xKey, yKey) = when (level) {
            1 -> Triple(LEVEL_1_USE_CUSTOM, LEVEL_1_CUSTOM_X, LEVEL_1_CUSTOM_Y)
            2 -> Triple(LEVEL_2_USE_CUSTOM, LEVEL_2_CUSTOM_X, LEVEL_2_CUSTOM_Y)
            else -> Triple(LEVEL_3_USE_CUSTOM, LEVEL_3_CUSTOM_X, LEVEL_3_CUSTOM_Y)
        }
        return if (prefs.getBoolean(useKey, false)) {
            "custom:${prefs.getInt(xKey, 0)}:${prefs.getInt(yKey, 0)}"
        } else {
            "preset:$positionString"
        }
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
        val (useCustomKey, xKey, yKey) = when (level) {
            1 -> Triple(LEVEL_1_USE_CUSTOM, LEVEL_1_CUSTOM_X, LEVEL_1_CUSTOM_Y)
            2 -> Triple(LEVEL_2_USE_CUSTOM, LEVEL_2_CUSTOM_X, LEVEL_2_CUSTOM_Y)
            else -> Triple(LEVEL_3_USE_CUSTOM, LEVEL_3_CUSTOM_X, LEVEL_3_CUSTOM_Y)
        }
        prefs.edit()
            .putBoolean(useCustomKey, true)
            .putInt(xKey, x)
            .putInt(yKey, y)
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
