package com.i5autolock.domain.usecase

import com.i5autolock.data.bluelink.model.LockState
import com.i5autolock.data.bluelink.model.VehicleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {

    private fun status(lock: LockState, engine: Boolean = false) =
        VehicleStatus(lock, engine, batteryCharging = null, timestamp = 0L)

    @Test
    fun locksWhenUnlockedAndEngineOff() {
        assertEquals(LockDecision.Lock, LockPolicy.decide(status(LockState.UNLOCKED)))
    }

    @Test
    fun skipsWhenAlreadyLocked() {
        val decision = LockPolicy.decide(status(LockState.LOCKED))
        assertTrue(decision is LockDecision.Skip)
    }

    @Test
    fun skipsWhenEngineRunning() {
        val decision = LockPolicy.decide(status(LockState.UNLOCKED, engine = true))
        assertTrue(decision is LockDecision.Skip)
    }

    @Test
    fun skipsWhenUnknown() {
        val decision = LockPolicy.decide(status(LockState.UNKNOWN))
        assertTrue(decision is LockDecision.Skip)
    }

    @Test
    fun skipsWhenDoorOpenAndGuardOn() {
        val open = status(LockState.UNLOCKED).copy(anyDoorOpen = true)
        assertTrue(LockPolicy.decide(open, dontLockIfOpen = true) is LockDecision.Skip)
        // Guard off → still locks (Hyundai will reject, but policy doesn't block).
        assertEquals(LockDecision.Lock, LockPolicy.decide(open, dontLockIfOpen = false))
    }

    @Test
    fun skipsWhenWindowOpenAndGuardOn() {
        val open = status(LockState.UNLOCKED).copy(anyWindowOpen = true)
        assertTrue(LockPolicy.decide(open, dontLockIfOpen = true) is LockDecision.Skip)
    }
}
