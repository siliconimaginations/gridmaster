package com.gridmaster.game.challenge

import com.gridmaster.game.GameMode

/**
 * Manages per-session challenge scenario state machines.
 *
 * Only CHALLENGE-mode sessions are tracked; calls for other modes are no-ops.
 *
 * ### Lifecycle (mirrors TutorialEngine):
 * 1. [register] — on session start; pre-schedules the scripted line-trip and
 *    load-spike events via EventEngine.
 * 2. [onTick] — called every tick; evaluates victory condition and updates
 *    the time-remaining countdown.
 * 3. [unregister] — on session stop or deletion.
 *
 * ### Victory condition
 * Health score ≥ [VICTORY_HEALTH_THRESHOLD] for [VICTORY_CONSECUTIVE_TICKS]
 * consecutive ticks, but only after [VICTORY_ELIGIBLE_AFTER_MINUTES] game-minutes.
 *
 * ### Defeat condition
 * Reuses the existing TickEngine game-over path (health < 20 for 3 ticks).
 */
interface ChallengeEngine {
    companion object {
        /** Total game-minutes the player has to restore stability. */
        const val CHALLENGE_DURATION_MINUTES: Long = 60L

        /** Game-minute at which the scripted line-trip fires. */
        const val TRIP_LINE_AT_MINUTES: Long = 10L

        /** Game-minute at which the scripted load-spike fires. */
        const val SCALE_LOAD_AT_MINUTES: Long = 20L

        /** Victory checks only start after this many game-minutes. */
        const val VICTORY_ELIGIBLE_AFTER_MINUTES: Long = 30L

        /**
         * Health score the player must sustain for [VICTORY_CONSECUTIVE_TICKS]
         * consecutive ticks to achieve victory.
         */
        const val VICTORY_HEALTH_THRESHOLD: Int = 60

        /** Number of consecutive healthy ticks required for victory. */
        const val VICTORY_CONSECUTIVE_TICKS: Int = 10
    }

    /**
     * Register [sessionId] with the given [mode].
     *
     * Only CHALLENGE sessions are tracked; all other modes are silently ignored.
     * Pre-schedules the scripted crisis events on the first call; idempotent on
     * subsequent calls (events are not re-scheduled).
     */
    fun register(
        sessionId: String,
        mode: GameMode,
    )

    /**
     * Called every tick. Evaluates the victory condition and updates the
     * time-remaining countdown.
     *
     * @param gameTimeMinutes Current accumulated game-time.
     * @param healthScore Current grid health score (0–100).
     * @return `true` if the victory condition was met this tick; `false` otherwise.
     */
    fun onTick(
        sessionId: String,
        gameTimeMinutes: Long,
        healthScore: Int,
    ): Boolean

    /**
     * Returns the number of game-minutes remaining until the challenge deadline,
     * or null if [sessionId] is not a registered CHALLENGE session.
     *
     * The value is clamped to zero once the deadline has passed.
     */
    fun challengeTimeRemainingMinutes(sessionId: String): Int?

    /**
     * Remove all challenge state for [sessionId]. No-op if not registered.
     */
    fun unregister(sessionId: String)
}
