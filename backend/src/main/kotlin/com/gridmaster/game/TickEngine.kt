package com.gridmaster.game

import com.gridmaster.engine.powerflow.PowerFlowResult

/**
 * Drives the simulation forward in discrete ticks.
 *
 * Each tick represents [GRID_MINUTES_PER_TICK] minutes of in-game time. The real-time
 * interval between ticks is compressed by the speed multiplier:
 *
 *   slotMs = 1_000 / speedMultiplier
 *
 * If a tick's work (power flow + bookkeeping) exceeds the slot, the engine **slips**:
 * the next tick starts immediately. Grid-time advances correctly; only real-time pacing
 * suffers. See [TickResult.slipped].
 *
 * Implementations must be thread-safe — pause/resume/setSpeed may be called concurrently
 * with an in-progress tick.
 */
interface TickEngine {
    /**
     * Register the session and start the tick loop.
     *
     * @throws IllegalArgumentException if [multiplier] is outside 1–100.
     * @throws IllegalStateException if the session is already running or stopped.
     */
    fun start(
        sessionId: String,
        userId: String,
    ): TickClockStatus

    /**
     * Pause the tick loop. The tick currently in progress completes before the loop halts.
     * [ClockState] transitions to [ClockState.PAUSED] and the session is auto-saved.
     *
     * @throws IllegalStateException if the session is not registered.
     */
    fun pause(
        sessionId: String,
        userId: String,
    ): TickClockStatus

    /**
     * Resume a paused clock.
     *
     * @throws IllegalStateException if the session is not paused.
     */
    fun resume(
        sessionId: String,
        userId: String,
    ): TickClockStatus

    /**
     * Change the speed multiplier. Takes effect on the next tick.
     * Clears any active auto-slow if the player explicitly raises the speed.
     *
     * @throws IllegalArgumentException if [multiplier] is outside 1–100.
     */
    fun setSpeed(
        sessionId: String,
        userId: String,
        multiplier: Int,
    ): TickClockStatus

    /**
     * Permanently stop the tick loop. The session transitions to [ClockState.STOPPED]
     * and is removed from the engine. Cannot be restarted.
     */
    fun stop(
        sessionId: String,
        userId: String,
    )

    /**
     * Current runtime status, or null if the session is not registered.
     * If [userId] is provided, ownership is verified — returns null if the session
     * exists but belongs to another user.
     */
    fun clockStatus(
        sessionId: String,
        userId: String? = null,
    ): TickClockStatus?
}

/** Fixed in-game time advance per tick, regardless of speed multiplier. */
const val GRID_MINUTES_PER_TICK = 10L

/** Default number of ticks between auto-saves. */
const val DEFAULT_AUTO_SAVE_INTERVAL = 10L

/** Maximum speed multiplier accepted by the engine. */
const val MAX_SPEED_MULTIPLIER = 100

/** Number of consecutive over-budget ticks before the engine auto-pauses. */
const val SLIP_PAUSE_THRESHOLD = 10

/**
 * Context passed to each tick so the pipeline knows where it is in simulated time.
 */
data class TickContext(
    val sessionId: String,
    /** 1-based counter, incremented each tick regardless of slip. */
    val tickNumber: Long,
    /** Game time at the *start* of this tick (before the [GRID_MINUTES_PER_TICK] advance). */
    val gameTimeMinutes: Long,
    /** Allowed wall-clock duration for this tick in milliseconds. */
    val wallClockSlotMs: Long,
)

/**
 * Outcome of a single tick.
 */
data class TickResult(
    val tickContext: TickContext,
    val powerFlowResult: PowerFlowResult,
    /** Actual wall-clock duration of the tick work. */
    val actualDurationMs: Long,
    /** True if [actualDurationMs] exceeded [TickContext.wallClockSlotMs]. */
    val slipped: Boolean,
)

/**
 * Snapshot of the clock's runtime state returned to callers.
 */
data class TickClockStatus(
    val clockState: ClockState,
    val speedMultiplier: Int,
    val gameTimeMinutes: Long,
    val tickCount: Long,
    /** True if the clock was automatically slowed due to a critical condition. */
    val autoSlowed: Boolean,
)
