package com.gridmaster.game

import com.gridmaster.engine.model.ExpansionSiteKind
import com.gridmaster.engine.model.NetworkMutation
import com.gridmaster.engine.network.IidmNetworkMapperImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for `freeplay50`'s dormant [com.gridmaster.engine.model.ExpansionSite]
 * topology and metadata (#414).
 */
class FreePlay50ExpansionSitesTest {
    // -------------------------------------------------------------------------
    // expansionSitesFor metadata
    // -------------------------------------------------------------------------

    @Test
    fun `expansionSitesFor is empty for presets that don't support expansion`() {
        assertThat(PresetNetworkFactory.expansionSitesFor("tutorial")).isEmpty()
        assertThat(PresetNetworkFactory.expansionSitesFor("ieee14")).isEmpty()
    }

    @Test
    fun `expansionSitesFor freeplay50 includes one site of every ExpansionSiteKind`() {
        val sites = PresetNetworkFactory.expansionSitesFor("freeplay50")

        assertThat(sites.map { it.kind }.toSet()).isEqualTo(ExpansionSiteKind.entries.toSet())
    }

    @Test
    fun `expansionSitesFor freeplay50 site ids are unique`() {
        val sites = PresetNetworkFactory.expansionSitesFor("freeplay50")

        assertThat(sites.map { it.id }).doesNotHaveDuplicates()
    }

    @Test
    fun `GENERATOR and SUBSTATION sites reference a connectingLineSiteId that resolves to a real NEW_LINE site`() {
        val sites = PresetNetworkFactory.expansionSitesFor("freeplay50")
        val byId = sites.associateBy { it.id }

        val bundled = sites.filter { it.connectingLineSiteId != null }
        assertThat(bundled).isNotEmpty()
        bundled.forEach { site ->
            val lineSite = byId[site.connectingLineSiteId]
            assertThat(lineSite)
                .withFailMessage { "connectingLineSiteId ${site.connectingLineSiteId} on ${site.id} does not resolve" }
                .isNotNull()
            assertThat(lineSite!!.kind).isEqualTo(ExpansionSiteKind.NEW_LINE)
        }
    }

    // -------------------------------------------------------------------------
    // Dormant IIDM topology: disconnected at construction
    // -------------------------------------------------------------------------

    @Test
    fun `every freeplay50 expansion site's IIDM elements are disconnected at construction`() {
        val network = PresetNetworkFactory.create("freeplay50")

        assertThat(network.getLine("LN-7-DUP").terminal1.isConnected).isFalse()
        assertThat(network.getLine("LN-7-DUP").terminal2.isConnected).isFalse()
        assertThat(network.getShuntCompensator("SC-EXP-EHUB").terminal.isConnected).isFalse()
        assertThat(network.getGenerator("G-GAS-N-EXP1").terminal.isConnected).isFalse()
        assertThat(network.getLine("LN-EXP1").terminal1.isConnected).isFalse()
        assertThat(network.getLine("LN-EXP1").terminal2.isConnected).isFalse()
        assertThat(network.getLine("LS-EXP1").terminal1.isConnected).isFalse()
        assertThat(network.getLine("LS-EXP1").terminal2.isConnected).isFalse()
    }

    @Test
    fun `dormant expansion elements are excluded from the mapped snapshot's connected state`() {
        val network = PresetNetworkFactory.create("freeplay50")
        val snapshot = IidmNetworkMapperImpl().toGridNetwork(network)

        assertThat(snapshot.generators.first { it.id == "G-GAS-N-EXP1" }.connected).isFalse()
        assertThat(snapshot.lines.first { it.id == "LN-EXP1" }.connected).isFalse()
        assertThat(snapshot.lines.first { it.id == "LN-7-DUP" }.connected).isFalse()
        assertThat(snapshot.shuntCompensators.first { it.id == "SC-EXP-EHUB" }.connected).isFalse()
    }

    // -------------------------------------------------------------------------
    // ConnectGenerator/ConnectLine produce a network identical in shape to one
    // built pre-connected -- no special-casing leaks into the mapper (#414).
    // -------------------------------------------------------------------------

    @Test
    fun `ConnectGenerator on a dormant GENERATOR site produces the same shape as an always-connected generator`() {
        val network = PresetNetworkFactory.create("freeplay50")
        val mapper = IidmNetworkMapperImpl()

        val result = mapper.applyMutation(network, NetworkMutation.ConnectGenerator("G-GAS-N-EXP1"))
        assertThat(result.isSuccess).isTrue()

        val snapshot = mapper.toGridNetwork(network)
        val connectedSite = snapshot.generators.first { it.id == "G-GAS-N-EXP1" }
        val ordinaryGenerator = snapshot.generators.first { it.id == "G-GAS-N" }

        // Same fields populated (or null) the same way as an ordinary, always-connected
        // generator on an un-solved network -- proves connecting a dormant site doesn't
        // leave it in some visibly different in-between state.
        assertThat(connectedSite.connected).isTrue()
        assertThat(connectedSite.powerOutputMw).isEqualTo(ordinaryGenerator.powerOutputMw) // both null pre-solve
        assertThat(connectedSite.busId).isEqualTo("N-BEXP1H")
        assertThat(connectedSite.minActivePowerMw).isEqualTo(20.0)
        assertThat(connectedSite.maxActivePowerMw).isEqualTo(120.0)
        assertThat(connectedSite.powerSetpointMw).isEqualTo(100.0)
    }

    @Test
    fun `ConnectLine on a dormant NEW_LINE site produces the same shape as an always-connected line`() {
        val network = PresetNetworkFactory.create("freeplay50")
        val mapper = IidmNetworkMapperImpl()

        val result = mapper.applyMutation(network, NetworkMutation.ConnectLine("LN-EXP1"))
        assertThat(result.isSuccess).isTrue()

        val snapshot = mapper.toGridNetwork(network)
        val connectedSite = snapshot.lines.first { it.id == "LN-EXP1" }
        val ordinaryLine = snapshot.lines.first { it.id == "LN-1" }

        assertThat(connectedSite.connected).isTrue()
        assertThat(connectedSite.currentFromA).isEqualTo(ordinaryLine.currentFromA) // both null pre-solve
        assertThat(connectedSite.fromBusId).isEqualTo("N-BEXP1H")
        assertThat(connectedSite.toBusId).isEqualTo("N-B10")
        assertThat(connectedSite.ratingA).isEqualTo(500.0) // line() helper's default rating
    }

    @Test
    fun `ConnectLine on the DOUBLE_LINE site reconnects both terminals of the second circuit`() {
        val network = PresetNetworkFactory.create("freeplay50")
        val mapper = IidmNetworkMapperImpl()

        val result = mapper.applyMutation(network, NetworkMutation.ConnectLine("LN-7-DUP"))
        assertThat(result.isSuccess).isTrue()

        val snapshot = mapper.toGridNetwork(network)
        val duplicateCircuit = snapshot.lines.first { it.id == "LN-7-DUP" }
        val originalCircuit = snapshot.lines.first { it.id == "LN-7" }

        assertThat(duplicateCircuit.connected).isTrue()
        // Same corridor, same electrical characteristics as the original circuit.
        assertThat(duplicateCircuit.fromBusId).isEqualTo(originalCircuit.fromBusId)
        assertThat(duplicateCircuit.toBusId).isEqualTo(originalCircuit.toBusId)
        assertThat(duplicateCircuit.resistanceOhm).isEqualTo(originalCircuit.resistanceOhm)
        assertThat(duplicateCircuit.reactanceOhm).isEqualTo(originalCircuit.reactanceOhm)
    }
}
