package com.gridmaster.engine.dispatch

import java.time.Instant
import kotlin.math.min

/**
 * Core merit-order dispatch algorithm shared by [MeritOrderDispatchService] and [LpDispatchService].
 *
 * For linear costs with box constraints, merit order is the exact LP optimum.
 * Extracting the algorithm here removes duplication and ensures both services
 * produce identical results for the current cost model.
 */
internal fun runMeritOrder(
    generators: List<DispatchableGenerator>,
    totalLoadMw: Double,
    logWarn: (String) -> Unit = {},
    logDebug: (String) -> Unit = {},
): DispatchResult {
    val committed = generators.filter { it.committed }

    // Step 1: all committed generators run at minimum (must-run)
    val dispatch = committed.associateTo(mutableMapOf()) { it.id to it.minActivePowerMw }
    var remaining = totalLoadMw - dispatch.values.sum()

    // Step 2: serve remaining load in ascending marginal cost order
    val sorted = committed.sortedBy { it.marginalCostPerMwh }
    var marginalUnit: DispatchableGenerator? = null
    var marginalCost = 0.0

    for (gen in sorted) {
        if (remaining <= 0.0) break
        val increment = min(gen.maxActivePowerMw - gen.minActivePowerMw, remaining)
        dispatch[gen.id] = gen.minActivePowerMw + increment
        remaining -= increment
        if (increment > 0.0) {
            marginalUnit = gen
            marginalCost = gen.marginalCostPerMwh
        }
    }

    val unserved = maxOf(0.0, remaining)
    if (unserved > 0.0) {
        logWarn("Dispatch: unserved load ${"%.1f".format(unserved)} MW — committed capacity insufficient")
    }

    val totalDispatched = dispatch.values.sum()
    val meritOrder =
        sorted.map { gen ->
            MeritOrderEntry(
                generatorId = gen.id,
                marginalCostPerMwh = gen.marginalCostPerMwh,
                minMw = gen.minActivePowerMw,
                maxMw = gen.maxActivePowerMw,
                dispatchedMw = dispatch[gen.id] ?: 0.0,
                isMarginalUnit = gen.id == marginalUnit?.id,
            )
        }

    logDebug(
        "Merit order: load=${"%.1f".format(totalLoadMw)} MW dispatched=${"%.1f".format(totalDispatched)} MW " +
            "unserved=${"%.1f".format(unserved)} MW SMC=${"%.2f".format(marginalCost)} £/MWh",
    )

    return DispatchResult(
        targets = dispatch.map { (id, mw) -> GeneratorTarget(id, mw) },
        meritOrder = meritOrder,
        totalLoadMw = totalLoadMw,
        totalDispatchedMw = totalDispatched,
        systemMarginalCostPerMwh = marginalCost,
        unservedLoadMw = unserved,
        dispatchedAt = Instant.now(),
    )
}
