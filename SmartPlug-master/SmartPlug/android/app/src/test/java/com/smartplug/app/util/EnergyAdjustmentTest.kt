package com.smartplug.app.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnergyAdjustmentTest {
    @Test fun zeroPercentIsIdentity() {
        assertThat(applyEnergyAdjustment(1.234, 0.0)).isEqualTo(1.234)
        assertThat(EnergyAdjustment.isAdjusted(0.0)).isFalse()
    }

    @Test fun plusAndMinusPercent() {
        assertThat(applyEnergyAdjustment(1.0, 3.25)).isWithin(1e-9).of(1.0325)
        assertThat(applyEnergyAdjustment(2.0, -10.0)).isWithin(1e-9).of(1.8)
        assertThat(EnergyAdjustment.isAdjusted(3.25)).isTrue()
    }

    @Test fun limitsAreClampedAndFactorStaysPositive() {
        assertThat(applyEnergyAdjustment(1.0, 300.0)).isWithin(1e-9).of(4.0)
        assertThat(applyEnergyAdjustment(1.0, 999.0)).isWithin(1e-9).of(4.0)
        assertThat(applyEnergyAdjustment(1.0, -99.99)).isWithin(1e-9).of(0.0001)
        assertThat(EnergyAdjustment.factor(-500.0)).isGreaterThan(0.0)
    }

    @Test fun roundsToHundredthOfAPercent() {
        assertThat(EnergyAdjustment.normalize(1.234)).isEqualTo(1.23)
        assertThat(EnergyAdjustment.normalize(1.235)).isEqualTo(1.24)
        assertThat(EnergyAdjustment.normalize(-0.004)).isEqualTo(0.0)
    }

    @Test fun manualInputValidation() {
        assertThat(EnergyAdjustment.parseInput("3.25")).isEqualTo(3.25)
        assertThat(EnergyAdjustment.parseInput("-10,5")).isEqualTo(-10.5)
        assertThat(EnergyAdjustment.parseInput("1000")).isEqualTo(300.0)
        assertThat(EnergyAdjustment.parseInput("-500")).isEqualTo(-99.99)
        assertThat(EnergyAdjustment.parseInput("")).isNull()
        assertThat(EnergyAdjustment.parseInput("-")).isNull()
        assertThat(EnergyAdjustment.parseInput(".")).isNull()
        assertThat(EnergyAdjustment.parseInput("abc")).isNull()
        assertThat(EnergyAdjustment.parseInput("NaN")).isNull()
        assertThat(EnergyAdjustment.parseInput("1e999")).isNull()
        assertThat(EnergyAdjustment.parseInput("99999999999999999999")).isEqualTo(300.0)
    }

    @Test fun sliderMapsZeroToTheMiddle() {
        assertThat(EnergyAdjustment.percentToSlider(0.0)).isEqualTo(0.5f)
        assertThat(EnergyAdjustment.sliderToPercent(0.5f)).isEqualTo(0.0)
        assertThat(EnergyAdjustment.sliderToPercent(0f)).isEqualTo(-99.99)
        assertThat(EnergyAdjustment.sliderToPercent(1f)).isEqualTo(300.0)
        assertThat(EnergyAdjustment.percentToSlider(300.0)).isEqualTo(1f)
        assertThat(EnergyAdjustment.percentToSlider(-99.99)).isEqualTo(0f)
    }

    @Test fun sliderRoundTripIsStable() {
        listOf(-50.0, -10.0, 3.25, 150.0).forEach { p ->
            assertThat(EnergyAdjustment.sliderToPercent(EnergyAdjustment.percentToSlider(p))).isWithin(0.02).of(p)
        }
    }

    @Test fun zeroAndTinyEnergy() {
        assertThat(applyEnergyAdjustment(0.0, 250.0)).isEqualTo(0.0)
        assertThat(applyEnergyAdjustment(0.00001, 100.0)).isWithin(1e-12).of(0.00002)
    }

    @Test fun percentLabel() {
        assertThat(EnergyAdjustment.formatPercent(3.25)).isEqualTo("+3.25%")
        assertThat(EnergyAdjustment.formatPercent(-10.0)).isEqualTo("-10.00%")
        assertThat(EnergyAdjustment.formatPercent(0.0)).isEqualTo("+0.00%")
    }
}
