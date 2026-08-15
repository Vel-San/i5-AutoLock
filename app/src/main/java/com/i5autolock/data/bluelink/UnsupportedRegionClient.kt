package com.i5autolock.data.bluelink

import com.i5autolock.data.bluelink.model.CommandResult
import com.i5autolock.data.bluelink.model.Vehicle
import com.i5autolock.data.bluelink.model.VehicleStatus

/**
 * Placeholder client for regions whose real BlueLink/UVO API isn't implemented yet (US/CA/AU).
 * It fails clearly instead of silently hitting the wrong (EU) endpoints. Swap in a real
 * region-specific client here as each is implemented — the architecture already routes by region.
 */
class UnsupportedRegionClient(override val region: Region) : BlueLinkClient {

    private val message = "${region.displayName} isn't supported yet — use the EU region for now."

    override suspend fun isAuthenticated(): Boolean = false
    override suspend fun ensureFreshSession(): Boolean = false
    override suspend fun login(username: String, authCodeOrPassword: String) = CommandResult.Failure(message)
    override suspend fun loginWithPassword(username: String, password: String) = CommandResult.Failure(message)
    override suspend fun vehicles(): List<Vehicle> = emptyList()
    override suspend fun status(vehicleId: String, forceRefresh: Boolean): VehicleStatus =
        throw UnsupportedOperationException(message)
    override suspend fun lock(vehicleId: String) = CommandResult.Failure(message)
    override suspend fun unlock(vehicleId: String) = CommandResult.Failure(message)
    override suspend fun clearSession() {}
}
