package com.gridmaster.engine.contingency

import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.network.IidmNetworkMapper
import com.gridmaster.engine.powerflow.EquipmentType
import com.gridmaster.engine.powerflow.PowerFlowParameters
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.SolveMode
import com.gridmaster.engine.powerflow.ViolationScanner
import com.gridmaster.engine.powerflow.ViolationSeverity
import com.gridmaster.engine.powerflow.ViolationThresholds
import com.powsybl.iidm.network.Network
import com.powsybl.iidm.network.VariantManagerConstants
import com.powsybl.security.LimitViolationType
import com.powsybl.security.PostContingencyComputationStatus
import com.powsybl.security.SecurityAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

/**
 * PowSyBl-backed implementation of [ContingencyAnalysisService].
 *
 * Async execution model:
 * - [triggerAsync] sends a run request into a [Channel.CONFLATED] channel.
 *   Conflated means only the latest pending request is kept — natural debouncing.
 * - A background coroutine consumes requests and executes analysis runs serially.
 * - Results are stored per-session in [caches] and readable via [latestResult] immediately.
 *
 * DC pre-screening:
 * - When [ContingencyAnalysisParameters.dcPreScreening] is true, each contingency
 *   is first evaluated with a DC power flow using PowSyBl network variants.
 * - Only contingencies with DC violations are escalated to full AC SecurityAnalysis.
 *   This reduces AC solve count by typically 80–90%.
 *
 * ### Thread safety (#360)
 * The background consumer executes [runAnalysis] on [Dispatchers.Default], entirely
 * independently of whatever thread called [triggerAsync]. Both the trigger-time
 * clone and the full background run mutate `network.variantManager` — clone,
 * setWorkingVariant, removeVariant — none of which PowSyBl guarantees safe under
 * concurrent access. Previously nothing serialized this against the tick loop's
 * own `powerFlowService.solve()` calls on the same live network; at low speed the
 * analysis reliably finished between ticks so the race was never hit, but at 100×
 * speed (10 ms/tick) the tick loop's solve calls and a still-running analysis
 * overlap almost every time, corrupting the shared variant array
 * (`ArrayIndexOutOfBoundsException` in `TDoubleArrayList`/`VariantManagerImpl`).
 * [runAnalysis] is not a `suspend fun` — none of its own work suspends, it only
 * calls blocking PowSyBl/solver code — so its entire body, plus the trigger-time
 * clone in [triggerAsync], can be wrapped in a plain `synchronized(lock)` block
 * using the caller-supplied lock (see [ContingencyAnalysisService.triggerAsync]).
 */
@Service
class PowSyBlContingencyAnalysisService(
    private val mapper: IidmNetworkMapper,
    private val powerFlowService: PowerFlowService,
    private val violationScanner: ViolationScanner = ViolationScanner(),
    private val thresholds: ViolationThresholds = ViolationThresholds(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : ContingencyAnalysisService {
    private val log = LoggerFactory.getLogger(PowSyBlContingencyAnalysisService::class.java)

    // CONFLATED channel — only the latest pending request is kept (debouncing).
    private val triggerChannel = Channel<RunRequest>(Channel.CONFLATED)

    // Tracks the variant ID cloned for the currently-pending (not-yet-consumed) request.
    // When a new trigger replaces a pending one, the old variant is cleaned up immediately.
    private val pendingVariantId = AtomicReference<String?>(null)

    // Per-session result caches (#347) -- this service is a singleton Spring bean shared
    // by every concurrent game session, but a single ContingencyAnalysisCache would let
    // one session's N-1 results leak into another's GET /contingencies response. Keyed by
    // sessionId, created lazily on first trigger/read for that session.
    private val caches = java.util.concurrent.ConcurrentHashMap<String, ContingencyAnalysisCache>()

    private fun cacheFor(sessionId: String): ContingencyAnalysisCache = caches.computeIfAbsent(sessionId) { ContingencyAnalysisCache() }

    init {
        // Background consumer — runs analyses serially as requests arrive.
        // The entire run is held under the caller-supplied lock (#360) — runAnalysis
        // is a plain blocking function (no suspension inside), so a JVM monitor here
        // is safe and serializes against the tick loop / REST controller / command
        // handler, all of which touch the same network under the same lock.
        scope.launch {
            triggerChannel.consumeEach { request ->
                try {
                    synchronized(request.lock) { runAnalysis(request) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    log.error("Contingency analysis run failed unexpectedly", e)
                }
            }
        }
    }

    override fun triggerAsync(
        network: Network,
        sessionId: String,
        lock: Any,
        parameters: ContingencyAnalysisParameters,
    ) {
        // Clone the network state synchronously so the analysis always runs on the
        // state at the time of the trigger call, not some later (post-mutation) state.
        // Synchronized against the same lock the background run (below) and every
        // other caller use — PowSyBl's VariantManager is not safe for concurrent
        // mutation (#360).
        val variantId = "ca-analysis-${System.nanoTime()}"
        synchronized(lock) {
            network.variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, variantId, true)

            // Clean up any previously-pending variant that is being replaced (CONFLATED channel).
            pendingVariantId.getAndSet(variantId)?.let { dropped ->
                runCatching { network.variantManager.removeVariant(dropped) }
                    .onFailure { log.warn("Failed to remove dropped variant {}", dropped, it) }
            }
        }

        // trySend() doesn't touch the network, so it doesn't need the lock — keep the
        // critical section limited to the actual variant-manager calls (Gemini review, PR #362).
        triggerChannel.trySend(RunRequest(network, sessionId, parameters, variantId, lock))
    }

    override fun latestResult(sessionId: String): ContingencyAnalysisResult? = caches[sessionId]?.latest()

    override fun clearSession(sessionId: String) {
        caches.remove(sessionId)
    }

    override fun buildN1Contingencies(network: GridNetwork): List<Contingency> = ContingencyBuilder.buildN1(network)

    /** Cancels the background consumer. Call when the game session ends. */
    fun shutdown() {
        scope.cancel()
        triggerChannel.close()
    }

    // -------------------------------------------------------------------------
    // Analysis execution
    // -------------------------------------------------------------------------

    private fun runAnalysis(request: RunRequest) {
        val (network, sessionId, parameters, analysisVariantId) = request
        log.info("Starting contingency analysis run")

        // Set the working variant to the snapshot that was cloned in triggerAsync.
        // This ensures we analyse the network state at trigger time, not the current state.
        pendingVariantId.compareAndSet(analysisVariantId, null) // clear pending — we own it now
        network.variantManager.setWorkingVariant(analysisVariantId)

        try {
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
                        val (screened, acCandidates) = dcPreScreen(network, contingencies, parameters, analysisVariantId)
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

            cacheFor(sessionId).update(result)

            log.info(
                "Contingency analysis complete: {} contingencies, {} critical, {}ms " +
                    "(DC pre-screened: {}, full AC: {})",
                contingencies.size,
                criticalContingencies.size,
                analysisTimeMs,
                preScreenedCount,
                fullAcCount,
            )
        } finally {
            // Guarantee variant cleanup even if an exception propagates out of runAnalysis.
            // Split into two blocks so removeVariant is always attempted even if setWorkingVariant throws.
            runCatching {
                network.variantManager.setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID)
            }.onFailure { log.warn("Failed to reset working variant after analysis", it) }
            runCatching {
                network.variantManager.removeVariant(analysisVariantId)
            }.onFailure { log.warn("Failed to remove analysis variant {}", analysisVariantId, it) }
        }
    }

    // -------------------------------------------------------------------------
    // DC pre-screening
    // -------------------------------------------------------------------------

    /**
     * Runs a DC power flow for each contingency using PowSyBl network variants.
     * Returns a pair of (secure/pre-screened results, contingencies needing AC).
     */
    private fun dcPreScreen(
        network: Network,
        contingencies: List<Contingency>,
        parameters: ContingencyAnalysisParameters,
        sourceVariantId: String,
    ): Pair<List<ContingencyResult>, List<Contingency>> {
        val secureResults = mutableListOf<ContingencyResult>()
        val needsAc = mutableListOf<Contingency>()

        for (contingency in contingencies) {
            val variantId = "dc-screen-${contingency.id}"
            try {
                network.variantManager.cloneVariant(
                    sourceVariantId,
                    variantId,
                    true,
                )
                network.variantManager.setWorkingVariant(variantId)
                if (!applyContingencyToNetwork(network, contingency)) {
                    needsAc += contingency
                } else {
                    val dcResult =
                        powerFlowService.solve(
                            network,
                            PowerFlowParameters(mode = SolveMode.DC),
                        )
                    if (dcResult.violations.isNotEmpty()) {
                        needsAc += contingency
                    } else {
                        secureResults +=
                            ContingencyResult(
                                contingency = contingency,
                                status = PostContingencyStatus.SECURE,
                                violations = emptyList(),
                            )
                    }
                }
            } catch (e: Exception) {
                // DC pre-screen failure → escalate to AC
                log.warn("DC pre-screen failed for contingency ${contingency.id}: ${e.message}")
                needsAc += contingency
            } finally {
                // No `withContext(NonCancellable)` needed here (Gemini review, PR #362, flagged
                // its removal): dcPreScreen is a plain blocking function now, not `suspend` — it
                // has no suspension points a cancelled Job could interrupt mid-cleanup, so these
                // two calls always run to completion as ordinary try/finally semantics, with no
                // dependency on coroutine cancellation state.
                runCatching {
                    network.variantManager.setWorkingVariant(sourceVariantId)
                }
                runCatching {
                    network.variantManager.removeVariant(variantId)
                }
            }
        }

        return secureResults to needsAc
    }

    // -------------------------------------------------------------------------
    // AC SecurityAnalysis
    // -------------------------------------------------------------------------

    private fun runAcSecurityAnalysis(
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
        runCatching { violationScanner.scan(snapshot).isEmpty() }
            .getOrElse { e ->
                log.warn("Base case security check failed: {}; assuming not secure", e.message)
                false
            }

    /**
     * Applies [contingency] to [network] by disconnecting elements.
     * Returns false if any element was not found — the caller should escalate
     * to full AC analysis rather than trusting the (unmodified) DC pre-screen result.
     */
    private fun applyContingencyToNetwork(
        network: Network,
        contingency: Contingency,
    ): Boolean {
        var allApplied = true
        contingency.elements.forEach { element ->
            val applied =
                when (element) {
                    is ContingencyElement.LineOutage ->
                        network.getLine(element.lineId)?.also {
                            it.terminal1.disconnect()
                            it.terminal2.disconnect()
                        } != null
                    is ContingencyElement.TwoWindingsTransformerOutage ->
                        network.getTwoWindingsTransformer(element.transformerId)?.also {
                            it.terminal1.disconnect()
                            it.terminal2.disconnect()
                        } != null
                    is ContingencyElement.ThreeWindingsTransformerOutage ->
                        network.getThreeWindingsTransformer(element.transformerId)?.also {
                            it.leg1.terminal.disconnect()
                            it.leg2.terminal.disconnect()
                            it.leg3.terminal.disconnect()
                        } != null
                    is ContingencyElement.GeneratorOutage ->
                        network.getGenerator(element.generatorId)?.also {
                            it.terminal.disconnect()
                        } != null
                }
            if (!applied) {
                log.warn(
                    "Contingency {}: element {} not found; escalating to AC",
                    contingency.id,
                    element,
                )
                allApplied = false
            }
        }
        return allApplied
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
                    log.warn("Unknown equipment type for thermal violation on {}; skipping", lv.subjectId)
                    return null
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

    private data class RunRequest(
        val network: Network,
        val sessionId: String,
        val parameters: ContingencyAnalysisParameters,
        val analysisVariantId: String,
        val lock: Any,
    )
}
