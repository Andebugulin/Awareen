package com.andebugulin.awareen.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.andebugulin.awareen.R
import com.andebugulin.awareen.data.AppSettings
import com.andebugulin.awareen.data.ScreenTimeRepository
import com.andebugulin.awareen.data.SettingsRepository
import com.andebugulin.awareen.overlay.OverlaySettings
import com.andebugulin.awareen.ui.MainActivity

/**
 * Home-screen widget showing today's accumulated screen time.
 *
 * Two update paths:
 *  1. The Android framework calls [onUpdate] on the cadence declared in
 *     widget_screen_time_info.xml (30 min) and whenever a widget is added.
 *     We read the latest persisted value from prefs.
 *  2. [ScreenTimeService] calls [refresh] from its tick loop while it is
 *     alive, giving the widget a fresh value every 30 seconds without
 *     waiting for the framework cadence.
 *
 * The widget mirrors the overlay's level coloring (level 1 / 2 / 3) but
 * does not blink — widget RemoteViews aren't suited for sub-second updates.
 */
class ScreenTimeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val prefs = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val seconds = ScreenTimeRepository(prefs).getTodayScreenTime()
        val settings = SettingsRepository(context, prefs).loadOverlaySettings()
        val views = buildViews(context, seconds, settings)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {
        fun refresh(context: Context, seconds: Int, settings: OverlaySettings) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, ScreenTimeWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(componentName)
            if (ids.isEmpty()) return
            val views = buildViews(context, seconds, settings)
            manager.updateAppWidget(componentName, views)
        }

        private fun buildViews(
            context: Context,
            seconds: Int,
            settings: OverlaySettings,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_screen_time)
            views.setTextViewText(R.id.widgetTimeText, formatTime(seconds))
            views.setTextColor(R.id.widgetTimeText, colorForSeconds(seconds, settings))

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
            return views
        }

        private fun formatTime(seconds: Int): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return String.format("%02d:%02d:%02d", h, m, s)
        }

        private fun colorForSeconds(seconds: Int, settings: OverlaySettings): Int {
            val level2End = settings.level1MaxTimeSeconds + settings.level2DurationSeconds
            return when {
                seconds < settings.level1MaxTimeSeconds -> settings.level1.color
                seconds < level2End -> settings.level2.color
                else -> settings.level3.color
            }
        }
    }
}
