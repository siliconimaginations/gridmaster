package com.gridmaster.engine.dispatch

import com.google.ortools.Loader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for OR-Tools-backed dispatch and unit commitment services.
 *
 * OR-Tools native libraries are loaded once per JVM via [Loader.loadNativeLibraries].
 * These tests verify that the LP and MIP solvers produce correct, feasible results.
 */
@Tag("unit")
class OrToolsDispatchTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun loadNativeLibs() {
            Loader.loadNativeLibraries()
        }

        private fun gen(
            id: String,
            min: Double = 0.0,
            max: Double,
            cost: Double,
            committed: Boolean = true,
            startupCost: Double = 0.0,
            minUpHours: Int = 0,
            minDownHours: Int = 0,
        ) = DispatchableGenerator(
            id = id,
            name = id,
            committed = committed,
            minActivePowerMw = min,
            maxActivePowerMw = max,
            currentActivePowerMw = 0.0,
            marginalCostPerMwh = cost,
            startupCostGbp = startupCost,
            minUpTimeHours = minUpHours,
            minDownTimeHours = minDownHours,
        )

        private fun flatForecast(loadMw: Double) =
            LoadForecast(
                hourlyLoadMw = List(24) { loadMw },
                startHour = Instant.now(),
            )
    }

    private val lpService = LpDispatchService()

    // ── LP dispatch ───────────────────────────────────────────────────────────

    @Test
    fun `LP dispatch serves exact load from two committed generators`() {
        val gens = listOf(gen("G1", max = 100.0, cost = 30.0), gen("G2", max = 100.0, cost = 50.0))
        val result = lpService.economicDispatch(gens, totalLoadMw = 80.0, DispatchParameters(mode = DispatchMode.LP))

        assertThat(result.totalDispatchedMw).isCloseTo(80.0, Offset.offset(0.01))
        assertThat(result.unservedLoadMw).isCloseTo(0.0, Offset.offset(0.01))
    }

    @Test
    fun `LP dispatch minimises cost — cheaper generator dispatched first`() {
        val gens =
            listOf(
                gen("cheap", max = 100.0, cost = 20.0),
                gen("expensive", max = 100.0, cost = 80.0),
            )
        val result = lpService.economicDispatch(gens, totalLoadMw = 80.0, DispatchParameters(mode = DispatchMode.LP))

        val cheapTarget = result.targets.find { it.generatorId == "cheap" }!!
        val expensiveTarget = result.targets.find { it.generatorId == "expensive" }!!

        // All load served by cheap generator (80 MW < 100 MW max)
        assertThat(cheapTarget.targetMw).isCloseTo(80.0, Offset.offset(0.1))
        assertThat(expensiveTarget.targetMw).isCloseTo(0.0, Offset.offset(0.1))
    }

    @Test
    fun `LP dispatch respects must-run minimum output`() {
        val gens =
            listOf(
                gen("G1", min = 20.0, max = 100.0, cost = 30.0),
                gen("G2", min = 20.0, max = 100.0, cost = 50.0),
            )
        val result = lpService.economicDispatch(gens, totalLoadMw = 60.0, DispatchParameters(mode = DispatchMode.LP))

        val g1 = result.targets.find { it.generatorId == "G1" }!!
        val g2 = result.targets.find { it.generatorId == "G2" }!!
        assertThat(g1.targetMw).isGreaterThanOrEqualTo(20.0)
        assertThat(g2.targetMw).isGreaterThanOrEqualTo(20.0)
        assertThat(result.totalDispatchedMw).isCloseTo(60.0, Offset.offset(0.01))
    }

    @Test
    fun `LP dispatch handles uncommitted generators — excluded from solve`() {
        val gens =
            listOf(
                gen("on", max = 100.0, cost = 30.0, committed = true),
                gen("off", max = 100.0, cost = 10.0, committed = false),
            )
        val result = lpService.economicDispatch(gens, totalLoadMw = 50.0, DispatchParameters(mode = DispatchMode.LP))

        // The cheaper but uncommitted generator should NOT be dispatched
        val offTarget = result.targets.find { it.generatorId == "off" }
        assertThat(offTarget?.targetMw ?: 0.0).isCloseTo(0.0, Offset.offset(0.01))
        assertThat(result.totalDispatchedMw).isCloseTo(50.0, Offset.offset(0.01))
    }

    @Test
    fun `LP dispatch returns unserved load when capacity is insufficient`() {
        val gens = listOf(gen("G1", max = 40.0, cost = 30.0))
        val result = lpService.economicDispatch(gens, totalLoadMw = 100.0, DispatchParameters(mode = DispatchMode.LP))

        assertThat(result.unservedLoadMw).isCloseTo(60.0, Offset.offset(0.01))
        assertThat(result.totalDispatchedMw).isCloseTo(40.0, Offset.offset(0.01))
    }

    @Test
    fun `LP dispatch with no committed generators returns zero dispatch`() {
        val gens = listOf(gen("G1", max = 100.0, cost = 30.0, committed = false))
        val result = lpService.economicDispatch(gens, totalLoadMw = 50.0, DispatchParameters(mode = DispatchMode.LP))

        assertThat(result.totalDispatchedMw).isEqualTo(0.0)
        assertThat(result.unservedLoadMw).isEqualTo(50.0)
    }

    // ── MIP unit commitment ───────────────────────────────────────────────────

    @Test
    fun `MIP UC falls back to greedy for small networks`() {
        val gens = List(5) { i -> gen("G$i", max = 100.0, cost = (i + 1) * 10.0) }
        val mipService = MipUnitCommitmentService(GreedyUnitCommitmentService(MeritOrderDispatchService(lpService)))

        val result = mipService.commit(gens, flatForecast(200.0))
        assertThat(result.hourlySchedule).hasSize(24)
        assertThat(result.feasible).isTrue()
    }

    @Test
    fun `MIP UC produces feasible 24-hour schedule for medium network`() {
        // 10 generators — above greedy threshold of 8
        val gens =
            List(10) { i ->
                gen("G$i", min = 5.0, max = 80.0, cost = (i + 1) * 8.0, committed = i < 5)
            }
        val mipService = MipUnitCommitmentService(GreedyUnitCommitmentService(MeritOrderDispatchService(lpService)))

        val result = mipService.commit(gens, flatForecast(200.0))

        assertThat(result.hourlySchedule).hasSize(24)
        assertThat(result.feasible).isTrue()
        // Every hour should serve all load
        result.hourlySchedule.forEach { hour ->
            assertThat(hour.targets.sumOf { it.targetMw }).isCloseTo(200.0, Offset.offset(1.0))
        }
    }

    @Test
    fun `MIP UC minimises total cost — cheap generators committed first`() {
        val gens =
            listOf(
                gen("cheap1", max = 100.0, cost = 10.0, committed = false),
                gen("cheap2", max = 100.0, cost = 15.0, committed = false),
                gen("expensive1", max = 100.0, cost = 80.0, committed = false),
                gen("expensive2", max = 100.0, cost = 90.0, committed = false),
                gen("expensive3", max = 100.0, cost = 95.0, committed = false),
                gen("expensive4", max = 100.0, cost = 100.0, committed = false),
                gen("expensive5", max = 100.0, cost = 110.0, committed = false),
                gen("expensive6", max = 100.0, cost = 120.0, committed = false),
                gen("extra1", max = 50.0, cost = 200.0, committed = false),
                gen("extra2", max = 50.0, cost = 210.0, committed = false),
            )
        val mipService = MipUnitCommitmentService(GreedyUnitCommitmentService(MeritOrderDispatchService(lpService)))

        val result = mipService.commit(gens, flatForecast(150.0))

        assertThat(result.feasible).isTrue()
        // cheap generators should be committed every hour
        val hour0 = result.hourlySchedule[0]
        assertThat(hour0.committedGeneratorIds).contains("cheap1", "cheap2")
    }

    @Test
    fun `MIP UC respects reserve margin`() {
        val gens = List(10) { i -> gen("G$i", max = 60.0, cost = (i + 1) * 5.0) }
        val params = DispatchParameters(reserveMarginFraction = 0.20)
        val mipService = MipUnitCommitmentService(GreedyUnitCommitmentService(MeritOrderDispatchService(lpService)))

        val result = mipService.commit(gens, flatForecast(100.0), params)

        assertThat(result.feasible).isTrue()
        result.hourlySchedule.forEach { hour ->
            val committedCapacity = hour.committedGeneratorIds.size * 60.0
            assertThat(committedCapacity).isGreaterThanOrEqualTo(100.0 * 1.20 - 0.1)
        }
    }
}
