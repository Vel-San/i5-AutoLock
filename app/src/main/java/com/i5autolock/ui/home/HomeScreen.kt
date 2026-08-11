package com.i5autolock.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.i5autolock.data.bluelink.model.LockState
import com.i5autolock.data.bluelink.model.VehicleStatus
import com.i5autolock.data.settings.RunMode
import com.i5autolock.domain.LogEntry
import com.i5autolock.domain.LogLevel
import com.i5autolock.domain.detection.DetectionState
import com.i5autolock.ui.theme.ParametricPixels
import com.i5autolock.ui.theme.brandGradient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val autoLock by viewModel.autoLock.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val vehicleStatus by viewModel.vehicleStatus.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoLock") },
                actions = {
                    IconButton(onClick = onOpenHelp) {
                        Icon(Icons.Default.Info, contentDescription = "Help")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vehicleStatus.loading,
            onRefresh = { viewModel.refreshStatus(force = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                item {
                    StatusCard(
                        enabled = settings.enabled,
                        demoMode = settings.demoMode,
                        dryRun = settings.runMode == RunMode.DRY_RUN,
                        detection = autoLock.detection,
                        graceRemaining = autoLock.graceRemaining,
                        vehicleName = settings.vehicleNickname,
                        onToggle = viewModel::setEnabled,
                    )
                }
                item {
                    VehicleStatusCard(
                        ui = vehicleStatus,
                        parkedLabel = settings.parkedLabel.takeIf { settings.rememberParkedLocation },
                        onRefresh = { viewModel.refreshStatus(force = true) },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = viewModel::runNow,
                            modifier = Modifier.weight(1f),
                            enabled = settings.isConfigured || settings.demoMode,
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Text("  Simulate leaving")
                        }
                        OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                    }
                }
                item { Text("Recent activity", style = MaterialTheme.typography.titleMedium) }
                if (log.isEmpty()) {
                    item {
                        Text(
                            "No activity yet. Enable AutoLock and try \"Simulate leaving\".",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    items(log, key = { it.timestamp }) { entry ->
                        LogRow(entry, Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleStatusCard(
    ui: VehicleStatusUi,
    parkedLabel: String?,
    onRefresh: () -> Unit,
) {
    val status = ui.status
    val badgeColor by animateColorAsState(
        targetValue = when (status?.lockState) {
            LockState.LOCKED -> MaterialTheme.colorScheme.primary
            LockState.UNLOCKED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        },
        label = "badge",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = badgeColor.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Vehicle status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    ParametricPixels(Modifier.size(40.dp, 24.dp), color = badgeColor)
                }
                RefreshButton(loading = ui.loading, onRefresh = onRefresh)
            }

            AnimatedContent(targetState = status?.lockState ?: LockState.UNKNOWN, label = "lock") { lockState ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(badgeColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (lockState == LockState.LOCKED) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    Column {
                        Text(
                            when (lockState) {
                                LockState.LOCKED -> "Locked"
                                LockState.UNLOCKED -> "Unlocked"
                                LockState.UNKNOWN -> "Unknown"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                        )
                        Text(
                            text = ui.lastRefreshEpochMs?.let { "Updated ${relativeTime(it)}" }
                                ?: if (ui.loading) "Refreshing…" else "Not checked yet",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            ui.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (status != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    status.evBatteryPercent?.let {
                        StatChip(Icons.Default.BatteryChargingFull, "$it%", "Drive", MaterialTheme.colorScheme.primary)
                    }
                    status.rangeKm?.let {
                        StatChip(Icons.Default.Bolt, "$it km", "Range", MaterialTheme.colorScheme.secondary)
                    }
                    status.twelveVoltPercent?.let {
                        StatChip(Icons.Default.BatteryStd, "$it%", "12V", MaterialTheme.colorScheme.tertiary)
                    }
                    StatChip(
                        icon = Icons.Default.Bolt,
                        value = if (status.engineRunning) "On" else "Off",
                        label = "Engine",
                        tint = if (status.engineRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    )
                }
                status.anyDoorOpen?.takeIf { it }?.let {
                    Text("A door appears to be open.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            parkedLabel?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text("Parked near $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StatChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    tint: Color,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = tint.copy(alpha = 0.14f),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
            Column {
                Text(
                    value,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun RefreshButton(loading: Boolean, onRefresh: () -> Unit) {
    if (loading) {
        val transition = rememberInfiniteTransition(label = "spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "angle",
        )
        Icon(
            Icons.Default.Refresh,
            contentDescription = "Refreshing",
            modifier = Modifier.rotate(angle).size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    } else {
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh status")
        }
    }
}

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}

@Composable
private fun StatusCard(
    enabled: Boolean,
    demoMode: Boolean,
    dryRun: Boolean,
    detection: DetectionState,
    graceRemaining: Int,
    vehicleName: String?,
    onToggle: (Boolean) -> Unit,
) {
    // Vibrant gradient when actively watching; calm surface when off.
    val watching = enabled && detection != DetectionState.IDLE
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    brush = if (enabled) brandGradient()
                    else Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ),
                ),
        ) {
            val onGradient = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface
            // Ioniq 5 parametric-pixel accent in the corner.
            ParametricPixels(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(64.dp, 40.dp),
                color = onGradient.copy(alpha = 0.35f),
            )
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (watching) PulsingDot(onGradient)
                        Text(
                            text = if (enabled) "Watching" else "Off",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = onGradient,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = onToggle)
                }
                Text(
                    vehicleName ?: "No vehicle selected",
                    style = MaterialTheme.typography.bodyLarge,
                    color = onGradient.copy(alpha = 0.9f),
                )

                AnimatedContent(targetState = detection, label = "detection") { state ->
                    val label = when (state) {
                        DetectionState.IDLE -> if (enabled) "Idle — waiting for you to leave the car." else "Disabled."
                        DetectionState.ARMED -> "Armed."
                        DetectionState.CONFIRMING -> "Confirming you left the car…"
                        DetectionState.GRACE -> "Locking in ${graceRemaining}s…"
                        DetectionState.VERIFYING -> "Checking vehicle status…"
                        DetectionState.LOCKING -> "Locking…"
                        DetectionState.LOCKED -> "Locked ✓"
                        DetectionState.SKIPPED -> "Already secure — nothing to do."
                        DetectionState.ABORTED -> "Cancelled."
                        DetectionState.ERROR -> "Something went wrong."
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = onGradient.copy(alpha = 0.9f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (demoMode) Badge("DEMO", onGradient)
                    Badge(if (dryRun) "DRY RUN" else "ARMED", onGradient)
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, contentColor: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(contentColor.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = contentColor)
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

@Composable
private fun LogRow(entry: LogEntry, modifier: Modifier = Modifier) {
    val time = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
    }
    val color = when (entry.level) {
        LogLevel.SUCCESS -> MaterialTheme.colorScheme.primary
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(time, style = MaterialTheme.typography.labelMedium)
        Text(entry.message, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
