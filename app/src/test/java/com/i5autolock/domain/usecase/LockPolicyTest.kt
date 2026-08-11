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
}
