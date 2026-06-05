package com.gridmaster.engine.powerflow

import com.gridmaster.engine.model.GridNetwork

/**
 * Parameters for a single power flow solve.
 * Defaults represent normal game-tick AC operation with distributed slack.
 */
data class PowerFlowParameters(
    val mode: SolveMode = SolveMode.AC,
    /** Distribute active power imbalance across participating generators. */
    val distributedSlack: Boolean = true,
    val balanceType: BalanceType = BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX,
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

enum class EquipmentType { LINE, TWO_WINDINGS_TRANSFORMER, THREE_WINDINGS_TRANSFORMER }

/**
 * Severity of a network violation.
 * Thresholds configurable in application.yml under gridmaster.violations.*
 * Defaults: WARNING ≥ 90 %, ALARM ≥ 100 %, CRITICAL ≥ 110 %.
 */
enum class ViolationSeverity { WARNING, ALARM, CRITICAL }

/** Configurable thresholds for violation severity classification. */
data class ViolationThresholds(
    val warningPercent: Double = 90.0,
    val alarmPercent: Double = 100.0,
    val criticalPercent: Double = 110.0,
    val voltageMinPu: Double = 0.95,
    val voltageMaxPu: Double = 1.05,
) {
    fun thermalSeverity(loadingPercent: Double): ViolationSeverity? =
        when {
            loadingPercent >= criticalPercent -> ViolationSeverity.CRITICAL
            loadingPercent >= alarmPercent -> ViolationSeverity.ALARM
            loadingPercent >= warningPercent -> ViolationSeverity.WARNING
            else -> null
        }

    fun voltageSeverity(voltagePu: Double): ViolationSeverity? =
        when {
            voltagePu < voltageMinPu || voltagePu > voltageMaxPu -> ViolationSeverity.ALARM
            else -> null
        }
}
