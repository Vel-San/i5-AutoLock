package com.i5autolock.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.i5autolock.data.backup.BackupManager
import com.i5autolock.data.bluelink.BlueLinkProvider
import com.i5autolock.data.bluelink.Region
import com.i5autolock.data.bluelink.model.Vehicle
import com.i5autolock.data.device.BluetoothDevices
import com.i5autolock.data.device.PairedDevice
import com.i5autolock.data.settings.AppSettings
import com.i5autolock.data.settings.KnownVehicle
import com.i5autolock.data.settings.NotificationField
import com.i5autolock.data.settings.RunMode
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.data.settings.ThemeMode
import com.i5autolock.domain.ActivityLog
import com.i5autolock.domain.LogLevel
import com.i5autolock.service.AutoLockService
import com.i5autolock.work.StatusRefreshWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiExtras(
    val pairedDevices: List<PairedDevice> = emptyList(),
    val vehicles: List<Vehicle> = emptyList(),
    val loadingVehicles: Boolean = false,
    val signedIn: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: android.content.Context,
    private val settingsRepo: SettingsRepository,
    private val bluetoothDevices: BluetoothDevices,
    private val provider: BlueLinkProvider,
    private val backupManager: BackupManager,
    private val log: ActivityLog,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _extras = MutableStateFlow(SettingsUiExtras())
    val extras: StateFlow<SettingsUiExtras> = _extras

    // One-shot user messages (backup results, etc.) surfaced as a toast by the screen.
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    fun clearNotice() { _notice.value = null }

    // Multi-line diagnostic report shown in a scrollable dialog.
    private val _diagnosticReport = MutableStateFlow<String?>(null)
    val diagnosticReport: StateFlow<String?> = _diagnosticReport
    fun clearDiagnosticReport() { _diagnosticReport.value = null }

    init {
        refreshDevices()
        // Auth checks read the encrypted token store (Keystore/crypto) which is slow on first access,
        // so everything blocking runs off the main thread to keep the Settings screen responsive.
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            // Show the cached vehicle list instantly so the picker survives restarts/navigation.
            if (s.knownVehicles.isNotEmpty()) {
                _extras.value = _extras.value.copy(vehicles = s.knownVehicles.map { it.toVehicle() })
            }
            // Signed-in status must reflect the REAL account, not the demo client — otherwise turning
            // on Demo mode makes it look signed-out even though the session (and email) still exist.
            val signedIn = s.accountEmail != null || withContext(Dispatchers.IO) {
                runCatching { provider.client(s.copy(demoMode = false)).isAuthenticated() }.getOrDefault(false)
            }
            _extras.value = _extras.value.copy(signedIn = signedIn)
            // Auto-load vehicles on open when signed in — no more "hit Load every launch".
            if (signedIn && !s.demoMode) loadVehicles()
        }
    }

    fun refreshDevices() = viewModelScope.launch {
        val devices = withContext(Dispatchers.IO) { bluetoothDevices.bondedDevices() }
        _extras.value = _extras.value.copy(pairedDevices = devices)
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
    fun setPinNotification(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.update { it.copy(pinNotification = enabled) }
        provider.invalidate()
        // Re-post the watching notification so the new pin behaviour applies immediately.
        if (settingsRepo.settings.first().enabled) AutoLockService.startWatching(appContext)
    }
    fun setAutoRefreshOnOpen(enabled: Boolean) = update { it.copy(autoRefreshOnOpen = enabled) }
    fun setAutoRefreshInterval(minutes: Int) = viewModelScope.launch {
        settingsRepo.update { it.copy(autoRefreshIntervalMinutes = minutes) }
        StatusRefreshWorker.schedule(appContext, minutes)
    }
    fun setHapticOnLock(enabled: Boolean) = update { it.copy(hapticOnLock = enabled) }
    fun setSoundOnLock(enabled: Boolean) = update { it.copy(soundOnLock = enabled) }
    fun setCustomLockSoundUri(uri: String?) = update { it.copy(customLockSoundUri = uri) }
    fun setLowVoltageAlert(enabled: Boolean) = update { it.copy(lowVoltageAlert = enabled) }
    fun setLowVoltageThreshold(percent: Int) = update { it.copy(lowVoltageThreshold = percent.coerceIn(5, 100)) }
    fun setMinRefreshSeconds(seconds: Int) = update { it.copy(minRefreshSeconds = seconds.coerceIn(15, 600)) }
    fun setShowAppBadge(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.update { it.copy(showAppBadge = enabled) }
        // Badge visibility is a channel property — recreate it, then re-post the watching
        // notification so the change takes effect right away.
        com.i5autolock.service.NotificationChannels.ensure(appContext, enabled)
        if (settingsRepo.settings.first().enabled) AutoLockService.startWatching(appContext)
    }
    fun setShowNotificationIcon(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.update { it.copy(showNotificationIcon = enabled) }
        // The icon lives on the channel importance; re-post the watching notification so the
        // ongoing notification switches to the visible/minimal channel immediately.
        if (settingsRepo.settings.first().enabled) AutoLockService.startWatching(appContext)
    }
    /** Play the currently-configured lock sound (custom if set, else the default chime) to test it. */
    fun testLockSound() = viewModelScope.launch {
        val s = settingsRepo.settings.first()
        com.i5autolock.data.sound.LockSound.play(appContext, s.customLockSoundUri)
    }
    /** Play the built-in default chime regardless of the custom setting. */
    fun playDefaultSound() = com.i5autolock.data.sound.LockSound.playDefault()
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
        val result = withContext(Dispatchers.IO) { runCatching { provider.client(s).vehicles() } }
        val loaded = result.getOrElse {
            log.add(LogLevel.ERROR, "Couldn't load vehicles: ${it.message}")
            // Keep whatever we already have (cache) rather than blanking the picker.
            emptyList()
        }
        if (loaded.isNotEmpty()) {
            // Cache the list so it persists across restarts and offline sessions.
            settingsRepo.update { st ->
                st.copy(knownVehicles = loaded.map { KnownVehicle(it.id, it.nickname, it.model, ccs2 = it.ccs2) })
            }
            _extras.value = _extras.value.copy(loadingVehicles = false, vehicles = loaded)
        } else {
            _extras.value = _extras.value.copy(loadingVehicles = false)
        }
    }

    fun signOut() = viewModelScope.launch {
        val s = settingsRepo.settings.first()
        withContext(Dispatchers.IO) { provider.client(s).clearSession() }
        settingsRepo.update { it.copy(accountEmail = null, vehicleId = null, vehicleNickname = null, knownVehicles = emptyList()) }
        _extras.value = _extras.value.copy(signedIn = false, vehicles = emptyList())
        log.add(LogLevel.INFO, "Signed out.")
    }

    /** Verifies the current EU session by hitting the vehicles endpoint. Reports via _notice + log. */
    fun checkCredentials() = viewModelScope.launch {
        val s = settingsRepo.settings.first()
        // Always test the REAL client, even if Demo mode is on.
        val real = provider.client(s.copy(demoMode = false))
        if (!withContext(Dispatchers.IO) { real.isAuthenticated() }) {
            val msg = appContext.getString(com.i5autolock.R.string.check_creds_no_session)
            _notice.value = msg
            log.add(LogLevel.WARN, msg)
            return@launch
        }
        val fresh = withContext(Dispatchers.IO) { runCatching { real.ensureFreshSession() }.getOrDefault(false) }
        if (!fresh) {
            val msg = appContext.getString(com.i5autolock.R.string.check_creds_refresh_failed)
            _notice.value = msg
            log.add(LogLevel.ERROR, msg)
            return@launch
        }
        val result = withContext(Dispatchers.IO) { runCatching { real.vehicles() } }
        result.onSuccess { list ->
            val protocols = list.joinToString(", ") { v ->
                val proto = if (v.ccs2) "CCS2" else "legacy"
                "${v.nickname} ($proto)"
            }
            val msg = if (list.isEmpty()) appContext.getString(com.i5autolock.R.string.check_creds_ok, list.size)
                      else appContext.getString(com.i5autolock.R.string.check_creds_ok_protocols, list.size, protocols)
            _notice.value = msg
            log.add(LogLevel.SUCCESS, msg)
        }.onFailure { t ->
            val detail = t.message ?: t::class.simpleName ?: "unknown error"
            val msg = appContext.getString(com.i5autolock.R.string.check_creds_failed, detail)
            _notice.value = msg
            log.add(LogLevel.ERROR, msg)
        }
    }

    /** Probes 3 EU endpoints in sequence and shows a report — proves whether a 503 is our code or Hyundai. */
    fun diagnoseApi() = viewModelScope.launch {
        val s = settingsRepo.settings.first()
        val vehicleId = s.vehicleId
        if (vehicleId.isNullOrBlank()) {
            _notice.value = appContext.getString(com.i5autolock.R.string.diagnose_no_vehicle)
            return@launch
        }
        val real = provider.client(s.copy(demoMode = false))
        val report = withContext(Dispatchers.IO) {
            runCatching { real.diagnose(vehicleId) }
                .getOrElse { "✗ Diagnosis failed: ${it.message ?: it::class.simpleName}" }
        }
        _diagnosticReport.value = report
        report.split("\n").forEach { line ->
            if (line.isBlank()) return@forEach
            val level = when {
                line.startsWith("✓") -> LogLevel.SUCCESS
                line.startsWith("✗") -> LogLevel.ERROR
                else -> LogLevel.INFO
            }
            log.add(level, line)
        }
    }

    // ── Backup & restore ────────────────────────────────────────────────
    fun suggestedBackupFileName(): String = backupManager.suggestedFileName()

    fun exportToAppFolder() = viewModelScope.launch {
        runCatching { backupManager.exportToAppFolder() }
            .onSuccess { _notice.value = appContext.getString(com.i5autolock.R.string.backup_exported, it) }
            .onFailure { _notice.value = appContext.getString(com.i5autolock.R.string.backup_export_failed) }
    }

    fun exportToUri(uri: android.net.Uri) = viewModelScope.launch {
        runCatching { backupManager.exportToUri(uri) }
            .onSuccess { _notice.value = appContext.getString(com.i5autolock.R.string.backup_export_ok) }
            .onFailure { _notice.value = appContext.getString(com.i5autolock.R.string.backup_export_failed) }
    }

    fun restoreFromUri(uri: android.net.Uri) = viewModelScope.launch {
        runCatching { backupManager.restoreFromUri(uri) }
            .onSuccess {
                provider.invalidate()
                _notice.value = appContext.getString(com.i5autolock.R.string.backup_restored)
            }
            .onFailure { _notice.value = appContext.getString(com.i5autolock.R.string.backup_restore_failed) }
    }

    fun onSignedIn(email: String) = viewModelScope.launch {
        settingsRepo.update { it.copy(accountEmail = email) }
        _extras.value = _extras.value.copy(signedIn = true)
        loadVehicles()
    }
}

private fun KnownVehicle.toVehicle(): Vehicle =
    Vehicle(id = id, vin = "", nickname = nickname, model = model, ccs2 = ccs2)
