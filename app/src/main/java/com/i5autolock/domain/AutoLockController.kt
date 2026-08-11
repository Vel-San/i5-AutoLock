package com.i5autolock.domain

import com.i5autolock.data.bluelink.BlueLinkProvider
import com.i5autolock.data.bluelink.model.CommandResult
import com.i5autolock.data.settings.RunMode
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.domain.detection.DetectionEvent
import com.i5autolock.domain.detection.DetectionState
import com.i5autolock.domain.detection.LockStateMachine
import com.i5autolock.domain.usecase.LockDecision
import com.i5autolock.domain.usecase.LockPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot the UI observes. */
data class AutoLockUiState(
    val detection: DetectionState = DetectionState.IDLE,
    val graceRemaining: Int = 0,
    val lastLockAtEpochMs: Long? = null,
    val statusSummary: String? = null,
)

/**
 * Single source of truth for a lock attempt. Owns timers + side effects; delegates
 * transition logic to [LockStateMachine] and the lock/skip decision to [LockPolicy].
 *
 * Called from the Bluetooth receiver, activity-recognition callbacks, and the UI.
 */
@Singleton
class AutoLockController @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val provider: BlueLinkProvider,
    private val locationHelper: com.i5autolock.data.location.LocationHelper,
    private val statusCache: com.i5autolock.data.status.StatusCache,
    private val log: ActivityLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(AutoLockUiState())
    val state: StateFlow<AutoLockUiState> = _state

    private var machine: LockStateMachine? = null
    private var runJob: Job? = null

    @Volatile private var skipGrace = false

    /** Bluetooth (or manual) trigger: begin an evaluation. */
    fun onTriggerFired() {
        runJob?.cancel()
        runJob = scope.launch { runEvaluation() }
    }

    /** Skip the remaining grace period and lock immediately (notification "Lock now"). */
    fun lockNow() {
        skipGrace = true
        if (runJob?.isActive != true) onTriggerFired()
    }

    fun onWalkingConfirmed() = scope.launch {
        machine?.let { advance(it.next(_state.value.detection, DetectionEvent.WalkingConfirmed)) }
    }

    fun onMovedBeyondGeofence() = scope.launch {
        machine?.let { advance(it.next(_state.value.detection, DetectionEvent.MovedBeyondGeofence)) }
    }

    fun cancel() {
        runJob?.cancel()
        machine?.let { advance(it.next(_state.value.detection, DetectionEvent.UserCancelled)) }
        _state.value = _state.value.copy(detection = DetectionState.ABORTED, graceRemaining = 0)
    }

    /** Manual "run now" for testing from the UI. */
    fun runNow() = onTriggerFired()

    private suspend fun runEvaluation() = mutex.withLock {
        try {
        skipGrace = false
        val settings = settingsRepo.settings.first()
        if (settings.vehicleId == null) {
            log.add(LogLevel.WARN, "No vehicle selected; ignoring trigger.")
            return
        }
        // Respect the optional active-hours schedule.
        val nowMinutes = java.util.Calendar.getInstance().let {
            it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
        }
        if (!settings.isWithinSchedule(nowMinutes)) {
            _state.value = _state.value.copy(detection = DetectionState.SKIPPED, graceRemaining = 0)
            log.add(LogLevel.INFO, "Outside active hours — not locking.")
            return
        }
        // Optionally remember where the car parked.
        if (settings.rememberParkedLocation) {
            locationHelper.currentParkedPlace()?.let { place ->
                settingsRepo.update { it.copy(parkedLabel = place.label, parkedLat = place.lat, parkedLng = place.lng) }
                place.label?.let { log.add(LogLevel.INFO, "Parked near $it.") }
            }
        }
        val sm = LockStateMachine(settings).also { machine = it }
        advance(sm.next(DetectionState.IDLE, DetectionEvent.CarBluetoothDisconnected))
        log.add(LogLevel.INFO, "Trigger fired — evaluating whether you left the car.")

        // Wait until confirmation signals promote us to GRACE (or abort/timeout).
        val confirmDeadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
        while (_state.value.detection == DetectionState.CONFIRMING) {
            if (System.currentTimeMillis() > confirmDeadline) {
                // No corroboration in time — proceed anyway on the BT signal alone.
                advance(DetectionState.GRACE)
                break
            }
            delay(250)
        }
        if (_state.value.detection == DetectionState.ABORTED) {
            log.add(LogLevel.INFO, "Aborted before grace period.")
            return
        }

        // Grace countdown, cancellable if the user returns (job cancelled).
        for (remaining in settings.graceSeconds downTo 1) {
            if (skipGrace) {
                log.add(LogLevel.INFO, "Lock now requested — skipping the wait.")
                break
            }
            _state.value = _state.value.copy(detection = DetectionState.GRACE, graceRemaining = remaining)
            delay(1000)
        }
        advance(sm.next(DetectionState.GRACE, DetectionEvent.GraceElapsed))

        // Verify current lock state before acting.
        val client = provider.client(settings)
        if (!settings.demoMode && !client.ensureFreshSession()) {
            fail("Session expired — please sign in again.")
            return
        }
        val status = runCatching { client.status(settings.vehicleId, forceRefresh = true) }
            .getOrElse { fail("Could not read vehicle status: ${it.message}"); return }

        // Build the user-customisable status line for the notification.
        val summary = if (settings.showStatusInNotification) {
            StatusSummary.build(status, settings.notificationFields).ifBlank { null }
        } else null
        _state.value = _state.value.copy(statusSummary = summary)
        statusCache.save(status.lockState.name, StatusSummary.build(status, settings.notificationFields))

        when (val decision = LockPolicy.decide(status)) {
            is LockDecision.Skip -> {
                _state.value = _state.value.copy(detection = DetectionState.SKIPPED, graceRemaining = 0)
                log.add(LogLevel.INFO, "Skipped: ${decision.reason}.")
            }
            LockDecision.Lock -> performLock(settings, client, status)
        }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.w("AutoLockController", "evaluation failed", t)
            fail("AutoLock hit an error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private suspend fun performLock(
        settings: com.i5autolock.data.settings.AppSettings,
        client: com.i5autolock.data.bluelink.BlueLinkClient,
        status: com.i5autolock.data.bluelink.model.VehicleStatus,
    ) {
        _state.value = _state.value.copy(detection = DetectionState.LOCKING)
        if (settings.runMode == RunMode.DRY_RUN) {
            log.add(LogLevel.SUCCESS, "DRY RUN: would have locked the car now. (No command sent.)")
            _state.value = _state.value.copy(
                detection = DetectionState.LOCKED,
                lastLockAtEpochMs = System.currentTimeMillis(),
            )
            return
        }
        when (val result = client.lock(settings.vehicleId!!)) {
            is CommandResult.Success -> {
                log.add(LogLevel.SUCCESS, "Car locked automatically.")
                // Reflect the new locked state in the notification summary + widget cache,
                // otherwise the "Locked" notification would still show the pre-lock "Unlocked".
                val locked = status.copy(
                    lockState = com.i5autolock.data.bluelink.model.LockState.LOCKED,
                    anyDoorOpen = false,
                )
                val summary = if (settings.showStatusInNotification) {
                    StatusSummary.build(locked, settings.notificationFields).ifBlank { null }
                } else null
                _state.value = _state.value.copy(
                    detection = DetectionState.LOCKED,
                    lastLockAtEpochMs = System.currentTimeMillis(),
                    statusSummary = summary,
                )
                statusCache.save("LOCKED", StatusSummary.build(locked, settings.notificationFields))
            }
            CommandResult.RateLimited -> fail("Locking is temporarily rate-limited. Try again shortly.")
            CommandResult.NotAuthenticated -> fail("Not signed in — please log in again.")
            is CommandResult.Failure -> fail("Lock failed: ${result.reason}")
        }
    }

    private fun fail(message: String) {
        log.add(LogLevel.ERROR, message)
        _state.value = _state.value.copy(detection = DetectionState.ERROR, graceRemaining = 0)
    }

    private fun advance(next: DetectionState) {
        _state.value = _state.value.copy(detection = next)
    }

    private companion object {
        const val CONFIRM_TIMEOUT_MS = 20_000L
    }
}
