package com.gridmaster.engine.dispatch

/**
 * Economic dispatch and congestion redispatch service.
 *
 * [economicDispatch] runs merit-order or LP dispatch for the current operating hour.
 * [congestionRedispatch] relieves thermal violations via generator-pair shifts.
 * [securityConstrainedDispatch] composes both, iterating until no violations remain
 * or the iteration limit is reached.
 */
interface DispatchService {
    /**
     * Run economic dispatch for [totalLoadMw] using the given [generators].
     *
     * Returns a [DispatchResult] with per-generator targets, merit order table,
     * and unserved load if committed capacity is insufficient.
     */
    fun economicDispatch(
        generators: List<DispatchableGenerator>,
        totalLoadMw: Double,
        parameters: DispatchParameters = DispatchParameters(),
    ): DispatchResult

    /**
     * Relieve thermal violations by shifting generation between pairs of generators
     * using pre-computed Generation Shift Keys ([gsk]).
     *
     * [gsk] maps generatorId → branchId → sensitivity (MW output change per MW branch flow change).
     * Returns updated targets and any violations that could not be relieved.
     */
    fun congestionRedispatch(
        currentTargets: List<GeneratorTarget>,
        generators: List<DispatchableGenerator>,
        overloadedBranchIds: List<String>,
        gsk: Map<String, Map<String, Double>>,
    ): CongestionRedispatchResult

    /**
     * Security-constrained dispatch: run [economicDispatch], apply targets to network
     * via [applyAndSolve], and iterate [congestionRedispatch] until no violations remain
     * or [maxIterations] is reached.
     *
     * [applyAndSolve] is a callback that applies targets to the network and returns
     * the IDs of any overloaded branches (empty if no violations).
     * [gsk] — see [congestionRedispatch].
     */
    fun securityConstrainedDispatch(
        generators: List<DispatchableGenerator>,
        totalLoadMw: Double,
        parameters: DispatchParameters,
        gsk: Map<String, Map<String, Double>>,
        applyAndSolve: (List<GeneratorTarget>) -> List<String>,
        maxIterations: Int = 5,
    ): DispatchResult
}
