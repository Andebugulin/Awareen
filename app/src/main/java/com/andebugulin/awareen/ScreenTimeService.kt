package com.andebugulin.awareen

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Calendar

class ScreenTimeService : Service() {
    private lateinit var overlayController: OverlayController
    private lateinit var prefs: SharedPreferences
    private var screenTimeSeconds = 0
    private var isScreenOn = true
    private var isDeviceUnlocked = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_ALARM_RESET = "com.andebugulin.awareen.ALARM_RESET"
        private const val ALARM_REQUEST_CODE = 1001
    }

    // Timer display settings
    private var timerDisplayMode: String = AppSettings.DEFAULT_TIMER_DISPLAY_MODE
    private var timerDisplayIntervalMinutes: Int = AppSettings.DEFAULT_TIMER_DISPLAY_INTERVAL_MINUTES
    private var timerDisplayDurationSeconds: Int = AppSettings.DEFAULT_TIMER_DISPLAY_DURATION_SECONDS

    // Per-level settings
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
    private var level3BlinkingEnabled: Boolean = AppSettings.DEFAULT_LEVEL_3_BLINKING_ENABLED

    private var currentResetHour: Int = AppSettings.DEFAULT_RESET_HOUR
    private var currentResetMinute: Int = AppSettings.DEFAULT_RESET_MINUTE

    private val TAG = "ScreenTimeService"

    private fun currentOverlaySettings(): OverlaySettings = OverlaySettings(
        level1 = LevelSettings(level1Color, level1Position, level1FontSize, level1BlinkingEnabled),
        level1MaxTimeSeconds = level1MaxTimeSeconds,
        level2 = LevelSettings(level2Color, level2Position, level2FontSize, level2BlinkingEnabled),
        level2DurationSeconds = level2DurationSeconds,
        level3 = LevelSettings(level3Color, level3Position, level3FontSize, level3BlinkingEnabled),
        timerDisplayMode = timerDisplayMode,
        timerDisplayIntervalMinutes = timerDisplayIntervalMinutes,
        timerDisplayDurationSeconds = timerDisplayDurationSeconds,
    )

    // =========================================================================
    // CORE TIMER LOOP
    // =========================================================================

    private val screenTimeUpdateRunnable = object : Runnable {
        override fun run() {
            // :REVIEW we check reset on every resume activity, possibly redundant here
            checkAndPerformResetIfNeeded()

            // Periodically verify the overlay is still attached (every 30s)
            if (screenTimeSeconds % 30 == 0) {
                overlayController.ensureAttached(screenTimeSeconds, currentOverlaySettings())
            }

            val actuallyUnlocked = isScreenActuallyOnAndUnlocked()

            if (actuallyUnlocked) {
                isScreenOn = true
                isDeviceUnlocked = true

                screenTimeSeconds++

                overlayController.render(screenTimeSeconds, currentOverlaySettings())
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

    // =========================================================================
    // BROADCAST RECEIVERS
    // =========================================================================

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
                    checkAndPerformResetIfNeeded()
                }

                Intent.ACTION_USER_PRESENT -> {
                    // User just unlocked — critical reset check point
                    checkAndPerformResetIfNeeded()

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
            Log.d(TAG, "settingsUpdateReceiver fired with action=${intent?.action}")
            if (intent?.action == AppSettings.ACTION_SETTINGS_UPDATED) {
                Log.d(TAG, "Received settings update broadcast, reloading settings.")
                loadSettings()
                applySettingsToOverlay()
                scheduleExactAlarm()
            }
        }
    }

    private val timeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                    Log.d(TAG, "Time or date changed, reloading settings and rescheduling")
                    loadSettings()
                    applySettingsToOverlay()
                    scheduleExactAlarm()
                    checkAndPerformResetIfNeeded()
                }
            }
        }
    }

    /**
     * Receives the AlarmManager broadcast to perform reset even from Doze.
     */
    private val alarmResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_ALARM_RESET) {
                Log.d(TAG, "AlarmManager reset fired")
                checkAndPerformResetIfNeeded()
                scheduleExactAlarm() // schedule the next one
            }
        }
    }

    // =========================================================================
    // SERVICE LIFECYCLE
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            overlayController = OverlayController(this, prefs)
            loadSettings()
            applySettingsToOverlay()

            // Check for any missed resets (e.g. phone was off overnight)
            checkAndPerformResetIfNeeded()
            loadScreenTime()

            val keyFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            // System broadcasts come from outside the app — must NOT use
            // RECEIVER_NOT_EXPORTED, which silently drops them on API 34+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(keyEventReceiver, keyFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(keyEventReceiver, keyFilter)
            }

            val timeFilter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            // Also system broadcasts
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(timeChangedReceiver, timeFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(timeChangedReceiver, timeFilter)
            }

            val settingsFilter = IntentFilter(AppSettings.ACTION_SETTINGS_UPDATED)
            registerReceiver(settingsUpdateReceiver, settingsFilter, RECEIVER_NOT_EXPORTED)

            val alarmFilter = IntentFilter(ACTION_ALARM_RESET)
            registerReceiver(alarmResetReceiver, alarmFilter, RECEIVER_NOT_EXPORTED)

            createNotificationChannel()
            val notification = NotificationCompat.Builder(this, "screen_time_channel")
                .setContentTitle("Screen Time Tracker")
                .setContentText("Tracking your screen time")
                .setSmallIcon(R.drawable.ic_timer)
                .build()
            startForeground(1, notification)

            overlayController.create(handler, level1Position)
            overlayController.render(screenTimeSeconds, currentOverlaySettings())

            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            isDeviceUnlocked = !keyguardManager.isKeyguardLocked

            handler.removeCallbacks(screenTimeUpdateRunnable)
            handler.post(screenTimeUpdateRunnable)

            // Schedule Doze-proof alarm for the next reset time
            scheduleExactAlarm()

            Log.d(TAG, "Service created successfully. Initial screenTimeSeconds: $screenTimeSeconds")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(screenTimeUpdateRunnable)
        saveScreenTime()

        cancelAlarm()

        try {
            unregisterReceiver(keyEventReceiver)
            unregisterReceiver(timeChangedReceiver)
            unregisterReceiver(settingsUpdateReceiver)
            unregisterReceiver(alarmResetReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers: ${e.message}", e)
        }

        overlayController.destroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If the service was restarted by the system (e.g. after being killed),
        // onCreate already ran. But if onStartCommand is called on a running
        // service (e.g. startForegroundService while already alive), make sure
        // the overlay is still attached and settings are fresh.
        loadSettings()
        applySettingsToOverlay()

        overlayController.ensureAttached(screenTimeSeconds, currentOverlaySettings())

        // Make sure the timer loop is running
        handler.removeCallbacks(screenTimeUpdateRunnable)
        handler.post(screenTimeUpdateRunnable)

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed — scheduling service restart")

        // Re-start the service so the overlay comes back
        val restartIntent = Intent(applicationContext, ScreenTimeService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            2001,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1000,
            pendingIntent
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================================
    // RESET LOGIC — deterministic, Doze-proof
    //
    // Core idea: on every check, compute the most recent wall-clock time that
    // matches the configured reset hour:minute and is <= now. If the last
    // actual reset happened BEFORE that moment, a reset is overdue.
    //
    // This works whether the phone was asleep 8 hours or 8 seconds.
    // =========================================================================

    /**
     * Returns the most recent wall-clock instant matching
     * [currentResetHour]:[currentResetMinute] that is <= now.
     */
    private fun getMostRecentResetTimeMillis(): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, currentResetHour)
            set(Calendar.MINUTE, currentResetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // If that time hasn't happened yet today, the most recent one was yesterday
        if (cal.timeInMillis > now) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return cal.timeInMillis
    }

    /**
     * Returns the next future wall-clock instant matching
     * [currentResetHour]:[currentResetMinute] that is > now.
     */
    private fun getNextResetTimeMillis(): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, currentResetHour)
            set(Calendar.MINUTE, currentResetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Deterministic reset check. No grace periods, no minimum-time guards.
     * Simply: did a scheduled reset moment pass since the last actual reset?
     */
    private fun shouldReset(): Boolean {
        val lastReset = prefs.getLong("last_actual_reset_timestamp", 0L)
        val mostRecentScheduled = getMostRecentResetTimeMillis()

        // Reset if last reset happened before the most recent scheduled reset
        val needed = lastReset < mostRecentScheduled
        if (needed) {
            Log.d(TAG, "Reset needed: lastReset=$lastReset < scheduledAt=$mostRecentScheduled")
        }
        return needed
    }

    /**
     * Single entry point for all reset checks throughout the service.
     */
    private fun checkAndPerformResetIfNeeded() {
        if (shouldReset()) {
            Log.d(TAG, "Performing reset — clearing screen time to 0")
            screenTimeSeconds = 0

            val todayKey = getTodayDateKey()
            val now = System.currentTimeMillis()

            prefs.edit()
                .putString("last_date_key", todayKey)
                .putInt(todayKey, 0)
                .putLong("last_save_timestamp", now)
                .putLong("last_actual_reset_timestamp", now)
                .apply()

            if (overlayController.isCreated()) {
                overlayController.render(screenTimeSeconds, currentOverlaySettings())
            }
            Log.d(TAG, "Reset complete. Next reset at ${getNextResetTimeMillis()}")
        }
    }

    // =========================================================================
    // ALARM MANAGER — fires even through Doze
    // =========================================================================

    /**
     * Schedules an exact alarm for the next reset time.
     * This ensures the reset fires even if the Handler is suspended.
     */
    private fun scheduleExactAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_ALARM_RESET).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextReset = getNextResetTimeMillis()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextReset,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                nextReset,
                pendingIntent
            )
        }

        Log.d(TAG, "Exact alarm scheduled for $nextReset (in ${(nextReset - System.currentTimeMillis()) / 1000 / 60} min)")
    }

    private fun cancelAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_ALARM_RESET).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    // =========================================================================
    // SETTINGS
    // =========================================================================

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

        Log.d(TAG, "Settings loaded: reset=${currentResetHour}:${currentResetMinute}, displayMode=$timerDisplayMode")
    }

    private fun applySettingsToOverlay() {
        if (overlayController.isCreated()) {
            overlayController.render(screenTimeSeconds, currentOverlaySettings())
        }
    }

    // =========================================================================
    // ANALYTICS
    // =========================================================================

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

    // =========================================================================
    // NOTIFICATION
    // =========================================================================

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

    // =========================================================================
    // SCREEN TIME PERSISTENCE
    // =========================================================================

    private fun saveScreenTime() {
        val todayKey = getTodayDateKey()
        val now = System.currentTimeMillis()

        prefs.edit()
            .putInt(todayKey, screenTimeSeconds)
            .putString("last_date_key", todayKey)
            .putLong("last_save_timestamp", now)
            .apply()
    }

    private fun loadScreenTime() {
        val todayKey = getTodayDateKey()
        screenTimeSeconds = prefs.getInt(todayKey, 0)
    }

    private fun getTodayDateKey(): String {
        val calendar = Calendar.getInstance()
        return "screen_time_${calendar.get(Calendar.YEAR)}_${calendar.get(Calendar.DAY_OF_YEAR)}"
    }

    // =========================================================================
    // SCREEN STATE CHECK
    // =========================================================================

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

}