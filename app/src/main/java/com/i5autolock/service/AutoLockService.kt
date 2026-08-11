package com.i5autolock.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.domain.AutoLockController
import com.i5autolock.domain.detection.DetectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Short-lived foreground service that runs while an evaluation is in flight. It starts
 * when the car Bluetooth disconnects, drives [AutoLockController], reflects progress in
 * the notification, and stops itself once a terminal state is reached.
 */
@AndroidEntryPoint
class AutoLockService : Service() {

    @Inject lateinit var controller: AutoLockController
    @Inject lateinit var settingsRepo: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var showLockNow: Boolean = true
    private var confirmedFeedback = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                controller.cancel()
                stop()
                return START_NOT_STICKY
            }
            ACTION_LOCK_NOW -> {
                controller.lockNow()
                observe()
                return START_NOT_STICKY
            }
        }

        scope.launch { showLockNow = settingsRepo.settings.first().showLockNowAction }
        startForegroundCompat(DetectionState.CONFIRMING, 0)
        if (intent?.action != ACTION_ALREADY_RUNNING) controller.onTriggerFired()
        observe()
        return START_NOT_STICKY
    }

    private fun observe() {
        if (observeJob != null) return
        observeJob = scope.launch {
            controller.state.collect { s ->
                updateNotification(s.detection, s.graceRemaining, s.statusSummary)
                if (s.detection == DetectionState.LOCKED && !confirmedFeedback) {
                    confirmedFeedback = true
                    playLockConfirmation()
                }
                if (s.detection.isTerminal()) {
                    // Let the user glance at the result, then tear down.
                    delay(4000)
                    stop()
                }
            }
        }
    }

    private fun playLockConfirmation() = scope.launch {
        val settings = settingsRepo.settings.first()
        if (settings.hapticOnLock) vibrateOnce()
        if (settings.soundOnLock) runCatching {
            RingtoneManager.getRingtone(
                this@AutoLockService,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            )?.play()
        }
    }

    private fun vibrateOnce() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun startForegroundCompat(state: DetectionState, grace: Int, summary: String? = null) {
        val notification = AutoLockNotification.build(this, state, grace, summary, showLockNow)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, AutoLockNotification.NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification(state: DetectionState, grace: Int, summary: String?) {
        if (state.isTerminal()) {
            // Detach from foreground so the result notification can linger briefly.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            if (!canPostNotifications()) return
            try {
                NotificationManagerCompat.from(this)
                    .notify(AutoLockNotification.NOTIFICATION_ID, AutoLockNotification.build(this, state, grace, summary, showLockNow))
            } catch (_: SecurityException) {
                // Notifications permission revoked between check and post — ignore.
            }
        } else {
            startForegroundCompat(state, grace, summary)
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun stop() {
        observeJob?.cancel()
        observeJob = null
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.i5autolock.CANCEL"
        const val ACTION_LOCK_NOW = "com.i5autolock.LOCK_NOW"
        const val ACTION_ALREADY_RUNNING = "com.i5autolock.ALREADY_RUNNING"

        fun start(context: Context) {
            val intent = Intent(context, AutoLockService::class.java)
            context.startForegroundService(intent)
        }
    }
}

private fun DetectionState.isTerminal(): Boolean = when (this) {
    DetectionState.LOCKED, DetectionState.SKIPPED, DetectionState.ABORTED, DetectionState.ERROR -> true
    else -> false
}
