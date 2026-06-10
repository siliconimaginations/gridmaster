package com.gridmaster.api.websocket

import com.gridmaster.api.PhysicsSession
import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.engine.model.Bus
import com.gridmaster.engine.model.FuelType
import com.gridmaster.engine.model.Generator
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.Load
import com.gridmaster.engine.network.TestNetworkFactory
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.EquipmentType
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.SolveMode
import com.gridmaster.game.ClockState
import com.gridmaster.game.command.AlertSeverity
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.time.Instant
import com.gridmaster.game.command.Alert as GameAlert

/**
 * Unit tests for [GameStatePublisherImpl].
 */
class GameStatePublisherImplTest {
    private lateinit var publisher: GameStatePublisherImpl
    private lateinit var messagingTemplate: SimpMessagingTemplate
    private lateinit var sessionStore: PhysicsSessionStore
    private lateinit var snapshot: GridNetwork

    private val sessionId = "session-ws-1"

    @BeforeEach
    fun setUp() {
        messagingTemplate = mockk(relaxed = true)
        sessionStore = mockk(relaxed = true)
        snapshot = buildSnapshot()
        val session = PhysicsSession(sessionId, TestNetworkFactory.create(), snapshot)
        every { sessionStore.find(sessionId) } returns session
        publisher = GameStatePublisherImpl(messagingTemplate, sessionStore)
    }

    // ── FULL on interval ──────────────────────────────────────────────────────

    @Test
    fun `publishTick sends FULL on tick 30`() {
        val destSlot = slot<String>()
        val payloadSlot = slot<Any>()
        every { messagingTemplate.convertAndSend(capture(destSlot), capture(payloadSlot)) } returns Unit

        tick30()

        assertThat(destSlot.captured).isEqualTo("/topic/session/$sessionId/state")
        val update = payloadSlot.captured as GameStateUpdate
        assertThat(update.type).isEqualTo(UpdateType.FULL)
        assertThat(update.tickNumber).isEqualTo(30L)
        assertThat(update.network).isNotNull
        assertThat(update.violations).isNotNull
    }

    @Test
    fun `publishTick suppresses broadcast when nothing changed between ticks`() {
        tick30()
        clearMocks(messagingTemplate, answers = false, recordedCalls = true)

        // Tick 31 — identical state → no broadcast
        publisher.publishTick(
            sessionId = sessionId,
            tickNumber = 31L,
            gameTimeMinutes = 310,
            clockState = ClockState.RUNNING,
            clockSpeedMultiplier = 1,
            powerFlowResult = pfResult(),
            newAlerts = emptyList(),
            pendingCards = emptyList(),
        )

        verify { messagingTemplate wasNot Called }
    }

    // ── New alerts always broadcast ───────────────────────────────────────────

    @Test
    fun `publishTick sends DELTA with alert even when network unchanged`() {
        tick30()
        clearMocks(messagingTemplate, answers = false, recordedCalls = true)

        val payloadSlot = slot<Any>()
        every { messagingTemplate.convertAndSend(any<String>(), capture(payloadSlot)) } returns Unit

        val alert =
            GameAlert.ThermalAlert(
                severity = AlertSeverity.WARNING,
                elementId = "l1",
                loadingPercent = 105.0,
                equipmentType = EquipmentType.LINE,
            )

        publisher.publishTick(
            sessionId = sessionId,
            tickNumber = 31L,
            gameTimeMinutes = 310,
            clockState = ClockState.RUNNING,
            clockSpeedMultiplier = 1,
            powerFlowResult = pfResult(),
            newAlerts = listOf(alert),
            pendingCards = emptyList(),
        )

        val update = payloadSlot.captured as GameStateUpdate
        assertThat(update.type).isEqualTo(UpdateType.DELTA)
        assertThat(update.alerts).hasSize(1)
        assertThat(update.alerts!![0].elementId).isEqualTo("l1")
    }

    // ── publishFull ───────────────────────────────────────────────────────────

    @Test
    fun `publishFull sends FULL update to session topic`() {
        val payloads = mutableListOf<Any>()
        every { messagingTemplate.convertAndSend(any<String>(), capture(payloads)) } returns Unit

        publishFull()

        val update = payloads.filterIsInstance<GameStateUpdate>().first()
        assertThat(update.type).isEqualTo(UpdateType.FULL)
        assertThat(update.sessionId).isEqualTo(sessionId)
        assertThat(update.clockState).isEqualTo(ClockState.PAUSED)
    }

    @Test
    fun `publishFull with missedTicks sends ConnectionStatus before state update`() {
        val payloads = mutableListOf<Any>()
        every { messagingTemplate.convertAndSend(any<String>(), capture(payloads)) } returns Unit

        publisher.publishFull(
            sessionId = sessionId,
            tickNumber = 5L,
            gameTimeMinutes = 50,
            clockState = ClockState.RUNNING,
            clockSpeedMultiplier = 2,
            powerFlowResult = pfResult(),
            newAlerts = emptyList(),
            pendingCards = emptyList(),
            missedTicks = 3L,
        )

        val status = payloads.filterIsInstance<ConnectionStatus>().firstOrNull()
        assertThat(status).isNotNull
        assertThat(status!!.type).isEqualTo(ConnectionStatusType.RECONNECTED)
        assertThat(status.missedTicks).isEqualTo(3L)
    }

    // ── clearSession ──────────────────────────────────────────────────────────

    @Test
    fun `clearSession resets delta state so next tick broadcasts again`() {
        tick30()
        publisher.clearSession(sessionId)
        clearMocks(messagingTemplate, answers = false, recordedCalls = true)

        // State hash reset to 0 → non-zero network hash → delta fires
        publisher.publishTick(
            sessionId = sessionId,
            tickNumber = 31L,
            gameTimeMinutes = 310,
            clockState = ClockState.RUNNING,
            clockSpeedMultiplier = 1,
            powerFlowResult = pfResult(),
            newAlerts = emptyList(),
            pendingCards = emptyList(),
        )

        verify(atLeast = 1) { messagingTemplate.convertAndSend(any<String>(), any<Any>()) }
    }

    // ── Destination ───────────────────────────────────────────────────────────

    @Test
    fun `FULL update broadcast to correct STOMP destination`() {
        val destSlot = slot<String>()
        every { messagingTemplate.convertAndSend(capture(destSlot), any<Any>()) } returns Unit

        tick30()

        assertThat(destSlot.captured).isEqualTo("/topic/session/$sessionId/state")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun tick30() =
        publisher.publishTick(
            sessionId = sessionId,
            tickNumber = 30L,
            gameTimeMinutes = 300,
            clockState = ClockState.RUNNING,
            clockSpeedMultiplier = 1,
            powerFlowResult = pfResult(),
            newAlerts = emptyList(),
            pendingCards = emptyList(),
        )

    private fun publishFull() =
        publisher.publishFull(
            sessionId = sessionId,
            tickNumber = 5L,
            gameTimeMinutes = 50,
            clockState = ClockState.PAUSED,
            clockSpeedMultiplier = 1,
            powerFlowResult = pfResult(),
            newAlerts = emptyList(),
            pendingCards = emptyList(),
        )

    private fun buildSnapshot() =
        GridNetwork(
            id = sessionId,
            name = "Test",
            buses = listOf(Bus("b1", "Bus 1", 400.0)),
            lines = emptyList(),
            twoWindingsTransformers = emptyList(),
            threeWindingsTransformers = emptyList(),
            generators =
                listOf(
                    Generator(
                        id = "g1",
                        name = "Gen 1",
                        busId = "b1",
                        minActivePowerMw = 0.0,
                        maxActivePowerMw = 200.0,
                        targetActivePowerMw = 100.0,
                        targetReactivePowerMvar = 0.0,
                        targetVoltagePu = 1.0,
                        connected = true,
                        fuelType = FuelType.GAS,
                        marginalCostPerMwh = 30.0,
                    ),
                ),
            loads = listOf(Load("ld1", "Load 1", "b1", 80.0, 10.0, connected = true)),
            shuntCompensators = emptyList(),
            snapshotAt = Instant.now(),
        )

    private fun pfResult() =
        PowerFlowResult(
            status = ConvergenceStatus.CONVERGED,
            solveMode = SolveMode.AC,
            iterationCount = 3,
            snapshot = snapshot,
            slackBusIds = listOf("b1"),
            violations = emptyList(),
            solveTimeMs = 5,
        )
}
