package com.i5autolock.data.bluelink

/**
 * Region + brand specific endpoint configuration.
 *
 * IMPORTANT: These values are reverse-engineered from the open-source projects
 * `bluelinky` (TS) and `hyundai_kia_connect_api` (Python). They change over time.
 * Verify against those references before shipping and centralise all changes here.
 */
data class RegionConfig(
    val region: Region,
    val brand: Brand,
    val apiBaseUrl: String,
    val userApiBaseUrl: String,
    val clientId: String,
    val clientSecret: String?,
    /** Base64 "cfb" seed used to derive the anti-tamper Stamp (EU). */
    val stampCfb: String?,
    val appId: String?,
    /** Pre-computed HTTP Basic auth header value ("Basic base64(clientId:secret)") for token calls. */
    val basicAuth: String? = null,
    /** Redirect the OAuth flow lands on; we intercept this. */
    val redirectUri: String,
    /** Custom scheme deep link the app registers to catch the redirect. */
    val appRedirectScheme: String = "i5autolock://oauth",
    // IDP (idpconnect) — headless email+password login that avoids reCAPTCHA.
    val idpBaseUrl: String? = null,
    /** redirect_uri used across the IDP authorize/signin/token calls. */
    val idpRedirectUri: String? = null,
    /** Mobile UA the official app sends to the IDP. */
    val mobileUserAgent: String = MOBILE_UA,
) {
    companion object {
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 4.1.1; Galaxy Nexus Build/JRO03C) AppleWebKit/535.19 " +
                "(KHTML, like Gecko) Chrome/18.0.1025.166 Mobile Safari/535.19_CCS_APP_AOS"

        // EU/Hyundai config. Values verified against BlueDeck + bluelink-refresh-token.
        fun euHyundai(): RegionConfig = RegionConfig(
            region = Region.EU,
            brand = Brand.HYUNDAI,
            apiBaseUrl = "https://prd.eu-ccapi.hyundai.com:8080",
            userApiBaseUrl = "https://prd.eu-ccapi.hyundai.com:8080/api/v1/user",
            clientId = "6d477c38-3ca4-4cf3-9557-2a1929a94654",
            clientSecret = "KUy49XxPzLpLuoK0xhBC77W6VXhmtQR9iQhmIFjjoY4IpxsV",
            stampCfb = "RFtoRq/vDXJmRndoZaZQyfOot7OrIqGVFj96iY2WL3yyH5Z/pUvlUhqmCxD2t+D65SQ=",
            appId = "014d2225-8495-4735-812d-2616334fd15d",
            basicAuth = "Basic NmQ0NzdjMzgtM2NhNC00Y2YzLTk1NTctMmExOTI5YTk0NjU0OktVeTQ5WHhQekxwTHVvSzB4aEJDNzdXNlZYaG10UVI5aVFobUlGampvWTRJcHhzVg==",
            redirectUri = "https://prd.eu-ccapi.hyundai.com:8080/api/v1/user/oauth2/redirect",
            idpBaseUrl = "https://idpconnect-eu.hyundai.com",
            idpRedirectUri = "https://prd.eu-ccapi.hyundai.com:8080/api/v1/user/oauth2/token",
        )

        // ─────────────────────────────────────────────────────────────────────────────────────
        // FUTURE: Hyundai "OneApp" (new myHyundai app) config. Extracted from the OneApp config
        // JSON — NOT wired up. OneApp is a distinct backend from the classic CCS API above:
        // different client_id, different API domain, different OAuth scope + redirect. The IDP
        // is shared (idpconnect-eu.hyundai.com). Ported partial only; a real migration needs
        // captured vehicle/control request shapes from OneApp traffic. Track upstream community
        // projects (bluelinky, hyundai_kia_connect_api) before rewriting.
        //
        //   client_id      = 4f4953b5-02e1-4dbc-8599-87e983ee1be5
        //   api domain     = https://cci-api-eu.hyundai.com   (prod)
        //                    https://pilot-cci-api-eu.hyundai.com  (staging)
        //   redirect_uri   = https://oneapp.hyundai.com/redirect
        //   scope          = account.token.transfer account.id.generate
        //                    account.puid.userinfos account.userinfo read
        //                    account.userinfos puid email name mobileNum birthdate
        //                    lang country signUpDate gender nationInfo certProfile offline
        //   authorize URL  = https://idpconnect-eu.hyundai.com/auth/api/v2/user/oauth2/authorize
        //   logout URL     = https://idpconnect-eu.hyundai.com/auth/api/v1/accounts/signout
        //   ccs enrol URL  = https://prd.eu-ccapi.hyundai.com:8080/api/v1/profile/cci/vehicles
        //
        // NOT captured (needs a real device capture):
        //   - client_secret (if OAuth grant is confidential; may be PKCE-only)
        //   - stamp cfb (if OneApp still uses the CCS Stamp header)
        //   - vehicle / status / control endpoint paths on cci-api-eu.hyundai.com
        //   - request/response shapes for status + door control
        //
        // Only add euHyundaiOneApp() once we have all of the above verified.
        // ─────────────────────────────────────────────────────────────────────────────────────
    }
}
