package com.i5autolock.data.status

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.statusDataStore: DataStore<Preferences> by preferencesDataStore(name = "autolock_status_cache")

/** Last-known lock state + summary, cached so the home-screen widget can render instantly. */
data class CachedStatus(
    val lockState: String = "UNKNOWN",
    val summary: String = "",
    val updatedAtEpochMs: Long = 0L,
)

@Singleton
class StatusCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val LOCK = stringPreferencesKey("lock_state")
        val SUMMARY = stringPreferencesKey("summary")
        val UPDATED = longPreferencesKey("updated_at")
    }

    val cached: Flow<CachedStatus> = context.statusDataStore.data.map {
        CachedStatus(
            lockState = it[Keys.LOCK] ?: "UNKNOWN",
            summary = it[Keys.SUMMARY] ?: "",
            updatedAtEpochMs = it[Keys.UPDATED] ?: 0L,
        )
    }

    suspend fun save(lockState: String, summary: String) {
        context.statusDataStore.edit {
            it[Keys.LOCK] = lockState
            it[Keys.SUMMARY] = summary
            it[Keys.UPDATED] = System.currentTimeMillis()
        }
        // Redraw any placed home-screen widgets.
        com.i5autolock.widget.AutoLockWidget.refresh(context)
    }
}
