package com.gridmaster.game.challenge

import com.gridmaster.game.GameMode
import com.gridmaster.game.challenge.ChallengeEngine.Companion.CHALLENGE_DURATION_MINUTES
import com.gridmaster.game.challenge.ChallengeEngine.Companion.SCALE_LOAD_AT_MINUTES
import com.gridmaster.game.challenge.ChallengeEngine.Companion.TRIP_LINE_AT_MINUTES
import com.gridmaster.game.challenge.ChallengeEngine.Companion.VICTORY_CONSECUTIVE_TICKS
import com.gridmaster.game.challenge.ChallengeEngine.Companion.VICTORY_ELIGIBLE_AFTER_MINUTES
import com.gridmaster.game.challenge.ChallengeEngine.Companion.VICTORY_HEALTH_THRESHOLD
import com.gridmaster.game.event.EconomicEvent
import com.gridmaster.game.event.EconomicEventType
import com.gridmaster.game.event.EventEffect
import com.gridmaster.game.event.EventEngine
import com.gridmaster.game.event.EventSeverity
import com.gridmaster.game.event.WeatherEvent
import com.gridmaster.game.event.WeatherEventType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [ChallengeEngine] implementation.
 *
 * ### Scripted crisis scenario
 * Two events are pre-scheduled on session [register]:
 * - t = 10 min: [WeatherEvent] (STORM) trips the North–Industrial corridor (`L12`).
 * - t = 20 min: [EconomicEvent] (INDUSTRIAL_BOOM) scales system load by 1.25×.
 *
 * ### Victory condition
 * After t = 30 min, if the health score is ≥ [VICTORY_HEALTH_THRESHOLD] for
 * [VICTORY_CONSECUTIVE_TICKS] consecutive ticks, [onTick] returns `true` and
 * [TickEngineImpl][com.gridmaster.game.TickEngineImpl] calls `triggerVictory`.
 *
 * ### Thread safety
 * [ChallengeSession] mutable fields are `@Volatile`. The compound check-then-set
 * in [onTick] is guarded by `synchronized(state)` to prevent double-victory.
 */
@Component
class ChallengeEngineImpl(
    private val eventEngine: EventEngine,
) : ChallengeEngine {
    private val log = LoggerFactory.getLogger(ChallengeEngineImpl::class.java)
    private val sessions = ConcurrentHashMap<String, ChallengeSession>()

    /** ID of the transmission line tripped by the scripted storm event. */
    private val scriptedLineId = "L12"

    // ── Per-session state ─────────────────────────────────────────────────────

    private class ChallengeSession(val sessionId: String) {
        /** Consecutive ticks where health ≥ [VICTORY_HEALTH_THRESHOLD] after eligibility gate. */
        @Volatile var consecutiveHighHealthTicks: Int = 0

        /** Set to true once victory has been declared (prevents re-triggering). */
        @Volatile var victoryTriggered: Boolean = false

        /** Latest computed time-remaining value (game-minutes); initialised to full duration. */
        @Volatile var timeRemainingMinutes: Int = CHALLENGE_DURATION_MINUTES.toInt()
    }

    // ── ChallengeEngine API ───────────────────────────────────────────────────

    override fun register(
        sessionId: String,
        mode: GameMode,
    ) {
        if (mode != GameMode.CHALLENGE) return
        val alreadyRegistered = sessions.putIfAbsent(sessionId, ChallengeSession(sessionId)) != null
        if (alreadyRegistered) return

        // Pre-schedule the scripted crisis events deterministically.
        val lineTrip =
            WeatherEvent(
                id = "challenge-line-trip",
                description = "Storm-induced fault — North–Industrial corridor ($scriptedLineId) tripped",
                severity = EventSeverity.CRITICAL,
                type = WeatherEventType.STORM,
                affectedRegionIds = null,
                durationMinutes = 60,
                effects = listOf(EventEffect.TripElement(scriptedLineId)),
            )
        val loadSpike =
            EconomicEvent(
                id = "challenge-load-spike",
                description = "Emergency demand surge following grid fault",
                severity = EventSeverity.WARNING,
                type = EconomicEventType.INDUSTRIAL_BOOM,
                durationMinutes = 40,
                effects = listOf(EventEffect.ScaleLoad(regionIds = null, factor = 1.25)),
            )
        eventEngine.schedule(sessionId, lineTrip, TRIP_LINE_AT_MINUTES)
        eventEngine.schedule(sessionId, loadSpike, SCALE_LOAD_AT_MINUTES)
        log.info(
            "Challenge engine registered session {}: line-trip at t={}min, load-spike at t={}min",
            sessionId,
            TRIP_LINE_AT_MINUTES,
            SCALE_LOAD_AT_MINUTES,
        )
    }

    override fun onTick(
        sessionId: String,
        gameTimeMinutes: Long,
        healthScore: Int,
    ): Boolean {
        val state = sessions[sessionId] ?: return false
        if (state.victoryTriggered) return false

        // Update the countdown (clamped to 0 once deadline passes).
        state.timeRemainingMinutes = maxOf(0L, CHALLENGE_DURATION_MINUTES - gameTimeMinutes).toInt()

        // Victory evaluation — guarded to prevent double-trigger from concurrent callers.
        synchronized(state) {
            if (state.victoryTriggered) return false

            if (gameTimeMinutes >= VICTORY_ELIGIBLE_AFTER_MINUTES && healthScore >= VICTORY_HEALTH_THRESHOLD) {
                state.consecutiveHighHealthTicks++
                if (state.consecutiveHighHealthTicks >= VICTORY_CONSECUTIVE_TICKS) {
                    state.victoryTriggered = true
                    log.info(
                        "Challenge session '{}' victory achieved at t={}min (health={})",
                        sessionId,
                        gameTimeMinutes,
                        healthScore,
                    )
                    return true
                }
            } else {
                // Any tick outside the health threshold resets the streak.
                state.consecutiveHighHealthTicks = 0
            }
        }
        return false
    }

    override fun challengeTimeRemainingMinutes(sessionId: String): Int? = sessions[sessionId]?.timeRemainingMinutes

    override fun unregister(sessionId: String) {
        sessions.remove(sessionId)
        log.debug("Challenge engine unregistered session {}", sessionId)
    }
}
