package com.i5autolock.data.settings

import androidx.annotation.StringRes
import com.i5autolock.R
import com.i5autolock.data.bluelink.Region

/** Whether AutoLock is allowed to actually send lock commands, or just simulate. */
enum class RunMode {
    /** Full flow but NEVER sends a real lock command. Safe for testing. */
    DRY_RUN,
    /** Real lock commands are sent. */
    ARMED,
}

/** Preferred app theme. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** A vehicle previously loaded from the account, cached so the picker survives app restarts. */
data class KnownVehicle(
    val id: String,
    val nickname: String,
    val model: String,
    /** True for CCS2 vehicles (Ioniq 5, EV6, IONIQ 6, newer Kona). Default true — our primary target. */
    val ccs2: Boolean = true,
    /** True when this car needs the legacy v1 control protocol (learned when CCS2 control is rejected). */
    val legacyControl: Boolean = false,
)

/** Pieces of vehicle info the user can show/hide in the ongoing notification. */
enum class NotificationField(@StringRes val labelRes: Int) {
    LOCK_STATE(R.string.notif_field_lock_state),
    EV_BATTERY(R.string.notif_field_ev_battery),
    RANGE(R.string.notif_field_range),
    ENGINE(R.string.notif_field_engine),
    TWELVE_VOLT(R.string.notif_field_twelve_volt),
    CLIMATE(R.string.notif_field_climate),
}

/** User-tunable configuration. Persisted via DataStore. */
data class AppSettings(
    val enabled: Boolean = false,
    val runMode: RunMode = RunMode.DRY_RUN,
    val region: Region = Region.EU,

    // Appearance.
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,

    // Notification content the user wants to see.
    val showStatusInNotification: Boolean = true,
    val showLockNowAction: Boolean = true,
    // Pin the ongoing "watching" notification so it re-appears if swiped away.
    val pinNotification: Boolean = true,
    val notificationFields: Set<NotificationField> = setOf(
        NotificationField.LOCK_STATE,
        NotificationField.EV_BATTERY,
        NotificationField.RANGE,
    ),

    // Behaviour toggles.
    val autoRefreshOnOpen: Boolean = true,
    // Periodic background status refresh in minutes (0 = off; min effective 15 via WorkManager).
    val autoRefreshIntervalMinutes: Int = 0,
    // Minimum seconds between LIVE vehicle polls (forced refresh wakes the car; too often = 503).
    val minRefreshSeconds: Int = 180,
    // Wake the car on a manual refresh (live poll). Default off: refreshes read the reliable cached
    // snapshot instead, avoiding Hyundai's transient 503 "5031 Unavailable remote control".
    val liveWakeRefresh: Boolean = false,
    val hapticOnLock: Boolean = true,
    val soundOnLock: Boolean = false,
    // Optional custom lock sound (content URI). Null = the built-in EV chime.
    val customLockSoundUri: String? = null,
    val rememberParkedLocation: Boolean = false,

    // Low 12V battery warning.
    val lowVoltageAlert: Boolean = true,
    val lowVoltageThreshold: Int = 40,

    // Show a launcher badge for AutoLock notifications (off by default; the notification suffices).
    val showAppBadge: Boolean = false,

    // Show the AutoLock icon in the status bar. When off, the ongoing notification is minimised
    // (still visible in the shade) so no icon sits in the status bar.
    val showNotificationIcon: Boolean = true,

    // Optional active-hours schedule (minutes from midnight). Supports overnight ranges.
    val scheduleEnabled: Boolean = false,
    val scheduleStartMinutes: Int = 7 * 60,
    val scheduleEndMinutes: Int = 22 * 60,

    // Last place the car was parked (label only), for the status card.
    val parkedLabel: String? = null,
    val parkedLat: Double? = null,
    val parkedLng: Double? = null,

    /** When true, uses the in-memory fake client so the app can be tested without a car/account. */
    val demoMode: Boolean = false,

    // Detection tuning.
    val graceSeconds: Int = 45,
    val useBluetoothTrigger: Boolean = true,
    val useActivityRecognition: Boolean = true,
    val useGeofence: Boolean = false,
    val geofenceRadiusMeters: Int = 25,
    // Only lock if a walk-away signal (Activity Recognition or geofence) confirms you left;
    // otherwise abort instead of proceeding on the Bluetooth disconnect alone.
    val requireWalkAwayConfirmation: Boolean = false,

    // Lock reliability.
    // After locking, re-read the status and re-send once if the car still reports unlocked.
    val verifyLock: Boolean = true,
    // Don't send a lock while a door or window is open (the car can't lock anyway) — warn instead.
    val dontLockIfOpen: Boolean = true,
    // Keep retrying a failed lock (car asleep / temporary 503) for this many minutes (0 = off).
    val retryWindowMinutes: Int = 0,
    // Post a one-shot "departure summary" notification after a lock attempt completes.
    val departureSummary: Boolean = true,

    // The paired car Bluetooth device that signals "in the car".
    val carBluetoothMac: String? = null,
    val carBluetoothName: String? = null,

    // Selected vehicle.
    val vehicleId: String? = null,
    val vehicleNickname: String? = null,

    // Vehicles loaded from the account (cached so the Settings picker persists offline).
    val knownVehicles: List<KnownVehicle> = emptyList(),

    // Account (non-secret parts only; tokens live in SecureStore).
    val accountEmail: String? = null,

    // First-run onboarding wizard shown until completed.
    val onboardingComplete: Boolean = false,

    val requireConfirmationBeforeLock: Boolean = false,
) {
    val isConfigured: Boolean
        get() = accountEmail != null && vehicleId != null

    /** True if the current minute-of-day falls within the active-hours window. */
    fun isWithinSchedule(nowMinutes: Int): Boolean {
        if (!scheduleEnabled) return true
        return if (scheduleStartMinutes <= scheduleEndMinutes) {
            nowMinutes in scheduleStartMinutes until scheduleEndMinutes
        } else {
            // Overnight window, e.g. 22:00 → 06:00.
            nowMinutes >= scheduleStartMinutes || nowMinutes < scheduleEndMinutes
        }
    }
}
