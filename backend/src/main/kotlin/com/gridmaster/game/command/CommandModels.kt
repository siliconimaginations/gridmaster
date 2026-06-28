package com.gridmaster.game.command

import com.gridmaster.engine.dispatch.DispatchMode
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.powerflow.EquipmentType
import com.gridmaster.engine.powerflow.PowerFlowResult

// ─────────────────────────────────────────────────────────────────────────────
// Player commands
// ─────────────────────────────────────────────────────────────────────────────

/**
 * All state-changing operations a player (or an internal service) can issue.
 *
 * Every command carries its target [sessionId]. Commands are handled by
 * [CommandHandler.handle] or [CommandHandler.handleBatch].
 */
sealed class PlayerCommand {
    abstract val sessionId: String

    // ── Real-time network operations ─────────────────────────────────────────

    /** Set a connected generator's active power output to [targetMw] MW. */
    data class SetGeneratorOutput(
        override val sessionId: String,
        val generatorId: String,
        val targetMw: Double,
    ) : PlayerCommand()

    /** Set a connected generator's terminal voltage setpoint to [targetVoltagePu] pu. */
    data class SetGeneratorVoltage(
        override val sessionId: String,
        val generatorId: String,
        val targetVoltagePu: Double,
    ) : PlayerCommand()

    /** Trip (disconnect) a connected line or generator. */
    data class TripElement(
        override val sessionId: String,
        val elementId: String,
        val elementType: EquipmentType,
    ) : PlayerCommand()

    /** Reconnect a previously disconnected line or generator. */
    data class ConnectElement(
        override val sessionId: String,
        val elementId: String,
        val elementType: EquipmentType,
    ) : PlayerCommand()

    /** Move a transformer's ratio tap changer to [tapPosition]. */
    data class SetTapPosition(
        override val sessionId: String,
        val transformerId: String,
        val tapPosition: Int,
    ) : PlayerCommand()

    /**
     * Shed [fractionToShed] × 100 % of a load's current active power.
     * [fractionToShed] must be in [0.0, 1.0].
     */
    data class ShedLoad(
        override val sessionId: String,
        val loadId: String,
        val fractionToShed: Double,
    ) : PlayerCommand()

    // ── Dispatch operations ──────────────────────────────────────────────────

    /** Run economic dispatch and apply resulting generator targets as mutations. */
    data class RunEconomicDispatch(
        override val sessionId: String,
        val totalLoadMw: Double,
        val mode: DispatchMode = DispatchMode.MERIT_ORDER,
        val securityConstrained: Boolean = false,
    ) : PlayerCommand()

    /** Commit (connect) a generator for the next dispatch solve. */
    data class CommitGenerator(
        override val sessionId: String,
        val generatorId: String,
    ) : PlayerCommand()

    /** Decommit (disconnect) a generator — removes it from the dispatch pool. */
    data class DecommitGenerator(
        override val sessionId: String,
        val generatorId: String,
    ) : PlayerCommand()

    /**
     * Apply a pre-computed unit-commitment schedule.
     * Each [GeneratorSchedule] sets a generator's commitment state and optional output target.
     */
    data class ApplyUcSchedule(
        override val sessionId: String,
        val schedule: List<GeneratorSchedule>,
    ) : PlayerCommand()

    // ── Clock control ────────────────────────────────────────────────────────

    /**
     * Change the simulation speed multiplier. [multiplier] must be in [1, 100].
     * Takes effect on the next tick.
     */
    data class SetClockSpeed(
        override val sessionId: String,
        val multiplier: Int,
    ) : PlayerCommand()

    /** Pause the simulation clock. */
    data class PauseClock(override val sessionId: String) : PlayerCommand()

    /** Resume a paused simulation clock. */
    data class ResumeClock(override val sessionId: String) : PlayerCommand()

    // ── Event card response ──────────────────────────────────────────────────

    /**
     * Respond to a pending [com.gridmaster.game.event.EventCard].
     * [cardId] identifies the card; [optionId] is the string key of the chosen option
     * (e.g. "0", "1") matching [com.gridmaster.api.websocket.EventCardOptionDto.id].
     */
    data class RespondToEventCard(
        override val sessionId: String,
        val cardId: String,
        val optionId: String,
    ) : PlayerCommand()
}

// ─────────────────────────────────────────────────────────────────────────────
// Dispatch schedule (for ApplyUcSchedule)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Per-generator entry in a unit-commitment schedule.
 *
 * @param committed True = connect the generator; false = disconnect it.
 * @param targetMw  Optional output target (MW). Applied only if [committed] is true and non-null.
 */
data class GeneratorSchedule(
    val generatorId: String,
    val committed: Boolean,
    val targetMw: Double? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// Command result
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Unified outcome for both single and batch command handling.
 *
 * - Single command: [commandOutcomes] has exactly one entry.
 * - Batch command: [commandOutcomes] has one entry per command.
 *
 * [success] is false if any command in the batch was rejected. When false,
 * zero mutations were applied and [snapshot] reflects the pre-command state.
 */
data class CommandResult(
    /** False if any command was rejected; no mutations were applied in that case. */
    val success: Boolean,
    /** Network snapshot after mutations (or pre-command state if unsuccessful). */
    val snapshot: GridNetwork,
    /** Power flow result after mutations (or latest cached result if unsuccessful). */
    val powerFlowResult: PowerFlowResult,
    /** Alerts generated from violations detected after this command. */
    val newAlerts: List<Alert>,
    /** One outcome per command; single-command results have exactly one entry. */
    val commandOutcomes: List<CommandOutcome>,
)

/**
 * Outcome for a single [PlayerCommand] within a [CommandResult].
 */
data class CommandOutcome(
    /** Discriminator matching [PlayerCommand] subclass name (e.g. "SetGeneratorOutput"). */
    val commandType: String,
    val success: Boolean,
    /** Non-null when [success] is false; human-readable reason for rejection. */
    val rejectionReason: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// Alerts
// ─────────────────────────────────────────────────────────────────────────────

/**
 * An in-game alert surfaced to the player when a post-command physics check detects
 * a network anomaly. Derived from [com.gridmaster.engine.powerflow.NetworkViolation]s
 * produced by the power flow solver.
 */
sealed class Alert {
    abstract val severity: AlertSeverity
    abstract val elementId: String
    abstract val message: String

    /** Voltage out of range on a bus. */
    data class VoltageAlert(
        override val severity: AlertSeverity,
        override val elementId: String,
        val voltagePu: Double,
        val limitMinPu: Double,
        val limitMaxPu: Double,
    ) : Alert() {
        override val message: String
            get() =
                "Bus $elementId voltage ${String.format("%.3f", voltagePu)} pu " +
                    "(limit ${String.format("%.3f", limitMinPu)}–${String.format("%.3f", limitMaxPu)} pu)"
    }

    /** Thermal overload on a line or transformer. */
    data class ThermalAlert(
        override val severity: AlertSeverity,
        override val elementId: String,
        val loadingPercent: Double,
        val equipmentType: EquipmentType,
    ) : Alert() {
        override val message: String
            get() = "${equipmentType.name} $elementId loaded at ${String.format("%.1f", loadingPercent)} %"
    }

    /** Power flow did not converge — grid topology is potentially unstable. */
    data class ConvergenceAlert(
        override val elementId: String = "system",
    ) : Alert() {
        override val severity: AlertSeverity = AlertSeverity.CRITICAL
        override val message: String = "Power flow did not converge — grid may be unstable"
    }
}

enum class AlertSeverity { INFO, WARNING, CRITICAL }
