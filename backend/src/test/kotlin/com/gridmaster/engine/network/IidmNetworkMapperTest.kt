package com.gridmaster.engine.network

import com.gridmaster.engine.model.FuelType
import com.gridmaster.engine.model.Region
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [IidmNetworkMapperImpl].
 * Uses [TestNetworkFactory] to build a small in-memory IIDM network; no solver invoked.
 */
class IidmNetworkMapperTest {
    private lateinit var mapper: IidmNetworkMapperImpl

    @BeforeEach
    fun setUp() {
        val metadata =
            mapOf(
                TestNetworkFactory.GENERATOR_1 to GeneratorMetadata(FuelType.GAS, 48.0),
                TestNetworkFactory.GENERATOR_2 to GeneratorMetadata(FuelType.COAL, 35.0),
            )
        mapper = IidmNetworkMapperImpl(MapGeneratorMetadataProvider(metadata))
    }

    // -------------------------------------------------------------------------
    // Bus mapping
    // -------------------------------------------------------------------------

    @Test
    fun `toGridNetwork maps all buses`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        // 5 buses: B1, B1L, B2, B3, B4
        assertThat(snapshot.buses).hasSize(5)
        val ids = snapshot.buses.map { it.id }.toSet()
        assertThat(ids).containsAll(
            setOf(
                TestNetworkFactory.BUS_1,
                TestNetworkFactory.BUS_2,
                TestNetworkFactory.BUS_3,
                TestNetworkFactory.BUS_4,
            ),
        )
    }

    @Test
    fun `bus nominal voltage is populated`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        val b1 = snapshot.buses.first { it.id == TestNetworkFactory.BUS_1 }
        assertThat(b1.nominalVoltageKv).isEqualTo(220.0)
        val b1l = snapshot.buses.first { it.id == TestNetworkFactory.BUS_1L }
        assertThat(b1l.nominalVoltageKv).isEqualTo(110.0)
    }

    @Test
    fun `bus voltage is null before power flow`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        snapshot.buses.forEach { bus ->
            assertThat(bus.voltageMagnitudePu).isNull()
            assertThat(bus.voltageAngleDeg).isNull()
        }
    }

    @Test
    fun `bus region annotation is applied`() {
        val network = TestNetworkFactory.create()
        val regions =
            listOf(
                Region("R1", "North", setOf(TestNetworkFactory.BUS_1, TestNetworkFactory.BUS_2)),
                Region("R2", "South", setOf(TestNetworkFactory.BUS_3, TestNetworkFactory.BUS_4)),
            )
        val snapshot = mapper.toGridNetwork(network, regions)

        val b1 = snapshot.buses.first { it.id == TestNetworkFactory.BUS_1 }
        assertThat(b1.regionId).isEqualTo("R1")
        val b3 = snapshot.buses.first { it.id == TestNetworkFactory.BUS_3 }
        assertThat(b3.regionId).isEqualTo("R2")
    }

    // -------------------------------------------------------------------------
    // Line mapping
    // -------------------------------------------------------------------------

    @Test
    fun `toGridNetwork maps all lines`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        assertThat(snapshot.lines).hasSize(4)
        val lineIds = snapshot.lines.map { it.id }.toSet()
        assertThat(lineIds).containsExactlyInAnyOrder(
            TestNetworkFactory.LINE_12,
            TestNetworkFactory.LINE_23,
            TestNetworkFactory.LINE_34,
            TestNetworkFactory.LINE_14,
        )
    }

    @Test
    fun `line impedance and terminal buses are correct`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        val l12 = snapshot.lines.first { it.id == TestNetworkFactory.LINE_12 }
        assertThat(l12.fromBusId).isEqualTo(TestNetworkFactory.BUS_1)
        assertThat(l12.toBusId).isEqualTo(TestNetworkFactory.BUS_2)
        assertThat(l12.resistanceOhm).isEqualTo(0.5)
        assertThat(l12.reactanceOhm).isEqualTo(5.0)
    }

    @Test
    fun `line current rating is mapped when set`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        val l12 = snapshot.lines.first { it.id == TestNetworkFactory.LINE_12 }
        assertThat(l12.ratingA).isEqualTo(500.0)
    }

    @Test
    fun `line current rating is null when not set`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        val l23 = snapshot.lines.first { it.id == TestNetworkFactory.LINE_23 }
        assertThat(l23.ratingA).isNull()
    }

    @Test
    fun `line currents are null before power flow`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        snapshot.lines.forEach { line ->
            assertThat(line.currentFromA).isNull()
            assertThat(line.currentToA).isNull()
        }
    }

    @Test
    fun `line shunt capacitance is sum of B1 and B2`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        val l23 = snapshot.lines.first { it.id == TestNetworkFactory.LINE_23 }
        assertThat(l23.shuntCapacitanceSiemens).isEqualTo(2e-4, org.assertj.core.data.Offset.offset(1e-10))
    }

    // -------------------------------------------------------------------------
    // Transformer mapping
    // -------------------------------------------------------------------------

    @Test
    fun `toGridNetwork maps two-winding transformer`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        assertThat(snapshot.twoWindingsTransformers).hasSize(1)
        val tx = snapshot.twoWindingsTransformers.first()
        assertThat(tx.id).isEqualTo(TestNetworkFactory.TRANSFORMER_12)
        assertThat(tx.fromBusId).isEqualTo(TestNetworkFactory.BUS_1)
        assertThat(tx.toBusId).isEqualTo(TestNetworkFactory.BUS_1L)
        assertThat(tx.nominalVoltageHvKv).isEqualTo(220.0)
        assertThat(tx.nominalVoltageLvKv).isEqualTo(110.0)
        assertThat(tx.ratingMva).isEqualTo(200.0)
    }

    @Test
    fun `transformer tap position defaults to 0 when no tap changer`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        val tx = snapshot.twoWindingsTransformers.first()
        assertThat(tx.ratioTapPosition).isEqualTo(0)
    }

    @Test
    fun `no three-winding transformers in test network`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        assertThat(snapshot.threeWindingsTransformers).isEmpty()
    }

    // -------------------------------------------------------------------------
    // Generator mapping
    // -------------------------------------------------------------------------

    @Test
    fun `toGridNetwork maps all generators`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        assertThat(snapshot.generators).hasSize(2)
        val ids = snapshot.generators.map { it.id }.toSet()
        assertThat(ids).containsExactlyInAnyOrder(
            TestNetworkFactory.GENERATOR_1,
            TestNetworkFactory.GENERATOR_2,
        )
    }

    @Test
    fun `generator fields are correctly mapped`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        val g1 = snapshot.generators.first { it.id == TestNetworkFactory.GENERATOR_1 }
        assertThat(g1.busId).isEqualTo(TestNetworkFactory.BUS_1)
        assertThat(g1.minActivePowerMw).isEqualTo(20.0)
        assertThat(g1.maxActivePowerMw).isEqualTo(100.0)
        assertThat(g1.powerSetpointMw).isEqualTo(80.0)
        assertThat(g1.connected).isTrue()
    }

    @Test
    fun `generator fuel type and cost come from metadata provider`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        val g1 = snapshot.generators.first { it.id == TestNetworkFactory.GENERATOR_1 }
        assertThat(g1.fuelType).isEqualTo(FuelType.GAS)
        assertThat(g1.marginalCostPerMwh).isEqualTo(48.0)

        val g2 = snapshot.generators.first { it.id == TestNetworkFactory.GENERATOR_2 }
        assertThat(g2.fuelType).isEqualTo(FuelType.COAL)
        assertThat(g2.marginalCostPerMwh).isEqualTo(35.0)
    }

    @Test
    fun `generator target voltage is converted to per unit`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        // targetV=220kV, nominalV=220kV → 1.0 pu
        val g1 = snapshot.generators.first { it.id == TestNetworkFactory.GENERATOR_1 }
        assertThat(g1.targetVoltagePu).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-6))
    }

    @Test
    fun `generator powerOutputMw is null before power flow`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        snapshot.generators.forEach { gen -> assertThat(gen.powerOutputMw).isNull() }
    }

    @Test
    fun `generator dispatchable is true for non-renewable fuel types`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        // Metadata provider in setUp() assigns GAS/COAL — both dispatchable.
        snapshot.generators.forEach { gen -> assertThat(gen.dispatchable).isTrue() }
    }

    @Test
    fun `generator dispatchable is false for WIND and SOLAR`() {
        val metadata =
            mapOf(
                TestNetworkFactory.GENERATOR_1 to GeneratorMetadata(FuelType.WIND, 0.0),
                TestNetworkFactory.GENERATOR_2 to GeneratorMetadata(FuelType.SOLAR, 0.0),
            )
        val renewableMapper = IidmNetworkMapperImpl(MapGeneratorMetadataProvider(metadata))
        val network = TestNetworkFactory.create()
        val snapshot = renewableMapper.toGridNetwork(network)

        snapshot.generators.forEach { gen -> assertThat(gen.dispatchable).isFalse() }
    }

    @Test
    fun `generator with unknown metadata gets OTHER fuel type and zero cost`() {
        val mapperNoMeta = IidmNetworkMapperImpl(DefaultGeneratorMetadataProvider())
        val network = TestNetworkFactory.create()
        val snapshot = mapperNoMeta.toGridNetwork(network)

        snapshot.generators.forEach { gen ->
            assertThat(gen.fuelType).isEqualTo(FuelType.OTHER)
            assertThat(gen.marginalCostPerMwh).isEqualTo(0.0)
        }
    }

    // -------------------------------------------------------------------------
    // Load mapping
    // -------------------------------------------------------------------------

    @Test
    fun `toGridNetwork maps all loads`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        assertThat(snapshot.loads).hasSize(2)
    }

    @Test
    fun `load fields are correctly mapped`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        val load1 = snapshot.loads.first { it.id == TestNetworkFactory.LOAD_1 }
        assertThat(load1.busId).isEqualTo(TestNetworkFactory.BUS_3)
        assertThat(load1.activePowerMw).isEqualTo(100.0)
        assertThat(load1.reactivePowerMvar).isEqualTo(30.0)
        assertThat(load1.connected).isTrue()
    }

    // -------------------------------------------------------------------------
    // Shunt compensator mapping
    // -------------------------------------------------------------------------

    @Test
    fun `toGridNetwork maps shunt compensator`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        assertThat(snapshot.shuntCompensators).hasSize(1)
        val sc = snapshot.shuntCompensators.first()
        assertThat(sc.id).isEqualTo(TestNetworkFactory.SHUNT_1)
        assertThat(sc.busId).isEqualTo(TestNetworkFactory.BUS_4)
        assertThat(sc.susceptanceSiemensPerSection).isEqualTo(0.05)
        assertThat(sc.maximumSectionCount).isEqualTo(3)
        assertThat(sc.currentSectionCount).isEqualTo(1)
        assertThat(sc.connected).isTrue()
    }

    // -------------------------------------------------------------------------
    // Snapshot metadata
    // -------------------------------------------------------------------------

    @Test
    fun `snapshot has network id and no warnings for valid network`() {
        val network = TestNetworkFactory.create()
        val snapshot = mapper.toGridNetwork(network)

        assertThat(snapshot.id).isEqualTo("test-network")
        assertThat(snapshot.warnings).isEmpty()
    }

    @Test
    fun `regions are embedded in snapshot`() {
        val network = TestNetworkFactory.create()
        val regions = listOf(Region("R1", "North", setOf(TestNetworkFactory.BUS_1)))
        val snapshot = mapper.toGridNetwork(network, regions)

        assertThat(snapshot.regions).hasSize(1)
        assertThat(snapshot.regions.first().id).isEqualTo("R1")
    }
}
