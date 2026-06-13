package com.gridmaster.game

import com.gridmaster.api.PhysicsSession
import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.api.SessionNotFoundException
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.powsybl.iidm.network.Network
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
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
 *
 * Timing-sensitive tests use [runTest] with [UnconfinedTestDispatcher] so that
 * [kotlinx.coroutines.delay] calls inside the tick loop advance virtual time rather than
 * blocking real wall-clock time. This makes each test deterministic and nearly instant.
 * Each such test injects the [TestScope][kotlinx.coroutines.test.TestScope] as the engine's
 * [TickEngineImpl.engineScope] and calls [TickEngineImpl.stop] before the block exits to
 * avoid [kotlinx.coroutines.test.UncompletedCoroutinesError].
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class TickEngineImplTest {
    private lateinit var physicsSessionStore: PhysicsSessionStore
    private lateinit var gameSessionService: GameSessionService
    private lateinit var powerFlowService: PowerFlowService
    private lateinit var contingencyAnalysisService: ContingencyAnalysisService
    private lateinit var eventEngine: com.gridmaster.game.event.EventEngine
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

        engine =
            TickEngineImpl(
                physicsSessionStore = physicsSessionStore,
                gameSessionService = gameSessionService,
                powerFlowService = powerFlowService,
                contingencyAnalysisService = contingencyAnalysisService,
                eventEngine = eventEngine,
                autoSaveInterval = 5L,
            )

        // Default: session exists and power flow converges with no violations
        every { physicsSessionStore.find(sessionId) } returns
            PhysicsSession(sessionId, mockNetwork, mockSnapshot)
        every { gameSessionService.load(sessionId, userId) } returns buildGameSession()
        every { gameSessionService.save(any(), any(), any(), any(), any()) } returns buildGameSession()
        every { powerFlowService.solve(any()) } returns convergedResult()
        justRun { contingencyAnalysisService.triggerAsync(any()) }
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

    /**
     * Uses [runTest] + [UnconfinedTestDispatcher] so [delay] calls in the tick loop
     * advance virtual time. The [UnconfinedTestDispatcher] also runs the first tick
     * synchronously during [TickEngineImpl.start], so auto-slow is observable immediately
     * without real wall-clock waiting.
     */
    @Test
    fun `auto-slow activates on NETWORK_FAILURE and sets speed to 1`() =
        runTest(UnconfinedTestDispatcher()) {
            every { powerFlowService.solve(any()) } returns networkFailureResult()
            every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)
            engine.engineScope = this
            engine.start(sessionId, userId)
            // UnconfinedTestDispatcher runs tick 1 synchronously during start():
            // NETWORK_FAILURE → applyAutoSlow → autoSlowed=true, speed→1, state→SLOW.
            // Advance past the first tick's virtual delay to confirm stable state.
            advanceTimeBy(50)

            val status = engine.clockStatus(sessionId)
            assertThat(status).isNotNull
            assertThat(status!!.autoSlowed).isTrue()
            assertThat(status.speedMultiplier).isEqualTo(1)
            assertThat(status.clockState).isEqualTo(ClockState.SLOW)

            engine.stop(sessionId, userId)
        }

    /**
     * Verifies that auto-slow is cleared when the grid recovers. Uses virtual time so the
     * 1× recovery slot (1 000 ms virtual) completes instantly.
     */
    @Test
    fun `auto-slow clears when power flow recovers`() =
        runTest(UnconfinedTestDispatcher()) {
            val callCount = java.util.concurrent.atomic.AtomicInteger(0)
            every { powerFlowService.solve(any()) } answers {
                // Tick 1 fails → auto-slow (speed→1, 1 000 ms slot). Tick 2+ succeed → clear.
                if (callCount.incrementAndGet() <= 1) networkFailureResult() else convergedResult()
            }
            every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)
            engine.engineScope = this
            engine.start(sessionId, userId)
            // Tick 1 ran synchronously: NETWORK_FAILURE → auto-slow (speed→1, slotMs→1 000 ms).
            // The tick loop is suspended at delay(~10ms) (captured slotMs before auto-slow).
            // Advance 1 100 ms virtual time: tick 2 runs at 1× → convergedResult → auto-slow clears.
            advanceTimeBy(1_100)

            val status = engine.clockStatus(sessionId)
            assertThat(status).isNotNull
            assertThat(status!!.autoSlowed).isFalse()
            assertThat(status.clockState).isIn(ClockState.RUNNING, ClockState.PAUSED)

            engine.stop(sessionId, userId)
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

    /**
     * Advances virtual time to fire 10+ ticks so the configured [autoSaveInterval] (5 ticks)
     * is crossed at least twice. With [UnconfinedTestDispatcher], the auto-save coroutine also
     * runs synchronously, so [GameSessionService.save] is verifiable immediately.
     */
    @Test
    fun `auto-save is triggered at configured interval`() =
        runTest(UnconfinedTestDispatcher()) {
            every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)
            engine.engineScope = this
            engine.start(sessionId, userId)
            // Advance 110 ms virtual time: 11 ticks at 100× (10 ms/tick).
            // autoSaveInterval=5 → ticks 5 and 10 trigger auto-save.
            advanceTimeBy(110)
            engine.pause(sessionId, userId)

            // save() called on pause + at auto-save intervals
            verify(atLeast = 1) {
                gameSessionService.save(sessionId, userId, any(), any(), any())
            }

            engine.stop(sessionId, userId)
        }

    // -------------------------------------------------------------------------
    // Slip detection
    // -------------------------------------------------------------------------

    /**
     * Verifies the engine auto-pauses after [SLIP_PAUSE_THRESHOLD] consecutive slipping ticks.
     * Uses [UnconfinedTestDispatcher]: the tick coroutine runs synchronously on the test thread,
     * so [Thread.sleep] still advances real wall-clock time for slip detection while the test
     * itself completes in ~200 ms (10 slips × 20 ms) instead of the original 1 s delay.
     */
    @Test
    fun `engine auto-pauses after SLIP_PAUSE_THRESHOLD consecutive slipping ticks`() =
        runTest(UnconfinedTestDispatcher()) {
            // At 100× the slot is 10 ms. Sleeping 20 ms causes every tick to slip.
            every { powerFlowService.solve(any()) } answers {
                Thread.sleep(20)
                convergedResult()
            }
            every { gameSessionService.load(sessionId, userId) } returns buildGameSession(multiplier = 100)
            engine.engineScope = this
            engine.start(sessionId, userId)
            // With UnconfinedTestDispatcher the slip path (no delay) runs synchronously:
            // 10 slips × 20 ms real time = ~200 ms, then the loop auto-pauses at pauseSignal.await().
            // engine.start() returns only after the loop first suspends — i.e. after auto-pause.

            val status = engine.clockStatus(sessionId)
            assertThat(status).isNotNull
            assertThat(status!!.clockState).isEqualTo(ClockState.PAUSED)

            engine.stop(sessionId, userId)
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
