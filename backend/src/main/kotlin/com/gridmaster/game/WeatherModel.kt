package com.gridmaster.game

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Discrete weather states used to drive WIND/SOLAR generation (issue #391).
 *
 * States are ordered from clearest to stormiest -- [WeatherSimulator]'s transition
 * table only ever moves between a state and its neighbors in this ordering (e.g.
 * CLEAR can reach PARTLY_CLOUDY but never jumps straight to STORM), matching the
 * "weather doesn't change all at once" persistence behavior described in the
 * wind-energy Markov weather-window literature (see [WeatherSimulator] KDoc for
 * sources).
 *
 * Cloud-cover-percent and wind-speed-m/s ranges are illustrative starting values
 * (per the issue), tunable without touching [WeatherSimulator]'s transition logic.
 */
enum class WeatherState {
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    OVERCAST,
    STORM,
}

/**
 * Static profile for one [WeatherState]: the continuous cloud-cover/wind-speed
 * envelope a [WeatherSimulator] drifts within while in that state, and the range
 * of tick counts ("dwell time") the simulator spends in the state before rolling
 * a transition. Dwell time is expressed in ticks (each tick = [GRID_MINUTES_PER_TICK]
 * simulated minutes) rather than a fixed real-time duration, so persistence behaves
 * consistently regardless of clock speed.
 */
data class WeatherStateProfile(
    val cloudCoverRangePct: ClosedFloatingPointRange<Double>,
    val windSpeedRangeMps: ClosedFloatingPointRange<Double>,
    val minDwellTicks: Int,
    val maxDwellTicks: Int,
)

/**
 * A single weather reading for one region/zone at a point in time.
 *
 * [regionId] defaults to [GLOBAL_WEATHER_REGION_ID] because this issue's scope is
 * a single weather state shared by the whole network -- but carrying a region key
 * from day one means per-region heterogeneous weather (issue's own stated future
 * extension) only requires keying a map of [WeatherSimulator]s by region and
 * looking up the right [WeatherReading] per bus/generator, not a data-model rewrite.
 */
data class WeatherReading(
    val regionId: String = GLOBAL_WEATHER_REGION_ID,
    val state: WeatherState,
    val cloudCoverPct: Double,
    val windSpeedMps: Double,
)

/** Sentinel region id used while weather is uniform across the whole network. */
const val GLOBAL_WEATHER_REGION_ID: String = "global"

/**
 * Per-state cloud-cover / wind-speed envelopes and dwell-time ranges.
 *
 * Illustrative starting values from issue #391 (tunable, not derived from any
 * specific climate dataset): STORM's wind-speed upper bound (28 m/s) intentionally
 * extends a little past [WindGenerationModel.CUT_OUT_MPS] (25 m/s) so severe storms
 * can occasionally push wind turbines into their cut-out shutdown range -- a
 * physically-motivated (if currently independent) tie-in with the event-engine's
 * outage mechanics that the issue's open questions call out as a natural future
 * hook.
 */
private val weatherProfiles: Map<WeatherState, WeatherStateProfile> =
    mapOf(
        WeatherState.CLEAR to
            WeatherStateProfile(
                cloudCoverRangePct = 0.0..10.0,
                windSpeedRangeMps = 2.0..8.0,
                minDwellTicks = 8,
                maxDwellTicks = 30,
            ),
        WeatherState.PARTLY_CLOUDY to
            WeatherStateProfile(
                cloudCoverRangePct = 10.0..40.0,
                windSpeedRangeMps = 3.0..10.0,
                minDwellTicks = 6,
                maxDwellTicks = 20,
            ),
        WeatherState.CLOUDY to
            WeatherStateProfile(
                cloudCoverRangePct = 40.0..75.0,
                windSpeedRangeMps = 4.0..14.0,
                minDwellTicks = 6,
                maxDwellTicks = 18,
            ),
        WeatherState.OVERCAST to
            WeatherStateProfile(
                cloudCoverRangePct = 75.0..100.0,
                windSpeedRangeMps = 5.0..18.0,
                minDwellTicks = 4,
                maxDwellTicks = 14,
            ),
        WeatherState.STORM to
            WeatherStateProfile(
                cloudCoverRangePct = 90.0..100.0,
                windSpeedRangeMps = 15.0..28.0,
                minDwellTicks = 3,
                maxDwellTicks = 10,
            ),
    )

/**
 * Transition weights rolled when a state's dwell countdown reaches zero. Only
 * neighboring states in the [WeatherState] ordering are reachable from any given
 * state (no CLEAR-to-STORM jumps) -- persistence itself comes from the dwell-time
 * countdown, not from a self-transition weight, so these maps only need to cover
 * "where do we go next" and always sum their values to 1.0 conceptually (values
 * are normalized defensively in [WeatherSimulator.rollNextState] regardless).
 */
private val transitionWeights: Map<WeatherState, Map<WeatherState, Double>> =
    mapOf(
        WeatherState.CLEAR to mapOf(WeatherState.PARTLY_CLOUDY to 1.0),
        WeatherState.PARTLY_CLOUDY to
            mapOf(
                WeatherState.CLEAR to 0.5,
                WeatherState.CLOUDY to 0.5,
            ),
        WeatherState.CLOUDY to
            mapOf(
                WeatherState.PARTLY_CLOUDY to 0.45,
                WeatherState.OVERCAST to 0.45,
                WeatherState.STORM to 0.10,
            ),
        WeatherState.OVERCAST to
            mapOf(
                WeatherState.PARTLY_CLOUDY to 0.20,
                WeatherState.CLOUDY to 0.55,
                WeatherState.STORM to 0.25,
            ),
        WeatherState.STORM to
            mapOf(
                WeatherState.CLOUDY to 0.10,
                WeatherState.OVERCAST to 0.90,
            ),
    )

/**
 * Maximum absolute per-tick drift applied to cloud-cover-percent and wind-speed
 * while a [WeatherSimulator] stays in the same [WeatherState] -- a small bounded
 * random walk so the continuous readings move smoothly tick-to-tick instead of
 * re-rolling to an unrelated value within the state's range every tick.
 */
private const val CLOUD_COVER_DRIFT_PCT: Double = 3.0
private const val WIND_SPEED_DRIFT_MPS: Double = 1.0

/**
 * Simulates one region/zone's weather as a first-order Markov chain with
 * persistence (dwell time), plus a continuous cloud-cover/wind-speed random walk
 * within the current [WeatherState].
 *
 * This general approach -- discrete weather states transitioning via a Markov
 * chain with realistic dwell times, used to drive derived quantities like wind
 * speed -- mirrors published multivariate/first-order Markov weather models used
 * for offshore wind O&M weather-window simulation (e.g. "A multivariate Markov
 * Weather Model for O&M Simulation of Offshore Wind Parks"; "First order
 * multivariate Markov chain model for generating annual weather data") and the
 * same Markov-chain approach used for cloud/rain simulation in games.
 *
 * ### Persistence design
 * Rather than rolling a transition every tick (which risks flip-flopping even
 * with a "mostly stay" self-transition weight), each call to [advanceTick]
 * decrements [ticksRemainingInState]; only when it reaches zero does the
 * simulator roll a new state via [transitionWeights] and redraw a fresh dwell
 * time from the new state's [WeatherStateProfile]. This guarantees a state is
 * held for at least [WeatherStateProfile.minDwellTicks] ticks, independent of
 * how often [advanceTick] happens to be called.
 *
 * ### Not thread-safe
 * Instances are per-session, single-writer (the tick loop) state, matching
 * [TickEngineImpl]'s existing pattern for other per-session mutable fields
 * (e.g. `SessionRuntime.baseLoadMw`) -- callers must not share an instance
 * across sessions or call [advanceTick] concurrently.
 */
class WeatherSimulator(
    val regionId: String = GLOBAL_WEATHER_REGION_ID,
    private val random: Random = Random.Default,
) {
    var currentState: WeatherState = WeatherState.CLEAR
        private set

    private var ticksRemainingInState: Int = drawDwellTicks(WeatherState.CLEAR)

    /** Current continuous cloud-cover percent (0-100), initialized to the CLEAR range midpoint. */
    var cloudCoverPct: Double = rangeMidpoint(weatherProfiles.getValue(WeatherState.CLEAR).cloudCoverRangePct)
        private set

    /** Current continuous wind speed in m/s, initialized to the CLEAR range midpoint. */
    var windSpeedMps: Double = rangeMidpoint(weatherProfiles.getValue(WeatherState.CLEAR).windSpeedRangeMps)
        private set

    /**
     * Advance the simulator by one tick: possibly transition to a new
     * [WeatherState] (see class KDoc), then drift [cloudCoverPct] and
     * [windSpeedMps] by a small bounded random step clamped into the
     * (possibly just-changed) current state's ranges.
     */
    fun advanceTick() {
        ticksRemainingInState--
        if (ticksRemainingInState <= 0) {
            currentState = rollNextState(currentState)
            ticksRemainingInState = drawDwellTicks(currentState)
        }
        val profile = weatherProfiles.getValue(currentState)
        cloudCoverPct = drift(cloudCoverPct, profile.cloudCoverRangePct, CLOUD_COVER_DRIFT_PCT)
        windSpeedMps = drift(windSpeedMps, profile.windSpeedRangeMps, WIND_SPEED_DRIFT_MPS)
    }

    /** Current reading, tagged with this simulator's [regionId]. */
    fun currentReading(): WeatherReading =
        WeatherReading(
            regionId = regionId,
            state = currentState,
            cloudCoverPct = cloudCoverPct,
            windSpeedMps = windSpeedMps,
        )

    private fun rollNextState(from: WeatherState): WeatherState {
        val weights = transitionWeights.getValue(from)
        val total = weights.values.sum()
        var roll = random.nextDouble() * total
        for ((state, weight) in weights) {
            roll -= weight
            if (roll <= 0.0) return state
        }
        return weights.keys.last()
    }

    private fun drawDwellTicks(state: WeatherState): Int {
        val profile = weatherProfiles.getValue(state)
        return random.nextInt(profile.minDwellTicks, profile.maxDwellTicks + 1)
    }

    private fun drift(
        current: Double,
        range: ClosedFloatingPointRange<Double>,
        maxStep: Double,
    ): Double {
        val step = (random.nextDouble() * 2.0 - 1.0) * maxStep
        return (current + step).coerceIn(range)
    }

    private fun rangeMidpoint(range: ClosedFloatingPointRange<Double>): Double = (range.start + range.endInclusive) / 2.0
}

/**
 * Solar output model (issue #391): time-of-day clear-sky proxy attenuated by a
 * cloud-cover index derived from NREL cloud-cover/irradiance data.
 *
 * `solarOutputMw = ratedMw x clearSkyFactor(hour) x clearSkyIndex(cloudCoverPct)`
 *
 * Sources:
 * - Simplified sinusoidal daylight window in place of a full solar-position
 *   model (Ineichen-Perez etc., which needs latitude/longitude/turbidity this
 *   game doesn't track).
 * - NREL, "The Influence of Cloud Cover on the Reliability of Satellite-Based
 *   Solar Resource Data" -- cloud-cover/irradiance quadratic fit used for
 *   [clearSkyIndex]; irradiance is only mildly reduced below ~50% cloud cover,
 *   then drops sharply (~67-72% reduction at full overcast).
 * - PVEducation, "Cloud Cover Data".
 */
object SolarGenerationModel {
    /** Fixed daylight window start (issue #391 explicitly defers seasonal day-length to a follow-up). */
    const val SUNRISE_HOUR: Double = 6.0

    /** Fixed daylight window end -- see [SUNRISE_HOUR]. */
    const val SUNSET_HOUR: Double = 18.0

    /**
     * `max(0, sin(pi x (hour - sunrise) / (sunset - sunrise)))` -- zero outside the
     * fixed daylight window, peaking at solar noon, per the issue's simplified
     * clear-sky proxy.
     */
    fun clearSkyFactor(hourOfDay: Double): Double {
        val raw = sin(PI * (hourOfDay - SUNRISE_HOUR) / (SUNSET_HOUR - SUNRISE_HOUR))
        return raw.coerceAtLeast(0.0)
    }

    /**
     * NREL-derived quadratic fit for irradiance attenuation from cloud cover.
     * [cloudCoverPct] is expected in 0-100; the result is coerced to [0, 1] since
     * the raw quadratic can dip slightly negative right at 100% cloud cover.
     */
    fun clearSkyIndex(cloudCoverPct: Double): Double {
        val index = 1.0 - 0.00243 * cloudCoverPct - 0.0000424 * cloudCoverPct.pow(2)
        return index.coerceIn(0.0, 1.0)
    }

    /**
     * Solar generator output in MW for [ratedMw] capacity at [gameTimeMinutes]
     * game-time under [cloudCoverPct] cloud cover. Time-of-day is derived via the
     * same `floorMod`-based minute-of-day approach [GameCalendar] and
     * [DailyLoadCurve] use, rather than re-deriving it independently.
     */
    fun outputMw(
        ratedMw: Double,
        gameTimeMinutes: Long,
        cloudCoverPct: Double,
    ): Double {
        val minuteOfDay = Math.floorMod(gameTimeMinutes, GameCalendar.MINUTES_PER_DAY)
        val hourOfDay = minuteOfDay / 60.0
        val output = ratedMw * clearSkyFactor(hourOfDay) * clearSkyIndex(cloudCoverPct)
        return output.coerceIn(0.0, ratedMw)
    }
}

/**
 * Wind turbine output model (issue #391): standard IEC-style power curve --
 * zero below cut-in, a cubic ramp (power in wind is proportional to the cube of
 * wind speed) between cut-in and rated speed, flat at rated capacity between
 * rated and cut-out speed, and zero again above cut-out (the turbine
 * feathers/shuts down to avoid mechanical damage).
 *
 * Cut-in/rated/cut-out values are typical industry figures, not from a specific
 * turbine spec sheet, per:
 * - "Typical wind turbine power curve" (ResearchGate figure).
 * - "A Critical Review on Wind Turbine Power Curve Modelling Techniques" (2016).
 */
object WindGenerationModel {
    /** Wind speed (m/s) below which the turbine produces no power. */
    const val CUT_IN_MPS: Double = 3.5

    /** Wind speed (m/s) at which the turbine first reaches its rated (nameplate) output. */
    const val RATED_MPS: Double = 13.0

    /** Wind speed (m/s) above which the turbine shuts down to avoid damage. */
    const val CUT_OUT_MPS: Double = 25.0

    /**
     * Wind generator output in MW for [ratedMw] capacity at [windSpeedMps].
     * Below [CUT_IN_MPS] and above [CUT_OUT_MPS] the output is zero; between
     * [CUT_IN_MPS] and [RATED_MPS] it ramps as `(v^3 - cutIn^3) / (rated^3 - cutIn^3)`;
     * between [RATED_MPS] and [CUT_OUT_MPS] it is flat at [ratedMw].
     */
    fun outputMw(
        ratedMw: Double,
        windSpeedMps: Double,
    ): Double =
        when {
            windSpeedMps < CUT_IN_MPS || windSpeedMps > CUT_OUT_MPS -> 0.0
            windSpeedMps >= RATED_MPS -> ratedMw
            else -> {
                val cubeRatio =
                    (windSpeedMps.pow(3) - CUT_IN_MPS.pow(3)) /
                        (RATED_MPS.pow(3) - CUT_IN_MPS.pow(3))
                (ratedMw * cubeRatio).coerceIn(0.0, ratedMw)
            }
        }
}
