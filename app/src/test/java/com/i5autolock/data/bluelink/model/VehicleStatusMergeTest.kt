package com.i5autolock.data.bluelink.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleStatusMergeTest {

    private fun status(
        lock: LockState = LockState.UNLOCKED,
        engine: Boolean = false,
        ev: Int? = null,
        range: Int? = null,
        twelve: Int? = null,
        door: Boolean? = null,
    ) = VehicleStatus(
        lockState = lock,
        engineRunning = engine,
        batteryCharging = null,
        timestamp = 0L,
        evBatteryPercent = ev,
        rangeKm = range,
        twelveVoltPercent = twelve,
        anyDoorOpen = door,
    )

    @Test
    fun keepsOldDetailWhenFreshIsPartial() {
        val old = status(ev = 72, range = 318, twelve = 88)
        val fresh = status(lock = LockState.LOCKED) // minimal force-refresh payload
        val merged = fresh.mergedOnto(old)
        assertEquals(LockState.LOCKED, merged.lockState) // lock state always from fresh
        assertEquals(72, merged.evBatteryPercent)
        assertEquals(318, merged.rangeKm)
        assertEquals(88, merged.twelveVoltPercent)
    }

    @Test
    fun freshValuesWinWhenPresent() {
        val old = status(ev = 50, range = 200)
        val fresh = status(ev = 80, range = 300)
        val merged = fresh.mergedOnto(old)
        assertEquals(80, merged.evBatteryPercent)
        assertEquals(300, merged.rangeKm)
    }

    @Test
    fun returnsSelfWhenNoPrevious() {
        val fresh = status(ev = 42)
        val merged = fresh.mergedOnto(null)
        assertEquals(42, merged.evBatteryPercent)
        assertNull(merged.rangeKm)
    }
}
