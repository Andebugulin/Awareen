package com.andebugulin.awareen

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

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
    }

    // =========================================================================
    // OVERLAY STATE
    // =========================================================================

    internal var overlayView: View? = null
        private set
    internal var timeTextView: TextView? = null
        private set
    private var windowManager: WindowManager? = null
    internal var currentLayoutParams: WindowManager.LayoutParams? = null
        private set

    var currentLevel = 1
        internal set

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isHidden = false

    // Updated by service when settings change
    var timerDisplayMode: String = AppSettings.DEFAULT_TIMER_DISPLAY_MODE
    private var isIntervalVisible = true

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    fun isCreated(): Boolean = overlayView != null && windowManager != null

    fun create(
        handler: Handler,
        level1Position: String,
        getCurrentLevel: () -> Int,
        onRenderNeeded: () -> Unit,
    ) {
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

        overlayView?.setOnTouchListener(buildTouchListener(handler, getCurrentLevel))

        try {
            if (overlayView != null && currentLayoutParams != null) {
                windowManager?.addView(overlayView, currentLayoutParams)
                onRenderNeeded()
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

    // =========================================================================
    // VISIBILITY
    // =========================================================================

    fun setIntervalVisible(shouldShow: Boolean) {
        if (shouldShow == isIntervalVisible) return
        isIntervalVisible = shouldShow
        updateTimerVisibility()
    }

    fun updateTimerVisibility() {
        if (isHidden) {
            overlayView?.visibility = View.GONE
            return
        }
        val shouldBeVisible = timerDisplayMode == "always" || isIntervalVisible
        overlayView?.visibility = if (shouldBeVisible) View.VISIBLE else View.GONE
    }

    // =========================================================================
    // POSITION
    // =========================================================================

    internal fun applyPositionForLevel(level: Int, positionString: String) {
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
        Log.d(TAG, "Applied position for Level $level: $positionString")
    }

    internal fun updateLayout() {
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
        Log.d(TAG, "Saved custom position for Level $level: x=$x, y=$y")
    }

    // =========================================================================
    // TOUCH LISTENER
    // =========================================================================

    private fun buildTouchListener(
        handler: Handler,
        getCurrentLevel: () -> Int,
    ): View.OnTouchListener = object : View.OnTouchListener {
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
                            }, 5000)
                        }
                    } else if (isDragging) {
                        saveCustomPositionForLevel(
                            getCurrentLevel(),
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
