package com.smartplug.app.ui.screens.devicedetail

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimerCountdownRenderingTest {

    @Test
    fun `armed timer remains at its fixed duration between refreshed server snapshots`() {
        assertThat(
            displayTimerRemaining(
                sourceMs = 5_000L,
                armed = true,
                receivedAtMs = 1_000L,
                nowMs = 1_999L,
            ),
        ).isEqualTo(5_000L)
    }

    @Test
    fun `running timer continues to count down locally between refreshed snapshots`() {
        assertThat(
            displayTimerRemaining(
                sourceMs = 5_000L,
                armed = false,
                receivedAtMs = 1_000L,
                nowMs = 1_999L,
            ),
        ).isEqualTo(4_001L)
    }
}
