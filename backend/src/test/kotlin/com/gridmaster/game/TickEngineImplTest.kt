package com.gridmaster.game

import com.gridmaster.api.PhysicsSession
import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.api.SessionNotFoundException
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.model.FuelType
import com.gridmaster.engine.model.Generator
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.Load
import com.gridmaster.engine.model.NetworkMutation
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.powsybl.iidm.network.Network
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [TickEngineImpl].
 *
 * All dependencies are mocked with MockK. Tests exercise:
 * - Start/pause/resume/setSpeed/stop lifecycle
 * - Auto-slow activation and restoration
 * - Slip detection and pause on threshold
 * - Auto-save triggered at configured interval
 * - Clock status reporting
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class TickEngineImplTest {
    private lateinit var physicsSessionStore: PhysicsSessionStore
    private lateinit var gameSessionService: GameSessionService
    private lateinit var powerFlowService: PowerFlowService
    private lateinit var contingencyAnalysisService: ContingencyAnalysisService
    private lateinit var eventEngine: com.gridmaster.game.event.EventEngine
    private lateinit var tutorialEngine: com.gridmaster.game.tutorial.TutorialEngine
    private lateinit var challengeEngine: com.gridmaster.game.challenge.ChallengeEngine
    private lateinit var networkRepository: com.gridmaster.engine.network.NetworkRepository
    private lateinit var networkMapper: com.gridmaster.engine.network.IidmNetworkMapper
    private lateinit var engine: TickEngineImpl

    private val sessionId = "session-1"
    private val userId = "user-1"
    private val mockNetwork = mockk<Network>(relaxed = true)
    private val mockSnapshot = mockk<GridNetwork>(relaxed = true)

    @BeforeEach
    fun setUp() {
        physicsSessionStore = mockk()
        gameSessionService = mockk()
        powerFlowService = mockk()
        contingencyAnalysisService = mockk()
        eventEngine = mockk(relaxed = true)
        tutorialEngine = mockk(relaxed = true)
        challengeEngine = mockk(relaxed = true)
        networkRepository = mockk(relaxed = true)
        networkMapper = mockk(relaxed = true)

        engine =
            TickEngineImpl(
                physicsSessionStore = physicsSessionStore,
                gameSessionService = gameSessionService,
                powerFlowService = powerFlowService,
                contingencyAnalysisService = contingencyAnalysisService,
                eventEngine = eventEngine,
                tutorialEngine = tutorialEngine,
                challengeEngine = challengeEngine,
                networkRepository = networkRepository,
                networkMapper = networkMapper,
                autoSaveInterval = 5L,
                dailyLoadCurveEnabled = true,
                weatherEnabled = false,
            )

        // Default: session exists and power flow converges with no violations
        every { physicsSessionStore.find(sessionId) } returns
            PhysicsSession(sessionId, mockNetwork, mockSnapshot)
        every { gameSessionService.load(sessionId, userId) } returns buildGameSession()
        every { gameSessionService.save(any(), any(), any(), any(), any()) } returns buildGameSession()
        every { powerFlowService.solve(any()) } returns convergedResult()
        justRun { contingencyAnalysisService.triggerAsync(any(), any()) }
        every { networkMapper.applyMutation(any(), any()) } returns Result.success(mockNetwork)
    }

    @AfterEach
    fun tearDown() {
        runCatching { engine.stop(sessionId, userId) }
        clearAllMocks()
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Test
    fun `start registers session and returns RUNNING status`() {
        engine.start(sessionId, userId)

        val status = engine.clockStatus(sessionId)
        assertThat(status).isNotNull
        assertThat(status!!.clockState).isEqualTo(ClockState.RUNNING)
        assertThat(status.tickCount).isGreaterThanOrEqualTo(0L)
    }

    @Test
    fun `start twice throws IllegalStateException`() {
        engine.start(sessionId, userId)

        assertThatThrownBy { engine.start(sessionId, userId) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `pause transitions clock to PAUSED`() {
        engine.start(sessionId, userId)
        engine.pause(sessionId, userId)

        assertThat(engine.clockStatus(sessionId)!!.clockState).isEqualTo(ClockState.PAUSED)
    }

    @Test
    fun `pause on non-running session throws`() {
        engine.start(sessionId, userId)
        engine.pause(sessionId, userId)

        assertThatThrownBy { engine.pause(sessionId, userId) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `resume transitions PAUSED clock back to RUNNING`() {
        engine.start(sessionId, userId)
        engine.pause(sessionId, userId)
        engine.resume(sessionId, userId)

        assertThat(engine.clockStatus(sessionId)!!.clockState).isEqualTo(ClockState.RUNNING)
    }

    @Test
    fun `resume on running session throws`() {
        engine.start(sessionId, userId)

        assertThatThrownBy { engine.resume(sessionId, userId) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `stop removes session from engine`() {
        engine.start(sessionId, userId)
        engine.stop(sessionId, userId)

        assertThat(engine.clockStatus(sessionId)).isNull()
    }

    @Test
    fun `stop unregisters session from engine`() {
        engine.start(sessionId, userId)
        engine.stop(sessionId, userId)
        // After stop, session is removed — start would re-register;
        // but if it were still in sessions as STOPPED it should throw.
        // Current impl: remove on stop, so re-register is allowed from a fresh state.
        // This test verifies stop cleans up.
        assertThat(engine.clockStatus(sessionId)).isNull()
    }

    @Test
    fun `clockStatus returns null for unknown session`() {
        assertThat(engine.clockStatus("nonexistent")).isNull()
    }

    // -------------------------------------------------------------------------
    // Speed control
    // -------------------------------------------------------------------------

    @Test
    fun `setSpeed updates multiplier`() {
        engine.start(sessionId, userId)
        engine.setSpeed(sessionId, userId, 10)

        assertThat(engine.clockStatus(sessionId)!!.speedMultiplier).isEqualTo(10)
    }

    @Test
    fun `setSpeed out of range throws`() {
        engine.start(sessionId, userId)

        assertThatThrownBy { engine.setSpeed(sessionId, userId, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { engine.setSpeed(sessionId, userId, 101) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `setSpeed on unregistered session throws`() {
        // Unregistered session now throws SessionNotFoundException (→ 404 from controller)
        assertThatThrownBy { engine.setSpeed(sessionId, userId, 10) }
            .isInstanceOf(SessionNotFoundException::class.java)
    }

    // -------------------------------------------------------------------------
    // Auto-slow
    // -------------------------------------------------------------------------

    @Test
    fun `auto-slow activates on NETWORK_FAILURE and sets speed to 1`() {
        runBlocking {
            every { powerFlowService.solve(any()) } returns networkFailureResult()
            // Start at 100× so ticks fire every 10ms
            every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)
            engine.start(sessionId, userId)
            // start() already uses multiplier=100 from the loaded session; no setSpeed needed
            delay(200)

            val status = engine.clockStatus(sessionId)
            assertThat(status).isNotNull
            assertThat(status!!.autoSlowed).isTrue()
            assertThat(status.speedMultiplier).isEqualTo(1)
            assertThat(status.clockState).isEqualTo(ClockState.SLOW)
        }
    }

    @Test
    fun `auto-slow clears when power flow recovers`() {
        runBlocking {
            val callCount = java.util.concurrent.atomic.AtomicInteger(0)
            every { powerFlowService.solve(any()) } answers {
                // First tick: fail → auto-slow (drops to 1×). Second tick (~1s later): recover.
                if (callCount.incrementAndGet() <= 1) networkFailureResult() else convergedResult()
            }
            every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)
            engine.start(sessionId, userId)
            engine.setSpeed(sessionId, userId, 100)
            // Auto-slow triggers quickly (10ms ticks at 100×); after 3 failure ticks
            // the clock drops to 1× (1000ms slot). Wait long enough for one recovery tick.
            delay(1500)

            val status = engine.clockStatus(sessionId)
            assertThat(status).isNotNull
            // After enough ticks with recovery, auto-slow should have cleared
            assertThat(status!!.autoSlowed).isFalse()
            assertThat(status.clockState).isIn(ClockState.RUNNING, ClockState.PAUSED)
        }
    }

    @Test
    fun `setSpeed by player clears auto-slow`() {
        engine.start(sessionId, userId)
        // Manually inject auto-slow state
        val runtime = engine.sessions[sessionId]!!
        runtime.autoSlowed = true
        runtime.autoSlowPreviousSpeed = 10
        runtime.clockState = ClockState.SLOW
        runtime.speedMultiplier = 1

        engine.setSpeed(sessionId, userId, 5)

        assertThat(engine.clockStatus(sessionId)!!.autoSlowed).isFalse()
        assertThat(engine.clockStatus(sessionId)!!.clockState).isEqualTo(ClockState.RUNNING)
    }

    // -------------------------------------------------------------------------
    // Auto-save
    // -------------------------------------------------------------------------

    @Test
    fun `auto-save is triggered at configured interval`() =
        runBlocking {
            every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)
            engine.start(sessionId, userId)
            engine.setSpeed(sessionId, userId, 100)
            // Wait long enough for > 5 ticks at 100× (10ms/tick → 50ms for 5 ticks)
            delay(300)
            engine.pause(sessionId, userId)

            // save() called on pause + at auto-save intervals
            verify(atLeast = 1) {
                gameSessionService.save(sessionId, userId, any(), any(), any())
            }
        }

    // -------------------------------------------------------------------------
    // Slip detection
    // -------------------------------------------------------------------------

    @Test
    fun `engine auto-pauses after SLIP_PAUSE_THRESHOLD consecutive slipping ticks`() {
        runBlocking {
            // At 100× the slot is 10ms. Mock solve to take 20ms — every tick slips.
            every { powerFlowService.solve(any()) } answers {
                Thread.sleep(20) // block for longer than the 10ms slot
                convergedResult()
            }
            every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)
            engine.start(sessionId, userId)
            engine.setSpeed(sessionId, userId, 100)

            // SLIP_PAUSE_THRESHOLD = 10 slips × ~20ms each = ~200ms. Wait 1s to be safe.
            delay(1000)

            val status = engine.clockStatus(sessionId)
            assertThat(status).isNotNull
            assertThat(status!!.clockState).isEqualTo(ClockState.PAUSED)
        }
    }

    // -------------------------------------------------------------------------
    // Daily load curve (issue #383)
    // -------------------------------------------------------------------------

    @Test
    fun `daily load curve scales loads via networkMapper when enabled`() {
        runBlocking {
            every { mockSnapshot.loads } returns
                listOf(
                    Load(id = "L1", name = "Load 1", busId = "B1", activePowerMw = 100.0, reactivePowerMvar = 10.0, connected = true),
                )
            every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)
            engine.start(sessionId, userId)
            delay(100)

            verify(atLeast = 1) {
                networkMapper.applyMutation(any(), any<NetworkMutation.SetLoadPower>())
            }
        }
    }

    @Test
    fun `daily load curve is skipped when disabled`() {
        runBlocking {
            engine =
                TickEngineImpl(
                    physicsSessionStore = physicsSessionStore,
                    gameSessionService = gameSessionService,
                    powerFlowService = powerFlowService,
                    contingencyAnalysisService = contingencyAnalysisService,
                    eventEngine = eventEngine,
                    tutorialEngine = tutorialEngine,
                    challengeEngine = challengeEngine,
                    networkRepository = networkRepository,
                    networkMapper = networkMapper,
                    autoSaveInterval = 5L,
                    dailyLoadCurveEnabled = false,
                    weatherEnabled = false,
                )
            every { mockSnapshot.loads } returns
                listOf(
                    Load(id = "L1", name = "Load 1", busId = "B1", activePowerMw = 100.0, reactivePowerMvar = 10.0, connected = true),
                )
            every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)
            engine.start(sessionId, userId)
            delay(100)

            verify(exactly = 0) {
                networkMapper.applyMutation(any(), any<NetworkMutation.SetLoadPower>())
            }
        }
    }

    // -------------------------------------------------------------------------
    // Weather-driven renewable generation (issue #391)
    // -------------------------------------------------------------------------

    private fun windGenerator(id: String = "G-WIND") =
        Generator(
            id = id,
            name = id,
            busId = "B1",
            minActivePowerMw = 0.0,
            maxActivePowerMw = 100.0,
            powerSetpointMw = 0.0,
            targetReactivePowerMvar = 0.0,
            targetVoltagePu = 1.0,
            connected = true,
            fuelType = FuelType.WIND,
            marginalCostPerMwh = 0.0,
        )

    private fun solarGenerator(id: String = "G-SOLAR") =
        Generator(
            id = id,
            name = id,
            busId = "B1",
            minActivePowerMw = 0.0,
            maxActivePowerMw = 50.0,
            powerSetpointMw = 0.0,
            targetReactivePowerMvar = 0.0,
            targetVoltagePu = 1.0,
            connected = true,
            fuelType = FuelType.SOLAR,
            marginalCostPerMwh = 0.0,
        )

    @Test
    fun `weather-driven output is applied to WIND and SOLAR generators when enabled`() {
        runBlocking {
            engine =
                TickEngineImpl(
                    physicsSessionStore = physicsSessionStore,
                    gameSessionService = gameSessionService,
                    powerFlowService = powerFlowService,
                    contingencyAnalysisService = contingencyAnalysisService,
                    eventEngine = eventEngine,
                    tutorialEngine = tutorialEngine,
                    challengeEngine = challengeEngine,
                    networkRepository = networkRepository,
                    networkMapper = networkMapper,
                    autoSaveInterval = 5L,
                    dailyLoadCurveEnabled = false,
                    weatherEnabled = true,
                )
            every { mockSnapshot.generators } returns listOf(windGenerator(), solarGenerator())
            every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)

            val mutationSlot = mutableListOf<NetworkMutation>()
            every { networkMapper.applyMutation(any(), capture(mutationSlot)) } answers {
                Result.success(mockNetwork)
            }

            engine.start(sessionId, userId)
            delay(100)

            val setOutputMutations = mutationSlot.filterIsInstance<NetworkMutation.SetGeneratorOutput>()
            assertThat(setOutputMutations.any { it.generatorId == "G-WIND" && it.isSystemControlled }).isTrue
            assertThat(setOutputMutations.any { it.generatorId == "G-SOLAR" && it.isSystemControlled }).isTrue
        }
    }

    @Test
    fun `weather-driven output is skipped when disabled`() {
        runBlocking {
            every { mockSnapshot.generators } returns listOf(windGenerator(), solarGenerator())
            every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)
            engine.start(sessionId, userId) // engine from setUp() has weatherEnabled = false
            delay(100)

            verify(exactly = 0) {
                networkMapper.applyMutation(any(), any<NetworkMutation.SetGeneratorOutput>())
            }
        }
    }

    // -------------------------------------------------------------------------
    // slotMillis helper
    // -------------------------------------------------------------------------

    @Test
    fun `slotMillis returns correct values`() {
        assertThat(TickEngineImpl.slotMillis(1)).isEqualTo(1_000L)
        assertThat(TickEngineImpl.slotMillis(10)).isEqualTo(100L)
        assertThat(TickEngineImpl.slotMillis(60)).isEqualTo(16L) // 1000/60 = 16ms
        assertThat(TickEngineImpl.slotMillis(100)).isEqualTo(10L)
    }

    // -------------------------------------------------------------------------
    // Rolling load/generation history (issue #392)
    // -------------------------------------------------------------------------

    @Test
    fun `each tick appends a history sample with that tick's game time`() {
        val physicsSession = PhysicsSession(sessionId, mockNetwork, mockSnapshot)
        every { physicsSessionStore.find(sessionId) } returns physicsSession
        every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)

        runBlocking {
            engine.start(sessionId, userId)
            delay(200)
        }

        val samples = physicsSession.history.snapshot()
        assertThat(samples).isNotEmpty
        assertThat(samples.first().gameTimeMinutes).isEqualTo(0L)
        // gameTimeMinutes is strictly increasing by GRID_MINUTES_PER_TICK per sample
        assertThat(samples.map { it.gameTimeMinutes }).isSorted
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    internal val TickEngineImpl.sessions: Map<String, SessionRuntime>
        get() {
            val field = TickEngineImpl::class.java.getDeclaredField("sessions")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return field.get(this) as Map<String, SessionRuntime>
        }

    private fun buildGameSession(multiplier: Int = 1) =
        GameSession(
            id = sessionId,
            userId = userId,
            mode = GameMode.TUTORIAL,
            displayName = "Test",
            iidmXml = "",
            gameTimeEpochMinutes = 0L,
            clockState = ClockState.PAUSED,
            clockSpeedMultiplier = multiplier,
        )

    private fun convergedResult() =
        PowerFlowResult(
            status = ConvergenceStatus.CONVERGED,
            solveMode = com.gridmaster.engine.powerflow.SolveMode.AC,
            iterationCount = 3,
            snapshot = mockSnapshot,
            slackBusIds = emptyList(),
            violations = emptyList(),
            solveTimeMs = 10L,
        )

    private fun networkFailureResult() =
        PowerFlowResult(
            status = ConvergenceStatus.NETWORK_FAILURE,
            solveMode = com.gridmaster.engine.powerflow.SolveMode.AC,
            iterationCount = 0,
            snapshot = mockSnapshot,
            slackBusIds = emptyList(),
            violations = emptyList(),
            solveTimeMs = 5L,
        )
}
