package com.i5autolock.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.i5autolock.AutoLockApp
import com.i5autolock.MainActivity
import com.i5autolock.R
import com.i5autolock.domain.detection.DetectionState

/** Builds the ongoing foreground notification shown during an evaluation. */
object AutoLockNotification {

    const val NOTIFICATION_ID = 4201

    /** Persistent "watching" notification shown whenever AutoLock is enabled. */
    fun buildWatching(
        context: Context,
        statusSummary: String? = null,
        pinned: Boolean = true,
        channelId: String = AutoLockApp.CHANNEL_ID,
        @DrawableRes smallIcon: Int = R.drawable.ic_stat_autolock,
    ): Notification {
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
        // If pinned and the user swipes it away, immediately re-assert it so watching stays visible.
        val reviveIntent = PendingIntent.getForegroundService(
            context,
            6,
            Intent(context, AutoLockService::class.java).setAction(AutoLockService.ACTION_START_WATCH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Show the live vehicle status as the main line when we have it; fall back otherwise.
        val text = statusSummary?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notif_watching_body)
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(context.getString(R.string.notif_watching_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .apply { if (pinned) setDeleteIntent(reviveIntent) }
            .addAction(0, context.getString(R.string.notif_turn_off), stopIntent)
            .build()
    }

    fun build(
        context: Context,
        state: DetectionState,
        graceRemaining: Int,
        statusSummary: String? = null,
        showLockNow: Boolean = false,
        channelId: String = AutoLockApp.CHANNEL_ID,
        @DrawableRes smallIcon: Int = R.drawable.ic_stat_autolock,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val (title, baseText) = when (state) {
            DetectionState.CONFIRMING -> context.getString(R.string.notif_confirming_title) to context.getString(R.string.notif_confirming_body)
            DetectionState.GRACE -> context.getString(R.string.notif_grace_title) to context.getString(R.string.notif_grace_body, graceRemaining)
            DetectionState.VERIFYING -> context.getString(R.string.notif_verifying_title) to context.getString(R.string.notif_verifying_body)
            DetectionState.AWAITING_CONFIRM -> context.getString(R.string.notif_confirm_title) to context.getString(R.string.notif_confirm_body)
            DetectionState.LOCKING -> context.getString(R.string.notif_locking_title) to context.getString(R.string.notif_locking_body)
            DetectionState.LOCKED -> context.getString(R.string.notif_locked_title) to context.getString(R.string.notif_locked_body)
            DetectionState.SKIPPED -> context.getString(R.string.notif_skipped_title) to context.getString(R.string.notif_skipped_body)
            else -> context.getString(R.string.notif_default_title) to context.getString(R.string.notif_default_body)
        }
        val text = if (!statusSummary.isNullOrBlank()) "$baseText\n$statusSummary" else baseText

        val waiting = state == DetectionState.CONFIRMING || state == DetectionState.GRACE ||
            state == DetectionState.AWAITING_CONFIRM
        // "Lock now" is useful while we're still waiting (confirming / grace / awaiting confirm).
        val canLockNow = showLockNow && waiting

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(state != DetectionState.LOCKED && state != DetectionState.SKIPPED)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)

        if (waiting) {
            builder.addAction(
                0,
                context.getString(R.string.notif_cancel),
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
                context.getString(R.string.notif_lock_now),
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
