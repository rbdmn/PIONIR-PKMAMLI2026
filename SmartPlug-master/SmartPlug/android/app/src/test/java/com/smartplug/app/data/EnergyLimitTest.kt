package com.smartplug.app.data

import com.google.common.truth.Truth.assertThat
import com.smartplug.app.data.local.EnergyLimit
import org.junit.Test

class EnergyLimitTest {
    @Test fun progressIsCurrentOverLimit() {
        val limit = EnergyLimit(enabled = true, kwh = 0.603)
        assertThat(Math.round(limit.progress(0.102) * 100)).isEqualTo(17L)
        assertThat(limit.progress(0.3015)).isWithin(1e-9).of(0.5)
    }

    @Test fun progressIsClampedAndSafe() {
        assertThat(EnergyLimit(true, 1.0).progress(2.0)).isEqualTo(1.0)
        assertThat(EnergyLimit(true, 1.0).progress(0.0)).isEqualTo(0.0)
        assertThat(EnergyLimit(true, 0.0).progress(5.0)).isEqualTo(0.0)
    }

    @Test fun reachedOnlyWhenEnabledAndAtOrAboveLimit() {
        assertThat(EnergyLimit(true, 1.0).isReached(1.0)).isTrue()
        assertThat(EnergyLimit(true, 1.0).isReached(0.999)).isFalse()
        assertThat(EnergyLimit(false, 1.0).isReached(5.0)).isFalse()
    }
}
