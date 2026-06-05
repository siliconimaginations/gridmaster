package com.gridmaster.engine.model

import java.time.Instant

/**
 * Immutable snapshot of the full grid network.
 * Created fresh from the live IIDM Network object after each power flow solve.
 * Shared safely across modules and serialised to JSON for the WebSocket state stream.
 */
data class GridNetwork(
    val id: String,
    val name: String,
    val buses: List<Bus>,
    val lines: List<Line>,
    val twoWindingsTransformers: List<TwoWindingsTransformer>,
    val threeWindingsTransformers: List<ThreeWindingsTransformer>,
    val generators: List<Generator>,
    val loads: List<Load>,
    val shuntCompensators: List<ShuntCompensator>,
    /** Geographic/game-mode annotations on subsets of buses. No topological meaning to the solver. */
    val regions: List<Region> = emptyList(),
    /** Non-fatal mapping warnings accumulated during snapshot creation. */
    val warnings: List<String> = emptyList(),
    val snapshotAt: Instant = Instant.now(),
)

/** A game-mode annotation grouping buses into a named geographic region. */
data class Region(
    val id: String,
    val name: String,
    val busIds: Set<String>,
)

/**
 * A bus (node) in the network. Voltages are null before the first power flow solve.
 * [nominalVoltageKv] is the rated voltage of the voltage level this bus belongs to.
 * [voltageMagnitudePu] and [voltageAngleDeg] are populated after a successful AC load flow.
 */
data class Bus(
    val id: String,
    val name: String,
    val nominalVoltageKv: Double,
    val voltageMagnitudePu: Double? = null,
    val voltageAngleDeg: Double? = null,
    val regionId: String? = null,
)

/**
 * An AC transmission line between two buses.
 * [ratingA] is the continuous thermal current rating; null if not specified in the network file.
 * [currentFromA]/[currentToA] are null before the first power flow solve.
 * Impedance parameters are in Ohms at the network's base voltage.
 * [shuntCapacitanceSiemens] is the total line-charging susceptance (B1 + B2).
 */
data class Line(
    val id: String,
    val name: String,
    val fromBusId: String,
    val toBusId: String,
    val ratingA: Double? = null,
    val currentFromA: Double? = null,
    val currentToA: Double? = null,
    val resistanceOhm: Double,
    val reactanceOhm: Double,
    val shuntCapacitanceSiemens: Double,
)

/**
 * A two-winding transformer between two buses (typically HV side → LV side).
 * [ratingMva] is the rated apparent power; null if not set.
 * [ratioTapPosition] is the current tap changer step (0 if no tap changer fitted).
 */
data class TwoWindingsTransformer(
    val id: String,
    val name: String,
    val fromBusId: String,
    val toBusId: String,
    val ratingMva: Double? = null,
    val currentFromA: Double? = null,
    val currentToA: Double? = null,
    val resistanceOhm: Double,
    val reactanceOhm: Double,
    val ratioTapPosition: Int = 0,
    val nominalVoltageHvKv: Double,
    val nominalVoltageLvKv: Double,
)

/**
 * A three-winding transformer with HV (leg1), MV (leg2), and LV (leg3) windings.
 * Impedance values are per-leg series resistances and reactances in Ohms.
 * Tap position is omitted for now (deferred to dispatch module).
 */
data class ThreeWindingsTransformer(
    val id: String,
    val name: String,
    val bus1Id: String,
    val bus2Id: String,
    val bus3Id: String,
    val ratingMva1: Double? = null,
    val ratingMva2: Double? = null,
    val ratingMva3: Double? = null,
    val current1A: Double? = null,
    val current2A: Double? = null,
    val current3A: Double? = null,
    /** Rated (nominal) voltage of leg 1 in kV. Used for MVA → A conversion in thermal checks. */
    val nominalVoltage1Kv: Double,
    /** Rated (nominal) voltage of leg 2 in kV. */
    val nominalVoltage2Kv: Double,
    /** Rated (nominal) voltage of leg 3 in kV. */
    val nominalVoltage3Kv: Double,
    val resistanceOhm1: Double,
    val reactanceOhm1: Double,
    val resistanceOhm2: Double,
    val reactanceOhm2: Double,
    val resistanceOhm3: Double,
    val reactanceOhm3: Double,
)

/**
 * An active/reactive power source.
 * [targetVoltagePu] is the voltage setpoint at the terminal bus (per unit).
 * [fuelType] and [marginalCostPerMwh] come from sidecar metadata; not present in IIDM.
 */
data class Generator(
    val id: String,
    val name: String,
    val busId: String,
    val minActivePowerMw: Double,
    val maxActivePowerMw: Double,
    val targetActivePowerMw: Double,
    val targetReactivePowerMvar: Double,
    val targetVoltagePu: Double,
    val connected: Boolean,
    val fuelType: FuelType,
    val marginalCostPerMwh: Double,
)

enum class FuelType { COAL, GAS, NUCLEAR, HYDRO, WIND, SOLAR, OIL, OTHER }

/** An active/reactive power demand at a bus (e.g. a city or industrial consumer). */
data class Load(
    val id: String,
    val name: String,
    val busId: String,
    val activePowerMw: Double,
    val reactivePowerMvar: Double,
    val connected: Boolean,
)

/**
 * A shunt element (capacitor bank or reactor) providing reactive compensation.
 * [susceptanceSiemensPerSection] is the susceptance added per section.
 * For capacitors B > 0 (reactive generation); for reactors B < 0 (reactive absorption).
 */
data class ShuntCompensator(
    val id: String,
    val name: String,
    val busId: String,
    val susceptanceSiemensPerSection: Double,
    val maximumSectionCount: Int,
    val currentSectionCount: Int,
    val connected: Boolean,
)
