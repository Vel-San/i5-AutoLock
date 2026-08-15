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
import io.ktor.client.plugins.timeout
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Real EU BlueLink (Hyundai) client.
 *
 * This implements the shape of the CCS OAuth + control flow ported from
 * `hyundai_kia_connect_api` and `bluelinky`. Exact endpoints, headers and the
 * control-token dance evolve over time — keep all EU specifics in this package.
 *
 * Sign-in is email + password via the OneApp/CCI flow ([EuIdpAuth]); the resulting CCS token
 * is used as a Bearer against the ccapi:8080 vehicle/control endpoints.
 */
class EuBlueLinkClient(
    private val http: HttpClient,
    private val config: RegionConfig,
    private val secureStore: SecureStore,
    private val settingsRepo: SettingsRepository,
    private val metrics: ApiMetrics,
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

    /**
     * Logs a failed request/response to the activity log and returns the formatted reason. Includes
     * the full URL + method + safe headers + response body — everything you need to identify which
     * endpoint failed. Sensitive headers (Authorization, Stamp, ccsp-device-id) are omitted or
     * redacted; the response body from Hyundai never contains secrets, so we log the raw snippet.
     */
    private suspend fun describeFailure(op: String, response: HttpResponse): String {
        val request = response.call.request
        val method = request.method.value
        val url = request.url.toString()
        val body = response.snippet()
        val safeHeaders = SAFE_HEADER_NAMES
            .mapNotNull { name -> request.headers[name]?.let { "$name=$it" } }
            .joinToString(", ")
        val line = buildString {
            append("$op → HTTP ${response.status.value}")
            append(" | $method $url")
            if (safeHeaders.isNotEmpty()) append(" | headers: $safeHeaders")
            if (body.isNotBlank()) append(" | body: $body")
        }
        diag("✗ $line")
        return line
    }

    /** Logs a request URL before firing it, so a hung request is visible in the activity log. */
    private fun logRequest(op: String, method: String, url: String) {
        diag("→ $method $url  [$op]")
    }

    /**
     * Parses a CCSP command envelope `{retCode,resCode,resMsg,msgId}`. CCSP frequently returns
     * HTTP 200 with `retCode:"F"` when the car rejects a command (offline, ignition on, etc), so
     * we MUST inspect the body rather than trust the HTTP status. `resMsg` may be a string or an
     * object; both shapes are tolerated.
     */
    private data class CommandOutcome(
        val ok: Boolean,
        val retCode: String?,
        val resCode: String?,
        val resMsg: String?,
        val msgId: String?,
        val snippet: String,
    )

    private suspend fun readCommandOutcome(response: HttpResponse): CommandOutcome {
        val raw = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
        val snippet = raw.take(300).replace(Regex("\\s+"), " ").trim()
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        val retCode = obj?.get("retCode")?.jsonPrimitive?.contentOrNull
        val resCode = obj?.get("resCode")?.jsonPrimitive?.contentOrNull
        val resMsgEl = obj?.get("resMsg")
        val resMsg = when (resMsgEl) {
            is JsonPrimitive -> resMsgEl.contentOrNull
            is JsonObject -> resMsgEl.toString()
            null -> null
            else -> resMsgEl.toString()
        }
        val msgId = obj?.get("msgId")?.jsonPrimitive?.contentOrNull
            ?: (resMsgEl as? JsonObject)?.get("msgId")?.jsonPrimitive?.contentOrNull
        // Empty body or missing retCode → treat HTTP status as truth (older endpoints).
        val ok = when {
            raw.isBlank() -> response.status.value in 200..299
            retCode == null -> response.status.value in 200..299
            else -> retCode.equals("S", ignoreCase = true)
        }
        return CommandOutcome(ok, retCode, resCode, resMsg, msgId, snippet)
    }

    /** Async command execution result reported by the vehicle (via notifications/records). */
    private enum class ActionState { SUCCESS, FAILED, PENDING }

    private data class ActionCheck(
        val state: ActionState,
        val resultCode: String?,
        val record: String? = null,
    )

    /**
     * Polls `notifications/{vehicleId}/records` for the given msgId. EU CCS2 control commands are
     * async — `retCode:"S"` only means the backend queued the request; the car's actual execution
     * result appears here (result="success"/"fail"). Polls every 3s up to [timeoutMs] with a short
     * per-request timeout so a stuck endpoint can't eat the whole poll window. Tries v2 first, then
     * v1 (references disagree; we pick whichever answers 200).
     */
    private suspend fun pollCommandStatus(
        vehicleId: String,
        msgId: String,
        timeoutMs: Long = 30_000L,
        pollEveryMs: Long = 3_000L,
    ): ActionCheck {
        val deadline = System.currentTimeMillis() + timeoutMs
        val candidates = mutableListOf(
            "${config.apiBaseUrl}/api/v2/spa/notifications/$vehicleId/records",
            "${config.apiBaseUrl}/api/v1/spa/notifications/$vehicleId/records",
        )
        var attempt = 0
        var firstProbeLogged = false
        while (System.currentTimeMillis() < deadline) {
            attempt++
            for ((idx, url) in candidates.withIndex()) {
                val label = if (idx == 0) "v2" else "v1"
                val start = System.currentTimeMillis()
                val result = runCatching {
                    http.get(url) {
                        baseHeaders(this)
                        header("Authorization", authHeader())
                        timeout { requestTimeoutMillis = 8_000 }
                    }
                }
                val response = result.getOrNull()
                if (response == null) {
                    val err = result.exceptionOrNull()?.message ?: "unknown"
                    if (!firstProbeLogged) {
                        diag("action poll ($label) #$attempt → ✗ request failed in ${System.currentTimeMillis() - start}ms: $err")
                        firstProbeLogged = true
                    }
                    continue
                }
                val raw = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
                if (!firstProbeLogged) {
                    val snippet = raw.take(200).replace(Regex("\\s+"), " ").trim()
                    diag("action poll ($label) #$attempt → HTTP ${response.status.value} in ${System.currentTimeMillis() - start}ms | body: $snippet")
                    firstProbeLogged = true
                }
                if (!response.ok()) continue
                // Successful response on this path — drop the other candidate to save requests.
                if (candidates.size > 1) candidates.removeAt(if (idx == 0) 1 else 0)
                val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                val records = (obj?.get("resMsg") as? JsonArray) ?: JsonArray(emptyList())
                for (rec in records) {
                    val recObj = rec as? JsonObject ?: continue
                    val id = recObj["recordId"]?.jsonPrimitive?.contentOrNull
                    if (id != msgId) continue
                    val resultStr = recObj["result"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    val resultCode = recObj["resultCode"]?.jsonPrimitive?.contentOrNull
                    val recordMsg = recObj["record"]?.jsonPrimitive?.contentOrNull
                    // Log the full matching record so the actual failure reason is visible.
                    diag("action poll ($label): matched record → ${recObj.toString().take(500)}")
                    when (resultStr) {
                        "success" -> return ActionCheck(ActionState.SUCCESS, resultCode, recordMsg)
                        "fail", "failure" -> return ActionCheck(ActionState.FAILED, resultCode ?: resultStr, recordMsg)
                        else -> break
                    }
                }
                break
            }
            delay(pollEveryMs)
        }
        diag("action poll: msgId=$msgId not confirmed after ${timeoutMs / 1000}s ($attempt tries)")
        return ActionCheck(ActionState.PENDING, null)
    }

    /** Wraps [pollCommandStatus] with logging + a UI-friendly CommandResult mapping. */
    private suspend fun confirmActionOrReport(
        vehicleId: String,
        msgId: String,
        action: String,
        close: Boolean,
    ): CommandResult {
        diag("action poll: waiting for car to confirm $action (msgId=$msgId)…")
        val check = pollCommandStatus(vehicleId, msgId)
        return when (check.state) {
            ActionState.SUCCESS -> {
                diag("action poll: ✓ $action confirmed by car (resultCode=${check.resultCode ?: "n/a"})")
                CommandResult.Success(if (close) "Locked" else "Unlocked")
            }
            ActionState.FAILED -> {
                val code = check.resultCode ?: "unknown"
                diag("action poll: ✗ car reported failure for $action (resultCode=$code)")
                // Prefer Hyundai's own human-readable "record" message (e.g. "[Fail] Cannot unlock
                // door. Please check your vehicle status.") — it's the same wording the myHyundai
                // app shows and gives the user a real starting point.
                val cleaned = check.record
                    ?.replace(Regex("^\\[[^]]+]\\s*"), "") // strip leading "[Fail] "
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                if (cleaned != null) {
                    CommandResult.Failure("Hyundai says: $cleaned Usually means remote services are disabled in the car, the car is offline/asleep, ignition is on, or the door is already in the requested state.")
                } else {
                    CommandResult.Failure("Car reported $action failed (resultCode $code). The car refused without a specific error — usually remote services disabled in the head-unit, car offline/asleep, ignition on, or door already in the requested state.")
                }
            }
            ActionState.PENDING -> {
                CommandResult.Failure("Command queued but the car didn't confirm within 30 s. It may be offline / in a poor signal area / asleep. Try again in a minute.")
            }
        }
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

    override suspend fun isAuthenticated(): Boolean = secureStore.loadTokens() != null

    /**
     * Fully headless EU sign-in with email + password via the OneApp/CCI flow (see [EuIdpAuth]).
     * Register the CCSP device, run the CCI login, then persist the CCS token + CCI token set.
     */
    override suspend fun loginWithPassword(username: String, password: String): CommandResult {
        if (config.oneAppClientId == null) return CommandResult.Failure("Password login isn't supported here.")
        return try {
            diag("Login: registering device…")
            val deviceId = ensureDeviceRegistered()
            val tokens = EuIdpAuth().login(config, username.trim(), password, deviceId, diag)
            persist(tokens)
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

    /**
     * The interface's OAuth code-exchange entry point. EU sign-in is now email + password
     * (OneApp/CCI); the legacy browser/code flow is gone, so direct callers here.
     */
    override suspend fun login(username: String, authCodeOrPassword: String): CommandResult =
        CommandResult.Failure("Sign in with your email and password.")

    private fun persist(tokens: EuIdpAuth.Tokens) {
        val prev = secureStore.loadTokens()
        secureStore.saveTokens(
            SessionTokens(
                accessToken = tokens.ccsAccessToken,
                refreshToken = tokens.cci.refreshToken,
                tokenType = "Bearer",
                expiresAtEpochMs = tokens.ccsExpiresAtEpochMs,
                deviceId = currentDeviceId(),
                controlToken = prev?.controlToken,
                controlTokenExpiresAtEpochMs = prev?.controlTokenExpiresAtEpochMs ?: 0L,
                cciAccessToken = tokens.cci.accessToken,
                exchangeableToken = tokens.cci.exchangeableToken,
                exchangeableRefreshToken = tokens.cci.exchangeableRefreshToken,
                nonCcsToken = tokens.cci.nonCcsToken,
                nonCcsRefreshToken = tokens.cci.nonCcsRefreshToken,
                idToken = tokens.cci.idToken,
            ),
        )
    }

    /** Reconstruct the CCI token set from stored session tokens (for refresh). */
    private fun SessionTokens.toCciTokens(): EuIdpAuth.CciTokens? {
        val cciAccess = cciAccessToken ?: return null
        return EuIdpAuth.CciTokens(
            accessToken = cciAccess,
            refreshToken = refreshToken,
            nonCcsToken = nonCcsToken.orEmpty(),
            nonCcsRefreshToken = nonCcsRefreshToken.orEmpty(),
            exchangeableToken = exchangeableToken.orEmpty(),
            exchangeableRefreshToken = exchangeableRefreshToken.orEmpty(),
            idToken = idToken.orEmpty(),
        )
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

    override suspend fun ensureFreshSession(): Boolean = refreshMutex.withLock {
        val tokens = secureStore.loadTokens() ?: return false
        if (!tokens.isAccessExpired) return true
        val cci = tokens.toCciTokens() ?: return false
        return try {
            val refreshed = EuIdpAuth().refresh(config, currentDeviceId(), cci, diag)
            persist(refreshed)
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
        val url = "${config.apiBaseUrl}/api/v1/spa/vehicles"
        logRequest("vehicles", "GET", url)
        val response = http.get(url) {
            baseHeaders(this)
            header("Authorization", authHeader())
        }
        if (!response.ok()) error(describeFailure("vehicles", response))
        val envelope: VehiclesEnvelope = response.body()
        val vehicles = envelope.resMsg?.vehicles.orEmpty().map {
            // Hyundai's ccuCCS2ProtocolSupport / protocolType on the vehicles list are UNRELIABLE
            // — the same Ioniq 5 has been observed reporting 0/0 even though CCS2 endpoints work.
            // We default every vehicle to CCS2 (Ioniq-5-first) and let status()'s runtime probe
            // (probeAndPersistCcs2Flag) downgrade to legacy on a real 400/403/404 from the CCS2
            // endpoint. The parsed flags are logged for debugging only.
            diag("vehicle parse: ${it.nickname.ifBlank { it.vehicleName }} — ccuCCS2ProtocolSupport=${it.ccuCCS2ProtocolSupport} protocolType=${it.protocolType} → CCS2 (default, confirmed at first status)")
            Vehicle(
                id = it.vehicleId,
                vin = it.vin,
                nickname = it.nickname.ifBlank { it.vehicleName }.ifBlank { "Vehicle" },
                model = it.vehicleName,
                regDate = it.regDate,
                ccs2 = true,
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

    /**
     * Probes the CCS2 cached endpoint (safe, never wakes the car) to check whether the vehicle
     * actually speaks CCS2. Persists the corrected flag onto the KnownVehicle so subsequent calls
     * skip the probe. Returns the probed value; on any non-conclusive response (401, 429, 503,
     * network error) returns `false` and leaves the persisted flag untouched.
     */
    private suspend fun probeAndPersistCcs2Flag(vehicleId: String): Boolean {
        val url = "${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/ccs2/carstatus/latest"
        logRequest("ccs2-probe", "GET", url)
        val response = runCatching {
            http.get(url) { baseHeaders(this); header("Authorization", authHeader()) }
        }.getOrElse {
            diag("ccs2-probe: exception (${it.message}) — keeping cached flag")
            return false
        }
        return when {
            response.ok() -> {
                diag("ccs2-probe: HTTP 200 → upgrading vehicle to CCS2")
                persistCcs2Flag(vehicleId, true)
                true
            }
            response.status.value in setOf(400, 403, 404) -> {
                describeFailure("ccs2-probe", response)
                diag("ccs2-probe: endpoint not applicable → confirming legacy")
                persistCcs2Flag(vehicleId, false)
                false
            }
            else -> {
                describeFailure("ccs2-probe", response)
                diag("ccs2-probe: inconclusive HTTP ${response.status.value} — keeping cached flag")
                false
            }
        }
    }

    private suspend fun persistCcs2Flag(vehicleId: String, ccs2: Boolean) {
        settingsRepo.update { s ->
            s.copy(knownVehicles = s.knownVehicles.map { if (it.id == vehicleId) it.copy(ccs2 = ccs2) else it })
        }
    }

    override suspend fun status(vehicleId: String, forceRefresh: Boolean): VehicleStatus {
        ensureFreshSession()
        val settings = settingsRepo.settings.first()
        var ccs2 = settings.knownVehicles.firstOrNull { it.id == vehicleId }?.ccs2 ?: true
        // Self-heal: if the persisted classification says legacy, probe the (safe, never-wakes)
        // cached CCS2 endpoint once. A 200 upgrades and rewrites the cached KnownVehicle so future
        // calls skip this probe. 400/403/404 leaves it legacy; anything else keeps the current guess.
        if (!ccs2) {
            ccs2 = probeAndPersistCcs2Flag(vehicleId)
        }
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
            val woke = triggerCcs2Wake(vehicleId)
            if (woke) {
                diag("carstatus: waiting ${CCS2_WAKE_DELAY_MS / 1000}s for car to report…")
                delay(CCS2_WAKE_DELAY_MS)
            } else {
                // Wake failed (typically 503/5031 account rate-limit). No point sleeping 25s for a
                // car that never woke up — read cached immediately so the user still gets state.
                diag("carstatus: wake failed → skipping 25s delay, reading cached now")
            }
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
    private suspend fun triggerCcs2Wake(vehicleId: String): Boolean {
        val url = "${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/ccs2/carstatus"
        logRequest("wake", "GET", url)
        val start = System.nanoTime()
        val response = http.get(url) {
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
            return false
        }
        metrics.record("status(wake)", elapsedMs, ApiOutcome.SUCCESS)
        diag("ccs2/carstatus wake → HTTP ${response.status.value} ✓")
        return true
    }

    private suspend fun fetchStatusPrimary(vehicleId: String, ccs2Path: String, v1FallbackPath: String, ccs2: Boolean): VehicleStatus {
        if (!ccs2) return fetchStatusV1(vehicleId, v1FallbackPath)
        // Canonical hyundai_kia_connect_api uses /api/v1/spa/ for CCS2 STATUS endpoints and only
        // /api/v2/spa/ for control (door/charge/temperature). Getting this wrong returns 403
        // "Access to this API has been disallowed".
        val ccs2Url = "${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/ccs2/$ccs2Path"
        logRequest("carstatus $ccs2Path (ccs2)", "GET", ccs2Url)
        val ccs2Response = http.get(ccs2Url) {
            baseHeaders(this)
            header("Authorization", authHeader())
        }
        if (ccs2Response.ok()) {
            diag("carstatus $ccs2Path (ccs2) → HTTP ${ccs2Response.status.value} ✓")
            return parseCcs2Status(ccs2Response.body())
        }
        // 400/403/404 = "endpoint not valid for this vehicle" — try v1 (rare, only if the flag is
        // wrong). Any other status (429, real 503 cooldown, 401) is reported as-is so we don't mask it.
        val fallbackWorthy = ccs2Response.status.value in setOf(400, 403, 404)
        if (!fallbackWorthy) error(describeFailure("carstatus $ccs2Path (ccs2)", ccs2Response))
        describeFailure("carstatus $ccs2Path (ccs2)", ccs2Response)
        diag("carstatus → falling back to v1 $v1FallbackPath…")
        return fetchStatusV1(vehicleId, v1FallbackPath)
    }

    private suspend fun fetchStatusV1(vehicleId: String, path: String): VehicleStatus {
        val url = "${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/$path"
        logRequest("status $path (v1)", "GET", url)
        val response = http.get(url) {
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
        // EU CCS2 payload is flat: {"EV":234,"Total":234,"Unit":1}. Prefer Total; fall back to EV.
        val dte = v?.drivetrain?.fuelSystem?.dte
        val rangeKm = (dte?.total ?: dte?.ev)?.let { value ->
            when (dte?.unit) {
                3 -> (value * 1.609344).toInt() // miles → km
                else -> value.toInt()
            }
        }
        val lockState = when (locked) {
            1 -> LockState.LOCKED
            0 -> LockState.UNLOCKED
            else -> LockState.UNKNOWN
        }
        val battery = v?.green?.batteryManagement?.batteryRemain?.ratio?.toInt()
        diag("ccs2 parsed: lock=$locked → $lockState, doorOpen=$anyOpen, battery=$battery%, range=${rangeKm}km, 12V=${v?.electronics?.battery?.level}%")
        return VehicleStatus(
            lockState = lockState,
            engineRunning = engineOn,
            batteryCharging = v?.green?.chargingInformation?.charging?.remainTime?.let { it > 0 },
            timestamp = System.currentTimeMillis(),
            evBatteryPercent = battery,
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
                val v2Url = "${config.apiBaseUrl}/api/v2/spa/vehicles/$vehicleId/ccs2/control/door"
                diag("control/door $action → sending (ccs2)…")
                logRequest("control/door $action (ccs2)", "POST", v2Url)
                val v2 = http.post(v2Url) {
                    baseHeaders(this)
                    header("Authorization", controlAuth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"command":"$action"}""")
                }
                if (v2.ok()) {
                    val outcome = readCommandOutcome(v2)
                    val msgTail = buildString {
                        outcome.retCode?.let { append(" retCode=$it") }
                        outcome.resCode?.let { append(" resCode=$it") }
                        outcome.msgId?.let { append(" msgId=$it") }
                    }
                    if (outcome.ok) {
                        diag("control/door $action (ccs2) → HTTP ${v2.status.value} queued…$msgTail")
                        val msgId = outcome.msgId
                        return if (msgId.isNullOrBlank()) {
                            CommandResult.Success(if (close) "Locked" else "Unlocked")
                        } else {
                            confirmActionOrReport(vehicleId, msgId, action, close)
                        }
                    }
                    // HTTP 200 but the car/backend rejected it — surface the exact reason.
                    diag("✗ control/door $action (ccs2) → HTTP ${v2.status.value} but body says failure$msgTail | body: ${outcome.snippet}")
                    val reason = outcome.resMsg?.takeIf { it.isNotBlank() }
                        ?: outcome.resCode?.let { "resCode $it" }
                        ?: outcome.snippet.ifBlank { "unknown" }
                    return CommandResult.Failure("Car rejected $action: $reason")
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
            val v1Url = "${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/control/door"
            diag("control/door $action → sending (v1)…")
            logRequest("control/door $action (v1)", "POST", v1Url)
            val v1 = http.post(v1Url) {
                baseHeaders(this)
                header("Authorization", controlAuth)
                contentType(ContentType.Application.Json)
                setBody("""{"action":"$action","deviceId":"$deviceId"}""")
            }
            when {
                v1.ok() -> {
                    val outcome = readCommandOutcome(v1)
                    val msgTail = buildString {
                        outcome.retCode?.let { append(" retCode=$it") }
                        outcome.resCode?.let { append(" resCode=$it") }
                        outcome.msgId?.let { append(" msgId=$it") }
                    }
                    if (outcome.ok) {
                        diag("control/door $action (v1) → HTTP ${v1.status.value} queued…$msgTail")
                        val msgId = outcome.msgId
                        if (msgId.isNullOrBlank()) {
                            CommandResult.Success(if (close) "Locked" else "Unlocked")
                        } else {
                            confirmActionOrReport(vehicleId, msgId, action, close)
                        }
                    } else {
                        diag("✗ control/door $action (v1) → HTTP ${v1.status.value} but body says failure$msgTail | body: ${outcome.snippet}")
                        val reason = outcome.resMsg?.takeIf { it.isNotBlank() }
                            ?: outcome.resCode?.let { "resCode $it" }
                            ?: outcome.snippet.ifBlank { "unknown" }
                        CommandResult.Failure("Car rejected $action: $reason")
                    }
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
        val url = "${config.apiBaseUrl}/api/v1/user/pin"
        diag("user/pin → requesting control token…")
        logRequest("user/pin", "PUT", url)
        val response: HttpResponse = http.put(url) {
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

    override suspend fun resetDeviceRegistration() {
        val old = secureStore.loadDeviceId()
        secureStore.clearDeviceId()
        diag("device registration reset (old=${old?.take(8) ?: "none"}…) — will re-register on next call")
    }

    /**
     * Runs 3 read-only probes in order and returns a multi-line report. Each line has the endpoint,
     * HTTP status, duration and (on failure) the server's response body — so the user can tell
     * exactly where the 503 comes from without having to interpret our internal flow. Also updates
     * [lastLivePollAtMs] so an aborted diagnostic doesn't blow past the min-refresh cooldown.
     */
    override suspend fun diagnose(vehicleId: String): String {
        if (!ensureFreshSession()) return "✗ No valid session — sign in first."
        val out = StringBuilder()
        fun row(label: String, url: String, status: Int?, ms: Long, body: String) {
            val mark = when {
                status == null -> "✗"
                status in 200..299 -> "✓"
                else -> "✗"
            }
            val code = status?.toString() ?: "n/a"
            out.appendLine("$mark $label → HTTP $code (${ms}ms)")
            out.appendLine("    URL: $url")
            if (body.isNotBlank()) out.appendLine("    body: $body")
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
        // Per-vehicle context header — shows the classification we're about to test against.
        val known = settingsRepo.settings.first().knownVehicles.firstOrNull { it.id == vehicleId }
        val cachedFlag = known?.ccs2 ?: true
        out.appendLine("Vehicle: ${known?.nickname ?: vehicleId}")
        out.appendLine("Cached classification: ${if (cachedFlag) "CCS2" else "legacy"}")
        out.appendLine()

        // 1) metadata — auth-only, never rate-limited or protocol-dependent
        val url1 = "${config.apiBaseUrl}/api/v1/spa/vehicles"
        logRequest("diagnose/vehicles", "GET", url1)
        val t1 = System.currentTimeMillis()
        val r1 = runCatching {
            http.get(url1) { baseHeaders(this); header("Authorization", authHeader()) }
        }
        val d1 = System.currentTimeMillis() - t1
        r1.onSuccess {
            val body = if (it.ok()) "" else it.snippet()
            row("GET /spa/vehicles (auth)", url1, it.status.value, d1, body)
            recordProbe("diagnose/vehicles", it.status.value, body, d1)
        }.onFailure {
            row("GET /spa/vehicles (auth)", url1, null, d1, "exception: ${it.message}")
            recordProbe("diagnose/vehicles", null, it.message ?: "", d1)
        }

        // 2) CACHED CCS2 status — read Hyundai's server-side cache, never wakes the car.
        // Doubles as the self-heal probe: 200 → this vehicle IS CCS2; 400/403/404 → confirmed legacy.
        val url2 = "${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/ccs2/carstatus/latest"
        logRequest("diagnose/carstatus_latest", "GET", url2)
        val t2 = System.currentTimeMillis()
        val r2 = runCatching {
            http.get(url2) { baseHeaders(this); header("Authorization", authHeader()) }
        }
        val d2 = System.currentTimeMillis() - t2
        var confirmedCcs2: Boolean? = null
        r2.onSuccess {
            val body = if (it.ok()) "" else it.snippet()
            row("GET ccs2/carstatus/latest (cached)", url2, it.status.value, d2, body)
            recordProbe("diagnose/carstatus_latest", it.status.value, body, d2)
            when {
                it.ok() -> confirmedCcs2 = true
                it.status.value in setOf(400, 403, 404) -> confirmedCcs2 = false
            }
        }.onFailure {
            row("GET ccs2/carstatus/latest (cached)", url2, null, d2, "exception: ${it.message}")
            recordProbe("diagnose/carstatus_latest", null, it.message ?: "", d2)
        }

        // Self-heal the persisted flag from the probe. Silent no-op if unchanged or inconclusive.
        confirmedCcs2?.let { verdict ->
            if (verdict != cachedFlag) {
                persistCcs2Flag(vehicleId, verdict)
                out.appendLine()
                out.appendLine("↻ Self-heal: reclassified vehicle to ${if (verdict) "CCS2" else "legacy"} (was ${if (cachedFlag) "CCS2" else "legacy"})")
            }
        }

        // 3) Protocol-appropriate LIVE poll. CCS2 → /ccs2/carstatus (wake). Legacy → /status/latest.
        // We use the confirmed verdict when we have one, else fall back to the cached flag.
        val useCcs2Path = confirmedCcs2 ?: cachedFlag
        val url3 = if (useCcs2Path) {
            "${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/ccs2/carstatus"
        } else {
            "${config.apiBaseUrl}/api/v1/spa/vehicles/$vehicleId/status/latest"
        }
        val opLabel = if (useCcs2Path) "diagnose/carstatus_wake" else "diagnose/status_latest_v1"
        val rowLabel = if (useCcs2Path) "GET ccs2/carstatus (wake trigger)" else "GET /status/latest (v1)"
        logRequest(opLabel, "GET", url3)
        val t3 = System.currentTimeMillis()
        val r3 = runCatching {
            http.get(url3) { baseHeaders(this); header("Authorization", authHeader()) }
        }
        val d3 = System.currentTimeMillis() - t3
        r3.onSuccess {
            val body = if (it.ok()) "" else it.snippet()
            row(rowLabel, url3, it.status.value, d3, body)
            recordProbe(opLabel, it.status.value, body, d3)
        }.onFailure {
            row(rowLabel, url3, null, d3, "exception: ${it.message}")
            recordProbe(opLabel, null, it.message ?: "", d3)
        }
        // A diagnostic live poll should still count toward the cooldown so we don't spam.
        lastLivePollAtMs = System.currentTimeMillis()

        // Interpretation footer to help the user decide next steps.
        val v = r1.getOrNull()?.status?.value ?: 0
        val c = r2.getOrNull()?.status?.value ?: 0
        val l = r3.getOrNull()?.status?.value ?: 0
        val interpretation = when {
            v !in 200..299 -> "AUTH is broken — sign in again."
            !useCcs2Path && c in setOf(400, 403, 404) && l in 200..299 -> "Legacy CCSP vehicle confirmed — CCS2 endpoints not applicable, /status/latest works ✓"
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

        // Headers safe to log for diagnostics. Auth/Stamp/tokens/PIN are deliberately excluded.
        val SAFE_HEADER_NAMES = listOf(
            "ccsp-service-id",
            "ccsp-application-id",
            "ccsp-device-id",
            "ccuCCS2ProtocolSupport",
            "User-Agent",
            "Content-Type",
            "Accept-Encoding",
        )
    }
}
