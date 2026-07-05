package com.gridmaster.api.dto

import com.gridmaster.engine.contingency.PostContingencyStatus
import java.time.Instant

/**
 * Post-contingency impact of losing a single branch, served by
 * GET /api/sessions/{sessionId}/contingency/{branchId}.
 *
 * Extracted from the latest cached N-1 [com.gridmaster.engine.contingency.ContingencyAnalysisResult]
 * for the contingency whose outage element matches the requested branch.
 */
data class ContingencyBranchResultDto(
    /** ID of the matched contingency, e.g. "N1-LINE-L7". */
    val contingencyId: String,
    /** Post-contingency network status for this outage. */
    val status: PostContingencyStatus,
    /** Limit violations that would appear if this branch tripped; empty when SECURE. */
    val violations: List<ContingencyViolationDto>,
    /** Completion timestamp of the analysis run this result was taken from. */
    val analysisCompletedAt: Instant,
)

/**
 * A single post-contingency limit violation, flattened for the frontend
 * (enums serialised as their names).
 */
data class ContingencyViolationDto(
    /** ID of the violating equipment (line, transformer, or bus). */
    val equipmentId: String,
    /** Equipment type name, e.g. "LINE" or "BUS". */
    val equipmentType: String,
    /** Violation type name: "THERMAL", "VOLTAGE_LOW", or "VOLTAGE_HIGH". */
    val violationType: String,
    /** Actual value: current in Amperes (thermal) or voltage in pu (voltage). */
    val value: Double,
    /** Applicable limit the value exceeded. */
    val limit: Double,
    /** Loading as a percentage of the limit. */
    val loadingPercent: Double,
    /** Severity name, e.g. "WARNING" or "CRITICAL". */
    val severity: String,
)
