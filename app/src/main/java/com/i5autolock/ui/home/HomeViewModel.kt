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
import kotlinx.coroutines.launch
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

    // Guard against hammering the (rate-limited) BlueLink API with rapid manual refreshes.
    private var lastFetchAtMs = 0L

    init {
        // Show the last-known status instantly (no blanks on reopen) and keep it in sync with the
        // cache, which is also written by the lock flow and the background refresh worker.
        viewModelScope.launch {
            statusCache.cached.collect { c ->
                val cached = c.toVehicleStatus() ?: return@collect
                _vehicleStatus.update { ui ->
                    if (ui.loading) ui else ui.copy(
                        status = cached.mergedOnto(ui.status),
                        lastRefreshEpochMs = c.updatedAtEpochMs.takeIf { it > 0 } ?: ui.lastRefreshEpochMs,
                    )
                }
            }
        }
        // Pull a fresh snapshot on open (respects the auto-refresh-on-open setting).
        refreshStatus(force = false)
        viewModelScope.launch {
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
        when (client.lock(vehicleId)) {
            is com.i5autolock.data.bluelink.model.CommandResult.Success -> {
                _lockResult.value = "Locked ✓"
                refreshStatus(force = false)
            }
            com.i5autolock.data.bluelink.model.CommandResult.RateLimited -> _lockResult.value = "Rate-limited — try again shortly."
            com.i5autolock.data.bluelink.model.CommandResult.NotAuthenticated -> {
                _lockResult.value = "Not signed in."
                _vehicleStatus.update { it.copy(needsReauth = true) }
            }
            is com.i5autolock.data.bluelink.model.CommandResult.Failure -> _lockResult.value = "Lock failed."
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
        if (force) {
            // Respect the API's rate-limit cooldown so we don't get further throttled.
            val snap = metrics.snapshot.value
            if (snap.isRateLimited()) {
                val secs = ((snap.rateLimitedUntilEpochMs!! - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                _vehicleStatus.update { it.copy(loading = false, error = "Rate-limited — try again in ${secs}s.") }
                return@launch
            }
            val now = System.currentTimeMillis()
            if (now - lastFetchAtMs < MIN_REFRESH_INTERVAL_MS) {
                _vehicleStatus.update { it.copy(loading = false, error = "Just refreshed — give it a few seconds.") }
                return@launch
            }
            lastFetchAtMs = now
        }
        val vehicleId = s.vehicleId ?: "demo-ioniq5"
        _vehicleStatus.update { it.copy(loading = true, error = null) }
        val client = provider.client(s)
        if (!s.demoMode && !client.ensureFreshSession()) {
            // Session expired — keep details but prompt a re-sign-in.
            _vehicleStatus.update { it.copy(loading = false, error = "Session expired — please sign in again.", needsReauth = true) }
            return@launch
        }
        runCatching { client.status(vehicleId, forceRefresh = force) }
            .onSuccess { fresh ->
                // Merge so a partial (force-refresh) response never wipes existing detail — and the
                // notification/widget summary is built from the merged status too.
                val merged = fresh.mergedOnto(_vehicleStatus.value.status)
                _vehicleStatus.update { ui ->
                    ui.copy(
                        loading = false,
                        error = null,
                        needsReauth = false,
                        status = merged,
                        lastRefreshEpochMs = System.currentTimeMillis(),
                    )
                }
                statusCache.saveStatus(merged, StatusSummary.build(merged, s.notificationFields))
            }
            .onFailure { e ->
                // Preserve the visible status; only report the error.
                _vehicleStatus.update { it.copy(loading = false, error = e.message ?: "Couldn't read status.") }
            }
    }

    private companion object {
        const val MIN_REFRESH_INTERVAL_MS = 6_000L
    }
}
