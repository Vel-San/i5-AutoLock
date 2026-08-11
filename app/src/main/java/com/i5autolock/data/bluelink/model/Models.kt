package com.i5autolock.data.bluelink.model

/** A vehicle registered to the BlueLink account. */
data class Vehicle(
    val id: String,
    val vin: String,
    val nickname: String,
    val model: String,
    val regDate: String? = null,
)

/** Door lock state as reported by the vehicle. */
enum class LockState { LOCKED, UNLOCKED, UNKNOWN }

/** Snapshot of the information we need to decide whether to lock, plus display extras. */
data class VehicleStatus(
    val lockState: LockState,
    val engineRunning: Boolean,
    val batteryCharging: Boolean?,
    val timestamp: Long,
    // Optional richer info for the status card; null when the backend doesn't provide it.
    val evBatteryPercent: Int? = null,
    val rangeKm: Int? = null,
    val climateOn: Boolean? = null,
    val twelveVoltPercent: Int? = null,
    val anyDoorOpen: Boolean? = null,
) {
    val isUnlocked: Boolean get() = lockState == LockState.UNLOCKED
}

/** Result of a remote command (lock/unlock). */
sealed interface CommandResult {
    data class Success(val message: String) : CommandResult
    data class Failure(val reason: String, val cause: Throwable? = null) : CommandResult
    data object RateLimited : CommandResult
    data object NotAuthenticated : CommandResult
}
