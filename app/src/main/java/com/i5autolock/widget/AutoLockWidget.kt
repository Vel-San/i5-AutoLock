package com.i5autolock.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.i5autolock.MainActivity
import com.i5autolock.R
import com.i5autolock.data.status.StatusCache
import com.i5autolock.service.AutoLockService
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Home-screen widget: glanceable lock state + summary, with a one-tap "Lock now". */
class AutoLockWidget : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun statusCache(): StatusCache
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val cache = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .statusCache()
        val cached = runBlocking { cache.cached.first() }

        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_autolock).apply {
                val (lockLabel, lockColor) = when (cached.lockState) {
                    "LOCKED" -> "Locked" to 0xFF7CF5C4.toInt()
                    "UNLOCKED" -> "Unlocked" to 0xFFFFC24B.toInt()
                    else -> "Unknown" to 0xFFFFFFFF.toInt()
                }
                setTextViewText(R.id.widget_lock_state, lockLabel)
                setTextColor(R.id.widget_lock_state, lockColor)
                setTextViewText(R.id.widget_summary, cached.summary)

                setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
                setOnClickPendingIntent(R.id.widget_lock_button, lockNowIntent(context))
            }
            manager.updateAppWidget(id, views)
        }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun lockNowIntent(context: Context): PendingIntent {
        val intent = Intent(context, AutoLockService::class.java).setAction(AutoLockService.ACTION_LOCK_NOW)
        return PendingIntent.getService(
            context,
            3,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        /** Ask the launcher to redraw the widget after a status change. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AutoLockWidget::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, AutoLockWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}
