package com.i5autolock.data.bluelink.eu

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.i5autolock.data.bluelink.RegionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * EU IDP login driven through a real Chromium [WebView] instead of raw OkHttp.
 *
 * Hyundai's IDP sits behind Akamai bot protection that classifies OkHttp's TLS/HTTP2
 * fingerprint as an "abusing request" and blocks the HMGID2 connector hop — even though the
 * credentials are correct. A WebView uses Chrome's genuine fingerprint and native cookie
 * store, so the identical flow (authorize → certs → RSA-encrypt password → signin → follow the
 * connector redirect → token) sails through. The whole thing is still fully headless/automatic:
 * we drive it with injected `fetch()` calls and an auto-submitting form, no user typing.
 *
 * Everything here is same-origin to `idpconnect-eu.hyundai.com`, so cookies flow automatically.
 */
class EuWebLogin(private val appContext: Context) {

    /** Infra failure (WebView missing / timed out) — caller may fall back to the raw path. */
    class WebUnavailable(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val main = Handler(Looper.getMainLooper())

    private enum class Phase { AUTHORIZE, CERTS, SIGNIN, TOKEN, DONE }

    suspend fun login(
        config: RegionConfig,
        username: String,
        password: String,
        diag: (String) -> Unit,
    ): EuIdpAuth.Tokens = withContext(Dispatchers.Main) {
        val idp = config.idpBaseUrl ?: throw EuIdpAuth.LoginException("Missing IDP base URL.")
        val redirect = config.idpRedirectUri ?: throw EuIdpAuth.LoginException("Missing IDP redirect.")

        suspendCancellableCoroutine { cont ->
            var settled = false
            var phase = Phase.AUTHORIZE
            var jwkKid = ""
            lateinit var web: WebView

            fun finish(block: () -> Unit) {
                if (settled) return
                settled = true
                main.removeCallbacksAndMessages(null)
                runCatching {
                    web.stopLoading()
                    web.webViewClient = WebViewClient()
                    web.destroy()
                }
                block()
            }

            fun fail(message: String) = finish { cont.resumeWithException(EuIdpAuth.LoginException(message)) }
            fun succeed(tokens: EuIdpAuth.Tokens) = finish { cont.resume(tokens) }

            // Overall watchdog — Akamai/JS hangs shouldn't leave the coroutine stuck.
            main.postDelayed({ fail("Web login timed out.") }, 90_000)

            val authorizeUrl = "$idp/auth/api/v2/user/oauth2/authorize?response_type=code" +
                "&client_id=${config.clientId}&redirect_uri=${enc(redirect)}&lang=de&state=ccsp&country=de"

            val bridge = object {
                @JavascriptInterface
                fun result(tag: String, body: String) = main.post {
                    if (settled) return@post
                    when (tag) {
                        "certs" -> onCerts(body)
                        "token" -> onToken(body)
                    }
                }

                @JavascriptInterface
                fun error(tag: String, msg: String) = main.post {
                    if (settled) return@post
                    fail("Web $tag call failed: ${msg.take(120)}")
                }

                fun onCerts(body: String) {
                    val jwk = runCatching { json.decodeFromString<CertsEnvelope>(body).retValue }.getOrNull()
                        ?: return fail("Login key missing from response.")
                    jwkKid = jwk.kid
                    diag("2/4 RSA key loaded (kid=${jwk.kid.take(8)}…)")
                    val encryptedPw = runCatching { EuAuth.encryptPassword(jwk.n, jwk.e, password) }
                        .getOrElse { return fail("Couldn't encrypt password: ${it.message}") }
                    phase = Phase.SIGNIN
                    diag("3/4 signin (web) submitting…")
                    web.loadDataWithBaseURL(
                        idp,
                        signinFormHtml(config, redirect, username.trim(), encryptedPw, jwk.kid),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }

                fun onToken(body: String) {
                    val token = runCatching { json.decodeFromString<TokenResponse>(body) }.getOrNull()
                    if (token == null || token.accessToken.isBlank()) {
                        diag("4/4 token body: ${body.take(160).ifBlank { "(empty)" }}")
                        return fail("Token exchange failed.")
                    }
                    diag("4/4 token exchange (web) → ok")
                    succeed(EuIdpAuth.Tokens(token.accessToken, token.refreshToken.orEmpty(), token.expiresIn))
                }
            }

            // Returns true if the URL was a terminal one (code captured or error) and was handled.
            fun handleNavigation(url: String): Boolean {
                if (!url.startsWith("http")) return false
                if (url.startsWith(redirect) || (url.contains("code=") && url.contains("eu-ccapi"))) {
                    diag("3/4 ↳ ${sanitize(url, redirect)}")
                    val code = EuAuth.extractAuthCode(url)
                    if (code == null) { fail("No authorization code in the redirect."); return true }
                    diag("3/4 authorization code received ✓")
                    web.stopLoading()
                    phase = Phase.TOKEN
                    // Bounce to a blank same-origin (idp) doc so the token fetch carries cookies.
                    web.loadDataWithBaseURL(idp, "<html><body></body></html>", "text/html", "utf-8", null)
                    startTokenFetch(web, config, redirect, code)
                    return true
                }
                if (url.contains("/error")) {
                    diag("3/4 ↳ ${sanitize(url, redirect)}")
                    val desc = EuAuth.queryParam(url, "error_description")
                        ?: EuAuth.queryParam(url, "error") ?: "Bad Request"
                    fail("Sign-in rejected: $desc")
                    return true
                }
                if (phase == Phase.SIGNIN) diag("3/4 ↳ ${sanitize(url, redirect)}")
                return false
            }

            @SuppressLint("SetJavaScriptEnabled")
            fun buildWebView(): WebView = WebView(appContext).apply {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                with(settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    @Suppress("DEPRECATION")
                    savePassword = false
                    saveFormData = false
                    allowFileAccess = false
                    allowContentAccess = false
                }
                addJavascriptInterface(bridge, "AndroidBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        return handleNavigation(url)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        if (url != null) handleNavigation(url)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (settled) return
                        when (phase) {
                            Phase.AUTHORIZE -> {
                                if (url != null && url.startsWith("http")) {
                                    diag("1/4 authorize (web) → loaded")
                                    phase = Phase.CERTS
                                    startCertsFetch(view ?: return)
                                }
                            }
                            Phase.TOKEN -> Unit // token fetch already kicked off in handleNavigation
                            else -> Unit
                        }
                    }
                }
            }

            web = runCatching { buildWebView() }
                .getOrElse { cont.resumeWithException(WebUnavailable("WebView unavailable: ${it.message}")); return@suspendCancellableCoroutine }

            cont.invokeOnCancellation { main.post { finish {} } }

            diag("Login (web): opening IDP…")
            web.loadUrl(authorizeUrl)
        }
    }

    private fun startCertsFetch(web: WebView) {
        val js = """
            (function(){
              fetch('/auth/api/v1/accounts/certs', {credentials:'same-origin', headers:{'Accept':'application/json'}})
                .then(function(r){return r.text();})
                .then(function(t){AndroidBridge.result('certs', t);})
                .catch(function(e){AndroidBridge.error('certs', String(e));});
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun startTokenFetch(web: WebView, config: RegionConfig, redirect: String, code: String) {
        val secret = config.clientSecret
        val js = buildString {
            append("(function(){var b=new URLSearchParams();")
            append("b.set('grant_type','authorization_code');")
            append("b.set('code',").append(jsStr(code)).append(");")
            append("b.set('redirect_uri',").append(jsStr(redirect)).append(");")
            append("b.set('client_id',").append(jsStr(config.clientId)).append(");")
            if (secret != null) append("b.set('client_secret',").append(jsStr(secret)).append(");")
            append("fetch('/auth/api/v2/user/oauth2/token',{method:'POST',credentials:'same-origin',")
            append("headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b.toString()})")
            append(".then(function(r){return r.text();})")
            append(".then(function(t){AndroidBridge.result('token', t);})")
            append(".catch(function(e){AndroidBridge.error('token', String(e));});})();")
        }
        // Give the blank doc a beat to commit before running the same-origin fetch.
        main.postDelayed({ runCatching { web.evaluateJavascript(js, null) } }, 200)
    }

    private fun signinFormHtml(
        config: RegionConfig,
        redirect: String,
        username: String,
        encryptedPassword: String,
        kid: String,
    ): String {
        fun input(name: String, value: String) =
            "<input type=\"hidden\" name=\"${htmlAttr(name)}\" value=\"${htmlAttr(value)}\">"
        val fields = buildString {
            append(input("client_id", config.clientId))
            append(input("encryptedPassword", "true"))
            append(input("password", encryptedPassword))
            append(input("redirect_uri", redirect))
            append(input("scope", ""))
            append(input("nonce", ""))
            append(input("state", "ccsp"))
            append(input("username", username))
            append(input("connector_session_key", ""))
            append(input("kid", kid))
            append(input("_csrf", ""))
        }
        return "<html><body>" +
            "<form id=\"f\" method=\"post\" action=\"${htmlAttr(config.idpBaseUrl + "/auth/account/signin")}\">" +
            fields +
            "</form><script>document.getElementById('f').submit();</script></body></html>"
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun jsStr(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "") + "\""

    private fun htmlAttr(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun sanitize(location: String, redirect: String): String =
        location.replace(Regex("code=[^&]+"), "code=***").take(300)
}
