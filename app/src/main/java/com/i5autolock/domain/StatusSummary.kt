package com.i5autolock.domain

import android.content.Context
import com.i5autolock.R
import com.i5autolock.data.bluelink.model.LockState
import com.i5autolock.data.bluelink.model.VehicleStatus
import com.i5autolock.data.settings.NotificationField

/** Builds the user-customisable one-line vehicle summary for the notification/UI. */
object StatusSummary {

    /** Localised words used in the summary. Defaults to English so pure tests need no context. */
    data class Labels(
        val locked: String = "Locked",
        val unlocked: String = "Unlocked",
        val lockUnknown: String = "Lock: ?",
        val engineOn: String = "Engine on",
        val engineOff: String = "Engine off",
        val climateOn: String = "Climate on",
        val climateOff: String = "Climate off",
        val twelveVolt: String = "12V",
        val rangeUnit: String = "km",
    ) {
        companion object {
            val ENGLISH = Labels()
            fun from(context: Context) = Labels(
                locked = context.getString(R.string.summary_locked),
                unlocked = context.getString(R.string.summary_unlocked),
                lockUnknown = context.getString(R.string.summary_lock_unknown),
                engineOn = context.getString(R.string.summary_engine_on),
                engineOff = context.getString(R.string.summary_engine_off),
                climateOn = context.getString(R.string.summary_climate_on),
                climateOff = context.getString(R.string.summary_climate_off),
                twelveVolt = context.getString(R.string.summary_twelve_volt),
                rangeUnit = context.getString(R.string.summary_range_unit),
            )
        }
    }

    fun build(status: VehicleStatus, fields: Set<NotificationField>, labels: Labels = Labels.ENGLISH): String {
        if (fields.isEmpty()) return ""
        val parts = mutableListOf<String>()
        // Preserve the enum order for a stable, predictable layout.
        for (field in NotificationField.entries) {
            if (field !in fields) continue
            when (field) {
                NotificationField.LOCK_STATE -> parts += when (status.lockState) {
                    LockState.LOCKED -> labels.locked
                    LockState.UNLOCKED -> labels.unlocked
                    LockState.UNKNOWN -> labels.lockUnknown
                }
                NotificationField.EV_BATTERY -> status.evBatteryPercent?.let { parts += "$it%" }
                NotificationField.RANGE -> status.rangeKm?.let { parts += "$it ${labels.rangeUnit}" }
                NotificationField.ENGINE -> parts += if (status.engineRunning) labels.engineOn else labels.engineOff
                NotificationField.TWELVE_VOLT -> status.twelveVoltPercent?.let { parts += "${labels.twelveVolt} $it%" }
                NotificationField.CLIMATE -> status.climateOn?.let { parts += if (it) labels.climateOn else labels.climateOff }
            }
        }
        return parts.joinToString(" · ")
    }
}
