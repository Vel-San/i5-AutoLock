package com.i5autolock.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.i5autolock.data.metrics.ApiMetrics
import com.i5autolock.data.metrics.MetricsSnapshot
import com.i5autolock.data.secure.SecureStore
import com.i5autolock.data.settings.AppSettings
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.domain.ActivityLog
import com.i5autolock.domain.LogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val metrics: ApiMetrics,
    private val activityLog: ActivityLog,
    private val secureStore: SecureStore,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    val snapshot: StateFlow<MetricsSnapshot> = metrics.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MetricsSnapshot())

    val log: StateFlow<List<LogEntry>> = activityLog.entries

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** When the current access token (session) expires, or null if not signed in. */
    fun sessionExpiresAtEpochMs(): Long? = secureStore.loadTokens()?.expiresAtEpochMs?.takeIf { it > 0 }

    fun clearMetrics() = metrics.clear()
    fun clearLog() = activityLog.clear()
}
