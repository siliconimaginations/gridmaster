package com.gridmaster.engine.network

import com.gridmaster.engine.model.NetworkMutation
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
}
