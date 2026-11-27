package com.nodeseek.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * Safe dispatcher refreshing all pinned Anode Glance AppWidgets.
 */
object GlanceWidgetUpdater {
    suspend fun updateAll(context: Context) {
        val appContext = context.applicationContext ?: context
        runCatching {
            ServerStatusGlanceWidget().updateAll(appContext)
        }
        runCatching {
            DailyPulseGlanceWidget().updateAll(appContext)
        }
    }
}
