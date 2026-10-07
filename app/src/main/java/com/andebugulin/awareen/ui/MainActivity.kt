package com.andebugulin.awareen.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.andebugulin.awareen.R
import com.andebugulin.awareen.data.AppSettings
import com.andebugulin.awareen.data.ScreenTimeRepository
import com.andebugulin.awareen.data.SettingsRepository
import com.andebugulin.awareen.domain.OverlayDecisions
import com.andebugulin.awareen.service.ResetScheduler
import com.andebugulin.awareen.service.ScreenTimeService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val HOME_CARD_REFRESH_MS = 5_000L
    }

    private val permissionWizard = PermissionWizard(this, ::actuallyStartService)

    // The service writes today's total every second; re-read it while this
    // screen is visible so the card doesn't sit on the value from onResume.
    private val homeCardHandler = Handler(Looper.getMainLooper())
    private val homeCardRefresh = object : Runnable {
        override fun run() {
            updateHomeCard()
            homeCardHandler.postDelayed(this, HOME_CARD_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Force dark navigation bar
        window.navigationBarColor = ContextCompat.getColor(this, R.color.app_background)

        val startServiceButton = findViewById<Button>(R.id.startServiceButton)
        startServiceButton.setOnClickListener {
            permissionWizard.start()
        }

        val stopServiceButton = findViewById<Button>(R.id.stopServiceButton)
        stopServiceButton.setOnClickListener {
            val serviceIntent = Intent(this, ScreenTimeService::class.java)
            stopService(serviceIntent)
            Toast.makeText(this, "Screen time tracking stopped", Toast.LENGTH_SHORT).show()
            startServiceButton.visibility = View.VISIBLE
            stopServiceButton.visibility = View.GONE
        }
        updateButtonVisibility()

        // Set up social media links
        setupSocialLinks()
        val settingsButton: ImageButton = findViewById(R.id.settingsButton)
        settingsButton.setOnClickListener {
            dismissSettingsHint()
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)

        }

        val infoButton: ImageButton = findViewById(R.id.infoButton)
        infoButton.setOnClickListener {
            val intent = Intent(this, InfoActivity::class.java)
            startActivity(intent)
        }

        val analyticsButton: ImageButton = findViewById(R.id.analyticsButton)
        analyticsButton.setOnClickListener {
            val intent = Intent(this, AnalyticsActivity::class.java)
            startActivity(intent)
        }

        findViewById<View>(R.id.settingsHint).setOnClickListener { dismissSettingsHint() }
    }

    override fun onResume() {
        super.onResume()
        updateButtonVisibility()
        homeCardHandler.post(homeCardRefresh)

        // Defensive reset check — catches missed resets even if service was killed
        performDefensiveResetCheck()
    }

    override fun onPause() {
        super.onPause()
        homeCardHandler.removeCallbacks(homeCardRefresh)
    }

    /**
     * Safety-net reset check: catches missed resets when the service was
     * killed, the alarm didn't fire, and the boot receiver didn't run.
     */
    private fun performDefensiveResetCheck() {
        try {
            val prefs = getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            val scheduler = ResetScheduler(
                this,
                SettingsRepository(this, prefs),
                ScreenTimeRepository(prefs),
            )
            scheduler.checkAndReset()
        } catch (e: Exception) {
            Log.e(TAG, "Error in defensive reset check: ${e.message}", e)
        }
    }

    private fun updateButtonVisibility() {
        val isServiceRunning = isServiceRunning(ScreenTimeService::class.java)
        findViewById<Button>(R.id.startServiceButton).visibility = if (isServiceRunning) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.stopServiceButton).visibility = if (isServiceRunning) View.VISIBLE else View.GONE
    }

    /**
     * Shows today's real tracked time once there is any — either tracking is
     * running right now, or there's a leftover total from earlier today.
     * Otherwise this is a fresh (or never-started) day, so the onboarding
     * tips stay up instead of a "0h 0m" that would look broken.
     */
    private fun updateHomeCard() {
        val isServiceRunning = isServiceRunning(ScreenTimeService::class.java)
        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val todaySeconds = ScreenTimeRepository(prefs).getTodayScreenTime()

        val featuresContentView = findViewById<View>(R.id.featuresContentView)
        val todayContentView = findViewById<View>(R.id.todayContentView)

        if (!isServiceRunning && todaySeconds <= 0) {
            featuresContentView.visibility = View.VISIBLE
            todayContentView.visibility = View.GONE
            return
        }

        val overlaySettings = SettingsRepository(this, prefs).loadOverlaySettings()
        val level = OverlayDecisions.levelFor(
            todaySeconds,
            overlaySettings.level1MaxTimeSeconds,
            overlaySettings.level2DurationSeconds,
        )
        val stage = overlaySettings.level(level)
        val statusText = "${stage.name}: " + when (level) {
            1 -> "staying mindful"
            2 -> "past your first checkpoint"
            else -> "well over your limit"
        }

        findViewById<TextView>(R.id.todayTimeTextView).apply {
            text = formatHoursMinutes(todaySeconds)
            setTextColor(stage.color)
        }
        findViewById<TextView>(R.id.todayStatusTextView).text = statusText

        featuresContentView.visibility = View.GONE
        todayContentView.visibility = View.VISIBLE
    }

    // =========================================================================
    // ONBOARDING — a single hint pointing at Settings, the first time the
    // user starts tracking. Dismissed by tapping it or opening Settings.
    // =========================================================================

    private fun showSettingsHintOnce() {
        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(AppSettings.ONBOARDING_SETTINGS_HINT_SHOWN, false)) return
        findViewById<View>(R.id.settingsHint).apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(250).start()
        }
    }

    private fun dismissSettingsHint() {
        val hint = findViewById<View>(R.id.settingsHint)
        if (hint.visibility != View.VISIBLE) return
        hint.visibility = View.GONE
        getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(AppSettings.ONBOARDING_SETTINGS_HINT_SHOWN, true)
            .apply()
    }

    private fun formatHoursMinutes(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return "${hours}h ${minutes}m"
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun setupSocialLinks() {
        findViewById<View>(R.id.githubLink).setOnClickListener {
            openUrl("https://github.com/Andebugulin")
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(url)
        startActivity(intent)
    }

    private fun actuallyStartService() {
        try {
            val serviceIntent = Intent(this, ScreenTimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Screen time tracking started", Toast.LENGTH_SHORT).show()
            findViewById<Button>(R.id.startServiceButton).visibility = View.GONE
            findViewById<Button>(R.id.stopServiceButton).visibility = View.VISIBLE
            showSettingsHintOnce()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
