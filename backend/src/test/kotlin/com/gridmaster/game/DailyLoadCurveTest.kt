package com.gridmaster.game

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DailyLoadCurve].
 */
class DailyLoadCurveTest {
    @Test
    fun `hourlyMultipliers has 24 values averaging to 1_0`() {
        assertThat(DailyLoadCurve.hourlyMultipliers).hasSize(24)
        val average = DailyLoadCurve.hourlyMultipliers.average()
        assertThat(average).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `multiplier at exact hour boundary matches the hourly sample`() {
        val midnight = DailyLoadCurve.multiplierForGameTimeMinutes(0L)
        assertThat(midnight).isCloseTo(DailyLoadCurve.hourlyMultipliers[0], within(1e-9))

        val noon = DailyLoadCurve.multiplierForGameTimeMinutes(12 * 60L)
        assertThat(noon).isCloseTo(DailyLoadCurve.hourlyMultipliers[12], within(1e-9))
    }

    @Test
    fun `multiplier interpolates between hours`() {
        // 30 minutes past hour 0 should be exactly halfway between hour 0 and hour 1.
        val expected = (DailyLoadCurve.hourlyMultipliers[0] + DailyLoadCurve.hourlyMultipliers[1]) / 2.0
        val actual = DailyLoadCurve.multiplierForGameTimeMinutes(30L)
        assertThat(actual).isCloseTo(expected, within(1e-9))
    }

    @Test
    fun `multiplier wraps past midnight`() {
        // 23:30 should interpolate between hour 23 and hour 0 (wrap-around).
        val expected = (DailyLoadCurve.hourlyMultipliers[23] + DailyLoadCurve.hourlyMultipliers[0]) / 2.0
        val actual = DailyLoadCurve.multiplierForGameTimeMinutes(23 * 60L + 30L)
        assertThat(actual).isCloseTo(expected, within(1e-9))
    }

    @Test
    fun `multiplier handles game time beyond one day and negative wrap`() {
        val dayThree = DailyLoadCurve.multiplierForGameTimeMinutes(2 * DailyLoadCurve.MINUTES_PER_DAY)
        assertThat(dayThree).isCloseTo(DailyLoadCurve.hourlyMultipliers[0], within(1e-9))
    }

    @Test
    fun `evening peak hour is higher than overnight trough`() {
        val eveningPeak = DailyLoadCurve.multiplierForGameTimeMinutes(19 * 60L)
        val overnightTrough = DailyLoadCurve.multiplierForGameTimeMinutes(3 * 60L)
        assertThat(eveningPeak).isGreaterThan(overnightTrough)
    }
}
