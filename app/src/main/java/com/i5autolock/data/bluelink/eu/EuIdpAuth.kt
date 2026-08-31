package com.i5autolock.data.bluelink.eu

import com.i5autolock.data.bluelink.RegionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
private const val OKHTTP_UA = "okhttp/3.12.0"

/**
 * EU Hyundai/Kia login over raw OkHttp using the **OneApp/CCI flow** (ported from
 * `hyundai_kia_connect_api` #1277 and `bluelink-refresh-token` v6.9.0).
 *
 * Since 2026-08-11 Hyundai's IDPConnect WAF blocks the legacy login `client_id` with the :8080
 * redirect ("classified as an abusing request and blocked"). The OneApp `client_id` is not on
 * that block list, so:
 *   1. authorize with [RegionConfig.oneAppClientId]  (passes the WAF)
 *   2. fetch the RSA cert, RSA-encrypt the password
 *   3. POST /auth/account/signin → 302 straight to the OneApp redirect with `?code=`
 *   4. exchange the code for CCI tokens at `cci-api-eu/domain/api/v1/auth/token`
 *   5. exchange the CCI token for a **CCS token** (`…/token-exchange?serviceType=CCS`) that the
 *      existing ccapi:8080 vehicle/control endpoints still accept as a Bearer access token.
 *
 * A shared cookie jar carries the IDP session from authorize → signin. Root cause of the outage
 * was the client_id block, not a TLS fingerprint, so no browser/WebView is needed.
 */
class EuIdpAuth {

    class LoginException(message: String) : Exception(message)

    /** The CCI token set that must be persisted to refresh the session later. */
    data class CciTokens(
        val accessToken: String,
        val refreshToken: String,
        val nonCcsToken: String,
        val nonCcsRefreshToken: String,
        val exchangeableToken: String,
        val exchangeableRefreshToken: String,
        val idToken: String,
    )

    /** Result of a login/refresh: the CCS token (used against ccapi:8080) + the CCI set. */
    data class Tokens(
        val ccsAccessToken: String,
        val ccsExpiresAtEpochMs: Long,
        val cci: CciTokens,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** One cookie jar shared across the whole login so the IDP session survives every hop. */
    private class SharedCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            store.getOrPut(url.host) { mutableListOf() }.apply {
                cookies.forEach { c -> removeAll { it.name == c.name }; add(c) }
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host].orEmpty().filter { it.expiresAt > System.currentTimeMillis() }
    }

    /** Full password login → CCI tokens → CCS token. [deviceId] is the registered CCSP device id. */
    suspend fun login(
        config: RegionConfig,
        username: String,
        password: String,
        deviceId: String,
        diag: (String) -> Unit,
    ): Tokens = withContext(Dispatchers.IO) {
        val idp = config.idpBaseUrl ?: throw LoginException("Missing IDP base URL.")
        val clientId = config.oneAppClientId ?: throw LoginException("Missing OneApp client id.")
        val redirect = config.oneAppRedirectUri ?: throw LoginException("Missing OneApp redirect.")
        val ua = config.mobileUserAgent

        val jar = SharedCookieJar()
        val http = OkHttpClient.Builder()
            .cookieJar(jar)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val noRedirect = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

        // 1. Authorize (OneApp client_id — passes the WAF that blocks the legacy id).
        val authorizeUrl = "$idp/auth/api/v2/user/oauth2/authorize?response_type=code" +
            "&client_id=$clientId&redirect_uri=${enc(redirect)}&lang=en&state=ccsp&country=de"
        http.newCall(get(authorizeUrl, ua)).execute().use { r ->
            val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
            diag("1/5 authorize → HTTP ${r.code}")
            if (body.contains("abusing", ignoreCase = true) ||
                r.request.url.toString().contains("/error?status=400")
            ) {
                throw LoginException(
                    "Login was blocked by Hyundai's WAF ('abusing request'). This is a server-side " +
                        "block, not your credentials.",
                )
            }
        }

        // 2. Certs — RSA public key; encrypt the password.
        val certsBody = http.newCall(get("$idp/auth/api/v1/accounts/certs", ua)).execute().use { r ->
            diag("2/5 certs → HTTP ${r.code}")
            if (!r.isSuccessful) throw LoginException("Couldn't load login key (HTTP ${r.code}).")
            r.body?.string().orEmpty()
        }
        val jwk = runCatching { json.decodeFromString<CertsEnvelope>(certsBody).retValue }.getOrNull()
            ?: throw LoginException("Login key missing from response.")
        diag("2/5 RSA key loaded (kid=${jwk.kid.take(8)}…)")
        val encryptedPw = EuAuth.encryptPassword(jwk.n, jwk.e, password)

        // 3. Sign in — the OneApp redirect returns the code directly in the 302 Location.
        val signinForm = FormBody.Builder()
            .add("client_id", clientId)
            .add("encryptedPassword", "true")
            .add("password", encryptedPw)
            .add("redirect_uri", redirect)
            .add("scope", "")
            .add("nonce", "")
            .add("state", "ccsp")
            .add("username", username)
            .add("connector_session_key", "")
            .add("kid", jwk.kid)
            .add("_csrf", "")
            .build()
        val signinReq = Request.Builder().url("$idp/auth/account/signin")
            .header("User-Agent", ua)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", ACCEPT_HTML)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", idp)
            .header("Referer", "$idp/auth/account/signin")
            .post(signinForm)
            .build()
        val location = noRedirect.newCall(signinReq).execute().use { r ->
            diag("3/5 signin → HTTP ${r.code}")
            if (r.code !in listOf(301, 302, 303, 307, 308)) {
                val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("").take(200)
                diag("3/5 signin body: ${body.ifBlank { "(empty)" }}")
                throw LoginException("Sign-in failed (HTTP ${r.code}). Check email & password.")
            }
            r.header("Location") ?: throw LoginException("Sign-in returned no redirect location.")
        }
        val code = EuAuth.extractAuthCode(location) ?: throw LoginException(classifyBounce(location))
        diag("3/5 authorization code received ✓")

        // 4 + 5. Exchange the code for CCI tokens, then for a CCS token.
        val cci = exchangeCodeForCci(config, deviceId, code, http, diag)
        val (ccs, ccsExpiry) = exchangeCcsToken(config, deviceId, cci, http, diag)
        Tokens(ccs, ccsExpiry, cci)
    }

    /** Refresh the CCI token set and re-exchange the CCS token — no password needed. */
    suspend fun refresh(
        config: RegionConfig,
        deviceId: String,
        current: CciTokens,
        diag: (String) -> Unit,
    ): Tokens = withContext(Dispatchers.IO) {
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val refreshed = refreshCci(config, deviceId, current, http, diag)
        val (ccs, ccsExpiry) = exchangeCcsToken(config, deviceId, refreshed, http, diag)
        Tokens(ccs, ccsExpiry, refreshed)
    }

    // ── CCI API calls (cci-api-eu.{hyundai,kia}.com) ─────────────────────────────────────────

    private fun exchangeCodeForCci(
        config: RegionConfig,
        deviceId: String,
        code: String,
        http: OkHttpClient,
        diag: (String) -> Unit,
    ): CciTokens {
        val url = "${cciDomainApi(config)}v1/auth/token?code=${enc(code)}"
        val req = Request.Builder().url(url)
            .apply { cciHeaders(config, deviceId).forEach { (k, v) -> header(k, v) } }
            .post(EMPTY_BODY)
            .build()
        val body = http.newCall(req).execute().use { r ->
            diag("4/5 CCI token exchange → HTTP ${r.code}")
            val text = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
            if (!r.isSuccessful) throw LoginException("CCI token exchange failed (HTTP ${r.code}). ${text.take(160)}")
            text
        }
        val cci = json.decodeFromString<CciTokenResponse>(body)
        if (cci.accessToken.isBlank()) throw LoginException("CCI token exchange returned no accessToken.")
        return cci.toTokens()
    }

    private fun exchangeCcsToken(
        config: RegionConfig,
        deviceId: String,
        cci: CciTokens,
        http: OkHttpClient,
        diag: (String) -> Unit,
    ): Pair<String, Long> {
        val url = "${cciDomainApi(config)}v1/auth/token-exchange?serviceType=CCS"
        val req = Request.Builder().url(url)
            .apply {
                cciHeaders(
                    config,
                    deviceId,
                    cciAccessToken = cci.accessToken,
                    nonCcsToken = cci.nonCcsToken,
                    exchangeableToken = cci.exchangeableToken,
                ).forEach { (k, v) -> header(k, v) }
            }
            .post(EMPTY_BODY)
            .build()
        val body = http.newCall(req).execute().use { r ->
            diag("5/5 CCS token exchange → HTTP ${r.code}")
            val text = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
            if (!r.isSuccessful) throw LoginException("CCS token exchange failed (HTTP ${r.code}). ${text.take(160)}")
            text
        }
        val data = json.decodeFromString<CcsExchangeResponse>(body)
        val ccs = (data.accessToken ?: data.ccsAccessToken)?.removePrefix("Bearer ")?.trim()
        if (ccs.isNullOrBlank()) throw LoginException("CCS token exchange returned no accessToken.")
        // `expiresTime` is the CCS token TTL in SECONDS (e.g. 86400 = 24h), not an epoch — treat it
        // as a duration from now (per hyundai_kia_connect_api). Fall back to +1h when absent.
        val expiry = data.expiresTime?.let { System.currentTimeMillis() + it * 1000L }
            ?: (System.currentTimeMillis() + 3600_000L)
        return ccs to expiry
    }

    private fun refreshCci(
        config: RegionConfig,
        deviceId: String,
        current: CciTokens,
        http: OkHttpClient,
        diag: (String) -> Unit,
    ): CciTokens {
        val url = "${cciDomainApi(config)}v2/auth/token-refresh"
        val payload = JSONObject()
            .put("accessToken", current.accessToken.removePrefix("Bearer "))
            .put("refreshToken", current.refreshToken)
            .put("exchangeableAccessToken", current.exchangeableToken)
            .put("exchangeableRefreshToken", current.exchangeableRefreshToken)
            .put("nonCcsToken", current.nonCcsToken)
            .put("nonCcsRefreshToken", current.nonCcsRefreshToken)
            .put("idToken", current.idToken)
            .toString()
        val req = Request.Builder().url(url)
            .apply {
                cciHeaders(
                    config,
                    deviceId,
                    cciAccessToken = current.accessToken,
                    nonCcsToken = current.nonCcsToken,
                    exchangeableToken = current.exchangeableToken,
                ).forEach { (k, v) -> header(k, v) }
            }
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        return http.newCall(req).execute().use { r ->
            diag("refresh: CCI token-refresh → HTTP ${r.code}")
            val text = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
            if (!r.isSuccessful) throw LoginException("CCI token refresh failed (HTTP ${r.code}). ${text.take(160)}")
            val data = json.decodeFromString<CciTokenResponse>(text).toTokens(fallback = current)
            // The refresh response's set-cookie t= may carry an updated exchangeable token.
            val setCookie = r.headers("set-cookie").firstOrNull { it.startsWith("t=") }
            val exchangeable = setCookie?.let { Regex("t=([^;]+)").find(it)?.groupValues?.get(1) }
                ?.takeIf { it.isNotBlank() } ?: data.exchangeableToken
            data.copy(exchangeableToken = exchangeable)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    private fun cciDomainApi(config: RegionConfig): String {
        val base = config.cciApiBaseUrl ?: throw LoginException("Missing CCI API URL.")
        return "$base/domain/api/"
    }

    /** App-identity + auth headers the CCI API expects. */
    private fun cciHeaders(
        config: RegionConfig,
        deviceId: String,
        cciAccessToken: String? = null,
        nonCcsToken: String? = null,
        exchangeableToken: String? = null,
    ): Map<String, String> {
        val headers = linkedMapOf(
            "client-id" to (config.cciPackageId ?: ""),
            "client-name" to (config.cciClientName ?: ""),
            "client-version" to config.cciClientVersion,
            "client-os-code" to "ios",
            "client-os-version" to config.cciClientOsVersion,
            "client-device-id" to deviceId,
            "client-device-model" to "iPhone",
            "client-notification-provider-type" to config.cciNotificationProvider,
            "locale" to "EN",
            "timezone" to timezoneOffset(),
            "Accept" to "application/json",
            "Accept-Language" to "en",
            "User-Agent" to OKHTTP_UA,
        )
        if (nonCcsToken != null) headers["Authentication"] = nonCcsToken
        if (cciAccessToken != null) {
            headers["authorization"] = "Bearer " + cciAccessToken.removePrefix("Bearer ").trim()
        }
        if (exchangeableToken != null) {
            headers["exchangeable-token"] = exchangeableToken
            headers["non-ccs-token"] = nonCcsToken ?: ""
        }
        return headers
    }

    private fun timezoneOffset(): String {
        val offsetMin = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (offsetMin >= 0) "+" else "-"
        return "%s%02d:%02d".format(sign, abs(offsetMin) / 60, abs(offsetMin) % 60)
    }

    private fun get(url: String, ua: String): Request =
        Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", ACCEPT_HTML)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

    private fun classifyBounce(location: String): String {
        val query = location.substringAfter('?', "")
        return when {
            location.contains("/web/v1/user/authorization") ->
                "Account consent is required. Open the official Hyundai app once to accept the terms, then retry."
            query.contains("error", ignoreCase = true) -> {
                val desc = EuAuth.queryParam(location, "error_description")
                    ?: EuAuth.queryParam(location, "error") ?: "unknown"
                "Sign-in rejected: $desc. Check your email and password."
            }
            location.contains("authorize") || location.contains("signin") || location.contains("login") ->
                "Sign-in bounced back to login — usually a wrong email/password. If you recently " +
                    "changed them, open the official Hyundai app once, then retry here."
            else -> "No authorization code in the redirect (see the log)."
        }
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun CciTokenResponse.toTokens(fallback: CciTokens? = null) = CciTokens(
        accessToken = accessToken.ifBlank { fallback?.accessToken ?: "" },
        refreshToken = refreshToken.ifBlank { fallback?.refreshToken ?: "" },
        nonCcsToken = nonCcsToken.ifBlank { fallback?.nonCcsToken ?: "" },
        nonCcsRefreshToken = nonCcsRefreshToken.ifBlank { fallback?.nonCcsRefreshToken ?: "" },
        exchangeableToken = exchangeableAccessToken.ifBlank { fallback?.exchangeableToken ?: "" },
        exchangeableRefreshToken = exchangeableRefreshToken.ifBlank { fallback?.exchangeableRefreshToken ?: "" },
        idToken = idToken.ifBlank { fallback?.idToken ?: "" },
    )

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}
