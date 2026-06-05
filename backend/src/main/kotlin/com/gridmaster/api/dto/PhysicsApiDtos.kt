package com.gridmaster.api.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant

// ---------------------------------------------------------------------------
// Network / mutations
// ---------------------------------------------------------------------------

data class NetworkMutationDto(
    val type: String,
    val targetId: String,
    val parameters: Map<String, Any> = emptyMap(),
)

data class ApplyMutationsRequest(
    @field:NotEmpty val mutations: List<NetworkMutationDto>,
)

// ---------------------------------------------------------------------------
// Power flow
// ---------------------------------------------------------------------------

data class PowerFlowRequest(
    val mode: String = "AC", // "AC" | "DC"
)

// ---------------------------------------------------------------------------
// Dispatch
// ---------------------------------------------------------------------------

data class DispatchRequest(
    val totalLoadMw: Double,
    val mode: String = "MERIT_ORDER",
    @field:DecimalMin("0.0") val reserveMarginFraction: Double = 0.20,
    val securityConstrained: Boolean = false,
)

// ---------------------------------------------------------------------------
// Unit commitment
// ---------------------------------------------------------------------------

data class UnitCommitmentRequest(
    @field:Size(min = 24, max = 24) val hourlyForecastMw: List<Double>,
    @field:DecimalMin("0.0") val reserveMarginFraction: Double = 0.20,
)

// ---------------------------------------------------------------------------
// Contingency analysis
// ---------------------------------------------------------------------------

data class ContingencyTriggerRequest(
    val dcPreScreening: Boolean = true,
    val postContingencyRatingMultiplier: Double = 1.0,
)

// ---------------------------------------------------------------------------
// Error envelope
// ---------------------------------------------------------------------------

data class ApiError(
    val status: Int,
    val error: String,
    val message: String,
    val sessionId: String?,
    val timestamp: Instant = Instant.now(),
)
