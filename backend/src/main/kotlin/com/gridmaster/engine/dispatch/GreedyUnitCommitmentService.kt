package com.gridmaster.engine.dispatch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.system.measureTimeMillis

/**
 * Greedy unit commitment for a 24-hour day-ahead window.
 *
 * Algorithm for each hour (processed chronologically, hour 0..23):
 * 1. Start with all generators from the previous hour's commitment.
 * 2. Commit cheapest generators (by startup cost, then marginal cost) until
 *    total capacity ≥ load × (1 + reserveMarginFraction).
 * 3. Decommit expensive generators if capacity remains sufficient after removal,
 *    subject to [DispatchableGenerator.minDownTimeHours].
 * 4. Run economic dispatch on the committed set for the hour's load.
 *
 * This greedy approach is correct and fast for small networks (<50 generators).
 * OR-Tools MIP is deferred to a future PR (issue #28) for larger instances.
 */
@Service
class GreedyUnitCommitmentService(
    private val dispatchService: DispatchService,
) : UnitCommitmentService {
    private val log = LoggerFactory.getLogger(GreedyUnitCommitmentService::class.java)

    override fun commit(
        generators: List<DispatchableGenerator>,
        forecast: LoadForecast,
        parameters: DispatchParameters,
    ): UcResult {
        val hourlySchedule = mutableListOf<UcHourSchedule>()
        var totalStartupCost = 0.0
        var totalOperatingCost = 0.0
        var feasible = true

        // Track current commitment state; start from the initial committed set
        val commitmentState =
            generators.associateTo(mutableMapOf()) { it.id to it.committed }
        // Track hours since last state change for min up/down time enforcement
        val hoursInCurrentState = generators.associateTo(mutableMapOf()) { it.id to 0 }

        var solveTimeMs = 0L
        measureTimeMillis {
            for (hour in 0 until 24) {
                val loadMw = forecast.hourlyLoadMw[hour]
                val requiredCapacity = loadMw * (1.0 + parameters.reserveMarginFraction)

                // Commit generators until capacity requirement is met
                val uncommitted =
                    generators.filter { !commitmentState.getValue(it.id) }
                        .sortedWith(compareBy({ it.startupCostGbp }, { it.marginalCostPerMwh }))

                for (gen in uncommitted) {
                    val currentCapacity = committedCapacity(generators, commitmentState)
                    if (currentCapacity >= requiredCapacity) break
                    val hoursDown = hoursInCurrentState[gen.id] ?: 0
                    if (hoursDown < gen.minDownTimeHours) continue // min down time not met
                    commitmentState[gen.id] = true
                    hoursInCurrentState[gen.id] = 0
                    totalStartupCost += gen.startupCostGbp
                    log.debug("UC hour {}: committing {} (startup £{:.0f})", hour, gen.id, gen.startupCostGbp)
                }

                // Try decommitting expensive generators if surplus capacity allows
                val committed =
                    generators.filter { commitmentState.getValue(it.id) }
                        .sortedByDescending { it.marginalCostPerMwh }

                for (gen in committed) {
                    val capacityWithout =
                        committedCapacity(generators, commitmentState) - gen.maxActivePowerMw
                    if (capacityWithout < requiredCapacity) continue // insufficient surplus without this unit; try others
                    val hoursUp = hoursInCurrentState[gen.id] ?: 0
                    if (hoursUp < gen.minUpTimeHours) continue // min up time not met
                    commitmentState[gen.id] = false
                    hoursInCurrentState[gen.id] = 0
                    log.debug("UC hour {}: decommitting {}", hour, gen.id)
                }

                // Increment hours in current state for all generators
                generators.forEach { gen ->
                    hoursInCurrentState[gen.id] = (hoursInCurrentState[gen.id] ?: 0) + 1
                }

                // Run economic dispatch for this hour
                val hourGenerators =
                    generators.map { it.copy(committed = commitmentState.getValue(it.id)) }
                val dispatchResult =
                    dispatchService.economicDispatch(hourGenerators, loadMw, parameters)

                if (dispatchResult.unservedLoadMw > 0.0) {
                    log.warn("UC hour {}: unserved load {:.1f} MW", hour, dispatchResult.unservedLoadMw)
                    feasible = false
                }

                val reserveMw =
                    committedCapacity(generators, commitmentState) - dispatchResult.totalDispatchedMw
                totalOperatingCost +=
                    dispatchResult.targets.sumOf { target ->
                        val gen = generators.find { it.id == target.generatorId }
                        (gen?.marginalCostPerMwh ?: 0.0) * target.targetMw
                    }

                hourlySchedule +=
                    UcHourSchedule(
                        hour = hour,
                        committedGeneratorIds =
                            commitmentState.filterValues { it }.keys.toSet(),
                        targets = dispatchResult.targets,
                        estimatedLoadMw = loadMw,
                        reserveMarginMw = reserveMw,
                    )
            }
        }.also { solveTimeMs = it }

        log.info(
            "UC complete: feasible={} startupCost=£{:.0f} operatingCost=£{:.0f} time={}ms",
            feasible,
            totalStartupCost,
            totalOperatingCost,
            solveTimeMs,
        )

        return UcResult(
            hourlySchedule = hourlySchedule,
            totalStartupCostGbp = totalStartupCost,
            totalOperatingCostGbp = totalOperatingCost,
            feasible = feasible,
            solveTimeMs = solveTimeMs,
        )
    }

    private fun committedCapacity(
        generators: List<DispatchableGenerator>,
        commitmentState: Map<String, Boolean>,
    ): Double =
        generators.sumOf { gen ->
            if (commitmentState.getValue(gen.id)) gen.maxActivePowerMw else 0.0
        }
}
