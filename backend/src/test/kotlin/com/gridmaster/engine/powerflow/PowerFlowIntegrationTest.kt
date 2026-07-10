package com.gridmaster.engine.powerflow

import com.gridmaster.engine.network.IidmNetworkMapperImpl
import com.gridmaster.engine.network.TestNetworkFactory
import com.gridmaster.game.PresetNetworkFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Integration tests for [PowSyBlPowerFlowService] using the real PowSyBl AC solver.
 * Tagged [Tag("integration")] — excluded from the default test run.
 *
 * These tests require the `powsybl-math-native` shared library (native sparse LU solver).
 * The library ships with x86_64 binaries; ARM64 Linux is not supported in v1.4.0.
 * On unsupported platforms the tests are skipped via [assumeNativeMathAvailable].
 */
@Tag("integration")
class PowerFlowIntegrationTest {
    private val mapper = IidmNetworkMapperImpl()
    private val scanner = ViolationScanner()
    private val service = PowSyBlPowerFlowService(mapper, scanner)

    @BeforeEach
    fun assumeNativeMathAvailable() {
        assumeTrue(
            isPowSyBlNativeAvailable(),
            "Skipping: powsybl-math-native not available on " +
                "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
        )
    }

    // -------------------------------------------------------------------------
    // AC solve — normal operation
    // -------------------------------------------------------------------------

    @Test
    fun `AC solve on test network converges`() {
        val result = service.solve(TestNetworkFactory.create())

        assertThat(result.status).isEqualTo(ConvergenceStatus.CONVERGED)
        assertThat(result.solveMode).isEqualTo(SolveMode.AC)
        assertThat(result.iterationCount).isGreaterThan(0)
    }

    @Test
    fun `AC solve populates bus voltages`() {
        val result = service.solve(TestNetworkFactory.create())

        assertThat(result.status).isEqualTo(ConvergenceStatus.CONVERGED)
        result.snapshot.buses.forEach { bus ->
            assertThat(bus.voltageMagnitudePu)
                .describedAs("Bus ${bus.id} should have voltage after solve")
                .isNotNull()
            assertThat(bus.voltageMagnitudePu!!)
                .isBetween(0.5, 1.5)
        }
    }

    @Test
    fun `AC solve populates line currents`() {
        val result = service.solve(TestNetworkFactory.create())

        assertThat(result.status).isEqualTo(ConvergenceStatus.CONVERGED)
        assertThat(result.snapshot.lines.count { it.currentFromA != null }).isGreaterThan(0)
    }

    @Test
    fun `solve time is recorded`() {
        val result = service.solve(TestNetworkFactory.create())

        assertThat(result.solveTimeMs).isGreaterThanOrEqualTo(0L)
    }

    @Test
    fun `slack bus is identified`() {
        val result = service.solve(TestNetworkFactory.create())

        assertThat(result.status).isEqualTo(ConvergenceStatus.CONVERGED)
        assertThat(result.slackBusIds).isNotEmpty()
    }

    @Test
    fun `AC solve converges on ieee14 after L1-2-1 outage (regression #397)`() {
        val network = PresetNetworkFactory.create("ieee14")
        mapper.configureActivePowerControl(network)

        val line = requireNotNull(network.getLine("L1-2-1")) { "Line L1-2-1 not found in the network" }
        line.terminal1.disconnect()
        line.terminal2.disconnect()

        val result = service.solve(network)
        assertThat(result.status).isEqualTo(ConvergenceStatus.CONVERGED)
    }

    // -------------------------------------------------------------------------
    // DC solve
    // -------------------------------------------------------------------------

    @Test
    fun `DC solve converges and returns DC mode`() {
        val result = service.solve(TestNetworkFactory.create(), PowerFlowParameters(mode = SolveMode.DC))

        assertThat(result.status).isEqualTo(ConvergenceStatus.CONVERGED)
        assertThat(result.solveMode).isEqualTo(SolveMode.DC)
    }

    // -------------------------------------------------------------------------
    // Violations
    // -------------------------------------------------------------------------

    @Test
    fun `thermal violation detected when line is overloaded`() {
        val network = TestNetworkFactory.create()
        network.getGenerator(TestNetworkFactory.GENERATOR_1).targetP = 100.0
        network.getGenerator(TestNetworkFactory.GENERATOR_2).targetP = 200.0
        network.getLine(TestNetworkFactory.LINE_12)
            .newCurrentLimits1().setPermanentLimit(1.0).add()

        val result = service.solve(network)

        assertThat(result.status).isEqualTo(ConvergenceStatus.CONVERGED)
        val l12 =
            result.violations.filterIsInstance<NetworkViolation.ThermalViolation>()
                .firstOrNull { it.equipmentId == TestNetworkFactory.LINE_12 }
        assertThat(l12).isNotNull()
        assertThat(l12!!.loadingPercent).isGreaterThan(100.0)
    }

    @Test
    fun `no critical violations on balanced well-configured test network`() {
        val result = service.solve(TestNetworkFactory.create())

        assertThat(result.status).isEqualTo(ConvergenceStatus.CONVERGED)
        assertThat(
            result.violations.filter {
                it is NetworkViolation.ThermalViolation && it.severity == ViolationSeverity.CRITICAL
            },
        ).isEmpty()
    }

    // -------------------------------------------------------------------------
    // Native lib check
    // -------------------------------------------------------------------------

    companion object {
        /**
         * Returns true when the powsybl-math-native library loaded successfully.
         * Uses a lazy flag so we only attempt the check once per JVM.
         */
        private val NATIVE_AVAILABLE: Boolean by lazy {
            try {
                // Calling create() triggers the static init of AbstractMathNative
                // which loads the native library — will throw if unavailable.
                com.powsybl.math.matrix.SparseMatrixFactory().create(2, 2, 2)
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun isPowSyBlNativeAvailable(): Boolean = NATIVE_AVAILABLE
    }
}
