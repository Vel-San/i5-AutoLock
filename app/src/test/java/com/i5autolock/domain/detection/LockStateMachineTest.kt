package com.i5autolock.domain.detection

import com.i5autolock.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class LockStateMachineTest {

    @Test
    fun disconnectGoesStraightToGraceWhenNoCorroborationRequired() {
        val sm = LockStateMachine(
            AppSettings(useActivityRecognition = false, useGeofence = false),
        )
        val next = sm.next(DetectionState.ARMED, DetectionEvent.CarBluetoothDisconnected)
        assertEquals(DetectionState.GRACE, next)
    }

    @Test
    fun disconnectWaitsForConfirmationWhenActivityRequired() {
        val sm = LockStateMachine(
            AppSettings(useActivityRecognition = true, useGeofence = false),
        )
        val afterDisconnect = sm.next(DetectionState.ARMED, DetectionEvent.CarBluetoothDisconnected)
        assertEquals(DetectionState.CONFIRMING, afterDisconnect)

        val afterWalking = sm.next(afterDisconnect, DetectionEvent.WalkingConfirmed)
        assertEquals(DetectionState.GRACE, afterWalking)
    }

    @Test
    fun reconnectAborts() {
        val sm = LockStateMachine(AppSettings())
        val next = sm.next(DetectionState.GRACE, DetectionEvent.CarBluetoothReconnected)
        assertEquals(DetectionState.ABORTED, next)
    }

    @Test
    fun graceElapsedMovesToVerifying() {
        val sm = LockStateMachine(AppSettings(useActivityRecognition = false, useGeofence = false))
        val next = sm.next(DetectionState.GRACE, DetectionEvent.GraceElapsed)
        assertEquals(DetectionState.VERIFYING, next)
    }
}
