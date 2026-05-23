package com.andebugulin.awareen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog

/**
 * Walks the user through the four permission steps needed for the screen-time
 * service to survive: overlay → battery optimization → pause-app-activity →
 * manufacturer auto-start.
 *
 * Each step either advances automatically (permission already granted) or
 * shows a dialog and routes the user into the system settings page for that
 * permission. Skipping any step except overlay still advances the flow —
 * we'd rather start the service with weaker guarantees than not at all.
 *
 * Activity-result launchers are registered eagerly (field initializers) so
 * the wizard must be constructed during the activity's INITIALIZED phase —
 * a field initializer of [activity] is the canonical location.
 *
 * Calls [onComplete] once when the user reaches the end of the flow,
 * regardless of which steps they skipped.
 */
class PermissionWizard(
    private val activity: ComponentActivity,
    private val onComplete: () -> Unit,
) {
    // =========================================================================
    // LAUNCHERS — registered eagerly
    // =========================================================================

    private val overlayLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkBatteryOptimization() }

    private val batteryLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { showPauseActivityDialog() }

    private val pauseActivityLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkAutoStart() }

    private val autoStartLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onComplete() }

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    fun start() = checkOverlay()

    // =========================================================================
    // STEP 1 — OVERLAY (the only step we refuse to skip)
    // =========================================================================

    private fun checkOverlay() {
        if (Settings.canDrawOverlays(activity)) {
            checkBatteryOptimization()
        } else {
            showOverlayDialog()
        }
    }

    private fun showOverlayDialog() {
        showCustomDialog(
            title = "Display Permission",
            message = "Please allow this app to display over other apps to show the screen time overlay.",
            positiveText = "Open Settings",
            negativeText = "Cancel",
            onPositive = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                overlayLauncher.launch(intent)
            },
            onNegative = {
                Toast.makeText(activity, "Overlay permission is required", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // =========================================================================
    // STEP 2 — BATTERY OPTIMIZATION
    // =========================================================================

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            showPauseActivityDialog()
            return
        }
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(activity.packageName)) {
            showPauseActivityDialog()
        } else {
            showBatteryOptimizationDialog()
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
                    data = Uri.parse("package:${activity.packageName}")
                }
                try {
                    batteryLauncher.launch(intent)
                } catch (e: Exception) {
                    showPauseActivityDialog()
                }
            },
            onNegative = { showPauseActivityDialog() }
        )
    }

    // =========================================================================
    // STEP 3 — PAUSE-APP-ACTIVITY
    // No reliable detection API, so we always show the dialog.
    // =========================================================================

    private fun showPauseActivityDialog() {
        showCustomDialog(
            title = "Disable App Pausing",
            message = "Please disable 'Pause app activity if unused' to prevent the timer from stopping.\n\nGo to: App Info → Permissions → 'Pause app activity if unused' → Turn OFF",
            positiveText = "Open App Settings",
            negativeText = "Skip",
            onPositive = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                pauseActivityLauncher.launch(intent)
            },
            onNegative = { checkAutoStart() }
        )
    }

    // =========================================================================
    // STEP 4 — MANUFACTURER AUTO-START
    // =========================================================================

    private fun checkAutoStart() {
        val m = Build.MANUFACTURER.lowercase()
        when {
            m.contains("xiaomi") -> showAutoStartDialog(
                "MIUI Auto-Start",
                "Enable auto-start in MIUI Security settings to ensure the app runs after reboot.",
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            m.contains("huawei") || m.contains("honor") -> showAutoStartDialog(
                "EMUI/Magic UI Protected Apps",
                "Enable this app in Protected Apps to prevent it from being killed.",
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            m.contains("oppo") -> showAutoStartDialog(
                "ColorOS Auto-Start",
                "Enable auto-start in ColorOS battery management.",
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )
            m.contains("vivo") -> showAutoStartDialog(
                "Funtouch OS Background Apps",
                "Remove from background app restrictions.",
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            m.contains("samsung") -> showAutoStartDialog(
                "Samsung Battery Management",
                "Remove from 'Sleeping apps' in Device Care.",
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
            m.contains("oneplus") -> showAutoStartDialog(
                "OnePlus Battery Optimization",
                "Enable auto-launch and disable battery optimization.",
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )
            m.contains("realme") -> showAutoStartDialog(
                "Realme Auto-Start",
                "Enable auto-start in Phone Manager.",
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )
            else -> onComplete()
        }
    }

    private fun showAutoStartDialog(
        title: String,
        message: String,
        packageName: String,
        activityName: String,
    ) {
        showCustomDialog(
            title = title,
            message = message,
            positiveText = "Open Settings",
            negativeText = "Skip",
            onPositive = { openAutoStartSettings(packageName, activityName) },
            onNegative = { onComplete() }
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
                onComplete()
            }
        }
    }

    // =========================================================================
    // SHARED DIALOG
    // =========================================================================

    private fun showCustomDialog(
        title: String,
        message: String,
        positiveText: String,
        negativeText: String,
        onPositive: () -> Unit,
        onNegative: () -> Unit,
    ) {
        val dialog = AlertDialog.Builder(activity, R.style.CustomAlertDialog)
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
}
