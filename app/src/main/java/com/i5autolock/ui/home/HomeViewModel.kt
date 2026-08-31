package com.i5autolock.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.i5autolock.data.bluelink.BlueLinkProvider
import com.i5autolock.data.bluelink.model.VehicleStatus
import com.i5autolock.data.bluelink.model.mergedOnto
import com.i5autolock.data.settings.AppSettings
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.data.status.StatusCache
import com.i5autolock.domain.ActivityLog
import com.i5autolock.domain.AutoLockController
import com.i5autolock.domain.AutoLockUiState
import com.i5autolock.domain.LogEntry
import com.i5autolock.domain.StatusSummary
import com.i5autolock.service.AutoLockService
import com.i5autolock.work.StatusRefreshWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import javax.inject.Inject

/** UI state for the live vehicle status card. */
data class VehicleStatusUi(
    val loading: Boolean = false,
    val status: VehicleStatus? = null,
    val lastRefreshEpochMs: Long? = null,
    val error: String? = null,
    /** True when the session expired and the user needs to sign in again. */
    val needsReauth: Boolean = false,
    /** True when the 12V battery is below the user's warning threshold. */
    val lowVoltage: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepo: SettingsRepository,
    private val controller: AutoLockController,
    private val provider: BlueLinkProvider,
    private val statusCache: StatusCache,
    private val metrics: com.i5autolock.data.metrics.ApiMetrics,
    private val secureStore: com.i5autolock.data.secure.SecureStore,
    activityLog: ActivityLog,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val autoLock: StateFlow<AutoLockUiState> = controller.state

    val log: StateFlow<List<LogEntry>> = activityLog.entries

    private val _vehicleStatus = MutableStateFlow(VehicleStatusUi())
    val vehicleStatus: StateFlow<VehicleStatusUi> = _vehicleStatus

    // Transient one-shot messages surfaced as a toast (rate-limit, low 12V, throttle…).
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    fun clearNotice() { _notice.value = null }

    init {
        // Show the last-known status instantly (no blanks on reopen) and keep it in sync with the
        // cache, which is also written by the lock flow and the background refresh worker.
        viewModelScope.launch {
            statusCache.cached.collect { c ->
                val cached = c.toVehicleStatus() ?: return@collect
                val s = settingsRepo.settings.first()
                _vehicleStatus.update { ui ->
                    if (ui.loading) ui else {
                        val merged = cached.mergedOnto(ui.status)
                        ui.copy(
                            status = merged,
                            lastRefreshEpochMs = c.updatedAtEpochMs.takeIf { it > 0 } ?: ui.lastRefreshEpochMs,
                            lowVoltage = isLowVoltage(merged, s),
                        )
                    }
                }
            }
        }
        // Pull a fresh snapshot on open (respects the auto-refresh-on-open setting).
        refreshStatus(force = false)
        // Service start + WorkManager scheduling do disk I/O — keep them off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            val s = settingsRepo.settings.first()
            if (s.enabled) AutoLockService.startWatching(appContext)
            // (Re)schedule the periodic background refresh to match the setting.
            StatusRefreshWorker.schedule(appContext, s.autoRefreshIntervalMinutes)
        }
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.update { it.copy(enabled = enabled) }
        if (enabled) AutoLockService.startWatching(appContext) else AutoLockService.stopWatching(appContext)
    }

    fun runNow() {
        // Drive the whole flow through the foreground service so the user gets the live
        // notification plus haptic/sound feedback, exactly like a real Bluetooth trigger.
        AutoLockService.start(appContext)
    }

    fun cancel() = controller.cancel()

    /** Whether a BlueLink PIN is stored, so the UI knows to ask for it before a manual lock. */
    fun hasPin(): Boolean = secureStore.loadPin() != null

    private val _lockResult = MutableStateFlow<String?>(null)
    val lockResult: StateFlow<String?> = _lockResult
    fun clearLockResult() { _lockResult.value = null }

    /** Manually lock now — gated by the BlueLink PIN when one is stored. */
    fun manualLock(pin: String?) = viewModelScope.launch {
        val s = settingsRepo.settings.first()
        val stored = secureStore.loadPin()
        if (stored != null && pin != stored) {
            _lockResult.value = "Incorrect PIN."
            return@launch
        }
        val vehicleId = s.vehicleId ?: run { _lockResult.value = "No vehicle selected."; return@launch }
        val client = provider.client(s)
        if (!s.demoMode && !client.ensureFreshSession()) {
            _lockResult.value = "Session expired — sign in again."
            _vehicleStatus.update { it.copy(needsReauth = true) }
            return@launch
        }
        when (val result = client.lock(vehicleId)) {
            is com.i5autolock.data.bluelink.model.CommandResult.Success -> {
                _lockResult.value = "Locked ✓"
                // Optimistic UI: cached endpoint won't reflect the new state until the car reports
                // back to Hyundai (can be minutes, longer under 503 cooldown). Update locally and
                // skip an immediate refresh — a fresh cached read would overwrite this with stale.
                _vehicleStatus.update { ui ->
                    val next = ui.status?.copy(
                        lockState = com.i5autolock.data.bluelink.model.LockState.LOCKED,
                        anyDoorOpen = false,
                        timestamp = System.currentTimeMillis(),
                    )
                    ui.copy(status = next, lastRefreshEpochMs = System.currentTimeMillis())
                }
            }
            com.i5autolock.data.bluelink.model.CommandResult.RateLimited -> _lockResult.value = "Rate-limited — try again shortly."
            com.i5autolock.data.bluelink.model.CommandResult.NotAuthenticated -> {
                _lockResult.value = "Not signed in."
                _vehicleStatus.update { it.copy(needsReauth = true) }
            }
            is com.i5autolock.data.bluelink.model.CommandResult.Failure ->
                _lockResult.value = "Lock failed: ${result.reason}"
        }
    }

    /** Manually unlock now — gated by the BlueLink PIN when one is stored. */
    fun manualUnlock(pin: String?) = viewModelScope.launch {
        val s = settingsRepo.settings.first()
        val stored = secureStore.loadPin()
        if (stored != null && pin != stored) {
            _lockResult.value = "Incorrect PIN."
            return@launch
        }
        val vehicleId = s.vehicleId ?: run { _lockResult.value = "No vehicle selected."; return@launch }
        val client = provider.client(s)
        if (!s.demoMode && !client.ensureFreshSession()) {
            _lockResult.value = "Session expired — sign in again."
            _vehicleStatus.update { it.copy(needsReauth = true) }
            return@launch
        }
        when (val result = client.unlock(vehicleId)) {
            is com.i5autolock.data.bluelink.model.CommandResult.Success -> {
                _lockResult.value = "Unlocked ✓"
                _vehicleStatus.update { ui ->
                    val next = ui.status?.copy(
                        lockState = com.i5autolock.data.bluelink.model.LockState.UNLOCKED,
                        timestamp = System.currentTimeMillis(),
                    )
                    ui.copy(status = next, lastRefreshEpochMs = System.currentTimeMillis())
                }
            }
            com.i5autolock.data.bluelink.model.CommandResult.RateLimited -> _lockResult.value = "Rate-limited — try again shortly."
            com.i5autolock.data.bluelink.model.CommandResult.NotAuthenticated -> {
                _lockResult.value = "Not signed in."
                _vehicleStatus.update { it.copy(needsReauth = true) }
            }
            is com.i5autolock.data.bluelink.model.CommandResult.Failure ->
                _lockResult.value = "Unlock failed: ${result.reason}"
        }
    }

    /** Switch the active vehicle (per-vehicle picker on Home). */
    fun selectVehicle(v: com.i5autolock.data.settings.KnownVehicle) = viewModelScope.launch {
        settingsRepo.update { it.copy(vehicleId = v.id, vehicleNickname = v.nickname) }
        _vehicleStatus.update { it.copy(status = null, lastRefreshEpochMs = null) }
        refreshStatus(force = false)
    }

    fun refreshStatus(force: Boolean) = viewModelScope.launch {
        val s = settingsRepo.settings.first()
        if (s.vehicleId == null && !s.demoMode) {
            _vehicleStatus.update { it.copy(loading = false, error = "No vehicle selected.") }
            return@launch
        }
        if (!force && !s.autoRefreshOnOpen) return@launch
        // On open (not a manual/pull refresh), skip the network round-trip when the cached snapshot
        // is still fresh — the card already shows it, so opening the app repeatedly no longer spams
        // the API or stutters. A manual refresh (force=true) always fetches.
        if (!force) {
            val cachedAt = statusCache.cached.first().updatedAtEpochMs
            if (cachedAt > 0 && System.currentTimeMillis() - cachedAt < ON_OPEN_FRESH_MS) return@launch
        }
        if (force) {
            // Respect the API's rate-limit cooldown so we don't get further throttled.
            val snap = metrics.snapshot.value
            if (snap.isRateLimited()) {
                val secs = ((snap.rateLimitedUntilEpochMs!! - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                val msg = "Rate-limited — try again in ${secs}s."
                _vehicleStatus.update { it.copy(loading = false, error = msg) }
                _notice.value = msg
                return@launch
            }
            // The minimum live-poll interval is enforced in the client (serves cached within it),
            // so we don't block the refresh here — no redundant "please wait" guard.
        }
        val vehicleId = s.vehicleId ?: "demo-ioniq5"
        _vehicleStatus.update { it.copy(loading = true, error = null) }
        val client = provider.client(s)
        // All the Keystore/token/network work runs off the main thread so the UI never stutters.
        // null result = session expired (needs re-auth).
        val result: Result<VehicleStatus>? = withContext(Dispatchers.IO) {
            if (!s.demoMode && !client.ensureFreshSession()) null
            else runCatching { client.status(vehicleId, forceRefresh = force) }
        }
        if (result == null) {
            _vehicleStatus.update { it.copy(loading = false, error = "Session expired — please sign in again.", needsReauth = true) }
            return@launch
        }
        result
            .onSuccess { fresh ->
                // Merge so a partial (force-refresh) response never wipes existing detail — and the
                // notification/widget summary is built from the merged status too.
                val merged = fresh.mergedOnto(_vehicleStatus.value.status)
                val low = isLowVoltage(merged, s)
                _vehicleStatus.update { ui ->
                    ui.copy(
                        loading = false,
                        error = null,
                        needsReauth = false,
                        status = merged,
                        lastRefreshEpochMs = System.currentTimeMillis(),
                        lowVoltage = low,
                    )
                }
                if (low) merged.twelveVoltPercent?.let { _notice.value = "12V battery low ($it%). Consider charging or driving soon." }
                statusCache.saveStatus(merged, StatusSummary.build(merged, s.notificationFields, StatusSummary.Labels.from(appContext)))
            }
            .onFailure { e ->
                // Preserve the visible status; only report the error.
                _vehicleStatus.update { it.copy(loading = false, error = e.message ?: "Couldn't read status.") }
            }
    }

    private fun isLowVoltage(status: VehicleStatus, s: AppSettings): Boolean =
        s.lowVoltageAlert && (status.twelveVoltPercent?.let { it < s.lowVoltageThreshold } ?: false)

    private companion object {
        // Don't re-fetch on app open if the cached status is younger than this.
        const val ON_OPEN_FRESH_MS = 5 * 60_000L
    }
}
