package com.gridmaster.api.websocket

import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.api.SessionNotFoundException
import com.gridmaster.engine.dispatch.DispatchMode
import com.gridmaster.engine.powerflow.EquipmentType
import com.gridmaster.game.ClockState
import com.gridmaster.game.TickEngine
import com.gridmaster.game.command.CommandHandler
import com.gridmaster.game.command.GeneratorSchedule
import com.gridmaster.game.command.PlayerCommand
import com.gridmaster.game.event.EventEngine
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.annotation.SendToUser
import org.springframework.stereotype.Controller

/**
 * Receives player commands via STOMP and routes them through [CommandHandler].
 *
 * Clients send to:   `/app/session/{sessionId}/command`
 * Ack is returned to: `/user/queue/session/{sessionId}/ack`
 *
 * After a successful command the latest session state is pushed as a FULL
 * [GameStateUpdate] to all session subscribers.
 */
@Controller
class CommandWebSocketController(
    private val commandHandler: CommandHandler,
    private val sessionStore: PhysicsSessionStore,
    private val gameStatePublisher: GameStatePublisher,
    private val eventEngine: EventEngine,
    private val tickEngine: TickEngine,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(CommandWebSocketController::class.java)

    @MessageMapping("/session/{sessionId}/command")
    @SendToUser("/queue/session/{sessionId}/ack")
    fun handleCommand(
        @DestinationVariable sessionId: String,
        @Payload message: PlayerCommandMessage,
        headerAccessor: SimpMessageHeaderAccessor,
    ): CommandAck {
        if (message.commandType.isBlank()) {
            return CommandAck(
                commandType = message.commandType,
                success = false,
                rejectionReason = "commandType must not be blank",
                appliedAtTick = -1,
            )
        }

        val userId =
            headerAccessor.user?.name
                ?: return CommandAck(
                    commandType = message.commandType,
                    success = false,
                    rejectionReason = "Unauthenticated",
                    appliedAtTick = -1,
                )

        val session =
            sessionStore.find(sessionId)
                ?: return CommandAck(
                    commandType = message.commandType,
                    success = false,
                    rejectionReason = "Session not found: $sessionId",
                    appliedAtTick = -1,
                )

        val command =
            try {
                deserializeCommand(sessionId, message)
            } catch (ex: Exception) {
                log.warn("Failed to deserialize command {}: {}", message.commandType, ex.message)
                return CommandAck(
                    commandType = message.commandType,
                    success = false,
                    rejectionReason = "Invalid command payload: ${ex.message}",
                    appliedAtTick = -1,
                )
            }

        val result =
            try {
                commandHandler.handle(command, userId)
            } catch (ex: SessionNotFoundException) {
                // TickEngine lost the session (e.g. backend restarted). Tell the
                // client to re-bootstrap rather than letting the exception propagate.
                log.warn("Session {} not found in TickEngine during command {}", sessionId, message.commandType)
                messagingTemplate.convertAndSend(
                    "/topic/session/$sessionId/state",
                    ConnectionStatus(type = ConnectionStatusType.SESSION_NOT_FOUND, sessionId = sessionId),
                )
                return CommandAck(
                    commandType = message.commandType,
                    success = false,
                    rejectionReason = "Session expired — please refresh",
                    appliedAtTick = -1,
                )
            }
        val clockStatus = tickEngine.clockStatus(sessionId)
        val currentTick = clockStatus?.tickCount ?: 0L
        val currentGameTime = clockStatus?.gameTimeMinutes ?: 0L
        val currentClockState = clockStatus?.clockState ?: ClockState.PAUSED
        val currentSpeed = clockStatus?.speedMultiplier ?: 1

        val ack =
            CommandAck(
                commandType = message.commandType,
                success = result.success,
                rejectionReason = result.commandOutcomes.firstOrNull()?.rejectionReason,
                appliedAtTick = currentTick,
            )

        if (result.success) {
            val pendingCards = eventEngine.pendingCards(sessionId)
            gameStatePublisher.publishFull(
                sessionId = sessionId,
                tickNumber = currentTick,
                gameTimeMinutes = currentGameTime,
                clockState = currentClockState,
                clockSpeedMultiplier = currentSpeed,
                powerFlowResult = result.powerFlowResult,
                newAlerts = result.newAlerts,
                pendingCards = pendingCards,
                // Issue #391: relay the tick engine's current weather reading (if any)
                // so a post-command FULL refresh doesn't momentarily blank the HUD's
                // weather badge — weather is stateful, not derivable from gameTimeMinutes
                // alone, so it must come from clockStatus rather than being recomputed here.
                // All four fields are declared nullable end-to-end (TickClockStatus ->
                // GameStatePublisher -> GameStateUpdate -> frontend WeatherState | null), so
                // clockStatus being null (session not found in TickEngine) or weather being
                // disabled (TickClockStatus.weatherState itself null) both safely collapse to
                // "no weather badge shown" via this `?.` chain — there is no non-null
                // assumption anywhere in this chain for `?.` to violate.
                weatherState = clockStatus?.weatherState,
                weatherCloudCoverPct = clockStatus?.weatherCloudCoverPct,
                weatherWindSpeedMps = clockStatus?.weatherWindSpeedMps,
                weatherRegionId = clockStatus?.weatherRegionId,
            )
        }

        log.info(
            "WS command {} for session {} by {}: success={}",
            message.commandType,
            sessionId,
            userId,
            result.success,
        )

        return ack
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PlayerCommandMessage → PlayerCommand deserialization
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun deserializeCommand(
        sessionId: String,
        msg: PlayerCommandMessage,
    ): PlayerCommand {
        val p = msg.payload

        fun str(key: String): String = p[key]?.toString() ?: error("Missing '$key' in ${msg.commandType} payload")

        fun double(key: String): Double = p[key]?.toString()?.toDoubleOrNull() ?: error("'$key' must be a number in ${msg.commandType}")

        fun int(key: String): Int = p[key]?.toString()?.toIntOrNull() ?: error("'$key' must be an integer in ${msg.commandType}")

        fun optDouble(key: String): Double? = p[key]?.toString()?.toDoubleOrNull()

        return when (msg.commandType) {
            "SetGeneratorOutput" ->
                PlayerCommand.SetGeneratorOutput(sessionId, str("generatorId"), double("targetMw"))
            "SetGeneratorVoltage" ->
                PlayerCommand.SetGeneratorVoltage(sessionId, str("generatorId"), double("targetVoltagePu"))
            "TripElement" ->
                PlayerCommand.TripElement(
                    sessionId,
                    str("elementId"),
                    EquipmentType.valueOf(str("elementType")),
                )
            "ConnectElement" ->
                PlayerCommand.ConnectElement(
                    sessionId,
                    str("elementId"),
                    EquipmentType.valueOf(str("elementType")),
                )
            "SetTapPosition" ->
                PlayerCommand.SetTapPosition(sessionId, str("transformerId"), int("tapPosition"))
            "ShedLoad" ->
                PlayerCommand.ShedLoad(sessionId, str("loadId"), double("fractionToShed"))
            "RunEconomicDispatch" ->
                PlayerCommand.RunEconomicDispatch(
                    sessionId,
                    double("totalLoadMw"),
                    mode = p["mode"]?.toString()?.let { DispatchMode.valueOf(it) } ?: DispatchMode.MERIT_ORDER,
                    securityConstrained = p["securityConstrained"]?.toString()?.toBooleanStrictOrNull() ?: false,
                )
            "CommitGenerator" ->
                PlayerCommand.CommitGenerator(sessionId, str("generatorId"))
            "DecommitGenerator" ->
                PlayerCommand.DecommitGenerator(sessionId, str("generatorId"))
            "ApplyUcSchedule" -> {
                val rawSchedule =
                    p["schedule"] as? List<Map<String, Any?>>
                        ?: error("'schedule' must be a list in ApplyUcSchedule")
                val schedule =
                    rawSchedule.map { entry ->
                        GeneratorSchedule(
                            generatorId =
                                entry["generatorId"]?.toString()
                                    ?: error("Missing 'generatorId' in schedule entry"),
                            committed =
                                entry["committed"]?.toString()?.toBooleanStrictOrNull()
                                    ?: error("Missing 'committed' in schedule entry"),
                            targetMw = entry["targetMw"]?.toString()?.toDoubleOrNull(),
                        )
                    }
                PlayerCommand.ApplyUcSchedule(sessionId, schedule)
            }
            "SetClockSpeed" ->
                PlayerCommand.SetClockSpeed(sessionId, int("multiplier"))
            "PauseClock" ->
                PlayerCommand.PauseClock(sessionId)
            "ResumeClock" ->
                PlayerCommand.ResumeClock(sessionId)
            "RespondToEventCard" ->
                PlayerCommand.RespondToEventCard(sessionId, str("cardId"), str("optionId"))
            else -> error("Unknown commandType: ${msg.commandType}")
        }
    }
}
