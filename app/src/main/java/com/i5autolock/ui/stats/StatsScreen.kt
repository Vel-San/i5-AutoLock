package com.i5autolock.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.i5autolock.R
import com.i5autolock.data.metrics.ApiCall
import com.i5autolock.data.metrics.ApiOutcome
import com.i5autolock.domain.LogEntry
import com.i5autolock.domain.LogLevel
import com.i5autolock.ui.theme.ambientBackground
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
    // Captured in composable scope so they can be used inside click lambdas.
    val clearCallsTitle = stringResource(R.string.stats_clear_calls_title)
    val clearCallsMsg = stringResource(R.string.stats_clear_calls_msg)
    val clearLogTitle = stringResource(R.string.stats_clear_log_title)
    val clearLogMsg = stringResource(R.string.stats_clear_log_msg)

    confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(pending.title) },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(onClick = { pending.action(); confirm = null }) { Text(stringResource(R.string.stats_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(ambientBackground()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
            Section(stringResource(R.string.stats_sec_session)) {
                KeyValue(stringResource(R.string.stats_region), settings.region.displayName)
                KeyValue(stringResource(R.string.stats_mode), if (settings.demoMode) stringResource(R.string.stats_mode_demo) else stringResource(R.string.stats_mode_live))
                KeyValue(stringResource(R.string.stats_command_mode), settings.runMode.name)
                KeyValue(stringResource(R.string.stats_account), settings.accountEmail ?: stringResource(R.string.set_not_signed_in))
                KeyValue(stringResource(R.string.stats_vehicle), settings.vehicleNickname ?: stringResource(R.string.stats_none_selected))
                if (!settings.demoMode) {
                    // Refresh over minute-ish ticks so the countdown stays live.
                    var now by remember { mutableStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) {
                        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) }
                    }
                    val expiry = remember(now) { viewModel.sessionExpiresAtEpochMs() }
                    KeyValue(
                        stringResource(R.string.stats_session_expiry),
                        if (expiry == null) stringResource(R.string.stats_session_none)
                        else sessionExpiryText(expiry, now),
                    )
                }
            }

            // Rate limiting.
            Section(stringResource(R.string.stats_sec_rate)) {
                if (snapshot.isRateLimited()) {
                    val secs = ((snapshot.rateLimitedUntilEpochMs!! - System.currentTimeMillis()) / 1000)
                        .coerceAtLeast(0)
                    Text(
                        stringResource(R.string.stats_rate_limited, secs.toInt()),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(stringResource(R.string.stats_not_rate_limited), color = MaterialTheme.colorScheme.primary)
                }
                KeyValue(stringResource(R.string.stats_rate_hits), snapshot.rateLimitedCount.toString())
            }

            // Aggregate metrics.
            Section(stringResource(R.string.stats_sec_totals)) {
                KeyValue(stringResource(R.string.stats_total_calls), snapshot.totalCalls.toString())
                val pct = (snapshot.successRate * 100).roundToInt()
                KeyValue(stringResource(R.string.stats_success_rate), "$pct%")
                LinearProgressIndicator(
                    progress = { snapshot.successRate },
                    modifier = Modifier.fillMaxWidth(),
                )
                KeyValue(stringResource(R.string.stats_successes), snapshot.successCount.toString())
                KeyValue(stringResource(R.string.stats_failures), snapshot.failureCount.toString())
                KeyValue(stringResource(R.string.stats_auth_failures), snapshot.unauthenticatedCount.toString())
                KeyValue(stringResource(R.string.stats_avg_duration), "${snapshot.avgDurationMs} ms")
                snapshot.lastCall?.let {
                    KeyValue(stringResource(R.string.stats_last_call), "${it.operation} · ${it.durationMs} ms · ${it.outcome}")
                }
            }

            // Recent API calls.
            SectionWithAction(stringResource(R.string.stats_sec_recent), stringResource(R.string.stats_clear), {
                confirm = ConfirmAction(clearCallsTitle, clearCallsMsg, viewModel::clearMetrics)
            }) {
                if (snapshot.calls.isEmpty()) {
                    Text(stringResource(R.string.stats_no_calls), style = MaterialTheme.typography.bodyMedium)
                } else {
                    snapshot.calls.take(50).forEach { ApiCallRow(it) }
                }
            }

            // Activity log.
            SectionWithAction(stringResource(R.string.stats_sec_log), stringResource(R.string.stats_clear), {
                confirm = ConfirmAction(clearLogTitle, clearLogMsg, viewModel::clearLog)
            }) {
                if (log.isEmpty()) {
                    Text(stringResource(R.string.stats_no_activity), style = MaterialTheme.typography.bodyMedium)
                } else {
                    log.take(50).forEach { LogRow(it) }
                }
            }
        }
    }
}

@Composable
private fun sessionExpiryText(expiryEpochMs: Long, now: Long): String {
    val diff = expiryEpochMs - now
    val clock = remember(expiryEpochMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(expiryEpochMs))
    }
    return if (diff <= 0) {
        stringResource(R.string.stats_session_expired, clock)
    } else {
        val mins = diff / 60_000
        val rel = if (mins >= 60) stringResource(R.string.stats_session_in_h, mins / 60, mins % 60)
        else stringResource(R.string.stats_session_in_m, mins.coerceAtLeast(1))
        stringResource(R.string.stats_session_valid, rel, clock)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {    Card(Modifier.fillMaxWidth()) {
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
