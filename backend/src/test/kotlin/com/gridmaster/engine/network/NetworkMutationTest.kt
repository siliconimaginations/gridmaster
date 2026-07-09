package com.gridmaster.engine.network

import com.gridmaster.engine.model.FuelType
import com.gridmaster.engine.model.NetworkMutation
import com.powsybl.iidm.network.extensions.ActivePowerControl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [IidmNetworkMapperImpl.applyMutation].
 * Verifies each [NetworkMutation] subtype produces the correct IIDM state change.
 */
class NetworkMutationTest {
    private lateinit var mapper: IidmNetworkMapperImpl

    @BeforeEach
    fun setUp() {
        mapper = IidmNetworkMapperImpl()
    }

    // -------------------------------------------------------------------------
    // SetGeneratorOutput
    // -------------------------------------------------------------------------

    @Test
    fun `SetGeneratorOutput updates targetP`() {
        val network = TestNetworkFactory.create()
        val result = mapper.applyMutation(network, NetworkMutation.SetGeneratorOutput(TestNetworkFactory.GENERATOR_1, 60.0))

        assertThat(result.isSuccess).isTrue()
        assertThat(network.getGenerator(TestNetworkFactory.GENERATOR_1).targetP).isEqualTo(60.0)
    }

    @Test
    fun `SetGeneratorOutput fails when below minP`() {
        val network = TestNetworkFactory.create()
        // G1 minP = 20.0
        val result = mapper.applyMutation(network, NetworkMutation.SetGeneratorOutput(TestNetworkFactory.GENERATOR_1, 5.0))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidMutationException::class.java)
    }

    @Test
    fun `SetGeneratorOutput fails when above maxP`() {
        val network = TestNetworkFactory.create()
        // G1 maxP = 100.0
        val result = mapper.applyMutation(network, NetworkMutation.SetGeneratorOutput(TestNetworkFactory.GENERATOR_1, 150.0))

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `SetGeneratorOutput fails for unknown generator`() {
        val network = TestNetworkFactory.create()
        val result = mapper.applyMutation(network, NetworkMutation.SetGeneratorOutput("NONEXISTENT", 50.0))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidMutationException::class.java)
    }

    @Test
    fun `SetGeneratorOutput fails for a WIND generator`() {
        val metadata = mapOf(TestNetworkFactory.GENERATOR_1 to GeneratorMetadata(FuelType.WIND, 0.0))
        val windMapper = IidmNetworkMapperImpl(MapGeneratorMetadataProvider(metadata))
        val network = TestNetworkFactory.create()
        val result = windMapper.applyMutation(network, NetworkMutation.SetGeneratorOutput(TestNetworkFactory.GENERATOR_1, 60.0))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidMutationException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("not dispatchable")
        // Setpoint is unchanged.
        assertThat(network.getGenerator(TestNetworkFactory.GENERATOR_1).targetP).isEqualTo(80.0)
    }

    @Test
    fun `SetGeneratorOutput fails for a SOLAR generator`() {
        val metadata = mapOf(TestNetworkFactory.GENERATOR_2 to GeneratorMetadata(FuelType.SOLAR, 0.0))
        val solarMapper = IidmNetworkMapperImpl(MapGeneratorMetadataProvider(metadata))
        val network = TestNetworkFactory.create()
        val result = solarMapper.applyMutation(network, NetworkMutation.SetGeneratorOutput(TestNetworkFactory.GENERATOR_2, 60.0))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("not dispatchable")
    }

    // -------------------------------------------------------------------------
    // TripLine / ConnectLine
    // -------------------------------------------------------------------------

    @Test
    fun `TripLine disconnects both terminals`() {
        val network = TestNetworkFactory.create()
        val result = mapper.applyMutation(network, NetworkMutation.TripLine(TestNetworkFactory.LINE_12))

        assertThat(result.isSuccess).isTrue()
        val line = network.getLine(TestNetworkFactory.LINE_12)
        assertThat(line.terminal1.isConnected).isFalse()
        assertThat(line.terminal2.isConnected).isFalse()
    }

    @Test
    fun `ConnectLine reconnects both terminals`() {
        val network = TestNetworkFactory.create()
        // First trip it
        mapper.applyMutation(network, NetworkMutation.TripLine(TestNetworkFactory.LINE_12))
        // Then reconnect
        val result = mapper.applyMutation(network, NetworkMutation.ConnectLine(TestNetworkFactory.LINE_12))

        assertThat(result.isSuccess).isTrue()
        val line = network.getLine(TestNetworkFactory.LINE_12)
        assertThat(line.terminal1.isConnected).isTrue()
        assertThat(line.terminal2.isConnected).isTrue()
    }

    @Test
    fun `TripLine fails for unknown line`() {
        val network = TestNetworkFactory.create()
        val result = mapper.applyMutation(network, NetworkMutation.TripLine("L_FAKE"))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidMutationException::class.java)
    }

    // -------------------------------------------------------------------------
    // TripGenerator / ConnectGenerator
    // -------------------------------------------------------------------------

    @Test
    fun `TripGenerator disconnects terminal`() {
        val network = TestNetworkFactory.create()
        val result = mapper.applyMutation(network, NetworkMutation.TripGenerator(TestNetworkFactory.GENERATOR_1))

        assertThat(result.isSuccess).isTrue()
        assertThat(network.getGenerator(TestNetworkFactory.GENERATOR_1).terminal.isConnected).isFalse()
    }

    @Test
    fun `ConnectGenerator reconnects terminal`() {
        val network = TestNetworkFactory.create()
        mapper.applyMutation(network, NetworkMutation.TripGenerator(TestNetworkFactory.GENERATOR_1))
        val result = mapper.applyMutation(network, NetworkMutation.ConnectGenerator(TestNetworkFactory.GENERATOR_1))

        assertThat(result.isSuccess).isTrue()
        assertThat(network.getGenerator(TestNetworkFactory.GENERATOR_1).terminal.isConnected).isTrue()
    }

    // -------------------------------------------------------------------------
    // SetLoadPower
    // -------------------------------------------------------------------------

    @Test
    fun `SetLoadPower updates active power`() {
        val network = TestNetworkFactory.create()
        val result = mapper.applyMutation(network, NetworkMutation.SetLoadPower(TestNetworkFactory.LOAD_1, 120.0))

        assertThat(result.isSuccess).isTrue()
        assertThat(network.getLoad(TestNetworkFactory.LOAD_1).p0).isEqualTo(120.0)
        // Q unchanged
        assertThat(network.getLoad(TestNetworkFactory.LOAD_1).q0).isEqualTo(30.0)
    }

    @Test
    fun `SetLoadPower updates reactive power when provided`() {
        val network = TestNetworkFactory.create()
        mapper.applyMutation(network, NetworkMutation.SetLoadPower(TestNetworkFactory.LOAD_1, 120.0, 40.0))

        assertThat(network.getLoad(TestNetworkFactory.LOAD_1).p0).isEqualTo(120.0)
        assertThat(network.getLoad(TestNetworkFactory.LOAD_1).q0).isEqualTo(40.0)
    }

    @Test
    fun `SetLoadPower fails for unknown load`() {
        val network = TestNetworkFactory.create()
        val result = mapper.applyMutation(network, NetworkMutation.SetLoadPower("FAKE_LOAD", 50.0))

        assertThat(result.isFailure).isTrue()
    }

    // -------------------------------------------------------------------------
    // ConnectLoad / DisconnectLoad
    // -------------------------------------------------------------------------

    @Test
    fun `DisconnectLoad disconnects terminal`() {
        val network = TestNetworkFactory.create()
        val result = mapper.applyMutation(network, NetworkMutation.DisconnectLoad(TestNetworkFactory.LOAD_1))

        assertThat(result.isSuccess).isTrue()
        assertThat(network.getLoad(TestNetworkFactory.LOAD_1).terminal.isConnected).isFalse()
    }

    @Test
    fun `ConnectLoad reconnects terminal`() {
        val network = TestNetworkFactory.create()
        mapper.applyMutation(network, NetworkMutation.DisconnectLoad(TestNetworkFactory.LOAD_1))
        val result = mapper.applyMutation(network, NetworkMutation.ConnectLoad(TestNetworkFactory.LOAD_1))

        assertThat(result.isSuccess).isTrue()
        assertThat(network.getLoad(TestNetworkFactory.LOAD_1).terminal.isConnected).isTrue()
    }

    // -------------------------------------------------------------------------
    // SetShuntSections
    // -------------------------------------------------------------------------

    @Test
    fun `SetShuntSections updates section count`() {
        val network = TestNetworkFactory.create()
        val result = mapper.applyMutation(network, NetworkMutation.SetShuntSections(TestNetworkFactory.SHUNT_1, 2))

        assertThat(result.isSuccess).isTrue()
        assertThat(network.getShuntCompensator(TestNetworkFactory.SHUNT_1).sectionCount).isEqualTo(2)
    }

    @Test
    fun `SetShuntSections fails above maximum`() {
        val network = TestNetworkFactory.create()
        // SC1 maximumSectionCount = 3
        val result = mapper.applyMutation(network, NetworkMutation.SetShuntSections(TestNetworkFactory.SHUNT_1, 5))

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `SetShuntSections to zero is valid`() {
        val network = TestNetworkFactory.create()
        val result = mapper.applyMutation(network, NetworkMutation.SetShuntSections(TestNetworkFactory.SHUNT_1, 0))

        assertThat(result.isSuccess).isTrue()
        assertThat(network.getShuntCompensator(TestNetworkFactory.SHUNT_1).sectionCount).isEqualTo(0)
    }

    // -------------------------------------------------------------------------
    // SetGeneratorVoltage
    // -------------------------------------------------------------------------

    @Test
    fun `SetGeneratorVoltage updates targetV in kV`() {
        val network = TestNetworkFactory.create()
        // nominalV = 220kV; setting 1.05 pu → 231 kV
        val result = mapper.applyMutation(network, NetworkMutation.SetGeneratorVoltage(TestNetworkFactory.GENERATOR_1, 1.05))

        assertThat(result.isSuccess).isTrue()
        assertThat(network.getGenerator(TestNetworkFactory.GENERATOR_1).targetV)
            .isEqualTo(231.0, org.assertj.core.data.Offset.offset(0.01))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SetTapPosition
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `SetTapPosition fails for unknown transformer`() {
        val network = TestNetworkFactory.create()
        val result = mapper.applyMutation(network, NetworkMutation.SetTapPosition("NONEXISTENT_TX", 0))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidMutationException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("not found")
    }

    @Test
    fun `SetTapPosition fails when transformer has no ratio tap changer`() {
        // TestNetworkFactory TX12 is created without a ratio tap changer
        val network = TestNetworkFactory.create()
        val result = mapper.applyMutation(network, NetworkMutation.SetTapPosition(TestNetworkFactory.TRANSFORMER_12, 0))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidMutationException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("no ratio tap changer")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // configureActivePowerControl
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `configureActivePowerControl sets participate true for dispatchable generators`() {
        val network = TestNetworkFactory.create()
        mapper.configureActivePowerControl(network)

        val apc = network.getGenerator(TestNetworkFactory.GENERATOR_1).activePowerControlExtension()
        assertThat(apc).isNotNull
        assertThat(apc!!.isParticipate).isTrue()
    }

    @Test
    fun `configureActivePowerControl sets participate false for WIND and SOLAR`() {
        val metadata =
            mapOf(
                TestNetworkFactory.GENERATOR_1 to GeneratorMetadata(FuelType.WIND, 0.0),
                TestNetworkFactory.GENERATOR_2 to GeneratorMetadata(FuelType.SOLAR, 0.0),
            )
        val renewableMapper = IidmNetworkMapperImpl(MapGeneratorMetadataProvider(metadata))
        val network = TestNetworkFactory.create()
        renewableMapper.configureActivePowerControl(network)

        listOf(TestNetworkFactory.GENERATOR_1, TestNetworkFactory.GENERATOR_2).forEach { id ->
            val apc = network.getGenerator(id).activePowerControlExtension()
            assertThat(apc).isNotNull
            assertThat(apc!!.isParticipate).isFalse()
        }
    }

    @Test
    fun `configureActivePowerControl is idempotent`() {
        val network = TestNetworkFactory.create()
        mapper.configureActivePowerControl(network)
        mapper.configureActivePowerControl(network)

        val gen = network.getGenerator(TestNetworkFactory.GENERATOR_1)
        assertThat(gen.activePowerControlExtension()).isNotNull
    }
}

/**
 * Retrieves the ActivePowerControl extension with a concrete type argument.
 * Kotlin cannot infer the generic Injectable type through a bare `Class<...>` literal
 * the way javac does, so the class token is cast explicitly (matches the production
 * code in [IidmNetworkMapperImpl.configureActivePowerControl]).
 */
@Suppress("UNCHECKED_CAST")
private fun com.powsybl.iidm.network.Generator.activePowerControlExtension(): ActivePowerControl<com.powsybl.iidm.network.Generator>? =
    getExtension(ActivePowerControl::class.java as Class<ActivePowerControl<com.powsybl.iidm.network.Generator>>)
