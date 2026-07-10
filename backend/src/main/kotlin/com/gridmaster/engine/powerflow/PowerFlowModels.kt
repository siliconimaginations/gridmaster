package com.gridmaster.engine.powerflow

import com.gridmaster.engine.model.GridNetwork

/** Square root of 3; used for MVA to A conversion: I = S * 1000 / (SQRT3 * V_kV). */
const val SQRT3 = 1.7320508075688772

/**
 * Parameters for a single power flow solve.
 * Defaults represent normal game-tick AC operation with distributed slack.
 *
 * [balanceType] defaults to [BalanceType.PROPORTIONAL_TO_LOAD] rather than
 * [BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX] (#397): the latter fails to
 * converge on the ieee14 preset's `L1-2-1` N-1 outage - empirically verified
 * with the real OpenLoadFlow solver (NR reaches MAX_ITERATION_REACHED under
 * P_MAX; PROPORTIONAL_TO_LOAD converges in 17 iterations for the same case
 * and does not regress the no-outage baseline).
 */
data class PowerFlowParameters(
    val mode: SolveMode = SolveMode.AC,
    /** Distribute active power imbalance across participating generators. */
    val distributedSlack: Boolean = true,
    val balanceType: BalanceType = BalanceType.PROPORTIONAL_TO_LOAD,
)

enum class SolveMode { AC, DC }

enum class BalanceType {
    PROPORTIONAL_TO_GENERATION_P_MAX,
    PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN,
    PROPORTIONAL_TO_LOAD,
}

/**
 * Full result of one power flow solve.
 *
 * [snapshot] — updated [GridNetwork] with bus voltages and branch currents populated.
 * [violations] — voltage and thermal violations detected after the solve.
 * [slackBusIds] — the buses that absorbed the active power imbalance.
 * [solveTimeMs] — wall-clock time; used by the game clock for tick budget monitoring.
 */
data class PowerFlowResult(
    val status: ConvergenceStatus,
    val solveMode: SolveMode,
    val iterationCount: Int,
    val snapshot: GridNetwork,
    val slackBusIds: List<String>,
    val violations: List<NetworkViolation>,
    val solveTimeMs: Long,
)

enum class ConvergenceStatus {
    /** All connected components converged. */
    CONVERGED,

    /** Some components converged; others are islanded or failed. */
    PARTIAL,

    /**
     * AC solve did not converge — treated as a grid failure event.
     * DC is NOT used as an automatic fallback (see design doc §1).
     */
    NETWORK_FAILURE,

    /** PowSyBl threw an unexpected exception; state is undefined. */
    FAILED,
}

// ---------------------------------------------------------------------------
// Violations
// ---------------------------------------------------------------------------

/** A network operating limit exceeded after a power flow solve. */
sealed class NetworkViolation {
    /**
     * Bus voltage outside the operational limits [limitMinPu, limitMaxPu].
     */
    data class VoltageViolation(
        val busId: String,
        val voltagePu: Double,
        val limitMinPu: Double,
        val limitMaxPu: Double,
        val severity: ViolationSeverity,
    ) : NetworkViolation()

    /**
     * Branch current exceeding its thermal rating.
     * [loadingPercent] = currentA / ratingA × 100.
     */
    data class ThermalViolation(
        val equipmentId: String,
        val equipmentType: EquipmentType,
        val currentA: Double,
        val ratingA: Double,
        val loadingPercent: Double,
        val severity: ViolationSeverity,
    ) : NetworkViolation()
}

enum class EquipmentType { LINE, TWO_WINDINGS_TRANSFORMER, THREE_WINDINGS_TRANSFORMER, BUS }

/**
 * Severity of a network violation.
 * Thresholds configurable in application.yml under gridmaster.violations.*
 * Defaults: WARNING ≥ 90 %, ALARM ≥ 100 %, CRITICAL ≥ 110 %.
 */
enum class ViolationSeverity { WARNING, ALARM, CRITICAL }

/**
 * Configurable thresholds for violation severity classification.
 *
 * Thermal (branch current):
 *   WARNING ≥ [warningPercent] %, ALARM ≥ [alarmPercent] %, CRITICAL ≥ [criticalPercent] %.
 *
 * Voltage (bus voltage in pu):
 *   WARNING: deviation beyond [voltageMinPu]/[voltageMaxPu] up to [voltageAlarmBandPu].
 *   ALARM:   deviation beyond warning band up to [voltageCriticalBandPu].
 *   CRITICAL: deviation beyond critical band.
 *
 * Default voltage bands (from the warning edge outward):
 *   WARNING  0.95–0.92 pu (low) / 1.05–1.08 pu (high)
 *   ALARM    0.92–0.90 pu       / 1.08–1.10 pu
 *   CRITICAL < 0.90 pu          / > 1.10 pu
 */
data class ViolationThresholds(
    val warningPercent: Double = 90.0,
    val alarmPercent: Double = 100.0,
    val criticalPercent: Double = 110.0,
    val voltageMinPu: Double = 0.95,
    val voltageMaxPu: Double = 1.05,
    /** Deviation from the warning edge at which severity escalates to ALARM. */
    val voltageAlarmBandPu: Double = 0.03,
    /** Deviation from the warning edge at which severity escalates to CRITICAL. */
    val voltageCriticalBandPu: Double = 0.05,
) {
    fun thermalSeverity(loadingPercent: Double): ViolationSeverity? =
        when {
            loadingPercent >= criticalPercent -> ViolationSeverity.CRITICAL
            loadingPercent >= alarmPercent -> ViolationSeverity.ALARM
            loadingPercent >= warningPercent -> ViolationSeverity.WARNING
            else -> null
        }

    /**
     * Returns the voltage violation severity based on how far [voltagePu] is outside the
     * normal band ([voltageMinPu]..[voltageMaxPu]).  Returns null when within limits.
     */
    fun voltageSeverity(voltagePu: Double): ViolationSeverity? {
        val deviation =
            when {
                voltagePu < voltageMinPu -> voltageMinPu - voltagePu
                voltagePu > voltageMaxPu -> voltagePu - voltageMaxPu
                else -> return null
            }
        return when {
            deviation >= voltageCriticalBandPu -> ViolationSeverity.CRITICAL
            deviation >= voltageAlarmBandPu -> ViolationSeverity.ALARM
            else -> ViolationSeverity.WARNING
        }
    }
}
