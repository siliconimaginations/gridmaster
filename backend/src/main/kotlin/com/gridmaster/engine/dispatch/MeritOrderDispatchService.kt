package com.gridmaster.engine.dispatch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.abs
import kotlin.math.min

/**
 * Merit-order economic dispatch and sensitivity-based congestion redispatch.
 *
 * Merit order algorithm:
 * 1. All committed generators run at [DispatchableGenerator.minActivePowerMw] (must-run).
 * 2. Remaining load is served by dispatching generators in ascending marginalCostPerMwh order,
 *    up to each generator's [DispatchableGenerator.maxActivePowerMw].
 * 3. The last generator to receive incremental dispatch is the marginal unit.
 *
 * LP mode delegates to [LpDispatchService] when [DispatchParameters.mode] == LP.
 */
@Service
class MeritOrderDispatchService(
    private val lpDispatch: LpDispatchService,
) : DispatchService {
    private val log = LoggerFactory.getLogger(MeritOrderDispatchService::class.java)

    // -------------------------------------------------------------------------
    // Economic dispatch
    // -------------------------------------------------------------------------

    override fun economicDispatch(
        generators: List<DispatchableGenerator>,
        totalLoadMw: Double,
        parameters: DispatchParameters,
    ): DispatchResult {
        if (parameters.mode == DispatchMode.LP) {
            return lpDispatch.economicDispatch(generators, totalLoadMw, parameters)
        }
        return meritOrderDispatch(generators, totalLoadMw, parameters)
    }

    private fun meritOrderDispatch(
        generators: List<DispatchableGenerator>,
        totalLoadMw: Double,
        parameters: DispatchParameters,
    ): DispatchResult {
        val committed = generators.filter { it.committed }

        // Step 1: all committed generators run at minimum (must-run)
        val dispatch = committed.associateTo(mutableMapOf()) { it.id to it.minActivePowerMw }
        var remaining = totalLoadMw - dispatch.values.sum()

        // Step 2: serve remaining load in merit order
        val sorted = committed.sortedBy { it.marginalCostPerMwh }
        var marginalUnit: DispatchableGenerator? = null
        var marginalCost = 0.0

        for (gen in sorted) {
            if (remaining <= 0.0) break
            val headroom = gen.maxActivePowerMw - gen.minActivePowerMw
            val increment = min(headroom, remaining)
            dispatch[gen.id] = gen.minActivePowerMw + increment
            remaining -= increment
            if (increment > 0.0) {
                marginalUnit = gen
                marginalCost = gen.marginalCostPerMwh
            }
        }

        val unserved = maxOf(0.0, remaining)
        if (unserved > 0.0) {
            log.warn("Dispatch: unserved load {:.1f} MW — committed capacity insufficient", unserved)
        }

        val totalDispatched = dispatch.values.sum()
        val meritOrder = buildMeritOrderTable(sorted, dispatch, marginalUnit)

        log.debug(
            "Merit order dispatch: load={:.1f} MW dispatched={:.1f} MW unserved={:.1f} MW SMC={:.2f} £/MWh",
            totalLoadMw,
            totalDispatched,
            unserved,
            marginalCost,
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

    private fun buildMeritOrderTable(
        sorted: List<DispatchableGenerator>,
        dispatch: Map<String, Double>,
        marginalUnit: DispatchableGenerator?,
    ): List<MeritOrderEntry> =
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

    // -------------------------------------------------------------------------
    // Congestion redispatch
    // -------------------------------------------------------------------------

    override fun congestionRedispatch(
        currentTargets: List<GeneratorTarget>,
        generators: List<DispatchableGenerator>,
        overloadedBranchIds: List<String>,
        gsk: Map<String, Map<String, Double>>,
    ): CongestionRedispatchResult {
        val targetMap = currentTargets.associateTo(mutableMapOf()) { it.generatorId to it.targetMw }
        val genMap = generators.filter { it.committed }.associateBy { it.id }
        val actions = mutableListOf<RedispatchAction>()
        val remainingViolations = mutableListOf<String>()
        var additionalCost = 0.0

        for (branchId in overloadedBranchIds) {
            val action = findBestRedispatchPair(branchId, targetMap, genMap, gsk)
            if (action == null) {
                log.warn("No feasible redispatch pair for branch {}", branchId)
                remainingViolations += branchId
                continue
            }
            // Apply shift
            targetMap[action.increaseGeneratorId] =
                (targetMap[action.increaseGeneratorId] ?: 0.0) + action.shiftMw
            targetMap[action.decreaseGeneratorId] =
                (targetMap[action.decreaseGeneratorId] ?: 0.0) - action.shiftMw

            val incGen = genMap[action.increaseGeneratorId]
            val decGen = genMap[action.decreaseGeneratorId]
            if (incGen != null && decGen != null) {
                additionalCost +=
                    action.shiftMw * (incGen.marginalCostPerMwh - decGen.marginalCostPerMwh)
            }
            actions += action
            log.debug(
                "Redispatch: branch={} inc={} dec={} shift={:.1f} MW relief={:.2f} MW",
                branchId,
                action.increaseGeneratorId,
                action.decreaseGeneratorId,
                action.shiftMw,
                action.estimatedReliefMw,
            )
        }

        return CongestionRedispatchResult(
            actions = actions,
            updatedTargets = targetMap.map { (id, mw) -> GeneratorTarget(id, mw) },
            remainingViolations = remainingViolations,
            additionalCostGbp = additionalCost,
        )
    }

    /**
     * Find the generator pair with the highest combined GSK sensitivity for [branchId].
     * One generator increases output (positive sensitivity) and one decreases (negative).
     * Returns null if no feasible pair exists.
     */
    private fun findBestRedispatchPair(
        branchId: String,
        targetMap: Map<String, Double>,
        genMap: Map<String, DispatchableGenerator>,
        gsk: Map<String, Map<String, Double>>,
    ): RedispatchAction? {
        // Collect generators with non-zero GSK for this branch
        data class GskEntry(val genId: String, val sensitivity: Double, val gen: DispatchableGenerator)

        val entries =
            genMap.keys.mapNotNull { genId ->
                val sens = gsk[genId]?.get(branchId) ?: 0.0
                if (abs(sens) < 1e-6) return@mapNotNull null
                val gen = genMap[genId] ?: return@mapNotNull null
                GskEntry(genId, sens, gen)
            }

        // To relieve an overload (reduce branch flow):
        // - Increase generation where GSK < 0 (counter-flow: more output → less branch flow)
        // - Decrease generation where GSK > 0 (pro-flow: less output → less branch flow)
        val increaseCandidates =
            entries.filter { e ->
                e.sensitivity < 0 &&
                    (targetMap[e.genId] ?: 0.0) < e.gen.maxActivePowerMw - 1e-3
            }
        val decreaseCandidates =
            entries.filter { e ->
                e.sensitivity > 0 &&
                    (targetMap[e.genId] ?: 0.0) > e.gen.minActivePowerMw + 1e-3
            }

        if (increaseCandidates.isEmpty() || decreaseCandidates.isEmpty()) return null

        // Maximise flow reduction potential: dec.sensitivity - inc.sensitivity (both terms positive)
        val best =
            increaseCandidates.flatMap { inc ->
                decreaseCandidates.map { dec -> inc to dec }
            }.maxByOrNull { (inc, dec) -> dec.sensitivity - inc.sensitivity }
                ?: return null

        val (inc, dec) = best
        val combinedSensitivity = dec.sensitivity - inc.sensitivity // always positive
        if (combinedSensitivity < 1e-6) return null

        // Shift by the maximum MW possible given generator headroom on each side
        val incHeadroom = (inc.gen.maxActivePowerMw - (targetMap[inc.genId] ?: 0.0))
        val decHeadroom = ((targetMap[dec.genId] ?: 0.0) - dec.gen.minActivePowerMw)
        val shiftMw = min(incHeadroom, decHeadroom).coerceAtLeast(0.0)
        val estimatedReliefMw = shiftMw * combinedSensitivity

        return RedispatchAction(
            increaseGeneratorId = inc.genId,
            decreaseGeneratorId = dec.genId,
            shiftMw = shiftMw,
            targetBranchId = branchId,
            estimatedReliefMw = estimatedReliefMw,
        )
    }

    // -------------------------------------------------------------------------
    // Security-constrained dispatch
    // -------------------------------------------------------------------------

    override fun securityConstrainedDispatch(
        generators: List<DispatchableGenerator>,
        totalLoadMw: Double,
        parameters: DispatchParameters,
        gsk: Map<String, Map<String, Double>>,
        applyAndSolve: (List<GeneratorTarget>) -> List<String>,
        maxIterations: Int,
    ): DispatchResult {
        var result = economicDispatch(generators, totalLoadMw, parameters)
        var totalCongestionCost = 0.0

        repeat(maxIterations) { iteration ->
            val violations = applyAndSolve(result.targets)
            if (violations.isEmpty()) {
                log.debug("SCED converged after {} iteration(s)", iteration + 1)
                return result.copy(congestionCostGbp = totalCongestionCost)
            }

            log.debug("SCED iteration {}: {} branch violation(s)", iteration + 1, violations.size)
            val redispatch =
                congestionRedispatch(result.targets, generators, violations, gsk)
            totalCongestionCost += redispatch.additionalCostGbp
            result = result.copy(targets = redispatch.updatedTargets)
        }

        log.warn("SCED reached max iterations ({}); violations may remain", maxIterations)
        return result.copy(congestionCostGbp = totalCongestionCost)
    }
}
