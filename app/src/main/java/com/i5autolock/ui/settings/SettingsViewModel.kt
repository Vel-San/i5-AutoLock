package com.i5autolock.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.i5autolock.data.bluelink.BlueLinkProvider
import com.i5autolock.data.bluelink.Region
import com.i5autolock.data.bluelink.model.Vehicle
import com.i5autolock.data.device.BluetoothDevices
import com.i5autolock.data.device.PairedDevice
import com.i5autolock.data.settings.AppSettings
import com.i5autolock.data.settings.NotificationField
import com.i5autolock.data.settings.RunMode
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.data.settings.ThemeMode
import com.i5autolock.domain.ActivityLog
import com.i5autolock.domain.LogLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiExtras(
    val pairedDevices: List<PairedDevice> = emptyList(),
    val vehicles: List<Vehicle> = emptyList(),
    val loadingVehicles: Boolean = false,
    val signedIn: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val bluetoothDevices: BluetoothDevices,
    private val provider: BlueLinkProvider,
    private val log: ActivityLog,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _extras = MutableStateFlow(SettingsUiExtras())
    val extras: StateFlow<SettingsUiExtras> = _extras

    init {
        refreshDevices()
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val client = provider.client(s)
            _extras.value = _extras.value.copy(signedIn = client.isAuthenticated())
        }
    }

    fun refreshDevices() {
        _extras.value = _extras.value.copy(pairedDevices = bluetoothDevices.bondedDevices())
    }

    private fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        settingsRepo.update(transform)
        provider.invalidate()
    }

    fun setRegion(region: Region) = update { it.copy(region = region) }
    fun setDemoMode(demo: Boolean) = update { it.copy(demoMode = demo) }
    fun setRunMode(mode: RunMode) = update { it.copy(runMode = mode) }
    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }
    fun setShowStatusInNotification(enabled: Boolean) = update { it.copy(showStatusInNotification = enabled) }
    fun toggleNotificationField(field: NotificationField, enabled: Boolean) = update {
        val next = it.notificationFields.toMutableSet().apply { if (enabled) add(field) else remove(field) }
        it.copy(notificationFields = next)
    }
    fun setShowLockNowAction(enabled: Boolean) = update { it.copy(showLockNowAction = enabled) }
    fun setAutoRefreshOnOpen(enabled: Boolean) = update { it.copy(autoRefreshOnOpen = enabled) }
    fun setHapticOnLock(enabled: Boolean) = update { it.copy(hapticOnLock = enabled) }
    fun setSoundOnLock(enabled: Boolean) = update { it.copy(soundOnLock = enabled) }
    fun setRememberParkedLocation(enabled: Boolean) = update { it.copy(rememberParkedLocation = enabled) }
    fun setScheduleEnabled(enabled: Boolean) = update { it.copy(scheduleEnabled = enabled) }
    fun setScheduleStart(minutes: Int) = update { it.copy(scheduleStartMinutes = minutes) }
    fun setScheduleEnd(minutes: Int) = update { it.copy(scheduleEndMinutes = minutes) }
    fun setGrace(seconds: Int) = update { it.copy(graceSeconds = seconds) }
    fun setUseBluetooth(v: Boolean) = update { it.copy(useBluetoothTrigger = v) }
    fun setUseActivity(v: Boolean) = update { it.copy(useActivityRecognition = v) }
    fun setUseGeofence(v: Boolean) = update { it.copy(useGeofence = v) }
    fun setGeofenceRadius(m: Int) = update { it.copy(geofenceRadiusMeters = m) }
    fun setRequireConfirmation(v: Boolean) = update { it.copy(requireConfirmationBeforeLock = v) }

    fun selectCarDevice(device: PairedDevice) =
        update { it.copy(carBluetoothMac = device.mac, carBluetoothName = device.name) }

    fun selectVehicle(vehicle: Vehicle) =
        update { it.copy(vehicleId = vehicle.id, vehicleNickname = vehicle.nickname) }

    fun loadVehicles() = viewModelScope.launch {
        _extras.value = _extras.value.copy(loadingVehicles = true)
        val s = settingsRepo.settings.first()
        val result = runCatching { provider.client(s).vehicles() }
        _extras.value = _extras.value.copy(
            loadingVehicles = false,
            vehicles = result.getOrElse {
                log.add(LogLevel.ERROR, "Couldn't load vehicles: ${it.message}")
                emptyList()
            },
        )
    }

    fun signOut() = viewModelScope.launch {
        val s = settingsRepo.settings.first()
        provider.client(s).clearSession()
        settingsRepo.update { it.copy(accountEmail = null, vehicleId = null, vehicleNickname = null) }
        _extras.value = _extras.value.copy(signedIn = false, vehicles = emptyList())
        log.add(LogLevel.INFO, "Signed out.")
    }

    fun onSignedIn(email: String) = viewModelScope.launch {
        settingsRepo.update { it.copy(accountEmail = email) }
        _extras.value = _extras.value.copy(signedIn = true)
        loadVehicles()
    }
}
