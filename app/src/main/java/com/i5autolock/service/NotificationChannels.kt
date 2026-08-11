package com.i5autolock.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.i5autolock.AutoLockApp
import com.i5autolock.R

/** Central creation of the (silent) AutoLock notification channel, with a configurable badge. */
object NotificationChannels {

    /**
     * (Re)creates the channel with the given [showBadge]. Badge visibility is immutable after a
     * channel is created, so changing it requires deleting and recreating the channel.
     */
    fun ensure(context: Context, showBadge: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        // Only recreate if the badge preference actually differs (avoids clobbering the live channel).
        val existing = manager.getNotificationChannel(AutoLockApp.CHANNEL_ID)
        if (existing == null || existing.canShowBadge() != showBadge) {
            if (existing != null) runCatching { manager.deleteNotificationChannel(AutoLockApp.CHANNEL_ID) }
            val channel = NotificationChannel(
                AutoLockApp.CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(showBadge)
                // AutoLock plays its own chime; the channel stays silent.
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }

        // A parallel, minimal-importance channel: same notification, but no status-bar icon.
        if (manager.getNotificationChannel(AutoLockApp.CHANNEL_ID_MINIMAL) == null) {
            val minimal = NotificationChannel(
                AutoLockApp.CHANNEL_ID_MINIMAL,
                context.getString(R.string.notification_channel_min_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.notification_channel_min_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(minimal)
        }

        // Clean up superseded channel ids.
        runCatching { manager.deleteNotificationChannel("autolock_activity") }
        runCatching { manager.deleteNotificationChannel("autolock_activity_v2") }
        runCatching { manager.deleteNotificationChannel("autolock_activity_v3") }
    }
}
