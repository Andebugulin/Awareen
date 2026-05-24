package com.andebugulin.awareen.service

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
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.andebugulin.awareen.R
import com.andebugulin.awareen.data.AppSettings
import com.andebugulin.awareen.data.ScreenTimeRepository
import com.andebugulin.awareen.data.SettingsRepository
import com.andebugulin.awareen.overlay.OverlayController
import com.andebugulin.awareen.overlay.OverlaySettings
import com.andebugulin.awareen.widget.ScreenTimeWidgetProvider

class ScreenTimeService : Service() {
    private lateinit var overlayController: OverlayController
    private lateinit var prefs: SharedPreferences
    private lateinit var repo: ScreenTimeRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var resetScheduler: ResetScheduler
    private lateinit var screenStateMonitor: ScreenStateMonitor
    private var screenTimeSeconds = 0
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The full overlay-settings snapshot. Reloaded fresh from the repo on
     * every settings change broadcast; passed straight to render() each tick.
     * Lateinit because the prefs/repo aren't available until onCreate.
     */
    private lateinit var settings: OverlaySettings

    private val TAG = "ScreenTimeService"

    // =========================================================================
    // CORE TIMER LOOP
    // =========================================================================

    private val screenTimeUpdateRunnable = object : Runnable {
        override fun run() {
            // :REVIEW we check reset on every resume activity, possibly redundant here
            checkAndPerformResetIfNeeded()

            // Periodically verify the overlay is still attached (every 30s)
            if (screenTimeSeconds % 30 == 0) {
                overlayController.ensureAttached(screenTimeSeconds, settings)
            }

            if (screenStateMonitor.isActive()) {
                screenTimeSeconds++

                overlayController.render(screenTimeSeconds, settings)
                saveScreenTime()
                saveAnalyticsData()

                // Push to the home-screen widget on a slower cadence than the
                // overlay — widgets aren't designed for per-second updates and
                // RemoteViews rebuilds aren't free.
                if (screenTimeSeconds % 30 == 0) {
                    ScreenTimeWidgetProvider.refresh(this@ScreenTimeService, screenTimeSeconds, settings)
                }

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
            settingsRepository = SettingsRepository(this, prefs)
            resetScheduler = ResetScheduler(this, settingsRepository, repo)
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

            overlayController.create(handler, settings.level1.position)
            overlayController.render(screenTimeSeconds, settings)

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

        overlayController.ensureAttached(screenTimeSeconds, settings)

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

    /**
     * The system delivers this whenever the device configuration changes
     * (rotation, multi-window resize, foldable fold/unfold, …). The overlay
     * window's gravity-anchored presets re-position automatically, but
     * fraction-based custom positions need an explicit re-apply against the
     * new screen size — see [OverlayController.onConfigurationChanged].
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayController.onConfigurationChanged()
    }

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
                overlayController.render(screenTimeSeconds, settings)
            }
            // Zero the widget immediately so it doesn't sit on yesterday's
            // value for up to 30 seconds.
            ScreenTimeWidgetProvider.refresh(this, screenTimeSeconds, settings)
        }
    }

    // =========================================================================
    // SETTINGS
    // =========================================================================

    private fun loadSettings() {
        settings = settingsRepository.loadOverlaySettings()
        Log.d(TAG, "Settings loaded: displayMode=${settings.timerDisplayMode}")
    }

    private fun applySettingsToOverlay() {
        if (overlayController.isCreated()) {
            overlayController.render(screenTimeSeconds, settings)
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