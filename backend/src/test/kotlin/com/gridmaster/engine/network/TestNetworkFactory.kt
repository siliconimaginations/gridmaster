package com.gridmaster.engine.network

import com.powsybl.iidm.network.Generator
import com.powsybl.iidm.network.Network
import com.powsybl.iidm.network.NetworkFactory
import com.powsybl.iidm.network.TopologyKind

/**
 * Builds a small 4-bus ring network for unit tests.
 *
 * Topology:
 *
 *   G1 (100 MW gas)         G2 (200 MW coal)
 *      |                       |
 *    [B1] ---L12--- [B2] ---L23--- [B3]
 *      |                                |
 *     L14                             L34
 *      |                                |
 *    [B4] --------L24---------[B2]     (L34 connects B3→B4)
 *      |
 *    Load1 (80 MW)
 *
 * Simplified wiring (actual):
 *   B1 — B2 via L12 (line)
 *   B2 — B3 via L23 (line)
 *   B3 — B4 via L34 (line)
 *   B1 — B4 via L14 (line)
 *   B1 — B2 via TX12 (transformer, 220kV/110kV)
 *
 * Generators: G1 on B1, G2 on B2
 * Loads: Load1 on B3, Load2 on B4
 * ShuntCompensator: SC1 on B4 (capacitor bank)
 */
object TestNetworkFactory {

    const val BUS_1 = "B1"
    const val BUS_2 = "B2"
    const val BUS_3 = "B3"
    const val BUS_4 = "B4"
    const val LINE_12 = "L12"
    const val LINE_23 = "L23"
    const val LINE_34 = "L34"
    const val LINE_14 = "L14"
    const val TRANSFORMER_12 = "TX12"
    const val GENERATOR_1 = "G1"
    const val GENERATOR_2 = "G2"
    const val LOAD_1 = "Load1"
    const val LOAD_2 = "Load2"
    const val SHUNT_1 = "SC1"

    fun create(): Network {
        val network = NetworkFactory.findDefault().createNetwork("test-network", "test")

        // Substations
        val s1 = network.newSubstation().setId("S1").setName("Substation 1").add()
        val s2 = network.newSubstation().setId("S2").setName("Substation 2").add()
        val s3 = network.newSubstation().setId("S3").setName("Substation 3").add()
        val s4 = network.newSubstation().setId("S4").setName("Substation 4").add()

        // Voltage levels (two voltage tiers)
        val vl1 = s1.newVoltageLevel()
            .setId("VL1").setNominalV(220.0).setTopologyKind(TopologyKind.BUS_BREAKER).add()
        val vl2 = s2.newVoltageLevel()
            .setId("VL2").setNominalV(220.0).setTopologyKind(TopologyKind.BUS_BREAKER).add()
        val vl2low = s2.newVoltageLevel()
            .setId("VL2L").setNominalV(110.0).setTopologyKind(TopologyKind.BUS_BREAKER).add()
        val vl3 = s3.newVoltageLevel()
            .setId("VL3").setNominalV(220.0).setTopologyKind(TopologyKind.BUS_BREAKER).add()
        val vl4 = s4.newVoltageLevel()
            .setId("VL4").setNominalV(220.0).setTopologyKind(TopologyKind.BUS_BREAKER).add()

        // Buses
        vl1.busBreakerView.newBus().setId(BUS_1).setName("Bus 1").add()
        vl2.busBreakerView.newBus().setId(BUS_2).setName("Bus 2").add()
        vl2low.busBreakerView.newBus().setId("B2L").setName("Bus 2 LV").add()
        vl3.busBreakerView.newBus().setId(BUS_3).setName("Bus 3").add()
        vl4.busBreakerView.newBus().setId(BUS_4).setName("Bus 4").add()

        // Lines
        network.newLine().setId(LINE_12).setName("Line B1-B2")
            .setVoltageLevel1("VL1").setBus1(BUS_1).setConnectableBus1(BUS_1)
            .setVoltageLevel2("VL2").setBus2(BUS_2).setConnectableBus2(BUS_2)
            .setR(0.5).setX(5.0).setB1(0.0).setB2(0.0).setG1(0.0).setG2(0.0)
            .add().also { line ->
                line.newCurrentLimits1().setPermanentLimit(500.0).add()
            }
        network.newLine().setId(LINE_23).setName("Line B2-B3")
            .setVoltageLevel1("VL2").setBus1(BUS_2).setConnectableBus1(BUS_2)
            .setVoltageLevel2("VL3").setBus2(BUS_3).setConnectableBus2(BUS_3)
            .setR(0.3).setX(3.0).setB1(1e-4).setB2(1e-4).setG1(0.0).setG2(0.0)
            .add()
        network.newLine().setId(LINE_34).setName("Line B3-B4")
            .setVoltageLevel1("VL3").setBus1(BUS_3).setConnectableBus1(BUS_3)
            .setVoltageLevel2("VL4").setBus2(BUS_4).setConnectableBus2(BUS_4)
            .setR(0.4).setX(4.0).setB1(0.0).setB2(0.0).setG1(0.0).setG2(0.0)
            .add()
        network.newLine().setId(LINE_14).setName("Line B1-B4")
            .setVoltageLevel1("VL1").setBus1(BUS_1).setConnectableBus1(BUS_1)
            .setVoltageLevel2("VL4").setBus2(BUS_4).setConnectableBus2(BUS_4)
            .setR(0.6).setX(6.0).setB1(0.0).setB2(0.0).setG1(0.0).setG2(0.0)
            .add()

        // Two-winding transformer B1(220kV) → B2L(110kV)
        s2.newTwoWindingsTransformer().setId(TRANSFORMER_12).setName("TX B1-B2L")
            .setVoltageLevel1("VL1").setBus1(BUS_1).setConnectableBus1(BUS_1)
            .setVoltageLevel2("VL2L").setBus2("B2L").setConnectableBus2("B2L")
            .setRatedU1(220.0).setRatedU2(110.0).setRatedS(200.0)
            .setR(0.1).setX(10.0).setB(0.0).setG(0.0)
            .add()

        // Generators
        vl1.newGenerator().setId(GENERATOR_1).setName("Gas Gen 1")
            .setBus(BUS_1).setConnectableBus(BUS_1)
            .setMinP(20.0).setMaxP(100.0).setTargetP(80.0)
            .setTargetQ(10.0).setTargetV(220.0)
            .setVoltageRegulatorOn(true)
            .add()
        vl2.newGenerator().setId(GENERATOR_2).setName("Coal Gen 2")
            .setBus(BUS_2).setConnectableBus(BUS_2)
            .setMinP(50.0).setMaxP(200.0).setTargetP(150.0)
            .setTargetQ(20.0).setTargetV(220.0)
            .setVoltageRegulatorOn(true)
            .add()

        // Loads
        vl3.newLoad().setId(LOAD_1).setName("Load 1")
            .setBus(BUS_3).setConnectableBus(BUS_3)
            .setP0(100.0).setQ0(30.0)
            .add()
        vl4.newLoad().setId(LOAD_2).setName("Load 2")
            .setBus(BUS_4).setConnectableBus(BUS_4)
            .setP0(80.0).setQ0(20.0)
            .add()

        // Shunt compensator on B4 (capacitor: B > 0)
        vl4.newShuntCompensator().setId(SHUNT_1).setName("Shunt Capacitor 1")
            .setBus(BUS_4).setConnectableBus(BUS_4)
            .setSectionCount(1)
            .newLinearModel().setBPerSection(0.05).setGPerSection(0.0).setMaximumSectionCount(3).add()
            .add()

        return network
    }
}
