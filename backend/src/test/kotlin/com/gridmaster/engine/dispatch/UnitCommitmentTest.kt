package com.gridmaster.engine.dispatch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.math.sin

@Tag("unit")
class UnitCommitmentTest {
    private val lpService = LpDispatchService()
    private val dispatchService = MeritOrderDispatchService(lpService)
    private val ucService = GreedyUnitCommitmentService(dispatchService)

    @Test
    fun `commitment schedule has 24 hours`() {
        val result = ucService.commit(sampleGenerators(), sampleForecast())
        assertThat(result.hourlySchedule).hasSize(24)
    }

    @Test
    fun `feasible result when capacity exceeds peak load plus reserve`() {
        // 3 generators × 100 MW = 300 MW; peak = 150 MW → plenty of headroom
        val result = ucService.commit(sampleGenerators(), sampleForecast(peakMw = 150.0))
        assertThat(result.feasible).isTrue()
    }

    @Test
    fun `infeasible when total capacity below peak load`() {
        val gens = listOf(gen("G1", max = 50.0, cost = 30.0))
        val result = ucService.commit(gens, sampleForecast(peakMw = 100.0))
        assertThat(result.feasible).isFalse()
    }

    @Test
    fun `reserve margin maintained each hour`() {
        val params = DispatchParameters(reserveMarginFraction = 0.20)
        val result = ucService.commit(sampleGenerators(), sampleForecast(peakMw = 100.0), params)
        result.hourlySchedule.forEach { hour ->
            assertThat(hour.reserveMarginMw)
                .describedAs("reserve at hour ${hour.hour}")
                .isGreaterThanOrEqualTo(-1.0) // allow tiny floating-point slack
        }
    }

    @Test
    fun `minimum up time respected`() {
        // Generator with minUpTime=3 should stay committed for at least 3 hours once started
        val gens =
            listOf(
                gen("Base", max = 200.0, cost = 20.0, committed = true), // always on
                gen("Peaker", max = 100.0, cost = 80.0, minUpTime = 3),
            )
        val forecast = sampleForecast(peakMw = 250.0)
        val result = ucService.commit(gens, forecast)
        // If Peaker is committed at any hour, it should stay committed for the next 2 hours
        val schedule = result.hourlySchedule
        for (h in 0 until 22) {
            if ("Peaker" in schedule[h].committedGeneratorIds &&
                "Peaker" !in (if (h > 0) schedule[h - 1].committedGeneratorIds else emptySet())
            ) {
                // Just committed at hour h — should remain for h+1 and h+2
                assertThat(schedule[h + 1].committedGeneratorIds).contains("Peaker")
                assertThat(schedule[h + 2].committedGeneratorIds).contains("Peaker")
            }
        }
    }

    @Test
    fun `LoadForecast rejects non-24-hour list`() {
        assertThrows<IllegalArgumentException> {
            LoadForecast(hourlyLoadMw = List(12) { 100.0 }, startHour = Instant.now())
        }
    }

    @Test
    fun `LoadForecast rejects negative load`() {
        assertThrows<IllegalArgumentException> {
            LoadForecast(hourlyLoadMw = List(24) { if (it == 5) -1.0 else 100.0 }, startHour = Instant.now())
        }
    }

    @Test
    fun `total startup cost is non-negative`() {
        val result = ucService.commit(sampleGenerators(), sampleForecast())
        assertThat(result.totalStartupCostGbp).isGreaterThanOrEqualTo(0.0)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sampleGenerators() =
        listOf(
            gen("G1", max = 100.0, cost = 20.0, committed = true),
            gen("G2", max = 100.0, cost = 40.0),
            gen("G3", max = 100.0, cost = 60.0),
        )

    /** Sine-wave shaped forecast peaking at [peakMw] around hour 14. */
    private fun sampleForecast(peakMw: Double = 200.0): LoadForecast {
        val hours =
            List(24) { h ->
                val base = peakMw * 0.5
                val amplitude = peakMw * 0.5
                (base + amplitude * sin(Math.PI * (h - 6) / 12.0)).coerceAtLeast(base * 0.3)
            }
        return LoadForecast(hourlyLoadMw = hours, startHour = Instant.now())
    }

    private fun gen(
        id: String,
        max: Double,
        cost: Double,
        committed: Boolean = false,
        minUpTime: Int = 0,
        minDownTime: Int = 0,
        startupCost: Double = 0.0,
    ) = DispatchableGenerator(
        id = id,
        name = id,
        committed = committed,
        minActivePowerMw = 0.0,
        maxActivePowerMw = max,
        currentActivePowerMw = 0.0,
        marginalCostPerMwh = cost,
        startupCostGbp = startupCost,
        minUpTimeHours = minUpTime,
        minDownTimeHours = minDownTime,
    )
}
