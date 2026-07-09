package com.gridmaster.game

/**
 * Typical 24-hour system load shape used to scale bus demand over simulated
 * grid-time, closing issue #383.
 *
 * The shape is a normalized representative utility daily load curve
 * reflecting the well-documented pattern described by the U.S. Energy
 * Information Administration (EIA, "Demand for electricity changes through
 * the day", Today in Energy, and the "morning ramp" / evening-peak pattern
 * seen across ISO New England, PJM, and ENTSO-E hourly demand data): an
 * overnight trough around 03:00 to 04:00, a steep morning ramp from
 * approximately 05:00 to 08:00 as commercial and industrial demand comes
 * online, a midday plateau, and the daily peak in the early evening
 * (approximately 18:00 to 19:00) driven by residential demand overlapping
 * with the tail end of commercial activity.
 *
 * [rawHourlyShape] holds relative demand weights per hour (not required to
 * average to 1.0); [hourlyMultipliers] is the same shape normalized so the
 * 24 values average to exactly 1.0, making it safe to multiply directly
 * against a bus's baseline (flat) load without changing the daily energy
 * total.
 */
object DailyLoadCurve {
    /** Number of simulated minutes in one in-game day. */
    const val MINUTES_PER_DAY: Long = 1_440L

    /** Number of hourly sample points in the curve. */
    const val HOURS_PER_DAY: Int = 24

    /**
     * Relative hourly demand weights, hour-of-day 0 (00:00-01:00) through 23
     * (23:00-00:00), sourced from the qualitative EIA / ISO-NE / PJM daily
     * load-shape pattern described above. Values are relative, not
     * percentages of a specific rated peak.
     */
    private val rawHourlyShape =
        doubleArrayOf(
            0.75,
            0.68,
            0.63,
            0.60,
            0.60,
            0.64,
            0.72,
            0.85,
            0.95,
            1.00,
            1.03,
            1.05,
            1.05,
            1.04,
            1.03,
            1.03,
            1.05,
            1.10,
            1.18,
            1.20,
            1.15,
            1.05,
            0.93,
            0.83,
        )

    /**
     * [rawHourlyShape] normalized so its 24 values average to exactly 1.0.
     * Multiplying a load's baseline active power by the value for the
     * current hour therefore leaves the daily energy total unchanged versus
     * a flat load, while redistributing it across the day.
     */
    val hourlyMultipliers: DoubleArray =
        rawHourlyShape.average().let { avg ->
            DoubleArray(rawHourlyShape.size) { i -> rawHourlyShape[i] / avg }
        }

    /**
     * The load multiplier for [gameTimeMinutes], linearly interpolated between
     * the two nearest hourly sample points so the multiplier changes smoothly
     * tick-to-tick rather than jumping once per simulated hour.
     *
     * [gameTimeMinutes] is treated modulo [MINUTES_PER_DAY]; negative values
     * wrap correctly.
     */
    fun multiplierForGameTimeMinutes(gameTimeMinutes: Long): Double {
        val minuteOfDay = ((gameTimeMinutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val hourFloat = minuteOfDay / 60.0
        val hourIndex = hourFloat.toInt().coerceIn(0, HOURS_PER_DAY - 1)
        val nextHourIndex = (hourIndex + 1) % HOURS_PER_DAY
        val fraction = hourFloat - hourIndex
        val current = hourlyMultipliers[hourIndex]
        val next = hourlyMultipliers[nextHourIndex]
        return current + (next - current) * fraction
    }
}
