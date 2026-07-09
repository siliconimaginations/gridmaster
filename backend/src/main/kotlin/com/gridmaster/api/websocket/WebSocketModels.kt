package com.gridmaster.api.websocket

import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.Line
import com.gridmaster.engine.model.TwoWindingsTransformer
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.SQRT3
import com.gridmaster.game.ClockState
import com.gridmaster.game.command.Alert
import java.util.UUID
import kotlin.math.PI

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
    /** Server-computed 0-100 grid health score for this tick. */
    val healthScore: Int? = null,
    /**
     * Current tutorial step number (1–5). Non-null only for TUTORIAL-mode sessions.
     * Null for FREE_PLAY and CHALLENGE sessions.
     */
    val tutorialStep: Int? = null,
    /**
     * Game-minutes remaining until the challenge deadline. Non-null only for
     * CHALLENGE-mode sessions; clamped to 0 once the deadline passes.
     */
    val challengeTimeRemainingMinutes: Int? = null,
    /**
     * Current daily-load-curve multiplier for [gameTimeMinutes] (issue #383),
     * where 1.0 is the network's flat baseline load. Always populated — this
     * reflects the time-of-day shape regardless of whether the tick engine's
     * gridmaster.daily-load-curve.enabled flag is actively scaling loads.
     */
    val dailyLoadMultiplier: Double? = null,
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
    /**
     * Locational marginal price (£/MWh) at this bus (#377). Always null for
     * now: the current dispatch LP (see LpDispatchService/MeritOrderAlgorithm)
     * uses a single system-wide power-balance constraint with no per-bus
     * nodal formulation, so there is no dual value or congestion-aware price
     * to report per bus yet — [systemMarginalCostPerMwh] on
     * [GridNetworkWsDto] remains the only real price signal available.
     * Deliberate placeholder per issue #377 ("if this is not ready ...
     * leave a placeholder") pending a genuine nodal/DC-OPF dispatch model.
     */
    val lmpPerMwh: Double? = null,
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
    /**
     * Actual active power output (MW), read back from the last power-flow solve.
     * Falls back to [setpointMw] before the first solve. This is the value production
     * cost must be calculated from — never [setpointMw] (issue #382).
     */
    val activePowerMw: Double,
    /**
     * Player/algorithm-settable active power setpoint (MW). Not settable for WIND/SOLAR
     * generators — see [dispatchable].
     */
    val setpointMw: Double,
    val maxActivePowerMw: Double,
    /** True when the generator terminal is connected (committed to the grid). */
    val committed: Boolean,
    val fuelType: String,
    /** £/MWh — from [com.gridmaster.engine.network.GeneratorMetadata] (issue #336). */
    val marginalCostPerMwh: Double,
    /** False for WIND/SOLAR — the setpoint control should be disabled in the UI (issue #382). */
    val dispatchable: Boolean,
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
    val id: String,
    val severity: String,
    val elementId: String,
    val message: String,
    val timestampMs: Long,
) {
    companion object {
        fun from(alert: Alert): AlertDto =
            AlertDto(
                id = UUID.randomUUID().toString(),
                severity = alert.severity.name,
                elementId = alert.elementId,
                message = alert.message,
                timestampMs = System.currentTimeMillis(),
            )
    }
}

data class EventCardDto(
    val id: String,
    val title: String,
    val description: String,
    val severity: String,
    val options: List<EventCardOptionDto>,
)

data class EventCardOptionDto(
    /** String key matching [com.gridmaster.game.event.CardOption] position (e.g. "0", "1"). */
    val id: String,
    val label: String,
    /** Short category tag displayed in the option chip (empty when not applicable). */
    val tag: String,
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
    /** Non-null when type == GAME_OVER. */
    val gameOver: GameOverDto? = null,
)

enum class ConnectionStatusType {
    CONNECTED,
    RECONNECTED,
    SESSION_NOT_FOUND,
    AUTH_FAILED,
    GAME_OVER,
}

/**
 * Payload carried in a [ConnectionStatus] with type [ConnectionStatusType.GAME_OVER].
 *
 * @param finalHealthScore   The last recorded per-tick health (0-100).
 * @param gridTimeManagedMinutes  Total simulated grid-minutes the session ran.
 * @param averageHealthScore Rolling average health over the session (0-100).
 * @param eventsHandledCount Number of event cards resolved by the player.
 */
data class GameOverDto(
    val finalHealthScore: Int,
    val gridTimeManagedMinutes: Long,
    val averageHealthScore: Int,
    val eventsHandledCount: Int,
    /** True when the player met the challenge victory condition; false for defeat. */
    val won: Boolean = false,
)

// ─────────────────────────────────────────────────────────────────────────────
// Domain → DTO mapper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Map a domain [GridNetwork] snapshot to [GridNetworkWsDto].
 *
 * Used by both the WebSocket tick publisher ([GameStatePublisherImpl]) and the REST
 * `GET /network` endpoint so that clients always receive the same field names
 * (`activePowerMw`, `committed`, etc.) regardless of transport.
 *
 * ### Performance (issue #247)
 * The conversion performs a single O(N) pass over all network elements — buses, lines,
 * two-windings transformers, generators, and loads — with no nested iteration.
 * At the current scale of ~50 buses this step contributes < 1 % of the tick wall-clock
 * time; the dominant cost is the PowSyBl power-flow solve.
 *
 * Because [GridNetwork.snapshotAt] is set to `Instant.now()` on every new snapshot,
 * reference-equality and data-class `hashCode` caching across ticks are ineffective.
 * No memoization is added at this scale.  If the network grows beyond ~200 buses,
 * consider caching the DTO keyed on a monotonic network-mutation counter instead.
 *
 * @param smc System marginal cost (£/MWh); null when no dispatch has run yet.
 */
fun GridNetwork.toNetworkWsDto(smc: Double? = null): GridNetworkWsDto {
    val totalLoad = loads.filter { it.connected }.sumOf { it.activePowerMw }
    val totalGen = generators.filter { it.connected }.sumOf { it.powerOutputMw ?: it.powerSetpointMw }

    val buses =
        buses.map { bus ->
            BusWsDto(
                id = bus.id,
                name = bus.name,
                substationId = bus.regionId,
                voltageKv = bus.nominalVoltageKv,
                voltagePu = bus.voltageMagnitudePu ?: 1.0,
                angleRad = bus.voltageAngleDeg?.let { it * PI / 180.0 } ?: 0.0,
            )
        }

    val branches: List<BranchWsDto> =
        lines.map { line ->
            BranchWsDto(
                id = line.id,
                fromBusId = line.fromBusId,
                toBusId = line.toBusId,
                activePowerMw = line.activePowerFromMw ?: 0.0,
                reactivePowerMvar = line.reactivePowerFromMvar ?: 0.0,
                loadingPercent = line.loadingPercent(),
                connected = line.connected,
            )
        } +
            twoWindingsTransformers.map { twt ->
                BranchWsDto(
                    id = twt.id,
                    fromBusId = twt.fromBusId,
                    toBusId = twt.toBusId,
                    activePowerMw = twt.activePowerFromMw ?: 0.0,
                    reactivePowerMvar = twt.reactivePowerFromMvar ?: 0.0,
                    loadingPercent = twt.loadingPercent(),
                    connected = twt.connected,
                )
            }

    val gens =
        generators.map { gen ->
            GeneratorWsDto(
                id = gen.id,
                busId = gen.busId,
                name = gen.name,
                activePowerMw = gen.powerOutputMw ?: gen.powerSetpointMw,
                setpointMw = gen.powerSetpointMw,
                maxActivePowerMw = gen.maxActivePowerMw,
                committed = gen.connected,
                fuelType = gen.fuelType.name,
                marginalCostPerMwh = gen.marginalCostPerMwh,
                dispatchable = gen.dispatchable,
            )
        }

    val loadDtos =
        loads.map { load ->
            LoadWsDto(
                id = load.id,
                busId = load.busId,
                name = load.name,
                activePowerMw = load.activePowerMw,
                reactivePowerMvar = load.reactivePowerMvar,
            )
        }

    return GridNetworkWsDto(
        buses = buses,
        branches = branches,
        generators = gens,
        loads = loadDtos,
        totalLoadMw = totalLoad,
        totalGenerationMw = totalGen,
        systemMarginalCostPerMwh = smc,
    )
}

private fun Line.loadingPercent(): Double {
    val rating = ratingA ?: return 0.0
    if (rating <= 0.0) return 0.0
    val maxCurrent = maxOf(currentFromA ?: 0.0, currentToA ?: 0.0)
    return maxCurrent / rating * 100.0
}

private fun TwoWindingsTransformer.loadingPercent(): Double {
    val ratingA =
        ratingMva?.let { mva ->
            if (nominalVoltageFromKv > 0.0) mva * 1000.0 / (SQRT3 * nominalVoltageFromKv) else null
        } ?: return 0.0
    if (ratingA <= 0.0) return 0.0
    val maxCurrent = maxOf(currentFromA ?: 0.0, currentToA ?: 0.0)
    return maxCurrent / ratingA * 100.0
}
