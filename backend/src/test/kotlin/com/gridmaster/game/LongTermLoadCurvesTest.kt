package com.gridmaster.game

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

private const val MIN_PER_DAY = GameCalendar.MINUTES_PER_DAY

/**
 * Unit tests for [GameCalendar], [WeeklyLoadCurve], [SeasonalLoadCurve],
 * [AnnualLoadGrowth], and [CompositeLoadCurve] (issue #388).
 */
class LongTermLoadCurvesTest {
    // -------------------------------------------------------------------------
    // GameCalendar
    // -------------------------------------------------------------------------

    @Test
    fun `day 0 is anchored as Monday, January 1, year 1`() {
        assertThat(GameCalendar.dayOfWeek(0L)).isEqualTo(0) // Monday
        assertThat(GameCalendar.month(0L)).isEqualTo(0) // January
        assertThat(GameCalendar.dayOfMonth(0L)).isEqualTo(1)
        assertThat(GameCalendar.yearIndex(0L)).isEqualTo(0L)
    }

    @Test
    fun `dayOfWeek cycles through a 7-day week`() {
        val days = (0 until 14).map { GameCalendar.dayOfWeek(it * MIN_PER_DAY) }
        assertThat(days).isEqualTo(listOf(0, 1, 2, 3, 4, 5, 6, 0, 1, 2, 3, 4, 5, 6))
    }

    @Test
    fun `yearIndex increments after 365 in-game days`() {
        assertThat(GameCalendar.yearIndex(364 * MIN_PER_DAY)).isEqualTo(0L)
        assertThat(GameCalendar.yearIndex(365 * MIN_PER_DAY)).isEqualTo(1L)
    }

    @Test
    fun `yearsElapsed is fractional and continuous`() {
        val halfYear = GameCalendar.yearsElapsed((GameCalendar.DAYS_PER_YEAR * MIN_PER_DAY) / 2)
        assertThat(halfYear).isCloseTo(0.5, within(1e-9))
    }

    @Test
    fun `month and dayOfMonth match standard month boundaries`() {
        assertThat(GameCalendar.month(31 * MIN_PER_DAY)).isEqualTo(1) // Feb 1
        assertThat(GameCalendar.dayOfMonth(31 * MIN_PER_DAY)).isEqualTo(1)
        assertThat(GameCalendar.month(364 * MIN_PER_DAY)).isEqualTo(11) // Dec 31
        assertThat(GameCalendar.dayOfMonth(364 * MIN_PER_DAY)).isEqualTo(31)
    }

    @Test
    fun `describe produces a human-readable calendar summary`() {
        val summary = GameCalendar.describe(40 * MIN_PER_DAY)
        assertThat(summary).contains("Year 1").contains("Day 41")
    }

    // -------------------------------------------------------------------------
    // WeeklyLoadCurve
    // -------------------------------------------------------------------------

    @Test
    fun `dayOfWeekMultipliers has 7 values averaging to 1_0`() {
        assertThat(WeeklyLoadCurve.dayOfWeekMultipliers).hasSize(7)
        assertThat(WeeklyLoadCurve.dayOfWeekMultipliers.average()).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `weekday multiplier is higher than weekend`() {
        val monday = WeeklyLoadCurve.multiplierForGameTimeMinutes(0L)
        val saturday = WeeklyLoadCurve.multiplierForGameTimeMinutes(5 * MIN_PER_DAY)
        val sunday = WeeklyLoadCurve.multiplierForGameTimeMinutes(6 * MIN_PER_DAY)
        assertThat(monday).isGreaterThan(saturday)
        assertThat(saturday).isGreaterThan(sunday)
    }

    // -------------------------------------------------------------------------
    // SeasonalLoadCurve
    // -------------------------------------------------------------------------

    @Test
    fun `monthlyMultipliers has 12 values averaging to 1_0`() {
        assertThat(SeasonalLoadCurve.monthlyMultipliers).hasSize(12)
        assertThat(SeasonalLoadCurve.monthlyMultipliers.average()).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `summer midpoint multiplier exceeds shoulder season midpoint multiplier`() {
        val julMidpoint = GameCalendar.monthStartDayOfYear[6] + GameCalendar.monthLengths[6] / 2
        val aprMidpoint = GameCalendar.monthStartDayOfYear[3] + GameCalendar.monthLengths[3] / 2
        val summer = SeasonalLoadCurve.multiplierForGameTimeMinutes(julMidpoint * MIN_PER_DAY)
        val shoulder = SeasonalLoadCurve.multiplierForGameTimeMinutes(aprMidpoint * MIN_PER_DAY)
        assertThat(summer).isGreaterThan(shoulder)
    }

    @Test
    fun `seasonal multiplier at a month midpoint matches the raw monthly sample`() {
        // January has an odd length (31 days), so its exact midpoint falls on a half-day
        // (day 15.5); compute the midpoint in minutes directly to land on it exactly,
        // rather than truncating to a whole day first.
        val janMidpointMinutes =
            GameCalendar.monthStartDayOfYear[0] * MIN_PER_DAY + (GameCalendar.monthLengths[0] * MIN_PER_DAY) / 2
        val actual = SeasonalLoadCurve.multiplierForGameTimeMinutes(janMidpointMinutes)
        assertThat(actual).isCloseTo(SeasonalLoadCurve.monthlyMultipliers[0], within(1e-9))
    }

    @Test
    fun `seasonal multiplier interpolates smoothly across the year-end boundary`() {
        // A day just before year-end and a day just after should both be close to
        // the interpolated value between December's and January's multipliers,
        // not jump discontinuously.
        val lastDay = SeasonalLoadCurve.multiplierForGameTimeMinutes(364 * MIN_PER_DAY)
        val firstDay = SeasonalLoadCurve.multiplierForGameTimeMinutes(365 * MIN_PER_DAY)
        val decValue = SeasonalLoadCurve.monthlyMultipliers[11]
        val janValue = SeasonalLoadCurve.monthlyMultipliers[0]
        val lo = minOf(decValue, janValue)
        val hi = maxOf(decValue, janValue)
        assertThat(lastDay).isBetween(lo, hi)
        assertThat(firstDay).isBetween(lo, hi)
    }

    // -------------------------------------------------------------------------
    // AnnualLoadGrowth
    // -------------------------------------------------------------------------

    @Test
    fun `annual growth multiplier is 1_0 at session start`() {
        assertThat(AnnualLoadGrowth.multiplierForGameTimeMinutes(0L)).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `annual growth multiplier compounds by the growth rate after one full year`() {
        val oneYearMinutes = GameCalendar.DAYS_PER_YEAR * MIN_PER_DAY
        val multiplier = AnnualLoadGrowth.multiplierForGameTimeMinutes(oneYearMinutes, annualGrowthRate = 0.02)
        assertThat(multiplier).isCloseTo(1.02, within(1e-9))
    }

    @Test
    fun `annual growth multiplier increases monotonically over time`() {
        val early = AnnualLoadGrowth.multiplierForGameTimeMinutes(10 * MIN_PER_DAY)
        val later = AnnualLoadGrowth.multiplierForGameTimeMinutes(200 * MIN_PER_DAY)
        assertThat(later).isGreaterThan(early)
    }

    // -------------------------------------------------------------------------
    // CompositeLoadCurve
    // -------------------------------------------------------------------------

    @Test
    fun `composite multiplier is the product of all four layers`() {
        val t = 40 * MIN_PER_DAY + 90L
        val expected =
            DailyLoadCurve.multiplierForGameTimeMinutes(t) *
                WeeklyLoadCurve.multiplierForGameTimeMinutes(t) *
                SeasonalLoadCurve.multiplierForGameTimeMinutes(t) *
                AnnualLoadGrowth.multiplierForGameTimeMinutes(t)
        assertThat(CompositeLoadCurve.multiplierForGameTimeMinutes(t)).isCloseTo(expected, within(1e-9))
    }

    @Test
    fun `composite multiplier at session start equals just the daily multiplier`() {
        val actual = CompositeLoadCurve.multiplierForGameTimeMinutes(0L)
        val expected =
            DailyLoadCurve.multiplierForGameTimeMinutes(0L) *
                WeeklyLoadCurve.multiplierForGameTimeMinutes(0L) *
                SeasonalLoadCurve.multiplierForGameTimeMinutes(0L)
        assertThat(actual).isCloseTo(expected, within(1e-9))
    }
}
