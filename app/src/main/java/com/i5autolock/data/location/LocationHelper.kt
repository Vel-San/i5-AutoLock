package com.i5autolock.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** A remembered parked position: coordinates + an optional human-friendly label. */
data class ParkedPlace(val lat: Double, val lng: Double, val label: String?)

/** Best-effort current location + a short human label, used to remember where the car parked. */
@Singleton
class LocationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns a short place label (e.g. "Market St") or null if unavailable. */
    suspend fun currentPlaceLabel(): String? = currentParkedPlace()?.label

    /** Returns the current coordinates + a short place label, or null if unavailable. */
    suspend fun currentParkedPlace(): ParkedPlace? {
        if (!hasPermission()) return null
        val location = try {
            suspendCancellableCoroutine<android.location.Location?> { cont ->
                fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
        } catch (_: SecurityException) {
            null
        } ?: return null

        return ParkedPlace(
            lat = location.latitude,
            lng = location.longitude,
            label = reverseGeocode(location.latitude, location.longitude),
        )
    }

    /** Raw current location (no geocoding) — used for geofence "moved away" confirmation. */
    suspend fun currentLocation(): android.location.Location? {
        if (!hasPermission()) return null
        return try {
            suspendCancellableCoroutine { cont ->
                fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private suspend fun reverseGeocode(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lng, 1) { results ->
                        cont.resume(results.firstOrNull()?.toLabel())
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.toLabel()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun android.location.Address.toLabel(): String? {
        // Build a detailed, human-friendly label: "12 Market St, San Francisco".
        val street = listOfNotNull(subThoroughfare, thoroughfare).joinToString(" ").ifBlank { null }
        val area = subLocality ?: locality ?: subAdminArea
        val parts = listOfNotNull(street ?: featureName, area).distinct()
        return parts.joinToString(", ").ifBlank { null }
    }
}
