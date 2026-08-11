package com.i5autolock.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsScheduleTest {

    @Test
    fun disabledScheduleAlwaysAllows() {
        val s = AppSettings(scheduleEnabled = false)
        assertTrue(s.isWithinSchedule(3 * 60))
    }

    @Test
    fun daytimeWindow() {
        val s = AppSettings(scheduleEnabled = true, scheduleStartMinutes = 7 * 60, scheduleEndMinutes = 22 * 60)
        assertTrue(s.isWithinSchedule(12 * 60))
        assertFalse(s.isWithinSchedule(6 * 60))
        assertFalse(s.isWithinSchedule(23 * 60))
    }

    @Test
    fun overnightWindowWraps() {
        val s = AppSettings(scheduleEnabled = true, scheduleStartMinutes = 22 * 60, scheduleEndMinutes = 6 * 60)
        assertTrue(s.isWithinSchedule(23 * 60))
        assertTrue(s.isWithinSchedule(2 * 60))
        assertFalse(s.isWithinSchedule(12 * 60))
    }
}
