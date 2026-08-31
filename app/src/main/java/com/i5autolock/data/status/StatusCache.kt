package com.i5autolock.data.status

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.i5autolock.data.bluelink.model.LockState
import com.i5autolock.data.bluelink.model.VehicleStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.statusDataStore: DataStore<Preferences> by preferencesDataStore(name = "autolock_status_cache")

/**
 * Last-known vehicle status, persisted so the home screen and widget render the previous state
 * instantly on open (no blanks) and stay in sync in real time. Stores the full status so the
 * detailed info (battery/range/engine/12V/doors) survives app restarts.
 */
data class CachedStatus(
    val lockState: String = "UNKNOWN",
    val summary: String = "",
    val updatedAtEpochMs: Long = 0L,
    val hasStatus: Boolean = false,
    val engineRunning: Boolean = false,
    val evBatteryPercent: Int? = null,
    val rangeKm: Int? = null,
    val twelveVoltPercent: Int? = null,
    val anyDoorOpen: Boolean? = null,
    val climateOn: Boolean? = null,
    val batteryCharging: Boolean? = null,
    /** When the car was last confirmed LOCKED (0 = never), for the widget's "locked X ago". */
    val lastLockedAtEpochMs: Long = 0L,
) {
    /** Reconstruct a [VehicleStatus] from the cache, or null if nothing has been stored yet. */
    fun toVehicleStatus(): VehicleStatus? {
        if (!hasStatus) return null
        return VehicleStatus(
            lockState = runCatching { LockState.valueOf(lockState) }.getOrDefault(LockState.UNKNOWN),
            engineRunning = engineRunning,
            batteryCharging = batteryCharging,
            timestamp = updatedAtEpochMs,
            evBatteryPercent = evBatteryPercent,
            rangeKm = rangeKm,
            climateOn = climateOn,
            twelveVoltPercent = twelveVoltPercent,
            anyDoorOpen = anyDoorOpen,
        )
    }
}

@Singleton
class StatusCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val LOCK = stringPreferencesKey("lock_state")
        val SUMMARY = stringPreferencesKey("summary")
        val UPDATED = longPreferencesKey("updated_at")
        val HAS = booleanPreferencesKey("has_status")
        val ENGINE = booleanPreferencesKey("engine")
        val EV = intPreferencesKey("ev_battery")
        val RANGE = intPreferencesKey("range_km")
        val TWELVE = intPreferencesKey("twelve_volt")
        val DOOR = booleanPreferencesKey("any_door_open")
        val CLIMATE = booleanPreferencesKey("climate_on")
        val CHARGING = booleanPreferencesKey("battery_charging")
        val LAST_LOCKED = longPreferencesKey("last_locked_at")
    }

    val cached: Flow<CachedStatus> = context.statusDataStore.data.map {
        CachedStatus(
            lockState = it[Keys.LOCK] ?: "UNKNOWN",
            summary = it[Keys.SUMMARY] ?: "",
            updatedAtEpochMs = it[Keys.UPDATED] ?: 0L,
            hasStatus = it[Keys.HAS] ?: false,
            engineRunning = it[Keys.ENGINE] ?: false,
            evBatteryPercent = it[Keys.EV],
            rangeKm = it[Keys.RANGE],
            twelveVoltPercent = it[Keys.TWELVE],
            anyDoorOpen = it[Keys.DOOR],
            climateOn = it[Keys.CLIMATE],
            batteryCharging = it[Keys.CHARGING],
            lastLockedAtEpochMs = it[Keys.LAST_LOCKED] ?: 0L,
        )
    }

    /** Save just the lock state + summary (used when a full status isn't available). */
    suspend fun save(lockState: String, summary: String) {
        context.statusDataStore.edit {
            it[Keys.LOCK] = lockState
            it[Keys.SUMMARY] = summary
            it[Keys.UPDATED] = System.currentTimeMillis()
        }
        com.i5autolock.widget.AutoLockWidget.refresh(context)
    }

    /** Save the full vehicle status so detailed info persists across restarts. */
    suspend fun saveStatus(status: VehicleStatus, summary: String) {
        context.statusDataStore.edit {
            it[Keys.LOCK] = status.lockState.name
            it[Keys.SUMMARY] = summary
            it[Keys.UPDATED] = System.currentTimeMillis()
            it[Keys.HAS] = true
            it[Keys.ENGINE] = status.engineRunning
            status.evBatteryPercent?.let { v -> it[Keys.EV] = v } ?: it.remove(Keys.EV)
            status.rangeKm?.let { v -> it[Keys.RANGE] = v } ?: it.remove(Keys.RANGE)
            status.twelveVoltPercent?.let { v -> it[Keys.TWELVE] = v } ?: it.remove(Keys.TWELVE)
            status.anyDoorOpen?.let { v -> it[Keys.DOOR] = v } ?: it.remove(Keys.DOOR)
            status.climateOn?.let { v -> it[Keys.CLIMATE] = v } ?: it.remove(Keys.CLIMATE)
            status.batteryCharging?.let { v -> it[Keys.CHARGING] = v } ?: it.remove(Keys.CHARGING)
            if (status.lockState == LockState.LOCKED) it[Keys.LAST_LOCKED] = System.currentTimeMillis()
        }
        // Redraw any placed home-screen widgets.
        com.i5autolock.widget.AutoLockWidget.refresh(context)
    }
}
