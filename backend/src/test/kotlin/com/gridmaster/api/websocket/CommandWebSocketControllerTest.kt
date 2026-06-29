package com.gridmaster.api.websocket

import com.gridmaster.api.PhysicsSession
import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.api.SessionNotFoundException
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.SolveMode
import com.gridmaster.game.ClockState
import com.gridmaster.game.TickClockStatus
import com.gridmaster.game.TickEngine
import com.gridmaster.game.command.CommandHandler
import com.gridmaster.game.command.CommandOutcome
import com.gridmaster.game.command.CommandResult
import com.gridmaster.game.event.EventEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.security.Principal

/**
 * Unit tests for [CommandWebSocketController].
 *
 * [handleCommand] is a regular Kotlin function and can be called directly — no STOMP
 * framework or Spring context is needed. All dependencies are mocked with MockK.
 */
class CommandWebSocketControllerTest {
    private lateinit var commandHandler: CommandHandler
    private lateinit var sessionStore: PhysicsSessionStore
    private lateinit var gameStatePublisher: GameStatePublisher
    private lateinit var eventEngine: EventEngine
    private lateinit var tickEngine: TickEngine
    private lateinit var messagingTemplate: SimpMessagingTemplate
    private lateinit var controller: CommandWebSocketController

    private val sessionId = "ws-session-1"
    private val userId = "ws-user-1"
    private val mockSnapshot = mockk<GridNetwork>(relaxed = true)
    private val mockIidmNetwork = mockk<com.powsybl.iidm.network.Network>(relaxed = true)

    @BeforeEach
    fun setUp() {
        commandHandler = mockk(relaxed = true)
        sessionStore = mockk()
        gameStatePublisher = mockk(relaxed = true)
        eventEngine = mockk(relaxed = true)
        tickEngine = mockk(relaxed = true)
        messagingTemplate = mockk(relaxed = true)

        controller =
            CommandWebSocketController(
                commandHandler = commandHandler,
                sessionStore = sessionStore,
                gameStatePublisher = gameStatePublisher,
                eventEngine = eventEngine,
                tickEngine = tickEngine,
                messagingTemplate = messagingTemplate,
            )

        val session = PhysicsSession(sessionId, mockIidmNetwork, mockSnapshot)
        every { sessionStore.find(sessionId) } returns session
        every { commandHandler.handle(any(), any()) } returns successResult()
        every { tickEngine.clockStatus(sessionId) } returns clockStatus()
        every { eventEngine.pendingCards(sessionId) } returns emptyList()
    }

    // ── Blank commandType (#257) ──────────────────────────────────────────────

    @Test
    fun `handleCommand returns failure ack when commandType is blank`() {
        val msg = PlayerCommandMessage("", emptyMap())

        val ack = controller.handleCommand(sessionId, msg, authedHeaders())

        assertThat(ack.success).isFalse()
        assertThat(ack.rejectionReason).isEqualTo("commandType must not be blank")
        assertThat(ack.appliedAtTick).isEqualTo(-1L)
    }

    // ── Unauthenticated ───────────────────────────────────────────────────────

    @Test
    fun `handleCommand returns failure ack with Unauthenticated when no user principal`() {
        val headers = mockk<SimpMessageHeaderAccessor> { every { user } returns null }
        val msg = PlayerCommandMessage("PauseClock", emptyMap())

        val ack = controller.handleCommand(sessionId, msg, headers)

        assertThat(ack.success).isFalse()
        assertThat(ack.commandType).isEqualTo("PauseClock")
        assertThat(ack.rejectionReason).isEqualTo("Unauthenticated")
        assertThat(ack.appliedAtTick).isEqualTo(-1L)
    }

    // ── Session not found ─────────────────────────────────────────────────────

    @Test
    fun `handleCommand returns failure ack when session is not in store`() {
        every { sessionStore.find(sessionId) } returns null
        val msg = PlayerCommandMessage("PauseClock", emptyMap())

        val ack = controller.handleCommand(sessionId, msg, authedHeaders())

        assertThat(ack.success).isFalse()
        assertThat(ack.rejectionReason).contains("Session not found")
        assertThat(ack.appliedAtTick).isEqualTo(-1L)
    }

    // ── Deserialization failures ───────────────────────────────────────────────

    @Test
    fun `handleCommand returns failure ack for unknown commandType`() {
        val msg = PlayerCommandMessage("QuantumLeap", emptyMap())

        val ack = controller.handleCommand(sessionId, msg, authedHeaders())

        assertThat(ack.success).isFalse()
        assertThat(ack.rejectionReason).contains("Invalid command payload")
    }

    @Test
    fun `handleCommand returns failure ack when required string field is missing`() {
        // SetGeneratorOutput requires both "generatorId" and "targetMw"
        val msg = PlayerCommandMessage("SetGeneratorOutput", mapOf("generatorId" to "G1"))

        val ack = controller.handleCommand(sessionId, msg, authedHeaders())

        assertThat(ack.success).isFalse()
        assertThat(ack.rejectionReason).contains("Invalid command payload")
    }

    @Test
    fun `handleCommand returns failure ack when numeric field is not a number`() {
        val msg = PlayerCommandMessage("SetGeneratorOutput", mapOf("generatorId" to "G1", "targetMw" to "notanumber"))

        val ack = controller.handleCommand(sessionId, msg, authedHeaders())

        assertThat(ack.success).isFalse()
        assertThat(ack.rejectionReason).contains("Invalid command payload")
    }

    // ── SessionNotFoundException during handle ────────────────────────────────

    @Test
    fun `handleCommand broadcasts SESSION_NOT_FOUND and returns failure when TickEngine loses session`() {
        every { commandHandler.handle(any(), any()) } throws SessionNotFoundException(sessionId)
        val msg = PlayerCommandMessage("PauseClock", emptyMap())

        val ack = controller.handleCommand(sessionId, msg, authedHeaders())

        assertThat(ack.success).isFalse()
        assertThat(ack.rejectionReason).contains("Session expired")
        verify { messagingTemplate.convertAndSend(any<String>(), any<ConnectionStatus>()) }
    }

    // ── Success path ──────────────────────────────────────────────────────────

    @Test
    fun `handleCommand returns success ack and publishes full state on successful command`() {
        val msg = PlayerCommandMessage("SetGeneratorOutput", mapOf("generatorId" to "G1", "targetMw" to "80.0"))

        val ack = controller.handleCommand(sessionId, msg, authedHeaders())

        assertThat(ack.success).isTrue()
        assertThat(ack.commandType).isEqualTo("SetGeneratorOutput")
        verify { gameStatePublisher.publishFull(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleCommand does not publish state when command handler returns failure`() {
        every { commandHandler.handle(any(), any()) } returns failureResult()
        val msg = PlayerCommandMessage("PauseClock", emptyMap())

        val ack = controller.handleCommand(sessionId, msg, authedHeaders())

        assertThat(ack.success).isFalse()
        verify(exactly = 0) { gameStatePublisher.publishFull(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── Command type deserialization coverage ─────────────────────────────────

    @Test
    fun `handleCommand deserializes PauseClock with empty payload`() {
        val ack = controller.handleCommand(sessionId, PlayerCommandMessage("PauseClock", emptyMap()), authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand deserializes ResumeClock with empty payload`() {
        val ack = controller.handleCommand(sessionId, PlayerCommandMessage("ResumeClock", emptyMap()), authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand deserializes SetClockSpeed`() {
        val msg = PlayerCommandMessage("SetClockSpeed", mapOf("multiplier" to "5"))
        val ack = controller.handleCommand(sessionId, msg, authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand deserializes RespondToEventCard`() {
        val msg = PlayerCommandMessage("RespondToEventCard", mapOf("cardId" to "card-uuid-1", "optionId" to "0"))
        val ack = controller.handleCommand(sessionId, msg, authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand deserializes TripElement`() {
        val msg = PlayerCommandMessage("TripElement", mapOf("elementId" to "L12", "elementType" to "LINE"))
        val ack = controller.handleCommand(sessionId, msg, authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand deserializes ConnectElement`() {
        val msg = PlayerCommandMessage("ConnectElement", mapOf("elementId" to "L12", "elementType" to "LINE"))
        val ack = controller.handleCommand(sessionId, msg, authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand deserializes SetTapPosition`() {
        val msg = PlayerCommandMessage("SetTapPosition", mapOf("transformerId" to "TX12", "tapPosition" to "1"))
        val ack = controller.handleCommand(sessionId, msg, authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand deserializes ShedLoad`() {
        val msg = PlayerCommandMessage("ShedLoad", mapOf("loadId" to "Load1", "fractionToShed" to "0.5"))
        val ack = controller.handleCommand(sessionId, msg, authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand deserializes CommitGenerator`() {
        val msg = PlayerCommandMessage("CommitGenerator", mapOf("generatorId" to "G1"))
        val ack = controller.handleCommand(sessionId, msg, authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand deserializes DecommitGenerator`() {
        val msg = PlayerCommandMessage("DecommitGenerator", mapOf("generatorId" to "G2"))
        val ack = controller.handleCommand(sessionId, msg, authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand deserializes SetGeneratorVoltage`() {
        val msg = PlayerCommandMessage("SetGeneratorVoltage", mapOf("generatorId" to "G1", "targetVoltagePu" to "1.02"))
        val ack = controller.handleCommand(sessionId, msg, authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand deserializes RunEconomicDispatch with defaults`() {
        val msg = PlayerCommandMessage("RunEconomicDispatch", mapOf("totalLoadMw" to "200.0"))
        val ack = controller.handleCommand(sessionId, msg, authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand deserializes ApplyUcSchedule`() {
        val schedule = listOf(mapOf("generatorId" to "G1", "committed" to "true", "targetMw" to "80.0"))
        val msg = PlayerCommandMessage("ApplyUcSchedule", mapOf("schedule" to schedule))
        val ack = controller.handleCommand(sessionId, msg, authedHeaders())
        assertThat(ack.success).isTrue()
    }

    @Test
    fun `handleCommand returns failure ack when ApplyUcSchedule schedule entry is missing generatorId`() {
        val schedule = listOf(mapOf("committed" to "true"))
        val msg = PlayerCommandMessage("ApplyUcSchedule", mapOf("schedule" to schedule))
        val ack = controller.handleCommand(sessionId, msg, authedHeaders())
        assertThat(ack.success).isFalse()
        assertThat(ack.rejectionReason).contains("Invalid command payload")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun authedHeaders(): SimpMessageHeaderAccessor {
        val principal = mockk<Principal> { every { name } returns userId }
        return mockk<SimpMessageHeaderAccessor> { every { user } returns principal }
    }

    private fun successResult() =
        CommandResult(
            success = true,
            snapshot = mockSnapshot,
            powerFlowResult = pfResult(),
            newAlerts = emptyList(),
            commandOutcomes = listOf(CommandOutcome(commandType = "cmd", success = true)),
        )

    private fun failureResult() =
        CommandResult(
            success = false,
            snapshot = mockSnapshot,
            powerFlowResult = pfResult(),
            newAlerts = emptyList(),
            commandOutcomes =
                listOf(
                    CommandOutcome(commandType = "cmd", success = false, rejectionReason = "Rejected"),
                ),
        )

    private fun pfResult() =
        PowerFlowResult(
            status = ConvergenceStatus.CONVERGED,
            solveMode = SolveMode.AC,
            iterationCount = 3,
            snapshot = mockSnapshot,
            slackBusIds = emptyList(),
            violations = emptyList(),
            solveTimeMs = 5,
        )

    private fun clockStatus() =
        TickClockStatus(
            clockState = ClockState.RUNNING,
            speedMultiplier = 1,
            gameTimeMinutes = 0L,
            tickCount = 5L,
            autoSlowed = false,
        )
}
