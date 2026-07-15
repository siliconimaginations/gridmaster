package com.gridmaster.engine.contingency

import com.gridmaster.engine.network.IidmNetworkMapperImpl
import com.gridmaster.engine.network.TestNetworkFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [ContingencyBuilder] — no PowSyBl solver required. */
class ContingencyBuilderTest {
    private val mapper = IidmNetworkMapperImpl()

    @Test
    fun `buildN1 produces one contingency per line`() {
        val snapshot = mapper.toGridNetwork(TestNetworkFactory.create())
        val contingencies = ContingencyBuilder.buildN1(snapshot)

        val lineContingencies =
            contingencies.filter {
                it.elements.any { e -> e is ContingencyElement.LineOutage }
            }
        assertThat(lineContingencies).hasSize(snapshot.lines.size)
    }

    @Test
    fun `buildN1 produces one contingency per two-winding transformer`() {
        val snapshot = mapper.toGridNetwork(TestNetworkFactory.create())
        val contingencies = ContingencyBuilder.buildN1(snapshot)

        val twtContingencies =
            contingencies.filter {
                it.elements.any { e -> e is ContingencyElement.TwoWindingsTransformerOutage }
            }
        assertThat(twtContingencies).hasSize(snapshot.twoWindingsTransformers.size)
    }

    @Test
    fun `buildN1 produces one contingency per connected generator`() {
        val snapshot = mapper.toGridNetwork(TestNetworkFactory.create())
        val connectedGenerators = snapshot.generators.count { it.connected }
        val contingencies = ContingencyBuilder.buildN1(snapshot)

        val genContingencies =
            contingencies.filter {
                it.elements.any { e -> e is ContingencyElement.GeneratorOutage }
            }
        assertThat(genContingencies).hasSize(connectedGenerators)
    }

    @Test
    fun `buildN1 total count equals lines plus transformers plus generators`() {
        val snapshot = mapper.toGridNetwork(TestNetworkFactory.create())
        val contingencies = ContingencyBuilder.buildN1(snapshot)

        val expected =
            snapshot.lines.size +
                snapshot.twoWindingsTransformers.size +
                snapshot.threeWindingsTransformers.size +
                snapshot.generators.count { it.connected }
        assertThat(contingencies).hasSize(expected)
    }

    @Test
    fun `each contingency has a unique id`() {
        val snapshot = mapper.toGridNetwork(TestNetworkFactory.create())
        val contingencies = ContingencyBuilder.buildN1(snapshot)

        assertThat(contingencies.map { it.id }.toSet()).hasSize(contingencies.size)
    }

    @Test
    fun `each contingency has exactly one element for N-1`() {
        val snapshot = mapper.toGridNetwork(TestNetworkFactory.create())
        val contingencies = ContingencyBuilder.buildN1(snapshot)

        contingencies.forEach { contingency ->
            assertThat(contingency.elements)
                .describedAs("Contingency ${contingency.id} should have exactly 1 element")
                .hasSize(1)
        }
    }

    @Test
    fun `buildN1 excludes a disconnected line (issue #407)`() {
        val network = TestNetworkFactory.create()
        val line = network.getLine(TestNetworkFactory.LINE_12)
        line.terminal1.disconnect()
        line.terminal2.disconnect()
        val snapshot = mapper.toGridNetwork(network)

        val contingencies = ContingencyBuilder.buildN1(snapshot)

        assertThat(contingencies.map { it.id }).doesNotContain("N1-LINE-${TestNetworkFactory.LINE_12}")
    }

    @Test
    fun `buildN1 excludes a disconnected two-winding transformer (issue #407)`() {
        val network = TestNetworkFactory.create()
        val twt = network.getTwoWindingsTransformer(TestNetworkFactory.TRANSFORMER_12)
        twt.terminal1.disconnect()
        twt.terminal2.disconnect()
        val snapshot = mapper.toGridNetwork(network)

        val contingencies = ContingencyBuilder.buildN1(snapshot)

        assertThat(contingencies.map { it.id }).doesNotContain("N1-TWT-${TestNetworkFactory.TRANSFORMER_12}")
    }

    @Test
    fun `toPowSyBl converts line contingency`() {
        val contingency =
            Contingency(
                id = "N1-LINE-L1",
                description = "Loss of L1",
                elements = listOf(ContingencyElement.LineOutage("L1")),
            )
        val powSyBl = ContingencyBuilder.toPowSyBl(contingency)

        assertThat(powSyBl.id).isEqualTo("N1-LINE-L1")
        assertThat(powSyBl.elements).hasSize(1)
    }

    @Test
    fun `toPowSyBl converts generator contingency`() {
        val contingency =
            Contingency(
                id = "N1-GEN-G1",
                description = "Loss of G1",
                elements = listOf(ContingencyElement.GeneratorOutage("G1")),
            )
        val powSyBl = ContingencyBuilder.toPowSyBl(contingency)

        assertThat(powSyBl.id).isEqualTo("N1-GEN-G1")
        assertThat(powSyBl.elements).hasSize(1)
    }
}
