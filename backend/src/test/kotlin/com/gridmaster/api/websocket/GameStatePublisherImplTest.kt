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
import com.gridmaster.engine.powerflow.NetworkViolation
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.SolveMode
import com.gridmaster.engine.powerflow.ViolationSeverity
import com.gridmaster.game.ClockState
import com.gridmaster.game.command.AlertSeverity
import com.gridmaster.game.event.CardOption
import com.gridmaster.game.event.EventCard
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

    // -- FULL on interval -----------------------------------------------------

    @Test
    fun publishTick_sendsFull_onTick30() {
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
    fun publishTick_alwaysSendsDelta_evenWhenNetworkUnchanged() {
        // Tick 30 (FULL) establishes baseline hashes.
        tick30()
        clearMocks(messagingTemplate, answers = false, recordedCalls = true)

        val payloadSlot = slot<Any>()
        every { messagingTemplate.convertAndSend(any<String>(), capture(payloadSlot)) } returns Unit

        // Tick 31: identical network/violations/cards but tickNumber always increments.
        // The publisher must always broadcast so the frontend tick counter advances.
        // (Suppressing when only clock fields change caused GC-01 to fail — fixed in #164.)
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

        val update = payloadSlot.captured as GameStateUpdate
        assertThat(update.type).isEqualTo(UpdateType.DELTA)
        assertThat(update.tickNumber).isEqualTo(31L)
        // Unchanged fields are omitted from the DELTA to keep bandwidth low
        assertThat(update.network).isNull()
        assertThat(update.violations).isNull()
        assertThat(update.pendingEventCards).isNull()
        assertThat(update.alerts).isNull()
    }

    // -- New alerts always broadcast ------------------------------------------

    @Test
    fun publishTick_sendsDeltaWithAlert_evenWhenNetworkUnchanged() {
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

    // -- publishFull ----------------------------------------------------------

    @Test
    fun publishFull_sendsFull_toSessionTopic() {
        val payloads = mutableListOf<Any>()
        every { messagingTemplate.convertAndSend(any<String>(), capture(payloads)) } returns Unit

        publishFull()

        val update = payloads.filterIsInstance<GameStateUpdate>().first()
        assertThat(update.type).isEqualTo(UpdateType.FULL)
        assertThat(update.sessionId).isEqualTo(sessionId)
        assertThat(update.clockState).isEqualTo(ClockState.PAUSED)
    }

    @Test
    fun publishFull_withMissedTicks_sendsConnectionStatusFirst() {
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

    // -- clearSession ---------------------------------------------------------

    @Test
    fun clearSession_resetsDeltaState_soNextTickBroadcasts() {
        tick30()
        publisher.clearSession(sessionId)
        clearMocks(messagingTemplate, answers = false, recordedCalls = true)

        // State hash reset to 0 so next delta always fires
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

    // -- Destination ----------------------------------------------------------

    @Test
    fun fullUpdate_broadcastsToCorrect_stompDestination() {
        val destSlot = slot<String>()
        every { messagingTemplate.convertAndSend(capture(destSlot), any<Any>()) } returns Unit

        tick30()

        assertThat(destSlot.captured).isEqualTo("/topic/session/$sessionId/state")
    }

    // -- Violation toDto coverage -----------------------------------------

    @Test
    fun `voltage low violation is mapped to VOLTAGE_LOW ViolationDto`() {
        val payloadSlot = slot<Any>()
        every { messagingTemplate.convertAndSend(any<String>(), capture(payloadSlot)) } returns Unit

        val violation =
            NetworkViolation.VoltageViolation(
                busId = "b1",
                voltagePu = 0.90,
                limitMinPu = 0.95,
                limitMaxPu = 1.05,
                severity = ViolationSeverity.ALARM,
            )
        publisher.publishTick(
            sessionId = sessionId,
            tickNumber = 30L,
            gameTimeMinutes = 300,
            clockState = ClockState.RUNNING,
            clockSpeedMultiplier = 1,
            powerFlowResult = pfResult(violations = listOf(violation)),
            newAlerts = emptyList(),
            pendingCards = emptyList(),
        )

        val update = payloadSlot.captured as GameStateUpdate
        assertThat(update.violations).hasSize(1)
        val dto = update.violations!!.first()
        assertThat(dto.elementId).isEqualTo("b1")
        assertThat(dto.elementType).isEqualTo("BUS")
        assertThat(dto.violationType).isEqualTo("VOLTAGE_LOW")
        assertThat(dto.value).isEqualTo(0.90)
        assertThat(dto.limit).isEqualTo(0.95)
    }

    @Test
    fun `voltage high violation maps to VOLTAGE_HIGH DTO with upper limit`() {
        val payloadSlot = slot<Any>()
        every { messagingTemplate.convertAndSend(any<String>(), capture(payloadSlot)) } returns Unit

        val violation =
            NetworkViolation.VoltageViolation(
                busId = "b2",
                voltagePu = 1.08,
                limitMinPu = 0.95,
                limitMaxPu = 1.05,
                severity = ViolationSeverity.CRITICAL,
            )
        publisher.publishTick(
            sessionId = sessionId,
            tickNumber = 30L,
            gameTimeMinutes = 300,
            clockState = ClockState.RUNNING,
            clockSpeedMultiplier = 1,
            powerFlowResult = pfResult(violations = listOf(violation)),
            newAlerts = emptyList(),
            pendingCards = emptyList(),
        )

        val update = payloadSlot.captured as GameStateUpdate
        val dto = update.violations!!.first()
        assertThat(dto.violationType).isEqualTo("VOLTAGE_HIGH")
        assertThat(dto.limit).isEqualTo(1.05)
        assertThat(dto.value).isEqualTo(1.08)
    }

    @Test
    fun `line thermal violation maps to OVERLOAD LINE ViolationDto`() {
        val payloadSlot = slot<Any>()
        every { messagingTemplate.convertAndSend(any<String>(), capture(payloadSlot)) } returns Unit

        val violation =
            NetworkViolation.ThermalViolation(
                equipmentId = "l1",
                equipmentType = EquipmentType.LINE,
                currentA = 550.0,
                ratingA = 500.0,
                loadingPercent = 110.0,
                severity = ViolationSeverity.CRITICAL,
            )
        publisher.publishTick(
            sessionId = sessionId,
            tickNumber = 30L,
            gameTimeMinutes = 300,
            clockState = ClockState.RUNNING,
            clockSpeedMultiplier = 1,
            powerFlowResult = pfResult(violations = listOf(violation)),
            newAlerts = emptyList(),
            pendingCards = emptyList(),
        )

        val update = payloadSlot.captured as GameStateUpdate
        val dto = update.violations!!.first()
        assertThat(dto.elementId).isEqualTo("l1")
        assertThat(dto.elementType).isEqualTo("LINE")
        assertThat(dto.violationType).isEqualTo("OVERLOAD")
        assertThat(dto.value).isEqualTo(110.0)
        assertThat(dto.limit).isEqualTo(100.0)
    }

    @Test
    fun `three-winding transformer thermal violation maps to TRANSFORMER element type`() {
        val payloadSlot = slot<Any>()
        every { messagingTemplate.convertAndSend(any<String>(), capture(payloadSlot)) } returns Unit

        val violation =
            NetworkViolation.ThermalViolation(
                equipmentId = "T3-1",
                equipmentType = EquipmentType.THREE_WINDINGS_TRANSFORMER,
                currentA = 300.0,
                ratingA = 262.5,
                loadingPercent = 114.3,
                severity = ViolationSeverity.CRITICAL,
            )
        publisher.publishTick(
            sessionId = sessionId,
            tickNumber = 30L,
            gameTimeMinutes = 300,
            clockState = ClockState.RUNNING,
            clockSpeedMultiplier = 1,
            powerFlowResult = pfResult(violations = listOf(violation)),
            newAlerts = emptyList(),
            pendingCards = emptyList(),
        )

        val update = payloadSlot.captured as GameStateUpdate
        val dto = update.violations!!.first()
        assertThat(dto.elementType).isEqualTo("TRANSFORMER")
        assertThat(dto.violationType).isEqualTo("OVERLOAD")
    }

    @Test
    fun `pending event cards are serialized as EventCardDto with options`() {
        val payloadSlot = slot<Any>()
        every { messagingTemplate.convertAndSend(any<String>(), capture(payloadSlot)) } returns Unit

        val card =
            EventCard(
                cardId = "card-001",
                title = "Extra capacity event",
                description = "Extra capacity needed?",
                severity = com.gridmaster.game.event.EventSeverity.WARNING,
                options =
                    listOf(
                        CardOption(label = "Yes, build gas peaker", effects = emptyList(), costGbp = 500_000.0, tag = "Economic"),
                        CardOption(label = "No, manage demand", effects = emptyList(), costGbp = 0.0),
                    ),
            )
        publisher.publishTick(
            sessionId = sessionId,
            tickNumber = 30L,
            gameTimeMinutes = 300,
            clockState = ClockState.RUNNING,
            clockSpeedMultiplier = 1,
            powerFlowResult = pfResult(),
            newAlerts = emptyList(),
            pendingCards = listOf(card),
        )

        val update = payloadSlot.captured as GameStateUpdate
        assertThat(update.pendingEventCards).hasSize(1)
        val cardDto = update.pendingEventCards!!.first()
        assertThat(cardDto.id).isEqualTo("card-001")
        assertThat(cardDto.title).isEqualTo("Extra capacity event")
        assertThat(cardDto.description).isEqualTo("Extra capacity needed?")
        assertThat(cardDto.severity).isEqualTo("WARNING")
        assertThat(cardDto.options).hasSize(2)
        assertThat(cardDto.options[0].id).isEqualTo("0")
        assertThat(cardDto.options[0].label).isEqualTo("Yes, build gas peaker")
        assertThat(cardDto.options[0].costGbp).isEqualTo(500_000.0)
        assertThat(cardDto.options[0].tag).isEqualTo("Economic") // tag propagated from CardOption (#262)
        assertThat(cardDto.options[1].id).isEqualTo("1")
        assertThat(cardDto.options[1].tag).isEqualTo("") // default empty tag
    }

    // -- Helpers --------------------------------------------------------------

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

    private fun pfResult(violations: List<NetworkViolation> = emptyList()) =
        PowerFlowResult(
            status = ConvergenceStatus.CONVERGED,
            solveMode = SolveMode.AC,
            iterationCount = 3,
            snapshot = snapshot,
            slackBusIds = listOf("b1"),
            violations = violations,
            solveTimeMs = 5,
        )
}
