package com.gridmaster.game.tutorial

import com.gridmaster.game.ClockState
import com.gridmaster.game.GameMode
import com.gridmaster.game.event.FiredEvent

/**
 * Manages per-session tutorial state machines.
 *
 * Only TUTORIAL-mode sessions are tracked; calls for other modes are no-ops.
 *
 * ### Lifecycle (mirrors EventEngine):
 * 1. [register] — on session start; initialises the step machine at OBSERVE.
 * 2. [onTick] — called every tick; auto-advances time-based steps.
 * 3. [onCommand] — called when a player command is handled (for step detection).
 * 4. [onClockStateChange] — called when the clock pauses or resumes (for step 4).
 * 5. [unregister] — on session stop or deletion.
 */
interface TutorialEngine {
    /**
     * Register [sessionId] with the given [mode].
     * Only TUTORIAL sessions are tracked; all other modes are silently ignored.
     */
    fun register(
        sessionId: String,
        mode: GameMode,
    )

    /**
     * Called every tick for all active sessions.
     * May auto-advance the step machine (e.g. OBSERVE → DISPATCH after 3 ticks,
     * or DEMAND_SPIKE → PAUSE_RESUME after the demand-spike event fires).
     *
     * @param firedEvents Events that fired this tick (used to detect step 3 completion).
     * @return The [TutorialStep.stepNumber] if it changed this tick, null otherwise.
     */
    fun onTick(
        sessionId: String,
        tickNumber: Long,
        gameTimeMinutes: Long,
        firedEvents: List<FiredEvent>,
    ): Int?

    /**
     * Returns the current [TutorialStep.stepNumber] for [sessionId],
     * or null if the session is not a tutorial or is not registered.
     */
    fun currentStep(sessionId: String): Int?

    /**
     * Notify the engine that a player command of [commandType] was processed.
     * Used to detect SetGeneratorOutput (step 2 → 3) and optionally PauseClock / ResumeClock.
     *
     * @param commandType Simple class name of the [com.gridmaster.game.command.PlayerCommand] subtype.
     */
    fun onCommand(
        sessionId: String,
        commandType: String,
    )

    /**
     * Notify the engine that the clock transitioned to [newState].
     * Used to detect the RUNNING→PAUSED→RUNNING cycle for step 4 → 5.
     */
    fun onClockStateChange(
        sessionId: String,
        newState: ClockState,
    )

    /**
     * Remove all tutorial state for [sessionId]. No-op if not registered.
     */
    fun unregister(sessionId: String)
}
