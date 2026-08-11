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
import java.util.concurrent.TimeUnit

private const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

/**
 * Headless EU IDP login on a raw OkHttp client with a shared cookie jar and manual redirect
 * control — mirrors how `bluelink-refresh-token` / BlueDeck keep the session between the
 * authorize GET and the /auth/account/signin POST. Ktor's cookie handling across redirect hops
 * was dropping the session cookie, which made the IDP bounce sign-in back to the login page.
 */
class EuIdpAuth {

    data class Tokens(val accessToken: String, val refreshToken: String, val expiresIn: Long)

    class LoginException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // One cookie jar shared across the whole login so the session survives every hop.
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

        fun names(host: String): String = store[host].orEmpty().joinToString(",") { it.name }.ifBlank { "none" }
    }

    suspend fun login(
        config: RegionConfig,
        username: String,
        password: String,
        diag: (String) -> Unit,
    ): Tokens = withContext(Dispatchers.IO) {
        val idp = config.idpBaseUrl ?: throw LoginException("Missing IDP base URL.")
        val redirect = config.idpRedirectUri ?: throw LoginException("Missing IDP redirect.")
        val ua = config.mobileUserAgent

        val jar = SharedCookieJar()
        val following = OkHttpClient.Builder()
            .cookieJar(jar)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val noRedirect = following.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val idpHost = idp.toHttpUrlHost()

        // 1. Authorize — establishes the session cookies.
        val authorizeUrl = "$idp/auth/api/v2/user/oauth2/authorize?response_type=code" +
            "&client_id=${config.clientId}&redirect_uri=${enc(redirect)}&lang=de&state=ccsp&country=de"
        following.newCall(get(authorizeUrl, ua)).execute().use { r ->
            diag("1/4 authorize → HTTP ${r.code}, cookies: ${jar.names(idpHost)}")
        }

        // 2. Certs — RSA public key; encrypt the password.
        val certsBody = following.newCall(get("$idp/auth/api/v1/accounts/certs", ua)).execute().use { r ->
            diag("2/4 certs → HTTP ${r.code}")
            if (!r.isSuccessful) throw LoginException("Couldn't load login key (HTTP ${r.code}).")
            r.body?.string().orEmpty()
        }
        val jwk = runCatching { json.decodeFromString<CertsEnvelope>(certsBody).retValue }.getOrNull()
            ?: throw LoginException("Login key missing from response.")
        diag("2/4 RSA key loaded (kid=${jwk.kid.take(8)}…)")
        val encryptedPw = EuAuth.encryptPassword(jwk.n, jwk.e, password)

        // 3. Sign in — code comes back in the 302; cookies carry the session.
        val signinForm = FormBody.Builder()
            .add("client_id", config.clientId)
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
        var location = noRedirect.newCall(signinReq).execute().use { r ->
            diag("3/4 signin → HTTP ${r.code}, cookies sent: ${jar.names(idpHost)}")
            if (r.code !in listOf(301, 302, 303, 307, 308)) {
                val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("").take(200)
                diag("3/4 signin body: ${body.ifBlank { "(empty)" }}")
                throw LoginException("Sign-in failed (HTTP ${r.code}). Check email & password.")
            }
            r.header("Location") ?: throw LoginException("Sign-in returned no redirect location.")
        }

        // HMGID2 signs in then 302s through the OAuth "connector" (authorize) before returning
        // the code. Follow the redirect chain until it lands on our redirect_uri with ?code=.
        var code: String? = null
        var referer = "$idp/auth/account/signin"
        var hops = 0
        while (code == null && hops < 8) {
            diag("3/4 ↳ ${sanitize(location)}")
            if (location.startsWith(redirect)) {
                code = EuAuth.extractAuthCode(location)
                    ?: throw LoginException(classifyBounce(location))
                break
            }
            val absolute = if (location.startsWith("http")) location else "$idp$location"
            // Real navigations carry these; without them Akamai flags the hop as "abusing".
            val hopReq = Request.Builder().url(absolute)
                .header("User-Agent", ua)
                .header("Accept", ACCEPT_HTML)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", referer)
                .get()
                .build()
            val next = noRedirect.newCall(hopReq).execute().use { r ->
                if (r.code !in listOf(301, 302, 303, 307, 308)) {
                    // Landed on a real page (e.g. the login form) — sign-in didn't take.
                    val onCodeUrl = EuAuth.extractAuthCode(r.request.url.toString())
                    if (onCodeUrl != null) return@use "code://$onCodeUrl"
                    throw LoginException(classifyBounce(absolute))
                }
                r.header("Location") ?: throw LoginException("Redirect chain ended unexpectedly.")
            }
            if (next.startsWith("code://")) {
                code = next.removePrefix("code://")
                break
            }
            referer = absolute
            location = next
            hops++
        }
        if (code == null) throw LoginException("Couldn't obtain an authorization code (after $hops redirects).")
        diag("3/4 authorization code received ✓")

        // 4. Exchange code for tokens.
        val tokenForm = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirect)
            .add("client_id", config.clientId)
            .apply { config.clientSecret?.let { add("client_secret", it) } }
            .build()
        val tokenReq = Request.Builder().url("$idp/auth/api/v2/user/oauth2/token")
            .header("User-Agent", ua)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(tokenForm)
            .build()
        val tokenBody = following.newCall(tokenReq).execute().use { r ->
            diag("4/4 token exchange → HTTP ${r.code}")
            if (!r.isSuccessful) {
                val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("").take(200)
                diag("4/4 token body: ${body.ifBlank { "(empty)" }}")
                throw LoginException("Token exchange failed (HTTP ${r.code}).")
            }
            r.body?.string().orEmpty()
        }
        val token = json.decodeFromString<TokenResponse>(tokenBody)
        Tokens(token.accessToken, token.refreshToken.orEmpty(), token.expiresIn)
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
            query.contains("error", ignoreCase = true) -> {
                val desc = EuAuth.queryParam(location, "error_description")
                    ?: EuAuth.queryParam(location, "error") ?: "unknown"
                "Sign-in rejected: $desc"
            }
            location.contains("authorize") || location.contains("signin") || location.contains("login") ->
                "Sign-in bounced back to login. Usually a wrong email/password, or Hyundai needs a fresh login in the official app first."
            else -> "No authorization code in the redirect (see the log)."
        }
    }

    private fun sanitize(location: String): String =
        location.replace(Regex("code=[^&]+"), "code=***").take(400)

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun String.toHttpUrlHost(): String =
        runCatching { java.net.URI(this).host ?: this }.getOrDefault(this)
}
