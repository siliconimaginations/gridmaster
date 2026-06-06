package com.gridmaster.api

import com.gridmaster.api.dto.ApplyMutationsRequest
import com.gridmaster.api.dto.DispatchRequest
import com.gridmaster.api.dto.NetworkMutationDto
import com.gridmaster.api.dto.RunPowerFlowRequest
import com.gridmaster.api.dto.UnitCommitmentRequest
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
import jakarta.validation.Valid
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
    // -------------------------------------------------------------------------
    // Network state
    // -------------------------------------------------------------------------

    /** GET /api/sessions/{sessionId}/network — current GridNetwork snapshot. */
    @GetMapping("/network")
    fun getNetwork(
        @PathVariable sessionId: String,
    ): GridNetwork {
        val session = sessionStore.get(sessionId)
        return session.latestSnapshot
    }

    /**
     * POST /api/sessions/{sessionId}/network/mutations — apply one or more mutations.
     *
     * Mutations are applied sequentially to the live IIDM network.
     * Power flow is NOT re-run automatically; the caller should follow up with
     * POST /powerflow/run if an immediate updated result is needed.
     *
     * Returns the updated [GridNetwork] snapshot (without power-flow results).
     */
    @PostMapping("/network/mutations")
    fun applyMutations(
        @PathVariable sessionId: String,
        @Valid @RequestBody request: ApplyMutationsRequest,
    ): GridNetwork {
        val session = sessionStore.get(sessionId)
        val mutations = request.mutations.map { it.toDomain() }

        for (mutation in mutations) {
            networkMapper.applyMutation(session.iidmNetwork, mutation)
                .getOrElse { ex ->
                    throw InvalidMutationException(ex.message ?: "Mutation failed: $mutation")
                }
        }

        val updated = networkMapper.toGridNetwork(session.iidmNetwork)
        session.latestSnapshot = updated
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
    fun runPowerFlow(
        @PathVariable sessionId: String,
        @RequestBody(required = false) request: RunPowerFlowRequest?,
    ): PowerFlowResult {
        val session = sessionStore.get(sessionId)
        val params = request?.toDomain() ?: PowerFlowParameters()

        val result =
            try {
                powerFlowService.solve(session.iidmNetwork, params)
            } catch (ex: Exception) {
                throw PhysicsServiceException(sessionId, "Power flow failed: ${ex.message}", ex)
            }

        session.latestPowerFlowResult = result
        session.latestSnapshot = result.snapshot
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
    fun triggerContingencies(
        @PathVariable sessionId: String,
    ): ResponseEntity<Void> {
        val session = sessionStore.get(sessionId)
        contingencyService.triggerAsync(
            session.iidmNetwork,
            ContingencyAnalysisParameters(),
        )
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
    fun runDispatch(
        @PathVariable sessionId: String,
        @Valid @RequestBody request: DispatchRequest,
    ): DispatchResult {
        val session = sessionStore.get(sessionId)
        val generators = session.latestSnapshot.toDispatchableGenerators()
        val params = request.toDomain()

        val result =
            try {
                dispatchService.economicDispatch(generators, request.totalLoadMw, params)
            } catch (ex: Exception) {
                throw PhysicsServiceException(sessionId, "Dispatch failed: ${ex.message}", ex)
            }

        session.latestDispatchResult = result
        return result
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
    fun runUnitCommitment(
        @PathVariable sessionId: String,
        @Valid @RequestBody request: UnitCommitmentRequest,
    ): UcResult {
        val session = sessionStore.get(sessionId)
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
        return result
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
            is Number -> v.toInt()
            is String ->
                v.toIntOrNull()
                    ?: throw InvalidMutationException("Parameter '$key' is not a valid integer: $v")
            else -> throw InvalidMutationException("Parameter '$key' has unexpected type: ${v::class}")
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
                reactivePowerMvar = (parameters["reactivePowerMvar"] as? Number)?.toDouble(),
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
            when (mode.uppercase()) {
                "AC" -> SolveMode.AC
                "DC" -> SolveMode.DC
                else -> throw InvalidMutationException("Unknown solve mode: $mode")
            },
        distributedSlack = distributedSlack,
        balanceType =
            when (balanceType.uppercase()) {
                "PROPORTIONAL_TO_GENERATION_P_MAX" -> BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX
                "PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN" -> BalanceType.PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN
                "PROPORTIONAL_TO_LOAD" -> BalanceType.PROPORTIONAL_TO_LOAD
                else -> throw InvalidMutationException("Unknown balance type: $balanceType")
            },
    )

private fun DispatchRequest.toDomain(): DispatchParameters =
    DispatchParameters(
        mode =
            when (mode.uppercase()) {
                "MERIT_ORDER" -> DispatchMode.MERIT_ORDER
                "LP" -> DispatchMode.LP
                else -> throw InvalidMutationException("Unknown dispatch mode: $mode")
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
