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
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
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
        private const val TAP_HIDE_DURATION_MS = 5000L
        private const val TRANSLUCENT_BG = "#80000000"

        // If a drag ends within this many dp of an edge, the corresponding
        // axis is snapped flush with that edge. Catches "I meant the edge"
        // gestures that fingers can't fully reach because of system bars
        // (status bar ≈ 24dp on most devices). Without this, a 3–5% saved
        // fraction becomes a small but visible gap after rotation.
        private const val EDGE_SNAP_DP = 32f
    }

    // Platform-canonical "user definitely meant to drag" threshold. Density-
    // aware and calibrated per device (~8dp on most phones), so a stray
    // fingertip wobble during a tap-to-hide won't flip the level into custom
    // position mode. Was a hard-coded 10 raw pixels, which is ~3dp on a 3x
    // device — effectively no threshold at all.
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

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

        // The initial applyPositionForLevel above ran with overlayView.width
        // and .height = 0, so for custom positions it had to fall back to
        // using just screen size as the denominator. Once the first layout
        // pass completes we know the overlay's real measured size — migrate
        // any legacy absolute-pixel prefs and re-apply with the correct math.
        overlayView?.viewTreeObserver?.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    overlayView?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                    if (!isCreated()) return
                    migrateLegacyIfNeeded()
                    applyPositionForLevel(currentLevel, currentPositionString)
                    updateLayout()
                }
            }
        )
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

        // 6. Display-mode visibility. NEVER hides the overlay entirely; the
        // service keeps ticking so the widget and analytics stay accurate.
        val shouldShow = when (settings.timerDisplayMode) {
            "never" -> false
            "interval" -> {
                val currentMinute = (seconds / 60) % settings.timerDisplayIntervalMinutes
                currentMinute == 0 && (seconds % 60) < settings.timerDisplayDurationSeconds
            }
            else -> true
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
    // Custom drag positions are stored as a fraction [0f, 1f] of the *available
    // range* — the screen size minus the overlay's own size — so fx=1.0 means
    // "the overlay's right edge sits flush with the screen's right edge" in
    // any orientation. Storing as a fraction of just the screen would leave
    // a corner-pinned overlay short by overlay-width pixels when rotated to
    // a wider screen (the right edge of the overlay no longer reaches the
    // right edge of the screen).
    //
    // Legacy prefs migration:
    //   • [LEGACY_LEVEL_*_X/_Y] — absolute pixels from the pre-rotation-fix
    //     versions; migrated to the new fraction format in
    //     [migrateLegacyIfNeeded], which runs from the first layout pass when
    //     the overlay's measured width/height is finally known.
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
     * One-shot per-level migration of legacy absolute-pixel prefs
     * ([LEGACY_LEVEL_*_X/_Y]) into the new fraction format. Requires the
     * overlay to be laid out so that overlay.width/height are non-zero —
     * called from the first-layout listener in [create].
     *
     * If overlay isn't measured yet, this is a no-op; legacy values stay in
     * prefs and [applyPositionForLevel] uses them directly until the next
     * layout pass picks them up.
     */
    private fun migrateLegacyIfNeeded() {
        val ow = overlayView?.width ?: 0
        val oh = overlayView?.height ?: 0
        if (ow == 0 || oh == 0) return
        val (sw, sh) = currentScreenSize()
        val rangeW = (sw - ow).coerceAtLeast(1)
        val rangeH = (sh - oh).coerceAtLeast(1)
        val editor = prefs.edit()
        var dirty = false
        for (level in 1..3) {
            val keys = customKeysFor(level)
            if (!prefs.contains(keys.fxKey) && prefs.contains(keys.legacyXKey)) {
                val oldX = prefs.getInt(keys.legacyXKey, 0)
                val oldY = prefs.getInt(keys.legacyYKey, 0)
                val fx = (oldX.toFloat() / rangeW).coerceIn(0f, 1f)
                val fy = (oldY.toFloat() / rangeH).coerceIn(0f, 1f)
                editor.putFloat(keys.fxKey, fx)
                    .putFloat(keys.fyKey, fy)
                    .remove(keys.legacyXKey)
                    .remove(keys.legacyYKey)
                dirty = true
            }
        }
        if (dirty) editor.apply()
    }

    private fun applyPositionForLevel(level: Int, positionString: String) {
        val keys = customKeysFor(level)
        if (prefs.getBoolean(keys.useKey, false)) {
            val (sw, sh) = currentScreenSize()
            val ow = overlayView?.width ?: 0
            val oh = overlayView?.height ?: 0
            val rangeW = (sw - ow).coerceAtLeast(1)
            val rangeH = (sh - oh).coerceAtLeast(1)

            var px: Int
            var py: Int
            if (prefs.contains(keys.fxKey)) {
                // Current format: fraction of (screen − overlay).
                val fx = prefs.getFloat(keys.fxKey, 0f)
                val fy = prefs.getFloat(keys.fyKey, 0f)
                px = (fx * rangeW).toInt()
                py = (fy * rangeH).toInt()
            } else if (prefs.contains(keys.legacyXKey)) {
                // Legacy absolute pixels from older app versions. Apply
                // directly so the position stays visually stable until
                // [migrateLegacyIfNeeded] converts them on first layout.
                px = prefs.getInt(keys.legacyXKey, 0)
                py = prefs.getInt(keys.legacyYKey, 0)
            } else {
                px = 0
                py = 0
            }

            // Clamp so the overlay stays on-screen if its measured size has
            // changed (e.g. larger font on a higher level pushes the right
            // edge past the screen edge). overlayView dimensions are 0 until
            // first layout — skip clamping then.
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
        // Use (screen − overlay) as the denominator so fx=1.0 means the
        // overlay's right edge is flush with the screen's right edge — the
        // invariant that keeps corner-pinned positions corner-pinned across
        // rotation. overlayView is laid out by the time the user drags it,
        // so ow/oh are non-zero here.
        val ow = overlayView?.width ?: 0
        val oh = overlayView?.height ?: 0
        val rangeW = (sw - ow).coerceAtLeast(1)
        val rangeH = (sh - oh).coerceAtLeast(1)

        val snapPx = (EDGE_SNAP_DP * context.resources.displayMetrics.density).toInt()
        val fx = when {
            x < snapPx -> 0f
            x > rangeW - snapPx -> 1f
            else -> (x.toFloat() / rangeW).coerceIn(0f, 1f)
        }
        val fy = when {
            y < snapPx -> 0f
            y > rangeH - snapPx -> 1f
            else -> (y.toFloat() / rangeH).coerceIn(0f, 1f)
        }

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
                        // Use the overlay's actual on-screen position rather
                        // than the raw layout-params x/y. For preset gravities
                        // (e.g. TOP|END "Top Right") the params hold x=0/y=0
                        // and the visible position is derived from gravity, so
                        // using them directly would teleport the overlay to
                        // (0, 0) the moment ACTION_MOVE switches gravity to
                        // TOP|START. getLocationOnScreen gives the true pixel
                        // offset, which converts cleanly.
                        val loc = IntArray(2)
                        overlayView?.getLocationOnScreen(loc)
                        initialX = loc[0]
                        initialY = loc[1]
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) {
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
                            // The save may have snapped to an edge — re-apply
                            // immediately so the overlay visually moves to the
                            // snap point on release instead of waiting up to a
                            // second for the next render tick to notice.
                            applyPositionForLevel(currentLevel, currentPositionString)
                            lastAppliedPositionKey =
                                positionKeyFor(currentLevel, currentPositionString)
                            updateLayout()
                        }
                        isDragging = false
                        return true
                    }
                }
                return false
            }
        }
}
