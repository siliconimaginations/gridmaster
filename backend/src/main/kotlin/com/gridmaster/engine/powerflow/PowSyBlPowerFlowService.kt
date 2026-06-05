package com.gridmaster.engine.powerflow

import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.network.IidmNetworkMapper
import com.powsybl.iidm.network.Network
import com.powsybl.loadflow.LoadFlow
import com.powsybl.loadflow.LoadFlowParameters
import com.powsybl.loadflow.LoadFlowResult.ComponentResult.Status
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.system.measureTimeMillis

/**
 * PowSyBl-backed implementation of [PowerFlowService].
 *
 * Runs AC or DC load flow via `LoadFlow.run()`, which mutates the [Network]
 * in-place. After the solve, maps the updated network to a [GridNetwork]
 * snapshot and scans for violations.
 *
 * DC is NOT used as an automatic fallback for AC divergence — see design doc §1.
 */
@Service
class PowSyBlPowerFlowService(
    private val mapper: IidmNetworkMapper,
    private val violationScanner: ViolationScanner,
) : PowerFlowService {
    private val log = LoggerFactory.getLogger(PowSyBlPowerFlowService::class.java)

    override fun solve(
        network: Network,
        parameters: PowerFlowParameters,
    ): PowerFlowResult {
        val params = buildPowSyBlParams(parameters)
        var loadFlowResult: com.powsybl.loadflow.LoadFlowResult? = null
        var exception: Exception? = null

        val solveTimeMs =
            measureTimeMillis {
                try {
                    loadFlowResult = LoadFlow.run(network, params)
                } catch (e: Exception) {
                    log.error("PowSyBl LoadFlow threw an unexpected exception", e)
                    exception = e
                }
            }

        if (exception != null || loadFlowResult == null) {
            val snapshot = safeSnapshot(network, parameters)
            return PowerFlowResult(
                status = ConvergenceStatus.FAILED,
                solveMode = parameters.mode,
                iterationCount = 0,
                snapshot = snapshot,
                slackBusIds = emptyList(),
                violations = emptyList(),
                solveTimeMs = solveTimeMs,
            )
        }

        val lfResult = loadFlowResult!!
        val (status, iterationCount, slackBusIds) = parseComponentResults(lfResult)

        if (status == ConvergenceStatus.NETWORK_FAILURE) {
            log.warn("AC power flow did not converge — raising NETWORK_FAILURE")
            val snapshot = safeSnapshot(network, parameters)
            return PowerFlowResult(
                status = ConvergenceStatus.NETWORK_FAILURE,
                solveMode = parameters.mode,
                iterationCount = iterationCount,
                snapshot = snapshot,
                slackBusIds = slackBusIds,
                violations = emptyList(),
                solveTimeMs = solveTimeMs,
            )
        }

        val snapshot = mapper.toGridNetwork(network)
        val violations = violationScanner.scan(snapshot)

        if (violations.isNotEmpty()) {
            log.debug(
                "Power flow found {} violation(s) — {} thermal, {} voltage",
                violations.size,
                violations.count { it is NetworkViolation.ThermalViolation },
                violations.count { it is NetworkViolation.VoltageViolation },
            )
        }

        return PowerFlowResult(
            status = status,
            solveMode = parameters.mode,
            iterationCount = iterationCount,
            snapshot = snapshot,
            slackBusIds = slackBusIds,
            violations = violations,
            solveTimeMs = solveTimeMs,
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildPowSyBlParams(parameters: PowerFlowParameters): LoadFlowParameters {
        val params = LoadFlowParameters()
        params.isDc = (parameters.mode == SolveMode.DC)
        params.isDistributedSlack = parameters.distributedSlack
        params.balanceType =
            when (parameters.balanceType) {
                BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX ->
                    LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX
                BalanceType.PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN ->
                    LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN
                BalanceType.PROPORTIONAL_TO_LOAD ->
                    LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD
            }
        return params
    }

    private data class ComponentSummary(
        val status: ConvergenceStatus,
        val iterationCount: Int,
        val slackBusIds: List<String>,
    )

    private fun parseComponentResults(lfResult: com.powsybl.loadflow.LoadFlowResult): ComponentSummary {
        val components = lfResult.componentResults
        if (components.isEmpty()) {
            return ComponentSummary(ConvergenceStatus.NETWORK_FAILURE, 0, emptyList())
        }

        val allConverged = components.all { it.status == Status.CONVERGED }
        val anyConverged = components.any { it.status == Status.CONVERGED }
        val status =
            when {
                allConverged -> ConvergenceStatus.CONVERGED
                anyConverged -> ConvergenceStatus.PARTIAL
                else -> ConvergenceStatus.NETWORK_FAILURE
            }

        val iterationCount = components.maxOf { it.iterationCount }

        val slackBusIds =
            components.mapNotNull { component ->
                runCatching { component.slackBusId }.getOrNull()
            }

        return ComponentSummary(status, iterationCount, slackBusIds)
    }

    /**
     * Produces a snapshot even when the solve failed/diverged.
     * Voltage and current values will be NaN→null (stale from previous tick).
     */
    private fun safeSnapshot(
        network: Network,
        parameters: PowerFlowParameters,
    ): GridNetwork =
        runCatching { mapper.toGridNetwork(network) }
            .getOrElse {
                GridNetwork(
                    id = network.id,
                    name = network.nameOrId,
                    buses = emptyList(),
                    lines = emptyList(),
                    twoWindingsTransformers = emptyList(),
                    threeWindingsTransformers = emptyList(),
                    generators = emptyList(),
                    loads = emptyList(),
                    shuntCompensators = emptyList(),
                    warnings = listOf("Snapshot unavailable after ${parameters.mode} solve failure"),
                )
            }
}
