package com.i5autolock.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.i5autolock.data.bluelink.BlueLinkProvider
import com.i5autolock.data.bluelink.model.VehicleStatus
import com.i5autolock.data.settings.AppSettings
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.data.status.StatusCache
import com.i5autolock.domain.ActivityLog
import com.i5autolock.domain.AutoLockController
import com.i5autolock.domain.AutoLockUiState
import com.i5autolock.domain.LogEntry
import com.i5autolock.domain.StatusSummary
import com.i5autolock.service.AutoLockService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.content.Context
import javax.inject.Inject

/** UI state for the live vehicle status card. */
data class VehicleStatusUi(
    val loading: Boolean = false,
    val status: VehicleStatus? = null,
    val lastRefreshEpochMs: Long? = null,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepo: SettingsRepository,
    private val controller: AutoLockController,
    private val provider: BlueLinkProvider,
    private val statusCache: StatusCache,
    activityLog: ActivityLog,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val autoLock: StateFlow<AutoLockUiState> = controller.state

    val log: StateFlow<List<LogEntry>> = activityLog.entries

    private val _vehicleStatus = MutableStateFlow(VehicleStatusUi())
    val vehicleStatus: StateFlow<VehicleStatusUi> = _vehicleStatus

    init {
        // Pull a cached status snapshot on open (no forced remote refresh).
        refreshStatus(force = false)
        // If watching is already enabled, make sure the background service is running.
        viewModelScope.launch {
            if (settingsRepo.settings.first().enabled) AutoLockService.startWatching(appContext)
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

    fun refreshStatus(force: Boolean) = viewModelScope.launch {
        val s = settingsRepo.settings.first()
        if (s.vehicleId == null && !s.demoMode) {
            _vehicleStatus.value = VehicleStatusUi(error = "No vehicle selected.")
            return@launch
        }
        if (!force && !s.autoRefreshOnOpen) return@launch
        val vehicleId = s.vehicleId ?: "demo-ioniq5"
        _vehicleStatus.value = _vehicleStatus.value.copy(loading = true, error = null)
        val client = provider.client(s)
        if (!s.demoMode && !client.ensureFreshSession()) {
            _vehicleStatus.value = VehicleStatusUi(error = "Not signed in.")
            return@launch
        }
        runCatching { client.status(vehicleId, forceRefresh = force) }
            .onSuccess {
                _vehicleStatus.value = VehicleStatusUi(
                    loading = false,
                    status = it,
                    lastRefreshEpochMs = System.currentTimeMillis(),
                )
                statusCache.save(it.lockState.name, StatusSummary.build(it, s.notificationFields))
            }
            .onFailure {
                _vehicleStatus.value = _vehicleStatus.value.copy(
                    loading = false,
                    error = it.message ?: "Couldn't read status.",
                )
            }
    }
}
