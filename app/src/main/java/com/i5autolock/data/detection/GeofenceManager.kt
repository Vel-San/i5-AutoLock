package com.i5autolock.data.detection

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.i5autolock.receiver.GeofenceReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers a battery-friendly, system-managed geofence around the parked car. When the phone
 * exits the radius (you walked away), the OS wakes [GeofenceReceiver] — no polling or persistent
 * service needed. Registered on Bluetooth "arrival" (connect) and removed once it fires.
 */
@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client = LocationServices.getGeofencingClient(context)

    private val pendingIntent: PendingIntent by lazy {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, GeofenceReceiver::class.java),
            flags,
        )
    }

    private fun hasBackgroundLocation(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) return false
        // Geofence callbacks while backgrounded need background-location on Q+.
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // Guarded by hasBackgroundLocation() + runCatching.
    fun register(lat: Double, lng: Double, radiusMeters: Int) {
        if (!hasBackgroundLocation()) return
        val geofence = Geofence.Builder()
            .setRequestId(ID)
            .setCircularRegion(lat, lng, radiusMeters.toFloat().coerceAtLeast(50f))
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .setLoiteringDelay(5_000)
            .build()
        val request = GeofencingRequest.Builder()
            // Don't fire on registration even if already outside — wait for a real exit.
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()
        runCatching { client.addGeofences(request, pendingIntent) }
    }

    fun remove() {
        runCatching { client.removeGeofences(pendingIntent) }
    }

    companion object {
        const val ID = "autolock_car_geofence"
    }
}
