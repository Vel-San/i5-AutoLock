package com.i5autolock.data.bluelink

import com.i5autolock.data.bluelink.model.CommandResult
import com.i5autolock.data.bluelink.model.Vehicle
import com.i5autolock.data.bluelink.model.VehicleStatus
import com.i5autolock.data.metrics.ApiMetrics
import com.i5autolock.data.metrics.ApiOutcome

/**
 * Decorator that times every [BlueLinkClient] call and records the outcome into [ApiMetrics].
 * It records only operation name / duration / outcome — never tokens, VINs, or account data.
 */
class MeteredBlueLinkClient(
    private val delegate: BlueLinkClient,
    private val metrics: ApiMetrics,
) : BlueLinkClient {

    override val region: Region get() = delegate.region

    override suspend fun isAuthenticated(): Boolean = delegate.isAuthenticated()

    override suspend fun ensureFreshSession(): Boolean =
        measured("ensureFreshSession") {
            val ok = delegate.ensureFreshSession()
            (if (ok) ApiOutcome.SUCCESS else ApiOutcome.UNAUTHENTICATED) to ok
        }

    override suspend fun login(username: String, authCodeOrPassword: String): CommandResult =
        measuredCommand("login") { delegate.login(username, authCodeOrPassword) }

    override suspend fun loginWithPassword(username: String, password: String): CommandResult =
        measuredCommand("loginWithPassword") { delegate.loginWithPassword(username, password) }

    override suspend fun vehicles(): List<Vehicle> =
        measuredValue("vehicles") { delegate.vehicles() }

    override suspend fun status(vehicleId: String, forceRefresh: Boolean): VehicleStatus =
        measuredValue(if (forceRefresh) "status(refresh)" else "status") {
            delegate.status(vehicleId, forceRefresh)
        }

    override suspend fun lock(vehicleId: String): CommandResult =
        measuredCommand("lock") { delegate.lock(vehicleId) }

    override suspend fun unlock(vehicleId: String): CommandResult =
        measuredCommand("unlock") { delegate.unlock(vehicleId) }

    override suspend fun clearSession() = delegate.clearSession()

    override suspend fun resetDeviceRegistration() = delegate.resetDeviceRegistration()

    override suspend fun diagnose(vehicleId: String): String = delegate.diagnose(vehicleId)

    private suspend fun <T> measured(op: String, block: suspend () -> Pair<ApiOutcome, T>): T {
        val start = System.nanoTime()
        try {
            val (outcome, value) = block()
            metrics.record(op, (System.nanoTime() - start) / 1_000_000, outcome)
            return value
        } catch (t: Throwable) {
            metrics.record(op, (System.nanoTime() - start) / 1_000_000, ApiOutcome.FAILURE, t.message)
            throw t
        }
    }

    private suspend fun <T> measuredValue(op: String, block: suspend () -> T): T =
        measured(op) { ApiOutcome.SUCCESS to block() }

    private suspend fun measuredCommand(op: String, block: suspend () -> CommandResult): CommandResult {
        val start = System.nanoTime()
        val result = try {
            block()
        } catch (t: Throwable) {
            metrics.record(op, (System.nanoTime() - start) / 1_000_000, ApiOutcome.FAILURE, t.message)
            throw t
        }
        val outcome = when (result) {
            is CommandResult.Success -> ApiOutcome.SUCCESS
            is CommandResult.Failure -> ApiOutcome.FAILURE
            CommandResult.RateLimited -> ApiOutcome.RATE_LIMITED
            CommandResult.NotAuthenticated -> ApiOutcome.UNAUTHENTICATED
        }
        val detail = (result as? CommandResult.Failure)?.reason
        metrics.record(op, (System.nanoTime() - start) / 1_000_000, outcome, detail)
        return result
    }
}
