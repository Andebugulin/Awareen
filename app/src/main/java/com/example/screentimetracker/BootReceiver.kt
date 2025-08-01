package com.example.screentimetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Boot receiver triggered with action: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // Check if overlay permission is granted before starting service
                if (Settings.canDrawOverlays(context)) {
                    try {
                        val serviceIntent = Intent(context, ScreenTimeService::class.java)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }

                        Log.d(TAG, "ScreenTimeService started successfully after boot")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start ScreenTimeService after boot: ${e.message}", e)
                    }
                } else {
                    Log.w(TAG, "Cannot start service after boot - overlay permission not granted")
                }
            }
        }
    }
}