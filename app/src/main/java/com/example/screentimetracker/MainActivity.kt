package com.example.screentimetracker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.PowerManager
import android.view.View
import androidx.appcompat.app.AlertDialog
import android.app.ActivityManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQ_CODE = 1234
    }

    private var hasRequestedBatteryOptimization = false
    private var hasRequestedAutoStart = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        val startServiceButton = findViewById<Button>(R.id.startServiceButton)
        startServiceButton.setOnClickListener {
            checkOverlayPermissionAndStartService()
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

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // After overlay permission, check battery optimization
        requestBatteryOptimizationExemption()
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // After battery optimization, check pause activity setting
        checkPauseActivityPermissionAndProceed()
    }

    private val pauseActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // After pause activity settings, check auto-start
        checkAutoStartPermissionAndStartService()
    }

    private val autoStartLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Finally start the service
        actuallyStartService()
    }


    private fun showCustomDialog(
        title: String,
        message: String,
        positiveText: String,
        negativeText: String,
        onPositive: () -> Unit,
        onNegative: () -> Unit
    ) {
        val dialog = AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { d, _ ->
                d.dismiss()
                onPositive()
            }
            .setNegativeButton(negativeText) { d, _ ->
                d.dismiss()
                onNegative()
            }
            .setCancelable(false)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#FFA500"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#FFA500"))
    }



    private fun checkOverlayPermissionAndStartService() {
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
        } else {
            startScreenTimeService()
        }

    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Settings.canDrawOverlays(this)) {
                startScreenTimeService()
            } else {
                Toast.makeText(this,
                    "Overlay permission is needed for this app to work",
                    Toast.LENGTH_LONG).show()
            }
        }
    }
    // Enhanced startScreenTimeService function
    private fun startScreenTimeService() {
        try {
            // Step 1: Check overlay permission first
            checkOverlayPermissionAndProceed()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkOverlayPermissionAndProceed() {
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
        } else {
            // Overlay permission already granted, proceed to battery optimization
            requestBatteryOptimizationExemption()
        }
    }

    private fun showOverlayPermissionDialog() {
        showCustomDialog(
            title = "Display Permission",
            message = "Please allow this app to display over other apps to show the screen time overlay.",
            positiveText = "Open Settings",
            negativeText = "Cancel",
            onPositive = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            },
            onNegative = {
                Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
            }
        )
    }



    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            } else {
                // Battery optimization already disabled, proceed to next step
                checkPauseActivityPermissionAndProceed()
            }
        } else {
            // No battery optimization on older Android versions
            checkPauseActivityPermissionAndProceed()
        }
    }

    private fun showBatteryOptimizationDialog() {
        showCustomDialog(
            title = "Battery Optimization",
            message = "Please disable battery optimization to prevent the app from being killed by the system.",
            positiveText = "Open Settings",
            negativeText = "Skip",
            onPositive = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    batteryOptimizationLauncher.launch(intent)
                } catch (e: Exception) {
                    checkPauseActivityPermissionAndProceed()
                }
            },
            onNegative = {
                checkPauseActivityPermissionAndProceed()
            }
        )
    }


    private fun checkPauseActivityPermissionAndProceed() {
        // Always show pause activity dialog since we can't reliably detect if it's enabled
        showPauseActivityDialog()
    }

    private fun showPauseActivityDialog() {
        showCustomDialog(
            title = "Disable App Pausing",
            message = "Please disable 'Pause app activity if unused' to prevent the timer from stopping.\n\nGo to: App Info → Permissions → 'Pause app activity if unused' → Turn OFF",
            positiveText = "Open App Settings",
            negativeText = "Skip",
            onPositive = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                pauseActivityLauncher.launch(intent)
            },
            onNegative = {
                checkAutoStartPermissionAndStartService()
            }
        )
    }



    private fun checkAutoStartPermissionAndStartService() {
        val manufacturer = Build.MANUFACTURER.lowercase()

        // Show auto-start dialog for manufacturers that need it
        when {
            manufacturer.contains("xiaomi") -> {
                showAutoStartDialog("MIUI Auto-Start",
                    "Enable auto-start in MIUI Security settings to ensure the app runs after reboot.",
                    "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                showAutoStartDialog("EMUI/Magic UI Protected Apps",
                    "Enable this app in Protected Apps to prevent it from being killed.",
                    "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            }
            manufacturer.contains("oppo") -> {
                showAutoStartDialog("ColorOS Auto-Start",
                    "Enable auto-start in ColorOS battery management.",
                    "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            }
            manufacturer.contains("vivo") -> {
                showAutoStartDialog("Funtouch OS Background Apps",
                    "Remove from background app restrictions.",
                    "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            }
            manufacturer.contains("samsung") -> {
                showAutoStartDialog("Samsung Battery Management",
                    "Remove from 'Sleeping apps' in Device Care.",
                    "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
            }
            manufacturer.contains("oneplus") -> {
                showAutoStartDialog("OnePlus Battery Optimization",
                    "Enable auto-launch and disable battery optimization.",
                    "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            }
            manufacturer.contains("realme") -> {
                showAutoStartDialog("Realme Auto-Start",
                    "Enable auto-start in Phone Manager.",
                    "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            }
            else -> {
                // For other manufacturers, just start the service
                actuallyStartService()
            }
        }
    }

    private fun showAutoStartDialog(title: String, message: String, packageName: String, activityName: String) {
        showCustomDialog(
            title = title,
            message = message,
            positiveText = "Open Settings",
            negativeText = "Skip",
            onPositive = {
                openAutoStartSettings(packageName, activityName)
            },
            onNegative = {
                actuallyStartService()
            }
        )
    }


    private fun openAutoStartSettings(packageName: String, activityName: String) {
        try {
            val intent = Intent().apply {
                component = ComponentName(packageName, activityName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            autoStartLauncher.launch(intent)
        } catch (e: Exception) {
            // If specific settings can't be opened, try general app settings
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                autoStartLauncher.launch(intent)
            } catch (e2: Exception) {
                actuallyStartService()
            }
        }
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