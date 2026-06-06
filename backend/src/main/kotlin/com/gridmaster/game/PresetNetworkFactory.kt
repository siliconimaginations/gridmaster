package com.gridmaster.game

import com.powsybl.iidm.network.Network
import com.powsybl.iidm.network.NetworkFactory
import com.powsybl.iidm.network.TopologyKind

/**
 * Creates seed PowSyBl [Network] objects for each [GameMode] preset.
 *
 * Networks are constructed programmatically so no classpath resources are required.
 * Module 07+ will replace these stubs with proper tutorial/free-play networks as
 * the game curriculum is built out.
 *
 * Topology (shared across presets; complexity grows in later modules):
 *
 *   G1 (gas, 100 MW)                G2 (coal, 200 MW)
 *      |                                  |
 *   [B1 220kV]──L12──[B2 220kV]──L23──[B3 220kV]
 *      |                                  |
 *    TX12 (S1 HV→LV)                    L34
 *      |                                  |
 *   [B1L 110kV]            [B4 220kV]──Load2
 *      |                        |
 *     L14──────────────────────/
 */
object PresetNetworkFactory {
    /** Maps a [networkPreset] string to a network-builder function. */
    val knownPresets: Set<String> = setOf("tutorial", "ieee14", "freeplay50")

    /**
     * Create a seed [Network] for the given [networkPreset].
     *
     * @throws IllegalArgumentException if the preset name is not recognised.
     */
    fun create(networkPreset: String): Network =
        when (networkPreset) {
            "tutorial" -> buildTutorialNetwork()
            "ieee14" -> buildTutorialNetwork() // TODO: #41 replace with proper IEEE 14-bus XIIDM
            "freeplay50" -> buildTutorialNetwork() // TODO: #42 replace with 50-bus free-play seed
            else -> throw IllegalArgumentException(
                "Unknown network preset: '$networkPreset'. " +
                    "Valid presets: ${knownPresets.joinToString()}",
            )
        }

    // -------------------------------------------------------------------------
    // Private builders
    // -------------------------------------------------------------------------

    private fun buildTutorialNetwork(): Network {
        val network = NetworkFactory.findDefault().createNetwork("tutorial-network", "tutorial")

        val s1 = network.newSubstation().setId("S1").setName("City North").add()
        val s2 = network.newSubstation().setId("S2").setName("Industrial Park").add()
        val s3 = network.newSubstation().setId("S3").setName("Riverside").add()
        val s4 = network.newSubstation().setId("S4").setName("City South").add()

        val vl1 =
            s1.newVoltageLevel()
                .setId("VL1").setName("North HV").setNominalV(220.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add()
        val vl1l =
            s1.newVoltageLevel()
                .setId("VL1L").setName("North LV").setNominalV(110.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add()
        val vl2 =
            s2.newVoltageLevel()
                .setId("VL2").setName("Industrial HV").setNominalV(220.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add()
        val vl3 =
            s3.newVoltageLevel()
                .setId("VL3").setName("Riverside HV").setNominalV(220.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add()
        val vl4 =
            s4.newVoltageLevel()
                .setId("VL4").setName("South HV").setNominalV(220.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add()

        vl1.busBreakerView.newBus().setId("B1").setName("North Bus").add()
        vl1l.busBreakerView.newBus().setId("B1L").setName("North LV Bus").add()
        vl2.busBreakerView.newBus().setId("B2").setName("Industrial Bus").add()
        vl3.busBreakerView.newBus().setId("B3").setName("Riverside Bus").add()
        vl4.busBreakerView.newBus().setId("B4").setName("South Bus").add()

        network.newLine().setId("L12").setName("North–Industrial")
            .setVoltageLevel1("VL1").setBus1("B1").setConnectableBus1("B1")
            .setVoltageLevel2("VL2").setBus2("B2").setConnectableBus2("B2")
            .setR(0.5).setX(5.0).setB1(0.0).setB2(0.0).setG1(0.0).setG2(0.0)
            .add().also { it.newCurrentLimits1().setPermanentLimit(500.0).add() }

        network.newLine().setId("L23").setName("Industrial–Riverside")
            .setVoltageLevel1("VL2").setBus1("B2").setConnectableBus1("B2")
            .setVoltageLevel2("VL3").setBus2("B3").setConnectableBus2("B3")
            .setR(0.3).setX(3.0).setB1(1e-4).setB2(1e-4).setG1(0.0).setG2(0.0)
            .add()

        network.newLine().setId("L34").setName("Riverside–South")
            .setVoltageLevel1("VL3").setBus1("B3").setConnectableBus1("B3")
            .setVoltageLevel2("VL4").setBus2("B4").setConnectableBus2("B4")
            .setR(0.4).setX(4.0).setB1(0.0).setB2(0.0).setG1(0.0).setG2(0.0)
            .add()

        network.newLine().setId("L14").setName("North–South")
            .setVoltageLevel1("VL1").setBus1("B1").setConnectableBus1("B1")
            .setVoltageLevel2("VL4").setBus2("B4").setConnectableBus2("B4")
            .setR(0.6).setX(6.0).setB1(0.0).setB2(0.0).setG1(0.0).setG2(0.0)
            .add()

        // Transformer: HV → LV within S1
        s1.newTwoWindingsTransformer().setId("TX12").setName("North Step-Down")
            .setVoltageLevel1("VL1").setBus1("B1").setConnectableBus1("B1")
            .setVoltageLevel2("VL1L").setBus2("B1L").setConnectableBus2("B1L")
            .setRatedU1(220.0).setRatedU2(110.0).setRatedS(200.0)
            .setR(0.1).setX(10.0).setB(0.0).setG(0.0)
            .add()

        vl1.newGenerator().setId("G1").setName("Gas Peaker")
            .setBus("B1").setConnectableBus("B1")
            .setMinP(20.0).setMaxP(100.0).setTargetP(80.0)
            .setTargetQ(10.0).setTargetV(220.0)
            .setVoltageRegulatorOn(true).add()

        vl2.newGenerator().setId("G2").setName("Coal Baseload")
            .setBus("B2").setConnectableBus("B2")
            .setMinP(50.0).setMaxP(200.0).setTargetP(150.0)
            .setTargetQ(20.0).setTargetV(220.0)
            .setVoltageRegulatorOn(true).add()

        vl3.newLoad().setId("Load1").setName("Riverside Load")
            .setBus("B3").setConnectableBus("B3")
            .setP0(100.0).setQ0(30.0).add()

        vl4.newLoad().setId("Load2").setName("South Load")
            .setBus("B4").setConnectableBus("B4")
            .setP0(80.0).setQ0(20.0).add()

        return network
    }
}
