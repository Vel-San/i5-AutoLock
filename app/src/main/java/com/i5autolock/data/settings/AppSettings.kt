package com.i5autolock.data.settings

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

/** Pieces of vehicle info the user can show/hide in the ongoing notification. */
enum class NotificationField(val label: String) {
    LOCK_STATE("Lock state"),
    EV_BATTERY("Drive battery %"),
    RANGE("Range"),
    ENGINE("Engine"),
    TWELVE_VOLT("12V battery %"),
    CLIMATE("Climate"),
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
    val notificationFields: Set<NotificationField> = setOf(
        NotificationField.LOCK_STATE,
        NotificationField.EV_BATTERY,
        NotificationField.RANGE,
    ),

    // Behaviour toggles.
    val autoRefreshOnOpen: Boolean = true,
    val hapticOnLock: Boolean = true,
    val soundOnLock: Boolean = false,
    val rememberParkedLocation: Boolean = false,

    // Optional active-hours schedule (minutes from midnight). Supports overnight ranges.
    val scheduleEnabled: Boolean = false,
    val scheduleStartMinutes: Int = 7 * 60,
    val scheduleEndMinutes: Int = 22 * 60,

    // Last place the car was parked (label only), for the status card.
    val parkedLabel: String? = null,

    /** When true, uses the in-memory fake client so the app can be tested without a car/account. */
    val demoMode: Boolean = false,

    // Detection tuning.
    val graceSeconds: Int = 45,
    val useBluetoothTrigger: Boolean = true,
    val useActivityRecognition: Boolean = true,
    val useGeofence: Boolean = false,
    val geofenceRadiusMeters: Int = 25,

    // The paired car Bluetooth device that signals "in the car".
    val carBluetoothMac: String? = null,
    val carBluetoothName: String? = null,

    // Selected vehicle.
    val vehicleId: String? = null,
    val vehicleNickname: String? = null,

    // Account (non-secret parts only; tokens live in SecureStore).
    val accountEmail: String? = null,

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
