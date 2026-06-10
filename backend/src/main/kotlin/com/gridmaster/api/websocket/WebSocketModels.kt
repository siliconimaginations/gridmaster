package com.gridmaster.api.websocket

import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.game.ClockState
import com.gridmaster.game.command.Alert

// ─────────────────────────────────────────────────────────────────────────────
// Server → Client
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Published to `/topic/session/{sessionId}/state` after each tick and after
 * any command that changes network state.
 *
 * [type] = [UpdateType.FULL] on connection/reconnect and every
 * [com.gridmaster.api.websocket.GameStatePublisher.FULL_STATE_INTERVAL_TICKS] ticks.
 * [type] = [UpdateType.DELTA] otherwise — only fields that changed are populated.
 */
data class GameStateUpdate(
    val type: UpdateType,
    val sessionId: String,
    val tickNumber: Long,
    val gameTimeMinutes: Long,
    val clockState: ClockState,
    val clockSpeedMultiplier: Int,
    /** Present on FULL; present on DELTA only if network state changed. */
    val network: GridNetworkWsDto? = null,
    val powerFlowStatus: ConvergenceStatus? = null,
    val violations: List<ViolationDto>? = null,
    /** Alerts generated this tick (append-only on the client). */
    val alerts: List<AlertDto>? = null,
    /** Non-null when pending event cards changed (new card arrived or card resolved). */
    val pendingEventCards: List<EventCardDto>? = null,
)

enum class UpdateType { FULL, DELTA }

/**
 * Full network snapshot sent over WebSocket each tick (or when changed on DELTA).
 * Field names are intentionally aligned with the frontend [GridNetworkDto] interface.
 */
data class GridNetworkWsDto(
    val buses: List<BusWsDto>,
    val branches: List<BranchWsDto>,
    val generators: List<GeneratorWsDto>,
    val loads: List<LoadWsDto>,
    /** Aggregate totals — pre-computed so the HUD doesn't have to sum arrays. */
    val totalLoadMw: Double,
    val totalGenerationMw: Double,
    val systemMarginalCostPerMwh: Double?,
)

data class BusWsDto(
    val id: String,
    val name: String,
    /** Corresponds to the game region; null for buses not assigned to a region. */
    val substationId: String? = null,
    /** Nominal voltage of the bus's voltage level (kV). */
    val voltageKv: Double,
    /** Per-unit voltage magnitude; 1.0 before first power flow. */
    val voltagePu: Double,
    /** Voltage angle in radians; 0.0 before first power flow. */
    val angleRad: Double,
)

data class BranchWsDto(
    val id: String,
    val fromBusId: String,
    val toBusId: String,
    /** Active power entering from the from-terminal (MW); 0.0 before first power flow. */
    val activePowerMw: Double,
    /** Reactive power entering from the from-terminal (Mvar); 0.0 before first power flow. */
    val reactivePowerMvar: Double,
    /** Current loading as a percentage of the thermal rating; 0.0 if no rating is set. */
    val loadingPercent: Double,
    val connected: Boolean,
)

data class GeneratorWsDto(
    val id: String,
    val busId: String,
    val name: String,
    /** Active power setpoint (MW). */
    val activePowerMw: Double,
    val maxActivePowerMw: Double,
    /** True when the generator terminal is connected (committed to the grid). */
    val committed: Boolean,
    val fuelType: String,
)

data class LoadWsDto(
    val id: String,
    val busId: String,
    val name: String,
    val activePowerMw: Double,
    val reactivePowerMvar: Double,
)

data class ViolationDto(
    val elementId: String,
    /** "LINE", "TRANSFORMER", or "BUS". */
    val elementType: String,
    /** "OVERLOAD", "VOLTAGE_HIGH", or "VOLTAGE_LOW". */
    val violationType: String,
    val value: Double,
    val limit: Double,
)

data class AlertDto(
    val severity: String,
    val elementId: String,
    val message: String,
) {
    companion object {
        fun from(alert: Alert): AlertDto =
            AlertDto(
                severity = alert.severity.name,
                elementId = alert.elementId,
                message = alert.message,
            )
    }
}

data class EventCardDto(
    val cardId: String,
    val prompt: String,
    val options: List<EventCardOptionDto>,
)

data class EventCardOptionDto(
    val index: Int,
    val label: String,
    val costGbp: Double,
)

// ─────────────────────────────────────────────────────────────────────────────
// Client → Server
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Envelope sent by the client to `/app/session/{sessionId}/command`.
 *
 * The server maps [commandType] + [payload] to the appropriate [com.gridmaster.game.command.PlayerCommand]
 * subclass and routes to [com.gridmaster.game.command.CommandHandler].
 *
 * Examples:
 * ```json
 * { "commandType": "SetGeneratorOutput", "payload": { "generatorId": "G1", "targetMw": 200 } }
 * { "commandType": "PauseClock",         "payload": {} }
 * ```
 */
data class PlayerCommandMessage(
    val commandType: String,
    val payload: Map<String, Any?>,
)

// ─────────────────────────────────────────────────────────────────────────────
// Server → Client (command acknowledgement)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Sent to `/user/queue/session/{sessionId}/ack` after a command is processed.
 * Delivered only to the commanding client (not broadcast).
 */
data class CommandAck(
    val commandType: String,
    val success: Boolean,
    val rejectionReason: String? = null,
    val appliedAtTick: Long,
)

// ─────────────────────────────────────────────────────────────────────────────
// Server → Client (connection lifecycle)
// ─────────────────────────────────────────────────────────────────────────────

data class ConnectionStatus(
    val type: ConnectionStatusType,
    val sessionId: String? = null,
    /** Number of ticks the client missed during disconnection (RECONNECTED only). */
    val missedTicks: Long? = null,
)

enum class ConnectionStatusType {
    CONNECTED,
    RECONNECTED,
    SESSION_NOT_FOUND,
    AUTH_FAILED,
}
