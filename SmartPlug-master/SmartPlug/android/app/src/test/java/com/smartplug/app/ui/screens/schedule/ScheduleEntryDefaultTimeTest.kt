package com.smartplug.app.ui.screens.schedule

import com.google.common.truth.Truth.assertThat
import com.smartplug.app.domain.model.DeviceSchedule
import org.junit.Test

class ScheduleEntryDefaultTimeTest {

    @Test
    fun `uses the schedule authority clock when it is available`() {
        val schedule = DeviceSchedule(
            enabled = true,
            clockSynchronized = true,
            clockUtcMs = 1_791_268_440_000L, // 2026-10-06T06:34:00Z
            timezoneOffsetMinutes = 420,
            nextRemainingSeconds = 0,
            nextTurnOn = null,
            entries = emptyList(),
        )

        assertThat(scheduleEntryDefaultTime(schedule, displayTimezoneOffsetMinutes = 420, fallbackUtcMs = 0L))
            .isEqualTo(ScheduleEntryTime(hour = 13, minute = 34))
    }

    @Test
    fun `uses a current fallback in the schedule timezone instead of midnight`() {
        val schedule = DeviceSchedule(
            enabled = false,
            clockSynchronized = false,
            clockUtcMs = 0L,
            timezoneOffsetMinutes = 420,
            nextRemainingSeconds = 0,
            nextTurnOn = null,
            entries = emptyList(),
        )

        assertThat(scheduleEntryDefaultTime(schedule, displayTimezoneOffsetMinutes = 420, fallbackUtcMs = 1_791_268_440_000L))
            .isEqualTo(ScheduleEntryTime(hour = 13, minute = 34))
    }
}
