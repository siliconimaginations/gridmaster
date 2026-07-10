package com.gridmaster.game

import kotlin.math.pow

/**
 * A lightweight in-game calendar derived purely from elapsed [gameTimeMinutes]
 * (issue #388, extending the hourly [DailyLoadCurve] from #383).
 *
 * ### Anchor assumption
 * Session start (`gameTimeMinutes == 0`) is anchored as an arbitrary **Monday,
 * January 1** of an arbitrary year. This is a simplification documented here
 * rather than derived from any real wall-clock date — Free Play sessions are
 * self-contained and don't need to agree with real-world calendars.
 *
 * ### Simplifications
 * - **No leap years.** Every in-game year is exactly [DAYS_PER_YEAR] (365)
 *   days. Over a single Free Play session (up to ~1 simulated year per
 *   `WORK_PLAN.md` Stage 6) the leap-day error is negligible and not worth the
 *   added complexity.
 * - Month lengths follow the standard (non-leap) Gregorian calendar
 *   (31, 28, 31, 30, ...) purely to give [SeasonalLoadCurve] believable
 *   month boundaries — no other leap-year logic depends on this.
 */
object GameCalendar {
    /** Number of simulated minutes in one in-game day (mirrors [DailyLoadCurve.MINUTES_PER_DAY]). */
    const val MINUTES_PER_DAY: Long = DailyLoadCurve.MINUTES_PER_DAY

    /** Number of days in one in-game week. */
    const val DAYS_PER_WEEK: Int = 7

    /** Number of days in one in-game year (leap years ignored — see class KDoc). */
    const val DAYS_PER_YEAR: Int = 365

    /** Standard (non-leap) Gregorian month lengths, January through December. */
    val monthLengths: IntArray = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    /** Cumulative day count at the start of each month (0-indexed day-of-year offset). */
    val monthStartDayOfYear: IntArray =
        IntArray(12).also { starts ->
            var acc = 0
            for (m in 0 until 12) {
                starts[m] = acc
                acc += monthLengths[m]
            }
        }

    /** Day names, Monday-first to match the day-0 anchor assumption. */
    val dayOfWeekNames: List<String> = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    val monthNames: List<String> =
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Total elapsed in-game days for [gameTimeMinutes] (floor division; handles negative input). */
    fun dayIndex(gameTimeMinutes: Long): Long = Math.floorDiv(gameTimeMinutes, MINUTES_PER_DAY)

    /** 0 = Monday ... 6 = Sunday, per the day-0 anchor assumption. */
    fun dayOfWeek(gameTimeMinutes: Long): Int = Math.floorMod(dayIndex(gameTimeMinutes), DAYS_PER_WEEK.toLong()).toInt()

    /** 0-indexed day within the current in-game year (0..364). */
    fun dayOfYear(gameTimeMinutes: Long): Int = Math.floorMod(dayIndex(gameTimeMinutes), DAYS_PER_YEAR.toLong()).toInt()

    /** 0-indexed in-game year number (year 0 is the year the session started in). */
    fun yearIndex(gameTimeMinutes: Long): Long = Math.floorDiv(dayIndex(gameTimeMinutes), DAYS_PER_YEAR.toLong())

    /** Fractional years elapsed since session start, e.g. 1.5 = halfway through the second year. */
    fun yearsElapsed(gameTimeMinutes: Long): Double = gameTimeMinutes.toDouble() / (MINUTES_PER_DAY * DAYS_PER_YEAR)

    /** 0-indexed month (0 = January .. 11 = December) for [gameTimeMinutes]. */
    fun month(gameTimeMinutes: Long): Int {
        val doy = dayOfYear(gameTimeMinutes)
        var m = 11
        for (i in 0 until 12) {
            if (doy < monthStartDayOfYear[i]) {
                m = i - 1
                break
            }
        }
        return m
    }

    /** 1-indexed day-of-month (1..31) for [gameTimeMinutes]. */
    fun dayOfMonth(gameTimeMinutes: Long): Int = dayOfYear(gameTimeMinutes) - monthStartDayOfYear[month(gameTimeMinutes)] + 1

    /**
     * Fractional day-of-year (e.g. `10.5` = noon on the 11th day of the year),
     * used by [SeasonalLoadCurve] to interpolate smoothly across month
     * boundaries instead of jumping once per simulated month.
     */
    fun dayOfYearFractional(gameTimeMinutes: Long): Double {
        val minuteOfDay = Math.floorMod(gameTimeMinutes, MINUTES_PER_DAY)
        return dayOfYear(gameTimeMinutes) + minuteOfDay / MINUTES_PER_DAY.toDouble()
    }

    /** Human-readable summary, e.g. "Year 2 · Day 41 · Wed · Mar" — matches the issue's HUD suggestion. */
    fun describe(gameTimeMinutes: Long): String {
        val year = yearIndex(gameTimeMinutes) + 1
        val doy = dayOfYear(gameTimeMinutes) + 1
        val dow = dayOfWeekNames[dayOfWeek(gameTimeMinutes)]
        val mon = monthNames[month(gameTimeMinutes)]
        return "Year $year · Day $doy · $dow · $mon"
    }
}

/**
 * Day-of-week demand scalar (issue #388), applied as a flat multiplier for the
 * whole simulated day on top of [DailyLoadCurve]'s hourly shape.
 *
 * Per EIA ("Hourly electricity consumption varies throughout the day and
 * across seasons," Feb 2020) and PJM operational/load-forecast data, U.S.
 * electricity demand is consistently lower on weekends: PJM cites weekend
 * demand running roughly 10 GW below weekday demand against a 140-160 GW
 * system peak (a ~7-10% reduction), and EIA's on-peak/off-peak convention
 * treats all of Saturday/Sunday as off-peak. Applied as a step function
 * (not interpolated) since the effect is a discrete day-type change, not a
 * continuous physical process like the hourly ramp.
 */
object WeeklyLoadCurve {
    /**
     * Raw relative weights, Monday (index 0) through Sunday (index 6).
     * Weekday = 1.00 baseline; Saturday = 0.92; Sunday = 0.88, per the
     * PJM/EIA weekend-reduction figures cited above. Renormalized to average
     * 1.0 by [dayOfWeekMultipliers] before use.
     */
    private val rawDayOfWeekShape = doubleArrayOf(1.00, 1.00, 1.00, 1.00, 1.00, 0.92, 0.88)

    /** [rawDayOfWeekShape] normalized so the 7 values average to exactly 1.0. */
    val dayOfWeekMultipliers: DoubleArray =
        rawDayOfWeekShape.average().let { avg ->
            DoubleArray(rawDayOfWeekShape.size) { i -> rawDayOfWeekShape[i] / avg }
        }

    /** The weekly multiplier in effect for [gameTimeMinutes] (flat for the whole in-game day). */
    fun multiplierForGameTimeMinutes(gameTimeMinutes: Long): Double = dayOfWeekMultipliers[GameCalendar.dayOfWeek(gameTimeMinutes)]
}

/**
 * Monthly seasonal demand scalar (issue #388), applied on top of
 * [DailyLoadCurve] and [WeeklyLoadCurve].
 *
 * Per EIA ("Electricity demand changes in predictable patterns"; "Hourly
 * electricity consumption varies throughout the day and across seasons"):
 * demand peaks in summer (single afternoon air-conditioning peak — used in
 * 87% of U.S. homes), is elevated but less peaky in winter (dual
 * morning/evening heating peaks), and is lowest in the spring/fall "shoulder"
 * seasons (Mar-May, Sep-Nov). EIA's 2024 data point: Lower-48 spring
 * shoulder-season (Mar-May) generation averaged 430.6 GWh vs. 547.4 GWh in
 * peak summer (Jun-Aug) — summer running ~27% above shoulder.
 *
 * Interpolated (unlike [WeeklyLoadCurve]'s step function) between each
 * month's midpoint so demand ramps smoothly across month boundaries instead
 * of jumping once per simulated month, mirroring [DailyLoadCurve]'s
 * hour-to-hour interpolation.
 */
object SeasonalLoadCurve {
    /**
     * Relative monthly demand weights, January (index 0) through December
     * (index 11), calibrated to the ~27% summer-vs-shoulder ratio from the
     * EIA 2024 data point cited above, with winter in between. Starting
     * values from issue #388 — a first-pass calibration, not a precision
     * claim; the qualitative shape and summer/shoulder ratio are the
     * load-bearing facts. Renormalized to average 1.0 by [monthlyMultipliers].
     */
    private val rawMonthlyShape =
        doubleArrayOf(1.02, 1.00, 0.88, 0.83, 0.85, 1.05, 1.18, 1.15, 0.95, 0.85, 0.88, 1.00)

    /** [rawMonthlyShape] normalized so the 12 values average to exactly 1.0. */
    val monthlyMultipliers: DoubleArray =
        rawMonthlyShape.average().let { avg ->
            DoubleArray(rawMonthlyShape.size) { i -> rawMonthlyShape[i] / avg }
        }

    /** Fractional day-of-year at the midpoint of each month, used as interpolation sample points. */
    private val monthMidpoints: DoubleArray =
        DoubleArray(12) { m -> GameCalendar.monthStartDayOfYear[m] + GameCalendar.monthLengths[m] / 2.0 }

    /**
     * The seasonal multiplier for [gameTimeMinutes], linearly interpolated
     * between the two nearest month-midpoint sample points (wrapping across
     * the year boundary, e.g. late December interpolates toward January).
     */
    fun multiplierForGameTimeMinutes(gameTimeMinutes: Long): Double {
        val doy = GameCalendar.dayOfYearFractional(gameTimeMinutes)
        val daysPerYear = GameCalendar.DAYS_PER_YEAR.toDouble()

        // Find the month whose midpoint is the last one <= doy (with wraparound).
        var lowerMonth = 11
        for (m in 0 until 12) {
            if (monthMidpoints[m] <= doy) lowerMonth = m else break
        }
        val upperMonth = (lowerMonth + 1) % 12

        val lowerPoint = monthMidpoints[lowerMonth]
        var upperPoint = monthMidpoints[upperMonth]
        var position = doy
        if (upperMonth == 0) {
            // Wrapping past year-end: project next January's midpoint forward onto a
            // contiguous number line so it's comparable to December's day-of-year values.
            upperPoint += daysPerYear
        }
        if (lowerMonth == 11 && doy < lowerPoint) {
            // Early January (before Jan's own midpoint): this doy actually continues the
            // segment from *last* December's midpoint, so shift it forward by a year too.
            position += daysPerYear
        }

        val span = upperPoint - lowerPoint
        val fraction = if (span > 0) (position - lowerPoint) / span else 0.0
        val lowerValue = monthlyMultipliers[lowerMonth]
        val upperValue = monthlyMultipliers[upperMonth]
        return lowerValue + (upperValue - lowerValue) * fraction
    }
}

/**
 * Compounding year-over-year demand growth (issue #388), modeling secular
 * load growth over long Free Play sessions (Stage 6 exit criterion: "Session
 * runs 1 simulated year without crash").
 *
 * Per EIA's Short-Term Energy Outlook: U.S. electricity consumption grew
 * ~2% in 2024 and is forecast to keep growing at roughly that rate through
 * 2025-2026 (driven substantially by data-center demand), averaging ~1.7%/yr
 * for 2020-2026; EIA's longer-range Annual Energy Outlook projects
 * 0.9-1.6%/yr through 2050. [DEFAULT_ANNUAL_GROWTH_RATE] uses the shorter-term
 * ~2.0%/yr figure as the default.
 *
 * Unlike [WeeklyLoadCurve] and [SeasonalLoadCurve], this multiplier is *not*
 * normalized to average 1.0 over any period — it's an intentional,
 * monotonically increasing multiplier (`(1 + rate) ^ yearsElapsed`), the one
 * layer of the four allowed to change the long-run average load.
 */
object AnnualLoadGrowth {
    /** Default compounding annual growth rate (2.0%/year), per EIA STEO. */
    const val DEFAULT_ANNUAL_GROWTH_RATE: Double = 0.02

    /**
     * The annual-growth multiplier for [gameTimeMinutes], compounding
     * continuously (fractional years) rather than only on year boundaries,
     * so the multiplier increases smoothly tick-to-tick.
     */
    fun multiplierForGameTimeMinutes(
        gameTimeMinutes: Long,
        annualGrowthRate: Double = DEFAULT_ANNUAL_GROWTH_RATE,
    ): Double {
        val yearsElapsed = GameCalendar.yearsElapsed(gameTimeMinutes)
        return (1.0 + annualGrowthRate).pow(yearsElapsed)
    }
}

/**
 * Composes all four multiplicative load-shape layers (issue #388, extending
 * #383's [DailyLoadCurve]):
 *
 * `finalLoadMw = baselineLoadMw × daily × weekly × seasonal × annualGrowth`
 *
 * Each of [DailyLoadCurve], [WeeklyLoadCurve], and [SeasonalLoadCurve] is
 * independently normalized to average 1.0 within its own dimension, so
 * composing them doesn't change the long-run average load beyond the
 * intentional [AnnualLoadGrowth] factor.
 */
object CompositeLoadCurve {
    fun multiplierForGameTimeMinutes(
        gameTimeMinutes: Long,
        annualGrowthRate: Double = AnnualLoadGrowth.DEFAULT_ANNUAL_GROWTH_RATE,
    ): Double =
        DailyLoadCurve.multiplierForGameTimeMinutes(gameTimeMinutes) *
            WeeklyLoadCurve.multiplierForGameTimeMinutes(gameTimeMinutes) *
            SeasonalLoadCurve.multiplierForGameTimeMinutes(gameTimeMinutes) *
            AnnualLoadGrowth.multiplierForGameTimeMinutes(gameTimeMinutes, annualGrowthRate)
}
