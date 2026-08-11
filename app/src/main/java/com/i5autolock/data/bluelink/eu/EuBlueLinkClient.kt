package com.i5autolock.data.bluelink.eu

import android.util.Log
import com.i5autolock.data.bluelink.BlueLinkClient
import com.i5autolock.data.bluelink.Region
import com.i5autolock.data.bluelink.RegionConfig
import com.i5autolock.data.bluelink.model.CommandResult
import com.i5autolock.data.bluelink.model.LockState
import com.i5autolock.data.bluelink.model.Vehicle
import com.i5autolock.data.bluelink.model.VehicleStatus
import com.i5autolock.data.secure.SecureStore
import com.i5autolock.data.secure.SessionTokens
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.client.request.forms.FormDataContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Real EU BlueLink (Hyundai) client.
 *
 * This implements the shape of the CCS OAuth + control flow ported from
 * `hyundai_kia_connect_api` and `bluelinky`. Exact endpoints, headers and the
 * control-token dance evolve over time — keep all EU specifics in this package.
 *
 * The login() code path expects the OAuth authorization `code` captured by the UI
 * (Custom Tab / browser redirect), NOT a raw password.
 */
class EuBlueLinkClient(
    private val http: HttpClient,
    private val config: RegionConfig,
    private val secureStore: SecureStore,
    // App context for the Chromium WebView login (null → raw OkHttp path only).
    private val appContext: android.content.Context? = null,
    // Optional step-by-step diagnostics surfaced in the app's activity log.
    private val diag: (String) -> Unit = {},
) : BlueLinkClient {

    override val region: Region = Region.EU

    private val refreshMutex = Mutex()

    /** Registered CCSP device id (empty until [ensureDeviceRegistered] runs). */
    private fun currentDeviceId(): String = secureStore.loadDeviceId() ?: ""

    private fun HttpResponse.ok() = status.value in 200..299

    /** CCSP headers required on EVERY EU request (matches the official app / BlueDeck). */
    private fun baseHeaders(builder: io.ktor.client.request.HttpRequestBuilder) {
        builder.header("ccsp-service-id", config.clientId)
        config.appId?.let { builder.header("ccsp-application-id", it) }
        builder.header("ccsp-device-id", currentDeviceId())
        builder.header("Stamp", EuAuth.generateStamp(config))
        builder.header("Accept-Encoding", "gzip")
        builder.header("Connection", "Keep-Alive")
        builder.header("User-Agent", "okhttp/3.12.0")
    }

    /** Plain headers for the IDP (idpconnect) host — no CCSP stamp, mobile UA. */
    private fun idpHeaders(builder: io.ktor.client.request.HttpRequestBuilder) {
        builder.header("User-Agent", config.mobileUserAgent)
    }

    override suspend fun isAuthenticated(): Boolean = secureStore.loadTokens() != null

    /**
     * Fully headless EU sign-in with email + password (no reCAPTCHA), ported from
     * `bluelink-refresh-token`: load the authorize page for cookies, fetch the IDP RSA key,
     * RSA-encrypt the password, POST /auth/account/signin (302 → code), exchange the code for
     * tokens. This generates the refresh token automatically so users never handle it.
     */
    override suspend fun loginWithPassword(username: String, password: String): CommandResult {
        if (config.idpBaseUrl == null) return CommandResult.Failure("Password login isn't supported here.")
        return try {
            diag("Login: registering device…")
            ensureDeviceRegistered()
            // EU IDP sits behind Akamai bot protection that blocks OkHttp's TLS fingerprint,
            // so drive the login through a real Chromium WebView. Fall back to the raw OkHttp
            // flow only if the WebView itself is unavailable.
            val tokens = if (appContext != null) {
                try {
                    EuWebLogin(appContext).login(config, username.trim(), password, diag)
                } catch (e: EuWebLogin.WebUnavailable) {
                    diag("Web login unavailable (${e.message}); trying direct…")
                    EuIdpAuth().login(config, username.trim(), password, diag)
                }
            } else {
                EuIdpAuth().login(config, username.trim(), password, diag)
            }
            secureStore.saveTokens(
                SessionTokens(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    tokenType = "Bearer",
                    expiresAtEpochMs = System.currentTimeMillis() + tokens.expiresIn * 1000,
                    deviceId = currentDeviceId(),
                ),
            )
            diag("Login complete ✓")
            CommandResult.Success("Signed in")
        } catch (t: EuIdpAuth.LoginException) {
            fail(t.message ?: "Sign-in failed.")
        } catch (t: Throwable) {
            Log.w(TAG, "password login failed: ${t.message}")
            fail("Login error: ${t.message}")
        }
    }

    private fun fail(message: String): CommandResult.Failure {
        diag("✗ $message")
        return CommandResult.Failure(message)
    }

    override suspend fun login(username: String, authCodeOrPassword: String): CommandResult {
        return try {
            diag("Login: exchanging code for tokens…")
            ensureDeviceRegistered()
            // CCSP browser-flow token exchange (matches bluelinky): basic auth + Stamp, code +
            // redirect_uri only. Body must NOT include client_id or the server returns 400.
            val response: HttpResponse = http.post("${config.apiBaseUrl}/api/v1/user/oauth2/token") {
                config.basicAuth?.let { header("Authorization", it) }
                header("Stamp", EuAuth.generateStamp(config))
                header("User-Agent", "okhttp/3.12.0")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    FormDataContent(
                        parameters {
                            append("grant_type", "authorization_code")
                            append("redirect_uri", config.redirectUri)
                            append("code", authCodeOrPassword)
                        },
                    ),
                )
            }
            if (!response.ok()) {
                val body = runCatching { response.body<String>() }.getOrDefault("").take(200)
                return fail("Token exchange failed (HTTP ${response.status.value}). $body")
            }
            val token: TokenResponse = response.body()
            persist(token, username)
            diag("Login complete ✓")
            CommandResult.Success("Signed in")
        } catch (t: Throwable) {
            Log.w(TAG, "login failed: ${t.message}")
            fail("Login error: ${t.message}")
        }
    }

    /**
     * Reliable EU sign-in: exchange a pre-obtained 48-char refresh token for an access token
     * using HTTP Basic auth + the CCSP stamp headers. This mirrors BlueDeck / bluelinky, since
     * the EU login page itself uses reCAPTCHA and can't be automated on-device.
     */
    override suspend fun loginWithRefreshToken(refreshToken: String): CommandResult {
        val cleaned = refreshToken.trim()
        if (cleaned.isEmpty()) return CommandResult.Failure("Enter your EU refresh token.")
        return try {
            // The device must be registered before the token exchange (matches BlueDeck).
            ensureDeviceRegistered()
            val response = idpTokenRefresh(cleaned)
            if (!response.ok()) {
                return CommandResult.Failure("Token refresh failed (${response.status.value}). Check the token.")
            }
            val token: TokenResponse = response.body()
            // Keep the user-provided refresh token if the server didn't return a new one.
            persist(token.copy(refreshToken = token.refreshToken ?: cleaned), null)
            CommandResult.Success("Signed in with refresh token")
        } catch (t: Throwable) {
            Log.w(TAG, "refresh-token login failed: ${t.message}")
            CommandResult.Failure("Login error", t)
        }
    }

    /** Refresh an EU access token at the IDP token endpoint (client_id + client_secret in body). */
    private suspend fun idpTokenRefresh(refreshToken: String): HttpResponse {
        val idp = config.idpBaseUrl ?: config.apiBaseUrl
        return http.post("$idp/auth/api/v2/user/oauth2/token") {
            idpHeaders(this)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                FormDataContent(
                    parameters {
                        append("grant_type", "refresh_token")
                        append("refresh_token", refreshToken)
                        append("client_id", config.clientId)
                        config.clientSecret?.let { append("client_secret", it) }
                    },
                ),
            )
        }
    }

    /**
     * Registers a CCSP device id via notifications/register (unauthenticated, empty device id)
     * and stores it. Required before status/control calls will work in the EU.
     */
    private suspend fun ensureDeviceRegistered(): String {
        secureStore.loadDeviceId()?.takeIf { it.isNotBlank() }?.let { return it }
        val response: HttpResponse = http.post("${config.apiBaseUrl}/api/v1/spa/notifications/register") {
            baseHeaders(this) // ccsp-device-id is empty at this point, as the backend expects
            contentType(ContentType.Application.Json)
            setBody(
                """{"pushRegId":"${randomHex(64)}","pushType":"GCM","uuid":"${UUID.randomUUID()}"}""",
            )
        }
        diag("device register → HTTP ${response.status.value}")
        if (!response.ok()) error("Device registration failed: ${response.status}")
        val env: RegisterEnvelope = response.body()
        val id = env.resMsg?.deviceId ?: env.retValue?.deviceId ?: env.deviceId
            ?: error("Device registration returned no deviceId")
        secureStore.saveDeviceId(id)
        return id
    }

    private fun randomHex(length: Int): String {
        val alphabet = "0123456789abcdef"
        return buildString(length) { repeat(length) { append(alphabet.random()) } }
    }

    private fun persist(token: TokenResponse, email: String?) {
        val prev = secureStore.loadTokens()
        secureStore.saveTokens(
            SessionTokens(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken ?: prev?.refreshToken.orEmpty(),
                tokenType = token.tokenType,
                expiresAtEpochMs = System.currentTimeMillis() + token.expiresIn * 1000,
                deviceId = currentDeviceId(),
            ),
        )
    }

    override suspend fun ensureFreshSession(): Boolean = refreshMutex.withLock {
        val tokens = secureStore.loadTokens() ?: return false
        if (!tokens.isAccessExpired) return true
        if (tokens.refreshToken.isBlank()) return false
        return try {
            val response = idpTokenRefresh(tokens.refreshToken)
            if (!response.ok()) return false
            val token: TokenResponse = response.body()
            persist(token.copy(refreshToken = token.refreshToken ?: tokens.refreshToken), null)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "refresh failed: ${t.message}")
            false
        }
    }

    private suspend fun authHeader(): String {
        val tokens = secureStore.loadTokens() ?: error("Not authenticated")
        return "${tokens.tokenType} ${tokens.accessToken}"
    }

    override suspend fun vehicles(): List<Vehicle> {
        ensureFreshSession()
        val response = http.get("${config.apiBaseUrl}/api/v1/spa/vehicles") {
            baseHeaders(this)
            header("Authorization", authHeader())
        }
        val envelope: VehiclesEnvelope = response.body()
        return envelope.resMsg?.vehicles.orEmpty().map {
            Vehicle(
                id = it.vehicleId,
                vin = it.vin,
                nickname = it.nickname.ifBlank { it.vehicleName }.ifBlank { "Vehicle" },
                model = it.vehicleName,
                regDate = it.regDate,
            )
        }
    }

    override suspend fun status(vehicleId: String, forceRefresh: Boolean): VehicleStatus {
        ensureFreshSession()
        val path = if (forceRefresh) "status" else "status/latest"
        val response = http.get("${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/$path") {
            baseHeaders(this)
            header("Authorization", authHeader())
        }
        val envelope: StatusEnvelope = response.body()
        val vs = envelope.resMsg?.vehicleStatusInfo?.vehicleStatus
        val locked = vs?.doorLock ?: envelope.resMsg?.doorLock
        val doorOpen = vs?.doorOpen?.let { d ->
            listOfNotNull(d.frontLeft, d.frontRight, d.backLeft, d.backRight).any { it != 0 }
        }
        return VehicleStatus(
            lockState = when (locked) {
                true -> LockState.LOCKED
                false -> LockState.UNLOCKED
                null -> LockState.UNKNOWN
            },
            engineRunning = vs?.engine ?: envelope.resMsg?.engine ?: false,
            batteryCharging = null,
            timestamp = System.currentTimeMillis(),
            evBatteryPercent = vs?.evStatus?.batteryStatus,
            rangeKm = vs?.evStatus?.drvDistance?.firstOrNull()
                ?.rangeByFuel?.totalAvailableRange?.value,
            climateOn = vs?.airCtrlOn,
            twelveVoltPercent = vs?.battery?.batterySoc,
            anyDoorOpen = doorOpen,
        )
    }

    override suspend fun lock(vehicleId: String): CommandResult = sendDoorCommand(vehicleId, close = true)

    override suspend fun unlock(vehicleId: String): CommandResult = sendDoorCommand(vehicleId, close = false)

    private suspend fun sendDoorCommand(vehicleId: String, close: Boolean): CommandResult {
        if (!ensureFreshSession()) return CommandResult.NotAuthenticated
        return try {
            val deviceId = ensureDeviceRegistered()
            // Lock/unlock needs a control token derived from the BlueLink PIN.
            val controlAuth = controlAuthorization(vehicleId, deviceId)
                ?: return CommandResult.Failure("Add your 4-digit BlueLink PIN to lock the car.")
            val action = if (close) "close" else "open"
            val response: HttpResponse = http.post(
                "${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/control/door",
            ) {
                baseHeaders(this)
                header("Authorization", controlAuth)
                contentType(ContentType.Application.Json)
                setBody("""{"action":"$action","deviceId":"$deviceId"}""")
            }
            when {
                response.ok() -> CommandResult.Success(if (close) "Locked" else "Unlocked")
                response.status == HttpStatusCode.TooManyRequests -> CommandResult.RateLimited
                response.status == HttpStatusCode.Unauthorized -> CommandResult.NotAuthenticated
                else -> CommandResult.Failure("Command failed: ${response.status}")
            }
        } catch (t: Throwable) {
            CommandResult.Failure("Command error", t)
        }
    }

    /**
     * Verifies the BlueLink PIN to obtain a short-lived control token (fetched fresh per command).
     * Returns null if no PIN is stored.
     */
    private suspend fun controlAuthorization(vehicleId: String, deviceId: String): String? {
        val pin = secureStore.loadPin()?.takeIf { it.isNotBlank() } ?: return null
        val response: HttpResponse = http.put("${config.apiBaseUrl}/api/v1/user/pin") {
            baseHeaders(this)
            header("Authorization", authHeader())
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"$deviceId","pin":"$pin","vehicleId":"$vehicleId"}""")
        }
        if (!response.ok()) error("PIN verification failed: ${response.status}")
        val env: PinEnvelope = response.body()
        val controlToken = env.controlToken ?: env.resMsg?.controlToken ?: env.retValue?.controlToken
            ?: return null
        return "Bearer $controlToken"
    }

    override suspend fun clearSession() = secureStore.clear()

    private companion object {
        const val TAG = "EuBlueLinkClient"
    }
}
