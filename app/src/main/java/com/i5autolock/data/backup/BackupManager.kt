package com.i5autolock.data.backup

import android.content.Context
import android.net.Uri
import com.i5autolock.data.bluelink.Region
import com.i5autolock.data.settings.AppSettings
import com.i5autolock.data.settings.KnownVehicle
import com.i5autolock.data.settings.NotificationField
import com.i5autolock.data.settings.RunMode
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.data.settings.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A restorable snapshot of user settings. Secrets (tokens, PIN, device id) are NEVER included. */
@Serializable
data class SettingsBackup(
    val backupVersion: Int = 1,
    val appVersion: String = "",
    val exportedAtEpochMs: Long = 0L,
    val runMode: String = RunMode.DRY_RUN.name,
    val region: String = Region.EU.name,
    val themeMode: String = ThemeMode.SYSTEM.name,
    val dynamicColor: Boolean = true,
    val showStatusInNotification: Boolean = true,
    val showLockNowAction: Boolean = true,
    val pinNotification: Boolean = true,
    val notificationFields: List<String> = emptyList(),
    val autoRefreshOnOpen: Boolean = true,
    val autoRefreshIntervalMinutes: Int = 0,
    val minRefreshSeconds: Int = 180,
    val hapticOnLock: Boolean = true,
    val soundOnLock: Boolean = false,
    val customLockSoundUri: String? = null,
    val rememberParkedLocation: Boolean = false,
    val lowVoltageAlert: Boolean = true,
    val lowVoltageThreshold: Int = 40,
    val showAppBadge: Boolean = false,
    val showNotificationIcon: Boolean = true,
    val scheduleEnabled: Boolean = false,
    val scheduleStartMinutes: Int = 7 * 60,
    val scheduleEndMinutes: Int = 22 * 60,
    val demoMode: Boolean = false,
    val graceSeconds: Int = 45,
    val useBluetoothTrigger: Boolean = true,
    val useActivityRecognition: Boolean = true,
    val useGeofence: Boolean = false,
    val geofenceRadiusMeters: Int = 25,
    val carBluetoothMac: String? = null,
    val carBluetoothName: String? = null,
    val vehicleId: String? = null,
    val vehicleNickname: String? = null,
    val knownVehicles: List<KnownVehicleBackup> = emptyList(),
    val accountEmail: String? = null,
    val requireConfirmationBeforeLock: Boolean = false,
) {
    /** Applies this backup onto [current], keeping `enabled` and onboarding as-is (no surprise activation). */
    fun applyTo(current: AppSettings): AppSettings = current.copy(
        runMode = runCatching { RunMode.valueOf(runMode) }.getOrDefault(current.runMode),
        region = Region.fromNameOrDefault(region),
        themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(current.themeMode),
        dynamicColor = dynamicColor,
        showStatusInNotification = showStatusInNotification,
        showLockNowAction = showLockNowAction,
        pinNotification = pinNotification,
        notificationFields = notificationFields
            .mapNotNull { runCatching { NotificationField.valueOf(it) }.getOrNull() }
            .toSet()
            .ifEmpty { current.notificationFields },
        autoRefreshOnOpen = autoRefreshOnOpen,
        autoRefreshIntervalMinutes = autoRefreshIntervalMinutes,
        minRefreshSeconds = minRefreshSeconds,
        hapticOnLock = hapticOnLock,
        soundOnLock = soundOnLock,
        customLockSoundUri = customLockSoundUri,
        rememberParkedLocation = rememberParkedLocation,
        lowVoltageAlert = lowVoltageAlert,
        lowVoltageThreshold = lowVoltageThreshold,
        showAppBadge = showAppBadge,
        showNotificationIcon = showNotificationIcon,
        scheduleEnabled = scheduleEnabled,
        scheduleStartMinutes = scheduleStartMinutes,
        scheduleEndMinutes = scheduleEndMinutes,
        demoMode = demoMode,
        graceSeconds = graceSeconds,
        useBluetoothTrigger = useBluetoothTrigger,
        useActivityRecognition = useActivityRecognition,
        useGeofence = useGeofence,
        geofenceRadiusMeters = geofenceRadiusMeters,
        carBluetoothMac = carBluetoothMac,
        carBluetoothName = carBluetoothName,
        vehicleId = vehicleId,
        vehicleNickname = vehicleNickname,
        knownVehicles = knownVehicles.map { KnownVehicle(it.id, it.nickname, it.model, ccs2 = it.ccs2) },
        accountEmail = accountEmail,
        requireConfirmationBeforeLock = requireConfirmationBeforeLock,
    )

    companion object {
        fun from(s: AppSettings, appVersion: String): SettingsBackup = SettingsBackup(
            appVersion = appVersion,
            exportedAtEpochMs = System.currentTimeMillis(),
            runMode = s.runMode.name,
            region = s.region.name,
            themeMode = s.themeMode.name,
            dynamicColor = s.dynamicColor,
            showStatusInNotification = s.showStatusInNotification,
            showLockNowAction = s.showLockNowAction,
            pinNotification = s.pinNotification,
            notificationFields = s.notificationFields.map { it.name },
            autoRefreshOnOpen = s.autoRefreshOnOpen,
            autoRefreshIntervalMinutes = s.autoRefreshIntervalMinutes,
            minRefreshSeconds = s.minRefreshSeconds,
            hapticOnLock = s.hapticOnLock,
            soundOnLock = s.soundOnLock,
            customLockSoundUri = s.customLockSoundUri,
            rememberParkedLocation = s.rememberParkedLocation,
            lowVoltageAlert = s.lowVoltageAlert,
            lowVoltageThreshold = s.lowVoltageThreshold,
            showAppBadge = s.showAppBadge,
            showNotificationIcon = s.showNotificationIcon,
            scheduleEnabled = s.scheduleEnabled,
            scheduleStartMinutes = s.scheduleStartMinutes,
            scheduleEndMinutes = s.scheduleEndMinutes,
            demoMode = s.demoMode,
            graceSeconds = s.graceSeconds,
            useBluetoothTrigger = s.useBluetoothTrigger,
            useActivityRecognition = s.useActivityRecognition,
            useGeofence = s.useGeofence,
            geofenceRadiusMeters = s.geofenceRadiusMeters,
            carBluetoothMac = s.carBluetoothMac,
            carBluetoothName = s.carBluetoothName,
            vehicleId = s.vehicleId,
            vehicleNickname = s.vehicleNickname,
            knownVehicles = s.knownVehicles.map { KnownVehicleBackup(it.id, it.nickname, it.model, it.ccs2) },
            accountEmail = s.accountEmail,
            requireConfirmationBeforeLock = s.requireConfirmationBeforeLock,
        )
    }
}

@Serializable
data class KnownVehicleBackup(
    val id: String,
    val nickname: String,
    val model: String,
    val ccs2: Boolean = true,
)

/** Exports/restores non-secret settings to a JSON file (app folder or a user-chosen location). */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun exportToAppFolder(): String = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
        val file = File(dir, suggestedFileName())
        file.writeText(buildJson())
        file.absolutePath
    }

    suspend fun exportToUri(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val text = buildJson()
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            ?: error("Could not open the selected destination.")
    }

    suspend fun restoreFromUri(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Could not read the selected file.")
        val backup = json.decodeFromString(SettingsBackup.serializer(), text)
        settingsRepo.update { backup.applyTo(it) }
    }

    fun suggestedFileName(): String = "autolock-backup-${timestamp()}.json"

    private suspend fun buildJson(): String {
        val settings = settingsRepo.settings.first()
        return json.encodeToString(SettingsBackup.serializer(), SettingsBackup.from(settings, appVersionName()))
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun appVersionName(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")
}
