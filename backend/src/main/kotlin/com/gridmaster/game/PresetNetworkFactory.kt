package com.gridmaster.game

import com.gridmaster.engine.model.ExpansionSite
import com.gridmaster.engine.model.ExpansionSiteKind
import com.gridmaster.engine.model.LocationHint
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory
import com.powsybl.iidm.network.Network
import com.powsybl.iidm.network.NetworkFactory
import com.powsybl.iidm.network.Substation
import com.powsybl.iidm.network.TopologyKind
import com.powsybl.iidm.network.VoltageLevel

/**
 * Creates seed PowSyBl [Network] objects for each [GameMode] preset.
 *
 * Networks are constructed programmatically so no classpath resources are required.
 * Module 07+ will replace these stubs with proper tutorial/free-play networks as
 * the game curriculum is built out.
 *
 * Topology (tutorial preset; see [IeeeCdfNetworkFactory.create14Solved] for the ieee14 preset):
 *
 *   G1 (gas, 100 MW)                G2 (coal, 200 MW)
 *      |                                  |
 *   [B1 220kV]──L12──[B2 220kV]──L23──[B3 220kV]
 *      |                                  |
 *    TX12 (S1 HV→LV)                    L34
 *      |                                  |
 *   [B1L 110kV]            [B4 220kV]──Load2
 *                               |
 *   L14: B1 (HV)──────────────/
 *
 * Free-play topology (freeplay50 preset, ~50-bus AC network):
 *
 *   North region (industrial/thermal): 10 substations, 19 buses
 *     Coal Alpha (NS1) + Coal Beta (NS2) + Gas Peaker (NS3) + CCGT (NS4)
 *     Industrial Hub A/B (NS5/NS6) + City North (NS7) + Mining (NS8)
 *     North Suburbs (NS9) + North Hub (NS10)
 *
 *   East region (coastal/wind): 9 substations, 15 buses
 *     Wind Platforms 1-3 (ES1-ES3) + Gas Backup (ES4)
 *     Coastal City (ES5) + Port Industrial (ES6) + Suburbs (ES7) + Coastal 2 (ES8)
 *     East Hub (ES9)
 *
 *   South region (solar/residential): 10 substations, 16 buses
 *     Solar Farms 1-3 (SS1/SS2/SS9) + CCGT South (SS3) + Gas South (SS4)
 *     Residential A-C (SS5-SS7) + Commercial Centre (SS8)
 *     South Hub (SS10)
 *
 *   Inter-region 220kV ties: N-Hub ↔ E-Hub, N-Hub ↔ S-Hub, E-Hub ↔ S-Hub
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
            "ieee14" -> buildIeee14Network()
            "freeplay50" -> buildFreePlay50Network() // #47
            else -> throw IllegalArgumentException(
                "Unknown network preset: '$networkPreset'. " +
                    "Valid presets: ${knownPresets.joinToString()}",
            )
        }

    /**
     * Dormant [ExpansionSite]s pre-built into [networkPreset]'s IIDM topology
     * by [create] (#414). Empty for presets that don't support expansion
     * (only `freeplay50` does in v1) -- metadata only, not embedded in the
     * IIDM network itself, mirroring how [Region] membership is supplied
     * externally rather than read off the network. See
     * `docs/engineering/17-grid-expansion.md`'s "Expansion sites: pre-built,
     * dormant topology" section.
     */
    fun expansionSitesFor(networkPreset: String): List<ExpansionSite> =
        when (networkPreset) {
            "freeplay50" -> freePlay50ExpansionSites()
            else -> emptyList()
        }

    // -------------------------------------------------------------------------
    // Thermal rating backfill (#395) — N-1-contingency-derived, hardcoded
    // -------------------------------------------------------------------------
    //
    // Methodology (superseding the earlier SIL-formula estimator, LineRatingEstimator,
    // removed in this follow-up per direction from Rick): for each preset, a one-off
    // N-1 contingency analysis was run (see the (deleted) scratch discovery test that
    // produced these numbers, `LineRatingDiscoveryTest`, run once and captured — not a
    // runtime computation):
    //
    //   1. Solve the base-case AC load flow for the preset network as built below.
    //   2. For every line and two-winding transformer, disconnect it alone (single
    //      N-1 outage) on a cloned PowSyBl variant, re-solve, and record the resulting
    //      current (A) on every still-in-service line/transformer.
    //   3. Take the max observed current across (base case union all N-1 outages)
    //      for each element.
    //   4. Apply a 20% security margin on top: rating = 1.2 x maxObservedCurrentA.
    //      20% is a difficulty knob (the project's default choice for this pass) —
    //      lower it to make overload easier to trigger, raise it to make the grid
    //      more forgiving. Tune per element if a specific preset needs to feel harder
    //      or easier.
    //
    // The result is a concrete number per element that is inherently N-1-safe: the
    // network can survive the loss of any single line/transformer without the
    // *other* elements exceeding their rating. This does not model generator N-1 —
    // see LineRatingDiscoveryTest's KDoc (before deletion) / PR discussion for why:
    // plain AC LoadFlow doesn't enforce generator maxP, so a generator outage can
    // force an unrealistic, physically-meaningless redispatch that would inflate
    // ratings without representing anything the game's dispatch logic could ever
    // produce. Scope is topology (branch) N-1 only.
    //
    // One exception: tutorial's TX12 carries exactly 0 A in every scenario (base
    // case and all N-1 outages) because its LV bus (B1L) has no load or generation
    // attached — it's a decorative dead-end step-down in the current topology. A
    // margined-zero rating would be meaningless (permanently "overloaded" at any
    // nonzero current), so TX12 uses its own nameplate rating instead
    // (I = ratedS / (sqrt3 x ratedU1), no additional margin — nameplate capacity is
    // already the equipment's real thermal limit).
    //
    // ieee14's L1-2-1 outage does not converge at all in the base topology (a
    // voltage-stability/cascading-failure condition, not a thermal-overload one) —
    // that scenario contributes no current data and is a separate, out-of-scope
    // finding flagged for Rick; it does not affect the ratings below, which are
    // still derived from every other (converging) scenario.
    //
    // If any preset's topology or generator dispatch changes materially, these
    // hardcoded numbers should be recomputed by rerunning the same kind of
    // discovery analysis and reapplying the 20% margin.
    //
    // This is a deliberate manual process, not an automated one (#399, Gemini
    // review on #396): running the N-1 discovery analysis on every preset build
    // would reintroduce the runtime-computed-rating approach this file explicitly
    // moved away from (see the SIL-formula rejection note above). The recompute
    // recipe: solve the base case, then for every line/transformer disconnect it
    // alone, re-solve, record the resulting current on every other still-in-service
    // element, take the max observed current per element across all scenarios, and
    // apply the 20% margin — the same steps used to produce the numbers below.

    /** ieee14 line ratings (A), N-1-derived + 20% margin. See methodology note above. */
    private val IEEE14_LINE_RATINGS_A =
        mapOf(
            "L1-2-1" to 103_402.9,
            "L1-5-1" to 49_459.5,
            "L2-3-1" to 48_656.8,
            "L2-4-1" to 37_228.6,
            "L2-5-1" to 27_635.4,
            "L3-4-1" to 16_439.9,
            "L4-5-1" to 43_007.0,
            "L6-11-1" to 5_290.0,
            "L6-12-1" to 5_295.6,
            "L6-13-1" to 12_405.4,
            "L7-8-1" to 11_201.7,
            "L7-9-1" to 18_707.3,
            "L9-10-1" to 4_407.7,
            "L9-14-1" to 6_622.9,
            "L10-11-1" to 2_713.0,
            "L12-13-1" to 1_169.8,
            "L13-14-1" to 3_896.9,
        )

    /** ieee14 two-winding transformer ratings (A), N-1-derived + 20% margin. */
    private val IEEE14_TRANSFORMER_RATINGS_A =
        mapOf(
            "T4-7-1" to 20_217.1,
            "T4-9-1" to 10_950.8,
            "T5-6-1" to 31_135.5,
        )

    /**
     * Applies the hardcoded, N-1-analysis-derived thermal current ratings to the
     * ieee14 network's lines and two-winding transformers.
     *
     * Idempotent: only IDs present in [IEEE14_LINE_RATINGS_A]/[IEEE14_TRANSFORMER_RATINGS_A]
     * are touched, and only if the element doesn't already have a current limit set.
     */
    private fun applyIeee14ThermalRatings(network: Network) {
        IEEE14_LINE_RATINGS_A.forEach { (id, ratingA) ->
            val line = network.getLine(id) ?: return@forEach
            if (line.currentLimits1.isPresent || line.currentLimits2.isPresent) return@forEach
            line.newCurrentLimits1().setPermanentLimit(ratingA).add()
        }
        IEEE14_TRANSFORMER_RATINGS_A.forEach { (id, ratingA) ->
            val twt = network.getTwoWindingsTransformer(id) ?: return@forEach
            if (twt.currentLimits1.isPresent || twt.currentLimits2.isPresent) return@forEach
            twt.newCurrentLimits1().setPermanentLimit(ratingA).add()
        }
    }

    // -------------------------------------------------------------------------
    // Post-load normalisation
    // -------------------------------------------------------------------------

    /**
     * Normalises generator MW limits on any [Network] loaded from persisted IIDM XML.
     *
     * Corrects the sentinel ±9999 MW values that PowSyBl writes when the CDF source
     * file has no explicit MW limits (IEEE 14-bus).  Safe to call on any network:
     * generators whose limits are already within a realistic range (<1000 MW) are
     * left unchanged.
     *
     * Called during session load so that sessions created before PR #275 are
     * automatically corrected on next resume without requiring the user to delete.
     *
     * @see com.gridmaster.game.GameSessionService.load
     */
    fun normalizeGeneratorBounds(network: Network) {
        network.generators.forEach { gen ->
            if (gen.maxP > 1_000.0) {
                val realisticMax =
                    if (gen.targetP > 5.0) (gen.targetP * 1.5).coerceAtMost(500.0) else 50.0
                gen.setMaxP(realisticMax)
            }
            if (gen.minP < 0.0) gen.setMinP(0.0)
            val clamped = gen.targetP.coerceIn(gen.minP, gen.maxP)
            if (clamped != gen.targetP) gen.setTargetP(clamped)
        }
    }

    // -------------------------------------------------------------------------
    // Private builders
    // -------------------------------------------------------------------------

    /**
     * Returns the pre-solved IEEE 14-bus network with realistic generator MW limits.
     *
     * [IeeeCdfNetworkFactory.create14Solved] initialises generator minP/maxP from the
     * standard CDF file, which contains no explicit MW limits — PowSyBl therefore
     * defaults them to ±9999 MW.  Those sentinel values are meaningless in the game
     * context (Dispatch panel shows "9999 MW", LP solver has no useful bounds).
     *
     * Post-processing clamps each generator to a realistic operating range:
     * - `maxP` is capped to 1.5× the solved-case setpoint (floor 50 MW for
     *   zero-dispatch synchronous condensers at buses 3, 6, and 8).
     * - `minP` is floored at 0 MW (conventional machines cannot export negatively).
     * - `targetP` is clamped to stay within `[minP, maxP]`.
     */
    private fun buildIeee14Network(): Network =
        IeeeCdfNetworkFactory.create14Solved().also { network ->
            network.generators.forEach { gen ->
                if (gen.maxP > 1_000.0) {
                    // Derive a realistic cap from the solved-case operating point:
                    // 1.5× provides operating headroom while avoiding sentinel values.
                    // A 50 MW floor handles synchronous condensers (buses 3, 6, 8)
                    // whose solved-case targetP is 0 MW.
                    val realisticMax =
                        if (gen.targetP > 5.0) {
                            (gen.targetP * 1.5).coerceAtMost(500.0)
                        } else {
                            50.0
                        }
                    gen.setMaxP(realisticMax)
                }
                if (gen.minP < 0.0) {
                    gen.setMinP(0.0)
                }
                // Keep the initial setpoint within the updated [minP, maxP] window.
                val clamped = gen.targetP.coerceIn(gen.minP, gen.maxP)
                if (clamped != gen.targetP) gen.setTargetP(clamped)
            }
            applyIeee14ThermalRatings(network)
        }

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

        // Rating (376.9 A): N-1-derived + 20% margin, see PresetNetworkFactory-level
        // methodology note above applyIeee14ThermalRatings — max observed current across
        // base case + all single-line/transformer N-1 outages was ~314.1 A.
        network.newLine().setId("L23").setName("Industrial–Riverside")
            .setVoltageLevel1("VL2").setBus1("B2").setConnectableBus1("B2")
            .setVoltageLevel2("VL3").setBus2("B3").setConnectableBus2("B3")
            .setR(0.3).setX(3.0).setB1(1e-4).setB2(1e-4).setG1(0.0).setG2(0.0)
            .add().also { it.newCurrentLimits1().setPermanentLimit(376.9).add() }

        // Rating (47.4 A): N-1-derived + 20% margin — max observed current ~39.5 A.
        network.newLine().setId("L34").setName("Riverside–South")
            .setVoltageLevel1("VL3").setBus1("B3").setConnectableBus1("B3")
            .setVoltageLevel2("VL4").setBus2("B4").setConnectableBus2("B4")
            .setR(0.4).setX(4.0).setB1(0.0).setB2(0.0).setG1(0.0).setG2(0.0)
            .add().also { it.newCurrentLimits1().setPermanentLimit(47.4).add() }

        // Rating (213.0 A): N-1-derived + 20% margin — max observed current ~177.5 A.
        network.newLine().setId("L14").setName("North–South")
            .setVoltageLevel1("VL1").setBus1("B1").setConnectableBus1("B1")
            .setVoltageLevel2("VL4").setBus2("B4").setConnectableBus2("B4")
            .setR(0.6).setX(6.0).setB1(0.0).setB2(0.0).setG1(0.0).setG2(0.0)
            .add().also { it.newCurrentLimits1().setPermanentLimit(213.0).add() }

        // Transformer: HV → LV within S1
        // Rating (524.9 A): TX12 carries 0 A in every N-1 scenario today (its LV bus
        // B1L has no load/generation attached), so a margined-N-1 number would be
        // meaningless here. Falls back to the transformer's own nameplate rating
        // (I = ratedS / (sqrt3 x ratedU1) = 200 MVA / (sqrt3 x 220 kV)), which is a
        // real physical thermal limit independent of current usage.
        s1.newTwoWindingsTransformer().setId("TX12").setName("North Step-Down")
            .setVoltageLevel1("VL1").setBus1("B1").setConnectableBus1("B1")
            .setVoltageLevel2("VL1L").setBus2("B1L").setConnectableBus2("B1L")
            .setRatedU1(220.0).setRatedU2(110.0).setRatedS(200.0)
            .setR(0.1).setX(10.0).setB(0.0).setG(0.0)
            .add().also { it.newCurrentLimits1().setPermanentLimit(524.9).add() }

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

    /**
     * Build the free-play seed network: ~50-bus, three-region, AC-convergent.
     *
     * Design parameters
     * -----------------
     * Buses      : 50 (19 North / 15 East / 16 South); BUS_BREAKER topology
     * Voltage    : 220 kV HV backbone + 110 kV LV distribution at each load substation
     * Substations: 29 (10 North + 9 East + 10 South)
     * Generators : 13 (4 North thermal, 4 East wind/gas, 5 South solar/CCGT/gas)
     * Loads      : 21 distributed across 220/110 kV buses
     * Lines      : 30 (10 N-intra + 8 E-intra + 9 S-intra + 3 inter-region ties)
     * Transformers: 21 HV→LV step-down (220 kV / 110 kV, 200 MVA each)
     *
     * Generation headroom: ~2 050 MW dispatch vs ~1 900 MW load;
     * slack bus absorbs mismatch. All generators are voltage-regulating.
     *
     * Region bus memberships are stored externally (see Module 13 region-unlock design);
     * they are NOT embedded in the IIDM network.
     */
    @Suppress("LongMethod")
    private fun buildFreePlay50Network(): Network {
        val network = NetworkFactory.findDefault().createNetwork("freeplay-50bus", "free-play")

        // ── local helpers ────────────────────────────────────────────────────

        fun Substation.vl(
            id: String,
            name: String,
            nomV: Double,
        ): VoltageLevel =
            newVoltageLevel()
                .setId(id)
                .setName(name)
                .setNominalV(nomV)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add()

        fun VoltageLevel.bus(
            id: String,
            name: String,
        ) = busBreakerView.newBus().setId(id).setName(name).add()

        // ratingA per transformer: N-1-derived + 20% margin, see the methodology
        // note on PresetNetworkFactory's ieee14 rating maps above — same one-off
        // discovery analysis, applied here per freeplay50 step-down transformer.
        fun Substation.stepDown(
            id: String,
            name: String,
            vlHvId: String,
            busHvId: String,
            vlLvId: String,
            busLvId: String,
            ratingA: Double,
        ) {
            newTwoWindingsTransformer()
                .setId(id).setName(name)
                .setVoltageLevel1(vlHvId).setBus1(busHvId).setConnectableBus1(busHvId)
                .setVoltageLevel2(vlLvId).setBus2(busLvId).setConnectableBus2(busLvId)
                .setRatedU1(220.0).setRatedU2(110.0).setRatedS(200.0)
                .setR(0.1).setX(10.0).setB(0.0).setG(0.0)
                .add()
                .also { it.newCurrentLimits1().setPermanentLimit(ratingA).add() }
        }

        fun line(
            id: String,
            name: String,
            vl1: String,
            b1: String,
            vl2: String,
            b2: String,
            r: Double,
            x: Double,
            ratingA: Double = 500.0,
        ) {
            network.newLine()
                .setId(id).setName(name)
                .setVoltageLevel1(vl1).setBus1(b1).setConnectableBus1(b1)
                .setVoltageLevel2(vl2).setBus2(b2).setConnectableBus2(b2)
                .setR(r).setX(x).setB1(5e-5).setB2(5e-5).setG1(0.0).setG2(0.0)
                .add()
                .also { it.newCurrentLimits1().setPermanentLimit(ratingA).add() }
        }

        fun VoltageLevel.gen(
            id: String,
            name: String,
            busId: String,
            minP: Double,
            maxP: Double,
            targetP: Double,
            targetQ: Double = 0.0,
        ) {
            newGenerator()
                .setId(id).setName(name)
                .setBus(busId).setConnectableBus(busId)
                .setMinP(minP).setMaxP(maxP).setTargetP(targetP)
                .setTargetQ(targetQ).setTargetV(nominalV)
                .setVoltageRegulatorOn(true)
                .add()
        }

        fun VoltageLevel.load(
            id: String,
            name: String,
            busId: String,
            p0: Double,
            q0: Double,
        ) {
            newLoad()
                .setId(id).setName(name)
                .setBus(busId).setConnectableBus(busId)
                .setP0(p0).setQ0(q0)
                .add()
        }

        // =====================================================================
        // NORTH REGION — industrial / thermal  (19 buses)
        // =====================================================================

        val sN1 = network.newSubstation().setId("NS1").setName("Coal Station Alpha").add()
        val sN2 = network.newSubstation().setId("NS2").setName("Coal Station Beta").add()
        val sN3 = network.newSubstation().setId("NS3").setName("Gas Peaker North").add()
        val sN4 = network.newSubstation().setId("NS4").setName("CCGT North").add()
        val sN5 = network.newSubstation().setId("NS5").setName("Industrial Hub A").add()
        val sN6 = network.newSubstation().setId("NS6").setName("Industrial Hub B").add()
        val sN7 = network.newSubstation().setId("NS7").setName("City North").add()
        val sN8 = network.newSubstation().setId("NS8").setName("Mining District").add()
        val sN9 = network.newSubstation().setId("NS9").setName("North Suburbs").add()
        val sN10 = network.newSubstation().setId("NS10").setName("North Grid Hub").add()

        // Voltage levels (HV = 220 kV, LV = 110 kV)
        val vlN1H = sN1.vl("N-VL1H", "Coal Alpha 220kV", 220.0)
        val vlN1L = sN1.vl("N-VL1L", "Coal Alpha 110kV", 110.0)
        val vlN2H = sN2.vl("N-VL2H", "Coal Beta 220kV", 220.0)
        val vlN2L = sN2.vl("N-VL2L", "Coal Beta 110kV", 110.0)
        val vlN3H = sN3.vl("N-VL3H", "Gas North 220kV", 220.0)
        val vlN3L = sN3.vl("N-VL3L", "Gas North 110kV", 110.0)
        val vlN4H = sN4.vl("N-VL4H", "CCGT North 220kV", 220.0)
        val vlN4L = sN4.vl("N-VL4L", "CCGT North 110kV", 110.0)
        val vlN5H = sN5.vl("N-VL5H", "Industrial A 220kV", 220.0)
        val vlN5L = sN5.vl("N-VL5L", "Industrial A 110kV", 110.0)
        val vlN6H = sN6.vl("N-VL6H", "Industrial B 220kV", 220.0)
        val vlN6L = sN6.vl("N-VL6L", "Industrial B 110kV", 110.0)
        val vlN7H = sN7.vl("N-VL7H", "City North 220kV", 220.0)
        val vlN7L = sN7.vl("N-VL7L", "City North 110kV", 110.0)
        val vlN8H = sN8.vl("N-VL8H", "Mining 220kV", 220.0)
        val vlN8L = sN8.vl("N-VL8L", "Mining 110kV", 110.0)
        val vlN9H = sN9.vl("N-VL9H", "Suburbs N 220kV", 220.0)
        val vlN9L = sN9.vl("N-VL9L", "Suburbs N 110kV", 110.0)
        val vlN10 = sN10.vl("N-VL10", "North Hub 220kV", 220.0)

        // Buses — North (19 buses)
        vlN1H.bus("N-B1H", "Coal Alpha HV")
        vlN1L.bus("N-B1L", "Coal Alpha LV")
        vlN2H.bus("N-B2H", "Coal Beta HV")
        vlN2L.bus("N-B2L", "Coal Beta LV")
        vlN3H.bus("N-B3H", "Gas North HV")
        vlN3L.bus("N-B3L", "Gas North LV")
        vlN4H.bus("N-B4H", "CCGT North HV")
        vlN4L.bus("N-B4L", "CCGT North LV")
        vlN5H.bus("N-B5H", "Industrial A HV")
        vlN5L.bus("N-B5L", "Industrial A LV")
        vlN6H.bus("N-B6H", "Industrial B HV")
        vlN6L.bus("N-B6L", "Industrial B LV")
        vlN7H.bus("N-B7H", "City North HV")
        vlN7L.bus("N-B7L", "City North LV")
        vlN8H.bus("N-B8H", "Mining HV")
        vlN8L.bus("N-B8L", "Mining LV")
        vlN9H.bus("N-B9H", "Suburbs N HV")
        vlN9L.bus("N-B9L", "Suburbs N LV")
        vlN10.bus("N-B10", "North Hub")

        // North generators (HV buses)
        vlN1H.gen("G-COAL-N1", "Coal Alpha", "N-B1H", minP = 80.0, maxP = 300.0, targetP = 280.0, targetQ = 50.0)
        vlN2H.gen("G-COAL-N2", "Coal Beta", "N-B2H", minP = 80.0, maxP = 250.0, targetP = 230.0, targetQ = 40.0)
        vlN3H.gen("G-GAS-N", "Gas Peaker North", "N-B3H", minP = 20.0, maxP = 120.0, targetP = 100.0, targetQ = 10.0)
        vlN4H.gen("G-CCGT-N", "CCGT North", "N-B4H", minP = 50.0, maxP = 200.0, targetP = 160.0, targetQ = 20.0)

        // North loads (LV buses, fed via step-down transformers)
        vlN1L.load("LOAD-N-AUX1", "Coal Alpha Aux", "N-B1L", p0 = 20.0, q0 = 6.0)
        vlN2L.load("LOAD-N-AUX2", "Coal Beta Aux", "N-B2L", p0 = 15.0, q0 = 5.0)
        vlN3L.load("LOAD-N-TOWN", "North Town", "N-B3L", p0 = 60.0, q0 = 18.0)
        vlN4L.load("LOAD-N-CCGTX", "CCGT North Aux", "N-B4L", p0 = 10.0, q0 = 3.0)
        vlN5L.load("LOAD-N-IND-A", "Industrial Hub A", "N-B5L", p0 = 180.0, q0 = 54.0)
        vlN6L.load("LOAD-N-IND-B", "Industrial Hub B", "N-B6L", p0 = 150.0, q0 = 45.0)
        vlN7L.load("LOAD-N-CITY", "City North", "N-B7L", p0 = 200.0, q0 = 60.0)
        vlN8L.load("LOAD-N-MINE", "Mining District", "N-B8L", p0 = 120.0, q0 = 36.0)
        vlN9L.load("LOAD-N-SUB", "North Suburbs", "N-B9L", p0 = 90.0, q0 = 27.0)

        // North step-down transformers (220 kV → 110 kV)
        sN1.stepDown("TX-N1", "Coal Alpha Step-Down", "N-VL1H", "N-B1H", "N-VL1L", "N-B1L", ratingA = 132.2)
        sN2.stepDown("TX-N2", "Coal Beta Step-Down", "N-VL2H", "N-B2H", "N-VL2L", "N-B2L", ratingA = 100.0)
        sN3.stepDown("TX-N3", "Gas North Step-Down", "N-VL3H", "N-B3H", "N-VL3L", "N-B3L", ratingA = 401.3)
        sN4.stepDown("TX-N4", "CCGT North Step-Down", "N-VL4H", "N-B4H", "N-VL4L", "N-B4L", ratingA = 65.9)
        sN5.stepDown("TX-N5", "Industrial A Step-Down", "N-VL5H", "N-B5H", "N-VL5L", "N-B5L", ratingA = 1273.7)
        sN6.stepDown("TX-N6", "Industrial B Step-Down", "N-VL6H", "N-B6H", "N-VL6L", "N-B6L", ratingA = 1056.8)
        sN7.stepDown("TX-N7", "City North Step-Down", "N-VL7H", "N-B7H", "N-VL7L", "N-B7L", ratingA = 1484.9)
        sN8.stepDown("TX-N8", "Mining Step-Down", "N-VL8H", "N-B8H", "N-VL8L", "N-B8L", ratingA = 855.7)
        sN9.stepDown("TX-N9", "Suburbs N Step-Down", "N-VL9H", "N-B9H", "N-VL9L", "N-B9L", ratingA = 630.2)

        // North 220 kV backbone (10 lines)
        line("LN-1", "Coal Alpha–Beta", "N-VL1H", "N-B1H", "N-VL2H", "N-B2H", r = 0.5, x = 5.0)
        line("LN-2", "Coal Alpha–Hub", "N-VL1H", "N-B1H", "N-VL10", "N-B10", r = 0.8, x = 8.0)
        line("LN-3", "Coal Beta–Ind A", "N-VL2H", "N-B2H", "N-VL5H", "N-B5H", r = 0.5, x = 5.0)
        line("LN-4", "Gas–Ind A", "N-VL3H", "N-B3H", "N-VL5H", "N-B5H", r = 0.5, x = 5.0)
        line("LN-5", "CCGT–Ind A", "N-VL4H", "N-B4H", "N-VL5H", "N-B5H", r = 0.5, x = 5.0)
        line("LN-6", "Ind A–Ind B", "N-VL5H", "N-B5H", "N-VL6H", "N-B6H", r = 0.3, x = 3.0)
        line("LN-7", "Ind B–Hub", "N-VL6H", "N-B6H", "N-VL10", "N-B10", r = 0.5, x = 5.0)
        line("LN-8", "City North–Hub", "N-VL7H", "N-B7H", "N-VL10", "N-B10", r = 0.6, x = 6.0)
        line("LN-9", "Mining–Suburbs", "N-VL8H", "N-B8H", "N-VL9H", "N-B9H", r = 0.4, x = 4.0)
        line("LN-10", "Suburbs–Hub", "N-VL9H", "N-B9H", "N-VL10", "N-B10", r = 0.5, x = 5.0)

        // =====================================================================
        // EAST REGION — coastal / wind  (15 buses)
        // =====================================================================

        val sE1 = network.newSubstation().setId("ES1").setName("Offshore Wind 1").add()
        val sE2 = network.newSubstation().setId("ES2").setName("Offshore Wind 2").add()
        val sE3 = network.newSubstation().setId("ES3").setName("Offshore Wind 3").add()
        val sE4 = network.newSubstation().setId("ES4").setName("Gas Backup East").add()
        val sE5 = network.newSubstation().setId("ES5").setName("Coastal City").add()
        val sE6 = network.newSubstation().setId("ES6").setName("Port Industrial").add()
        val sE7 = network.newSubstation().setId("ES7").setName("East Suburbs").add()
        val sE8 = network.newSubstation().setId("ES8").setName("East Coastal 2").add()
        val sE9 = network.newSubstation().setId("ES9").setName("East Grid Hub").add()

        val vlE1H = sE1.vl("E-VL1H", "Wind 1 220kV", 220.0)
        val vlE2H = sE2.vl("E-VL2H", "Wind 2 220kV", 220.0)
        val vlE2L = sE2.vl("E-VL2L", "Wind 2 110kV", 110.0)
        val vlE3H = sE3.vl("E-VL3H", "Wind 3 220kV", 220.0)
        val vlE3L = sE3.vl("E-VL3L", "Wind 3 110kV", 110.0)
        val vlE4H = sE4.vl("E-VL4H", "Gas East 220kV", 220.0)
        val vlE5H = sE5.vl("E-VL5H", "Coastal 220kV", 220.0)
        val vlE5L = sE5.vl("E-VL5L", "Coastal 110kV", 110.0)
        val vlE6H = sE6.vl("E-VL6H", "Port 220kV", 220.0)
        val vlE6L = sE6.vl("E-VL6L", "Port 110kV", 110.0)
        val vlE7H = sE7.vl("E-VL7H", "E-Sub 220kV", 220.0)
        val vlE7L = sE7.vl("E-VL7L", "E-Sub 110kV", 110.0)
        val vlE8H = sE8.vl("E-VL8H", "Coast2 220kV", 220.0)
        val vlE8L = sE8.vl("E-VL8L", "Coast2 110kV", 110.0)
        val vlE9 = sE9.vl("E-VL9", "East Hub 220kV", 220.0)

        // Buses — East (15 buses)
        vlE1H.bus("E-B1H", "Wind 1 HV")
        vlE2H.bus("E-B2H", "Wind 2 HV")
        vlE2L.bus("E-B2L", "Wind 2 LV")
        vlE3H.bus("E-B3H", "Wind 3 HV")
        vlE3L.bus("E-B3L", "Wind 3 LV")
        vlE4H.bus("E-B4H", "Gas East HV")
        vlE5H.bus("E-B5H", "Coastal HV")
        vlE5L.bus("E-B5L", "Coastal LV")
        vlE6H.bus("E-B6H", "Port HV")
        vlE6L.bus("E-B6L", "Port LV")
        vlE7H.bus("E-B7H", "E-Suburbs HV")
        vlE7L.bus("E-B7L", "E-Suburbs LV")
        vlE8H.bus("E-B8H", "Coastal 2 HV")
        vlE8L.bus("E-B8L", "Coastal 2 LV")
        vlE9.bus("E-B9", "East Hub")

        // East generators
        vlE1H.gen("G-WIND-E1", "Offshore Wind 1", "E-B1H", minP = 0.0, maxP = 200.0, targetP = 180.0)
        vlE2H.gen("G-WIND-E2", "Offshore Wind 2", "E-B2H", minP = 0.0, maxP = 180.0, targetP = 160.0)
        vlE3H.gen("G-WIND-E3", "Offshore Wind 3", "E-B3H", minP = 0.0, maxP = 160.0, targetP = 140.0)
        vlE4H.gen("G-GAS-E", "Gas Backup East", "E-B4H", minP = 20.0, maxP = 80.0, targetP = 65.0, targetQ = 5.0)

        // East loads (LV buses)
        vlE2L.load("LOAD-E-ISLAND", "Offshore Service Base", "E-B2L", p0 = 10.0, q0 = 3.0)
        vlE3L.load("LOAD-E-MARINA", "East Marina", "E-B3L", p0 = 20.0, q0 = 6.0)
        vlE5L.load("LOAD-E-COAST", "Coastal City", "E-B5L", p0 = 160.0, q0 = 48.0)
        vlE6L.load("LOAD-E-PORT", "Port Industrial", "E-B6L", p0 = 140.0, q0 = 42.0)
        vlE7L.load("LOAD-E-SUB", "East Suburbs", "E-B7L", p0 = 80.0, q0 = 24.0)
        vlE8L.load("LOAD-E-COAST2", "East Coastal 2", "E-B8L", p0 = 60.0, q0 = 18.0)

        // East step-down transformers
        sE2.stepDown("TX-E2", "Wind 2 Step-Down", "E-VL2H", "E-B2H", "E-VL2L", "E-B2L", ratingA = 65.9)
        sE3.stepDown("TX-E3", "Wind 3 Step-Down", "E-VL3H", "E-B3H", "E-VL3L", "E-B3L", ratingA = 132.2)
        sE5.stepDown("TX-E5", "Coastal Step-Down", "E-VL5H", "E-B5H", "E-VL5L", "E-B5L", ratingA = 1142.5)
        sE6.stepDown("TX-E6", "Port Step-Down", "E-VL6H", "E-B6H", "E-VL6L", "E-B6L", ratingA = 988.7)
        sE7.stepDown("TX-E7", "E-Sub Step-Down", "E-VL7H", "E-B7H", "E-VL7L", "E-B7L", ratingA = 552.1)
        sE8.stepDown("TX-E8", "Coast2 Step-Down", "E-VL8H", "E-B8H", "E-VL8L", "E-B8L", ratingA = 410.2)

        // East 220 kV backbone (8 lines)
        line("LE-1", "Wind 1–E Hub", "E-VL1H", "E-B1H", "E-VL9", "E-B9", r = 1.0, x = 10.0)
        line("LE-2", "Wind 2–E Hub", "E-VL2H", "E-B2H", "E-VL9", "E-B9", r = 1.0, x = 10.0)
        line("LE-3", "Wind 3–Gas East", "E-VL3H", "E-B3H", "E-VL4H", "E-B4H", r = 0.5, x = 5.0)
        line("LE-4", "Gas East–E Hub", "E-VL4H", "E-B4H", "E-VL9", "E-B9", r = 0.8, x = 8.0)
        line("LE-5", "Coastal–E Hub", "E-VL5H", "E-B5H", "E-VL9", "E-B9", r = 0.5, x = 5.0)
        line("LE-6", "Port–E Hub", "E-VL6H", "E-B6H", "E-VL9", "E-B9", r = 0.5, x = 5.0)
        line("LE-7", "E-Suburbs–Coast2", "E-VL7H", "E-B7H", "E-VL8H", "E-B8H", r = 0.3, x = 3.0)
        line("LE-8", "Coast2–E Hub", "E-VL8H", "E-B8H", "E-VL9", "E-B9", r = 0.5, x = 5.0)

        // =====================================================================
        // SOUTH REGION — solar / residential  (16 buses)
        // =====================================================================

        val sS1 = network.newSubstation().setId("SS1").setName("Solar Farm Alpha").add()
        val sS2 = network.newSubstation().setId("SS2").setName("Solar Farm Beta").add()
        val sS3 = network.newSubstation().setId("SS3").setName("CCGT South").add()
        val sS4 = network.newSubstation().setId("SS4").setName("Gas Peaker South").add()
        val sS5 = network.newSubstation().setId("SS5").setName("Residential A").add()
        val sS6 = network.newSubstation().setId("SS6").setName("Residential B").add()
        val sS7 = network.newSubstation().setId("SS7").setName("Residential C").add()
        val sS8 = network.newSubstation().setId("SS8").setName("Commercial Centre").add()
        val sS9 = network.newSubstation().setId("SS9").setName("Solar Farm Gamma").add()
        val sS10 = network.newSubstation().setId("SS10").setName("South Grid Hub").add()

        val vlS1H = sS1.vl("S-VL1H", "Solar Alpha 220kV", 220.0)
        val vlS2H = sS2.vl("S-VL2H", "Solar Beta 220kV", 220.0)
        val vlS3H = sS3.vl("S-VL3H", "CCGT S 220kV", 220.0)
        val vlS3L = sS3.vl("S-VL3L", "CCGT S 110kV", 110.0)
        val vlS4H = sS4.vl("S-VL4H", "Gas South 220kV", 220.0)
        val vlS4L = sS4.vl("S-VL4L", "Gas South 110kV", 110.0)
        val vlS5H = sS5.vl("S-VL5H", "Res A 220kV", 220.0)
        val vlS5L = sS5.vl("S-VL5L", "Res A 110kV", 110.0)
        val vlS6H = sS6.vl("S-VL6H", "Res B 220kV", 220.0)
        val vlS6L = sS6.vl("S-VL6L", "Res B 110kV", 110.0)
        val vlS7H = sS7.vl("S-VL7H", "Res C 220kV", 220.0)
        val vlS7L = sS7.vl("S-VL7L", "Res C 110kV", 110.0)
        val vlS8H = sS8.vl("S-VL8H", "Commercial 220kV", 220.0)
        val vlS8L = sS8.vl("S-VL8L", "Commercial 110kV", 110.0)
        val vlS9H = sS9.vl("S-VL9H", "Solar Gamma 220kV", 220.0)
        val vlS10 = sS10.vl("S-VL10", "South Hub 220kV", 220.0)

        // Buses — South (16 buses)
        vlS1H.bus("S-B1H", "Solar Alpha HV")
        vlS2H.bus("S-B2H", "Solar Beta HV")
        vlS3H.bus("S-B3H", "CCGT South HV")
        vlS3L.bus("S-B3L", "CCGT South LV")
        vlS4H.bus("S-B4H", "Gas South HV")
        vlS4L.bus("S-B4L", "Gas South LV")
        vlS5H.bus("S-B5H", "Res A HV")
        vlS5L.bus("S-B5L", "Res A LV")
        vlS6H.bus("S-B6H", "Res B HV")
        vlS6L.bus("S-B6L", "Res B LV")
        vlS7H.bus("S-B7H", "Res C HV")
        vlS7L.bus("S-B7L", "Res C LV")
        vlS8H.bus("S-B8H", "Commercial HV")
        vlS8L.bus("S-B8L", "Commercial LV")
        vlS9H.bus("S-B9H", "Solar Gamma HV")
        vlS10.bus("S-B10", "South Hub")

        // South generators
        vlS1H.gen("G-SOLAR-S1", "Solar Alpha", "S-B1H", minP = 0.0, maxP = 120.0, targetP = 105.0)
        vlS2H.gen("G-SOLAR-S2", "Solar Beta", "S-B2H", minP = 0.0, maxP = 100.0, targetP = 88.0)
        vlS3H.gen("G-CCGT-S", "CCGT South", "S-B3H", minP = 80.0, maxP = 350.0, targetP = 295.0, targetQ = 50.0)
        vlS4H.gen("G-GAS-S", "Gas South", "S-B4H", minP = 20.0, maxP = 100.0, targetP = 82.0, targetQ = 8.0)
        vlS9H.gen("G-SOLAR-S3", "Solar Gamma", "S-B9H", minP = 0.0, maxP = 80.0, targetP = 68.0)

        // South loads (LV buses)
        vlS3L.load("LOAD-S-CCGTX", "CCGT South Aux", "S-B3L", p0 = 15.0, q0 = 5.0)
        vlS4L.load("LOAD-S-TOWN", "South Town", "S-B4L", p0 = 50.0, q0 = 15.0)
        vlS5L.load("LOAD-S-RES-A", "Residential A", "S-B5L", p0 = 160.0, q0 = 48.0)
        vlS6L.load("LOAD-S-RES-B", "Residential B", "S-B6L", p0 = 140.0, q0 = 42.0)
        vlS7L.load("LOAD-S-RES-C", "Residential C", "S-B7L", p0 = 120.0, q0 = 36.0)
        vlS8L.load("LOAD-S-COMM", "Commercial Centre", "S-B8L", p0 = 100.0, q0 = 30.0)

        // South step-down transformers
        sS3.stepDown("TX-S3", "CCGT South Step-Down", "S-VL3H", "S-B3H", "S-VL3L", "S-B3L", ratingA = 100.0)
        sS4.stepDown("TX-S4", "Gas South Step-Down", "S-VL4H", "S-B4H", "S-VL4L", "S-B4L", ratingA = 333.4)
        sS5.stepDown("TX-S5", "Res A Step-Down", "S-VL5H", "S-B5H", "S-VL5L", "S-B5L", ratingA = 1126.7)
        sS6.stepDown("TX-S6", "Res B Step-Down", "S-VL6H", "S-B6H", "S-VL6L", "S-B6L", ratingA = 979.0)
        sS7.stepDown("TX-S7", "Res C Step-Down", "S-VL7H", "S-B7H", "S-VL7L", "S-B7L", ratingA = 842.2)
        sS8.stepDown("TX-S8", "Commercial Step-Down", "S-VL8H", "S-B8H", "S-VL8L", "S-B8L", ratingA = 693.3)

        // South 220 kV backbone (9 lines)
        line("LS-1", "Solar Alpha–S Hub", "S-VL1H", "S-B1H", "S-VL10", "S-B10", r = 1.0, x = 10.0)
        line("LS-2", "Solar Beta–S Hub", "S-VL2H", "S-B2H", "S-VL10", "S-B10", r = 1.0, x = 10.0)
        line("LS-3", "CCGT South–Res A", "S-VL3H", "S-B3H", "S-VL5H", "S-B5H", r = 0.5, x = 5.0)
        line("LS-4", "Gas South–S Hub", "S-VL4H", "S-B4H", "S-VL10", "S-B10", r = 0.8, x = 8.0)
        line("LS-5", "Res A–Res B", "S-VL5H", "S-B5H", "S-VL6H", "S-B6H", r = 0.3, x = 3.0)
        line("LS-6", "Res B–S Hub", "S-VL6H", "S-B6H", "S-VL10", "S-B10", r = 0.5, x = 5.0)
        line("LS-7", "Res C–Commercial", "S-VL7H", "S-B7H", "S-VL8H", "S-B8H", r = 0.3, x = 3.0)
        line("LS-8", "Commercial–S Hub", "S-VL8H", "S-B8H", "S-VL10", "S-B10", r = 0.5, x = 5.0)
        line("LS-9", "Solar Gamma–S Hub", "S-VL9H", "S-B9H", "S-VL10", "S-B10", r = 1.0, x = 10.0)

        // =====================================================================
        // INTER-REGION 220 kV TIE LINES (3 lines)
        // =====================================================================

        line("LIE-1", "N Hub–E Hub", "N-VL10", "N-B10", "E-VL9", "E-B9", r = 2.0, x = 20.0, ratingA = 400.0)
        line("LIE-2", "N Hub–S Hub", "N-VL10", "N-B10", "S-VL10", "S-B10", r = 2.0, x = 20.0, ratingA = 400.0)
        line("LIE-3", "E Hub–S Hub", "E-VL9", "E-B9", "S-VL10", "S-B10", r = 2.0, x = 20.0, ratingA = 400.0)

        // =====================================================================
        // EXPANSION SITES (dormant, disconnected until built — #414)
        // =====================================================================
        //
        // Real IIDM topology, seeded from session start, matching
        // docs/engineering/17-grid-expansion.md's "pre-built, dormant
        // topology" design: every element below is built the same way as its
        // energized counterparts above and then explicitly disconnected —
        // the same Terminal.disconnect() call TripLine/TripGenerator already
        // use at runtime (Module 17 Design Decision #1), just applied once at
        // network construction instead of mid-session. freePlay50ExpansionSites()
        // (below) supplies the matching ExpansionSite metadata for these IDs;
        // the two are kept in sync by hand, same as every other hardcoded ID
        // in this preset.
        //
        // Site count/placement is a first pass (Module 17 Open Question #5) —
        // one of each ExpansionSiteKind, near buses/corridors already prone to
        // stress, refined later via playtesting.

        // DOUBLE_LINE: second circuit on the North "Ind B–Hub" corridor (LN-7).
        line("LN-7-DUP", "Ind B–Hub (2nd circuit, dormant)", "N-VL6H", "N-B6H", "N-VL10", "N-B10", r = 0.5, x = 5.0)
        network.getLine("LN-7-DUP")!!.also {
            it.terminal1.disconnect()
            it.terminal2.disconnect()
        }

        // SHUNT_COMPENSATOR: dormant capacitor bank at the East Hub.
        vlE9.newShuntCompensator().setId("SC-EXP-EHUB").setName("East Hub Capacitor Bank (dormant)")
            .setBus("E-B9").setConnectableBus("E-B9")
            .setSectionCount(0)
            .newLinearModel().setBPerSection(0.02).setGPerSection(0.0).setMaximumSectionCount(3).add()
            .add()
        network.getShuntCompensator("SC-EXP-EHUB")!!.terminal.disconnect()

        // GENERATOR + NEW_LINE bundle: a second North gas peaker on its own new
        // bus, reached via a dormant connecting line back to the North Hub.
        val sNExp1 = network.newSubstation().setId("NS-EXP1").setName("Expansion Site: Gas Peaker North-2 (dormant)").add()
        val vlNExp1 = sNExp1.vl("N-VLEXP1H", "Expansion Gas Peaker 220kV", 220.0)
        vlNExp1.bus("N-BEXP1H", "Expansion Gas Peaker HV")
        vlNExp1.gen(
            "G-GAS-N-EXP1",
            "Gas Peaker North-2 (dormant)",
            "N-BEXP1H",
            minP = 20.0,
            maxP = 120.0,
            targetP = 100.0,
            targetQ = 10.0,
        )
        network.getGenerator("G-GAS-N-EXP1")!!.terminal.disconnect()
        line(
            "LN-EXP1",
            "Expansion Gas Peaker–North Hub (dormant)",
            "N-VLEXP1H",
            "N-BEXP1H",
            "N-VL10",
            "N-B10",
            r = 0.6,
            x = 6.0,
        )
        network.getLine("LN-EXP1")!!.also {
            it.terminal1.disconnect()
            it.terminal2.disconnect()
        }

        // SUBSTATION + NEW_LINE bundle: a new injection point south of the South
        // Hub, reached via a dormant connecting line. No generator/load of its
        // own — the design doc describes SUBSTATION sites as "a new injection
        // point" for area-wide congestion relief without specifying further
        // electrical content; kept minimal here (bus + connecting line only)
        // as a first-pass authoring choice, same status as Open Question #5.
        val sSExp1 = network.newSubstation().setId("SS-EXP1").setName("Expansion Site: South Injection Point (dormant)").add()
        val vlSExp1 = sSExp1.vl("S-VLEXP1H", "Expansion South Sub 220kV", 220.0)
        vlSExp1.bus("S-BEXP1H", "Expansion South Sub HV")
        line(
            "LS-EXP1",
            "Expansion South Sub–South Hub (dormant)",
            "S-VLEXP1H",
            "S-BEXP1H",
            "S-VL10",
            "S-B10",
            r = 0.6,
            x = 6.0,
        )
        network.getLine("LS-EXP1")!!.also {
            it.terminal1.disconnect()
            it.terminal2.disconnect()
        }

        return network
    }

    /**
     * [ExpansionSite] metadata for `freeplay50`'s dormant topology (above).
     * One of each [ExpansionSiteKind] — see the "EXPANSION SITES" section of
     * [buildFreePlay50Network] for the matching IIDM elements and rationale.
     */
    private fun freePlay50ExpansionSites(): List<ExpansionSite> =
        listOf(
            ExpansionSite(
                id = "EXP-DOUBLE-LN7",
                kind = ExpansionSiteKind.DOUBLE_LINE,
                anchorBusId = "N-B6H",
                remediesElementId = "LN-7",
                locationHint = LocationHint(x = 0.62, y = 0.30),
            ),
            ExpansionSite(
                id = "EXP-SHUNT-EHUB",
                kind = ExpansionSiteKind.SHUNT_COMPENSATOR,
                anchorBusId = "E-B9",
                remediesElementId = "E-B9",
                locationHint = LocationHint(x = 0.85, y = 0.55),
            ),
            ExpansionSite(
                id = "EXP-NEWLINE-N1",
                kind = ExpansionSiteKind.NEW_LINE,
                anchorBusId = "N-B10",
                remediesElementId = "N-B10",
                locationHint = LocationHint(x = 0.55, y = 0.15),
            ),
            // anchorBusId is this site's OWN dormant bus (where the generator itself
            // sits, connected via EXP-NEWLINE-N1); remediesElementId is the EXISTING
            // bus this generator is meant to relieve once built -- two distinct
            // buses by design (site location vs. remedy target), not a typo.
            ExpansionSite(
                id = "EXP-GEN-N1",
                kind = ExpansionSiteKind.GENERATOR,
                anchorBusId = "N-BEXP1H",
                remediesElementId = "N-B10",
                connectingLineSiteId = "EXP-NEWLINE-N1",
                locationHint = LocationHint(x = 0.50, y = 0.10),
            ),
            ExpansionSite(
                id = "EXP-NEWLINE-S1",
                kind = ExpansionSiteKind.NEW_LINE,
                anchorBusId = "S-B10",
                remediesElementId = "S-B10",
                locationHint = LocationHint(x = 0.45, y = 0.85),
            ),
            ExpansionSite(
                id = "EXP-SUB-S1",
                kind = ExpansionSiteKind.SUBSTATION,
                anchorBusId = "S-BEXP1H",
                remediesElementId = "S-B10",
                connectingLineSiteId = "EXP-NEWLINE-S1",
                locationHint = LocationHint(x = 0.40, y = 0.90),
            ),
        )
}
