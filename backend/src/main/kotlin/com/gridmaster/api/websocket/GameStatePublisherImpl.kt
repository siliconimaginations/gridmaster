package com.gridmaster.api.websocket

import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.engine.model.Line
import com.gridmaster.engine.model.TwoWindingsTransformer
import com.gridmaster.engine.powerflow.EquipmentType
import com.gridmaster.engine.powerflow.NetworkViolation
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.game.ClockState
import com.gridmaster.game.command.Alert
import com.gridmaster.game.event.EventCard
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Default [GameStatePublisher] implementation.
 *
 * ### FULL vs DELTA
 * A FULL [GameStateUpdate] is sent:
 * - On connect / reconnect (via [publishFull])
 * - Every [FULL_STATE_INTERVAL_TICKS] ticks
 *
 * A DELTA [GameStateUpdate] is sent on all other ticks — only fields whose
 * hashes changed since the last broadcast are populated.
 *
 * ### Destinations
 * - `/topic/session/{sessionId}/state` — broadcast to all subscribers
 */
@Component
class GameStatePublisherImpl(
    private val messagingTemplate: SimpMessagingTemplate,
    private val sessionStore: PhysicsSessionStore,
) : GameStatePublisher {
    private val log = LoggerFactory.getLogger(GameStatePublisherImpl::class.java)

    /** Tracks delta state per session so we can compute what changed. */
    private val sessionState = ConcurrentHashMap<String, SessionPublishState>()

    override fun publishTick(
        sessionId: String,
        tickNumber: Long,
        gameTimeMinutes: Long,
        clockState: ClockState,
        clockSpeedMultiplier: Int,
        powerFlowResult: PowerFlowResult,
        newAlerts: List<Alert>,
        pendingCards: List<EventCard>,
    ) {
        val isFull = tickNumber % GameStatePublisher.FULL_STATE_INTERVAL_TICKS == 0L
        val state = sessionState.getOrPut(sessionId) { SessionPublishState() }

        if (isFull) {
            doPublishFull(
                sessionId,
                tickNumber,
                gameTimeMinutes,
                clockState,
                clockSpeedMultiplier,
                powerFlowResult,
                newAlerts,
                pendingCards,
            )
        } else {
            doPublishDelta(
                state,
                sessionId,
                tickNumber,
                gameTimeMinutes,
                clockState,
                clockSpeedMultiplier,
                powerFlowResult,
                newAlerts,
                pendingCards,
            )
        }
    }

    override fun publishFull(
        sessionId: String,
        tickNumber: Long,
        gameTimeMinutes: Long,
        clockState: ClockState,
        clockSpeedMultiplier: Int,
        powerFlowResult: PowerFlowResult,
        newAlerts: List<Alert>,
        pendingCards: List<EventCard>,
        missedTicks: Long?,
    ) {
        if (missedTicks != null) {
            val dest = "/topic/session/$sessionId/state"
            messagingTemplate.convertAndSend(
                dest,
                ConnectionStatus(
                    type = ConnectionStatusType.RECONNECTED,
                    sessionId = sessionId,
                    missedTicks = missedTicks,
                ),
            )
        }
        doPublishFull(sessionId, tickNumber, gameTimeMinutes, clockState, clockSpeedMultiplier, powerFlowResult, newAlerts, pendingCards)
    }

    override fun clearSession(sessionId: String) {
        sessionState.remove(sessionId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun doPublishFull(
        sessionId: String,
        tickNumber: Long,
        gameTimeMinutes: Long,
        clockState: ClockState,
        clockSpeedMultiplier: Int,
        powerFlowResult: PowerFlowResult,
        newAlerts: List<Alert>,
        pendingCards: List<EventCard>,
    ) {
        val networkDto = buildNetworkDto(sessionId, powerFlowResult)
        val update =
            GameStateUpdate(
                type = UpdateType.FULL,
                sessionId = sessionId,
                tickNumber = tickNumber,
                gameTimeMinutes = gameTimeMinutes,
                clockState = clockState,
                clockSpeedMultiplier = clockSpeedMultiplier,
                network = networkDto,
                powerFlowStatus = powerFlowResult.status,
                violations = powerFlowResult.violations.map { it.toDto() },
                alerts = newAlerts.map { AlertDto.from(it) },
                pendingEventCards = pendingCards.map { it.toDto() },
            )

        val state = sessionState.getOrPut(sessionId) { SessionPublishState() }
        state.lastNetworkHash = networkDto.hashCode()
        state.lastViolationsHash = update.violations.hashCode()
        state.lastPendingCardsHash = update.pendingEventCards.hashCode()

        broadcast(sessionId, update)
    }

    private fun doPublishDelta(
        state: SessionPublishState,
        sessionId: String,
        tickNumber: Long,
        gameTimeMinutes: Long,
        clockState: ClockState,
        clockSpeedMultiplier: Int,
        powerFlowResult: PowerFlowResult,
        newAlerts: List<Alert>,
        pendingCards: List<EventCard>,
    ) {
        val networkDto = buildNetworkDto(sessionId, powerFlowResult)
        val violations = powerFlowResult.violations.map { it.toDto() }
        val cards = pendingCards.map { it.toDto() }

        val networkChanged = networkDto.hashCode() != state.lastNetworkHash
        val violationsChanged = violations.hashCode() != state.lastViolationsHash
        val cardsChanged = cards.hashCode() != state.lastPendingCardsHash

        // Always send alerts if any fired this tick
        val alertsToSend = newAlerts.map { AlertDto.from(it) }.takeIf { it.isNotEmpty() }

        // Skip broadcast entirely if nothing changed and no alerts
        if (!networkChanged && !violationsChanged && !cardsChanged && alertsToSend == null) return

        val update =
            GameStateUpdate(
                type = UpdateType.DELTA,
                sessionId = sessionId,
                tickNumber = tickNumber,
                gameTimeMinutes = gameTimeMinutes,
                clockState = clockState,
                clockSpeedMultiplier = clockSpeedMultiplier,
                network = if (networkChanged) networkDto else null,
                powerFlowStatus = if (networkChanged) powerFlowResult.status else null,
                violations = if (violationsChanged) violations else null,
                alerts = alertsToSend,
                pendingEventCards = if (cardsChanged) cards else null,
            )

        if (networkChanged) state.lastNetworkHash = networkDto.hashCode()
        if (violationsChanged) state.lastViolationsHash = violations.hashCode()
        if (cardsChanged) state.lastPendingCardsHash = cards.hashCode()

        broadcast(sessionId, update)
    }

    private fun broadcast(
        sessionId: String,
        update: GameStateUpdate,
    ) {
        val dest = "/topic/session/$sessionId/state"
        try {
            messagingTemplate.convertAndSend(dest, update)
            log.debug(
                "Published {} GameStateUpdate for session {} tick={}",
                update.type,
                sessionId,
                update.tickNumber,
            )
        } catch (ex: Exception) {
            log.error("Failed to broadcast GameStateUpdate for session {}: {}", sessionId, ex.message, ex)
        }
    }

    private fun buildNetworkDto(
        sessionId: String,
        pfResult: PowerFlowResult,
    ): GridNetworkWsDto {
        val snapshot = pfResult.snapshot
        val totalLoad = snapshot.loads.filter { it.connected }.sumOf { it.activePowerMw }
        val totalGen = snapshot.generators.filter { it.connected }.sumOf { it.targetActivePowerMw }
        val smc = sessionStore.find(sessionId)?.latestDispatchResult?.systemMarginalCostPerMwh

        val buses =
            snapshot.buses.map { bus ->
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
            snapshot.lines.map { line ->
                BranchWsDto(
                    id = line.id,
                    fromBusId = line.fromBusId,
                    toBusId = line.toBusId,
                    activePowerMw = line.activePowerFromMw ?: 0.0,
                    reactivePowerMvar = line.reactivePowerFromMvar ?: 0.0,
                    loadingPercent = lineLoadingPercent(line),
                    connected = line.connected,
                )
            } +
            snapshot.twoWindingsTransformers.map { twt ->
                BranchWsDto(
                    id = twt.id,
                    fromBusId = twt.fromBusId,
                    toBusId = twt.toBusId,
                    activePowerMw = twt.activePowerFromMw ?: 0.0,
                    reactivePowerMvar = twt.reactivePowerFromMvar ?: 0.0,
                    loadingPercent = transformerLoadingPercent(twt),
                    connected = twt.connected,
                )
            }

        val generators =
            snapshot.generators.map { gen ->
                GeneratorWsDto(
                    id = gen.id,
                    busId = gen.busId,
                    name = gen.name,
                    activePowerMw = gen.targetActivePowerMw,
                    maxActivePowerMw = gen.maxActivePowerMw,
                    committed = gen.connected,
                    fuelType = gen.fuelType.name,
                )
            }

        val loads =
            snapshot.loads.map { load ->
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
            generators = generators,
            loads = loads,
            totalLoadMw = totalLoad,
            totalGenerationMw = totalGen,
            systemMarginalCostPerMwh = smc,
        )
    }

    private fun lineLoadingPercent(line: Line): Double {
        val rating = line.ratingA ?: return 0.0
        if (rating <= 0.0) return 0.0
        val maxCurrent = maxOf(line.currentFromA ?: 0.0, line.currentToA ?: 0.0)
        return maxCurrent / rating * 100.0
    }

    private fun transformerLoadingPercent(twt: TwoWindingsTransformer): Double {
        // Prefer a direct current rating derived from ratingMva at the from-side voltage.
        val ratingA =
            twt.ratingMva?.let { mva ->
                if (twt.nominalVoltageFromKv > 0.0) mva * 1000.0 / (SQRT3 * twt.nominalVoltageFromKv) else null
            } ?: return 0.0
        if (ratingA <= 0.0) return 0.0
        val maxCurrent = maxOf(twt.currentFromA ?: 0.0, twt.currentToA ?: 0.0)
        return maxCurrent / ratingA * 100.0
    }

    companion object {
        private val SQRT3 = sqrt(3.0)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-session tracking
// ─────────────────────────────────────────────────────────────────────────────

private class SessionPublishState {
    var lastNetworkHash: Int = 0
    var lastViolationsHash: Int = 0
    var lastPendingCardsHash: Int = 0
}

// ─────────────────────────────────────────────────────────────────────────────
// Mapping helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun NetworkViolation.toDto(): ViolationDto =
    when (this) {
        is NetworkViolation.VoltageViolation ->
            ViolationDto(
                elementId = busId,
                elementType = "BUS",
                violationType = if (voltagePu < limitMinPu) "VOLTAGE_LOW" else "VOLTAGE_HIGH",
                value = voltagePu,
                limit = if (voltagePu < limitMinPu) limitMinPu else limitMaxPu,
            )
        is NetworkViolation.ThermalViolation ->
            ViolationDto(
                elementId = equipmentId,
                elementType =
                    when (equipmentType) {
                        EquipmentType.LINE -> "LINE"
                        EquipmentType.TWO_WINDINGS_TRANSFORMER,
                        EquipmentType.THREE_WINDINGS_TRANSFORMER,
                        -> "TRANSFORMER"
                        EquipmentType.BUS -> "BUS"
                    },
                violationType = "OVERLOAD",
                value = loadingPercent,
                limit = 100.0,
            )
    }

private fun EventCard.toDto(): EventCardDto =
    EventCardDto(
        cardId = cardId,
        prompt = prompt,
        options =
            options.mapIndexed { idx, opt ->
                EventCardOptionDto(index = idx, label = opt.label, costGbp = opt.costGbp)
            },
    )
