package com.i5autolock.data.bluelink.fake

import com.i5autolock.data.bluelink.BlueLinkClient
import com.i5autolock.data.bluelink.Region
import com.i5autolock.data.bluelink.model.CommandResult
import com.i5autolock.data.bluelink.model.LockState
import com.i5autolock.data.bluelink.model.Vehicle
import com.i5autolock.data.bluelink.model.VehicleStatus
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory client used for the DRY_RUN mode, unit tests, and demoing without a car.
 * It simulates realistic latency and a vehicle that starts UNLOCKED.
 */
@Singleton
class FakeBlueLinkClient @Inject constructor() : BlueLinkClient {

    override val region: Region = Region.EU

    private val authed = AtomicReference(false)
    private val lockState = AtomicReference(LockState.UNLOCKED)

    override suspend fun isAuthenticated(): Boolean = authed.get()

    override suspend fun login(username: String, authCodeOrPassword: String): CommandResult {
        delay(400)
        authed.set(true)
        return CommandResult.Success("Signed in (simulated) as $username")
    }

    override suspend fun loginWithPassword(username: String, password: String): CommandResult {
        delay(600)
        authed.set(true)
        return CommandResult.Success("Signed in (simulated) as $username")
    }

    override suspend fun ensureFreshSession(): Boolean = authed.get()

    override suspend fun vehicles(): List<Vehicle> = listOf(
        Vehicle(
            id = "demo-ioniq5",
            vin = "KMHXXXXXXXXXXXXXX",
            nickname = "My Ioniq 5",
            model = "IONIQ 5",
        ),
    )

    override suspend fun status(vehicleId: String, forceRefresh: Boolean): VehicleStatus {
        delay(if (forceRefresh) 900 else 150)
        return VehicleStatus(
            lockState = lockState.get(),
            engineRunning = false,
            batteryCharging = false,
            timestamp = System.currentTimeMillis(),
            evBatteryPercent = 72,
            rangeKm = 318,
            climateOn = false,
            twelveVoltPercent = 88,
            anyDoorOpen = lockState.get() == LockState.UNLOCKED,
        )
    }

    override suspend fun lock(vehicleId: String): CommandResult {
        delay(1200)
        lockState.set(LockState.LOCKED)
        return CommandResult.Success("Vehicle locked (simulated)")
    }

    override suspend fun unlock(vehicleId: String): CommandResult {
        delay(1200)
        lockState.set(LockState.UNLOCKED)
        return CommandResult.Success("Vehicle unlocked (simulated)")
    }

    override suspend fun clearSession() {
        authed.set(false)
    }

    /** Test helper to force a starting state. */
    fun setLockStateForTest(state: LockState) = lockState.set(state)
}
