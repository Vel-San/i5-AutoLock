package com.i5autolock.data.bluelink.eu

import android.util.Log
import com.i5autolock.data.bluelink.BlueLinkClient
import com.i5autolock.data.bluelink.Region
import com.i5autolock.data.bluelink.RegionConfig
import com.i5autolock.data.bluelink.model.CommandResult
import com.i5autolock.data.bluelink.model.LockState
import com.i5autolock.data.bluelink.model.Vehicle
import com.i5autolock.data.bluelink.model.VehicleStatus
import com.i5autolock.data.bluelink.model.mergedOnto
import com.i5autolock.data.metrics.ApiMetrics
import com.i5autolock.data.metrics.ApiOutcome
import com.i5autolock.data.secure.SecureStore
import com.i5autolock.data.secure.SessionTokens
import com.i5autolock.data.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.client.request.forms.FormDataContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    private val settingsRepo: SettingsRepository,
    private val metrics: ApiMetrics,
    // App context for the Chromium WebView login (null → raw OkHttp path only).
    private val appContext: android.content.Context? = null,
    // Optional step-by-step diagnostics surfaced in the app's activity log.
    private val diag: (String) -> Unit = {},
) : BlueLinkClient {

    override val region: Region = Region.EU

    private val refreshMutex = Mutex()

    // Timestamp of the last live vehicle poll (carstatus), to rate-limit forced refreshes.
    @Volatile private var lastLivePollAtMs = 0L

    /** Registered CCSP device id (empty until [ensureDeviceRegistered] runs). */
    private fun currentDeviceId(): String = secureStore.loadDeviceId() ?: ""

    private fun HttpResponse.ok() = status.value in 200..299

    /** Reads the response body as a short text snippet for diagnostics (never logs tokens/PIN). */
    private suspend fun HttpResponse.snippet(max: Int = 600): String = runCatching {
        bodyAsText().take(max).replace(Regex("\\s+"), " ").trim()
    }.getOrDefault("")

    /** Logs a failed request/response to the activity log and returns the formatted reason. */
    private suspend fun describeFailure(op: String, response: HttpResponse): String {
        val body = response.snippet()
        val line = if (body.isBlank()) "$op → HTTP ${response.status.value}"
                   else "$op → HTTP ${response.status.value}: $body"
        diag("✗ $line")
        return line
    }

    /** CCSP headers required on EVERY EU request (matches the official app / BlueDeck). */
    private fun baseHeaders(builder: io.ktor.client.request.HttpRequestBuilder) {
        builder.header("ccsp-service-id", config.clientId)
        config.appId?.let { builder.header("ccsp-application-id", it) }
        builder.header("ccsp-device-id", currentDeviceId())
        builder.header("Stamp", EuAuth.generateStamp(config))
        builder.header("Accept-Encoding", "gzip")
        builder.header("Connection", "Keep-Alive")
        builder.header("User-Agent", "okhttp/3.12.0")
        // Ioniq 5 / EV6 / IONIQ 6 use the newer CCS2 protocol; the backend rejects some legacy
        // endpoints for these cars unless this header is present.
        builder.header("ccuCCS2ProtocolSupport", "1")
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
        if (!response.ok()) error(describeFailure("device register", response))
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
        if (!response.ok()) error(describeFailure("vehicles", response))
        val envelope: VehiclesEnvelope = response.body()
        val vehicles = envelope.resMsg?.vehicles.orEmpty().map {
            // Prefer the explicit ccuCCS2ProtocolSupport flag; fall back to protocolType (2 = CCS2).
            // If the response omits both, assume CCS2 — this is an Ioniq-5-first app.
            val ccs2 = when {
                it.ccuCCS2ProtocolSupport != null -> it.ccuCCS2ProtocolSupport == 1
                it.protocolType != null -> it.protocolType == 2
                else -> true
            }
            Vehicle(
                id = it.vehicleId,
                vin = it.vin,
                nickname = it.nickname.ifBlank { it.vehicleName }.ifBlank { "Vehicle" },
                model = it.vehicleName,
                regDate = it.regDate,
                ccs2 = ccs2,
            )
        }
        vehicles.forEach { v ->
            diag("vehicle: ${v.nickname} (${v.model}) — ${if (v.ccs2) "CCS2" else "legacy"}")
        }
        return vehicles
    }

    /** Looks up the cached CCS2 flag for a vehicle; defaults to true (Ioniq 5-first). */
    private suspend fun isCcs2Vehicle(vehicleId: String): Boolean {
        val known = settingsRepo.settings.first().knownVehicles.firstOrNull { it.id == vehicleId }
        return known?.ccs2 ?: true
    }

    override suspend fun status(vehicleId: String, forceRefresh: Boolean): VehicleStatus {
        ensureFreshSession()
        val settings = settingsRepo.settings.first()
        val ccs2 = settings.knownVehicles.firstOrNull { it.id == vehicleId }?.ccs2 ?: true
        // Ioniq 5 / EV6 / IONIQ 6 (CCS2) status is under /api/v1/spa/vehicles/{id}/ccs2/carstatus[/latest]
        // (v1, not v2 — only control endpoints use v2). Cached read never wakes the car; forced read
        // uses the async wake flow (see below). Legacy CCSP cars fall back to /status[/latest].
        val cachedPath = "carstatus/latest"
        // A forced read runs a LIVE poll (wakes the car over cellular) which Hyundai heavily
        // rate-limits — hammering it returns 503 "temporarily unavailable". The user's "Minimum
        // refresh interval" setting is the min spacing between live polls; within it we serve the
        // cached report instead, like the official app. Protects every caller (verify, refresh).
        val cooldownMs = settings.minRefreshSeconds.coerceAtLeast(1) * 1000L
        val now = System.currentTimeMillis()
        val livePollAllowed = forceRefresh && (now - lastLivePollAtMs >= cooldownMs)
        if (!livePollAllowed) {
            if (forceRefresh) {
                val waitMs = cooldownMs - (now - lastLivePollAtMs)
                diag("status: live poll on cooldown (~${waitMs / 1000}s) — using cached report")
            }
            return fetchStatusPrimary(vehicleId, cachedPath, "status/latest", ccs2)
        }
        // Back off regardless of the outcome so a 503/error doesn't get retried immediately.
        lastLivePollAtMs = now
        if (ccs2) {
            // Canonical CCS2 async flow (per hyundai_kia_connect_api KiaUvoApiEU):
            //   1. GET /ccs2/carstatus  → returns {retCode, resCode, msgId} (NOT state) and wakes the car
            //   2. wait ~25s while the car reports back to Hyundai's server cache
            //   3. GET /ccs2/carstatus/latest → fresh state
            triggerCcs2Wake(vehicleId)
            diag("carstatus: waiting ${CCS2_WAKE_DELAY_MS / 1000}s for car to report…")
            delay(CCS2_WAKE_DELAY_MS)
            return fetchStatusPrimary(vehicleId, cachedPath, "status/latest", ccs2)
        }
        // Legacy non-CCS2: forced then cached, merged (forced payload is often minimal).
        val forced = fetchStatusPrimary(vehicleId, "carstatus", "status", ccs2)
        val latest = runCatching { fetchStatusPrimary(vehicleId, cachedPath, "status/latest", ccs2) }.getOrNull()
        return forced.mergedOnto(latest)
    }

    /**
     * Triggers a CCS2 live vehicle poll (wakes the car) and returns immediately. The response
     * is an async command envelope {retCode, resCode, msgId} — NOT vehicle state — so we discard
     * it. Caller must wait ~25s before reading /ccs2/carstatus/latest for the fresh snapshot.
     * A failed wake is logged but not thrown: we still try the follow-up cached read.
     * Records outcome to [metrics] as its own operation ("status(wake)") so a 503 / resCode 5031
     * shows up in the Statistics screen's rate-limit counter, even though the outer status() call
     * ultimately succeeds (with cached data).
     */
    private suspend fun triggerCcs2Wake(vehicleId: String) {
        diag("waking car via /ccs2/carstatus…")
        val start = System.nanoTime()
        val response = http.get("${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/ccs2/carstatus") {
            baseHeaders(this)
            header("Authorization", authHeader())
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        if (!response.ok()) {
            val detail = describeFailure("ccs2/carstatus (wake)", response)
            // CCSP returns 503 with resCode 5031 for account-level rate limiting. Also treat 429.
            val bodyLower = detail.lowercase()
            val rateLimited = response.status == HttpStatusCode.TooManyRequests ||
                response.status == HttpStatusCode.ServiceUnavailable ||
                bodyLower.contains("5031") ||
                bodyLower.contains("rate")
            metrics.record(
                operation = "status(wake)",
                durationMs = elapsedMs,
                outcome = if (rateLimited) ApiOutcome.RATE_LIMITED else ApiOutcome.FAILURE,
                detail = detail,
            )
            return
        }
        metrics.record("status(wake)", elapsedMs, ApiOutcome.SUCCESS)
        diag("ccs2/carstatus wake → HTTP ${response.status.value} ✓")
    }

    private suspend fun fetchStatusPrimary(vehicleId: String, ccs2Path: String, v1FallbackPath: String, ccs2: Boolean): VehicleStatus {
        if (!ccs2) return fetchStatusV1(vehicleId, v1FallbackPath)
        // Canonical hyundai_kia_connect_api uses /api/v1/spa/ for CCS2 STATUS endpoints and only
        // /api/v2/spa/ for control (door/charge/temperature). Getting this wrong returns 403
        // "Access to this API has been disallowed".
        val ccs2Url = "${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/ccs2/$ccs2Path"
        val ccs2Response = http.get(ccs2Url) {
            baseHeaders(this)
            header("Authorization", authHeader())
        }
        if (ccs2Response.ok()) return parseCcs2Status(ccs2Response.body())
        // 400/403/404 = "endpoint not valid for this vehicle" — try v1 (rare, only if the flag is
        // wrong). Any other status (429, real 503 cooldown, 401) is reported as-is so we don't mask it.
        val fallbackWorthy = ccs2Response.status.value in setOf(400, 403, 404)
        if (!fallbackWorthy) error(describeFailure("carstatus $ccs2Path (ccs2)", ccs2Response))
        describeFailure("carstatus $ccs2Path (ccs2)", ccs2Response)
        diag("carstatus → falling back to v1 $v1FallbackPath…")
        return fetchStatusV1(vehicleId, v1FallbackPath)
    }

    private suspend fun fetchStatusV1(vehicleId: String, path: String): VehicleStatus {
        val response = http.get("${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/$path") {
            baseHeaders(this)
            header("Authorization", authHeader())
        }
        if (!response.ok()) error(describeFailure("status $path (v1)", response))
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

    /** Parses the deeply-nested CCS2 status payload into our flat [VehicleStatus] model. */
    private fun parseCcs2Status(env: Ccs2StatusEnvelope): VehicleStatus {
        val v = env.resMsg?.state?.vehicle
        val driverDoor = v?.cabin?.door?.row1?.driver
        val locked = driverDoor?.lock
        val doorRows = listOfNotNull(v?.cabin?.door?.row1, v?.cabin?.door?.row2)
        val anyOpen = doorRows.flatMap {
            listOfNotNull(it.driver, it.passenger, it.left, it.right)
        }.any { (it.open ?: 0) != 0 }
        val ignition = v?.drivetrain?.fuelSystem?.ignitionStatus
        val engineOn = ignition != null && !ignition.equals("Off", ignoreCase = true)
        // DTE Unit: 1 = km, 3 = miles (per hyundai_kia_connect_api). We normalise to km.
        val dte = v?.drivetrain?.fuelSystem?.dte?.total
        val rangeKm = dte?.value?.let { value ->
            when (dte.unit) {
                3 -> (value * 1.609344).toInt() // miles → km
                else -> value.toInt()
            }
        }
        return VehicleStatus(
            lockState = when (locked) {
                1 -> LockState.LOCKED
                0 -> LockState.UNLOCKED
                else -> LockState.UNKNOWN
            },
            engineRunning = engineOn,
            batteryCharging = v?.green?.chargingInformation?.charging?.remainTime?.let { it > 0 },
            timestamp = System.currentTimeMillis(),
            evBatteryPercent = v?.green?.batteryManagement?.batteryRemain?.ratio?.toInt(),
            rangeKm = rangeKm,
            climateOn = v?.cabin?.hvac?.row1?.hvac?.active?.let { it != 0 },
            twelveVoltPercent = v?.electronics?.battery?.level,
            anyDoorOpen = anyOpen,
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
            val ccs2 = isCcs2Vehicle(vehicleId)

            // Ioniq 5 / EV6 / IONIQ 6 (CCS2) use v2/ccs2 with a "command" body. Older cars (Kona
            // PHEV, older IONIQ) use v1 with "action" + deviceId. For known-CCS2 vehicles we go
            // directly to CCS2 (no wasted round-trip). Fallback only happens on 400/403/404
            // ("endpoint not applicable"), which also gets logged.
            if (ccs2) {
                diag("control/door $action → sending (ccs2)…")
                val v2 = http.post("${config.apiBaseUrl}/api/v2/spa/vehicles/$vehicleId/ccs2/control/door") {
                    baseHeaders(this)
                    header("Authorization", controlAuth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"command":"$action"}""")
                }
                if (v2.ok()) {
                    diag("control/door $action (ccs2) → HTTP ${v2.status.value} ✓")
                    return CommandResult.Success(if (close) "Locked" else "Unlocked")
                }
                when (v2.status) {
                    HttpStatusCode.TooManyRequests -> {
                        describeFailure("control/door $action (ccs2)", v2)
                        return CommandResult.RateLimited
                    }
                    HttpStatusCode.Unauthorized -> {
                        describeFailure("control/door $action (ccs2)", v2)
                        return CommandResult.NotAuthenticated
                    }
                    else -> Unit
                }
                // Only fall back for "endpoint not applicable" statuses. Real cooldowns / server
                // errors are reported as-is so v1's identical failure doesn't shadow them.
                val fallbackWorthy = v2.status.value in setOf(400, 403, 404)
                if (!fallbackWorthy) return CommandResult.Failure(describeFailure("control/door $action (ccs2)", v2))
                describeFailure("control/door $action (ccs2)", v2)
                diag("control/door $action → falling back to v1…")
            }
            // Legacy v1 path (CCS1 vehicles, or CCS2 vehicles whose flag turned out wrong).
            diag("control/door $action → sending (v1)…")
            val v1 = http.post("${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/control/door") {
                baseHeaders(this)
                header("Authorization", controlAuth)
                contentType(ContentType.Application.Json)
                setBody("""{"action":"$action","deviceId":"$deviceId"}""")
            }
            when {
                v1.ok() -> {
                    diag("control/door $action (v1) → HTTP ${v1.status.value} ✓")
                    CommandResult.Success(if (close) "Locked" else "Unlocked")
                }
                v1.status == HttpStatusCode.TooManyRequests -> {
                    describeFailure("control/door $action (v1)", v1); CommandResult.RateLimited
                }
                v1.status == HttpStatusCode.Unauthorized -> {
                    describeFailure("control/door $action (v1)", v1); CommandResult.NotAuthenticated
                }
                else -> CommandResult.Failure(describeFailure("control/door $action (v1)", v1))
            }
        } catch (t: Throwable) {
            val detail = t.message ?: t::class.simpleName ?: "unknown error"
            diag("✗ command exception: $detail")
            CommandResult.Failure("Command error: $detail", t)
        }
    }

    /**
     * Verifies the BlueLink PIN to obtain a short-lived control token (fetched fresh per command).
     * Returns null if no PIN is stored.
     */
    private suspend fun controlAuthorization(vehicleId: String, deviceId: String): String? {
        val pin = secureStore.loadPin()?.takeIf { it.isNotBlank() } ?: return null
        diag("user/pin → requesting control token…")
        val response: HttpResponse = http.put("${config.apiBaseUrl}/api/v1/user/pin") {
            baseHeaders(this)
            header("Authorization", authHeader())
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"$deviceId","pin":"$pin","vehicleId":"$vehicleId"}""")
        }
        if (!response.ok()) error(describeFailure("user/pin", response))
        val env: PinEnvelope = response.body()
        val controlToken = env.controlToken ?: env.resMsg?.controlToken ?: env.retValue?.controlToken
            ?: run {
                diag("✗ user/pin → HTTP ${response.status.value} but no controlToken in body")
                return null
            }
        diag("user/pin → HTTP ${response.status.value} ✓ (controlToken received)")
        return "Bearer $controlToken"
    }

    override suspend fun clearSession() = secureStore.clear()

    /**
     * Runs 3 read-only probes in order and returns a multi-line report. Each line has the endpoint,
     * HTTP status, duration and (on failure) the server's response body — so the user can tell
     * exactly where the 503 comes from without having to interpret our internal flow. Also updates
     * [lastLivePollAtMs] so an aborted diagnostic doesn't blow past the min-refresh cooldown.
     */
    override suspend fun diagnose(vehicleId: String): String {
        if (!ensureFreshSession()) return "✗ No valid session — sign in first."
        val out = StringBuilder()
        fun row(label: String, status: Int?, ms: Long, extra: String) {
            val mark = when {
                status == null -> "✗"
                status in 200..299 -> "✓"
                else -> "✗"
            }
            val code = status?.toString() ?: "n/a"
            out.appendLine("$mark $label → HTTP $code (${ms}ms)${if (extra.isNotBlank()) "  $extra" else ""}")
        }
        // Record each probe to ApiMetrics so a rate-limited diagnostic bumps the counter on the
        // Statistics screen — same as a real refresh would.
        fun recordProbe(op: String, status: Int?, body: String, ms: Long) {
            val outcome = when {
                status == null -> ApiOutcome.FAILURE
                status in 200..299 -> ApiOutcome.SUCCESS
                status == 429 || status == 503 || body.lowercase().contains("5031") -> ApiOutcome.RATE_LIMITED
                status == 401 -> ApiOutcome.UNAUTHENTICATED
                else -> ApiOutcome.FAILURE
            }
            metrics.record(op, ms, outcome, if (outcome == ApiOutcome.SUCCESS) null else body.take(120))
        }
        // 1) metadata — auth-only, never rate-limited or protocol-dependent
        val t1 = System.currentTimeMillis()
        val r1 = runCatching {
            http.get("${config.apiBaseUrl}/api/v1/spa/vehicles") {
                baseHeaders(this); header("Authorization", authHeader())
            }
        }
        val d1 = System.currentTimeMillis() - t1
        r1.onSuccess {
            val body = if (it.ok()) "" else it.snippet()
            row("GET /spa/vehicles (auth)", it.status.value, d1, body)
            recordProbe("diagnose/vehicles", it.status.value, body, d1)
        }.onFailure {
            row("GET /spa/vehicles (auth)", null, d1, "exception: ${it.message}")
            recordProbe("diagnose/vehicles", null, it.message ?: "", d1)
        }

        // 2) CACHED status — read Hyundai's server-side cache, never wakes the car
        val t2 = System.currentTimeMillis()
        val r2 = runCatching {
            http.get("${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/ccs2/carstatus/latest") {
                baseHeaders(this); header("Authorization", authHeader())
            }
        }
        val d2 = System.currentTimeMillis() - t2
        r2.onSuccess {
            val body = if (it.ok()) "" else it.snippet()
            row("GET ccs2/carstatus/latest (cached)", it.status.value, d2, body)
            recordProbe("diagnose/carstatus_latest", it.status.value, body, d2)
        }.onFailure {
            row("GET ccs2/carstatus/latest (cached)", null, d2, "exception: ${it.message}")
            recordProbe("diagnose/carstatus_latest", null, it.message ?: "", d2)
        }

        // 3) LIVE poll — the endpoint that Hyundai rate-limits per account
        val t3 = System.currentTimeMillis()
        val r3 = runCatching {
            http.get("${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/ccs2/carstatus") {
                baseHeaders(this); header("Authorization", authHeader())
            }
        }
        val d3 = System.currentTimeMillis() - t3
        r3.onSuccess {
            val body = if (it.ok()) "" else it.snippet()
            row("GET ccs2/carstatus (wake trigger)", it.status.value, d3, body)
            recordProbe("diagnose/carstatus_wake", it.status.value, body, d3)
        }.onFailure {
            row("GET ccs2/carstatus (wake trigger)", null, d3, "exception: ${it.message}")
            recordProbe("diagnose/carstatus_wake", null, it.message ?: "", d3)
        }
        // A diagnostic live poll should still count toward the cooldown so we don't spam.
        lastLivePollAtMs = System.currentTimeMillis()

        // Interpretation footer to help the user decide next steps.
        val v = r1.getOrNull()?.status?.value ?: 0
        val c = r2.getOrNull()?.status?.value ?: 0
        val l = r3.getOrNull()?.status?.value ?: 0
        val interpretation = when {
            v !in 200..299 -> "AUTH is broken — sign in again."
            c !in 200..299 && l !in 200..299 -> "Hyundai status service degraded (cached + live both failing). Server-side."
            c in 200..299 && l !in 200..299 -> "Live poll rate-limited on your account (server-side). Cached works."
            c in 200..299 && l in 200..299 -> "All endpoints healthy ✓"
            else -> "Mixed result — see per-line detail above."
        }
        out.appendLine()
        out.append("→ ").append(interpretation)
        return out.toString().trim()
    }

    private companion object {
        const val TAG = "EuBlueLinkClient"

        // How long to wait after a CCS2 wake trigger before reading the cached snapshot.
        // Canonical hyundai_kia_connect_api uses 25s (~20s live-measured on a reachable EU CCS2 car).
        const val CCS2_WAKE_DELAY_MS = 25_000L
    }
}
