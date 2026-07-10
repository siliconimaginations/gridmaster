package com.gridmaster.api.dto

import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

// ---------------------------------------------------------------------------
// Network mutation
// ---------------------------------------------------------------------------

/**
 * POST /api/sessions/{sessionId}/network/mutations
 */
data class ApplyMutationsRequest(
    @field:NotEmpty val mutations: List<NetworkMutationDto>,
)

/**
 * A single network mutation command.
 *
 * [type] maps to [com.gridmaster.engine.model.NetworkMutation] subtypes:
 * - "SET_GENERATOR_OUTPUT"
 * - "SET_GENERATOR_VOLTAGE"
 * - "TRIP_LINE" / "CONNECT_LINE"
 * - "TRIP_GENERATOR" / "CONNECT_GENERATOR"
 * - "SET_TAP_POSITION"
 * - "SET_LOAD_ACTIVE_POWER"
 * - "SET_SHUNT_SECTION_COUNT"
 *
 * [parameters] carries subtype-specific fields (e.g. targetPMw, tapPosition).
 */
data class NetworkMutationDto(
    val type: String,
    val targetId: String,
    val parameters: Map<String, Any> = emptyMap(),
)

// ---------------------------------------------------------------------------
// Power flow
// ---------------------------------------------------------------------------

/**
 * POST /api/sessions/{sessionId}/powerflow/run
 * (body is optional — omitting it uses AC distributed-slack defaults)
 */
data class RunPowerFlowRequest(
    val mode: String = "AC",
    val distributedSlack: Boolean = true,
    val balanceType: String = "PROPORTIONAL_TO_GENERATION_P_MAX",
)

// ---------------------------------------------------------------------------
// Dispatch
// ---------------------------------------------------------------------------

/**
 * POST /api/sessions/{sessionId}/dispatch
 */
data class DispatchRequest(
    val totalLoadMw: Double,
    val mode: String = "MERIT_ORDER",
    val reserveMarginFraction: Double = 0.20,
    val securityConstrained: Boolean = false,
)

// ---------------------------------------------------------------------------
// Unit commitment
// ---------------------------------------------------------------------------

/**
 * POST /api/sessions/{sessionId}/unitcommitment
 *
 * [hourlyForecastMw] must contain exactly 24 values (one per hour).
 */
data class UnitCommitmentRequest(
    @field:Size(min = 24, max = 24) val hourlyForecastMw: List<Double>,
    val reserveMarginFraction: Double = 0.20,
)
