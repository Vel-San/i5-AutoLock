package com.i5autolock.domain.detection

import com.i5autolock.data.settings.AppSettings

/**
 * Pure, side-effect-free transition logic. The orchestrator owns timers and I/O;
 * this class only computes the next state so it can be unit tested exhaustively.
 *
 * Confirmation policy: after the Bluetooth disconnect trigger, we require every
 * *enabled* corroborating signal (activity + geofence) before starting the grace
 * countdown. This keeps false positives low.
 */
class LockStateMachine(private val settings: AppSettings) {

    private var walkingConfirmed = !settings.useActivityRecognition
    private var geofenceConfirmed = !settings.useGeofence

    fun next(current: DetectionState, event: DetectionEvent): DetectionState = when (event) {
        DetectionEvent.Armed -> DetectionState.ARMED

        DetectionEvent.CarBluetoothReconnected,
        DetectionEvent.UserCancelled -> DetectionState.ABORTED

        DetectionEvent.CarBluetoothDisconnected -> {
            if (current == DetectionState.ARMED || current == DetectionState.IDLE) {
                if (allConfirmed()) DetectionState.GRACE else DetectionState.CONFIRMING
            } else current
        }

        DetectionEvent.WalkingConfirmed -> {
            walkingConfirmed = true
            promoteIfConfirming(current)
        }

        DetectionEvent.MovedBeyondGeofence -> {
            geofenceConfirmed = true
            promoteIfConfirming(current)
        }

        DetectionEvent.GraceElapsed ->
            if (current == DetectionState.GRACE) DetectionState.VERIFYING else current
    }

    private fun promoteIfConfirming(current: DetectionState): DetectionState =
        if (current == DetectionState.CONFIRMING && allConfirmed()) DetectionState.GRACE else current

    private fun allConfirmed(): Boolean = walkingConfirmed && geofenceConfirmed
}
