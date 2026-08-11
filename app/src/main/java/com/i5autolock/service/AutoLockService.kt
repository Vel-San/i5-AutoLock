package com.i5autolock.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
    @Inject lateinit var statusCache: com.i5autolock.data.status.StatusCache
    @Inject lateinit var activityRecognition: com.i5autolock.data.detection.ActivityRecognitionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var showLockNow: Boolean = true
    private var pinNotification: Boolean = true
    private var showStatus: Boolean = true
    // Last-known vehicle status line, kept so the "watching" notification can still show it.
    private var lastSummary: String? = null
    private var currentDetection: DetectionState = DetectionState.IDLE
    private var confirmedFeedback = false
    // True while AutoLock is enabled and should keep watching in the background persistently.
    private var watchMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Single source of truth for the status line: keep it fresh for the whole service lifetime
        // so the "watching" notification always reflects the last-known vehicle status.
        scope.launch {
            showStatus = settingsRepo.settings.first().showStatusInNotification
            statusCache.cached.collect { c ->
                lastSummary = c.summary.ifBlank { null }
                // Refresh the watching notification while resting so the new status shows.
                if (watchMode && currentDetection == DetectionState.IDLE) startForegroundWatch()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system recreated us after a kill (START_STICKY). Resume watching
        // if it's still enabled — never treat this as a fresh "leaving" trigger.
        if (intent == null) {
            startForegroundWatch()
            watchMode = true
            scope.launch {
                val s = settingsRepo.settings.first()
                showLockNow = s.showLockNowAction
                pinNotification = s.pinNotification
                showStatus = s.showStatusInNotification
                lastSummary = statusCache.cached.first().summary.ifBlank { null }
                if (!s.enabled) {
                    watchMode = false
                    stop()
                } else {
                    startForegroundWatch()
                }
            }
            observe()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP_WATCH -> {
                // Satisfy the foreground-service contract for this start, then tear down.
                startForegroundWatch()
                watchMode = false
                controller.cancel()
                // Turning off from the notification must also flip the in-app toggle.
                scope.launch {
                    settingsRepo.update { it.copy(enabled = false) }
                    stop()
                }
                return START_NOT_STICKY
            }
            ACTION_START_WATCH -> {
                watchMode = true
                startForegroundWatch()
                scope.launch {
                    val s = settingsRepo.settings.first()
                    showLockNow = s.showLockNowAction
                    pinNotification = s.pinNotification
                    showStatus = s.showStatusInNotification
                    lastSummary = statusCache.cached.first().summary.ifBlank { null }
                    if (s.useActivityRecognition) activityRecognition.start() else activityRecognition.stop()
                    startForegroundWatch()
                }
                observe()
                return START_STICKY
            }
            ACTION_CANCEL -> {
                controller.cancel()
                if (watchMode) startForegroundWatch() else stop()
                return if (watchMode) START_STICKY else START_NOT_STICKY
            }
            ACTION_LOCK_NOW -> {
                controller.lockNow()
                observe()
                return if (watchMode) START_STICKY else START_NOT_STICKY
            }
        }

        // Default: a trigger fired (Bluetooth disconnect or "Simulate leaving").
        scope.launch {
            val s = settingsRepo.settings.first()
            showLockNow = s.showLockNowAction
            showStatus = s.showStatusInNotification
            // Soft "listening" chime when an evaluation begins.
            if (s.soundOnLock && intent.action != ACTION_ALREADY_RUNNING) {
                runCatching { com.i5autolock.data.sound.EvChime.playNotify() }
            }
        }
        startForegroundCompat(DetectionState.CONFIRMING, 0)
        if (intent.action != ACTION_ALREADY_RUNNING) controller.onTriggerFired()
        observe()
        return if (watchMode) START_STICKY else START_NOT_STICKY
    }

    // If the user swipes the app out of Recents, keep watching alive when enabled.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (watchMode) startForegroundWatch()
        super.onTaskRemoved(rootIntent)
    }

    private fun observe() {
        if (observeJob != null) return
        observeJob = scope.launch {
            controller.state.collect { s ->
                currentDetection = s.detection
                updateNotification(s.detection, s.graceRemaining, s.statusSummary)
                if (s.detection == DetectionState.LOCKED && !confirmedFeedback) {
                    confirmedFeedback = true
                    playLockConfirmation()
                }
                if (s.detection.isTerminal()) {
                    // Let the user glance at the result, then either keep watching or tear down.
                    delay(4000)
                    if (watchMode) {
                        confirmedFeedback = false
                        startForegroundWatch()
                    } else {
                        stop()
                    }
                }
            }
        }
    }

    private fun playLockConfirmation() = scope.launch {
        val settings = settingsRepo.settings.first()
        if (settings.hapticOnLock) vibrateOnce()
        if (settings.soundOnLock) runCatching {
            com.i5autolock.data.sound.LockSound.play(this@AutoLockService, settings.customLockSoundUri)
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

    private fun startForegroundWatch() {
        val summary = if (showStatus) lastSummary else null
        val notification = AutoLockNotification.buildWatching(this, statusSummary = summary, pinned = pinNotification)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, AutoLockNotification.NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification(state: DetectionState, grace: Int, summary: String?) {
        when {
            // Resting state while watching → the persistent "watching" notification.
            watchMode && state == DetectionState.IDLE -> startForegroundWatch()
            // Terminal result on a one-off run (not watching) → detach so it lingers, then stop.
            state.isTerminal() && !watchMode -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                if (!canPostNotifications()) return
                try {
                    NotificationManagerCompat.from(this)
                        .notify(AutoLockNotification.NOTIFICATION_ID, AutoLockNotification.build(this, state, grace, summary, showLockNow))
                } catch (_: SecurityException) {
                    // Notifications permission revoked between check and post — ignore.
                }
            }
            else -> startForegroundCompat(state, grace, summary)
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
        activityRecognition.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
        const val ACTION_START_WATCH = "com.i5autolock.START_WATCH"
        const val ACTION_STOP_WATCH = "com.i5autolock.STOP_WATCH"

        /** Fire a one-off evaluation (Bluetooth trigger / "Simulate leaving"). */
        fun start(context: Context) = safeStart(context, null)

        /** Begin persistent background watching (call when AutoLock is enabled). */
        fun startWatching(context: Context) = safeStart(context, ACTION_START_WATCH)

        /** Stop persistent background watching (call when AutoLock is disabled). */
        fun stopWatching(context: Context) = safeStart(context, ACTION_STOP_WATCH)

        private fun safeStart(context: Context, action: String?) {
            val intent = Intent(context, AutoLockService::class.java).apply { action?.let { setAction(it) } }
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                // e.g. ForegroundServiceStartNotAllowedException when the OS blocks a background start.
                android.util.Log.w("AutoLockService", "startForegroundService blocked: ${t.message}")
            }
        }
    }
}

private fun DetectionState.isTerminal(): Boolean = when (this) {
    DetectionState.LOCKED, DetectionState.SKIPPED, DetectionState.ABORTED, DetectionState.ERROR -> true
    else -> false
}
