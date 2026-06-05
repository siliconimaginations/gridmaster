package com.gridmaster.engine.dispatch

import java.time.Instant

// ---------------------------------------------------------------------------
// Parameters
// ---------------------------------------------------------------------------

/**
 * Parameters for a single economic dispatch run.
 *
 * [mode] — [DispatchMode.MERIT_ORDER] (default) or [DispatchMode.LP].
 * [securityConstrained] — if true, congestion redispatch is applied after merit order.
 * [reserveMarginFraction] — minimum spinning reserve as a fraction of total load.
 *   Default 0.20 (20 %).
 */
data class DispatchParameters(
    val mode: DispatchMode = DispatchMode.MERIT_ORDER,
    val securityConstrained: Boolean = false,
    val reserveMarginFraction: Double = 0.20,
) {
    init {
        require(reserveMarginFraction >= 0.0) { "reserveMarginFraction must be >= 0" }
    }
}

enum class DispatchMode { MERIT_ORDER, LP }

// ---------------------------------------------------------------------------
// Domain inputs
// ---------------------------------------------------------------------------

/**
 * Dispatchable generator visible to the dispatch service.
 *
 * [startupCostGbp] — one-time startup cost used by UC.
 * [minUpTimeHours] / [minDownTimeHours] — minimum time committed/de-committed (UC).
 */
data class DispatchableGenerator(
    val id: String,
    val name: String,
    val committed: Boolean,
    val minActivePowerMw: Double,
    val maxActivePowerMw: Double,
    val currentActivePowerMw: Double,
    val marginalCostPerMwh: Double,
    val startupCostGbp: Double = 0.0,
    val minUpTimeHours: Int = 0,
    val minDownTimeHours: Int = 0,
)

/**
 * Hourly load forecast for a 24-hour UC window.
 * Must have exactly 24 entries (one per hour starting at [startHour]).
 */
data class LoadForecast(
    val hourlyLoadMw: List<Double>,
    val startHour: Instant,
) {
    init {
        require(hourlyLoadMw.size == 24) { "LoadForecast must have exactly 24 hourly values" }
        require(hourlyLoadMw.all { it >= 0.0 }) { "Hourly load values must be non-negative" }
    }
}

// ---------------------------------------------------------------------------
// Economic dispatch results
// ---------------------------------------------------------------------------

/** Target active power output for a single generator after dispatch. */
data class GeneratorTarget(
    val generatorId: String,
    val targetMw: Double,
)

/**
 * Result of one merit-order or LP economic dispatch run.
 *
 * [meritOrder] — generators sorted by marginal cost (ascending), with their
 *   dispatched MW. Populated for the player-facing dispatch panel.
 * [systemMarginalCostPerMwh] — marginal cost of the last dispatched unit.
 * [unservedLoadMw] — load that could not be served (committed capacity
 *   insufficient). Non-zero triggers a load-shedding event in the game engine.
 * [congestionCostGbp] — additional cost incurred by redispatch (0 if no congestion).
 */
data class DispatchResult(
    val targets: List<GeneratorTarget>,
    val meritOrder: List<MeritOrderEntry>,
    val totalLoadMw: Double,
    val totalDispatchedMw: Double,
    val systemMarginalCostPerMwh: Double,
    val unservedLoadMw: Double,
    val congestionCostGbp: Double = 0.0,
    val dispatchedAt: Instant,
)

data class MeritOrderEntry(
    val generatorId: String,
    val marginalCostPerMwh: Double,
    val minMw: Double,
    val maxMw: Double,
    val dispatchedMw: Double,
    val isMarginalUnit: Boolean,
)

// ---------------------------------------------------------------------------
// Unit commitment results
// ---------------------------------------------------------------------------

/**
 * 24-hour unit commitment schedule.
 *
 * [hourlySchedule] — one [UcHourSchedule] per hour (index 0 = first hour).
 * [totalStartupCostGbp] — sum of startup costs for all commitments in the window.
 * [feasible] — false if total capacity is insufficient even with all units committed.
 */
data class UcResult(
    val hourlySchedule: List<UcHourSchedule>,
    val totalStartupCostGbp: Double,
    val totalOperatingCostGbp: Double,
    val feasible: Boolean,
    val solveTimeMs: Long,
)

/** Commitment and dispatch targets for one hour in the UC schedule. */
data class UcHourSchedule(
    val hour: Int,
    val committedGeneratorIds: Set<String>,
    val targets: List<GeneratorTarget>,
    val estimatedLoadMw: Double,
    val reserveMarginMw: Double,
)

// ---------------------------------------------------------------------------
// Congestion redispatch
// ---------------------------------------------------------------------------

/**
 * A single generator-pair redispatch action to relieve a thermal overload.
 *
 * One generator's output is increased while another's is decreased by the same
 * MW, guided by Generation Shift Keys (GSKs) for the overloaded branch.
 */
data class RedispatchAction(
    val increaseGeneratorId: String,
    val decreaseGeneratorId: String,
    val shiftMw: Double,
    val targetBranchId: String,
    val estimatedReliefMw: Double,
)

data class CongestionRedispatchResult(
    val actions: List<RedispatchAction>,
    val updatedTargets: List<GeneratorTarget>,
    val remainingViolations: List<String>,
    val additionalCostGbp: Double,
)
