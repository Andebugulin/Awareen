package com.example.screentimetracker

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Calendar
import android.util.Log
import android.util.TypedValue
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.os.PowerManager
import com.example.screentimetracker.AppSettings
import kotlin.math.abs

class ScreenTimeService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var timeTextView: TextView? = null
    private lateinit var prefs: SharedPreferences
    private var screenTimeSeconds = 0
    private var isScreenOn = true
    private var isDeviceUnlocked = false
    private val handler = Handler(Looper.getMainLooper())
    private var resetTimeJob: Runnable? = null

    // Drag functionality variables
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isHidden = false
    private val CLICK_THRESHOLD = 10f // pixels

    // Per-level custom position keys
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
    }

    // Timer display customization
    private var timerDisplayMode: String = AppSettings.DEFAULT_TIMER_DISPLAY_MODE
    private var timerDisplayIntervalMinutes: Int = AppSettings.DEFAULT_TIMER_DISPLAY_INTERVAL_MINUTES
    private var timerDisplayDurationSeconds: Int = AppSettings.DEFAULT_TIMER_DISPLAY_DURATION_SECONDS
    private var isTimerCurrentlyVisible = true
    private var timerVisibilityJob: Runnable? = null

    // Settings variables
    private var level1MaxTimeSeconds: Int = AppSettings.DEFAULT_LEVEL_1_MAX_TIME_SECONDS
    private var level1Color: Int = AppSettings.DEFAULT_LEVEL_1_COLOR
    private var level1Position: String = AppSettings.DEFAULT_LEVEL_1_POSITION
    private var level1FontSize: Float = AppSettings.DEFAULT_LEVEL_1_FONT_SIZE.toFloat()
    private var level1BlinkingEnabled: Boolean = AppSettings.DEFAULT_LEVEL_1_BLINKING_ENABLED

    private var level2DurationSeconds: Int = AppSettings.DEFAULT_LEVEL_2_DURATION_SECONDS
    private var level2Color: Int = AppSettings.DEFAULT_LEVEL_2_COLOR
    private var level2Position: String = AppSettings.DEFAULT_LEVEL_2_POSITION
    private var level2FontSize: Float = AppSettings.DEFAULT_LEVEL_2_FONT_SIZE.toFloat()
    private var level2EndTimeSeconds: Int = 0
    private var level2BlinkingEnabled: Boolean = AppSettings.DEFAULT_LEVEL_2_BLINKING_ENABLED

    private var level3Color: Int = AppSettings.DEFAULT_LEVEL_3_COLOR
    private var level3Position: String = AppSettings.DEFAULT_LEVEL_3_POSITION
    private var level3FontSize: Float = AppSettings.DEFAULT_LEVEL_3_FONT_SIZE.toFloat()

    private var currentResetHour: Int = AppSettings.DEFAULT_RESET_HOUR
    private var currentResetMinute: Int = AppSettings.DEFAULT_RESET_MINUTE
    private var level3BlinkingEnabled: Boolean = AppSettings.DEFAULT_LEVEL_3_BLINKING_ENABLED

    private var currentLayoutParams: WindowManager.LayoutParams? = null
    private val TAG = "ScreenTimeService"

    private var currentLevel = 1 // Track which level we're currently in

    private val screenTimeUpdateRunnable = object : Runnable {
        override fun run() {
            val actuallyUnlocked = isScreenActuallyOnAndUnlocked()

            if (actuallyUnlocked) {
                isScreenOn = true
                isDeviceUnlocked = true

                screenTimeSeconds++

                if (timerDisplayMode == "interval") {
                    val currentMinute = (screenTimeSeconds / 60) % timerDisplayIntervalMinutes
                    val shouldShow = currentMinute == 0 && (screenTimeSeconds % 60) < timerDisplayDurationSeconds

                    if (shouldShow != isTimerCurrentlyVisible) {
                        isTimerCurrentlyVisible = shouldShow
                        updateTimerVisibility()
                    }
                }

                updateTimeDisplay()
                saveScreenTime()
                saveAnalyticsData()

                handler.postDelayed(this, 1000)
            } else {
                isScreenOn = false
                isDeviceUnlocked = false
                saveScreenTime()
                handler.postDelayed(this, 2000)
            }
        }
    }

    private val keyEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    isDeviceUnlocked = false
                    saveScreenTime()
                }

                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    checkDateChangeAndReset()
                }

                Intent.ACTION_USER_PRESENT -> {
                    isDeviceUnlocked = true
                    isScreenOn = true
                    handler.removeCallbacks(screenTimeUpdateRunnable)
                    handler.post(screenTimeUpdateRunnable)
                }
            }
        }
    }

    private val settingsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AppSettings.ACTION_SETTINGS_UPDATED) {
                Log.d(TAG, "Received settings update broadcast, reloading settings.")
                loadSettings()
            }
        }
    }

    private val timeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                    Log.d(TAG, "Time or date changed, reloading settings and rescheduling reset time")
                    loadSettings()
                    scheduleResetTime()
                    checkDateChangeAndReset()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            loadSettings()

            checkDateChangeAndReset()
            loadScreenTime()

            val keyFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(keyEventReceiver, keyFilter, RECEIVER_NOT_EXPORTED)

            val timeFilter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            registerReceiver(timeChangedReceiver, timeFilter)

            val settingsFilter = IntentFilter(AppSettings.ACTION_SETTINGS_UPDATED)
            registerReceiver(settingsUpdateReceiver, settingsFilter, RECEIVER_NOT_EXPORTED)

            createNotificationChannel()
            val notification = NotificationCompat.Builder(this, "screen_time_channel")
                .setContentTitle("Screen Time Tracker")
                .setContentText("Tracking your screen time")
                .setSmallIcon(R.drawable.ic_timer)
                .build()
            startForeground(1, notification)

            createOverlay()

            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            isDeviceUnlocked = !keyguardManager.isKeyguardLocked

            handler.removeCallbacks(screenTimeUpdateRunnable)
            handler.post(screenTimeUpdateRunnable)
            scheduleResetTime()
            Log.d(TAG, "Service created successfully. Initial screenTimeSeconds: $screenTimeSeconds")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
        }
    }

    private fun loadSettings() {
        level1MaxTimeSeconds = prefs.getInt(AppSettings.LEVEL_1_MAX_TIME_SECONDS, AppSettings.DEFAULT_LEVEL_1_MAX_TIME_SECONDS)
        level1Color = prefs.getInt(AppSettings.LEVEL_1_COLOR, AppSettings.DEFAULT_LEVEL_1_COLOR)
        level1Position = prefs.getString(AppSettings.LEVEL_1_POSITION, AppSettings.DEFAULT_LEVEL_1_POSITION) ?: AppSettings.DEFAULT_LEVEL_1_POSITION
        level1FontSize = prefs.getInt(AppSettings.LEVEL_1_FONT_SIZE, AppSettings.DEFAULT_LEVEL_1_FONT_SIZE).toFloat()
        level1BlinkingEnabled = prefs.getBoolean(AppSettings.LEVEL_1_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_1_BLINKING_ENABLED)

        level2DurationSeconds = prefs.getInt(AppSettings.LEVEL_2_DURATION_SECONDS, AppSettings.DEFAULT_LEVEL_2_DURATION_SECONDS)
        level2Color = prefs.getInt(AppSettings.LEVEL_2_COLOR, AppSettings.DEFAULT_LEVEL_2_COLOR)
        level2Position = prefs.getString(AppSettings.LEVEL_2_POSITION, AppSettings.DEFAULT_LEVEL_2_POSITION) ?: AppSettings.DEFAULT_LEVEL_2_POSITION
        level2FontSize = prefs.getInt(AppSettings.LEVEL_2_FONT_SIZE, AppSettings.DEFAULT_LEVEL_2_FONT_SIZE).toFloat()
        level2EndTimeSeconds = level1MaxTimeSeconds + level2DurationSeconds
        level2BlinkingEnabled = prefs.getBoolean(AppSettings.LEVEL_2_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_2_BLINKING_ENABLED)

        level3Color = prefs.getInt(AppSettings.LEVEL_3_COLOR, AppSettings.DEFAULT_LEVEL_3_COLOR)
        level3Position = prefs.getString(AppSettings.LEVEL_3_POSITION, AppSettings.DEFAULT_LEVEL_3_POSITION) ?: AppSettings.DEFAULT_LEVEL_3_POSITION
        level3FontSize = prefs.getInt(AppSettings.LEVEL_3_FONT_SIZE, AppSettings.DEFAULT_LEVEL_3_FONT_SIZE).toFloat()

        currentResetHour = prefs.getInt(AppSettings.RESET_HOUR, AppSettings.DEFAULT_RESET_HOUR)
        currentResetMinute = prefs.getInt(AppSettings.RESET_MINUTE, AppSettings.DEFAULT_RESET_MINUTE)

        level3BlinkingEnabled = prefs.getBoolean(AppSettings.LEVEL_3_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_3_BLINKING_ENABLED)

        timerDisplayMode = prefs.getString(AppSettings.TIMER_DISPLAY_MODE, AppSettings.DEFAULT_TIMER_DISPLAY_MODE) ?: AppSettings.DEFAULT_TIMER_DISPLAY_MODE
        timerDisplayIntervalMinutes = prefs.getInt(AppSettings.TIMER_DISPLAY_INTERVAL_MINUTES, AppSettings.DEFAULT_TIMER_DISPLAY_INTERVAL_MINUTES)
        timerDisplayDurationSeconds = prefs.getInt(AppSettings.TIMER_DISPLAY_DURATION_SECONDS, AppSettings.DEFAULT_TIMER_DISPLAY_DURATION_SECONDS)

        Log.d(TAG, "Settings loaded: Timer Display Mode=$timerDisplayMode, Interval=${timerDisplayIntervalMinutes}min, Duration=${timerDisplayDurationSeconds}s")

        if (overlayView != null && windowManager != null) {
            updateOverlayLayoutParams(getCurrentPositionString(), true)
            updateTimeDisplay()
            updateTimerVisibility()
        }
    }

    private fun updateTimerVisibility() {
        if (isHidden) {
            overlayView?.visibility = View.GONE
            return
        }

        val shouldBeVisible = when {
            timerDisplayMode == "always" -> true
            timerDisplayMode == "interval" -> isTimerCurrentlyVisible
            else -> true
        }

        overlayView?.visibility = if (shouldBeVisible) View.VISIBLE else View.GONE
    }

    private fun saveAnalyticsData() {
        val calendar = Calendar.getInstance()
        val dateKey = "analytics_${calendar.get(Calendar.YEAR)}_${calendar.get(Calendar.MONTH)}_${calendar.get(Calendar.DAY_OF_MONTH)}"
        val hourKey = "${dateKey}_hour_${calendar.get(Calendar.HOUR_OF_DAY)}"

        prefs.edit().putInt(dateKey, screenTimeSeconds).apply()
        val currentHourTime = prefs.getInt(hourKey, 0)
        prefs.edit().putInt(hourKey, currentHourTime + 1).apply()

        val existingDates = prefs.getStringSet("analytics_dates", mutableSetOf()) ?: mutableSetOf()
        existingDates.add(dateKey)
        prefs.edit().putStringSet("analytics_dates", existingDates).apply()
    }

    private fun getCurrentPositionString(): String {
        return when {
            screenTimeSeconds < level1MaxTimeSeconds -> level1Position
            screenTimeSeconds < level2EndTimeSeconds -> level2Position
            else -> level3Position
        }
    }

    private fun getCurrentLevel(): Int {
        return when {
            screenTimeSeconds < level1MaxTimeSeconds -> 1
            screenTimeSeconds < level2EndTimeSeconds -> 2
            else -> 3
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "screen_time_channel",
                "Screen Time Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        currentLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        // Set initial position based on level 1
        applyPositionForLevel(1)

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        timeTextView = overlayView?.findViewById(R.id.timeTextView)

        // Setup touch listener for drag and tap functionality
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
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
                            // It's a tap - toggle visibility
                            isHidden = !isHidden
                            updateTimerVisibility()

                            if (isHidden) {
                                handler.postDelayed({
                                    isHidden = false
                                    updateTimerVisibility()
                                }, 5000)
                            }
                        } else if (isDragging) {
                            // Save custom position for current level
                            val level = getCurrentLevel()
                            saveCustomPositionForLevel(level, currentLayoutParams!!.x, currentLayoutParams!!.y)
                        }

                        isDragging = false
                        return true
                    }
                }
                return false
            }
        })

        try {
            if (overlayView != null && currentLayoutParams != null) {
                windowManager?.addView(overlayView, currentLayoutParams)
                updateTimeDisplay()
                updateTimerVisibility()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view: ${e.message}", e)
        }
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

    private fun applyPositionForLevel(level: Int) {
        val (useCustomKey, xKey, yKey, positionString) = when (level) {
            1 -> listOf(LEVEL_1_USE_CUSTOM, LEVEL_1_CUSTOM_X, LEVEL_1_CUSTOM_Y, level1Position)
            2 -> listOf(LEVEL_2_USE_CUSTOM, LEVEL_2_CUSTOM_X, LEVEL_2_CUSTOM_Y, level2Position)
            else -> listOf(LEVEL_3_USE_CUSTOM, LEVEL_3_CUSTOM_X, LEVEL_3_CUSTOM_Y, level3Position)
        }

        val useCustom = prefs.getBoolean(useCustomKey as String, false)

        if (useCustom) {
            currentLayoutParams?.gravity = Gravity.TOP or Gravity.START
            currentLayoutParams?.x = prefs.getInt(xKey as String, 0)
            currentLayoutParams?.y = prefs.getInt(yKey as String, 0)
            Log.d(TAG, "Applied custom position for Level $level")
        } else {
            currentLayoutParams?.gravity = getGravityForPosition(positionString as String)
            currentLayoutParams?.x = 0
            currentLayoutParams?.y = 0
            Log.d(TAG, "Applied default position for Level $level: $positionString")
        }
    }

    private fun getGravityForPosition(positionString: String): Int {
        return when (positionString) {
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
    }

    private fun updateTimeDisplay() {
        checkDateChangeAndReset()

        val hours = screenTimeSeconds / 3600
        val minutes = (screenTimeSeconds % 3600) / 60
        val seconds = screenTimeSeconds % 60
        val timeText = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        timeTextView?.text = timeText

        val newLevel = getCurrentLevel()

        // Check if level changed
        if (newLevel != currentLevel) {
            currentLevel = newLevel
            applyPositionForLevel(currentLevel)
            if (overlayView?.parent != null) {
                try {
                    windowManager?.updateViewLayout(overlayView, currentLayoutParams)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating overlay on level change: ${e.message}", e)
                }
            }
        }

        when {
            screenTimeSeconds < level1MaxTimeSeconds -> {
                timeTextView?.setTextColor(level1Color)
                overlayView?.setBackgroundColor(Color.parseColor("#80000000"))
                timeTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, level1FontSize)
                if (level1BlinkingEnabled) {
                    startBlinking()
                } else {
                    stopBlinking()
                }
            }
            screenTimeSeconds < level2EndTimeSeconds -> {
                timeTextView?.setTextColor(level2Color)
                overlayView?.setBackgroundColor(Color.parseColor("#80000000"))
                timeTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, level2FontSize)
                if (level2BlinkingEnabled) {
                    startBlinking()
                } else {
                    stopBlinking()
                }
            }
            else -> {
                timeTextView?.setTextColor(level3Color)
                overlayView?.setBackgroundColor(Color.parseColor("#80000000"))
                timeTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, level3FontSize)
                if (level3BlinkingEnabled) {
                    startBlinking()
                } else {
                    stopBlinking()
                }
            }
        }
    }

    private fun isScreenActuallyOnAndUnlocked(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }

        val isLocked = keyguardManager.isKeyguardLocked

        return isScreenOn && !isLocked
    }

    private var isBlinking = false
    private val blinkingRunnable = object : Runnable {
        override fun run() {
            val shouldBlink = when {
                screenTimeSeconds < level1MaxTimeSeconds -> level1BlinkingEnabled
                screenTimeSeconds < level2EndTimeSeconds -> level2BlinkingEnabled
                else -> level3BlinkingEnabled
            }

            if (shouldBlink && isBlinking) {
                val seconds = screenTimeSeconds % 60
                val isEvenSecond = seconds % 2 == 0

                if (isEvenSecond) {
                    timeTextView?.setTextColor(Color.BLACK)
                    overlayView?.setBackgroundColor(when {
                        screenTimeSeconds < level1MaxTimeSeconds -> level1Color
                        screenTimeSeconds < level2EndTimeSeconds -> level2Color
                        else -> level3Color
                    })
                } else {
                    val currentColor = when {
                        screenTimeSeconds < level1MaxTimeSeconds -> level1Color
                        screenTimeSeconds < level2EndTimeSeconds -> level2Color
                        else -> level3Color
                    }
                    timeTextView?.setTextColor(currentColor)
                    overlayView?.setBackgroundColor(Color.parseColor("#80000000"))
                }
                handler.postDelayed(this, 500)
            } else {
                stopBlinking()
            }
        }
    }

    private fun startBlinking() {
        if (!isBlinking) {
            isBlinking = true
            handler.removeCallbacks(blinkingRunnable)
            handler.post(blinkingRunnable)
        }
    }

    private fun stopBlinking() {
        if (isBlinking) {
            isBlinking = false
            handler.removeCallbacks(blinkingRunnable)
            val currentColor = when {
                screenTimeSeconds < level1MaxTimeSeconds -> level1Color
                screenTimeSeconds < level2EndTimeSeconds -> level2Color
                else -> level3Color
            }
            timeTextView?.setTextColor(currentColor)
            overlayView?.setBackgroundColor(Color.parseColor("#80000000"))
        }
    }

    private fun scheduleResetTime() {
        resetTimeJob?.let {
            handler.removeCallbacks(it)
        }

        val calendar = Calendar.getInstance()
        val now = calendar.timeInMillis
        calendar.apply {
            set(Calendar.HOUR_OF_DAY, currentResetHour)
            set(Calendar.MINUTE, currentResetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (calendar.timeInMillis <= now) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val delayMillis = calendar.timeInMillis - now

        resetTimeJob = Runnable {
            screenTimeSeconds = 0
            loadSettings()
            updateTimeDisplay()
            saveScreenTime()
            scheduleResetTime()
        }
        handler.postDelayed(resetTimeJob!!, delayMillis)
    }

    private fun updateOverlayLayoutParams(positionString: String, forceUpdate: Boolean = false) {
        // This method is kept for compatibility but position updates are now handled in updateTimeDisplay
        if (forceUpdate) {
            val level = getCurrentLevel()
            applyPositionForLevel(level)
            if (overlayView?.parent != null) {
                try {
                    windowManager?.updateViewLayout(overlayView, currentLayoutParams)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating overlay layout: ${e.message}", e)
                }
            }
        }
    }

    private fun saveScreenTime() {
        val todayKey = getTodayDateKey()
        prefs.edit().putInt(todayKey, screenTimeSeconds).apply()
        prefs.edit().putString("last_date_key", todayKey).apply()

        val nextResetTime = getNextResetTimeMillis()
        prefs.edit().putLong("next_reset_timestamp", nextResetTime).apply()
    }

    private fun getNextResetTimeMillis(): Long {
        val calendar = Calendar.getInstance()
        val now = calendar.timeInMillis
        calendar.apply {
            set(Calendar.HOUR_OF_DAY, currentResetHour)
            set(Calendar.MINUTE, currentResetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (timeInMillis <= now) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return calendar.timeInMillis
    }

    private fun loadScreenTime() {
        val todayKey = getTodayDateKey()
        screenTimeSeconds = prefs.getInt(todayKey, 0)
    }

    private fun getTodayDateKey(): String {
        val calendar = Calendar.getInstance()
        return "screen_time_${calendar.get(Calendar.YEAR)}_${calendar.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun checkDateChangeAndReset() {
        val now = System.currentTimeMillis()
        val nextResetTimestamp = prefs.getLong("next_reset_timestamp", 0L)

        if (nextResetTimestamp > 0 && now >= nextResetTimestamp) {
            Log.d(TAG, "Reset time reached, resetting counter")
            screenTimeSeconds = 0
            saveScreenTime()
        } else if (nextResetTimestamp == 0L) {
            saveScreenTime()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(screenTimeUpdateRunnable)
        handler.removeCallbacks(blinkingRunnable)
        resetTimeJob?.let {
            handler.removeCallbacks(it)
        }
        saveScreenTime()

        try {
            unregisterReceiver(keyEventReceiver)
            unregisterReceiver(timeChangedReceiver)
            unregisterReceiver(settingsUpdateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers: ${e.message}", e)
        }

        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view: ${e.message}", e)
            } finally {
                overlayView = null
                windowManager = null
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}