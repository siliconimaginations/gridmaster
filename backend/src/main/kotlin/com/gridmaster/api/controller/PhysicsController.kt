package com.gridmaster.api.controller

import com.gridmaster.api.dto.ApiError
import com.gridmaster.api.dto.ApplyMutationsRequest
import com.gridmaster.api.dto.ContingencyTriggerRequest
import com.gridmaster.api.dto.DispatchRequest
import com.gridmaster.api.dto.PowerFlowRequest
import com.gridmaster.api.dto.UnitCommitmentRequest
import com.gridmaster.api.mapper.toDispatchableGenerators
import com.gridmaster.engine.contingency.ContingencyAnalysisParameters
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.dispatch.DispatchMode
import com.gridmaster.engine.dispatch.DispatchParameters
import com.gridmaster.engine.dispatch.DispatchService
import com.gridmaster.engine.dispatch.LoadForecast
import com.gridmaster.engine.dispatch.UnitCommitmentService
import com.gridmaster.engine.network.IidmNetworkMapper
import com.gridmaster.engine.powerflow.PowerFlowParameters
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.SolveMode
import com.gridmaster.session.SessionRegistry
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * REST controller for physics engine operations.
 *
 * All endpoints are session-scoped: `/api/sessions/{sessionId}/...`.
 * Controllers are thin — they validate input, delegate to services, and map results.
 * No physics logic lives here.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}")
class PhysicsController(
    private val sessionRegistry: SessionRegistry,
    private val powerFlowService: PowerFlowService,
    private val contingencyService: ContingencyAnalysisService,
    private val dispatchService: DispatchService,
    private val unitCommitmentService: UnitCommitmentService,
    private val mapper: IidmNetworkMapper,
) {
    private val log = LoggerFactory.getLogger(PhysicsController::class.java)

    // -------------------------------------------------------------------------
    // Network
    // -------------------------------------------------------------------------

    @GetMapping("/network")
    fun getNetwork(
        @PathVariable sessionId: String,
    ): ResponseEntity<Any> {
        val session = sessionRegistry.get(sessionId) ?: return sessionNotFound(sessionId)
        val snapshot = mapper.toGridNetwork(session.network)
        return ResponseEntity.ok(snapshot)
    }

    @PostMapping("/network/mutations")
    fun applyMutations(
        @PathVariable sessionId: String,
        @Valid @RequestBody request: ApplyMutationsRequest,
    ): ResponseEntity<Any> {
        val session = sessionRegistry.get(sessionId) ?: return sessionNotFound(sessionId)
        return try {
            session.applyMutations(request.mutations)
            ResponseEntity.ok(mapOf("applied" to request.mutations.size))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                ApiError(400, "INVALID_MUTATION", e.message ?: "Invalid mutation", sessionId),
            )
        }
    }

    // -------------------------------------------------------------------------
    // Power flow
    // -------------------------------------------------------------------------

    @GetMapping("/powerflow")
    fun getLatestPowerFlow(
        @PathVariable sessionId: String,
    ): ResponseEntity<Any> {
        val session = sessionRegistry.get(sessionId) ?: return sessionNotFound(sessionId)
        val result =
            session.latestPowerFlowResult
                ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(result)
    }

    @PostMapping("/powerflow/run")
    fun runPowerFlow(
        @PathVariable sessionId: String,
        @RequestBody(required = false) request: PowerFlowRequest?,
    ): ResponseEntity<Any> {
        val session = sessionRegistry.get(sessionId) ?: return sessionNotFound(sessionId)
        val mode =
            when (request?.mode?.uppercase()) {
                "DC" -> SolveMode.DC
                else -> SolveMode.AC
            }
        val result = powerFlowService.solve(session.network, PowerFlowParameters(mode = mode))
        session.updatePowerFlowResult(result)
        return ResponseEntity.ok(result)
    }

    // -------------------------------------------------------------------------
    // Contingency analysis
    // -------------------------------------------------------------------------

    @GetMapping("/contingencies")
    fun getLatestContingencyResult(
        @PathVariable sessionId: String,
    ): ResponseEntity<Any> {
        sessionRegistry.get(sessionId) ?: return sessionNotFound(sessionId)
        val result = contingencyService.latestResult() ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(result)
    }

    @PostMapping("/contingencies/trigger")
    fun triggerContingencyAnalysis(
        @PathVariable sessionId: String,
        @RequestBody(required = false) request: ContingencyTriggerRequest?,
    ): ResponseEntity<Any> {
        val session = sessionRegistry.get(sessionId) ?: return sessionNotFound(sessionId)
        val params =
            ContingencyAnalysisParameters(
                dcPreScreening = request?.dcPreScreening ?: true,
                postContingencyRatingMultiplier = request?.postContingencyRatingMultiplier ?: 1.0,
            )
        contingencyService.triggerAsync(session.network, params)
        return ResponseEntity.accepted().body(mapOf("status" to "triggered"))
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    @PostMapping("/dispatch")
    fun runDispatch(
        @PathVariable sessionId: String,
        @Valid @RequestBody request: DispatchRequest,
    ): ResponseEntity<Any> {
        val session = sessionRegistry.get(sessionId) ?: return sessionNotFound(sessionId)
        val snapshot = mapper.toGridNetwork(session.network)
        val generators = snapshot.toDispatchableGenerators()
        val mode =
            when (request.mode.uppercase()) {
                "LP" -> DispatchMode.LP
                else -> DispatchMode.MERIT_ORDER
            }
        val result =
            dispatchService.economicDispatch(
                generators,
                request.totalLoadMw,
                DispatchParameters(
                    mode = mode,
                    securityConstrained = request.securityConstrained,
                    reserveMarginFraction = request.reserveMarginFraction,
                ),
            )
        return ResponseEntity.ok(result)
    }

    @GetMapping("/violations")
    fun getViolations(
        @PathVariable sessionId: String,
    ): ResponseEntity<Any> {
        val session = sessionRegistry.get(sessionId) ?: return sessionNotFound(sessionId)
        val result =
            session.latestPowerFlowResult
                ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(mapOf("violations" to result.violations))
    }

    // -------------------------------------------------------------------------
    // Unit commitment
    // -------------------------------------------------------------------------

    @PostMapping("/unitcommitment")
    fun runUnitCommitment(
        @PathVariable sessionId: String,
        @Valid @RequestBody request: UnitCommitmentRequest,
    ): ResponseEntity<Any> {
        val session = sessionRegistry.get(sessionId) ?: return sessionNotFound(sessionId)
        val snapshot = mapper.toGridNetwork(session.network)
        val generators = snapshot.toDispatchableGenerators()
        return try {
            val forecast =
                LoadForecast(
                    hourlyLoadMw = request.hourlyForecastMw,
                    startHour = Instant.now(),
                )
            val result =
                unitCommitmentService.commit(
                    generators,
                    forecast,
                    DispatchParameters(reserveMarginFraction = request.reserveMarginFraction),
                )
            ResponseEntity.ok(result)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                ApiError(400, "INVALID_FORECAST", e.message ?: "Invalid forecast", sessionId),
            )
        }
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val message = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(ApiError(400, "VALIDATION_ERROR", message, null))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("Unexpected error in physics API", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiError(500, "PHYSICS_ERROR", e.message ?: "Internal error", null),
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sessionNotFound(sessionId: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError(404, "SESSION_NOT_FOUND", "Session '$sessionId' not found", sessionId),
        )
}
