package com.i5autolock.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.i5autolock.AutoLockApp
import com.i5autolock.MainActivity
import com.i5autolock.R
import com.i5autolock.domain.detection.DetectionState

/** Builds the ongoing foreground notification shown during an evaluation. */
object AutoLockNotification {

    const val NOTIFICATION_ID = 4201

    /** Persistent "watching" notification shown whenever AutoLock is enabled. */
    fun buildWatching(context: Context, statusSummary: String? = null): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context,
            5,
            Intent(context, AutoLockService::class.java).setAction(AutoLockService.ACTION_STOP_WATCH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // If the user swipes the notification away, immediately re-assert it: watching must persist.
        val reviveIntent = PendingIntent.getForegroundService(
            context,
            6,
            Intent(context, AutoLockService::class.java).setAction(AutoLockService.ACTION_START_WATCH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (!statusSummary.isNullOrBlank()) {
            "Monitoring for you leaving the car.\n$statusSummary"
        } else {
            "Monitoring for you leaving the car."
        }
        return NotificationCompat.Builder(context, AutoLockApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("AutoLock is watching")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .setDeleteIntent(reviveIntent)
            .addAction(0, "Turn off", stopIntent)
            .build()
    }

    fun build(
        context: Context,
        state: DetectionState,
        graceRemaining: Int,
        statusSummary: String? = null,
        showLockNow: Boolean = false,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val (title, baseText) = when (state) {
            DetectionState.CONFIRMING -> "Checking if you left the car" to "Confirming you've walked away…"
            DetectionState.GRACE -> "Locking soon" to "Locking in ${graceRemaining}s. Tap to cancel."
            DetectionState.VERIFYING -> "Verifying" to "Reading vehicle status…"
            DetectionState.LOCKING -> "Locking" to "Sending lock command…"
            DetectionState.LOCKED -> "Locked" to "Your car has been locked."
            DetectionState.SKIPPED -> "No action needed" to "Car was already secure."
            else -> "AutoLock" to "Watching for you leaving the car."
        }
        val text = if (!statusSummary.isNullOrBlank()) "$baseText\n$statusSummary" else baseText

        // "Lock now" is only useful while we're still waiting (confirming / grace).
        val canLockNow = showLockNow && (state == DetectionState.CONFIRMING || state == DetectionState.GRACE)

        val builder = NotificationCompat.Builder(context, AutoLockApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(state != DetectionState.LOCKED && state != DetectionState.SKIPPED)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)

        if (state == DetectionState.CONFIRMING || state == DetectionState.GRACE) {
            builder.addAction(
                0,
                "Cancel",
                PendingIntent.getService(
                    context,
                    1,
                    Intent(context, AutoLockService::class.java).setAction(AutoLockService.ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        if (canLockNow) {
            builder.addAction(
                0,
                "Lock now",
                PendingIntent.getService(
                    context,
                    2,
                    Intent(context, AutoLockService::class.java).setAction(AutoLockService.ACTION_LOCK_NOW),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder.build()
    }
}
