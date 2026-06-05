package com.gridmaster.engine.dispatch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

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
        parameters: DispatchParameters,
    ): DispatchResult {
        // TODO: replace with OR-Tools LP solver when cost curves / ramp limits are added (#28)
        // For linear costs + box constraints, merit order is the exact LP optimum.
        val committed = generators.filter { it.committed }
        val dispatch = committed.associateTo(mutableMapOf()) { it.id to it.minActivePowerMw }
        var remaining = totalLoadMw - dispatch.values.sum()

        val sorted = committed.sortedBy { it.marginalCostPerMwh }
        var marginalCost = 0.0
        var marginalGenId: String? = null

        for (gen in sorted) {
            if (remaining <= 0.0) break
            val increment = minOf(gen.maxActivePowerMw - gen.minActivePowerMw, remaining)
            dispatch[gen.id] = gen.minActivePowerMw + increment
            remaining -= increment
            if (increment > 0.0) {
                marginalCost = gen.marginalCostPerMwh
                marginalGenId = gen.id
            }
        }

        val unserved = maxOf(0.0, remaining)
        val totalDispatched = dispatch.values.sum()

        log.debug("LP dispatch: load={:.1f} MW dispatched={:.1f} MW SMC={:.2f}", totalLoadMw, totalDispatched, marginalCost)

        return DispatchResult(
            targets = dispatch.map { (id, mw) -> GeneratorTarget(id, mw) },
            meritOrder =
                sorted.map { gen ->
                    MeritOrderEntry(
                        generatorId = gen.id,
                        marginalCostPerMwh = gen.marginalCostPerMwh,
                        minMw = gen.minActivePowerMw,
                        maxMw = gen.maxActivePowerMw,
                        dispatchedMw = dispatch[gen.id] ?: 0.0,
                        isMarginalUnit = gen.id == marginalGenId,
                    )
                },
            totalLoadMw = totalLoadMw,
            totalDispatchedMw = totalDispatched,
            systemMarginalCostPerMwh = marginalCost,
            unservedLoadMw = unserved,
            dispatchedAt = Instant.now(),
        )
    }
}
