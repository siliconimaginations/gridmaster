package com.gridmaster.api

import com.gridmaster.api.dto.ApplyMutationsRequest
import com.gridmaster.api.dto.DispatchRequest
import com.gridmaster.api.dto.NetworkMutationDto
import com.gridmaster.api.dto.RunPowerFlowRequest
import com.gridmaster.api.dto.UnitCommitmentRequest
import com.gridmaster.api.websocket.GridNetworkWsDto
import com.gridmaster.api.websocket.toNetworkWsDto
import com.gridmaster.engine.contingency.ContingencyAnalysisParameters
import com.gridmaster.engine.contingency.ContingencyAnalysisResult
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.dispatch.DispatchMode
import com.gridmaster.engine.dispatch.DispatchParameters
import com.gridmaster.engine.dispatch.DispatchResult
import com.gridmaster.engine.dispatch.DispatchService
import com.gridmaster.engine.dispatch.DispatchableGenerator
import com.gridmaster.engine.dispatch.LoadForecast
import com.gridmaster.engine.dispatch.UcResult
import com.gridmaster.engine.dispatch.UnitCommitmentService
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.NetworkMutation
import com.gridmaster.engine.network.IidmNetworkMapper
import com.gridmaster.engine.powerflow.BalanceType
import com.gridmaster.engine.powerflow.NetworkViolation
import com.gridmaster.engine.powerflow.PowerFlowParameters
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.SolveMode
import com.powsybl.iidm.network.VariantManagerConstants
import jakarta.validation.Valid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * REST API for physics operations scoped to a game session.
 *
 * All paths are prefixed `/api/sessions/{sessionId}`.
 *
 * Controllers are thin: validate → map DTO → delegate to service → map result.
 * No business or physics logic lives here.
 *
 * Session lifecycle (create / delete) is owned by Module 06 (Session Model).
 * This controller only reads and mutates sessions that already exist in
 * [PhysicsSessionStore].
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}")
class PhysicsController(
    private val sessionStore: PhysicsSessionStore,
    private val networkMapper: IidmNetworkMapper,
    private val powerFlowService: PowerFlowService,
    private val contingencyService: ContingencyAnalysisService,
    private val dispatchService: DispatchService,
    private val unitCommitmentService: UnitCommitmentService,
) {
    private val log = LoggerFactory.getLogger(PhysicsController::class.java)

    // -------------------------------------------------------------------------
    // Network state
    // -------------------------------------------------------------------------

    /**
     * GET /api/sessions/{sessionId}/network — current network snapshot as [GridNetworkWsDto].
     *
     * Returns the same DTO shape as the WebSocket state stream ([GridNetworkWsDto]) so that
     * REST and WS clients share identical field names ([activePowerMw], [GeneratorWsDto.committed],
     * etc.). Eliminates the REST/WS mismatch that caused frontend race conditions when the
     * store was seeded from REST before the first WS tick arrived.
     */
    @GetMapping("/network")
    fun getNetwork(
        @PathVariable sessionId: String,
    ): GridNetworkWsDto {
        val session = sessionStore.get(sessionId)
        val smc = session.latestDispatchResult?.systemMarginalCostPerMwh
        return session.latestSnapshot.toNetworkWsDto(smc)
    }

    /**
     * POST /api/sessions/{sessionId}/network/mutations — apply one or more mutations atomically.
     *
     * All mutations in the request are applied as a single atomic unit: either every mutation
     * succeeds and the network is updated, or the first failure triggers a full rollback to the
     * pre-request state and a 400 is returned. No partial application occurs.
     *
     * Power flow is NOT re-run automatically; follow up with POST /powerflow/run if needed.
     *
     * Returns the updated [GridNetwork] snapshot (without power-flow results).
     *
     * NOTE: The snapshot/rollback logic here mirrors [com.gridmaster.game.command.CommandHandlerImpl].
     * Direct delegation to [com.gridmaster.game.command.CommandHandler.applyMutations] is intentionally
     * avoided because that path always runs power flow. This REST endpoint is a low-level tool for
     * tutorial missions and test setups that need to stage network state without triggering a solve.
     * See TODO #76 for a future consolidation plan.
     */
    @PostMapping("/network/mutations")
    fun applyMutations(
        @PathVariable sessionId: String,
        @Valid @RequestBody request: ApplyMutationsRequest,
    ): GridNetwork {
        val session = sessionStore.get(sessionId)
        val mutations = request.mutations.map { it.toDomain() }

        val updated =
            synchronized(session) {
                val network = session.iidmNetwork
                // Clone current state before mutations so we can restore atomically on failure
                // without reassigning session.iidmNetwork. PowSyBl variant clone is cheaper
                // than an XML round-trip and preserves the same network object reference.
                val rollbackId = "rollback-${System.nanoTime()}"
                network.variantManager.cloneVariant(
                    VariantManagerConstants.INITIAL_VARIANT_ID,
                    rollbackId,
                    false,
                )

                try {
                    for (mutation in mutations) {
                        networkMapper.applyMutation(network, mutation)
                            .getOrElse { ex ->
                                throw InvalidMutationException(ex.message ?: "Mutation failed: $mutation")
                            }
                    }
                } catch (ex: InvalidMutationException) {
                    // Restore INITIAL from the rollback snapshot, then discard it.
                    runCatching {
                        network.variantManager.cloneVariant(
                            rollbackId,
                            VariantManagerConstants.INITIAL_VARIANT_ID,
                            true,
                        )
                    }.onFailure { log.warn("applyMutations rollback failed for session {}: {}", sessionId, it.message) }
                    runCatching { network.variantManager.removeVariant(rollbackId) }
                    throw ex
                }

                runCatching { network.variantManager.removeVariant(rollbackId) }
                networkMapper.toGridNetwork(network).also { session.latestSnapshot = it }
            }
        return updated
    }

    // -------------------------------------------------------------------------
    // Power flow
    // -------------------------------------------------------------------------

    /** GET /api/sessions/{sessionId}/powerflow — latest cached PowerFlowResult, or 204 if none. */
    @GetMapping("/powerflow")
    fun getLatestPowerFlow(
        @PathVariable sessionId: String,
    ): ResponseEntity<PowerFlowResult> {
        val session = sessionStore.get(sessionId)
        val result =
            session.latestPowerFlowResult
                ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(result)
    }

    /**
     * POST /api/sessions/{sessionId}/powerflow/run — synchronous AC power flow solve.
     *
     * Used by tutorial missions that need an immediate updated result after dispatch changes.
     * Returns the full [PowerFlowResult] including the updated snapshot and any violations.
     * Divergence is NOT a 500 — [PowerFlowResult.status] == NETWORK_FAILURE is a valid game state.
     */
    @PostMapping("/powerflow/run")
    suspend fun runPowerFlow(
        @PathVariable sessionId: String,
        @RequestBody(required = false) request: RunPowerFlowRequest?,
    ): PowerFlowResult {
        val session = sessionStore.get(sessionId)
        val params = request?.toDomain() ?: PowerFlowParameters()

        // Hold the session lock for the entire solve: PowSyBl mutates iidmNetwork in-place
        // (writes V, θ, I) and the network must not be modified concurrently.
        // Module 06 (Session Model) will replace this with proper session serialisation.
        val result =
            withContext(Dispatchers.IO) {
                synchronized(session) {
                    try {
                        powerFlowService.solve(session.iidmNetwork, params)
                    } catch (ex: Exception) {
                        throw PhysicsServiceException(sessionId, "Power flow failed: ${ex.message}", ex)
                    }.also { r ->
                        session.latestPowerFlowResult = r
                        session.latestSnapshot = r.snapshot
                    }
                }
            }
        return result
    }

    // -------------------------------------------------------------------------
    // Violations
    // -------------------------------------------------------------------------

    /** GET /api/sessions/{sessionId}/violations — violations from the latest power flow. */
    @GetMapping("/violations")
    fun getViolations(
        @PathVariable sessionId: String,
    ): List<NetworkViolation> {
        val session = sessionStore.get(sessionId)
        return session.latestPowerFlowResult?.violations ?: emptyList()
    }

    // -------------------------------------------------------------------------
    // Contingency analysis
    // -------------------------------------------------------------------------

    /** GET /api/sessions/{sessionId}/contingencies — latest cached N-1 result, or 204 if none. */
    @GetMapping("/contingencies")
    fun getLatestContingencies(
        @PathVariable sessionId: String,
    ): ResponseEntity<ContingencyAnalysisResult> {
        val session = sessionStore.get(sessionId)
        val result =
            session.latestContingencyResult
                ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(result)
    }

    /**
     * POST /api/sessions/{sessionId}/contingencies/trigger — trigger async N-1 analysis.
     *
     * Returns 202 Accepted immediately; results become available via GET /contingencies.
     */
    @PostMapping("/contingencies/trigger")
    suspend fun triggerContingencies(
        @PathVariable sessionId: String,
    ): ResponseEntity<Void> {
        val session = sessionStore.get(sessionId)
        // Acquire the session lock while cloneVariant() captures the current network state.
        // triggerAsync() calls variantManager.cloneVariant() synchronously, so the variant
        // snapshot is atomic with the lock. PowSyBl uses a per-thread working variant
        // (ThreadLocal), so the background analysis on its variant does not interfere with
        // session mutations that operate on INITIAL_VARIANT_ID.
        // This replaces the former NetworkSerDe XML round-trip (O(network size)) — #38.
        withContext(Dispatchers.IO) {
            synchronized(session) {
                contingencyService.triggerAsync(session.iidmNetwork, ContingencyAnalysisParameters())
            }
        }
        return ResponseEntity.accepted().build()
    }

    // -------------------------------------------------------------------------
    // Economic dispatch
    // -------------------------------------------------------------------------

    /**
     * POST /api/sessions/{sessionId}/dispatch — run economic dispatch.
     *
     * Derives [DispatchableGenerator] list from the current network snapshot.
     * Returns a [DispatchResult] with per-generator targets and merit order.
     */
    @PostMapping("/dispatch")
    suspend fun runDispatch(
        @PathVariable sessionId: String,
        @Valid @RequestBody request: DispatchRequest,
    ): DispatchResult {
        val session = sessionStore.get(sessionId)
        // Hold the lock for the full read-solve-write cycle so that latestSnapshot
        // cannot be replaced by a concurrent power flow run mid-dispatch.
        // Module 06 (Session Model) will own session serialisation at a higher level.
        return withContext(Dispatchers.IO) {
            synchronized(session) {
                val generators = session.latestSnapshot.toDispatchableGenerators()
                val params = request.toDomain()
                val result =
                    try {
                        dispatchService.economicDispatch(generators, request.totalLoadMw, params)
                    } catch (ex: Exception) {
                        throw PhysicsServiceException(sessionId, "Dispatch failed: ${ex.message}", ex)
                    }
                session.latestDispatchResult = result
                result
            }
        }
    }

    // -------------------------------------------------------------------------
    // Unit commitment
    // -------------------------------------------------------------------------

    /**
     * POST /api/sessions/{sessionId}/unitcommitment — run 24-hour unit commitment.
     *
     * [UnitCommitmentRequest.hourlyForecastMw] must have exactly 24 values.
     * The schedule start hour is set to the current wall-clock hour (UTC).
     * Returns a [UcResult] with per-hour commitment sets and dispatch targets.
     */
    @PostMapping("/unitcommitment")
    suspend fun runUnitCommitment(
        @PathVariable sessionId: String,
        @Valid @RequestBody request: UnitCommitmentRequest,
    ): UcResult {
        val session = sessionStore.get(sessionId)
        // Hold the lock for the full read-solve-write cycle (same rationale as runDispatch).
        return withContext(Dispatchers.IO) {
            synchronized(session) {
                val generators = session.latestSnapshot.toDispatchableGenerators()
                val forecast =
                    LoadForecast(
                        hourlyLoadMw = request.hourlyForecastMw,
                        startHour = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS),
                    )
                val params = DispatchParameters(reserveMarginFraction = request.reserveMarginFraction)
                val result =
                    try {
                        unitCommitmentService.commit(generators, forecast, params)
                    } catch (ex: Exception) {
                        throw PhysicsServiceException(sessionId, "Unit commitment failed: ${ex.message}", ex)
                    }
                session.latestUcResult = result
                result
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Private mapping helpers
// ---------------------------------------------------------------------------

/**
 * Map a [NetworkMutationDto] to the sealed [NetworkMutation] domain type.
 * Throws [InvalidMutationException] for unknown or malformed mutation types.
 */
private fun NetworkMutationDto.toDomain(): NetworkMutation {
    fun param(key: String): Any =
        parameters[key]
            ?: throw InvalidMutationException("Mutation $type missing required parameter '$key'")

    fun double(key: String): Double =
        when (val v = param(key)) {
            is Number -> v.toDouble()
            is String ->
                v.toDoubleOrNull()
                    ?: throw InvalidMutationException("Parameter '$key' is not a valid number: $v")
            else -> throw InvalidMutationException("Parameter '$key' has unexpected type: ${v::class}")
        }

    fun int(key: String): Int =
        when (val v = param(key)) {
            is Number -> {
                val d = v.toDouble()
                if (d != kotlin.math.floor(d)) {
                    throw InvalidMutationException(
                        "Parameter '$key' must be an integer but got fractional value: $v",
                    )
                }
                d.toInt()
            }
            is String ->
                v.toIntOrNull()
                    ?: throw InvalidMutationException("Parameter '$key' is not a valid integer: $v")
            else -> throw InvalidMutationException("Parameter '$key' has unexpected type: ${v::class}")
        }

    // Returns null when key is absent; throws for non-null values of invalid type.
    fun nullableDouble(key: String): Double? {
        val v = parameters[key] ?: return null
        return when (v) {
            is Number -> v.toDouble()
            is String ->
                v.toDoubleOrNull()
                    ?: throw InvalidMutationException("Parameter '$key' is not a valid number: $v")
            else -> throw InvalidMutationException("Parameter '$key' has unexpected type: ${v::class}")
        }
    }

    return when (type) {
        "SET_GENERATOR_OUTPUT" -> NetworkMutation.SetGeneratorOutput(targetId, double("targetPMw"))
        "SET_GENERATOR_VOLTAGE" -> NetworkMutation.SetGeneratorVoltage(targetId, double("targetVoltagePu"))
        "TRIP_LINE" -> NetworkMutation.TripLine(targetId)
        "CONNECT_LINE" -> NetworkMutation.ConnectLine(targetId)
        "TRIP_GENERATOR" -> NetworkMutation.TripGenerator(targetId)
        "CONNECT_GENERATOR" -> NetworkMutation.ConnectGenerator(targetId)
        "SET_TAP_POSITION" -> NetworkMutation.SetTapPosition(targetId, int("tapPosition"))
        "SET_LOAD_ACTIVE_POWER" ->
            NetworkMutation.SetLoadPower(
                loadId = targetId,
                activePowerMw = double("activePowerMw"),
                reactivePowerMvar = nullableDouble("reactivePowerMvar"),
            )
        "CONNECT_LOAD" -> NetworkMutation.ConnectLoad(targetId)
        "DISCONNECT_LOAD" -> NetworkMutation.DisconnectLoad(targetId)
        "SET_SHUNT_SECTION_COUNT" -> NetworkMutation.SetShuntSections(targetId, int("sectionCount"))
        else -> throw InvalidMutationException("Unknown mutation type: $type")
    }
}

private fun RunPowerFlowRequest.toDomain(): PowerFlowParameters =
    PowerFlowParameters(
        mode =
            when (mode.uppercase(java.util.Locale.ROOT)) {
                "AC" -> SolveMode.AC
                "DC" -> SolveMode.DC
                else -> throw IllegalArgumentException("Unknown solve mode: $mode")
            },
        distributedSlack = distributedSlack,
        balanceType =
            when (balanceType.uppercase(java.util.Locale.ROOT)) {
                "PROPORTIONAL_TO_GENERATION_P_MAX" -> BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX
                "PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN" -> BalanceType.PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN
                "PROPORTIONAL_TO_LOAD" -> BalanceType.PROPORTIONAL_TO_LOAD
                else -> throw IllegalArgumentException("Unknown balance type: $balanceType")
            },
    )

private fun DispatchRequest.toDomain(): DispatchParameters =
    DispatchParameters(
        mode =
            when (mode.uppercase(java.util.Locale.ROOT)) {
                "MERIT_ORDER" -> DispatchMode.MERIT_ORDER
                "LP" -> DispatchMode.LP
                else -> throw IllegalArgumentException("Unknown dispatch mode: $mode")
            },
        securityConstrained = securityConstrained,
        reserveMarginFraction = reserveMarginFraction,
    )

/**
 * Derive [DispatchableGenerator] list from a [GridNetwork] snapshot.
 * Committed = generator is connected. Startup cost and min up/down times
 * default to zero (will be enriched by Module 06 generator metadata).
 */
private fun GridNetwork.toDispatchableGenerators(): List<DispatchableGenerator> =
    generators.map { g ->
        DispatchableGenerator(
            id = g.id,
            name = g.name,
            committed = g.connected,
            minActivePowerMw = g.minActivePowerMw,
            maxActivePowerMw = g.maxActivePowerMw,
            currentActivePowerMw = g.targetActivePowerMw,
            marginalCostPerMwh = g.marginalCostPerMwh,
        )
    }
