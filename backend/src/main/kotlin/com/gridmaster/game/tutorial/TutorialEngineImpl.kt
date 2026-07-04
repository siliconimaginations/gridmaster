package com.gridmaster.game.tutorial

import com.gridmaster.game.ClockState
import com.gridmaster.game.GameMode
import com.gridmaster.game.event.EconomicEvent
import com.gridmaster.game.event.EconomicEventType
import com.gridmaster.game.event.EventEffect
import com.gridmaster.game.event.EventEngine
import com.gridmaster.game.event.EventSeverity
import com.gridmaster.game.event.FiredEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [TutorialEngine] implementation.
 *
 * ### Step machine
 *
 * | Step | Name          | Advance trigger                                      |
 * |------|---------------|------------------------------------------------------|
 * | 1    | OBSERVE       | 3 ticks elapsed (auto)                               |
 * | 2    | DISPATCH      | Player sends SetGeneratorOutput command              |
 * | 3    | DEMAND_SPIKE  | Demand-spike event fires (auto, 1 tick after enter)  |
 * | 4    | PAUSE_RESUME  | Clock pauses then resumes                            |
 * | 5    | COMPLETE      | Terminal — session marked completed by TickEngine    |
 *
 * Non-TUTORIAL sessions are silently ignored on all calls.
 *
 * ### Thread safety
 * [TutorialSession] mutable fields are `@Volatile`. Compound check-then-set
 * transitions use `synchronized(state)` to prevent double-firing.
 */
@Component
class TutorialEngineImpl(
    private val eventEngine: EventEngine,
) : TutorialEngine {
    private val log = LoggerFactory.getLogger(TutorialEngineImpl::class.java)
    private val sessions = ConcurrentHashMap<String, TutorialSession>()

    // ── Per-session state ─────────────────────────────────────────────────────

    private class TutorialSession(val sessionId: String) {
        @Volatile var step: TutorialStep = TutorialStep.OBSERVE

        @Volatile var ticksSinceStart: Long = 0L

        /** Set to true once we've registered a pause during step 4. */
        @Volatile var sawPause: Boolean = false

        /** Set to true once the demand-spike event has been scheduled. */
        @Volatile var demandSpikeScheduled: Boolean = false
    }

    // ── TutorialEngine API ────────────────────────────────────────────────────

    override fun register(
        sessionId: String,
        mode: GameMode,
    ) {
        if (mode != GameMode.TUTORIAL) return
        sessions.putIfAbsent(sessionId, TutorialSession(sessionId))
        log.info("Tutorial engine registered session {} (mode=TUTORIAL)", sessionId)
    }

    override fun onTick(
        sessionId: String,
        tickNumber: Long,
        gameTimeMinutes: Long,
        firedEvents: List<FiredEvent>,
    ): Int? {
        val state = sessions[sessionId] ?: return null

        state.ticksSinceStart++
        var advanced: TutorialStep? = null

        synchronized(state) {
            when (state.step) {
                // Step 1: auto-advance after 3 ticks
                TutorialStep.OBSERVE -> {
                    if (state.ticksSinceStart >= 3L) {
                        advanced = advance(state, TutorialStep.DISPATCH)
                    }
                }

                // Step 3: schedule demand spike on entry (first tick at this step),
                //         then advance as soon as the event fires.
                TutorialStep.DEMAND_SPIKE -> {
                    if (!state.demandSpikeScheduled) {
                        scheduleDemandSpike(sessionId, gameTimeMinutes + 10L)
                        state.demandSpikeScheduled = true
                    }
                    if (firedEvents.isNotEmpty()) {
                        advanced = advance(state, TutorialStep.PAUSE_RESUME)
                    }
                }

                else -> { /* steps 2, 4, 5 have no tick-based auto-advance */ }
            }
        }

        return advanced?.stepNumber
    }

    override fun currentStep(sessionId: String): Int? = sessions[sessionId]?.step?.stepNumber

    override fun onCommand(
        sessionId: String,
        commandType: String,
    ) {
        val state = sessions[sessionId] ?: return
        synchronized(state) {
            if (state.step == TutorialStep.DISPATCH && commandType == "SetGeneratorOutput") {
                advance(state, TutorialStep.DEMAND_SPIKE)
            }
        }
    }

    override fun onClockStateChange(
        sessionId: String,
        newState: ClockState,
    ) {
        val state = sessions[sessionId] ?: return
        synchronized(state) {
            when (newState) {
                ClockState.PAUSED -> {
                    if (state.step == TutorialStep.PAUSE_RESUME) {
                        state.sawPause = true
                    }
                }
                ClockState.RUNNING -> {
                    if (state.step == TutorialStep.PAUSE_RESUME && state.sawPause) {
                        advance(state, TutorialStep.COMPLETE)
                    }
                }
                else -> { /* STOPPED / SLOW — ignore */ }
            }
        }
    }

    override fun unregister(sessionId: String) {
        sessions.remove(sessionId)
        log.debug("Tutorial engine unregistered session {}", sessionId)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Mutates [state].step and logs; returns the new step. */
    private fun advance(
        state: TutorialSession,
        to: TutorialStep,
    ): TutorialStep {
        state.step = to
        log.info("Tutorial session '{}' → step {} ({})", state.sessionId, to.stepNumber, to.name)
        return to
    }

    private fun scheduleDemandSpike(
        sessionId: String,
        atGameTimeMinutes: Long,
    ) {
        val event =
            EconomicEvent(
                id = "tutorial-demand-spike",
                description = "Industrial demand surge — factories ramping up production",
                severity = EventSeverity.WARNING,
                type = EconomicEventType.INDUSTRIAL_BOOM,
                durationMinutes = 30,
                effects = listOf(EventEffect.ScaleLoad(regionIds = null, factor = 1.35)),
            )
        eventEngine.schedule(sessionId, event, atGameTimeMinutes)
        log.info("Tutorial session '{}' demand-spike scheduled at t={}min", sessionId, atGameTimeMinutes)
    }
}
