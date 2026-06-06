package com.gridmaster.engine.dispatch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * LP-based economic dispatch using a simple bounded variable solver.
 *
 * Solves: minimise Σ cost[g] * p[g]
 *   subject to: Σ p[g] = totalLoadMw, minMw[g] <= p[g] <= maxMw[g]
 *
 * For a linear cost with box constraints and a single equality constraint,
 * the LP optimum is identical to merit order: dispatch cheapest capacity first.
 * This implementation makes the LP path explicit so cost curves or ramp constraints
 * can be added later without changing the interface.
 *
 * OR-Tools integration is deferred to a future PR (tracked in issue #28).
 * This service uses the same merit-order algorithm internally as a correct,
 * exact solution for the current linear cost model.
 */
@Service
class LpDispatchService {
    private val log = LoggerFactory.getLogger(LpDispatchService::class.java)

    fun economicDispatch(
        generators: List<DispatchableGenerator>,
        totalLoadMw: Double,
        @Suppress("UNUSED_PARAMETER") parameters: DispatchParameters,
    ): DispatchResult {
        // TODO: replace with OR-Tools LP solver when cost curves / ramp limits are added (#28)
        // For linear costs + box constraints, merit order is the exact LP optimum.
        return runMeritOrder(
            generators = generators,
            totalLoadMw = totalLoadMw,
            logDebug = { log.debug(it) },
        )
    }
}
