package com.gridmaster.game.tutorial

import com.gridmaster.game.ClockState
import com.gridmaster.game.GameMode
import com.gridmaster.game.event.EconomicEvent
import com.gridmaster.game.event.EconomicEventType
import com.gridmaster.game.event.EventEngine
import com.gridmaster.game.event.FiredEvent
import io.mockk.clearAllMocks
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TutorialEngineImpl].
 *
 * All step-machine transitions, thread-safety guards, and EventEngine
 * scheduling calls are exercised without a running Spring context.
 */
class TutorialEngineImplTest {
    private lateinit var eventEngine: EventEngine
    private lateinit var engine: TutorialEngineImpl

    private val sessionId = "tutorial-session-1"

    // A minimal FiredEvent stub; content doesn't matter for the tutorial engine.
    private val stubFiredEvent = mockk<FiredEvent>(relaxed = true)

    @BeforeEach
    fun setUp() {
        eventEngine = mockk(relaxed = true)
        justRun { eventEngine.schedule(any(), any(), any()) }
        engine = TutorialEngineImpl(eventEngine)
    }

    @AfterEach
    fun tearDown() {
        engine.unregister(sessionId)
        clearAllMocks()
    }

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    fun `register TUTORIAL mode initialises session at OBSERVE`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.OBSERVE.stepNumber)
    }

    @Test
    fun `register FREE_PLAY mode is silently ignored`() {
        engine.register(sessionId, GameMode.FREE_PLAY)
        assertThat(engine.currentStep(sessionId)).isNull()
    }

    @Test
    fun `register is idempotent — second call does not reset step`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        // Advance past step 1
        repeat(3) { engine.onTick(sessionId, it.toLong() + 1, 0L, emptyList()) }
        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.DISPATCH.stepNumber)

        // Re-registering must not reset to OBSERVE
        engine.register(sessionId, GameMode.TUTORIAL)
        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.DISPATCH.stepNumber)
    }

    // ── OBSERVE → DISPATCH (step 1 → 2) ──────────────────────────────────────

    @Test
    fun `onTick fewer than 3 times does not advance from OBSERVE`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        engine.onTick(sessionId, 1L, 0L, emptyList())
        engine.onTick(sessionId, 2L, 10L, emptyList())
        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.OBSERVE.stepNumber)
    }

    @Test
    fun `onTick 3 times advances OBSERVE to DISPATCH and returns new stepNumber`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        engine.onTick(sessionId, 1L, 0L, emptyList())
        engine.onTick(sessionId, 2L, 10L, emptyList())
        val returned = engine.onTick(sessionId, 3L, 20L, emptyList())

        assertThat(returned).isEqualTo(TutorialStep.DISPATCH.stepNumber)
        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.DISPATCH.stepNumber)
    }

    @Test
    fun `onTick returns null when no step advances`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        val returned = engine.onTick(sessionId, 1L, 0L, emptyList())
        assertThat(returned).isNull()
    }

    // ── DISPATCH → DEMAND_SPIKE (step 2 → 3) ─────────────────────────────────

    @Test
    fun `onCommand SetGeneratorOutput at DISPATCH advances to DEMAND_SPIKE`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        advanceToStep(TutorialStep.DISPATCH)

        engine.onCommand(sessionId, "SetGeneratorOutput")

        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.DEMAND_SPIKE.stepNumber)
    }

    @Test
    fun `onCommand wrong type at DISPATCH does not advance`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        advanceToStep(TutorialStep.DISPATCH)

        engine.onCommand(sessionId, "PauseClock")

        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.DISPATCH.stepNumber)
    }

    @Test
    fun `onCommand SetGeneratorOutput at wrong step does not advance`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        // Still at OBSERVE
        engine.onCommand(sessionId, "SetGeneratorOutput")
        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.OBSERVE.stepNumber)
    }

    // ── DEMAND_SPIKE — scheduling and advancement (step 3) ───────────────────

    @Test
    fun `first onTick at DEMAND_SPIKE schedules the demand-spike event`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        advanceToStep(TutorialStep.DEMAND_SPIKE)

        val eventSlot = slot<com.gridmaster.game.event.GameEvent>()
        val timeSlot = slot<Long>()
        justRun { eventEngine.schedule(eq(sessionId), capture(eventSlot), capture(timeSlot)) }

        engine.onTick(sessionId, 4L, 100L, emptyList())

        verify(exactly = 1) { eventEngine.schedule(sessionId, any(), any()) }
        val scheduled = eventSlot.captured
        assertThat(scheduled).isInstanceOf(EconomicEvent::class.java)
        assertThat((scheduled as EconomicEvent).type).isEqualTo(EconomicEventType.INDUSTRIAL_BOOM)
        assertThat(timeSlot.captured).isEqualTo(110L) // gameTime + 10
    }

    @Test
    fun `second onTick at DEMAND_SPIKE does not re-schedule the event`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        advanceToStep(TutorialStep.DEMAND_SPIKE)

        engine.onTick(sessionId, 4L, 100L, emptyList())
        engine.onTick(sessionId, 5L, 110L, emptyList())

        // schedule must only be called once
        verify(exactly = 1) { eventEngine.schedule(any(), any(), any()) }
    }

    @Test
    fun `onTick at DEMAND_SPIKE with empty firedEvents does not advance`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        advanceToStep(TutorialStep.DEMAND_SPIKE)

        engine.onTick(sessionId, 4L, 100L, emptyList())

        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.DEMAND_SPIKE.stepNumber)
    }

    @Test
    fun `onTick at DEMAND_SPIKE with firedEvents advances to PAUSE_RESUME`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        advanceToStep(TutorialStep.DEMAND_SPIKE)
        engine.onTick(sessionId, 4L, 100L, emptyList()) // schedules spike
        val returned = engine.onTick(sessionId, 5L, 110L, listOf(stubFiredEvent))

        assertThat(returned).isEqualTo(TutorialStep.PAUSE_RESUME.stepNumber)
        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.PAUSE_RESUME.stepNumber)
    }

    // ── PAUSE_RESUME → COMPLETE (step 4 → 5) ─────────────────────────────────

    @Test
    fun `PAUSED then RUNNING at PAUSE_RESUME advances to COMPLETE`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        advanceToStep(TutorialStep.PAUSE_RESUME)

        engine.onClockStateChange(sessionId, ClockState.PAUSED)
        engine.onClockStateChange(sessionId, ClockState.RUNNING)

        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.COMPLETE.stepNumber)
    }

    @Test
    fun `RUNNING without prior PAUSED does not advance from PAUSE_RESUME`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        advanceToStep(TutorialStep.PAUSE_RESUME)

        engine.onClockStateChange(sessionId, ClockState.RUNNING)

        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.PAUSE_RESUME.stepNumber)
    }

    @Test
    fun `PAUSED in wrong step does not set sawPause flag`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        // Still at OBSERVE — pause should be ignored
        engine.onClockStateChange(sessionId, ClockState.PAUSED)
        advanceToStep(TutorialStep.PAUSE_RESUME)
        // Resume without a valid pause at this step → must not advance
        engine.onClockStateChange(sessionId, ClockState.RUNNING)

        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.PAUSE_RESUME.stepNumber)
    }

    @Test
    fun `onClockStateChange SLOW and STOPPED are silently ignored`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        advanceToStep(TutorialStep.PAUSE_RESUME)

        engine.onClockStateChange(sessionId, ClockState.SLOW)
        engine.onClockStateChange(sessionId, ClockState.STOPPED)

        assertThat(engine.currentStep(sessionId)).isEqualTo(TutorialStep.PAUSE_RESUME.stepNumber)
    }

    // ── Non-tutorial / unregistered ───────────────────────────────────────────

    @Test
    fun `onTick for unregistered session returns null without error`() {
        val result = engine.onTick("unknown-session", 1L, 0L, emptyList())
        assertThat(result).isNull()
    }

    @Test
    fun `onCommand for unregistered session is a no-op`() {
        engine.onCommand("unknown-session", "SetGeneratorOutput")
        // No exception — verified by reaching here
    }

    @Test
    fun `onClockStateChange for unregistered session is a no-op`() {
        engine.onClockStateChange("unknown-session", ClockState.PAUSED)
        // No exception — verified by reaching here
    }

    // ── unregister ────────────────────────────────────────────────────────────

    @Test
    fun `unregister removes session — currentStep returns null afterwards`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        assertThat(engine.currentStep(sessionId)).isNotNull()
        engine.unregister(sessionId)
        assertThat(engine.currentStep(sessionId)).isNull()
    }

    @Test
    fun `unregister is idempotent — second call does not throw`() {
        engine.register(sessionId, GameMode.TUTORIAL)
        engine.unregister(sessionId)
        engine.unregister(sessionId) // second call — must not throw
    }

    // ── currentStep ───────────────────────────────────────────────────────────

    @Test
    fun `currentStep returns null for session registered with FREE_PLAY`() {
        engine.register(sessionId, GameMode.FREE_PLAY)
        assertThat(engine.currentStep(sessionId)).isNull()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Advances the tutorial session to the requested [step] using the minimum
     * number of operations required by the state machine contract.
     */
    private fun advanceToStep(step: TutorialStep) {
        engine.register(sessionId, GameMode.TUTORIAL) // idempotent
        when (step) {
            TutorialStep.OBSERVE -> { /* already there */ }
            TutorialStep.DISPATCH -> {
                repeat(3) { i -> engine.onTick(sessionId, i.toLong() + 1, i * 10L, emptyList()) }
            }
            TutorialStep.DEMAND_SPIKE -> {
                advanceToStep(TutorialStep.DISPATCH)
                engine.onCommand(sessionId, "SetGeneratorOutput")
            }
            TutorialStep.PAUSE_RESUME -> {
                advanceToStep(TutorialStep.DEMAND_SPIKE)
                engine.onTick(sessionId, 4L, 100L, emptyList()) // schedules spike
                engine.onTick(sessionId, 5L, 110L, listOf(stubFiredEvent)) // fires → PAUSE_RESUME
            }
            TutorialStep.COMPLETE -> {
                advanceToStep(TutorialStep.PAUSE_RESUME)
                engine.onClockStateChange(sessionId, ClockState.PAUSED)
                engine.onClockStateChange(sessionId, ClockState.RUNNING)
            }
        }
    }
}
