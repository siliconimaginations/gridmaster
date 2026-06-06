package com.gridmaster.engine.dispatch

import com.google.ortools.linearsolver.MPSolver
import com.google.ortools.linearsolver.MPVariable
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import kotlin.system.measureTimeMillis

/**
 * MIP unit commitment using OR-Tools SCIP.
 *
 * Solves the 24-hour day-ahead commitment problem as a Mixed Integer Program:
 *
 * ```
 *   Variables
 *     y[g][h] ∈ {0,1}  — generator g committed in hour h
 *     p[g][h] ≥ 0      — dispatch of generator g in hour h (MW)
 *     s[g][h] ∈ {0,1}  — generator g starts up in hour h (y[g][h] - y[g][h-1] = 1)
 *
 *   Minimise
 *     Σ startupCost[g] * s[g][h]  +  Σ marginalCost[g] * p[g][h]
 *
 *   Subject to
 *     Power balance:  Σ_g p[g][h] = load[h]                        ∀ h
 *     Capacity lb:    p[g][h] ≥ min[g] * y[g][h]                   ∀ g, h
 *     Capacity ub:    p[g][h] ≤ max[g] * y[g][h]                   ∀ g, h
 *     Reserve:        Σ_g max[g] * y[g][h] ≥ load[h] * (1+reserve)  ∀ h
 *     Startup:        s[g][h] ≥ y[g][h] - y[g][h-1]                ∀ g, h>0
 *     Min up time:    Σ_{t=h}^{h+minUp-1} y[g][t] ≥ minUp * s[g][h] ∀ g,h (if minUpTimeHours>0)
 *     Min down time:  Σ_{t=h}^{h+minDown-1} (1-y[g][t]) ≥ minDown * (y[g][h-1]-y[g][h])
 * ```
 *
 * MIP is used for networks up to [MIP_GENERATOR_LIMIT] generators, where the solve time is
 * acceptable and the optimal solution is desirable. For larger networks, MIP solve time grows
 * exponentially, so the greedy heuristic is used instead to keep scheduling tractable.
 *
 * This implementation is the `@Primary` [UnitCommitmentService] bean; [GreedyUnitCommitmentService]
 * remains available as a `@Qualifier("greedy")` fallback for large networks and on solver failure.
 */
@Primary
@Service
class MipUnitCommitmentService(
    /** Greedy heuristic fallback — used for large networks and on MIP solver failure. */
    private val greedy: GreedyUnitCommitmentService,
) : UnitCommitmentService {
    private val log = LoggerFactory.getLogger(MipUnitCommitmentService::class.java)

    companion object {
        /**
         * Maximum number of generators for which the MIP solver is used.
         * Networks above this size fall back to the greedy heuristic: MIP solve time grows
         * exponentially with generator count, making it impractical for large instances.
         */
        const val MIP_GENERATOR_LIMIT = 20

        /** SCIP time limit per solve in milliseconds. */
        const val SOLVER_TIME_LIMIT_MS = 10_000L
    }

    override fun commit(
        generators: List<DispatchableGenerator>,
        forecast: LoadForecast,
        parameters: DispatchParameters,
    ): UcResult {
        if (generators.size > MIP_GENERATOR_LIMIT) {
            log.debug(
                "MipUC: {} generators > limit {} — using greedy heuristic for tractability",
                generators.size,
                MIP_GENERATOR_LIMIT,
            )
            return greedy.commit(generators, forecast, parameters)
        }
        return solveMip(generators, forecast, parameters)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MIP formulation
    // ─────────────────────────────────────────────────────────────────────────

    private fun solveMip(
        generators: List<DispatchableGenerator>,
        forecast: LoadForecast,
        parameters: DispatchParameters,
    ): UcResult {
        val g = generators.size
        val h = 24

        log.info("MipUC: solving {}-generator 24-hour UC via SCIP", g)

        var solveTimeMs = 0L
        lateinit var result: UcResult

        measureTimeMillis {
            val solver =
                MPSolver.createSolver("SCIP")
                    ?: run {
                        log.warn("MipUC: SCIP solver not available — falling back to greedy heuristic")
                        return greedy.commit(generators, forecast, parameters)
                    }

            solver.setTimeLimit(SOLVER_TIME_LIMIT_MS)

            // ── Decision variables ────────────────────────────────────────────
            // y[gen][hour] — committed
            val y: Array<Array<MPVariable>> =
                Array(g) { gi ->
                    Array(h) { hi -> solver.makeBoolVar("y_${generators[gi].id}_$hi") }
                }
            // p[gen][hour] — dispatch (MW)
            val p: Array<Array<MPVariable>> =
                Array(g) { gi ->
                    val gen = generators[gi]
                    Array(h) { hi -> solver.makeNumVar(0.0, gen.maxActivePowerMw, "p_${gen.id}_$hi") }
                }
            // s[gen][hour] — startup indicator
            val s: Array<Array<MPVariable>> =
                Array(g) { gi ->
                    Array(h) { hi -> solver.makeBoolVar("s_${generators[gi].id}_$hi") }
                }

            // ── Objective ─────────────────────────────────────────────────────
            val obj = solver.objective()
            for (gi in 0 until g) {
                val gen = generators[gi]
                for (hi in 0 until h) {
                    obj.setCoefficient(s[gi][hi], gen.startupCostGbp)
                    obj.setCoefficient(p[gi][hi], gen.marginalCostPerMwh)
                }
            }
            obj.setMinimization()

            for (hi in 0 until h) {
                val loadMw = forecast.hourlyLoadMw[hi]
                val maxCapacity = generators.sumOf { it.maxActivePowerMw }
                val feasibleLoad = loadMw.coerceAtMost(maxCapacity)

                // ── Power balance ─────────────────────────────────────────────
                val balance = solver.makeConstraint(feasibleLoad, feasibleLoad, "balance_$hi")
                for (gi in 0 until g) balance.setCoefficient(p[gi][hi], 1.0)

                // ── Reserve margin ────────────────────────────────────────────
                val reserveRequired = loadMw * (1.0 + parameters.reserveMarginFraction)
                val reserve = solver.makeConstraint(reserveRequired, Double.MAX_VALUE, "reserve_$hi")
                for (gi in 0 until g) reserve.setCoefficient(y[gi][hi], generators[gi].maxActivePowerMw)

                for (gi in 0 until g) {
                    val gen = generators[gi]

                    // ── Capacity bounds: p ∈ [min*y, max*y] ──────────────────
                    // p[g][h] >= min[g] * y[g][h]
                    val lb = solver.makeConstraint(0.0, Double.MAX_VALUE, "lb_${gi}_$hi")
                    lb.setCoefficient(p[gi][hi], 1.0)
                    lb.setCoefficient(y[gi][hi], -gen.minActivePowerMw)

                    // p[g][h] <= max[g] * y[g][h]
                    val ub = solver.makeConstraint(-Double.MAX_VALUE, 0.0, "ub_${gi}_$hi")
                    ub.setCoefficient(p[gi][hi], 1.0)
                    ub.setCoefficient(y[gi][hi], -gen.maxActivePowerMw)

                    // ── Startup indicator: s[g][h] >= y[g][h] - y[g][h-1] ────
                    if (hi == 0) {
                        // s[g][0] >= y[g][0] - init  →  s[g][0] - y[g][0] >= -init
                        val init = if (gen.committed) 1.0 else 0.0
                        val sc = solver.makeConstraint(-init, Double.MAX_VALUE, "startup_${gi}_0")
                        sc.setCoefficient(s[gi][0], 1.0)
                        sc.setCoefficient(y[gi][0], -1.0)
                    } else {
                        // s[g][h] >= y[g][h] - y[g][h-1]  →  s[g][h] - y[g][h] + y[g][h-1] >= 0
                        val sc = solver.makeConstraint(0.0, Double.MAX_VALUE, "startup_${gi}_$hi")
                        sc.setCoefficient(s[gi][hi], 1.0)
                        sc.setCoefficient(y[gi][hi], -1.0)
                        sc.setCoefficient(y[gi][hi - 1], 1.0)
                    }

                    // ── Min up time ───────────────────────────────────────────
                    // Include hi=0: s[g][0] already encodes the startup from initial state.
                    if (gen.minUpTimeHours > 1) {
                        val endH = minOf(hi + gen.minUpTimeHours - 1, h - 1)
                        // Σ_{t=hi}^{endH} y[g][t] >= minUp * s[g][hi]
                        val minUp = solver.makeConstraint(0.0, Double.MAX_VALUE, "minup_${gi}_$hi")
                        for (t in hi..endH) minUp.setCoefficient(y[gi][t], 1.0)
                        minUp.setCoefficient(s[gi][hi], -(endH - hi + 1).toDouble())
                    }

                    // ── Min down time ─────────────────────────────────────────
                    // Constraint: D * (1 + y[hi] - y[hi-1]) >= Σ_{t=hi}^{endH} y[t]
                    // For hi=0 the initial committed state substitutes for y[hi-1].
                    if (gen.minDownTimeHours > 1) {
                        val endH = minOf(hi + gen.minDownTimeHours - 1, h - 1)
                        val windowSize = (endH - hi + 1).toDouble()
                        if (hi == 0) {
                            val init = if (gen.committed) 1.0 else 0.0
                            // lb = windowSize*(init-1); y[hi-1] is a constant so it shifts the bound
                            val minDown =
                                solver.makeConstraint(windowSize * (init - 1.0), Double.MAX_VALUE, "mindown_${gi}_0")
                            for (t in 1..endH) minDown.setCoefficient(y[gi][t], -1.0)
                            minDown.setCoefficient(y[gi][0], windowSize)
                        } else {
                            val minDown =
                                solver.makeConstraint(-windowSize, Double.MAX_VALUE, "mindown_${gi}_$hi")
                            for (t in hi..endH) minDown.setCoefficient(y[gi][t], -1.0)
                            minDown.setCoefficient(y[gi][hi - 1], -windowSize)
                            minDown.setCoefficient(y[gi][hi], windowSize)
                        }
                    }
                }
            }

            // ── Solve ─────────────────────────────────────────────────────────
            val status = solver.solve()
            val feasible =
                status == MPSolver.ResultStatus.OPTIMAL || status == MPSolver.ResultStatus.FEASIBLE

            if (!feasible) {
                log.warn("MipUC: SCIP returned {} — falling back to greedy", status)
                result = greedy.commit(generators, forecast, parameters)
            } else {
                // ── Extract solution ──────────────────────────────────────────────
                val hourlySchedule = mutableListOf<UcHourSchedule>()
                var totalStartupCost = 0.0
                var totalOperatingCost = 0.0

                for (hi in 0 until h) {
                    val committedIds = mutableSetOf<String>()
                    val targets = mutableListOf<GeneratorTarget>()

                    for (gi in 0 until g) {
                        val gen = generators[gi]
                        val committed = y[gi][hi].solutionValue() > 0.5
                        val dispatch = p[gi][hi].solutionValue()
                        val started = s[gi][hi].solutionValue() > 0.5

                        if (committed) committedIds.add(gen.id)
                        if (dispatch > 0.0) targets.add(GeneratorTarget(gen.id, dispatch))
                        if (started) totalStartupCost += gen.startupCostGbp
                        totalOperatingCost += dispatch * gen.marginalCostPerMwh
                    }

                    val totalDispatched = targets.sumOf { it.targetMw }
                    val reserveMw =
                        committedIds.sumOf { id ->
                            generators.find { it.id == id }?.maxActivePowerMw ?: 0.0
                        } - totalDispatched

                    hourlySchedule +=
                        UcHourSchedule(
                            hour = hi,
                            committedGeneratorIds = committedIds,
                            targets = targets,
                            estimatedLoadMw = forecast.hourlyLoadMw[hi],
                            reserveMarginMw = reserveMw,
                        )
                }

                result =
                    UcResult(
                        hourlySchedule = hourlySchedule,
                        totalStartupCostGbp = totalStartupCost,
                        totalOperatingCostGbp = totalOperatingCost,
                        feasible = true,
                        solveTimeMs = 0, // patched below
                    )
            } // end if feasible
        }.also { solveTimeMs = it }

        return result.copy(solveTimeMs = solveTimeMs)
    }
}
