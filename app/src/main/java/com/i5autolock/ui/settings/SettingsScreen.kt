package com.i5autolock.ui.settings

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.i5autolock.R
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

    // Ringtone/sound picker for the custom lock sound.
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            viewModel.setCustomLockSoundUri(uri?.toString())
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(ambientBackground()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            HeroBanner(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_hero_subtitle),
                icon = Icons.Default.Tune,
                eyebrow = stringResource(R.string.settings_hero_eyebrow),
            )

            // Safety / run mode.
            Section(stringResource(R.string.sec_safety)) {
                RowToggle(
                    title = stringResource(R.string.set_demo_title),
                    subtitle = stringResource(R.string.set_demo_sub),
                    checked = settings.demoMode,
                    onCheckedChange = viewModel::setDemoMode,
                )
                Text(stringResource(R.string.set_locking_behaviour), fontWeight = FontWeight.Medium)
                RunModeOption(stringResource(R.string.set_dry_run), settings.runMode == RunMode.DRY_RUN) {
                    viewModel.setRunMode(RunMode.DRY_RUN)
                }
                RunModeOption(stringResource(R.string.set_armed), settings.runMode == RunMode.ARMED) {
                    viewModel.setRunMode(RunMode.ARMED)
                }
                RowToggle(
                    title = stringResource(R.string.set_confirm_title),
                    subtitle = stringResource(R.string.set_confirm_sub),
                    checked = settings.requireConfirmationBeforeLock,
                    onCheckedChange = viewModel::setRequireConfirmation,
                )
            }

            // Account.
            Section(stringResource(R.string.sec_account)) {
                Text(settings.accountEmail ?: stringResource(R.string.set_not_signed_in), fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (extras.signedIn) {
                        OutlinedButton(onClick = viewModel::signOut) { Text(stringResource(R.string.set_sign_out)) }
                        Button(onClick = viewModel::loadVehicles) { Text(stringResource(R.string.set_reload_vehicles)) }
                    } else {
                        Button(onClick = onLogin) { Text(stringResource(R.string.action_sign_in)) }
                    }
                }
                Text(stringResource(R.string.set_region), fontWeight = FontWeight.Medium)
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
            Section(stringResource(R.string.sec_vehicle)) {
                if (extras.loadingVehicles) {
                    CircularProgressIndicator()
                } else if (extras.vehicles.isEmpty()) {
                    Text(stringResource(R.string.set_no_vehicles), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = viewModel::loadVehicles) { Text(stringResource(R.string.set_load_vehicles)) }
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
            Section(stringResource(R.string.sec_car_bt)) {
                Text(
                    settings.carBluetoothName?.let { stringResource(R.string.set_car_bt_selected, it) } ?: stringResource(R.string.set_car_bt_pick),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = viewModel::refreshDevices) { Text(stringResource(R.string.set_refresh_devices)) }
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
            Section(stringResource(R.string.sec_detection)) {
                RowToggle(
                    stringResource(R.string.set_det_bt_title),
                    stringResource(R.string.set_det_bt_sub),
                    settings.useBluetoothTrigger,
                    viewModel::setUseBluetooth,
                )
                RowToggle(
                    stringResource(R.string.set_det_activity_title),
                    stringResource(R.string.set_det_activity_sub),
                    settings.useActivityRecognition,
                    viewModel::setUseActivity,
                )
                RowToggle(
                    stringResource(R.string.set_det_geo_title),
                    stringResource(R.string.set_det_geo_sub),
                    settings.useGeofence,
                    viewModel::setUseGeofence,
                )
                if (settings.useGeofence) {
                    Text(stringResource(R.string.set_geo_radius, settings.geofenceRadiusMeters))
                    Slider(
                        value = settings.geofenceRadiusMeters.toFloat(),
                        onValueChange = { viewModel.setGeofenceRadius(it.toInt()) },
                        valueRange = 10f..100f,
                    )
                }
            }

            // Timing.
            Section(stringResource(R.string.sec_timing)) {
                Text(stringResource(R.string.set_grace, settings.graceSeconds))
                Slider(
                    value = settings.graceSeconds.toFloat(),
                    onValueChange = { viewModel.setGrace(it.toInt()) },
                    valueRange = 5f..180f,
                )
                Text(stringResource(R.string.set_min_refresh_title), fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.set_min_refresh_sub),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.set_min_refresh_value, settings.minRefreshSeconds))
                Slider(
                    value = settings.minRefreshSeconds.toFloat(),
                    onValueChange = { viewModel.setMinRefreshSeconds(it.toInt()) },
                    valueRange = 3f..30f,
                )
            }

            // Low 12V battery warning.
            Section(stringResource(R.string.sec_low_volt)) {
                RowToggle(
                    stringResource(R.string.set_low_volt_title),
                    stringResource(R.string.set_low_volt_sub),
                    settings.lowVoltageAlert,
                    viewModel::setLowVoltageAlert,
                )
                if (settings.lowVoltageAlert) {
                    Text(stringResource(R.string.set_low_volt_threshold, settings.lowVoltageThreshold))
                    Slider(
                        value = settings.lowVoltageThreshold.toFloat(),
                        onValueChange = { viewModel.setLowVoltageThreshold(it.toInt()) },
                        valueRange = 10f..90f,
                    )
                }
            }

            // Behaviour.
            Section(stringResource(R.string.sec_behaviour)) {
                RowToggle(
                    stringResource(R.string.set_auto_refresh_open_title),
                    stringResource(R.string.set_auto_refresh_open_sub),
                    settings.autoRefreshOnOpen,
                    viewModel::setAutoRefreshOnOpen,
                )
                Text(stringResource(R.string.set_bg_check_title), fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.set_bg_check_sub),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to stringResource(R.string.set_off),
                        15 to stringResource(R.string.set_15m),
                        30 to stringResource(R.string.set_30m),
                        60 to stringResource(R.string.set_60m),
                    ).forEach { (mins, label) ->
                        FilterChip(
                            selected = settings.autoRefreshIntervalMinutes == mins,
                            onClick = { viewModel.setAutoRefreshInterval(mins) },
                            label = { Text(label) },
                        )
                    }
                }
                RowToggle(
                    stringResource(R.string.set_vibrate_title),
                    stringResource(R.string.set_vibrate_sub),
                    settings.hapticOnLock,
                    viewModel::setHapticOnLock,
                )
                RowToggle(
                    stringResource(R.string.set_sound_title),
                    stringResource(R.string.set_sound_sub),
                    settings.soundOnLock,
                    viewModel::setSoundOnLock,
                )
                if (settings.soundOnLock) {
                    val soundLabel = remember(settings.customLockSoundUri) {
                        settings.customLockSoundUri?.let { uriStr ->
                            runCatching {
                                RingtoneManager.getRingtone(context, Uri.parse(uriStr))?.getTitle(context)
                            }.getOrNull()
                        }
                    }
                    Text(
                        stringResource(R.string.set_sound_current, soundLabel ?: stringResource(R.string.set_sound_default)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.set_sound_pick_title))
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                settings.customLockSoundUri?.let {
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
                                }
                            }
                            soundPicker.launch(intent)
                        }) { Text(stringResource(R.string.set_sound_choose)) }
                        if (settings.customLockSoundUri != null) {
                            OutlinedButton(onClick = { viewModel.setCustomLockSoundUri(null) }) {
                                Text(stringResource(R.string.set_sound_use_default))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.testLockSound() }) {
                            Text(stringResource(R.string.set_sound_test))
                        }
                        OutlinedButton(onClick = { viewModel.playDefaultSound() }) {
                            Text(stringResource(R.string.set_sound_play_default))
                        }
                    }
                }
                RowToggle(
                    stringResource(R.string.set_remember_title),
                    stringResource(R.string.set_remember_sub),
                    settings.rememberParkedLocation,
                    viewModel::setRememberParkedLocation,
                )
            }

            // Active-hours schedule.
            Section(stringResource(R.string.sec_schedule)) {
                RowToggle(
                    stringResource(R.string.schedule_enabled_title),
                    stringResource(R.string.schedule_enabled_sub),
                    settings.scheduleEnabled,
                    viewModel::setScheduleEnabled,
                )
                if (settings.scheduleEnabled) {
                    Text(stringResource(R.string.schedule_from, formatMinutes(settings.scheduleStartMinutes)))
                    Slider(
                        value = settings.scheduleStartMinutes.toFloat(),
                        onValueChange = { viewModel.setScheduleStart((it / 15).toInt() * 15) },
                        valueRange = 0f..1425f,
                    )
                    Text(stringResource(R.string.schedule_until, formatMinutes(settings.scheduleEndMinutes)))
                    Slider(
                        value = settings.scheduleEndMinutes.toFloat(),
                        onValueChange = { viewModel.setScheduleEnd((it / 15).toInt() * 15) },
                        valueRange = 0f..1425f,
                    )
                }
            }

            // Notification.
            Section(stringResource(R.string.sec_notification)) {
                RowToggle(
                    title = stringResource(R.string.set_pin_title),
                    subtitle = stringResource(R.string.set_pin_sub),
                    checked = settings.pinNotification,
                    onCheckedChange = viewModel::setPinNotification,
                )
                RowToggle(
                    title = stringResource(R.string.set_badge_title),
                    subtitle = stringResource(R.string.set_badge_sub),
                    checked = settings.showAppBadge,
                    onCheckedChange = viewModel::setShowAppBadge,
                )
                RowToggle(
                    title = stringResource(R.string.set_locknow_title),
                    subtitle = stringResource(R.string.set_locknow_sub),
                    checked = settings.showLockNowAction,
                    onCheckedChange = viewModel::setShowLockNowAction,
                )
                RowToggle(
                    title = stringResource(R.string.set_showstatus_title),
                    subtitle = stringResource(R.string.set_showstatus_sub),
                    checked = settings.showStatusInNotification,
                    onCheckedChange = viewModel::setShowStatusInNotification,
                )
                if (settings.showStatusInNotification) {
                    Text(stringResource(R.string.set_show_details), fontWeight = FontWeight.Medium)
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
            Section(stringResource(R.string.sec_appearance)) {
                Text(stringResource(R.string.set_theme), fontWeight = FontWeight.Medium)
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
                    title = stringResource(R.string.set_dynamic_title),
                    subtitle = stringResource(R.string.set_dynamic_sub),
                    checked = settings.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }

            // Keep running.
            Section(stringResource(R.string.sec_keep_running)) {
                Text(
                    stringResource(R.string.set_keep_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.set_keep_tips),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { openBatterySettings(context) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.set_battery_settings))
                    }
                    OutlinedButton(onClick = { openAppInfo(context) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.set_app_info))
                    }
                }
            }

            // Diagnostics.
            Section(stringResource(R.string.sec_diagnostics)) {
                Text(
                    stringResource(R.string.set_diag_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onStats, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.set_open_stats))
                }
            }

            // Help.
            Section(stringResource(R.string.sec_help)) {
                Text(
                    stringResource(R.string.set_help_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.set_open_help))
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
