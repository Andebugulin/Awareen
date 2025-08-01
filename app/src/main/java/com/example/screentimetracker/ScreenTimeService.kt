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
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Calendar
import android.util.Log
import android.util.TypedValue
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.os.FileObserver

import com.example.screentimetracker.AppSettings

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

    private var level2DurationSeconds: Int = AppSettings.DEFAULT_LEVEL_2_DURATION_SECONDS
    private var level2Color: Int = AppSettings.DEFAULT_LEVEL_2_COLOR
    private var level2Position: String = AppSettings.DEFAULT_LEVEL_2_POSITION
    private var level2FontSize: Float = AppSettings.DEFAULT_LEVEL_2_FONT_SIZE.toFloat()
    private var level2EndTimeSeconds: Int = 0

    private var level3Color: Int = AppSettings.DEFAULT_LEVEL_3_COLOR
    private var level3Position: String = AppSettings.DEFAULT_LEVEL_3_POSITION
    private var level3FontSize: Float = AppSettings.DEFAULT_LEVEL_3_FONT_SIZE.toFloat()

    private var currentResetHour: Int = AppSettings.DEFAULT_RESET_HOUR
    private var currentResetMinute: Int = AppSettings.DEFAULT_RESET_MINUTE
    private var level3BlinkingEnabled: Boolean = AppSettings.DEFAULT_LEVEL_3_BLINKING_ENABLED

    private var currentLayoutParams: WindowManager.LayoutParams? = null
    private val TAG = "ScreenTimeService"

    private val screenTimeUpdateRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn && isDeviceUnlocked) {
                screenTimeSeconds++
                updateTimeDisplay()
                saveScreenTime()
                saveAnalyticsData()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val timerVisibilityUpdateRunnable = object : Runnable {
        override fun run() {
            if (timerDisplayMode == "interval") {
                val currentMinute = (screenTimeSeconds / 60) % timerDisplayIntervalMinutes
                val shouldShow = currentMinute == 0 && (screenTimeSeconds % 60) < timerDisplayDurationSeconds

                if (shouldShow != isTimerCurrentlyVisible) {
                    isTimerCurrentlyVisible = shouldShow
                    updateTimerVisibility()
                }
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val keyEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    isDeviceUnlocked = false
                    handler.removeCallbacks(screenTimeUpdateRunnable)
                    handler.removeCallbacks(timerVisibilityUpdateRunnable)
                    saveScreenTime()
                }

                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    if (isDeviceUnlocked) {
                        handler.post(screenTimeUpdateRunnable)
                        if (timerDisplayMode == "interval") {
                            handler.post(timerVisibilityUpdateRunnable)
                        }
                    }
                    checkDateChangeAndReset()
                }

                Intent.ACTION_USER_PRESENT -> {
                    isDeviceUnlocked = true
                    if (isScreenOn) {
                        handler.post(screenTimeUpdateRunnable)
                        if (timerDisplayMode == "interval") {
                            handler.post(timerVisibilityUpdateRunnable)
                        }
                    }
                }
            }
        }
    }


    // Settings update receiver
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

            if (isScreenOn && isDeviceUnlocked) {
                handler.post(screenTimeUpdateRunnable)
                if (timerDisplayMode == "interval") {
                    handler.post(timerVisibilityUpdateRunnable)
                }
            }
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

        level2DurationSeconds = prefs.getInt(AppSettings.LEVEL_2_DURATION_SECONDS, AppSettings.DEFAULT_LEVEL_2_DURATION_SECONDS)
        level2Color = prefs.getInt(AppSettings.LEVEL_2_COLOR, AppSettings.DEFAULT_LEVEL_2_COLOR)
        level2Position = prefs.getString(AppSettings.LEVEL_2_POSITION, AppSettings.DEFAULT_LEVEL_2_POSITION) ?: AppSettings.DEFAULT_LEVEL_2_POSITION
        level2FontSize = prefs.getInt(AppSettings.LEVEL_2_FONT_SIZE, AppSettings.DEFAULT_LEVEL_2_FONT_SIZE).toFloat()
        level2EndTimeSeconds = level1MaxTimeSeconds + level2DurationSeconds

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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        currentLayoutParams?.gravity = getGravityForPosition(level1Position)

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        timeTextView = overlayView?.findViewById(R.id.timeTextView)

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
        val hours = screenTimeSeconds / 3600
        val minutes = (screenTimeSeconds % 3600) / 60
        val seconds = screenTimeSeconds % 60
        val timeText = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        timeTextView?.text = timeText

        when {
            screenTimeSeconds < level1MaxTimeSeconds -> {
                timeTextView?.setTextColor(level1Color)
                overlayView?.setBackgroundColor(Color.parseColor("#80000000"))
                timeTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, level1FontSize)
                updateOverlayLayoutParams(level1Position)
                overlayView?.clearAnimation()
                handler.removeCallbacks(blinkingRunnable)
            }
            screenTimeSeconds < level2EndTimeSeconds -> {
                timeTextView?.setTextColor(level2Color)
                overlayView?.setBackgroundColor(Color.parseColor("#80000000"))
                timeTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, level2FontSize)
                updateOverlayLayoutParams(level2Position)
                overlayView?.clearAnimation()
                handler.removeCallbacks(blinkingRunnable)
            }
            else -> {
                timeTextView?.setTextColor(level3Color)
                overlayView?.setBackgroundColor(Color.parseColor("#80000000"))
                timeTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, level3FontSize)
                updateOverlayLayoutParams(level3Position)

                if (level3BlinkingEnabled) {
                    startBlinking()
                } else {
                    stopBlinking()
                }
            }
        }
    }

    private var isBlinking = false
    private val blinkingRunnable = object : Runnable {
        override fun run() {
            if (screenTimeSeconds >= level2EndTimeSeconds && level3BlinkingEnabled && isBlinking) {
                val seconds = screenTimeSeconds % 60
                val isEvenSecond = seconds % 2 == 0

                if (isEvenSecond) {
                    timeTextView?.setTextColor(Color.WHITE)
                    overlayView?.setBackgroundColor(level3Color)
                } else {
                    timeTextView?.setTextColor(level3Color)
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
            handler.post(blinkingRunnable)
        }
    }

    private fun stopBlinking() {
        if (isBlinking) {
            isBlinking = false
            handler.removeCallbacks(blinkingRunnable)
            if (screenTimeSeconds >= level2EndTimeSeconds) {
                timeTextView?.setTextColor(level3Color)
                overlayView?.setBackgroundColor(Color.parseColor("#80000000"))
            }
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
        val newGravity = getGravityForPosition(positionString)
        if (currentLayoutParams != null) {
            if (currentLayoutParams!!.gravity != newGravity || forceUpdate) {
                currentLayoutParams!!.gravity = newGravity
                if (overlayView?.parent != null) {
                    try {
                        windowManager?.updateViewLayout(overlayView, currentLayoutParams)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating overlay layout: ${e.message}", e)
                    }
                }
            }
        }
    }

    private fun saveScreenTime() {
        val todayKey = getTodayDateKey()
        prefs.edit().putInt(todayKey, screenTimeSeconds).apply()
        prefs.edit().putString("last_date_key", todayKey).apply()
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
        val todayKey = getTodayDateKey()
        val lastDateKey = prefs.getString("last_date_key", null)

        if (lastDateKey != null && lastDateKey != todayKey) {
            screenTimeSeconds = 0
            updateTimeDisplay()
            saveScreenTime()
        } else if (lastDateKey == null) {
            saveScreenTime()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(screenTimeUpdateRunnable)
        handler.removeCallbacks(blinkingRunnable)
        handler.removeCallbacks(timerVisibilityUpdateRunnable)
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