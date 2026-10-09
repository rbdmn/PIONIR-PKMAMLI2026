package com.smartplug.app.ui.screens.schedule

import com.google.common.truth.Truth.assertThat
import com.smartplug.app.domain.model.DailyScheduleEntry
import org.junit.Test

class ScheduleOrderingTest {

    @Test
    fun `renders entries in ascending time while preserving their device index for delete`() {
        val entries = listOf(
            DailyScheduleEntry(hour = 23, minute = 59, turnOn = false, event = "Night"),
            DailyScheduleEntry(hour = 0, minute = 0, turnOn = true, event = "Start"),
            DailyScheduleEntry(hour = 8, minute = 30, turnOn = true, event = "Morning"),
        )

        val ordered = orderedScheduleEntries(entries)

        assertThat(ordered.map { it.value.hour to it.value.minute })
            .containsExactly(0 to 0, 8 to 30, 23 to 59)
            .inOrder()
        assertThat(ordered.map { it.index }).containsExactly(1, 2, 0).inOrder()
    }
}
