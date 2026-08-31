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
    /** CCSP service id sent as `ccsp-service-id` on every ccapi:8080 request (the legacy id). */
    val clientId: String,
    /** Base64 "cfb" seed used to derive the anti-tamper Stamp (EU). */
    val stampCfb: String?,
    val appId: String?,
    // IDP (idpconnect) host for the OneApp/CCI login.
    val idpBaseUrl: String? = null,
    /** Mobile UA the OneApp login sends to the IDP authorize/signin. */
    val mobileUserAgent: String = MOBILE_UA,
    // ── OneApp / CCI login (EU Hyundai/Kia) ──────────────────────────────────────────────
    // Since 2026-08-11 Hyundai's WAF blocks the legacy login client_id ([clientId] with the
    // :8080 redirect) — "classified as an abusing request and blocked". The OneApp client_id
    // is NOT on that block list, so login now runs through it: authorize with [oneAppClientId]
    // → sign in → exchange the code for CCI tokens → exchange those for a CCS token that the
    // existing ccapi:8080 vehicle/control endpoints still accept. See hyundai_kia_connect_api#1277.
    val oneAppClientId: String? = null,
    val oneAppRedirectUri: String? = null,
    /** CCI API host (e.g. https://cci-api-eu.hyundai.com), used for the token exchanges. */
    val cciApiBaseUrl: String? = null,
    /** App-identity headers the CCI API expects. */
    val cciPackageId: String? = null,
    val cciClientName: String? = null,
    val cciClientVersion: String = "1.3.3",
    val cciClientOsVersion: String = "18.7",
    val cciNotificationProvider: String = "APNS",
) {
    companion object {
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 4.1.1; Galaxy Nexus Build/JRO03C) AppleWebKit/535.19 " +
                "(KHTML, like Gecko) Chrome/18.0.1025.166 Mobile Safari/535.19_CCS_APP_AOS"

        // EU/Hyundai config. Values verified against BlueDeck + bluelink-refresh-token +
        // hyundai_kia_connect_api (#1277).
        fun euHyundai(): RegionConfig = RegionConfig(
            region = Region.EU,
            brand = Brand.HYUNDAI,
            apiBaseUrl = "https://prd.eu-ccapi.hyundai.com:8080",
            clientId = "6d477c38-3ca4-4cf3-9557-2a1929a94654",
            stampCfb = "RFtoRq/vDXJmRndoZaZQyfOot7OrIqGVFj96iY2WL3yyH5Z/pUvlUhqmCxD2t+D65SQ=",
            appId = "014d2225-8495-4735-812d-2616334fd15d",
            idpBaseUrl = "https://idpconnect-eu.hyundai.com",
            // OneApp/CCI login (WAF bypass). These are public reverse-engineered app constants.
            oneAppClientId = "4f4953b5-02e1-4dbc-8599-87e983ee1be5",
            oneAppRedirectUri = "https://oneapp.hyundai.com/redirect",
            cciApiBaseUrl = "https://cci-api-eu.hyundai.com",
            cciPackageId = "com.hyundai.oneapp.eu",
            cciClientName = "hyundai",
            cciClientVersion = "1.3.3",
            cciClientOsVersion = "18.7",
            cciNotificationProvider = "APNS",
        )
    }
}
