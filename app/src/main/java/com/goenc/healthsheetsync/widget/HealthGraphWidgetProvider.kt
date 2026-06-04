package com.goenc.healthsheetsync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import com.goenc.healthsheetsync.MainActivity
import com.goenc.healthsheetsync.R
import com.goenc.healthsheetsync.data.LocalHealthDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HealthGraphWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        HealthGraphWidgetUpdater.requestUpdate(context.applicationContext)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        HealthGraphWidgetUpdater.requestUpdate(context.applicationContext)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        HealthGraphWidgetUpdater.requestUpdate(context.applicationContext)
    }
}

internal object HealthGraphWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun requestUpdate(context: Context) {
        scope.launch {
            updateWidgets(context)
        }
    }

    private suspend fun updateWidgets(context: Context) {
        val appContext = context.applicationContext
        val appWidgetManager = AppWidgetManager.getInstance(appContext)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(appContext, HealthGraphWidgetProvider::class.java),
        )
        if (appWidgetIds.isEmpty()) return

        val storedData = LocalHealthDataStore(appContext).load()
        appWidgetIds.forEach { appWidgetId ->
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val bitmap = HealthGraphWidgetRenderer.render(
                context = appContext,
                storedData = storedData,
                widthPx = resolveWidgetWidthPx(appContext, options),
                heightPx = resolveWidgetHeightPx(appContext, options),
            )
            appWidgetManager.updateAppWidget(
                appWidgetId,
                buildRemoteViews(appContext, bitmap),
            )
        }
    }

    private fun buildRemoteViews(context: Context, bitmap: android.graphics.Bitmap): RemoteViews {
        return RemoteViews(context.packageName, R.layout.widget_health_graph).apply {
            setImageViewBitmap(R.id.widget_graph_image, bitmap)
            setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
        }
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    private fun resolveWidgetWidthPx(context: Context, options: Bundle): Int {
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            .takeIf { it > 0 }
            ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDGET_WIDTH_DP)
        return (widthDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    private fun resolveWidgetHeightPx(context: Context, options: Bundle): Int {
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            .takeIf { it > 0 }
            ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_WIDGET_HEIGHT_DP)
        return (heightDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    private const val DEFAULT_WIDGET_WIDTH_DP = 320
    private const val DEFAULT_WIDGET_HEIGHT_DP = 200
}
