package com.i5autolock.domain.detection

/** High-level lifecycle of one "did I leave the car?" evaluation. */
enum class DetectionState {
    IDLE,
    ARMED,        // Watching for a disconnect trigger.
    CONFIRMING,   // Trigger fired; waiting for corroborating signals.
    GRACE,        // Counting down before acting.
    VERIFYING,    // Querying the vehicle status via API.
    AWAITING_CONFIRM, // Waiting for the user to confirm the lock (opt-in).
    LOCKING,      // Sending the lock command.
    LOCKED,       // Success.
    SKIPPED,      // Nothing to do (already locked / engine on / etc.).
    ABORTED,      // User returned / reconnected / cancelled.
    ERROR,        // Something failed.
}

/** External signals feeding the state machine. */
sealed interface DetectionEvent {
    data object Armed : DetectionEvent
    data object CarBluetoothDisconnected : DetectionEvent
    data object CarBluetoothReconnected : DetectionEvent
    data object WalkingConfirmed : DetectionEvent
    data object MovedBeyondGeofence : DetectionEvent
    data object GraceElapsed : DetectionEvent
    data object UserCancelled : DetectionEvent
}
