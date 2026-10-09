package com.smartplug.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleCountdownVisibilityTest {
    @Test
    fun `shows a schedule countdown through exactly three hours`() {
        assertTrue(shouldShowScheduleCountdown(1L))
        assertTrue(shouldShowScheduleCountdown(SCHEDULE_COUNTDOWN_DISPLAY_LIMIT_MS))
    }

    @Test
    fun `hides an expired or more than three hour schedule countdown`() {
        assertFalse(shouldShowScheduleCountdown(0L))
        assertFalse(shouldShowScheduleCountdown(SCHEDULE_COUNTDOWN_DISPLAY_LIMIT_MS + 1L))
    }
}
