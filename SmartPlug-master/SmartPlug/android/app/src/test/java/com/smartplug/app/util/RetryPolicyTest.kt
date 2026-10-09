package com.smartplug.app.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun `measurement backoff follows design_md 2,4,8,15,30 second schedule`() {
        assertThat(MeasurementBackoff.delayForAttempt(1)).isEqualTo(2_000)
        assertThat(MeasurementBackoff.delayForAttempt(2)).isEqualTo(4_000)
        assertThat(MeasurementBackoff.delayForAttempt(3)).isEqualTo(8_000)
        assertThat(MeasurementBackoff.delayForAttempt(4)).isEqualTo(15_000)
        assertThat(MeasurementBackoff.delayForAttempt(5)).isEqualTo(30_000)
    }

    @Test
    fun `measurement backoff caps at 30 seconds for further failures`() {
        assertThat(MeasurementBackoff.delayForAttempt(6)).isEqualTo(30_000)
        assertThat(MeasurementBackoff.delayForAttempt(100)).isEqualTo(30_000)
    }

    @Test
    fun `measurement backoff never goes below the first step`() {
        assertThat(MeasurementBackoff.delayForAttempt(0)).isEqualTo(2_000)
        assertThat(MeasurementBackoff.delayForAttempt(-5)).isEqualTo(2_000)
    }

    @Test
    fun `reconnect backoff mirrors firmware MQTT 5,10,20,40,60 second schedule`() {
        assertThat(ReconnectBackoff.delayForAttempt(1)).isEqualTo(5_000)
        assertThat(ReconnectBackoff.delayForAttempt(2)).isEqualTo(10_000)
        assertThat(ReconnectBackoff.delayForAttempt(3)).isEqualTo(20_000)
        assertThat(ReconnectBackoff.delayForAttempt(4)).isEqualTo(40_000)
        assertThat(ReconnectBackoff.delayForAttempt(5)).isEqualTo(60_000)
        assertThat(ReconnectBackoff.delayForAttempt(9)).isEqualTo(60_000)
    }

    @Test
    fun `polling cadences match design_md intervals`() {
        assertThat(PollingCadence.NORMAL.intervalMs).isEqualTo(2_000)
        assertThat(PollingCadence.LIVE.intervalMs).isEqualTo(1_000)
        assertThat(PollingCadence.DEVICE_LIST.intervalMs).isEqualTo(5_000)
    }
}
