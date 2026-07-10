package com.gridmaster.engine.network

import kotlin.math.sqrt

/**
 * Derives thermal current ratings for lines and transformers that have no
 * explicit `currentLimits` set on the PowSyBl model.
 *
 * Background (issue #395): several presets — the full ieee14 preset (sourced
 * from PowSyBl's IeeeCdfNetworkFactory, whose classic CDF data carries no
 * thermal ratings), most tutorial-preset lines, and every freeplay50
 * transformer — have no thermal limit at all today. Without one,
 * Line.loadingPercent and TwoWindingsTransformer.loadingPercent always
 * return 0.0, so those elements can never be flagged as overloaded.
 *
 * Line ratings use a surge-impedance-loadability (SIL) approach, the
 * standard transmission-planning rule of thumb described by the St. Clair
 * curves (Dunlop, Gutman and Marchenko's analytical development of the 1953
 * St. Clair curve; see the Iowa State reference and the 3-phase-EE SIL
 * reference cited in the issue):
 *
 * - Surge impedance, lossless approximation: Z0 = sqrt(X / B), ignoring the
 *   (typically small or zero) resistance and conductance terms.
 * - Surge impedance loading: SIL (MW) = V_nominal squared / Z0.
 * - Loadability: for short lines (well under 80 km) the thermal limit
 *   dominates and is typically several multiples of SIL; voltage-drop and
 *   stability limits only bind for much longer lines. None of this game's
 *   synthetic lines model an explicit length, so a flat loadability factor
 *   of 3 times SIL is used as the thermal-dominated-regime default. This is
 *   a documented simplification — a future enhancement could vary the
 *   factor with an explicit line-length field if one is ever added.
 * - Conversion to a current rating: I (A) = loadability (MW) times 1e6,
 *   divided by (sqrt(3) times V_nominal in volts).
 *
 * Transformer ratings need no new physics: every transformer in these
 * presets already declares a ratedS (nameplate apparent power); the rating
 * is simply I (A) = ratedS times 1e6, divided by (sqrt(3) times ratedU1 in
 * volts).
 */
object LineRatingEstimator {
    private val SQRT3 = sqrt(3.0)

    /** St. Clair-curve loadability multiple applied to SIL for short, thermally-limited lines. */
    private const val LOADABILITY_FACTOR = 3.0

    /**
     * Conservative fallback surge impedance (ohms) used when a line reports
     * zero total shunt susceptance on both legs (B1 = B2 = 0.0), which would
     * otherwise make the X / B ratio undefined. 400 ohms sits in the middle
     * of the typical 300-500 ohm range for HV overhead lines per the 3-phase-EE
     * SIL reference cited in the issue, so it produces a plausible, moderately
     * conservative rating rather than skipping the line entirely.
     */
    private const val FALLBACK_SURGE_IMPEDANCE_OHM = 400.0

    /**
     * Conservative fallback nameplate rated apparent power (MVA) used when a
     * transformer declares no ratedS at all (some ieee14 phase-shifting
     * connections). A mid-range HV transformer size, consistent with the
     * 200 MVA step-down transformers used elsewhere in these presets but
     * deliberately smaller — being conservative (a lower assumed rating)
     * means an ambiguous transformer is more likely to surface as a thermal
     * violation than to silently hide one.
     */
    private const val FALLBACK_TRANSFORMER_RATED_S_MVA = 100.0

    /**
     * Estimates a thermal current rating (amps) for a line from its reactance,
     * shunt susceptance and nominal voltage, using the SIL / loadability
     * approach documented on this object.
     *
     * Falls back to [FALLBACK_SURGE_IMPEDANCE_OHM] when both [b1] and [b2] are
     * zero (or negative, which should not occur but is treated the same way),
     * since the lossless surge-impedance formula cannot be evaluated in that
     * case. Uses whichever leg's susceptance is available otherwise — some of
     * this game's synthetic lines only populate one leg.
     *
     * @param x reactance (ohms)
     * @param b1 shunt susceptance on leg 1 (siemens)
     * @param b2 shunt susceptance on leg 2 (siemens)
     * @param nominalVoltageKv nominal voltage of the line (kV)
     * @return estimated permanent current limit in amps, or null if
     *   [nominalVoltageKv] is not positive (rating is meaningless without it)
     */
    fun estimateLineRatingAmps(
        x: Double,
        b1: Double,
        b2: Double,
        nominalVoltageKv: Double,
    ): Double? {
        if (nominalVoltageKv <= 0.0) return null

        val totalB = b1 + b2
        val z0 =
            if (totalB > 0.0) {
                sqrt(x / totalB)
            } else {
                FALLBACK_SURGE_IMPEDANCE_OHM
            }
        if (z0.isNaN() || z0 <= 0.0) return null

        val silMw = (nominalVoltageKv * nominalVoltageKv) / z0
        val loadabilityMw = LOADABILITY_FACTOR * silMw
        return mwToAmps(loadabilityMw, nominalVoltageKv)
    }

    /**
     * Estimates a thermal current rating (amps) for a two-winding transformer
     * directly from its own nameplate rated apparent power and rated primary
     * voltage — no derived physics, just unit conversion.
     *
     * A handful of ieee14 transformers (the off-nominal-ratio phase-shifting
     * connections at buses 4-7, 4-9 and 5-6, modelled by PowSyBl's CDF
     * converter without ratedS) declare no nameplate apparent power at all.
     * Rather than leave those completely unrated, fall back to
     * [FALLBACK_TRANSFORMER_RATED_S_MVA] — a conservative mid-range HV
     * transformer nameplate — so every transformer still gets a plausible
     * thermal limit.
     *
     * @param ratedSMva nameplate rated apparent power (MVA)
     * @param ratedU1Kv rated voltage of winding 1 (kV)
     * @return estimated permanent current limit in amps, or null if
     *   [ratedU1Kv] is not positive (rating is meaningless without it)
     */
    fun estimateTransformerRatingAmps(
        ratedSMva: Double,
        ratedU1Kv: Double,
    ): Double? {
        if (ratedU1Kv.isNaN() || ratedU1Kv <= 0.0) return null
        val effectiveRatedSMva =
            if (ratedSMva.isNaN() || ratedSMva <= 0.0) FALLBACK_TRANSFORMER_RATED_S_MVA else ratedSMva
        return mwToAmps(effectiveRatedSMva, ratedU1Kv)
    }

    /** Converts an MW (or MVA) figure at [voltageKv] to a current in amps: I = MW * 1e6 / (sqrt3 * V_V). */
    private fun mwToAmps(
        mw: Double,
        voltageKv: Double,
    ): Double = mw * 1.0e6 / (SQRT3 * voltageKv * 1_000.0)
}
