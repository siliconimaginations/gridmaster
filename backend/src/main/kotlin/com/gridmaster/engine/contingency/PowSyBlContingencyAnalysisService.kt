package com.gridmaster.engine.contingency

import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.network.IidmNetworkMapper
import com.gridmaster.engine.powerflow.EquipmentType
import com.gridmaster.engine.powerflow.PowerFlowParameters
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.SolveMode
import com.gridmaster.engine.powerflow.ViolationSeverity
import com.gridmaster.engine.powerflow.ViolationThresholds
import com.powsybl.iidm.network.Network
import com.powsybl.iidm.network.VariantManagerConstants
import com.powsybl.security.LimitViolationType
import com.powsybl.security.PostContingencyComputationStatus
import com.powsybl.security.SecurityAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.system.measureTimeMillis

/**
 * PowSyBl-backed implementation of [ContingencyAnalysisService].
 *
 * Async execution model:
 * - [triggerAsync] sends a run request into a [Channel.CONFLATED] channel.
 *   Conflated means only the latest pending request is kept — natural debouncing.
 * - A background coroutine consumes requests and executes analysis runs serially.
 * - Results are stored in [cache] and readable via [latestResult] immediately.
 *
 * DC pre-screening:
 * - When [ContingencyAnalysisParameters.dcPreScreening] is true, each contingency
 *   is first evaluated with a DC power flow using PowSyBl network variants.
 * - Only contingencies with DC violations are escalated to full AC SecurityAnalysis.
 *   This reduces AC solve count by typically 80–90%.
 */
@Service
class PowSyBlContingencyAnalysisService(
    private val mapper: IidmNetworkMapper,
    private val powerFlowService: PowerFlowService,
    private val cache: ContingencyAnalysisCache = ContingencyAnalysisCache(),
    private val thresholds: ViolationThresholds = ViolationThresholds(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : ContingencyAnalysisService {
    private val log = LoggerFactory.getLogger(PowSyBlContingencyAnalysisService::class.java)

    // CONFLATED channel — only the latest pending request is kept (debouncing).
    private val triggerChannel = Channel<RunRequest>(Channel.CONFLATED)

    init {
        // Background consumer — runs analyses serially as requests arrive.
        scope.launch {
            triggerChannel.consumeEach { request ->
                runAnalysis(request)
            }
        }
    }

    override fun triggerAsync(
        network: Network,
        parameters: ContingencyAnalysisParameters,
    ) {
        triggerChannel.trySend(RunRequest(network, parameters))
    }

    override fun latestResult(): ContingencyAnalysisResult? = cache.latest()

    override fun buildN1Contingencies(network: GridNetwork): List<Contingency> = ContingencyBuilder.buildN1(network)

    /** Cancels the background consumer. Call when the game session ends. */
    fun shutdown() {
        scope.cancel()
        triggerChannel.close()
    }

    // -------------------------------------------------------------------------
    // Analysis execution
    // -------------------------------------------------------------------------

    private suspend fun runAnalysis(request: RunRequest) {
        val (network, parameters) = request
        log.info("Starting contingency analysis run")

        // Compute snapshot once — used for contingency list building and base case check.
        val snapshot = mapper.toGridNetwork(network)

        val contingencies = parameters.contingencies.ifEmpty { ContingencyBuilder.buildN1(snapshot) }

        if (contingencies.isEmpty()) {
            log.info("No contingencies to analyse")
            return
        }

        val baseCaseSecure = checkBaseCaseSecure(snapshot)
        var analysisTimeMs = 0L
        val contingencyResults: List<ContingencyResult>
        val preScreenedCount: Int
        val fullAcCount: Int

        analysisTimeMs =
            measureTimeMillis {
                if (parameters.dcPreScreening) {
                    val (screened, acCandidates) = dcPreScreen(network, contingencies, parameters)
                    preScreenedCount = screened.size
                    fullAcCount = acCandidates.size
                    contingencyResults =
                        if (acCandidates.isEmpty()) {
                            screened
                        } else {
                            screened + runAcSecurityAnalysis(network, acCandidates, parameters)
                        }
                } else {
                    preScreenedCount = 0
                    fullAcCount = contingencies.size
                    contingencyResults = runAcSecurityAnalysis(network, contingencies, parameters)
                }
            }

        val criticalContingencies =
            contingencyResults
                .filter { it.worstViolationSeverity == ViolationSeverity.CRITICAL }
                .map { it.contingency.id }

        val result =
            ContingencyAnalysisResult(
                baseCaseSecure = baseCaseSecure,
                contingencyResults = contingencyResults,
                criticalContingencies = criticalContingencies,
                analysisTimeMs = analysisTimeMs,
                completedAt = Instant.now(),
                preScreenedContingenciesCount = preScreenedCount,
                fullAcContingenciesCount = fullAcCount,
            )

        cache.update(result)

        log.info(
            "Contingency analysis complete: {} contingencies, {} critical, {}ms " +
                "(DC pre-screened: {}, full AC: {})",
            contingencies.size,
            criticalContingencies.size,
            analysisTimeMs,
            preScreenedCount,
            fullAcCount,
        )
    }

    // -------------------------------------------------------------------------
    // DC pre-screening
    // -------------------------------------------------------------------------

    /**
     * Runs a DC power flow for each contingency using PowSyBl network variants.
     * Returns a pair of (secure/pre-screened results, contingencies needing AC).
     */
    private suspend fun dcPreScreen(
        network: Network,
        contingencies: List<Contingency>,
        parameters: ContingencyAnalysisParameters,
    ): Pair<List<ContingencyResult>, List<Contingency>> {
        val secureResults = mutableListOf<ContingencyResult>()
        val needsAc = mutableListOf<Contingency>()

        for (contingency in contingencies) {
            val variantId = "dc-screen-${contingency.id}"
            try {
                network.variantManager.cloneVariant(
                    VariantManagerConstants.INITIAL_VARIANT_ID,
                    variantId,
                    true,
                )
                network.variantManager.setWorkingVariant(variantId)
                applyContingencyToNetwork(network, contingency)

                val dcResult =
                    powerFlowService.solve(
                        network,
                        PowerFlowParameters(mode = SolveMode.DC),
                    )

                val hasViolations = dcResult.violations.isNotEmpty()
                if (hasViolations) {
                    needsAc += contingency
                } else {
                    secureResults +=
                        ContingencyResult(
                            contingency = contingency,
                            status = PostContingencyStatus.SECURE,
                            violations = emptyList(),
                        )
                }
            } catch (e: Exception) {
                // DC pre-screen failure → escalate to AC
                log.warn("DC pre-screen failed for contingency ${contingency.id}: ${e.message}")
                needsAc += contingency
            } finally {
                withContext(NonCancellable) {
                    runCatching {
                        network.variantManager.setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID)
                        network.variantManager.removeVariant(variantId)
                    }
                }
            }
        }

        return secureResults to needsAc
    }

    // -------------------------------------------------------------------------
    // AC SecurityAnalysis
    // -------------------------------------------------------------------------

    private suspend fun runAcSecurityAnalysis(
        network: Network,
        contingencies: List<Contingency>,
        parameters: ContingencyAnalysisParameters,
    ): List<ContingencyResult> {
        val powSyBlContingencies = ContingencyBuilder.toPowSyBlList(contingencies)
        val contingencyById = contingencies.associateBy { it.id }
        val results = mutableListOf<ContingencyResult>()

        runCatching {
            // SecurityAnalysis.run() returns SecurityAnalysisReport; extract result from it.
            val saReport = SecurityAnalysis.run(network, powSyBlContingencies)
            saReport.result.postContingencyResults.forEach { pcResult ->
                val contingency = contingencyById[pcResult.contingency.id] ?: return@forEach
                val solved =
                    pcResult.status == PostContingencyComputationStatus.CONVERGED ||
                        pcResult.status == PostContingencyComputationStatus.NO_IMPACT
                if (!solved) {
                    results +=
                        ContingencyResult(
                            contingency = contingency,
                            status = PostContingencyStatus.NETWORK_FAILURE,
                            violations = emptyList(),
                        )
                    return@forEach
                }
                val violations =
                    pcResult.limitViolationsResult.limitViolations.mapNotNull { lv ->
                        mapLimitViolation(lv, parameters.postContingencyRatingMultiplier, network)
                    }
                val status =
                    if (violations.isEmpty()) PostContingencyStatus.SECURE else PostContingencyStatus.VIOLATION
                results += ContingencyResult(contingency = contingency, status = status, violations = violations)
            }
        }.onFailure { e ->
            log.error("AC SecurityAnalysis failed: ${e.message}", e)
            contingencies.forEach { contingency ->
                results +=
                    ContingencyResult(
                        contingency = contingency,
                        status = PostContingencyStatus.NETWORK_FAILURE,
                        violations = emptyList(),
                    )
            }
        }

        return results
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun checkBaseCaseSecure(snapshot: GridNetwork): Boolean =
        runCatching {
            val baseCaseViolations =
                buildList {
                    snapshot.buses.forEach { bus ->
                        val vPu = bus.voltageMagnitudePu ?: return@forEach
                        if (vPu < thresholds.voltageMinPu || vPu > thresholds.voltageMaxPu) add(bus.id)
                    }
                    snapshot.lines.forEach { line ->
                        val rating = line.ratingA ?: return@forEach
                        val current = maxOfNullable(line.currentFromA, line.currentToA) ?: return@forEach
                        if (current / rating * 100.0 >= thresholds.warningPercent) add(line.id)
                    }
                    snapshot.twoWindingsTransformers.forEach { twt ->
                        val rating = twt.ratingMva ?: return@forEach
                        val ratingFromA = mvaToAmps(rating, twt.nominalVoltageFromKv)
                        val ratingToA = mvaToAmps(rating, twt.nominalVoltageToKv)
                        // Check each side independently — null on one side does not fall back to the other.
                        val fromOverloaded =
                            twt.currentFromA?.let { it / ratingFromA * 100.0 >= thresholds.warningPercent } == true
                        val toOverloaded =
                            twt.currentToA?.let { it / ratingToA * 100.0 >= thresholds.warningPercent } == true
                        if (fromOverloaded || toOverloaded) add(twt.id)
                    }
                    snapshot.threeWindingsTransformers.forEach { twt3 ->
                        listOf(
                            Triple(twt3.ratingMva1, twt3.current1A, twt3.nominalVoltage1Kv),
                            Triple(twt3.ratingMva2, twt3.current2A, twt3.nominalVoltage2Kv),
                            Triple(twt3.ratingMva3, twt3.current3A, twt3.nominalVoltage3Kv),
                        ).forEach { (ratingMva, currentA, voltageKv) ->
                            val ratingA = ratingMva?.let { mvaToAmps(it, voltageKv) } ?: return@forEach
                            val current = currentA ?: return@forEach
                            if (current / ratingA * 100.0 >= thresholds.warningPercent) add(twt3.id)
                        }
                    }
                }
            baseCaseViolations.isEmpty()
        }.getOrElse { e ->
            log.warn("Base case security check failed: {}; assuming not secure", e.message)
            false
        }

    private fun applyContingencyToNetwork(
        network: Network,
        contingency: Contingency,
    ) {
        contingency.elements.forEach { element ->
            when (element) {
                is ContingencyElement.LineOutage ->
                    network.getLine(element.lineId)?.let {
                        it.terminal1.disconnect()
                        it.terminal2.disconnect()
                    }
                is ContingencyElement.TwoWindingsTransformerOutage ->
                    network.getTwoWindingsTransformer(element.transformerId)?.let {
                        it.terminal1.disconnect()
                        it.terminal2.disconnect()
                    }
                is ContingencyElement.ThreeWindingsTransformerOutage ->
                    network.getThreeWindingsTransformer(element.transformerId)?.let {
                        it.leg1.terminal.disconnect()
                        it.leg2.terminal.disconnect()
                        it.leg3.terminal.disconnect()
                    }
                is ContingencyElement.GeneratorOutage ->
                    network.getGenerator(element.generatorId)?.terminal?.disconnect()
            }
        }
    }

    private fun mapLimitViolation(
        lv: com.powsybl.security.LimitViolation,
        ratingMultiplier: Double,
        network: Network,
    ): PostContingencyViolation? {
        val thermalEquipmentType =
            when {
                network.getLine(lv.subjectId) != null -> EquipmentType.LINE
                network.getTwoWindingsTransformer(lv.subjectId) != null ->
                    EquipmentType.TWO_WINDINGS_TRANSFORMER
                network.getThreeWindingsTransformer(lv.subjectId) != null ->
                    EquipmentType.THREE_WINDINGS_TRANSFORMER
                else -> {
                    log.warn("Unknown equipment type for thermal violation on {}", lv.subjectId)
                    EquipmentType.LINE
                }
            }
        val (violationType, equipmentType) =
            when (lv.limitType) {
                LimitViolationType.CURRENT -> ViolationType.THERMAL to thermalEquipmentType
                LimitViolationType.LOW_VOLTAGE -> ViolationType.VOLTAGE_LOW to EquipmentType.BUS
                LimitViolationType.HIGH_VOLTAGE -> ViolationType.VOLTAGE_HIGH to EquipmentType.BUS
                else -> return null
            }

        // Rating multiplier applies only to thermal (current) limits; voltage limits are fixed pu values.
        val adjustedLimit =
            if (violationType == ViolationType.THERMAL) lv.limit * ratingMultiplier else lv.limit
        val loadingPercent = lv.value / adjustedLimit * 100.0
        val severity =
            when (violationType) {
                ViolationType.THERMAL -> thresholds.thermalSeverity(loadingPercent)
                // voltageSeverity expects the raw p.u. voltage, not a normalised ratio.
                ViolationType.VOLTAGE_LOW,
                ViolationType.VOLTAGE_HIGH,
                -> thresholds.voltageSeverity(lv.value)
            } ?: return null

        return PostContingencyViolation(
            equipmentId = lv.subjectId,
            equipmentType = equipmentType,
            violationType = violationType,
            value = lv.value,
            limit = adjustedLimit,
            loadingPercent = loadingPercent,
            severity = severity,
        )
    }

    private fun maxOfNullable(
        a: Double?,
        b: Double?,
    ): Double? =
        when {
            a != null && b != null -> maxOf(a, b)
            a != null -> a
            b != null -> b
            else -> null
        }

    /** I = S(MVA) × 1000 / (√3 × V_kV) */
    private fun mvaToAmps(
        mva: Double,
        voltageKv: Double,
    ): Double = if (voltageKv > 0.0) mva * 1000.0 / (SQRT3 * voltageKv) else 0.0

    private data class RunRequest(
        val network: Network,
        val parameters: ContingencyAnalysisParameters,
    )

    companion object {
        private val SQRT3 = kotlin.math.sqrt(3.0)
    }
}
