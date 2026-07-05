package com.gridmaster.engine.contingency

import com.fasterxml.jackson.annotation.JsonIgnore
import com.gridmaster.engine.powerflow.EquipmentType
import com.gridmaster.engine.powerflow.ViolationSeverity
import java.time.Instant

/**
 * A single credible outage scenario: the simultaneous loss of one or more
 * network elements. N-1 contingencies have exactly one element; N-2 have two.
 */
data class Contingency(
    val id: String,
    val description: String,
    val elements: List<ContingencyElement>,
)

/** A single network element involved in a [Contingency]. */
sealed class ContingencyElement {
    data class LineOutage(val lineId: String) : ContingencyElement()

    data class TwoWindingsTransformerOutage(val transformerId: String) : ContingencyElement()

    data class ThreeWindingsTransformerOutage(val transformerId: String) : ContingencyElement()

    data class GeneratorOutage(val generatorId: String) : ContingencyElement()
}

/**
 * Parameters controlling a contingency analysis run.
 *
 * [dcPreScreening] — run a DC power flow per contingency first; only escalate
 * contingencies with DC violations to full AC analysis. Reduces AC solve count by ~80–90%.
 *
 * [postContingencyRatingMultiplier] — multiplier applied to branch ratings for
 * post-contingency checks. 1.0 = normal ratings. 1.1 = 110 % emergency rating.
 * Must be ≥ 1.0.
 */
data class ContingencyAnalysisParameters(
    val contingencies: List<Contingency> = emptyList(),
    val dcPreScreening: Boolean = true,
    val postContingencyRatingMultiplier: Double = 1.0,
) {
    init {
        require(postContingencyRatingMultiplier >= 1.0) {
            "postContingencyRatingMultiplier must be >= 1.0, got $postContingencyRatingMultiplier"
        }
    }
}

// ---------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------

/**
 * Full result of one N-1 contingency analysis run.
 *
 * [baseCaseSecure] — true if the pre-contingency network had no limit violations.
 * [criticalContingencies] — IDs of contingencies with at least one CRITICAL violation.
 * [preScreenedContingenciesCount] — contingencies marked SECURE by DC pre-screen (skipped AC).
 * [fullAcContingenciesCount] — contingencies evaluated with full AC power flow.
 */
data class ContingencyAnalysisResult(
    val baseCaseSecure: Boolean,
    val contingencyResults: List<ContingencyResult>,
    val criticalContingencies: List<String>,
    val analysisTimeMs: Long,
    val completedAt: Instant,
    val preScreenedContingenciesCount: Int,
    val fullAcContingenciesCount: Int,
) {
    /**
     * Contingency results indexed by every outaged element ID, computed once on
     * first access. Lets request handlers answer "what happens if element X trips"
     * in constant time instead of scanning [contingencyResults] per request.
     *
     * A multi-element contingency appears once per element; when several
     * contingencies share an element the first in [contingencyResults] wins.
     * Excluded from JSON serialisation — the result payload stays unchanged.
     */
    @get:JsonIgnore
    val resultsByElementId: Map<String, ContingencyResult> by lazy {
        buildMap {
            for (cr in contingencyResults) {
                for (element in cr.contingency.elements) {
                    val elementId =
                        when (element) {
                            is ContingencyElement.LineOutage -> element.lineId
                            is ContingencyElement.TwoWindingsTransformerOutage -> element.transformerId
                            is ContingencyElement.ThreeWindingsTransformerOutage -> element.transformerId
                            is ContingencyElement.GeneratorOutage -> element.generatorId
                        }
                    putIfAbsent(elementId, cr)
                }
            }
        }
    }
}

/** Result for a single contingency. */
data class ContingencyResult(
    val contingency: Contingency,
    val status: PostContingencyStatus,
    val violations: List<PostContingencyViolation>,
    /** Pre-computed for fast alert-system access. */
    val worstViolationSeverity: ViolationSeverity? =
        violations
            .mapNotNull { it.severity }
            .maxByOrNull { it.ordinal },
)

enum class PostContingencyStatus {
    /** No violations post-contingency. */
    SECURE,

    /** One or more limit violations detected. */
    VIOLATION,

    /** Post-contingency power flow did not converge. */
    NETWORK_FAILURE,
}

/** A limit violation detected in a post-contingency power flow. */
data class PostContingencyViolation(
    val equipmentId: String,
    val equipmentType: EquipmentType,
    val violationType: ViolationType,
    /** Actual value: current in Amperes (thermal) or voltage in pu (voltage). */
    val value: Double,
    /** Applicable limit (adjusted by [ContingencyAnalysisParameters.postContingencyRatingMultiplier]). */
    val limit: Double,
    val loadingPercent: Double,
    val severity: ViolationSeverity,
)

enum class ViolationType { THERMAL, VOLTAGE_LOW, VOLTAGE_HIGH }
