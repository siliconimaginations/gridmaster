package com.gridmaster.engine.network

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/** Unit tests for [LineRatingEstimator] — the SIL/loadability and ratedS-based rating formulas (#395). */
class LineRatingEstimatorTest {
    // -------------------------------------------------------------------------
    // Line ratings (SIL-based)
    // -------------------------------------------------------------------------

    @Test
    fun `estimateLineRatingAmps computes SIL loadability from X and B`() {
        // X = 5.0 ohm, B = 2e-4 siemens (b1+b2), V = 220 kV
        // Z0 = sqrt(5.0 / 2e-4) = sqrt(25000) ~= 158.11 ohm
        // SIL = 220^2 / 158.11 ~= 306.11 MW
        // loadability = 3 * SIL ~= 918.33 MW
        // I = 918.33e6 / (sqrt3 * 220000) ~= 2409.3 A
        val x = 5.0
        val b = 2e-4
        val v = 220.0
        val z0 = sqrt(x / b)
        val silMw = (v * v) / z0
        val expectedAmps = (3.0 * silMw) * 1.0e6 / (sqrt(3.0) * v * 1_000.0)

        val ratingA = LineRatingEstimator.estimateLineRatingAmps(x = x, b1 = 1e-4, b2 = 1e-4, nominalVoltageKv = v)

        assertThat(ratingA).isNotNull()
        assertThat(ratingA!!).isCloseTo(expectedAmps, within(0.01))
    }

    @Test
    fun `estimateLineRatingAmps uses whichever leg susceptance is nonzero`() {
        val onlyB1 = LineRatingEstimator.estimateLineRatingAmps(x = 5.0, b1 = 2e-4, b2 = 0.0, nominalVoltageKv = 220.0)
        val onlyB2 = LineRatingEstimator.estimateLineRatingAmps(x = 5.0, b1 = 0.0, b2 = 2e-4, nominalVoltageKv = 220.0)
        val both = LineRatingEstimator.estimateLineRatingAmps(x = 5.0, b1 = 1e-4, b2 = 1e-4, nominalVoltageKv = 220.0)

        assertThat(onlyB1).isNotNull()
        assertThat(onlyB2).isNotNull()
        assertThat(both).isNotNull()
        // b1+b2 is the same total (2e-4) in all three cases, so the rating should match.
        assertThat(onlyB1!!).isCloseTo(both!!, within(0.01))
        assertThat(onlyB2!!).isCloseTo(both, within(0.01))
    }

    @Test
    fun `estimateLineRatingAmps falls back to a conservative surge impedance when B1 and B2 are both zero`() {
        // Should not throw/divide-by-zero, and should still return a positive rating.
        val ratingA = LineRatingEstimator.estimateLineRatingAmps(x = 5.0, b1 = 0.0, b2 = 0.0, nominalVoltageKv = 220.0)

        assertThat(ratingA).isNotNull()
        assertThat(ratingA!!).isGreaterThan(0.0)
    }

    @Test
    fun `estimateLineRatingAmps returns null for non-positive nominal voltage`() {
        assertThat(LineRatingEstimator.estimateLineRatingAmps(x = 5.0, b1 = 1e-4, b2 = 1e-4, nominalVoltageKv = 0.0)).isNull()
        assertThat(LineRatingEstimator.estimateLineRatingAmps(x = 5.0, b1 = 1e-4, b2 = 1e-4, nominalVoltageKv = -10.0)).isNull()
    }

    @Test
    fun `estimateLineRatingAmps returns null for negative reactance combined with nonzero B`() {
        // sqrt(negative) is NaN — must not leak a NaN rating out to callers.
        assertThat(LineRatingEstimator.estimateLineRatingAmps(x = -5.0, b1 = 1e-4, b2 = 1e-4, nominalVoltageKv = 220.0)).isNull()
    }

    // -------------------------------------------------------------------------
    // Transformer ratings (ratedS-based)
    // -------------------------------------------------------------------------

    @Test
    fun `estimateTransformerRatingAmps computes from ratedS and ratedU1`() {
        // I = 200e6 / (sqrt3 * 220000) ~= 524.86 A
        val expectedAmps = 200.0 * 1.0e6 / (sqrt(3.0) * 220.0 * 1_000.0)

        val ratingA = LineRatingEstimator.estimateTransformerRatingAmps(ratedSMva = 200.0, ratedU1Kv = 220.0)

        assertThat(ratingA).isNotNull()
        assertThat(ratingA!!).isCloseTo(expectedAmps, within(0.01))
    }

    @Test
    fun `estimateTransformerRatingAmps returns null when ratedU1Kv is non-positive`() {
        assertThat(LineRatingEstimator.estimateTransformerRatingAmps(ratedSMva = 200.0, ratedU1Kv = 0.0)).isNull()
        assertThat(LineRatingEstimator.estimateTransformerRatingAmps(ratedSMva = 200.0, ratedU1Kv = -10.0)).isNull()
        assertThat(LineRatingEstimator.estimateTransformerRatingAmps(ratedSMva = 200.0, ratedU1Kv = Double.NaN)).isNull()
    }

    @Test
    fun `estimateTransformerRatingAmps falls back to a conservative nameplate MVA when ratedSMva is missing`() {
        // Mirrors ieee14's off-nominal-ratio phase-shifting transformers (e.g. T4-7-1),
        // which the PowSyBl CDF converter builds without a ratedS at all.
        val zero = LineRatingEstimator.estimateTransformerRatingAmps(ratedSMva = 0.0, ratedU1Kv = 220.0)
        val negative = LineRatingEstimator.estimateTransformerRatingAmps(ratedSMva = -50.0, ratedU1Kv = 220.0)
        val nan = LineRatingEstimator.estimateTransformerRatingAmps(ratedSMva = Double.NaN, ratedU1Kv = 220.0)

        assertThat(zero).isNotNull()
        assertThat(negative).isNotNull()
        assertThat(nan).isNotNull()
        // All three "missing" cases should fall back to the same conservative rating.
        assertThat(zero!!).isCloseTo(negative!!, within(0.01))
        assertThat(zero).isCloseTo(nan!!, within(0.01))
    }
}
