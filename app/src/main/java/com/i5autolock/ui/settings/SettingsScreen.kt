package com.i5autolock.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.i5autolock.data.bluelink.Region
import com.i5autolock.data.settings.NotificationField
import com.i5autolock.data.settings.RunMode
import com.i5autolock.data.settings.ThemeMode
import com.i5autolock.ui.components.HeroBanner
import com.i5autolock.ui.theme.ambientBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onStats: () -> Unit,
    onHelp: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val extras by viewModel.extras.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize().background(ambientBackground()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
            HeroBanner(
                title = "Settings",
                subtitle = "Tune how AutoLock detects you leaving and how it locks your car.",
                icon = Icons.Default.Tune,
                eyebrow = "Configure",
            )

            // Safety / run mode.
            Section("Safety") {
                RowToggle(
                    title = "Demo mode",
                    subtitle = "Use a simulated car — no account needed.",
                    checked = settings.demoMode,
                    onCheckedChange = viewModel::setDemoMode,
                )
                Text("Locking behaviour", fontWeight = FontWeight.Medium)
                RunModeOption("Dry run (never sends a real lock)", settings.runMode == RunMode.DRY_RUN) {
                    viewModel.setRunMode(RunMode.DRY_RUN)
                }
                RunModeOption("Armed (locks for real)", settings.runMode == RunMode.ARMED) {
                    viewModel.setRunMode(RunMode.ARMED)
                }
                RowToggle(
                    title = "Confirm before locking",
                    subtitle = "Ask via notification instead of locking automatically.",
                    checked = settings.requireConfirmationBeforeLock,
                    onCheckedChange = viewModel::setRequireConfirmation,
                )
            }

            // Account.
            Section("Account") {
                Text(settings.accountEmail ?: "Not signed in", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (extras.signedIn) {
                        OutlinedButton(onClick = viewModel::signOut) { Text("Sign out") }
                        Button(onClick = viewModel::loadVehicles) { Text("Reload vehicles") }
                    } else {
                        Button(onClick = onLogin) { Text("Sign in") }
                    }
                }
                Text("Region", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Region.entries.forEach { region ->
                        FilterChip(
                            selected = settings.region == region,
                            onClick = { viewModel.setRegion(region) },
                            label = { Text(region.name) },
                        )
                    }
                }
            }

            // Vehicle.
            Section("Vehicle") {
                if (extras.loadingVehicles) {
                    CircularProgressIndicator()
                } else if (extras.vehicles.isEmpty()) {
                    Text("No vehicles loaded.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = viewModel::loadVehicles) { Text("Load vehicles") }
                } else {
                    extras.vehicles.forEach { v ->
                        SelectableRow(
                            title = v.nickname,
                            subtitle = v.model,
                            selected = settings.vehicleId == v.id,
                            onClick = { viewModel.selectVehicle(v) },
                        )
                    }
                }
            }

            // Bluetooth trigger.
            Section("Car Bluetooth") {
                Text(
                    settings.carBluetoothName?.let { "Selected: $it" } ?: "Pick your car's Bluetooth device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = viewModel::refreshDevices) { Text("Refresh paired devices") }
                extras.pairedDevices.forEach { d ->
                    SelectableRow(
                        title = d.name,
                        subtitle = d.mac,
                        selected = settings.carBluetoothMac == d.mac,
                        onClick = { viewModel.selectCarDevice(d) },
                    )
                }
            }

            // Detection.
            Section("Detection") {
                RowToggle(
                    "Bluetooth disconnect",
                    "Primary trigger when you leave the car.",
                    settings.useBluetoothTrigger,
                    viewModel::setUseBluetooth,
                )
                RowToggle(
                    "Activity confirmation",
                    "Confirm you switched from driving to walking.",
                    settings.useActivityRecognition,
                    viewModel::setUseActivity,
                )
                RowToggle(
                    "Geofence confirmation",
                    "Only lock once you've walked away from the car.",
                    settings.useGeofence,
                    viewModel::setUseGeofence,
                )
                if (settings.useGeofence) {
                    Text("Radius: ${settings.geofenceRadiusMeters} m")
                    Slider(
                        value = settings.geofenceRadiusMeters.toFloat(),
                        onValueChange = { viewModel.setGeofenceRadius(it.toInt()) },
                        valueRange = 10f..100f,
                    )
                }
            }

            // Timing.
            Section("Timing") {
                Text("Grace period: ${settings.graceSeconds}s")
                Slider(
                    value = settings.graceSeconds.toFloat(),
                    onValueChange = { viewModel.setGrace(it.toInt()) },
                    valueRange = 5f..180f,
                )
            }

            // Behaviour.
            Section("Behaviour") {
                RowToggle(
                    "Auto-refresh on open",
                    "Fetch the latest vehicle status when you open the app.",
                    settings.autoRefreshOnOpen,
                    viewModel::setAutoRefreshOnOpen,
                )
                Text("Background auto-check", fontWeight = FontWeight.Medium)
                Text(
                    "Refresh the vehicle status in the background on a schedule.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Off", 15 to "15m", 30 to "30m", 60 to "60m").forEach { (mins, label) ->
                        FilterChip(
                            selected = settings.autoRefreshIntervalMinutes == mins,
                            onClick = { viewModel.setAutoRefreshInterval(mins) },
                            label = { Text(label) },
                        )
                    }
                }
                RowToggle(
                    "Vibrate on lock",
                    "Buzz when the car is locked.",
                    settings.hapticOnLock,
                    viewModel::setHapticOnLock,
                )
                RowToggle(
                    "Sound on lock",
                    "Play a short sound when the car is locked.",
                    settings.soundOnLock,
                    viewModel::setSoundOnLock,
                )
                RowToggle(
                    "Remember parked location",
                    "Show where you left the car on the status card.",
                    settings.rememberParkedLocation,
                    viewModel::setRememberParkedLocation,
                )
            }

            // Active-hours schedule.
            Section("Schedule") {
                RowToggle(
                    "Only during set hours",
                    "AutoLock stays idle outside this time window.",
                    settings.scheduleEnabled,
                    viewModel::setScheduleEnabled,
                )
                if (settings.scheduleEnabled) {
                    Text("From ${formatMinutes(settings.scheduleStartMinutes)}")
                    Slider(
                        value = settings.scheduleStartMinutes.toFloat(),
                        onValueChange = { viewModel.setScheduleStart((it / 15).toInt() * 15) },
                        valueRange = 0f..1425f,
                    )
                    Text("Until ${formatMinutes(settings.scheduleEndMinutes)}")
                    Slider(
                        value = settings.scheduleEndMinutes.toFloat(),
                        onValueChange = { viewModel.setScheduleEnd((it / 15).toInt() * 15) },
                        valueRange = 0f..1425f,
                    )
                }
            }

            // Notification.
            Section("Notification") {
                RowToggle(
                    title = "Pin the notification",
                    subtitle = "Keep the \"watching\" notification stuck — it re-appears if swiped away.",
                    checked = settings.pinNotification,
                    onCheckedChange = viewModel::setPinNotification,
                )
                RowToggle(
                    title = "\"Lock now\" button",
                    subtitle = "Show a button to lock immediately, skipping the countdown.",
                    checked = settings.showLockNowAction,
                    onCheckedChange = viewModel::setShowLockNowAction,
                )
                RowToggle(
                    title = "Show vehicle status",
                    subtitle = "Add a live status line to the ongoing notification.",
                    checked = settings.showStatusInNotification,
                    onCheckedChange = viewModel::setShowStatusInNotification,
                )
                if (settings.showStatusInNotification) {
                    Text("Show these details:", fontWeight = FontWeight.Medium)
                    NotificationField.entries.forEach { field ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(field.label)
                            Switch(
                                checked = field in settings.notificationFields,
                                onCheckedChange = { viewModel.toggleNotificationField(field, it) },
                            )
                        }
                    }
                }
            }

            // Appearance.
            Section("Appearance") {
                Text("Theme", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                RowToggle(
                    title = "Dynamic color",
                    subtitle = "Use colors from your wallpaper (Android 12+).",
                    checked = settings.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }

            // Keep running.
            Section("Keep AutoLock running") {
                Text(
                    "Phones aggressively kill background apps. So auto-locking fires reliably:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "\u2022 Allow unrestricted / unmonitored battery use\n" +
                        "\u2022 Remove any \"restrict background\" limit\n" +
                        "\u2022 Keep AutoLock's notifications enabled\n" +
                        "\u2022 On Samsung/Xiaomi/Huawei, add AutoLock to \"never sleeping apps\"",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { openBatterySettings(context) }, modifier = Modifier.weight(1f)) {
                        Text("Battery settings")
                    }
                    OutlinedButton(onClick = { openAppInfo(context) }, modifier = Modifier.weight(1f)) {
                        Text("App info")
                    }
                }
            }

            // Diagnostics.
            Section("Diagnostics") {
                Text(
                    "See detailed API metrics: call durations, success rate, rate-limit status, and logs.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onStats, modifier = Modifier.fillMaxWidth()) {
                    Text("Open API statistics")
                }
            }

            // Help.
            Section("Help") {
                Text(
                    "New here? The help page explains every setting and how automatic locking works.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) {
                    Text("Open help & tutorial")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

private fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/** Opens the system battery-optimization list so the user can exempt AutoLock. */
private fun openBatterySettings(context: Context) {
    val opened = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) openAppInfo(context)
}

/** Opens this app's system settings page (battery, notifications, permissions). */
private fun openAppInfo(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun RowToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RunModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SelectableRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
