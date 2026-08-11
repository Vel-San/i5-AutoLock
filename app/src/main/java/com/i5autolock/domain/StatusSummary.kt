package com.i5autolock.domain

import com.i5autolock.data.bluelink.model.LockState
import com.i5autolock.data.bluelink.model.VehicleStatus
import com.i5autolock.data.settings.NotificationField

/** Builds the user-customisable one-line vehicle summary for the notification/UI. */
object StatusSummary {
    fun build(status: VehicleStatus, fields: Set<NotificationField>): String {
        if (fields.isEmpty()) return ""
        val parts = mutableListOf<String>()
        // Preserve the enum order for a stable, predictable layout.
        for (field in NotificationField.entries) {
            if (field !in fields) continue
            when (field) {
                NotificationField.LOCK_STATE -> parts += when (status.lockState) {
                    LockState.LOCKED -> "Locked"
                    LockState.UNLOCKED -> "Unlocked"
                    LockState.UNKNOWN -> "Lock: ?"
                }
                NotificationField.EV_BATTERY -> status.evBatteryPercent?.let { parts += "$it%" }
                NotificationField.RANGE -> status.rangeKm?.let { parts += "$it km" }
                NotificationField.ENGINE -> parts += if (status.engineRunning) "Engine on" else "Engine off"
                NotificationField.TWELVE_VOLT -> status.twelveVoltPercent?.let { parts += "12V $it%" }
                NotificationField.CLIMATE -> status.climateOn?.let { parts += if (it) "Climate on" else "Climate off" }
            }
        }
        return parts.joinToString(" · ")
    }
}
