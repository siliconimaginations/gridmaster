package com.gridmaster.engine.powerflow

import com.gridmaster.engine.network.IidmNetworkMapperImpl
import com.powsybl.iidm.network.Network
import com.powsybl.iidm.network.NetworkFactory
import com.powsybl.iidm.network.TopologyKind
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.system.measureNanoTime

/**
 * Benchmark for issue #423: does `PowSyBlPowerFlowService.solve()` fit within
 * the tick budget (`TickEngineImpl.slotMillis`, 1000ms/speedMultiplier) at
 * network sizes beyond today's largest shipped preset (`freeplay50`, ~50
 * buses)? Module 17's expansion mechanic will grow `freeplay50` toward
 * `WORK_PLAN.md` Stage 6's ~500-bus target over a session, so this
 * establishes a baseline before that growth is live.
 *
 * Not a correctness test (no assertions on convergence) -- a profiling tool.
 * Networks are synthetic radial feeders (a strong slack "hub" generator
 * feeding N independent 20 kV radial feeders of chained buses+loads),
 * built purely to exercise the AC solver at scale -- not a candidate seed
 * preset. Results are printed as CSV to stdout; see issue #423 for a
 * recorded run and conclusion.
 */
@Tag("integration")
class PowerFlowScaleBenchmarkTest {
    @Test
    fun `solve time at increasing network scale`() {
        assumeTrue(
            PowerFlowIntegrationTest.isPowSyBlNativeAvailable(),
            "Skipping: powsybl-math-native not available on " +
                "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
        )

        val service = PowSyBlPowerFlowService(IidmNetworkMapperImpl(), ViolationScanner())

        println("busCount,feeders,busesPerFeeder,solveTimeMs,status,violationCount")
        for ((feeders, busesPerFeeder) in listOf(10 to 10, 30 to 10, 50 to 10, 100 to 10)) {
            val network = buildSyntheticRadialNetwork(feeders, busesPerFeeder)
            val busCount = 1 + feeders * busesPerFeeder

            // Warm-up-free single measurement -- the tick engine calls solve() cold
            // every tick too (no persistent solver state carried between calls), so
            // a single measured call is representative of production behaviour.
            var result: PowerFlowResult? = null
            val elapsedNanos = measureNanoTime { result = service.solve(network) }
            val elapsedMs = elapsedNanos / 1_000_000.0

            println(
                "$busCount,$feeders,$busesPerFeeder,${"%.1f".format(elapsedMs)}," +
                    "${result!!.status},${result!!.violations.size}",
            )
        }
    }

    /**
     * Builds a synthetic radial distribution network: one 220 kV "hub"
     * substation with a large voltage-regulating generator, feeding
     * [feeders] independent 20 kV radial chains of [busesPerFeeder] buses
     * each (short lines, small loads at every bus). Radial topology
     * converges far more readily at scale than a meshed one, which is what
     * we want for a solve-time benchmark rather than a convergence stress
     * test.
     */
    private fun buildSyntheticRadialNetwork(
        feeders: Int,
        busesPerFeeder: Int,
    ): Network {
        val network = NetworkFactory.findDefault().createNetwork("perf-bench-$feeders-$busesPerFeeder", "bench")
        val loadPerBusMw = 2.0
        val totalLoadMw = feeders * busesPerFeeder * loadPerBusMw

        val hubSubstation = network.newSubstation().setId("HUB").add()
        val hubVl =
            hubSubstation.newVoltageLevel()
                .setId("HUB-VL").setNominalV(220.0).setTopologyKind(TopologyKind.BUS_BREAKER).add()
        hubVl.busBreakerView.newBus().setId("HUB-B").add()
        hubVl.newGenerator()
            .setId("HUB-GEN").setBus("HUB-B").setConnectableBus("HUB-B")
            .setMinP(0.0).setMaxP(totalLoadMw * 1.5).setTargetP(totalLoadMw * 1.1)
            .setTargetQ(0.0).setTargetV(220.0).setVoltageRegulatorOn(true)
            .add()

        for (f in 1..feeders) {
            val feederSub = network.newSubstation().setId("F$f").add()
            val hvVl =
                feederSub.newVoltageLevel()
                    .setId("F$f-HV").setNominalV(220.0).setTopologyKind(TopologyKind.BUS_BREAKER).add()
            hvVl.busBreakerView.newBus().setId("F$f-HVB").add()
            val lvVl =
                feederSub.newVoltageLevel()
                    .setId("F$f-LV").setNominalV(20.0).setTopologyKind(TopologyKind.BUS_BREAKER).add()
            lvVl.busBreakerView.newBus().setId("F$f-B1").add()

            feederSub.newTwoWindingsTransformer()
                .setId("F$f-TX").setVoltageLevel1("F$f-HV").setBus1("F$f-HVB").setConnectableBus1("F$f-HVB")
                .setVoltageLevel2("F$f-LV").setBus2("F$f-B1").setConnectableBus2("F$f-B1")
                .setRatedU1(220.0).setRatedU2(20.0).setRatedS(50.0)
                .setR(0.05).setX(5.0).setB(0.0).setG(0.0)
                .add()
                .also { it.newCurrentLimits1().setPermanentLimit(500.0).add() }

            network.newLine()
                .setId("F$f-HUBTIE")
                .setVoltageLevel1("HUB-VL").setBus1("HUB-B").setConnectableBus1("HUB-B")
                .setVoltageLevel2("F$f-HV").setBus2("F$f-HVB").setConnectableBus2("F$f-HVB")
                .setR(0.5).setX(5.0).setB1(0.0).setB2(0.0).setG1(0.0).setG2(0.0)
                .add()
                .also { it.newCurrentLimits1().setPermanentLimit(1000.0).add() }

            lvVl.newLoad()
                .setId("F$f-L1").setBus("F$f-B1").setConnectableBus("F$f-B1")
                .setP0(loadPerBusMw).setQ0(loadPerBusMw * 0.3)
                .add()

            for (b in 2..busesPerFeeder) {
                lvVl.busBreakerView.newBus().setId("F$f-B$b").add()
                network.newLine()
                    .setId("F$f-L${b - 1}$b")
                    .setVoltageLevel1("F$f-LV").setBus1("F$f-B${b - 1}").setConnectableBus1("F$f-B${b - 1}")
                    .setVoltageLevel2("F$f-LV").setBus2("F$f-B$b").setConnectableBus2("F$f-B$b")
                    .setR(0.02).setX(0.2).setB1(0.0).setB2(0.0).setG1(0.0).setG2(0.0)
                    .add()
                    .also { it.newCurrentLimits1().setPermanentLimit(500.0).add() }

                lvVl.newLoad()
                    .setId("F$f-L$b").setBus("F$f-B$b").setConnectableBus("F$f-B$b")
                    .setP0(loadPerBusMw).setQ0(loadPerBusMw * 0.3)
                    .add()
            }
        }

        return network
    }
}
