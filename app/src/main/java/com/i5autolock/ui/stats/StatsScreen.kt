package com.i5autolock.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.i5autolock.data.metrics.ApiCall
import com.i5autolock.data.metrics.ApiOutcome
import com.i5autolock.domain.LogEntry
import com.i5autolock.domain.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private data class ConfirmAction(val title: String, val message: String, val action: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Pending confirmation for destructive "Clear" actions.
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }

    confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(pending.title) },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(onClick = { pending.action(); confirm = null }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API statistics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Session / connection.
            Section("Session") {
                KeyValue("Region", settings.region.displayName)
                KeyValue("Mode", if (settings.demoMode) "Demo (simulated)" else "Live")
                KeyValue("Command mode", settings.runMode.name)
                KeyValue("Account", settings.accountEmail ?: "Not signed in")
                KeyValue("Vehicle", settings.vehicleNickname ?: "None selected")
            }

            // Rate limiting.
            Section("Rate limiting") {
                if (snapshot.isRateLimited()) {
                    val secs = ((snapshot.rateLimitedUntilEpochMs!! - System.currentTimeMillis()) / 1000)
                        .coerceAtLeast(0)
                    Text(
                        "⚠ Rate-limited — cooldown ~${secs}s remaining",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text("✓ Not rate-limited", color = MaterialTheme.colorScheme.primary)
                }
                KeyValue("Rate-limit hits", snapshot.rateLimitedCount.toString())
            }

            // Aggregate metrics.
            Section("Totals") {
                KeyValue("Total API calls", snapshot.totalCalls.toString())
                val pct = (snapshot.successRate * 100).roundToInt()
                KeyValue("Success rate", "$pct%")
                LinearProgressIndicator(
                    progress = { snapshot.successRate },
                    modifier = Modifier.fillMaxWidth(),
                )
                KeyValue("Successes", snapshot.successCount.toString())
                KeyValue("Failures", snapshot.failureCount.toString())
                KeyValue("Auth failures", snapshot.unauthenticatedCount.toString())
                KeyValue("Avg duration", "${snapshot.avgDurationMs} ms")
                snapshot.lastCall?.let {
                    KeyValue("Last call", "${it.operation} · ${it.durationMs} ms · ${it.outcome}")
                }
            }

            // Recent API calls.
            SectionWithAction("Recent API calls", "Clear", {
                confirm = ConfirmAction("Clear API calls?", "This removes the recorded call history.", viewModel::clearMetrics)
            }) {
                if (snapshot.calls.isEmpty()) {
                    Text("No API calls yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    snapshot.calls.take(50).forEach { ApiCallRow(it) }
                }
            }

            // Activity log.
            SectionWithAction("Activity log", "Clear", {
                confirm = ConfirmAction("Clear activity log?", "This removes the recent activity entries.", viewModel::clearLog)
            }) {
                if (log.isEmpty()) {
                    Text("No activity yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    log.take(50).forEach { LogRow(it) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SectionWithAction(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ApiCallRow(call: ApiCall) {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(call.timestamp))
    val color = when (call.outcome) {
        ApiOutcome.SUCCESS -> MaterialTheme.colorScheme.primary
        ApiOutcome.RATE_LIMITED -> MaterialTheme.colorScheme.tertiary
        ApiOutcome.UNAUTHENTICATED -> MaterialTheme.colorScheme.tertiary
        ApiOutcome.FAILURE -> MaterialTheme.colorScheme.error
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(time, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        Text(call.operation, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text("${call.durationMs}ms", style = MaterialTheme.typography.labelMedium)
        Text(call.outcome.name, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
    val color = when (entry.level) {
        LogLevel.SUCCESS -> MaterialTheme.colorScheme.primary
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(time, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        Text(entry.message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
