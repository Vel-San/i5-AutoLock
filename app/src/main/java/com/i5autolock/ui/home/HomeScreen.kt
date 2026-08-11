package com.i5autolock.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning

import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.i5autolock.R
import com.i5autolock.data.bluelink.model.LockState
import com.i5autolock.data.settings.RunMode
import com.i5autolock.domain.LogEntry
import com.i5autolock.domain.LogLevel
import com.i5autolock.domain.detection.DetectionState
import com.i5autolock.ui.theme.ParametricPixels
import com.i5autolock.ui.theme.PixelBand
import com.i5autolock.ui.theme.PixelField
import com.i5autolock.ui.theme.ScanningPixelBand
import com.i5autolock.ui.theme.ambientBackground
import com.i5autolock.ui.theme.brandGradient
import com.i5autolock.ui.theme.heroGlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenLogin: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val autoLock by viewModel.autoLock.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val vehicleStatus by viewModel.vehicleStatus.collectAsStateWithLifecycle()
    val lockResult by viewModel.lockResult.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var showLockDialog by remember { mutableStateOf(false) }
    LaunchedEffect(lockResult) {
        lockResult?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearLockResult()
        }
    }
    LaunchedEffect(notice) {
        notice?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearNotice()
        }
    }
    if (showLockDialog) {
        LockNowDialog(
            requirePin = viewModel.hasPin(),
            onDismiss = { showLockDialog = false },
            onConfirm = { pin -> showLockDialog = false; viewModel.manualLock(pin) },
        )
    }

    Box(Modifier.fillMaxSize().background(ambientBackground())) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { BrandWordmark() },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    actions = {
                        IconButton(onClick = onOpenHelp) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.cd_help))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.cd_settings))
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
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
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
                    if (vehicleStatus.needsReauth) {
                        item { ReauthBanner(onSignIn = onOpenLogin) }
                    }
                    if (vehicleStatus.lowVoltage) {
                        item { LowVoltageBanner(percent = vehicleStatus.status?.twelveVoltPercent) }
                    }
                    if (settings.knownVehicles.size > 1) {
                        item {
                            VehicleSwitcher(
                                vehicles = settings.knownVehicles,
                                selectedId = settings.vehicleId,
                                onSelect = viewModel::selectVehicle,
                            )
                        }
                    }
                    item {
                        VehicleStatusCard(
                            ui = vehicleStatus,
                            parkedLabel = settings.parkedLabel.takeIf { settings.rememberParkedLocation },
                            parkedLat = settings.parkedLat.takeIf { settings.rememberParkedLocation },
                            parkedLng = settings.parkedLng.takeIf { settings.rememberParkedLocation },
                            onRefresh = { viewModel.refreshStatus(force = true) },
                        )
                    }
                    item {
                        // Simulating in Armed mode on a real account would send a real lock — only
                        // allow it in Demo or Dry run.
                        val armedLive = settings.runMode == RunMode.ARMED && !settings.demoMode
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = viewModel::runNow,
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    enabled = (settings.isConfigured || settings.demoMode) && !armedLive,
                                ) {
                                    Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (armedLive) stringResource(R.string.home_simulate_off) else stringResource(R.string.home_simulate),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                OutlinedButton(
                                    onClick = viewModel::cancel,
                                    modifier = Modifier.height(56.dp),
                                    shape = RoundedCornerShape(18.dp),
                                ) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            }
                            if (armedLive) {
                                Text(
                                    stringResource(R.string.home_simulate_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (settings.isConfigured || settings.demoMode) {
                                val alreadyLocked = vehicleStatus.status?.lockState == LockState.LOCKED
                                Button(
                                    onClick = { showLockDialog = true },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    enabled = !alreadyLocked,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (alreadyLocked) stringResource(R.string.action_already_locked) else stringResource(R.string.action_lock_now),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                    item { SectionHeader(stringResource(R.string.home_recent_activity)) }
                    item { ActivityLogCard(log) }
                }
            }
        }
    }
}

@Composable
private fun LockNowDialog(requirePin: Boolean, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var pin by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.home_lock_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (requirePin) stringResource(R.string.home_lock_dialog_pin)
                    else stringResource(R.string.home_lock_dialog_plain),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (requirePin) {
                    androidx.compose.material3.OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 8) pin = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.home_pin_label)) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pin.ifBlank { null }) },
                enabled = !requirePin || pin.length >= 4,
            ) { Text(stringResource(R.string.action_lock_now)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ReauthBanner(onSignIn: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                stringResource(R.string.home_reauth),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onSignIn) { Text(stringResource(R.string.action_sign_in)) }
        }
    }
}

@Composable
private fun LowVoltageBanner(percent: Int?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_low_volt_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    if (percent != null) stringResource(R.string.home_low_volt_body, percent)
                    else stringResource(R.string.home_low_volt_body_generic),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleSwitcher(
    vehicles: List<com.i5autolock.data.settings.KnownVehicle>,
    selectedId: String?,
    onSelect: (com.i5autolock.data.settings.KnownVehicle) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.home_vehicles))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vehicles.forEach { v ->
                androidx.compose.material3.FilterChip(
                    selected = v.id == selectedId,
                    onClick = { onSelect(v) },
                    label = { Text(v.nickname) },
                    leadingIcon = { Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun BrandWordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ParametricPixels(Modifier.size(30.dp, 19.dp), color = MaterialTheme.colorScheme.primary)
        Row {
            Text("Auto", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "Lock",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(width = 4.dp, height = 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Hero status card ────────────────────────────────────────────────
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
    // An evaluation is actively in flight vs. a settled outcome vs. resting.
    val active = detection == DetectionState.ARMED ||
        detection == DetectionState.CONFIRMING ||
        detection == DetectionState.GRACE ||
        detection == DetectionState.VERIFYING ||
        detection == DetectionState.AWAITING_CONFIRM ||
        detection == DetectionState.LOCKING
    val secured = detection == DetectionState.LOCKED || detection == DetectionState.SKIPPED
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = if (enabled) 10.dp else 2.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    brush = if (enabled) brandGradient()
                    else Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ),
                ),
        ) {
            val onGradient = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface
            // Subtle blurred pixel texture behind the content.
            PixelField(
                modifier = Modifier
                    .matchParentSize()
                    .blur(5.dp),
                color = onGradient.copy(alpha = 0.16f),
                active = enabled,
            )
            if (enabled) {
                Box(Modifier.fillMaxWidth().height(220.dp).background(heroGlow()))
            }
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.home_system_status),
                    style = MaterialTheme.typography.labelMedium,
                    color = onGradient.copy(alpha = 0.7f),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Heartbeat dot pulses whenever AutoLock is watching.
                        if (enabled) PulsingDot(onGradient)
                        Text(
                            text = if (enabled) stringResource(R.string.home_watching) else stringResource(R.string.home_off),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black,
                            color = onGradient,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color.White.copy(alpha = 0.35f),
                            checkedBorderColor = Color.White.copy(alpha = 0.6f),
                        ),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Default.DirectionsCar,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = onGradient.copy(alpha = 0.9f),
                    )
                    Text(
                        vehicleName ?: stringResource(R.string.home_no_vehicle),
                        style = MaterialTheme.typography.titleMedium,
                        color = onGradient.copy(alpha = 0.95f),
                    )
                }

                AnimatedContent(targetState = detection, label = "detection") { state ->
                    val label = when (state) {
                        DetectionState.IDLE -> if (enabled) stringResource(R.string.home_state_idle) else stringResource(R.string.home_state_disabled)
                        DetectionState.ARMED -> stringResource(R.string.home_state_armed)
                        DetectionState.CONFIRMING -> stringResource(R.string.home_state_confirming)
                        DetectionState.GRACE -> stringResource(R.string.home_state_grace, graceRemaining)
                        DetectionState.VERIFYING -> stringResource(R.string.home_state_verifying)
                        DetectionState.AWAITING_CONFIRM -> stringResource(R.string.home_state_awaiting)
                        DetectionState.LOCKING -> stringResource(R.string.home_state_locking)
                        DetectionState.LOCKED -> stringResource(R.string.home_state_locked)
                        DetectionState.SKIPPED -> stringResource(R.string.home_state_skipped)
                        DetectionState.ABORTED -> stringResource(R.string.home_state_aborted)
                        DetectionState.ERROR -> stringResource(R.string.home_state_error)
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = onGradient.copy(alpha = 0.85f))
                }

                // The pixel bar is meaningful, not just decorative:
                //  • scanning while an evaluation is in flight,
                //  • a solid "secured" bar once the car is locked/already safe,
                //  • a dim static bar when armed and simply waiting.
                when {
                    active -> ScanningPixelBand(
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = onGradient.copy(alpha = 0.9f),
                    )
                    secured -> PixelBand(
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = onGradient.copy(alpha = 0.95f),
                        cells = 16,
                        dim = false,
                    )
                    enabled -> PixelBand(
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = onGradient.copy(alpha = 0.35f),
                        cells = 16,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (demoMode) Badge(stringResource(R.string.badge_demo), onGradient)
                    Badge(if (dryRun) stringResource(R.string.badge_dry_run) else stringResource(R.string.badge_armed), onGradient)
                }
            }
        }
    }
}

// ── Vehicle status card (gradient hero, state-driven) ───────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleStatusCard(
    ui: VehicleStatusUi,
    parkedLabel: String?,
    parkedLat: Double?,
    parkedLng: Double?,
    onRefresh: () -> Unit,
) {
    val status = ui.status
    val lockState = status?.lockState ?: LockState.UNKNOWN
    val now = rememberNow()
    val context = LocalContext.current
    val gradient = when (lockState) {
        LockState.LOCKED -> Brush.linearGradient(listOf(Color(0xFF0E8575), Color(0xFF0A4E48)))
        LockState.UNLOCKED -> Brush.linearGradient(listOf(Color(0xFFC9503E), Color(0xFF7E2A20)))
        LockState.UNKNOWN -> Brush.linearGradient(listOf(Color(0xFF34413E), Color(0xFF1E2725)))
    }
    val onCard = Color.White

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Box(Modifier.fillMaxWidth().background(gradient)) {
            // Subtle blurred pixel texture behind the content.
            PixelField(
                modifier = Modifier
                    .matchParentSize()
                    .blur(5.dp),
                color = onCard.copy(alpha = 0.16f),
                active = lockState == LockState.LOCKED,
            )
            Box(Modifier.fillMaxWidth().height(180.dp).background(heroGlow()))
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.home_vehicle), style = MaterialTheme.typography.labelLarge, color = onCard.copy(alpha = 0.75f))
                    RefreshButton(loading = ui.loading, onRefresh = onRefresh, tint = onCard)
                }

                AnimatedContent(targetState = lockState, label = "lock") { state ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(onCard.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (state == LockState.LOCKED) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = onCard,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        Column {
                            Text(
                                when (state) {
                                    LockState.LOCKED -> stringResource(R.string.lock_locked)
                                    LockState.UNLOCKED -> stringResource(R.string.lock_unlocked)
                                    LockState.UNKNOWN -> stringResource(R.string.lock_unknown)
                                },
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                                color = onCard,
                            )
                            Text(
                                text = ui.lastRefreshEpochMs?.let { stringResource(R.string.home_updated, relativeTime(it, now, context)) }
                                    ?: if (ui.loading) stringResource(R.string.home_refreshing) else stringResource(R.string.home_not_checked),
                                style = MaterialTheme.typography.bodySmall,
                                color = onCard.copy(alpha = 0.75f),
                            )
                        }
                    }
                }

                ui.error?.let {
                    Text(it, color = onCard, style = MaterialTheme.typography.bodyMedium)
                }

                if (status != null) {
                    status.evBatteryPercent?.let { pct ->
                        BatteryBar(percent = pct, onCard = onCard)
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        status.rangeKm?.let {
                            HeroStatChip(Icons.Default.Route, stringResource(R.string.home_range_value, it), stringResource(R.string.home_range), onCard)
                        }
                        status.twelveVoltPercent?.let {
                            HeroStatChip(Icons.Default.BatteryStd, "$it%", stringResource(R.string.home_twelve_volt), onCard)
                        }
                        HeroStatChip(
                            icon = Icons.Default.Bolt,
                            value = if (status.engineRunning) stringResource(R.string.state_on) else stringResource(R.string.state_off),
                            label = stringResource(R.string.home_engine),
                            onCard = onCard,
                        )
                    }
                    status.anyDoorOpen?.takeIf { it }?.let {
                        Text(stringResource(R.string.home_door_open), color = onCard, style = MaterialTheme.typography.bodySmall)
                    }
                } else if (ui.loading) {
                    // First-ever load with nothing cached — show shimmering skeletons.
                    SkeletonBar(onCard)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repeat(3) { SkeletonChip(onCard) }
                    }
                }

                parkedLabel?.let {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { openParkedInMaps(context, parkedLat, parkedLng, it) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp), tint = onCard.copy(alpha = 0.9f))
                        Text(stringResource(R.string.home_parked_near, it), style = MaterialTheme.typography.bodySmall, color = onCard.copy(alpha = 0.9f))
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.cd_open_maps), modifier = Modifier.size(14.dp), tint = onCard.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun shimmerAlpha(): Float {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmerAlpha",
    )
    return a
}

@Composable
private fun SkeletonBar(onCard: Color) {
    val a = shimmerAlpha()
    Box(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(onCard.copy(alpha = a * 0.35f)),
    )
}

@Composable
private fun SkeletonChip(onCard: Color) {
    val a = shimmerAlpha()
    Box(
        Modifier
            .size(width = 92.dp, height = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(onCard.copy(alpha = a * 0.30f)),
    )
}

@Composable
private fun BatteryBar(percent: Int, onCard: Color) {
    val fraction by animateFloatAsState(targetValue = (percent / 100f).coerceIn(0f, 1f), label = "battery")
    val animatedPct by animateIntAsState(targetValue = percent, animationSpec = tween(700), label = "pct")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(18.dp), tint = onCard)
                Text(stringResource(R.string.home_drive_battery), style = MaterialTheme.typography.bodyMedium, color = onCard.copy(alpha = 0.85f))
            }
            Text("$animatedPct%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = onCard)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(onCard.copy(alpha = 0.22f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(onCard),
            )
        }
    }
}

@Composable
private fun HeroStatChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    onCard: Color,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(onCard.copy(alpha = 0.16f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = onCard)
        Column {
            AnimatedContent(
                targetState = value,
                transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(450)) },
                label = "chipValue",
            ) { v ->
                Text(
                    v,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = onCard,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = onCard.copy(alpha = 0.75f),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun RefreshButton(loading: Boolean, onRefresh: () -> Unit, tint: Color = MaterialTheme.colorScheme.primary) {
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
            contentDescription = stringResource(R.string.home_refreshing_cd),
            modifier = Modifier.rotate(angle).size(24.dp),
            tint = tint,
        )
    } else {
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.home_refresh_status), tint = tint)
        }
    }
}

// ── Activity log ────────────────────────────────────────────────────
@Composable
private fun ActivityLogCard(log: List<LogEntry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            if (log.isEmpty()) {
                Text(
                    stringResource(R.string.home_no_activity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            } else {
                log.forEach { entry ->
                    LogRow(entry)
                }
            }
        }
    }
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(entry.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Badge(text: String, contentColor: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(contentColor.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
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
            .size(12.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

private fun relativeTime(epochMs: Long, now: Long, context: android.content.Context): String {
    val diff = now - epochMs
    return when {
        diff < 45_000 -> context.getString(R.string.time_just_now)
        diff < 3_600_000 -> context.getString(R.string.time_min_ago, (diff / 60_000).coerceAtLeast(1).toInt())
        diff < 86_400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}

/** A recomposing clock that ticks every [intervalMs] so relative timestamps stay fresh. */
@Composable
private fun rememberNow(intervalMs: Long = 15_000L): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Opens the parked spot in Google Maps (by coordinates when known, else a search on the label). */
private fun openParkedInMaps(context: Context, lat: Double?, lng: Double?, label: String?) {
    val query = label?.let { Uri.encode(it) } ?: "Parked+car"
    val geoUri = if (lat != null && lng != null) {
        Uri.parse("geo:$lat,$lng?q=$lat,$lng($query)")
    } else if (label != null) {
        Uri.parse("geo:0,0?q=${Uri.encode(label)}")
    } else return
    val webUri = if (lat != null && lng != null) {
        Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
    } else {
        Uri.parse("https://www.google.com/maps/search/?api=1&query=$query")
    }
    try {
        // Prefer the Google Maps app, then any maps app, then the browser.
        context.startActivity(
            Intent(Intent.ACTION_VIEW, geoUri).setPackage("com.google.android.apps.maps"),
        )
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }
}
