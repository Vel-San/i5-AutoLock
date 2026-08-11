package com.i5autolock.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.i5autolock.domain.ActivityLog
import com.i5autolock.domain.AutoLockController
import com.i5autolock.domain.LogLevel
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.service.AutoLockService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Primary "left the car" trigger: fires when the phone disconnects from the car's
 * paired Bluetooth device. Also cancels a pending lock if we reconnect (user got back in).
 */
@AndroidEntryPoint
class BluetoothStateReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var controller: AutoLockController
    @Inject lateinit var log: ActivityLog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        @Suppress("DEPRECATION")
        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val deviceMac = device?.address ?: return

        val pending = goAsync()
        scope.launch {
            try {
                val settings = settingsRepo.settings.first()
                if (!settings.enabled || !settings.useBluetoothTrigger) return@launch
                if (!deviceMac.equalsIgnoreCase(settings.carBluetoothMac)) return@launch

                when (action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        log.add(LogLevel.INFO, "Car Bluetooth disconnected — starting evaluation.")
                        AutoLockService.start(context)
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        controller.cancel()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun String.equalsIgnoreCase(other: String?): Boolean =
        other != null && this.equals(other, ignoreCase = true)
}
