package com.gridmaster.engine.contingency

import com.gridmaster.engine.powerflow.EquipmentType
import com.gridmaster.engine.powerflow.ViolationSeverity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Unit tests for domain model logic in contingency results. */
class ContingencyResultTest {
    // -------------------------------------------------------------------------
    // ContingencyResult.worstViolationSeverity
    // -------------------------------------------------------------------------

    @Test
    fun `worstViolationSeverity is null when no violations`() {
        val result = result(emptyList())
        assertThat(result.worstViolationSeverity).isNull()
    }

    @Test
    fun `worstViolationSeverity returns CRITICAL when critical violation present`() {
        val result =
            result(
                listOf(
                    violation(ViolationSeverity.WARNING),
                    violation(ViolationSeverity.CRITICAL),
                    violation(ViolationSeverity.ALARM),
                ),
            )
        assertThat(result.worstViolationSeverity).isEqualTo(ViolationSeverity.CRITICAL)
    }

    @Test
    fun `worstViolationSeverity returns ALARM when no critical`() {
        val result =
            result(
                listOf(
                    violation(ViolationSeverity.WARNING),
                    violation(ViolationSeverity.ALARM),
                ),
            )
        assertThat(result.worstViolationSeverity).isEqualTo(ViolationSeverity.ALARM)
    }

    @Test
    fun `worstViolationSeverity returns WARNING when only warnings`() {
        val result = result(listOf(violation(ViolationSeverity.WARNING)))
        assertThat(result.worstViolationSeverity).isEqualTo(ViolationSeverity.WARNING)
    }

    // -------------------------------------------------------------------------
    // ContingencyAnalysisParameters validation
    // -------------------------------------------------------------------------

    @Test
    fun `ContingencyAnalysisParameters rejects multiplier below 1`() {
        assertThrows<IllegalArgumentException> {
            ContingencyAnalysisParameters(postContingencyRatingMultiplier = 0.9)
        }
    }

    @Test
    fun `ContingencyAnalysisParameters accepts multiplier of exactly 1`() {
        val params = ContingencyAnalysisParameters(postContingencyRatingMultiplier = 1.0)
        assertThat(params.postContingencyRatingMultiplier).isEqualTo(1.0)
    }

    @Test
    fun `ContingencyAnalysisParameters accepts multiplier above 1`() {
        val params = ContingencyAnalysisParameters(postContingencyRatingMultiplier = 1.1)
        assertThat(params.postContingencyRatingMultiplier).isEqualTo(1.1)
    }

    // -------------------------------------------------------------------------
    // ContingencyAnalysisCache
    // -------------------------------------------------------------------------

    @Test
    fun `cache returns null before any result stored`() {
        val cache = ContingencyAnalysisCache()
        assertThat(cache.latest()).isNull()
    }

    @Test
    fun `cache returns latest stored result`() {
        val cache = ContingencyAnalysisCache()
        val stored = analysisResult(listOf("C1", "C2"))
        cache.update(stored)
        assertThat(cache.latest()).isEqualTo(stored)
    }

    @Test
    fun `cache update replaces previous result`() {
        val cache = ContingencyAnalysisCache()
        cache.update(analysisResult(listOf("C1")))
        val second = analysisResult(listOf("C2"))
        cache.update(second)
        assertThat(cache.latest()).isEqualTo(second)
    }

    @Test
    fun `cache clear sets result to null`() {
        val cache = ContingencyAnalysisCache()
        cache.update(analysisResult(emptyList()))
        cache.clear()
        assertThat(cache.latest()).isNull()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun violation(severity: ViolationSeverity) =
        PostContingencyViolation(
            equipmentId = "L1",
            equipmentType = EquipmentType.LINE,
            violationType = ViolationType.THERMAL,
            value = 480.0,
            limit = 500.0,
            loadingPercent = 96.0,
            severity = severity,
        )

    private fun result(violations: List<PostContingencyViolation>) =
        ContingencyResult(
            contingency = Contingency("C1", "Test", listOf(ContingencyElement.LineOutage("L1"))),
            status = if (violations.isEmpty()) PostContingencyStatus.SECURE else PostContingencyStatus.VIOLATION,
            violations = violations,
        )

    private fun analysisResult(criticalIds: List<String>) =
        ContingencyAnalysisResult(
            baseCaseSecure = true,
            contingencyResults = emptyList(),
            criticalContingencies = criticalIds,
            analysisTimeMs = 100L,
            completedAt = java.time.Instant.now(),
            preScreenedContingenciesCount = 0,
            fullAcContingenciesCount = 0,
        )
}
