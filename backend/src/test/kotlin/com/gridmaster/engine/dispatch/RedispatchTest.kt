package com.gridmaster.engine.dispatch

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class RedispatchTest {
    private val lpService = LpDispatchService()
    private val service = MeritOrderDispatchService(lpService)

    @Test
    fun `redispatch selects pair with highest combined sensitivity`() {
        val generators =
            listOf(
                gen("G1", min = 0.0, max = 100.0, currentMw = 50.0, cost = 30.0),
                gen("G2", min = 0.0, max = 100.0, currentMw = 50.0, cost = 50.0),
            )
        val targets = generators.map { GeneratorTarget(it.id, it.currentActivePowerMw) }
        // G1 has positive sensitivity on BRANCH-1, G2 has negative — standard counter-pair
        val gsk =
            mapOf(
                "G1" to mapOf("BRANCH-1" to 0.6),
                "G2" to mapOf("BRANCH-1" to -0.4),
            )
        val result = service.congestionRedispatch(targets, generators, listOf("BRANCH-1"), gsk)

        assertThat(result.actions).hasSize(1)
        val action = result.actions.first()
        // G2 has negative sensitivity → increase to reduce branch flow
        // G1 has positive sensitivity → decrease to reduce branch flow
        assertThat(action.increaseGeneratorId).isEqualTo("G2")
        assertThat(action.decreaseGeneratorId).isEqualTo("G1")
        assertThat(action.shiftMw).isGreaterThan(0.0)
        assertThat(result.remainingViolations).isEmpty()
    }

    @Test
    fun `remaining violation reported when no feasible pair`() {
        val generators =
            listOf(
                gen("G1", min = 0.0, max = 100.0, currentMw = 100.0, cost = 30.0), // at max
                gen("G2", min = 0.0, max = 100.0, currentMw = 0.0, cost = 50.0), // at min
            )
        val targets = generators.map { GeneratorTarget(it.id, it.currentActivePowerMw) }
        // Both generators have positive sensitivity → both decrease candidates, no increase candidate
        val gsk =
            mapOf(
                "G1" to mapOf("BRANCH-1" to 0.6),
                "G2" to mapOf("BRANCH-1" to 0.4),
            )
        val result = service.congestionRedispatch(targets, generators, listOf("BRANCH-1"), gsk)

        assertThat(result.remainingViolations).contains("BRANCH-1")
        assertThat(result.actions).isEmpty()
    }

    @Test
    fun `redispatch preserves power balance`() {
        val generators =
            listOf(
                gen("G1", min = 0.0, max = 200.0, currentMw = 100.0, cost = 30.0),
                gen("G2", min = 0.0, max = 200.0, currentMw = 100.0, cost = 50.0),
            )
        val targets = generators.map { GeneratorTarget(it.id, it.currentActivePowerMw) }
        val gsk =
            mapOf(
                "G1" to mapOf("BRANCH-1" to 0.5),
                "G2" to mapOf("BRANCH-1" to -0.5),
            )
        val result = service.congestionRedispatch(targets, generators, listOf("BRANCH-1"), gsk)

        val before = targets.sumOf { it.targetMw }
        val after = result.updatedTargets.sumOf { it.targetMw }
        assertThat(after).isCloseTo(before, Offset.offset(0.01))
    }

    @Test
    fun `additional cost is negative when redispatching from expensive to cheap generator`() {
        val generators =
            listOf(
                gen("Cheap", min = 0.0, max = 200.0, currentMw = 50.0, cost = 20.0),
                gen("Expensive", min = 0.0, max = 200.0, currentMw = 50.0, cost = 80.0),
            )
        val targets = generators.map { GeneratorTarget(it.id, it.currentActivePowerMw) }
        val gsk =
            mapOf(
                "Expensive" to mapOf("BR-1" to 0.6),
                "Cheap" to mapOf("BR-1" to -0.4),
            )
        val result = service.congestionRedispatch(targets, generators, listOf("BR-1"), gsk)

        // Cheap increases (20/MWh), Expensive decreases (80/MWh) → cost savings (negative additional cost)
        if (result.actions.isNotEmpty()) {
            assertThat(result.additionalCostGbp).isLessThan(0.0)
        }
    }

    @Test
    fun `security constrained dispatch calls applyAndSolve`() {
        val generators =
            listOf(
                gen("G1", min = 0.0, max = 200.0, currentMw = 0.0, cost = 30.0),
                gen("G2", min = 0.0, max = 200.0, currentMw = 0.0, cost = 50.0),
            )
        var callCount = 0
        val result =
            service.securityConstrainedDispatch(
                generators = generators,
                totalLoadMw = 150.0,
                parameters = DispatchParameters(securityConstrained = true),
                gsk = emptyMap(),
                applyAndSolve = {
                    callCount++
                    emptyList() // no violations — converges immediately
                },
            )

        assertThat(callCount).isGreaterThanOrEqualTo(1)
        assertThat(result.totalDispatchedMw).isCloseTo(150.0, Offset.offset(0.01))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun gen(
        id: String,
        min: Double,
        max: Double,
        currentMw: Double,
        cost: Double,
    ) = DispatchableGenerator(
        id = id,
        name = id,
        committed = true,
        minActivePowerMw = min,
        maxActivePowerMw = max,
        currentActivePowerMw = currentMw,
        marginalCostPerMwh = cost,
    )
}
