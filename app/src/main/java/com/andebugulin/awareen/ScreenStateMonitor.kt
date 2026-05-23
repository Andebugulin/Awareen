package com.andebugulin.awareen

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Thin wrapper around [PowerManager] and [KeyguardManager] so callers can ask
 * "is the user actively on the device right now?" without juggling two system
 * services.
 *
 * Every method re-reads from the system on call. There is no caching — the
 * tick loop relies on always getting the current state, not a snapshot.
 */
class ScreenStateMonitor(context: Context) {
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager =
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    /**
     * Screen on AND device unlocked — the condition for counting screen time
     * on a tick.
     */
    fun isActive(): Boolean = isScreenOn() && !isLocked()

    fun isScreenOn(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }

    fun isLocked(): Boolean = keyguardManager.isKeyguardLocked
}
