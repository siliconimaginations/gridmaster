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
    val network: NetworkStateDto? = null,
    val powerFlowStatus: ConvergenceStatus? = null,
    val violations: List<ViolationDto>? = null,
    /** New alerts generated this tick (append-only on the client). */
    val newAlerts: List<AlertDto>? = null,
    /** Non-null when pending event cards changed (new card arrived or card resolved). */
    val pendingEventCards: List<EventCardDto>? = null,
)

enum class UpdateType { FULL, DELTA }

/**
 * Lightweight network state snapshot for the WebSocket message.
 * Only the fields the frontend HUD and map need — full detail available via REST.
 */
data class NetworkStateDto(
    val totalLoadMw: Double,
    val totalGenerationMw: Double,
    /** System marginal price (£/MWh) from the last dispatch run; null if none. */
    val systemMarginalCostPerMwh: Double?,
)

data class ViolationDto(
    val elementId: String,
    val type: String,
    val severity: String,
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
