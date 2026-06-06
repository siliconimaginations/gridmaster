package com.gridmaster.game

import com.gridmaster.engine.network.IidmNetworkMapperImpl
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.PowSyBlPowerFlowService
import com.gridmaster.engine.powerflow.ViolationScanner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [PresetNetworkFactory].
 *
 * Topology assertions run on every build. The AC power flow test is tagged
 * "integration" and requires the powsybl-math-native shared library — it is
 * excluded from the default test run and included via `-Pintegration`.
 */
class PresetNetworkFactoryTest {
    // -----------------------------------------------------------------------
    // Preset enumeration
    // -----------------------------------------------------------------------

    @Test
    fun `create returns a non-null network for every known preset`() {
        for (preset in PresetNetworkFactory.knownPresets) {
            val network = PresetNetworkFactory.create(preset)
            assertThat(network).isNotNull()
            assertThat(network.substationCount).isGreaterThan(0)
        }
    }

    @Test
    fun `create throws IllegalArgumentException for unknown preset`() {
        assertThatThrownBy { PresetNetworkFactory.create("not_a_preset") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not_a_preset")
    }

    @Test
    fun `tutorial network has expected topology`() {
        val network = PresetNetworkFactory.create("tutorial")

        assertThat(network.generatorCount).isEqualTo(2)
        assertThat(network.loadCount).isEqualTo(2)
        assertThat(network.lineCount).isEqualTo(4)
        assertThat(network.twoWindingsTransformerCount).isEqualTo(1)
    }

    // -----------------------------------------------------------------------
    // Power flow integration — real PowSyBl AC solver
    // -----------------------------------------------------------------------

    @Test
    @Tag("integration")
    fun `tutorial preset network converges under AC power flow`() {
        assumeTrue(
            nativeAvailable,
            "Skipping: powsybl-math-native not available on " +
                "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
        )

        val network = PresetNetworkFactory.create("tutorial")
        val service = PowSyBlPowerFlowService(IidmNetworkMapperImpl(), ViolationScanner())

        val result = service.solve(network)

        assertThat(result.status).isEqualTo(ConvergenceStatus.CONVERGED)
        assertThat(result.violations).isEmpty()
    }

    companion object {
        /** True when the powsybl-math-native shared library loaded successfully. */
        val nativeAvailable: Boolean by lazy {
            try {
                com.powsybl.math.matrix.SparseMatrixFactory().create(2, 2, 2)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
