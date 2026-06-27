package com.gridmaster.api.websocket

import com.gridmaster.engine.model.Bus
import com.gridmaster.engine.model.FuelType
import com.gridmaster.engine.model.Generator
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.Line
import com.gridmaster.engine.model.Load
import com.gridmaster.engine.model.TwoWindingsTransformer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Unit tests for [GridNetwork.toNetworkWsDto].
 *
 * Tests the domain-to-DTO mapping logic directly, independently of the WebSocket
 * publisher and REST controller that both call this function.
 */
class WebSocketModelsTest {
    // region helpers

    private fun bus(
        id: String,
        voltageKv: Double = 132.0,
        voltageMagnitudePu: Double? = 1.02,
        voltageAngleDeg: Double? = 10.0,
        regionId: String? = "region-1",
    ) = Bus(
        id = id,
        name = "Bus $id",
        nominalVoltageKv = voltageKv,
        voltageMagnitudePu = voltageMagnitudePu,
        voltageAngleDeg = voltageAngleDeg,
        regionId = regionId,
    )

    private fun generator(
        id: String,
        busId: String,
        targetMw: Double,
        maxMw: Double = 500.0,
        connected: Boolean = true,
        fuelType: FuelType = FuelType.GAS,
    ) = Generator(
        id = id,
        name = "Gen $id",
        busId = busId,
        minActivePowerMw = 0.0,
        maxActivePowerMw = maxMw,
        targetActivePowerMw = targetMw,
        targetReactivePowerMvar = 0.0,
        targetVoltagePu = 1.0,
        connected = connected,
        fuelType = fuelType,
        marginalCostPerMwh = 50.0,
    )

    private fun load(
        id: String,
        busId: String,
        activeMw: Double,
        connected: Boolean = true,
    ) = Load(
        id = id,
        name = "Load $id",
        busId = busId,
        activePowerMw = activeMw,
        reactivePowerMvar = 10.0,
        connected = connected,
    )

    private fun line(
        id: String,
        ratingA: Double? = 500.0,
        currentFromA: Double? = null,
        currentToA: Double? = null,
    ) = Line(
        id = id,
        name = "Line $id",
        fromBusId = "B1",
        toBusId = "B2",
        ratingA = ratingA,
        currentFromA = currentFromA,
        currentToA = currentToA,
        activePowerFromMw = 120.0,
        reactivePowerFromMvar = 30.0,
        connected = true,
        resistanceOhm = 0.1,
        reactanceOhm = 0.4,
        shuntCapacitanceSiemens = 0.0001,
    )

    private fun transformer(
        id: String,
        ratingMva: Double? = 100.0,
        nominalVoltageFromKv: Double = 132.0,
        currentFromA: Double? = null,
        currentToA: Double? = null,
    ) = TwoWindingsTransformer(
        id = id,
        name = "TWT $id",
        fromBusId = "B1",
        toBusId = "B2",
        ratingMva = ratingMva,
        currentFromA = currentFromA,
        currentToA = currentToA,
        activePowerFromMw = 80.0,
        reactivePowerFromMvar = 20.0,
        connected = true,
        resistanceOhm = 0.5,
        reactanceOhm = 10.0,
        nominalVoltageHvKv = 132.0,
        nominalVoltageLvKv = 33.0,
        nominalVoltageFromKv = nominalVoltageFromKv,
        nominalVoltageToKv = 33.0,
    )

    private fun minimalNetwork(
        buses: List<Bus> = listOf(bus("B1"), bus("B2")),
        generators: List<Generator> = emptyList(),
        loads: List<Load> = emptyList(),
        lines: List<Line> = emptyList(),
        transformers: List<TwoWindingsTransformer> = emptyList(),
    ) = GridNetwork(
        id = "net-test",
        name = "Test Network",
        buses = buses,
        lines = lines,
        twoWindingsTransformers = transformers,
        threeWindingsTransformers = emptyList(),
        generators = generators,
        loads = loads,
        shuntCompensators = emptyList(),
        snapshotAt = Instant.EPOCH,
    )

    // endregion

    // region generator mapping

    /** Verifies the naming fix from #237: domain targetActivePowerMw -> DTO activePowerMw. */
    @Test
    fun `activePowerMw maps from domain targetActivePowerMw`() {
        val network = minimalNetwork(generators = listOf(generator("G1", "B1", targetMw = 250.0)))
        val dto = network.toNetworkWsDto()
        assertThat(dto.generators).hasSize(1)
        assertThat(dto.generators[0].activePowerMw).isEqualTo(250.0)
    }

    /** Verifies the naming fix from #237: domain connected -> DTO committed. */
    @Test
    fun `committed maps from domain connected field`() {
        val gens =
            listOf(
                generator("G1", "B1", targetMw = 200.0, connected = true),
                generator("G2", "B1", targetMw = 100.0, connected = false),
            )
        val dto = minimalNetwork(generators = gens).toNetworkWsDto()
        assertThat(dto.generators[0].committed).isTrue()
        assertThat(dto.generators[1].committed).isFalse()
    }

    @Test
    fun `generator maxActivePowerMw is mapped correctly`() {
        val network = minimalNetwork(generators = listOf(generator("G1", "B1", targetMw = 200.0, maxMw = 400.0)))
        val dto = network.toNetworkWsDto()
        assertThat(dto.generators[0].maxActivePowerMw).isEqualTo(400.0)
    }

    @Test
    fun `fuelType is mapped by enum name`() {
        val network = minimalNetwork(generators = listOf(generator("G1", "B1", targetMw = 100.0, fuelType = FuelType.NUCLEAR)))
        val dto = network.toNetworkWsDto()
        assertThat(dto.generators[0].fuelType).isEqualTo("NUCLEAR")
    }

    // endregion

    // region aggregate totals

    @Test
    fun `totalGenerationMw sums only connected generators`() {
        val gens =
            listOf(
                generator("G1", "B1", targetMw = 200.0, connected = true),
                generator("G2", "B1", targetMw = 100.0, connected = false),
                generator("G3", "B1", targetMw = 150.0, connected = true),
            )
        val dto = minimalNetwork(generators = gens).toNetworkWsDto()
        assertThat(dto.totalGenerationMw).isEqualTo(350.0)
    }

    @Test
    fun `totalLoadMw sums only connected loads`() {
        val loads =
            listOf(
                load("L1", "B1", activeMw = 80.0, connected = true),
                load("L2", "B1", activeMw = 40.0, connected = false),
                load("L3", "B2", activeMw = 60.0, connected = true),
            )
        val dto = minimalNetwork(loads = loads).toNetworkWsDto()
        assertThat(dto.totalLoadMw).isEqualTo(140.0)
    }

    @Test
    fun `totalGenerationMw and totalLoadMw are zero when no connected elements`() {
        val gens = listOf(generator("G1", "B1", targetMw = 200.0, connected = false))
        val loads = listOf(load("L1", "B1", activeMw = 100.0, connected = false))
        val dto = minimalNetwork(generators = gens, loads = loads).toNetworkWsDto()
        assertThat(dto.totalGenerationMw).isEqualTo(0.0)
        assertThat(dto.totalLoadMw).isEqualTo(0.0)
    }

    // endregion

    // region system marginal cost

    @Test
    fun `systemMarginalCostPerMwh is null when not provided`() {
        assertThat(minimalNetwork().toNetworkWsDto().systemMarginalCostPerMwh).isNull()
    }

    @Test
    fun `systemMarginalCostPerMwh passes through non-null value`() {
        assertThat(minimalNetwork().toNetworkWsDto(smc = 75.50).systemMarginalCostPerMwh).isEqualTo(75.50)
    }

    @Test
    fun `systemMarginalCostPerMwh can be explicitly null`() {
        assertThat(minimalNetwork().toNetworkWsDto(smc = null).systemMarginalCostPerMwh).isNull()
    }

    // endregion

    // region bus mapping

    @Test
    fun `bus voltageAngleDeg converts to radians`() {
        val dto = minimalNetwork(buses = listOf(bus("B1", voltageAngleDeg = 90.0))).toNetworkWsDto()
        assertThat(dto.buses[0].angleRad).isCloseTo(PI / 2.0, within(1e-9))
    }

    @Test
    fun `bus with null voltage fields defaults to 1_0 pu and 0_0 rad`() {
        val dto = minimalNetwork(buses = listOf(bus("B1", voltageMagnitudePu = null, voltageAngleDeg = null))).toNetworkWsDto()
        assertThat(dto.buses[0].voltagePu).isEqualTo(1.0)
        assertThat(dto.buses[0].angleRad).isEqualTo(0.0)
    }

    @Test
    fun `bus substationId maps from domain regionId`() {
        val dto = minimalNetwork(buses = listOf(bus("B1", regionId = "north"))).toNetworkWsDto()
        assertThat(dto.buses[0].substationId).isEqualTo("north")
    }

    // endregion

    // region line loadingPercent

    @Test
    fun `line loadingPercent is 0 when no rating set`() {
        val dto = minimalNetwork(lines = listOf(line("L1", ratingA = null, currentFromA = 300.0))).toNetworkWsDto()
        assertThat(dto.branches[0].loadingPercent).isEqualTo(0.0)
    }

    @Test
    fun `line loadingPercent uses max of from and to current`() {
        val dto = minimalNetwork(lines = listOf(line("L1", ratingA = 400.0, currentFromA = 200.0, currentToA = 320.0))).toNetworkWsDto()
        assertThat(dto.branches[0].loadingPercent).isCloseTo(80.0, within(1e-6)) // max(200,320)/400*100
    }

    @Test
    fun `line loadingPercent is 0 when currents are null`() {
        val dto = minimalNetwork(lines = listOf(line("L1", ratingA = 500.0))).toNetworkWsDto()
        assertThat(dto.branches[0].loadingPercent).isEqualTo(0.0)
    }

    // endregion

    // region transformer loadingPercent

    @Test
    fun `transformer loadingPercent is 0 when ratingMva is null`() {
        val dto = minimalNetwork(transformers = listOf(transformer("T1", ratingMva = null, currentFromA = 400.0))).toNetworkWsDto()
        assertThat(dto.branches[0].loadingPercent).isEqualTo(0.0)
    }

    @Test
    fun `transformer loadingPercent computed from MVA rating and from-side voltage`() {
        val ratingMva = 100.0
        val voltageKv = 132.0
        val ratingA = ratingMva * 1000.0 / (sqrt(3.0) * voltageKv)
        val twt = transformer("T1", ratingMva = ratingMva, nominalVoltageFromKv = voltageKv, currentFromA = ratingA * 0.6)
        val dto = minimalNetwork(transformers = listOf(twt)).toNetworkWsDto()
        assertThat(dto.branches[0].loadingPercent).isCloseTo(60.0, within(0.001))
    }

    // endregion

    // region branch ordering and load list completeness

    @Test
    fun `branches list contains lines before transformers`() {
        val dto = minimalNetwork(lines = listOf(line("LINE-1")), transformers = listOf(transformer("TWT-1"))).toNetworkWsDto()
        assertThat(dto.branches).hasSize(2)
        assertThat(dto.branches[0].id).isEqualTo("LINE-1")
        assertThat(dto.branches[1].id).isEqualTo("TWT-1")
    }

    @Test
    fun `loads list includes disconnected elements but totalLoadMw excludes them`() {
        val loads =
            listOf(
                load("L1", "B1", activeMw = 100.0, connected = true),
                load("L2", "B2", activeMw = 50.0, connected = false),
            )
        val dto = minimalNetwork(loads = loads).toNetworkWsDto()
        assertThat(dto.loads).hasSize(2)
        assertThat(dto.totalLoadMw).isEqualTo(100.0)
    }

    // endregion
}
