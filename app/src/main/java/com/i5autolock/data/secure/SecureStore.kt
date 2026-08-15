package com.i5autolock.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** OAuth / session tokens. Never logged, never backed up, always encrypted at rest. */
@Serializable
data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresAtEpochMs: Long,
    val deviceId: String? = null,
    val controlToken: String? = null,
    val controlTokenExpiresAtEpochMs: Long = 0L,
    // EU OneApp/CCI login: [accessToken] is a CCS token exchanged from the CCI access token,
    // [refreshToken] is the CCI refresh token. These extra CCI fields are persisted so the
    // session can be refreshed (cci-api token-refresh + CCS re-exchange) without a full login.
    val cciAccessToken: String? = null,
    val exchangeableToken: String? = null,
    val exchangeableRefreshToken: String? = null,
    val nonCcsToken: String? = null,
    val nonCcsRefreshToken: String? = null,
    val idToken: String? = null,
) {
    val isAccessExpired: Boolean get() = System.currentTimeMillis() >= (expiresAtEpochMs - 60_000)
}

/**
 * Encrypted storage backed by the Android Keystore. Holds BlueLink session tokens.
 */
@Singleton
class SecureStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences by lazy { openPrefs() }

    private fun buildMasterKey(): MasterKey {
        val builder = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        // Prefer hardware-backed StrongBox when the device has a secure element.
        return runCatching {
            builder.setRequestStrongBoxBacked(true).build()
        }.getOrElse {
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            buildMasterKey(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    /**
     * Opens the encrypted store. If the keystore entry or file is corrupted (e.g. after a
     * restore or key rotation), we wipe and recreate rather than crash — the user simply
     * re-authenticates. No plaintext fallback is ever used.
     */
    private fun openPrefs(): SharedPreferences = try {
        createEncryptedPrefs()
    } catch (_: Exception) {
        context.deleteSharedPreferences(PREFS_FILE)
        runCatching { File(context.filesDir.parent, "shared_prefs/$PREFS_FILE.xml").delete() }
        createEncryptedPrefs()
    }

    fun saveTokens(tokens: SessionTokens) {
        prefs.edit().putString(KEY_TOKENS, json.encodeToString(SessionTokens.serializer(), tokens)).apply()
    }

    fun loadTokens(): SessionTokens? {
        val raw = prefs.getString(KEY_TOKENS, null) ?: return null
        return runCatching { json.decodeFromString(SessionTokens.serializer(), raw) }.getOrNull()
    }

    /** CCSP device id registered with the EU backend (required for status/control calls). */
    fun saveDeviceId(deviceId: String) = prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    fun loadDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)
    fun clearDeviceId() = prefs.edit().remove(KEY_DEVICE_ID).apply()

    /** BlueLink service PIN, needed to obtain an EU control token for lock/unlock. */
    fun savePin(pin: String) = prefs.edit().putString(KEY_PIN, pin).apply()
    fun loadPin(): String? = prefs.getString(KEY_PIN, null)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_FILE = "autolock_secure"
        const val KEY_TOKENS = "session_tokens"
        const val KEY_DEVICE_ID = "eu_device_id"
        const val KEY_PIN = "service_pin"
    }
}
