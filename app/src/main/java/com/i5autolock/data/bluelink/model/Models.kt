package com.i5autolock.data.bluelink.model

/** A vehicle registered to the BlueLink account. */
data class Vehicle(
    val id: String,
    val vin: String,
    val nickname: String,
    val model: String,
    val regDate: String? = null,
    /** True for CCS2 vehicles (Ioniq 5, EV6, IONIQ 6, newer Kona) — primary protocol for this app. */
    val ccs2: Boolean = true,
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
    val anyWindowOpen: Boolean? = null,
) {
    val isUnlocked: Boolean get() = lockState == LockState.UNLOCKED

    /** True when a door or window is open (so the car can't actually lock). */
    val isOpenSomewhere: Boolean get() = anyDoorOpen == true || anyWindowOpen == true
}

/**
 * Prefer this (fresh) status but fall back to [old] for any optional field the backend omitted.
 * The EU "force refresh" endpoint often returns a minimal payload, so merging keeps battery/range
 * from the last-known reading instead of dropping them.
 */
fun VehicleStatus.mergedOnto(old: VehicleStatus?): VehicleStatus {
    if (old == null) return this
    return copy(
        evBatteryPercent = evBatteryPercent ?: old.evBatteryPercent,
        rangeKm = rangeKm ?: old.rangeKm,
        twelveVoltPercent = twelveVoltPercent ?: old.twelveVoltPercent,
        climateOn = climateOn ?: old.climateOn,
        anyDoorOpen = anyDoorOpen ?: old.anyDoorOpen,
        anyWindowOpen = anyWindowOpen ?: old.anyWindowOpen,
        batteryCharging = batteryCharging ?: old.batteryCharging,
    )
}

/** Result of a remote command (lock/unlock). */
sealed interface CommandResult {
    data class Success(val message: String) : CommandResult
    data class Failure(val reason: String, val cause: Throwable? = null) : CommandResult
    data object RateLimited : CommandResult
    data object NotAuthenticated : CommandResult
}
