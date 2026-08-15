package com.i5autolock.data.bluelink

import com.i5autolock.data.bluelink.model.CommandResult
import com.i5autolock.data.bluelink.model.Vehicle
import com.i5autolock.data.bluelink.model.VehicleStatus

/**
 * Region-agnostic contract for talking to a BlueLink/UVO backend.
 *
 * Implementations:
 *  - [com.i5autolock.data.bluelink.fake.FakeBlueLinkClient] for tests + dry-run/demo.
 *  - [com.i5autolock.data.bluelink.eu.EuBlueLinkClient] for the real EU flow.
 *
 * All calls are suspending and safe to run off the main thread.
 */
interface BlueLinkClient {

    val region: Region

    /** True once we hold a valid (or refreshable) session. */
    suspend fun isAuthenticated(): Boolean

    /**
     * Complete authentication.
     *
     * For OAuth regions (EU) [authCodeOrPassword] is the authorization code captured
     * from the redirect; for password regions it is the account password.
     */
    suspend fun login(username: String, authCodeOrPassword: String): CommandResult

    /**
     * Fully automatic EU sign-in with email + password: generates the session on-device via the
     * OneApp/CCI flow (no reCAPTCHA, no WAF block). Default: unsupported for non-EU regions.
     */
    suspend fun loginWithPassword(username: String, password: String): CommandResult =
        CommandResult.Failure("Email/password sign-in isn't supported for this region.")

    /** Ensure the access token is fresh, refreshing if needed. */
    suspend fun ensureFreshSession(): Boolean

    /** List vehicles on the account. */
    suspend fun vehicles(): List<Vehicle>

    /** Fetch a lock-relevant status snapshot. May be cached or force-refreshed. */
    suspend fun status(vehicleId: String, forceRefresh: Boolean): VehicleStatus

    /** Send the remote lock command. */
    suspend fun lock(vehicleId: String): CommandResult

    /** Send the remote unlock command (used only for manual testing from the UI). */
    suspend fun unlock(vehicleId: String): CommandResult

    /** Drop the local session (logout). */
    suspend fun clearSession()

    /**
     * Force the client to re-register its CCSP device id on the next call. Useful when Hyundai's
     * backend rejects commands with "Please check your vehicle status" — a stale/revoked device id
     * on the account can silently cause every remote command to fail. Default: no-op.
     */
    suspend fun resetDeviceRegistration() {}

    /**
     * Probes the three key EU endpoints in order and returns a multi-line human-readable report.
     * Used to isolate 503s: if vehicles/list + cached status work but the live poll fails, the
     * problem is server-side (Hyundai rate limit / degradation), not our code or credentials.
     * Non-EU regions return a "not supported" line.
     */
    suspend fun diagnose(vehicleId: String): String = "Diagnostics not supported for this region."
}
