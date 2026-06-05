package com.gridmaster.engine.powerflow

import com.gridmaster.engine.model.GridNetwork
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.sqrt

/**
 * Scans a [GridNetwork] snapshot for voltage and thermal violations.
 * Called after every successful power flow solve.
 *
 * Voltage limits are taken from [ViolationThresholds]; a future extension
 * will allow per-voltage-level overrides via sidecar metadata.
 */
@Component
class ViolationScanner(
    private val thresholds: ViolationThresholds = ViolationThresholds(),
) {
    private val log = LoggerFactory.getLogger(ViolationScanner::class.java)

    fun scan(snapshot: GridNetwork): List<NetworkViolation> {
        val violations = mutableListOf<NetworkViolation>()
        violations += scanVoltage(snapshot)
        violations += scanThermal(snapshot)
        return violations
    }

    // -------------------------------------------------------------------------
    // Voltage
    // -------------------------------------------------------------------------

    private fun scanVoltage(snapshot: GridNetwork): List<NetworkViolation> {
        val violations = mutableListOf<NetworkViolation>()
        for (bus in snapshot.buses) {
            val vPu = bus.voltageMagnitudePu ?: continue // null before first solve
            val severity = thresholds.voltageSeverity(vPu) ?: continue
            violations +=
                NetworkViolation.VoltageViolation(
                    busId = bus.id,
                    voltagePu = vPu,
                    limitMinPu = thresholds.voltageMinPu,
                    limitMaxPu = thresholds.voltageMaxPu,
                    severity = severity,
                )
        }
        return violations
    }

    // -------------------------------------------------------------------------
    // Thermal
    // -------------------------------------------------------------------------

    private fun scanThermal(snapshot: GridNetwork): List<NetworkViolation> {
        val violations = mutableListOf<NetworkViolation>()

        // Lines
        for (line in snapshot.lines) {
            val rating = line.ratingA ?: continue
            val current = listOfNotNull(line.currentFromA, line.currentToA).maxOrNull() ?: continue
            thermalViolation(line.id, EquipmentType.LINE, current, rating)
                ?.let { violations += it }
        }

        // Two-winding transformers
        // Convert the MVA rating to an equivalent current rating on each side using that
        // side's own nominal voltage, then compare each side's measured current against its
        // own rating.  Using a single HV-side conversion for both currents is incorrect
        // because LV-side current is naturally higher for the same power flow.
        // O(n_buses) map built once per scan — negligible for game-scale networks.
        val busNominalKv = snapshot.buses.associate { it.id to it.nominalVoltageKv }
        for (twt in snapshot.twoWindingsTransformers) {
            val ratingMva = twt.ratingMva ?: continue
            val fromVkv =
                busNominalKv[twt.fromBusId].also {
                    if (it == null) log.warn("Bus {} not found for 2W transformer {}; skipping thermal check", twt.fromBusId, twt.id)
                } ?: continue
            val toVkv =
                busNominalKv[twt.toBusId].also {
                    if (it == null) log.warn("Bus {} not found for 2W transformer {}; skipping thermal check", twt.toBusId, twt.id)
                } ?: continue
            val ratingFromA = mvaToAmps(ratingMva, fromVkv)
            val ratingToA = mvaToAmps(ratingMva, toVkv)
            val fromViolation =
                twt.currentFromA?.let {
                    thermalViolation(twt.id, EquipmentType.TWO_WINDINGS_TRANSFORMER, it, ratingFromA)
                }
            val toViolation =
                twt.currentToA?.let {
                    thermalViolation(twt.id, EquipmentType.TWO_WINDINGS_TRANSFORMER, it, ratingToA)
                }
            listOfNotNull(fromViolation, toViolation)
                .maxByOrNull { it.loadingPercent }
                ?.let { violations += it }
        }

        // Three-winding transformers — check each leg against its own per-leg MVA rating.
        // nominalVoltageXKv is populated from PowSyBl leg.ratedU in the mapper.
        for (twt3 in snapshot.threeWindingsTransformers) {
            listOf(
                Triple(twt3.ratingMva1, twt3.current1A, twt3.nominalVoltage1Kv),
                Triple(twt3.ratingMva2, twt3.current2A, twt3.nominalVoltage2Kv),
                Triple(twt3.ratingMva3, twt3.current3A, twt3.nominalVoltage3Kv),
            ).forEach { (ratingMva, currentA, voltageKv) ->
                val rating = ratingMva?.let { mvaToAmps(it, voltageKv) } ?: return@forEach
                val current = currentA ?: return@forEach
                thermalViolation(twt3.id, EquipmentType.THREE_WINDINGS_TRANSFORMER, current, rating)
                    ?.let { violations += it }
            }
        }

        return violations
    }

    private fun thermalViolation(
        id: String,
        type: EquipmentType,
        currentA: Double,
        ratingA: Double,
    ): NetworkViolation.ThermalViolation? {
        if (ratingA <= 0.0) return null
        val loadingPercent = currentA / ratingA * 100.0
        val severity = thresholds.thermalSeverity(loadingPercent) ?: return null
        return NetworkViolation.ThermalViolation(
            equipmentId = id,
            equipmentType = type,
            currentA = currentA,
            ratingA = ratingA,
            loadingPercent = loadingPercent,
            severity = severity,
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** S(MVA) → I(A) at given voltage: I = S × 1000 / (√3 × V_kV) */
    private fun mvaToAmps(
        mva: Double,
        voltageKv: Double,
    ): Double = if (voltageKv > 0.0) mva * 1000.0 / (SQRT3 * voltageKv) else 0.0

    companion object {
        private val SQRT3 = sqrt(3.0)
    }
}
