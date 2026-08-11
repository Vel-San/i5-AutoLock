package com.i5autolock.domain

import com.i5autolock.data.bluelink.model.LockState
import com.i5autolock.data.bluelink.model.VehicleStatus
import com.i5autolock.data.settings.NotificationField
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusSummaryTest {

    private val status = VehicleStatus(
        lockState = LockState.UNLOCKED,
        engineRunning = false,
        batteryCharging = false,
        timestamp = 0L,
        evBatteryPercent = 72,
        rangeKm = 318,
        twelveVoltPercent = 88,
        climateOn = false,
    )

    @Test
    fun buildsSelectedFieldsInStableOrder() {
        val summary = StatusSummary.build(
            status,
            setOf(NotificationField.RANGE, NotificationField.LOCK_STATE, NotificationField.EV_BATTERY),
        )
        assertEquals("Unlocked · 72% · 318 km", summary)
    }

    @Test
    fun emptySelectionYieldsEmptyString() {
        assertEquals("", StatusSummary.build(status, emptySet()))
    }

    @Test
    fun skipsMissingValues() {
        val bare = status.copy(evBatteryPercent = null, rangeKm = null)
        val summary = StatusSummary.build(
            bare,
            setOf(NotificationField.EV_BATTERY, NotificationField.RANGE, NotificationField.TWELVE_VOLT),
        )
        assertEquals("12V 88%", summary)
    }
}
