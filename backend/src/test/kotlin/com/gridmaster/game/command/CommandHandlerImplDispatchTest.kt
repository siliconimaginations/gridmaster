package com.gridmaster.game.command

import com.gridmaster.api.PhysicsSession
import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.dispatch.DispatchResult
import com.gridmaster.engine.dispatch.DispatchService
import com.gridmaster.engine.dispatch.GeneratorTarget
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
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.SolveMode
import com.gridmaster.game.TickEngine
import com.gridmaster.game.event.EventEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for [CommandHandlerImpl] focused on [PlayerCommand.RunEconomicDispatch].
 *
 * Covers the file-scope [GridNetwork.toDispatchableGenerators] extension function
 * (compiled as `CommandHandlerImplKt`) which was 0% covered by the existing test suite.
 */
class CommandHandlerImplDispatchTest {
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

    private val sessionId = "session-dispatch-1"
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

        snapshot = buildSnapshot()
        session = PhysicsSession(sessionId, TestNetworkFactory.create(), snapshot)

        every { sessionStore.get(sessionId) } returns session
        every { powerFlowService.solve(any(), any()) } returns successPf()
        every { networkMapper.applyMutation(any(), any()) } returns Result.success(session.iidmNetwork)

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

    @Test
    fun `RunEconomicDispatch calls dispatchService and applies generator targets as mutations`() {
        val capturedMutations = mutableListOf<NetworkMutation>()
        val totalLoadSlot = slot<Double>()
        every { networkMapper.applyMutation(any(), capture(capturedMutations)) } returns Result.success(session.iidmNetwork)
        every { dispatchService.economicDispatch(any(), capture(totalLoadSlot), any()) } returns singleTargetResult("g1", 90.0)

        val result = handler.handle(PlayerCommand.RunEconomicDispatch(sessionId, totalLoadMw = 90.0), userId)

        assertThat(result.success).isTrue()
        assertThat(totalLoadSlot.captured).isEqualTo(90.0) // exact totalLoadMw forwarded to dispatch service (#258)
        assertThat(capturedMutations).hasSize(1)
        val mutation = capturedMutations[0] as NetworkMutation.SetGeneratorOutput
        assertThat(mutation.generatorId).isEqualTo("g1")
        assertThat(mutation.targetPMw).isEqualTo(90.0)
    }

    @Test
    fun `RunEconomicDispatch with multiple targets applies all mutations in order`() {
        val capturedMutations = mutableListOf<NetworkMutation>()
        val generatorsSlot = slot<List<com.gridmaster.engine.dispatch.DispatchableGenerator>>()
        every { networkMapper.applyMutation(any(), capture(capturedMutations)) } returns Result.success(session.iidmNetwork)
        every { dispatchService.economicDispatch(capture(generatorsSlot), any(), any()) } returns
            multiTargetResult(listOf("g1" to 80.0, "g2" to 50.0), 130.0)

        val result = handler.handle(PlayerCommand.RunEconomicDispatch(sessionId, totalLoadMw = 130.0), userId)

        assertThat(result.success).isTrue()
        assertThat(generatorsSlot.captured).hasSize(2) // snapshot has exactly 2 generators (#258)
        assertThat(generatorsSlot.captured.map { it.id }).containsExactly("g1", "g2")
        assertThat(capturedMutations).hasSize(2)
        assertThat((capturedMutations[0] as NetworkMutation.SetGeneratorOutput).generatorId).isEqualTo("g1")
        assertThat((capturedMutations[1] as NetworkMutation.SetGeneratorOutput).generatorId).isEqualTo("g2")
    }

    @Test
    fun `RunEconomicDispatch with zero totalLoadMw is rejected before dispatch service is called`() {
        val result = handler.handle(PlayerCommand.RunEconomicDispatch(sessionId, totalLoadMw = 0.0), userId)

        assertThat(result.success).isFalse()
        assertThat(result.commandOutcomes[0].rejectionReason).contains("positive")
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun singleTargetResult(
        generatorId: String,
        targetMw: Double,
    ) = DispatchResult(
        targets = listOf(GeneratorTarget(generatorId, targetMw)),
        meritOrder = emptyList(),
        totalLoadMw = targetMw,
        totalDispatchedMw = targetMw,
        systemMarginalCostPerMwh = 30.0,
        unservedLoadMw = 0.0,
        dispatchedAt = Instant.now(),
    )

    private fun multiTargetResult(
        targets: List<Pair<String, Double>>,
        totalMw: Double,
    ) = DispatchResult(
        targets = targets.map { (id, mw) -> GeneratorTarget(id, mw) },
        meritOrder = emptyList(),
        totalLoadMw = totalMw,
        totalDispatchedMw = totalMw,
        systemMarginalCostPerMwh = 35.0,
        unservedLoadMw = 0.0,
        dispatchedAt = Instant.now(),
    )

    private fun buildSnapshot() =
        GridNetwork(
            id = sessionId,
            name = "Dispatch Test",
            buses = listOf(Bus("b1", "Bus 1", 220.0)),
            lines =
                listOf(Line("l1", "L1", "b1", "b1", resistanceOhm = 0.01, reactanceOhm = 0.1, shuntCapacitanceSiemens = 0.0)),
            twoWindingsTransformers = emptyList(),
            threeWindingsTransformers = emptyList(),
            generators =
                listOf(
                    Generator("g1", "G1", "b1", 0.0, 200.0, 100.0, 0.0, 1.0, true, FuelType.GAS, 30.0),
                    Generator("g2", "G2", "b1", 0.0, 150.0, 80.0, 0.0, 1.0, true, FuelType.COAL, 40.0),
                ),
            loads = listOf(Load("ld1", "Load 1", "b1", 100.0, 10.0, true)),
            shuntCompensators = emptyList(),
            snapshotAt = Instant.now(),
        )

    private fun successPf() =
        PowerFlowResult(
            status = ConvergenceStatus.CONVERGED,
            solveMode = SolveMode.AC,
            iterationCount = 3,
            snapshot = snapshot,
            slackBusIds = listOf("b1"),
            violations = emptyList(),
            solveTimeMs = 10,
        )
}
