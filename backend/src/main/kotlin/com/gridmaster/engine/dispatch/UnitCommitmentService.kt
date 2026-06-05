package com.gridmaster.engine.dispatch

/**
 * Day-ahead unit commitment service.
 *
 * Determines which generators to commit or decommit over a 24-hour window
 * using a greedy heuristic that respects minimum up/down time constraints
 * and a reserve margin requirement.
 *
 * Note: the design doc specifies OR-Tools MIP as the preferred solver.
 * The greedy implementation here is a pragmatic first pass that satisfies
 * the tutorial use case (small networks, <20 generators). The OR-Tools
 * MIP path can be added in a future PR once the OR-Tools dependency is
 * integrated and tested on CI.
 */
interface UnitCommitmentService {
    /**
     * Produce a 24-hour commitment and dispatch schedule for [generators]
     * given [forecast] and [parameters].
     *
     * Returns a [UcResult] with per-hour commitment sets and dispatch targets.
     * [UcResult.feasible] is false when total capacity is insufficient to
     * meet peak demand plus reserve at any hour.
     */
    fun commit(
        generators: List<DispatchableGenerator>,
        forecast: LoadForecast,
        parameters: DispatchParameters = DispatchParameters(),
    ): UcResult
}
