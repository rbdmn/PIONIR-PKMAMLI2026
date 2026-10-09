package com.smartplug.app.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KwhFormatTest {
    @Test fun allSixFormats() {
        val v = 1.234567
        assertThat(formatKwhValue(v, 5)).isEqualTo("1.23457")
        assertThat(formatKwhValue(v, 4)).isEqualTo("1.2346")
        assertThat(formatKwhValue(v, 3)).isEqualTo("1.235")
        assertThat(formatKwhValue(v, 2)).isEqualTo("1.23")
        assertThat(formatKwhValue(v, 1)).isEqualTo("1.2")
        assertThat(formatKwhValue(v, 0)).isEqualTo("1")
    }

    @Test fun outOfRangeIsClamped() {
        assertThat(KwhFormat.clamp(9)).isEqualTo(5)
        assertThat(KwhFormat.clamp(-2)).isEqualTo(0)
        assertThat(formatKwhValue(1.5, 9)).isEqualTo("1.50000")
    }

    @Test fun chipPatterns() {
        assertThat(KwhFormat.pattern(5)).isEqualTo("x.xxxxx")
        assertThat(KwhFormat.pattern(1)).isEqualTo("x.x")
        assertThat(KwhFormat.pattern(0)).isEqualTo("x")
    }
}
