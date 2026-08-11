package com.i5autolock.data.bluelink.eu

import android.util.Base64
import com.i5autolock.data.bluelink.RegionConfig

/**
 * EU-specific auth primitives (ported from `hyundai_kia_connect_api` / `bluelinky`).
 *
 * The EU backend rejects requests without a valid "Stamp" header, which is derived by
 * XOR-ing a per-app base64 seed (cfb) with "<appId>:<epochSeconds>".
 *
 * NOTE: Endpoint shapes and parameters must be verified against the reference projects;
 * they are consolidated here so future fixes touch a single file.
 */
object EuAuth {

    /** Anti-tamper header expected by EU CCS endpoints. */
    fun generateStamp(config: RegionConfig, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val cfb = config.stampCfb ?: return ""
        val appId = config.appId ?: return ""
        val seed = Base64.decode(cfb, Base64.DEFAULT)
        val raw = "$appId:$nowSeconds".toByteArray(Charsets.UTF_8)
        val out = ByteArray(minOf(seed.size, raw.size))
        for (i in out.indices) out[i] = (seed[i].toInt() xor raw[i].toInt()).toByte()
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * CCSP browser-flow authorize URL for the visible WebView. Matches bluelinky exactly —
     * CCSP renders the actual IDP login form and, after signin, 302s to [RegionConfig.redirectUri]
     * with `?code=…`. Loading the IDP mobile URL directly in a browser returns 400 (that URL is a
     * machine-only endpoint for headless clients), which is why we go through CCSP here.
     */
    fun buildAuthorizeUrl(config: RegionConfig): String {
        val base = "${config.apiBaseUrl}/api/v1/user/oauth2/authorize"
        val params = listOf(
            "response_type" to "code",
            "state" to "test",
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
        ).joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        return "$base?$params"
    }

    /** Extract the authorization code from an intercepted redirect URL, if present. */
    fun extractAuthCode(redirectedUrl: String): String? {
        val q = redirectedUrl.substringAfter('?', "")
        if (q.isEmpty()) return null
        return q.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "code" }
            ?.get(1)
    }

    /**
     * Lenient extractor for a value the user pasted from an external browser. Accepts a full
     * redirect URL, a raw query string, `code=...`, or a bare code token.
     */
    fun extractAuthCodeLoose(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        extractAuthCode(trimmed)?.let { return it.trim() }
        if (trimmed.contains("code=")) {
            val after = trimmed.substringAfter("code=")
            return after.substringBefore('&').trim().ifBlank { null }
        }
        // A pasted bare code shouldn't contain spaces or URL punctuation.
        return if (trimmed.none { it.isWhitespace() } && !trimmed.contains('/')) trimmed else null
    }

    /** Reads a single query parameter value from a URL, or null. */
    fun queryParam(url: String, name: String): String? {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return null
        return q.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /**
     * RSA-encrypts the password (PKCS#1 v1.5) with the IDP's JWK public key and returns hex,
     * matching the official app / bluelink-refresh-token headless flow.
     */
    fun encryptPassword(nB64Url: String, eB64Url: String, password: String): String {
        val decoder = java.util.Base64.getUrlDecoder()
        val modulus = java.math.BigInteger(1, decoder.decode(nB64Url))
        val exponent = java.math.BigInteger(1, decoder.decode(eB64Url))
        val spec = java.security.spec.RSAPublicKeySpec(modulus, exponent)
        val publicKey = java.security.KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        // Mask to a byte before hex, otherwise negative bytes sign-extend to 8 hex chars.
        return encrypted.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
