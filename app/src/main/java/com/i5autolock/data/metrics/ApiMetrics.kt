package com.i5autolock.data.metrics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a single API call. */
@Serializable
enum class ApiOutcome { SUCCESS, FAILURE, RATE_LIMITED, UNAUTHENTICATED }

/** One recorded API call. Contains NO tokens, credentials, or PII. */
@Serializable
data class ApiCall(
    val timestamp: Long,
    val operation: String,
    val durationMs: Long,
    val outcome: ApiOutcome,
    val detail: String? = null,
)

/** Aggregated, ready-to-display metrics snapshot. */
@Serializable
data class MetricsSnapshot(
    val calls: List<ApiCall> = emptyList(),
    val totalCalls: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val rateLimitedCount: Int = 0,
    val unauthenticatedCount: Int = 0,
    val avgDurationMs: Long = 0,
    val lastCall: ApiCall? = null,
    val rateLimitedUntilEpochMs: Long? = null,
) {
    val successRate: Float
        get() = if (totalCalls == 0) 0f else successCount.toFloat() / totalCalls

    fun isRateLimited(now: Long = System.currentTimeMillis()): Boolean =
        rateLimitedUntilEpochMs?.let { it > now } == true
}

private val Context.metricsDataStore: DataStore<Preferences> by preferencesDataStore(name = "api_metrics")

/**
 * API telemetry surfaced on the Statistics screen, persisted so it survives app restarts.
 * Privacy-first: records only operation name, timing, and outcome — never tokens, headers,
 * VINs, or account data.
 */
@Singleton
class ApiMetrics @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _snapshot = MutableStateFlow(MetricsSnapshot())
    val snapshot: StateFlow<MetricsSnapshot> = _snapshot

    init {
        // Restore the previously persisted snapshot on startup.
        scope.launch {
            val stored = context.metricsDataStore.data.first()[KEY] ?: return@launch
            runCatching { json.decodeFromString<MetricsSnapshot>(stored) }.getOrNull()?.let { restored ->
                if (_snapshot.value.totalCalls == 0) _snapshot.value = restored
            }
        }
    }

    fun record(
        operation: String,
        durationMs: Long,
        outcome: ApiOutcome,
        detail: String? = null,
    ) {
        val call = ApiCall(System.currentTimeMillis(), operation, durationMs, outcome, detail)
        _snapshot.update { prev ->
            val calls = (listOf(call) + prev.calls).take(MAX_CALLS)
            val total = prev.totalCalls + 1
            val successes = prev.successCount + if (outcome == ApiOutcome.SUCCESS) 1 else 0
            val failures = prev.failureCount + if (outcome == ApiOutcome.FAILURE) 1 else 0
            val rateLimited = prev.rateLimitedCount + if (outcome == ApiOutcome.RATE_LIMITED) 1 else 0
            val unauth = prev.unauthenticatedCount + if (outcome == ApiOutcome.UNAUTHENTICATED) 1 else 0
            val avg = ((prev.avgDurationMs * prev.totalCalls) + durationMs) / total
            prev.copy(
                calls = calls,
                totalCalls = total,
                successCount = successes,
                failureCount = failures,
                rateLimitedCount = rateLimited,
                unauthenticatedCount = unauth,
                avgDurationMs = avg,
                lastCall = call,
                rateLimitedUntilEpochMs = if (outcome == ApiOutcome.RATE_LIMITED) {
                    System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                } else {
                    prev.rateLimitedUntilEpochMs
                },
            )
        }
        persist()
    }

    fun clear() {
        _snapshot.update { MetricsSnapshot() }
        persist()
    }

    private fun persist() {
        val current = _snapshot.value
        scope.launch {
            runCatching {
                context.metricsDataStore.edit { it[KEY] = json.encodeToString(current) }
            }
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("snapshot")
        const val MAX_CALLS = 120
        // Conservative estimate; BlueLink throttles remote commands for a few minutes.
        const val RATE_LIMIT_COOLDOWN_MS = 5 * 60_000L
    }
}
