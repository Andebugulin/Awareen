package com.andebugulin.awareen

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val permissionWizard = PermissionWizard(this, ::actuallyStartService)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Force dark navigation bar
        window.navigationBarColor = Color.parseColor("#121212")

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
            // Create an Intent to start SettingsActivity
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
    }

    override fun onResume() {
        super.onResume()
        updateButtonVisibility()

        // Defensive reset check — catches missed resets even if service was killed
        performDefensiveResetCheck()
    }

    /**
     * Safety-net reset check: catches missed resets when the service was
     * killed, the alarm didn't fire, and the boot receiver didn't run.
     */
    private fun performDefensiveResetCheck() {
        try {
            val prefs = getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            val scheduler = ResetScheduler(this, prefs, ScreenTimeRepository(prefs))
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
        val githubLink = findViewById<TextView>(R.id.githubLink)
        val linkedinLink = findViewById<TextView>(R.id.linkedinLink)
        val donateLink = findViewById<TextView>(R.id.donateLink)

        // Set up GitHub link
        githubLink.setOnClickListener {
            openUrl("https://github.com/Andebugulin")
        }

        // Set up LinkedIn link
        linkedinLink.setOnClickListener {
            openUrl("https://www.linkedin.com/in/andrei-gulin")
        }

        // Set up Donate link
        donateLink.setOnClickListener {
            // Replace with your actual Buy Me a Coffee link once you have it
            openUrl("https://buymeacoffee.com/andebugulin")
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
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
