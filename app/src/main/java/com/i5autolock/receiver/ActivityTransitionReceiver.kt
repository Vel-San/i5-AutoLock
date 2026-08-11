package com.i5autolock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.i5autolock.domain.AutoLockController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Receives Activity Recognition transitions and confirms "walking" to the controller. */
@AndroidEntryPoint
class ActivityTransitionReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: AutoLockController

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val walkedAway = result.transitionEvents.any {
            it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER &&
                (it.activityType == DetectedActivity.WALKING || it.activityType == DetectedActivity.ON_FOOT)
        }
        if (walkedAway) controller.onWalkingConfirmed()
    }
}
