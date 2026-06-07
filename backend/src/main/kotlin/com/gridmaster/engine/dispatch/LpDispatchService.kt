package com.gridmaster.engine.dispatch

import com.google.ortools.linearsolver.MPSolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * LP-based economic dispatch using OR-Tools GLOP.
 *
 * Solves the linear program:
 * ```
 *   minimise   Σ cost[g] * p[g]
 *   subject to Σ p[g] = totalLoadMw                   (power balance)
 *              min[g] <= p[g] <= max[g]  ∀ committed g (capacity bounds)
 *              p[g] = 0                  ∀ uncommitted g
 * ```
 *
 * For linear costs + box constraints this LP is provably equivalent to merit order.
 * The GLOP formulation is the correct foundation for future extensions:
 * piecewise-linear cost curves, ramp limits, or reserve constraints.
 *
 * Called by [MeritOrderDispatchService] when [DispatchParameters.mode] == [DispatchMode.LP].
 */
@Service
class LpDispatchService {
    private val log = LoggerFactory.getLogger(LpDispatchService::class.java)

    /**
     * Run LP economic dispatch.
     *
     * @throws IllegalStateException if GLOP fails to find an optimal solution.
     */
    fun economicDispatch(
        generators: List<DispatchableGenerator>,
        totalLoadMw: Double,
        @Suppress("UNUSED_PARAMETER") parameters: DispatchParameters,
    ): DispatchResult {
        val committed = generators.filter { it.committed }
        if (committed.isEmpty()) {
            return emptyResult(totalLoadMw)
        }

        val solver =
            MPSolver.createSolver("GLOP")
                ?: error("OR-Tools GLOP solver not available — ensure OR-Tools native libraries are loaded")

        // ── Variables: p[g] ∈ [min, max] for each committed generator ────────
        val vars =
            committed.associateWith { gen ->
                solver.makeNumVar(gen.minActivePowerMw, gen.maxActivePowerMw, gen.id)
            }

        // ── Objective: minimise Σ cost[g] * p[g] ─────────────────────────────
        val objective = solver.objective()
        vars.forEach { (gen, v) -> objective.setCoefficient(v, gen.marginalCostPerMwh) }
        objective.setMinimization()

        // ── Power balance: Σ p[g] = totalLoadMw ──────────────────────────────
        val mustRunMw = committed.sumOf { it.minActivePowerMw }
        val maxCapacityMw = committed.sumOf { it.maxActivePowerMw }
        val feasibleLoad = totalLoadMw.coerceIn(mustRunMw, maxCapacityMw)

        val balance = solver.makeConstraint(feasibleLoad, feasibleLoad, "balance")
        vars.values.forEach { balance.setCoefficient(it, 1.0) }

        // ── Solve ─────────────────────────────────────────────────────────────
        val status = solver.solve()
        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
            log.warn("LP dispatch solver returned non-optimal status: {} — falling back to merit order", status)
            return runMeritOrder(generators, totalLoadMw, logDebug = { log.debug(it) })
        }

        val unservedLoad = (totalLoadMw - feasibleLoad).coerceAtLeast(0.0)
        val targets =
            committed.map { gen ->
                GeneratorTarget(gen.id, vars.getValue(gen).solutionValue())
            }

        // ── Merit order table from solution ───────────────────────────────────
        val dispatched =
            targets.associate { it.generatorId to it.targetMw }
        val totalDispatched = targets.sumOf { it.targetMw }
        val smc =
            committed
                .filter { (dispatched[it.id] ?: 0.0) > it.minActivePowerMw }
                .maxByOrNull { it.marginalCostPerMwh }?.marginalCostPerMwh ?: 0.0

        val meritOrder =
            committed
                .sortedBy { it.marginalCostPerMwh }
                .map { gen ->
                    MeritOrderEntry(
                        generatorId = gen.id,
                        marginalCostPerMwh = gen.marginalCostPerMwh,
                        minMw = gen.minActivePowerMw,
                        maxMw = gen.maxActivePowerMw,
                        dispatchedMw = dispatched[gen.id] ?: 0.0,
                        isMarginalUnit = gen.marginalCostPerMwh == smc,
                    )
                }

        log.debug(
            "LP dispatch: load={:.1f} MW dispatched={:.1f} MW smc={:.2f} £/MWh status={}",
            totalLoadMw,
            totalDispatched,
            smc,
            status,
        )

        return DispatchResult(
            targets = targets,
            meritOrder = meritOrder,
            totalLoadMw = totalLoadMw,
            totalDispatchedMw = totalDispatched,
            systemMarginalCostPerMwh = smc,
            unservedLoadMw = unservedLoad,
            dispatchedAt = Instant.now(),
        )
    }

    private fun emptyResult(totalLoadMw: Double) =
        DispatchResult(
            targets = emptyList(),
            meritOrder = emptyList(),
            totalLoadMw = totalLoadMw,
            totalDispatchedMw = 0.0,
            systemMarginalCostPerMwh = 0.0,
            unservedLoadMw = totalLoadMw,
            dispatchedAt = Instant.now(),
        )
}
