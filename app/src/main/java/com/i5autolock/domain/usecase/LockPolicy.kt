package com.i5autolock.domain.usecase

import com.i5autolock.data.bluelink.model.LockState
import com.i5autolock.data.bluelink.model.VehicleStatus

/** Outcome of evaluating a status snapshot against our locking policy. */
sealed interface LockDecision {
    data object Lock : LockDecision
    data class Skip(val reason: String) : LockDecision
}

/** Pure policy: should we attempt to lock, given this status? */
object LockPolicy {
    fun decide(status: VehicleStatus, dontLockIfOpen: Boolean = false): LockDecision = when {
        status.lockState == LockState.LOCKED -> LockDecision.Skip("Already locked")
        status.lockState == LockState.UNKNOWN -> LockDecision.Skip("Lock state unknown")
        status.engineRunning -> LockDecision.Skip("Engine running")
        dontLockIfOpen && status.anyDoorOpen == true -> LockDecision.Skip("A door is open")
        dontLockIfOpen && status.anyWindowOpen == true -> LockDecision.Skip("A window is open")
        else -> LockDecision.Lock
    }
}
