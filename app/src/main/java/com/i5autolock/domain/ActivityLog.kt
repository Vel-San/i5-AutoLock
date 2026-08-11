package com.i5autolock.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class LogLevel { INFO, SUCCESS, WARN, ERROR }

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
)

/** In-memory ring buffer of recent activity, surfaced in the UI. */
@Singleton
class ActivityLog @Inject constructor() {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    fun add(level: LogLevel, message: String) {
        _entries.update { prev ->
            (listOf(LogEntry(System.currentTimeMillis(), level, message)) + prev).take(MAX)
        }
    }

    fun clear() = _entries.update { emptyList() }

    private companion object { const val MAX = 100 }
}
