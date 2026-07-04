package com.gridmaster.game.command

import com.gridmaster.api.PhysicsSession
import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.api.SessionNotFoundException
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.dispatch.DispatchService
import com.gridmaster.engine.model.Bus
import com.gridmaster.engine.model.FuelType
import com.gridmaster.engine.model.Generator
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.Line
import com.gridmaster.engine.model.Load
import com.gridmaster.engine.model.NetworkMutation
import com.gridmaster.engine.network.IidmNetworkMapper
import com.gridmaster.engine.network.TestNetworkFactory
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.EquipmentType
import com.gridmaster.engine.powerflow.NetworkViolation
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.SolveMode
import com.gridmaster.engine.powerflow.ViolationSeverity
import com.gridmaster.game.TickEngine
import com.gridmaster.game.event.EventEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for [CommandHandlerImpl].
 *
 * Uses a real [TestNetworkFactory] network and a relaxed-mocked [IidmNetworkMapper]
 * to avoid actual PowSyBl mutation calls. Tests cover validation rules,
 * mutation translation, clock/card delegation, rollback, and alert generation.
 */
class CommandHandlerImplTest {
    private lateinit var handler: CommandHandlerImpl
    private lateinit var sessionStore: PhysicsSessionStore
    private lateinit var networkMapper: IidmNetworkMapper
    private lateinit var powerFlowService: PowerFlowService
    private lateinit var contingencyService: ContingencyAnalysisService
    private lateinit var dispatchService: DispatchService
    private lateinit var tickEngine: TickEngine
    private lateinit var eventEngine: EventEngine
    private lateinit var tutorialEngine: com.gridmaster.game.tutorial.TutorialEngine
    private lateinit var session: PhysicsSession
    private lateinit var snapshot: GridNetwork

    private val sessionId = "session-cmd-1"
    private val userId = "user-1"

    @BeforeEach
    fun setUp() {
        sessionStore = mockk()
        networkMapper = mockk(relaxed = true)
        powerFlowService = mockk()
        contingencyService = mockk(relaxed = true)
        dispatchService = mockk(relaxed = true)
        tickEngine = mockk(relaxed = true)
        eventEngine = mockk(relaxed = true)
        tutorialEngine = mockk(relaxed = true)

        snapshot = buildMinimalSnapshot()
        session = PhysicsSession(sessionId, TestNetworkFactory.create(), snapshot)

        every { sessionStore.get(sessionId) } returns session
        every { sessionStore.get(neq(sessionId)) } throws SessionNotFoundException("unknown")
        every { powerFlowService.solve(any(), any()) } returns successPfResult()
        every { networkMapper.applyMutation(any(), any()) } returns Result.success(session.iidmNetwork)
        every { networkMapper.toGridNetwork(any()) } returns snapshot

        handler =
            CommandHandlerImpl(
                sessionStore,
                networkMapper,
                powerFlowService,
                contingencyService,
                dispatchService,
                tickEngine,
                eventEngine,
                tutorialEngine,
            )
    }

    // ── SetGeneratorOutput ────────────────────────────────────────────────────

    @Test
    fun `SetGeneratorOutput in range succeeds and applies mutation`() {
        val cmd = PlayerCommand.SetGeneratorOutput(sessionId, generatorId = "g1", targetMw = 80.0)
        val captured = slot<NetworkMutation.SetGeneratorOutput>()
        every { networkMapper.applyMutation(any(), capture(captured)) } returns Result.success(session.iidmNetwork)

        val result = handler.handle(cmd, userId)

        assertThat(result.success).isTrue()
        assertThat(captured.captured.targetPMw).isEqualTo(80.0)
        assertThat(result.commandOutcomes).hasSize(1)
        assertThat(result.commandOutcomes[0].success).isTrue()
    }

    @Test
    fun `SetGeneratorOutput below min rejected — no mutation applied`() {
        val result =
            handler.handle(
                PlayerCommand.SetGeneratorOutput(sessionId, "g1", targetMw = -5.0),
                userId,
            )
        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("out of range")
        verify(exactly = 0) { networkMapper.applyMutation(any(), any()) }
    }

    @Test
    fun `SetGeneratorOutput above max rejected`() {
        val result =
            handler.handle(
                PlayerCommand.SetGeneratorOutput(sessionId, "g1", targetMw = 999.0),
                userId,
            )
        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("out of range")
    }

    @Test
    fun `SetGeneratorOutput for disconnected generator rejected`() {
        val result =
            handler.handle(
                PlayerCommand.SetGeneratorOutput(sessionId, "g-off", targetMw = 50.0),
                userId,
            )
        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("not committed")
    }

    @Test
    fun `SetGeneratorOutput for unknown generator rejected`() {
        val result =
            handler.handle(
                PlayerCommand.SetGeneratorOutput(sessionId, "no-such-gen", targetMw = 50.0),
                userId,
            )
        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("not found")
    }

    // ── SetGeneratorVoltage ───────────────────────────────────────────────────

    @Test
    fun `SetGeneratorVoltage in range succeeds`() {
        val result =
            handler.handle(
                PlayerCommand.SetGeneratorVoltage(sessionId, "g1", targetVoltagePu = 1.02),
                userId,
            )
        assertThat(result.success).isTrue()
    }

    @Test
    fun `SetGeneratorVoltage outside 0·9–1·1 rejected`() {
        val result =
            handler.handle(
                PlayerCommand.SetGeneratorVoltage(sessionId, "g1", targetVoltagePu = 1.5),
                userId,
            )
        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("out of range")
    }

    // ── ShedLoad ──────────────────────────────────────────────────────────────

    @Test
    fun `ShedLoad 50 percent halves the load activePowerMw`() {
        val captured = slot<NetworkMutation.SetLoadPower>()
        every { networkMapper.applyMutation(any(), capture(captured)) } returns Result.success(session.iidmNetwork)

        val result =
            handler.handle(
                PlayerCommand.ShedLoad(sessionId, loadId = "ld1", fractionToShed = 0.5),
                userId,
            )

        assertThat(result.success).isTrue()
        assertThat(captured.captured.activePowerMw).isEqualTo(50.0)
    }

    @Test
    fun `ShedLoad fraction above 1·0 rejected`() {
        val result =
            handler.handle(
                PlayerCommand.ShedLoad(sessionId, "ld1", fractionToShed = 1.5),
                userId,
            )
        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("out of range")
    }

    // ── TripElement ───────────────────────────────────────────────────────────

    @Test
    fun `TripElement LINE produces TripLine mutation`() {
        val captured = slot<NetworkMutation.TripLine>()
        every { networkMapper.applyMutation(any(), capture(captured)) } returns Result.success(session.iidmNetwork)

        val result =
            handler.handle(
                PlayerCommand.TripElement(sessionId, "l1", EquipmentType.LINE),
                userId,
            )

        assertThat(result.success).isTrue()
        assertThat(captured.captured.lineId).isEqualTo("l1")
    }

    @Test
    fun `TripElement unknown line rejected`() {
        val result =
            handler.handle(
                PlayerCommand.TripElement(sessionId, "no-such-line", EquipmentType.LINE),
                userId,
            )
        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("not found")
    }

    @Test
    fun `TripElement BUS rejected`() {
        val result =
            handler.handle(
                PlayerCommand.TripElement(sessionId, "b1", EquipmentType.BUS),
                userId,
            )
        assertThat(result.success).isFalse()
    }

    // ── Clock commands ────────────────────────────────────────────────────────

    @Test
    fun `PauseClock delegates to TickEngine and skips mutation pipeline`() {
        val result = handler.handle(PlayerCommand.PauseClock(sessionId), userId)

        assertThat(result.success).isTrue()
        assertThat(result.snapshot).isSameAs(snapshot)
        verify { tickEngine.pause(sessionId, userId) }
        verify(exactly = 0) { networkMapper.applyMutation(any(), any()) }
    }

    @Test
    fun `SetClockSpeed valid multiplier delegates to TickEngine`() {
        val result = handler.handle(PlayerCommand.SetClockSpeed(sessionId, multiplier = 5), userId)
        assertThat(result.success).isTrue()
        verify { tickEngine.setSpeed(sessionId, userId, 5) }
    }

    @Test
    fun `SetClockSpeed zero rejected — TickEngine not called`() {
        val result = handler.handle(PlayerCommand.SetClockSpeed(sessionId, multiplier = 0), userId)
        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("out of range")
        verify(exactly = 0) { tickEngine.setSpeed(any(), any(), any()) }
    }

    // ── RespondToEventCard ────────────────────────────────────────────────────

    @Test
    fun `RespondToEventCard delegates to EventEngine`() {
        val result =
            handler.handle(
                PlayerCommand.RespondToEventCard(sessionId, cardId = "card-uuid", optionId = "1"),
                userId,
            )
        assertThat(result.success).isTrue()
        verify { eventEngine.resolveCard(sessionId, "card-uuid", 1) }
    }

    // ── CommitGenerator / DecommitGenerator ──────────────────────────────────

    @Test
    fun `CommitGenerator produces ConnectGenerator mutation`() {
        val captured = slot<NetworkMutation.ConnectGenerator>()
        every { networkMapper.applyMutation(any(), capture(captured)) } returns Result.success(session.iidmNetwork)

        val result = handler.handle(PlayerCommand.CommitGenerator(sessionId, "g-off"), userId)

        assertThat(result.success).isTrue()
        assertThat(captured.captured.generatorId).isEqualTo("g-off")
    }

    @Test
    fun `DecommitGenerator rejected when only one generator connected`() {
        session.latestSnapshot = buildMinimalSnapshot(connectedGenerators = listOf("g1"))

        val result = handler.handle(PlayerCommand.DecommitGenerator(sessionId, "g1"), userId)

        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("only connected generator")
    }

    // ── handleBatch ───────────────────────────────────────────────────────────

    @Test
    fun `handleBatch applies all mutations and runs single power flow`() {
        val cmds =
            listOf(
                PlayerCommand.SetGeneratorOutput(sessionId, "g1", 80.0),
                PlayerCommand.SetGeneratorOutput(sessionId, "g1", 90.0),
            )

        val result = handler.handleBatch(cmds, userId)

        assertThat(result.success).isTrue()
        assertThat(result.commandOutcomes).hasSize(2)
        assertThat(result.commandOutcomes.all { it.success }).isTrue()
        verify(exactly = 2) { networkMapper.applyMutation(any(), any<NetworkMutation.SetGeneratorOutput>()) }
        verify(exactly = 1) { powerFlowService.solve(any(), any()) }
    }

    @Test
    fun `handleBatch rejects entire batch when any command is invalid`() {
        val cmds =
            listOf(
                PlayerCommand.SetGeneratorOutput(sessionId, "g1", 80.0),
                PlayerCommand.SetGeneratorOutput(sessionId, "g1", -999.0),
            )

        val result = handler.handleBatch(cmds, userId)

        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].success).isTrue()
        assertThat(result.commandOutcomes[1].success).isFalse()
        verify(exactly = 0) { networkMapper.applyMutation(any(), any()) }
    }

    // ── applyMutations ────────────────────────────────────────────────────────

    @Test
    fun `applyMutations skips validation and applies directly`() {
        val result = handler.applyMutations(listOf(NetworkMutation.TripLine("l1")), sessionId)

        assertThat(result.success).isTrue()
        verify { networkMapper.applyMutation(any(), NetworkMutation.TripLine("l1")) }
    }

    // ── Topology change → N-1 trigger ────────────────────────────────────────

    @Test
    fun `TripElement triggers async contingency analysis`() {
        handler.handle(PlayerCommand.TripElement(sessionId, "l1", EquipmentType.LINE), userId)
        verify { contingencyService.triggerAsync(any(), any()) }
    }

    @Test
    fun `SetGeneratorOutput does not trigger contingency analysis`() {
        handler.handle(PlayerCommand.SetGeneratorOutput(sessionId, "g1", 80.0), userId)
        verify(exactly = 0) { contingencyService.triggerAsync(any(), any()) }
    }

    // ── Rollback on failure ───────────────────────────────────────────────────

    @Test
    fun `mutation failure rolls back network and returns failure result`() {
        every {
            networkMapper.applyMutation(any(), any<NetworkMutation.TripLine>())
        } returns Result.failure(RuntimeException("Line not found"))

        val originalRef = session.iidmNetwork
        val result = handler.applyMutations(listOf(NetworkMutation.TripLine("l99")), sessionId)

        assertThat(result.success).isFalse()
        // Variant rollback restores state in-place — same object reference, internal state reset.
        assertThat(session.iidmNetwork).isSameAs(originalRef)
    }

    // ── Alerts ────────────────────────────────────────────────────────────────

    @Test
    fun `thermal violation produces ThermalAlert`() {
        every { powerFlowService.solve(any(), any()) } returns pfResultWithThermalViolation()

        val result =
            handler.handle(
                PlayerCommand.SetGeneratorOutput(sessionId, "g1", 80.0),
                userId,
            )

        assertThat(result.newAlerts).hasSize(1)
        assertThat(result.newAlerts[0]).isInstanceOf(Alert.ThermalAlert::class.java)
    }

    @Test
    fun `NETWORK_FAILURE produces ConvergenceAlert — mutation still applied`() {
        every { powerFlowService.solve(any(), any()) } returns pfResultNetworkFailure()

        val result =
            handler.handle(
                PlayerCommand.SetGeneratorOutput(sessionId, "g1", 80.0),
                userId,
            )

        assertThat(result.success).isTrue()
        assertThat(result.newAlerts.any { it is Alert.ConvergenceAlert }).isTrue()
    }

    // ── VoltageAlert ──────────────────────────────────────────────────────────

    @Test
    fun `voltage violation produces VoltageAlert`() {
        every { powerFlowService.solve(any(), any()) } returns pfResultWithVoltageViolation()

        val result =
            handler.handle(
                PlayerCommand.SetGeneratorOutput(sessionId, "g1", 80.0),
                userId,
            )

        assertThat(result.newAlerts).hasSize(1)
        assertThat(result.newAlerts[0]).isInstanceOf(Alert.VoltageAlert::class.java)
        val va = result.newAlerts[0] as Alert.VoltageAlert
        assertThat(va.voltagePu).isEqualTo(0.88)
    }

    // ── Power flow exception rollback ─────────────────────────────────────────

    @Test
    fun `power flow exception rolls back network and returns failure`() {
        every { powerFlowService.solve(any(), any()) } throws RuntimeException("Solver crash")
        val originalRef = session.iidmNetwork

        val result =
            handler.handle(
                PlayerCommand.SetGeneratorOutput(sessionId, "g1", 80.0),
                userId,
            )

        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("Power flow error")
        // Variant rollback restores state in-place — same object reference, internal state reset.
        assertThat(session.iidmNetwork).isSameAs(originalRef)
    }

    // ── ConnectElement ────────────────────────────────────────────────────────

    @Test
    fun `ConnectElement LINE produces ConnectLine mutation`() {
        val captured = slot<NetworkMutation.ConnectLine>()
        every { networkMapper.applyMutation(any(), capture(captured)) } returns Result.success(session.iidmNetwork)

        val result =
            handler.handle(
                PlayerCommand.ConnectElement(sessionId, "l1", EquipmentType.LINE),
                userId,
            )

        assertThat(result.success).isTrue()
        assertThat(captured.captured.lineId).isEqualTo("l1")
    }

    @Test
    fun `ConnectElement unknown line rejected`() {
        val result =
            handler.handle(
                PlayerCommand.ConnectElement(sessionId, "no-such-line", EquipmentType.LINE),
                userId,
            )
        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("not found")
    }

    // ── ResumeClock ───────────────────────────────────────────────────────────

    @Test
    fun `ResumeClock delegates to TickEngine and skips mutation pipeline`() {
        val result = handler.handle(PlayerCommand.ResumeClock(sessionId), userId)
        assertThat(result.success).isTrue()
        verify { tickEngine.resume(sessionId, userId) }
        verify(exactly = 0) { networkMapper.applyMutation(any(), any()) }
    }

    // ── ApplyUcSchedule ───────────────────────────────────────────────────────

    @Test
    fun `ApplyUcSchedule commit produces ConnectGenerator and SetGeneratorOutput`() {
        val captured = mutableListOf<NetworkMutation>()
        every { networkMapper.applyMutation(any(), capture(captured)) } returns Result.success(session.iidmNetwork)

        val result =
            handler.handle(
                PlayerCommand.ApplyUcSchedule(
                    sessionId,
                    listOf(GeneratorSchedule("g-off", committed = true, targetMw = 75.0)),
                ),
                userId,
            )

        assertThat(result.success).isTrue()
        assertThat(captured).hasSize(2)
        assertThat(captured[0]).isInstanceOf(NetworkMutation.ConnectGenerator::class.java)
        val setOut = captured[1] as NetworkMutation.SetGeneratorOutput
        assertThat(setOut.targetPMw).isEqualTo(75.0)
    }

    @Test
    fun `ApplyUcSchedule unknown generator rejected`() {
        val result =
            handler.handle(
                PlayerCommand.ApplyUcSchedule(
                    sessionId,
                    listOf(GeneratorSchedule("nonexistent", committed = true)),
                ),
                userId,
            )
        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("not found")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildMinimalSnapshot(connectedGenerators: List<String> = listOf("g1", "g2")): GridNetwork {
        val gens =
            connectedGenerators.map { id ->
                Generator(
                    id = id,
                    name = id,
                    busId = "b1",
                    minActivePowerMw = 0.0,
                    maxActivePowerMw = 200.0,
                    targetActivePowerMw = 100.0,
                    targetReactivePowerMvar = 0.0,
                    targetVoltagePu = 1.0,
                    connected = true,
                    fuelType = FuelType.GAS,
                    marginalCostPerMwh = 30.0,
                )
            } +
                listOf(
                    Generator(
                        id = "g-off",
                        name = "g-off",
                        busId = "b1",
                        minActivePowerMw = 0.0,
                        maxActivePowerMw = 100.0,
                        targetActivePowerMw = 0.0,
                        targetReactivePowerMvar = 0.0,
                        targetVoltagePu = 1.0,
                        connected = false,
                        fuelType = FuelType.COAL,
                        marginalCostPerMwh = 50.0,
                    ),
                )
        return GridNetwork(
            id = sessionId,
            name = "Test",
            buses = listOf(Bus("b1", "Bus 1", 400.0)),
            lines =
                listOf(
                    Line(
                        id = "l1",
                        name = "Line 1",
                        fromBusId = "b1",
                        toBusId = "b1",
                        resistanceOhm = 0.01,
                        reactanceOhm = 0.1,
                        shuntCapacitanceSiemens = 0.0,
                    ),
                ),
            twoWindingsTransformers = emptyList(),
            threeWindingsTransformers = emptyList(),
            generators = gens,
            loads =
                listOf(
                    Load("ld1", "Load 1", "b1", activePowerMw = 100.0, reactivePowerMvar = 10.0, connected = true),
                ),
            shuntCompensators = emptyList(),
            snapshotAt = Instant.now(),
        )
    }

    private fun successPfResult() =
        PowerFlowResult(
            status = ConvergenceStatus.CONVERGED,
            solveMode = SolveMode.AC,
            iterationCount = 3,
            snapshot = snapshot,
            slackBusIds = listOf("b1"),
            violations = emptyList(),
            solveTimeMs = 10,
        )

    private fun pfResultWithThermalViolation() =
        PowerFlowResult(
            status = ConvergenceStatus.CONVERGED,
            solveMode = SolveMode.AC,
            iterationCount = 3,
            snapshot = snapshot,
            slackBusIds = listOf("b1"),
            violations =
                listOf(
                    NetworkViolation.ThermalViolation(
                        equipmentId = "l1",
                        equipmentType = EquipmentType.LINE,
                        currentA = 550.0,
                        ratingA = 500.0,
                        loadingPercent = 110.0,
                        severity = ViolationSeverity.ALARM,
                    ),
                ),
            solveTimeMs = 12,
        )

    private fun pfResultNetworkFailure() =
        PowerFlowResult(
            status = ConvergenceStatus.NETWORK_FAILURE,
            solveMode = SolveMode.AC,
            iterationCount = 0,
            snapshot = snapshot,
            slackBusIds = emptyList(),
            violations = emptyList(),
            solveTimeMs = 5,
        )

    private fun pfResultWithVoltageViolation() =
        PowerFlowResult(
            status = ConvergenceStatus.CONVERGED,
            solveMode = SolveMode.AC,
            iterationCount = 3,
            snapshot = snapshot,
            slackBusIds = listOf("b1"),
            violations =
                listOf(
                    NetworkViolation.VoltageViolation(
                        busId = "b1",
                        voltagePu = 0.88,
                        limitMinPu = 0.95,
                        limitMaxPu = 1.05,
                        severity = ViolationSeverity.WARNING,
                    ),
                ),
            solveTimeMs = 8,
        )
}
