package com.gridmaster.engine.dispatch

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class MeritOrderTest {
    private val lpService = LpDispatchService()
    private val service = MeritOrderDispatchService(lpService)

    // -------------------------------------------------------------------------
    // Basic merit order
    // -------------------------------------------------------------------------

    @Test
    fun `cheapest generator dispatched first`() {
        val gens =
            listOf(
                gen("G1", min = 0.0, max = 100.0, cost = 50.0),
                gen("G2", min = 0.0, max = 100.0, cost = 30.0),
                gen("G3", min = 0.0, max = 100.0, cost = 70.0),
            )
        val result = service.economicDispatch(gens, totalLoadMw = 80.0)

        val g2 = result.targets.first { it.generatorId == "G2" }
        val g1 = result.targets.first { it.generatorId == "G1" }
        val g3 = result.targets.first { it.generatorId == "G3" }
        assertThat(g2.targetMw).isCloseTo(80.0, Offset.offset(0.01))
        assertThat(g1.targetMw).isCloseTo(0.0, Offset.offset(0.01))
        assertThat(g3.targetMw).isCloseTo(0.0, Offset.offset(0.01))
    }

    @Test
    fun `load balanced across multiple generators`() {
        val gens =
            listOf(
                gen("G1", min = 0.0, max = 100.0, cost = 30.0),
                gen("G2", min = 0.0, max = 100.0, cost = 50.0),
            )
        val result = service.economicDispatch(gens, totalLoadMw = 150.0)

        assertThat(result.totalDispatchedMw).isCloseTo(150.0, Offset.offset(0.01))
        assertThat(result.unservedLoadMw).isCloseTo(0.0, Offset.offset(0.01))
        assertThat(result.targets.first { it.generatorId == "G1" }.targetMw).isCloseTo(100.0, Offset.offset(0.01))
        assertThat(result.targets.first { it.generatorId == "G2" }.targetMw).isCloseTo(50.0, Offset.offset(0.01))
    }

    @Test
    fun `system marginal cost is cost of marginal unit`() {
        val gens =
            listOf(
                gen("G1", min = 0.0, max = 100.0, cost = 30.0),
                gen("G2", min = 0.0, max = 100.0, cost = 50.0),
            )
        val result = service.economicDispatch(gens, totalLoadMw = 120.0)

        assertThat(result.systemMarginalCostPerMwh).isCloseTo(50.0, Offset.offset(0.01))
    }

    @Test
    fun `must-run minimum always dispatched before merit order`() {
        val gens =
            listOf(
                gen("G1", min = 20.0, max = 100.0, cost = 80.0), // expensive but must-run at 20 MW
                gen("G2", min = 0.0, max = 100.0, cost = 30.0),
            )
        val result = service.economicDispatch(gens, totalLoadMw = 60.0)

        val g1 = result.targets.first { it.generatorId == "G1" }
        val g2 = result.targets.first { it.generatorId == "G2" }
        assertThat(g1.targetMw).isGreaterThanOrEqualTo(20.0) // at minimum
        assertThat(g2.targetMw).isCloseTo(40.0, Offset.offset(0.01)) // fills remainder cheaply
        assertThat(result.totalDispatchedMw).isCloseTo(60.0, Offset.offset(0.01))
    }

    @Test
    fun `uncommitted generators are excluded`() {
        val gens =
            listOf(
                gen("G1", min = 0.0, max = 100.0, cost = 30.0, committed = false),
                gen("G2", min = 0.0, max = 100.0, cost = 50.0),
            )
        val result = service.economicDispatch(gens, totalLoadMw = 50.0)

        assertThat(result.targets.none { it.generatorId == "G1" && it.targetMw > 0.0 }).isTrue()
        assertThat(result.targets.first { it.generatorId == "G2" }.targetMw).isCloseTo(50.0, Offset.offset(0.01))
    }

    @Test
    fun `unserved load reported when capacity insufficient`() {
        val gens = listOf(gen("G1", min = 0.0, max = 50.0, cost = 30.0))
        val result = service.economicDispatch(gens, totalLoadMw = 80.0)

        assertThat(result.unservedLoadMw).isCloseTo(30.0, Offset.offset(0.01))
        assertThat(result.totalDispatchedMw).isCloseTo(50.0, Offset.offset(0.01))
    }

    @Test
    fun `merit order table has correct order and marginal unit flagged`() {
        val gens =
            listOf(
                gen("G1", min = 0.0, max = 100.0, cost = 50.0),
                gen("G2", min = 0.0, max = 100.0, cost = 30.0),
            )
        val result = service.economicDispatch(gens, totalLoadMw = 80.0)

        assertThat(result.meritOrder.map { it.generatorId }).isEqualTo(listOf("G2", "G1"))
        val marginal = result.meritOrder.first { it.isMarginalUnit }
        assertThat(marginal.generatorId).isEqualTo("G2")
    }

    @Test
    fun `zero load produces zero dispatch`() {
        val gens = listOf(gen("G1", min = 0.0, max = 100.0, cost = 30.0))
        val result = service.economicDispatch(gens, totalLoadMw = 0.0)

        assertThat(result.totalDispatchedMw).isCloseTo(0.0, Offset.offset(0.01))
        assertThat(result.unservedLoadMw).isCloseTo(0.0, Offset.offset(0.01))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun gen(
        id: String,
        min: Double,
        max: Double,
        cost: Double,
        committed: Boolean = true,
    ) = DispatchableGenerator(
        id = id,
        name = id,
        committed = committed,
        minActivePowerMw = min,
        maxActivePowerMw = max,
        currentActivePowerMw = 0.0,
        marginalCostPerMwh = cost,
    )
}
