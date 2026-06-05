package com.gridmaster.engine.powerflow

import com.gridmaster.engine.model.GridNetwork
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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
            val current = maxOfNullable(line.currentFromA, line.currentToA) ?: continue
            thermalViolation(line.id, EquipmentType.LINE, current, rating)
                ?.let { violations += it }
        }

        // Two-winding transformers
        for (twt in snapshot.twoWindingsTransformers) {
            val rating = twt.ratingMva?.let { mvaToAmps(it, twt.nominalVoltageHvKv) } ?: continue
            val current = maxOfNullable(twt.currentFromA, twt.currentToA) ?: continue
            thermalViolation(twt.id, EquipmentType.TWO_WINDINGS_TRANSFORMER, current, rating)
                ?.let { violations += it }
        }

        // Three-winding transformers — check each leg independently
        for (twt3 in snapshot.threeWindingsTransformers) {
            listOf(
                Triple(twt3.ratingMva1, twt3.current1A, "leg1"),
                Triple(twt3.ratingMva2, twt3.current2A, "leg2"),
                Triple(twt3.ratingMva3, twt3.current3A, "leg3"),
            ).forEach { (ratingMva, current, leg) ->
                // Three-winding transformers don't have a nominal voltage on the
                // domain model yet — skip MVA→A conversion until leg voltages are added.
                // TODO: add nominalVoltageKv per leg to ThreeWindingsTransformer (#issue)
                if (ratingMva != null && current != null) {
                    log.debug(
                        "3W transformer ${twt3.id} $leg: rating=${ratingMva}MVA " +
                            "current=${current}A — thermal check skipped (no leg voltage)",
                    )
                }
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

    private fun maxOfNullable(
        a: Double?,
        b: Double?,
    ): Double? =
        when {
            a != null && b != null -> maxOf(a, b)
            a != null -> a
            b != null -> b
            else -> null
        }

    companion object {
        private val SQRT3 = Math.sqrt(3.0)
    }
}
