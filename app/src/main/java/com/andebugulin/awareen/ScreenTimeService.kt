package com.andebugulin.awareen

import android.app.AlarmManager
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
import android.util.Log
import androidx.core.app.NotificationCompat

class ScreenTimeService : Service() {
    private lateinit var overlayController: OverlayController
    private lateinit var prefs: SharedPreferences
    private lateinit var repo: ScreenTimeRepository
    private lateinit var resetScheduler: ResetScheduler
    private lateinit var screenStateMonitor: ScreenStateMonitor
    private var screenTimeSeconds = 0
    private val handler = Handler(Looper.getMainLooper())

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
    private var level2BlinkingEnabled: Boolean = AppSettings.DEFAULT_LEVEL_2_BLINKING_ENABLED

    private var level3Color: Int = AppSettings.DEFAULT_LEVEL_3_COLOR
    private var level3Position: String = AppSettings.DEFAULT_LEVEL_3_POSITION
    private var level3FontSize: Float = AppSettings.DEFAULT_LEVEL_3_FONT_SIZE.toFloat()
    private var level3BlinkingEnabled: Boolean = AppSettings.DEFAULT_LEVEL_3_BLINKING_ENABLED

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

            if (screenStateMonitor.isActive()) {
                screenTimeSeconds++

                overlayController.render(screenTimeSeconds, currentOverlaySettings())
                saveScreenTime()
                saveAnalyticsData()

                handler.postDelayed(this, 1000)
            } else {
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
                    saveScreenTime()
                }

                Intent.ACTION_SCREEN_ON -> {
                    checkAndPerformResetIfNeeded()
                }

                Intent.ACTION_USER_PRESENT -> {
                    // User just unlocked — critical reset check point
                    checkAndPerformResetIfNeeded()
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
                resetScheduler.scheduleNextAlarm()
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
                    resetScheduler.scheduleNextAlarm()
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
            if (intent?.action == ResetScheduler.ACTION_ALARM_RESET) {
                Log.d(TAG, "AlarmManager reset fired")
                checkAndPerformResetIfNeeded()
                resetScheduler.scheduleNextAlarm() // schedule the next one
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
            repo = ScreenTimeRepository(prefs)
            resetScheduler = ResetScheduler(this, prefs, repo)
            screenStateMonitor = ScreenStateMonitor(this)
            overlayController = OverlayController(this, prefs)
            loadSettings()

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

            val alarmFilter = IntentFilter(ResetScheduler.ACTION_ALARM_RESET)
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

            handler.removeCallbacks(screenTimeUpdateRunnable)
            handler.post(screenTimeUpdateRunnable)

            // Schedule Doze-proof alarm for the next reset time
            resetScheduler.scheduleNextAlarm()

            Log.d(TAG, "Service created successfully. Initial screenTimeSeconds: $screenTimeSeconds")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(screenTimeUpdateRunnable)
        saveScreenTime()

        resetScheduler.cancelAlarm()

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
    // RESET — thin wrapper around ResetScheduler
    //
    // The scheduler owns the wall-clock math, the alarm, and the prefs write.
    // The service still owns the in-memory screenTimeSeconds counter and the
    // overlay, so the wrapper exists to keep both in sync after a reset.
    // =========================================================================

    private fun checkAndPerformResetIfNeeded() {
        if (resetScheduler.checkAndReset()) {
            screenTimeSeconds = 0
            if (overlayController.isCreated()) {
                overlayController.render(screenTimeSeconds, currentOverlaySettings())
            }
        }
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
        level2BlinkingEnabled = prefs.getBoolean(AppSettings.LEVEL_2_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_2_BLINKING_ENABLED)

        level3Color = prefs.getInt(AppSettings.LEVEL_3_COLOR, AppSettings.DEFAULT_LEVEL_3_COLOR)
        level3Position = prefs.getString(AppSettings.LEVEL_3_POSITION, AppSettings.DEFAULT_LEVEL_3_POSITION) ?: AppSettings.DEFAULT_LEVEL_3_POSITION
        level3FontSize = prefs.getInt(AppSettings.LEVEL_3_FONT_SIZE, AppSettings.DEFAULT_LEVEL_3_FONT_SIZE).toFloat()
        level3BlinkingEnabled = prefs.getBoolean(AppSettings.LEVEL_3_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_3_BLINKING_ENABLED)

        timerDisplayMode = prefs.getString(AppSettings.TIMER_DISPLAY_MODE, AppSettings.DEFAULT_TIMER_DISPLAY_MODE) ?: AppSettings.DEFAULT_TIMER_DISPLAY_MODE
        timerDisplayIntervalMinutes = prefs.getInt(AppSettings.TIMER_DISPLAY_INTERVAL_MINUTES, AppSettings.DEFAULT_TIMER_DISPLAY_INTERVAL_MINUTES)
        timerDisplayDurationSeconds = prefs.getInt(AppSettings.TIMER_DISPLAY_DURATION_SECONDS, AppSettings.DEFAULT_TIMER_DISPLAY_DURATION_SECONDS)

        Log.d(TAG, "Settings loaded: displayMode=$timerDisplayMode")
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
        repo.recordAnalyticsTick(screenTimeSeconds)
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
        repo.saveTodayScreenTime(screenTimeSeconds)
    }

    private fun loadScreenTime() {
        screenTimeSeconds = repo.getTodayScreenTime()
    }

}