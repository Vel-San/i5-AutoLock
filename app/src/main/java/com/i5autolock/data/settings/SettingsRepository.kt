package com.i5autolock.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.i5autolock.data.bluelink.Region
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "autolock_settings")

/** Reads/writes [AppSettings]. All non-secret; tokens are stored separately in SecureStore. */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val RUN_MODE = stringPreferencesKey("run_mode")
        val REGION = stringPreferencesKey("region")
        val DEMO = booleanPreferencesKey("demo_mode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SHOW_STATUS_NOTIF = booleanPreferencesKey("show_status_notif")
        val NOTIF_FIELDS = stringSetPreferencesKey("notif_fields")
        val SHOW_LOCK_NOW = booleanPreferencesKey("show_lock_now")
        val PIN_NOTIF = booleanPreferencesKey("pin_notif")
        val AUTO_REFRESH_OPEN = booleanPreferencesKey("auto_refresh_open")
        val AUTO_REFRESH_INTERVAL = intPreferencesKey("auto_refresh_interval")
        val HAPTIC_ON_LOCK = booleanPreferencesKey("haptic_on_lock")
        val SOUND_ON_LOCK = booleanPreferencesKey("sound_on_lock")
        val REMEMBER_PARKED = booleanPreferencesKey("remember_parked")
        val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
        val SCHEDULE_START = intPreferencesKey("schedule_start")
        val SCHEDULE_END = intPreferencesKey("schedule_end")
        val PARKED_LABEL = stringPreferencesKey("parked_label")
        val PARKED_LAT = doublePreferencesKey("parked_lat")
        val PARKED_LNG = doublePreferencesKey("parked_lng")
        val GRACE = intPreferencesKey("grace_seconds")
        val USE_BT = booleanPreferencesKey("use_bt")
        val USE_AR = booleanPreferencesKey("use_ar")
        val USE_GEO = booleanPreferencesKey("use_geo")
        val GEO_RADIUS = intPreferencesKey("geo_radius")
        val CAR_BT_MAC = stringPreferencesKey("car_bt_mac")
        val CAR_BT_NAME = stringPreferencesKey("car_bt_name")
        val VEHICLE_ID = stringPreferencesKey("vehicle_id")
        val VEHICLE_NICK = stringPreferencesKey("vehicle_nick")
        val KNOWN_VEHICLES = stringSetPreferencesKey("known_vehicles")
        val ACCOUNT_EMAIL = stringPreferencesKey("account_email")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val REQUIRE_CONFIRM = booleanPreferencesKey("require_confirm")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        enabled = this[Keys.ENABLED] ?: false,
        runMode = runCatching { RunMode.valueOf(this[Keys.RUN_MODE] ?: "") }.getOrDefault(RunMode.DRY_RUN),
        region = Region.fromNameOrDefault(this[Keys.REGION]),
        demoMode = this[Keys.DEMO] ?: false,
        themeMode = runCatching { ThemeMode.valueOf(this[Keys.THEME_MODE] ?: "") }.getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
        showStatusInNotification = this[Keys.SHOW_STATUS_NOTIF] ?: true,
        notificationFields = this[Keys.NOTIF_FIELDS]
            ?.mapNotNull { runCatching { NotificationField.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?: AppSettings().notificationFields,
        showLockNowAction = this[Keys.SHOW_LOCK_NOW] ?: true,
        pinNotification = this[Keys.PIN_NOTIF] ?: true,
        autoRefreshOnOpen = this[Keys.AUTO_REFRESH_OPEN] ?: true,
        autoRefreshIntervalMinutes = this[Keys.AUTO_REFRESH_INTERVAL] ?: 0,
        hapticOnLock = this[Keys.HAPTIC_ON_LOCK] ?: true,
        soundOnLock = this[Keys.SOUND_ON_LOCK] ?: false,
        rememberParkedLocation = this[Keys.REMEMBER_PARKED] ?: false,
        scheduleEnabled = this[Keys.SCHEDULE_ENABLED] ?: false,
        scheduleStartMinutes = this[Keys.SCHEDULE_START] ?: (7 * 60),
        scheduleEndMinutes = this[Keys.SCHEDULE_END] ?: (22 * 60),
        parkedLabel = this[Keys.PARKED_LABEL],
        parkedLat = this[Keys.PARKED_LAT],
        parkedLng = this[Keys.PARKED_LNG],
        graceSeconds = this[Keys.GRACE] ?: 45,
        useBluetoothTrigger = this[Keys.USE_BT] ?: true,
        useActivityRecognition = this[Keys.USE_AR] ?: true,
        useGeofence = this[Keys.USE_GEO] ?: false,
        geofenceRadiusMeters = this[Keys.GEO_RADIUS] ?: 25,
        carBluetoothMac = this[Keys.CAR_BT_MAC],
        carBluetoothName = this[Keys.CAR_BT_NAME],
        vehicleId = this[Keys.VEHICLE_ID],
        vehicleNickname = this[Keys.VEHICLE_NICK],
        knownVehicles = this[Keys.KNOWN_VEHICLES]
            ?.mapNotNull { entry ->
                entry.split('\u001F').takeIf { it.size >= 3 }?.let { KnownVehicle(it[0], it[1], it[2]) }
            }
            ?: emptyList(),
        accountEmail = this[Keys.ACCOUNT_EMAIL],
        onboardingComplete = this[Keys.ONBOARDING_DONE] ?: false,
        requireConfirmationBeforeLock = this[Keys.REQUIRE_CONFIRM] ?: false,
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.ENABLED] = next.enabled
            prefs[Keys.RUN_MODE] = next.runMode.name
            prefs[Keys.REGION] = next.region.name
            prefs[Keys.DEMO] = next.demoMode
            prefs[Keys.THEME_MODE] = next.themeMode.name
            prefs[Keys.DYNAMIC_COLOR] = next.dynamicColor
            prefs[Keys.SHOW_STATUS_NOTIF] = next.showStatusInNotification
            prefs[Keys.NOTIF_FIELDS] = next.notificationFields.map { it.name }.toSet()
            prefs[Keys.SHOW_LOCK_NOW] = next.showLockNowAction
            prefs[Keys.PIN_NOTIF] = next.pinNotification
            prefs[Keys.AUTO_REFRESH_OPEN] = next.autoRefreshOnOpen
            prefs[Keys.AUTO_REFRESH_INTERVAL] = next.autoRefreshIntervalMinutes
            prefs[Keys.HAPTIC_ON_LOCK] = next.hapticOnLock
            prefs[Keys.SOUND_ON_LOCK] = next.soundOnLock
            prefs[Keys.REMEMBER_PARKED] = next.rememberParkedLocation
            prefs[Keys.SCHEDULE_ENABLED] = next.scheduleEnabled
            prefs[Keys.SCHEDULE_START] = next.scheduleStartMinutes
            prefs[Keys.SCHEDULE_END] = next.scheduleEndMinutes
            next.parkedLabel?.let { prefs[Keys.PARKED_LABEL] = it } ?: prefs.remove(Keys.PARKED_LABEL)
            next.parkedLat?.let { prefs[Keys.PARKED_LAT] = it } ?: prefs.remove(Keys.PARKED_LAT)
            next.parkedLng?.let { prefs[Keys.PARKED_LNG] = it } ?: prefs.remove(Keys.PARKED_LNG)
            prefs[Keys.GRACE] = next.graceSeconds
            prefs[Keys.USE_BT] = next.useBluetoothTrigger
            prefs[Keys.USE_AR] = next.useActivityRecognition
            prefs[Keys.USE_GEO] = next.useGeofence
            prefs[Keys.GEO_RADIUS] = next.geofenceRadiusMeters
            next.carBluetoothMac?.let { prefs[Keys.CAR_BT_MAC] = it } ?: prefs.remove(Keys.CAR_BT_MAC)
            next.carBluetoothName?.let { prefs[Keys.CAR_BT_NAME] = it } ?: prefs.remove(Keys.CAR_BT_NAME)
            next.vehicleId?.let { prefs[Keys.VEHICLE_ID] = it } ?: prefs.remove(Keys.VEHICLE_ID)
            next.vehicleNickname?.let { prefs[Keys.VEHICLE_NICK] = it } ?: prefs.remove(Keys.VEHICLE_NICK)
            prefs[Keys.KNOWN_VEHICLES] = next.knownVehicles
                .map { "${it.id}\u001F${it.nickname}\u001F${it.model}" }
                .toSet()
            next.accountEmail?.let { prefs[Keys.ACCOUNT_EMAIL] = it } ?: prefs.remove(Keys.ACCOUNT_EMAIL)
            prefs[Keys.ONBOARDING_DONE] = next.onboardingComplete
            prefs[Keys.REQUIRE_CONFIRM] = next.requireConfirmationBeforeLock
        }
    }
}
