package com.i5autolock.data.device

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PairedDevice(val name: String, val mac: String)

/** Lists bonded (paired) Bluetooth devices so the user can pick their car. */
@Singleton
class BluetoothDevices @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun bondedDevices(): List<PairedDevice> {
        if (!hasPermission()) return emptyList()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty()
                .map { PairedDevice(it.name ?: "Unknown", it.address) }
                .sortedBy { it.name.lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
