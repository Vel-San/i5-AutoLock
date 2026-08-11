package com.i5autolock.data.detection

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.i5autolock.receiver.ActivityTransitionReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers Activity Recognition transition updates so a driving → walking transition can confirm
 * the user actually left the car (a second signal beyond the Bluetooth disconnect).
 */
@Singleton
class ActivityRecognitionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pendingIntent: PendingIntent by lazy {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ActivityTransitionReceiver::class.java),
            flags,
        )
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Guarded by hasPermission() + runCatching.
    fun start() {
        if (!hasPermission()) return
        val transitions = ArrayList<ActivityTransition>()
        for (type in intArrayOf(DetectedActivity.WALKING, DetectedActivity.ON_FOOT)) {
            transitions.add(
                ActivityTransition.Builder()
                    .setActivityType(type)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
            )
        }
        runCatching {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent)
        }
    }

    @SuppressLint("MissingPermission") // Guarded by hasPermission() + runCatching.
    fun stop() {
        if (!hasPermission()) return
        runCatching {
            ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent)
        }
    }
}
