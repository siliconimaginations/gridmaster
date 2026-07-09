package com.gridmaster.game

import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.api.SessionNotFoundException
import com.gridmaster.api.websocket.GameOverDto
import com.gridmaster.api.websocket.GameStatePublisher
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.model.NetworkMutation
import com.gridmaster.engine.network.IidmNetworkMapper
import com.gridmaster.engine.network.NetworkRepository
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.NetworkViolation
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.ViolationSeverity
import com.gridmaster.game.event.EventEngine
import com.gridmaster.game.tutorial.TutorialEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

// Extracts the common severity from either NetworkViolation subtype.
private val NetworkViolation.severity: ViolationSeverity
    get() =
        when (this) {
            is NetworkViolation.VoltageViolation -> severity
            is NetworkViolation.ThermalViolation -> severity
        }

/**
 * Coroutine-based implementation of [TickEngine].
 *
 * Each active session gets its own coroutine [Job] launched on [Dispatchers.Default].
 * The tick loop is driven by a [SessionRuntime] that holds all mutable clock state.
 * Control commands (pause, resume, setSpeed) update volatile fields in the runtime;
 * the running loop observes those fields at the top of each iteration.
 *
 * ### Thread safety
 * All fields in [SessionRuntime] that are read by the tick loop *and* written by
 * control commands are annotated `@Volatile`. [sessions] itself is a
 * [ConcurrentHashMap] so map mutations are safe. The tick loop only ever reads its
 * own session's runtime — it never modifies other sessions.
 *
 * ### Slip handling
 * If a tick's work exceeds the wall-clock slot, the loop records a slip and
 * starts the next tick immediately. After [SLIP_PAUSE_THRESHOLD] consecutive slips
 * the clock auto-pauses to prevent the simulation from becoming unresponsive.
 *
 * ### Auto-slow
 * When the power flow returns [ConvergenceStatus.NETWORK_FAILURE] or any
 * [ViolationSeverity.CRITICAL] violation is detected the clock drops to 1× and
 * [ClockState.SLOW]. It restores automatically when the condition clears.
 */

@Component
class TickEngineImpl(
    private val physicsSessionStore: PhysicsSessionStore,
    private val gameSessionService: GameSessionService,
    private val powerFlowService: PowerFlowService,
    private val contingencyAnalysisService: ContingencyAnalysisService,
    private val eventEngine: EventEngine,
    private val tutorialEngine: TutorialEngine,
    private val challengeEngine: com.gridmaster.game.challenge.ChallengeEngine,
    private val networkRepository: NetworkRepository,
    private val networkMapper: IidmNetworkMapper,
    @Value("\${gridmaster.clock.auto-save-interval:$DEFAULT_AUTO_SAVE_INTERVAL}")
    private val autoSaveInterval: Long,
    /**
     * Feature flag for issue #383 — when true (the default), each bus load is
     * scaled every tick by [DailyLoadCurve.multiplierForGameTimeMinutes] so demand
     * follows a realistic daily shape instead of staying flat. Additive and
     * backward compatible: existing sessions and tests that assume flat load can
     * disable it via `gridmaster.daily-load-curve.enabled=false`.
     */
    @Value("\${gridmaster.daily-load-curve.enabled:true}")
    private val dailyLoadCurveEnabled: Boolean,
) : TickEngine {
    private val log = LoggerFactory.getLogger(TickEngineImpl::class.java)

    /**
     * Optional WebSocket publisher — wired when Module 10 is on the classpath.
     * Injected via field injection to avoid a circular dependency between
     * TickEngine ↔ GameStatePublisher ↔ TickEngine.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private var gameStatePublisher: GameStatePublisher? = null

    /** Parent scope for all per-session coroutines. Cancelled on application shutdown. */
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Live session runtimes keyed by sessionId. */
    private val sessions = ConcurrentHashMap<String, SessionRuntime>()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    override fun start(
        sessionId: String,
        userId: String,
    ): TickClockStatus {
        val gameSession = gameSessionService.load(sessionId, userId)
        val runtime =
            SessionRuntime(
                sessionId = sessionId,
                userId = userId,
                clockState = ClockState.RUNNING,
                speedMultiplier = gameSession.clockSpeedMultiplier.coerceIn(1, MAX_SPEED_MULTIPLIER),
                gameTimeMinutes = gameSession.gameTimeEpochMinutes,
            )
        // Atomic check-and-register: putIfAbsent eliminates the TOCTOU race (closes #69).
        val displaced = sessions.putIfAbsent(sessionId, runtime)
        check(displaced == null) {
            "Session $sessionId is already active. Use /resume if paused, or /stop then re-create if stopped."
        }
        runtime.job =
            engineScope.launch {
                runTickLoop(runtime)
            }
        eventEngine.register(sessionId)
        tutorialEngine.register(sessionId, gameSession.mode)
        challengeEngine.register(sessionId, gameSession.mode)
        log.info("Started tick loop for session {} at {}×", sessionId, runtime.speedMultiplier)
        return runtime.toStatus()
    }

    override fun pause(
        sessionId: String,
        userId: String,
    ): TickClockStatus {
        val runtime = findAndCheckOwner(sessionId, userId)
        val pausedStatus =
            synchronized(runtime) {
                check(runtime.clockState in setOf(ClockState.RUNNING, ClockState.SLOW)) {
                    "Cannot pause session $sessionId in state ${runtime.clockState}"
                }
                runtime.clockState = ClockState.PAUSED
                runtime.toStatus()
            }
        runtime.pendingSave = true // Tick loop saves after current tick finishes
        tutorialEngine.onClockStateChange(sessionId, ClockState.PAUSED)
        log.info("Paused session {}", sessionId)
        return pausedStatus
    }

    override fun resume(
        sessionId: String,
        userId: String,
    ): TickClockStatus {
        val runtime = findAndCheckOwner(sessionId, userId)
        val resumedStatus =
            synchronized(runtime) {
                check(runtime.clockState == ClockState.PAUSED) {
                    "Cannot resume session $sessionId in state ${runtime.clockState}"
                }
                runtime.clockState = ClockState.RUNNING
                runtime.toStatus()
            }
        tutorialEngine.onClockStateChange(sessionId, ClockState.RUNNING)
        log.info("Resumed session {}", sessionId)
        return resumedStatus
    }

    override fun setSpeed(
        sessionId: String,
        userId: String,
        multiplier: Int,
    ): TickClockStatus {
        require(multiplier in 1..MAX_SPEED_MULTIPLIER) {
            "Speed multiplier must be in 1–$MAX_SPEED_MULTIPLIER, got $multiplier"
        }
        val runtime = findAndCheckOwner(sessionId, userId)
        check(runtime.clockState != ClockState.STOPPED) {
            "Cannot change speed of stopped session $sessionId"
        }
        val speedStatus =
            synchronized(runtime) {
                runtime.speedMultiplier = multiplier
                // If the player manually changes speed, clear any active auto-slow and
                // set the override flag so applyAutoSlow() won't re-engage on the same tick.
                if (runtime.autoSlowed) {
                    runtime.autoSlowed = false
                    runtime.autoSlowPreviousSpeed = null
                    runtime.playerSpeedOverride = true
                    if (runtime.clockState == ClockState.SLOW) {
                        runtime.clockState = ClockState.RUNNING
                    }
                }
                runtime.toStatus()
            }
        log.info("Set speed for session {} to {}×", sessionId, multiplier)
        return speedStatus
    }

    override fun stop(
        sessionId: String,
        userId: String,
    ) {
        val runtime = findAndCheckOwner(sessionId, userId)
        runtime.clockState = ClockState.STOPPED
        runtime.job?.cancel()
        sessions.remove(sessionId)
        eventEngine.unregister(sessionId)
        tutorialEngine.unregister(sessionId)
        challengeEngine.unregister(sessionId)
        networkRepository.evictSession(sessionId)
        log.info("Stopped tick loop for session {}", sessionId)
    }

    override fun clockStatus(
        sessionId: String,
        userId: String?,
    ): TickClockStatus? {
        val runtime = sessions[sessionId] ?: return null
        if (userId != null && runtime.userId != userId) return null
        return runtime.toStatus()
    }

    // -------------------------------------------------------------------------
    // Tick loop
    // -------------------------------------------------------------------------

    private suspend fun runTickLoop(runtime: SessionRuntime) {
        log.debug("Tick loop started for session {}", runtime.sessionId)
        try {
            while (runtime.clockState != ClockState.STOPPED) {
                when (runtime.clockState) {
                    ClockState.PAUSED -> {
                        if (runtime.pendingSave) {
                            runtime.pendingSave = false
                            triggerAutoSave(runtime)
                        }
                        delay(PAUSE_POLL_INTERVAL_MS)
                        continue
                    }
                    ClockState.RUNNING, ClockState.SLOW -> executeTick(runtime)
                    ClockState.STOPPED -> break
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                log.debug("Tick loop cancelled for session {}", runtime.sessionId)
                throw e
            }
            log.error("Tick loop failed unexpectedly for session {}", runtime.sessionId, e)
            synchronized(runtime) { runtime.clockState = ClockState.STOPPED }
        } finally {
            log.debug("Tick loop ended for session {}", runtime.sessionId)
        }
    }

    private suspend fun executeTick(runtime: SessionRuntime) {
        val slotMs = slotMillis(runtime.speedMultiplier)
        val tickStart = System.currentTimeMillis()

        val ctx =
            TickContext(
                sessionId = runtime.sessionId,
                tickNumber = runtime.tickCount + 1,
                gameTimeMinutes = runtime.gameTimeMinutes,
                wallClockSlotMs = slotMs,
            )

        val physicsSession =
            physicsSessionStore.find(runtime.sessionId) ?: run {
                log.warn("Session {} no longer in PhysicsSessionStore — stopping clock", runtime.sessionId)
                synchronized(runtime) { runtime.clockState = ClockState.STOPPED }
                return
            }

        // Step 1–4: power flow solve + snapshot update
        // Synchronized on physicsSession — PowSyBl's Network/VariantManager
        // is not thread-safe, and the contingency-analysis background run (triggered
        // below) mutates the same network's variants from a different coroutine. At
        // high speed (short slotMillis) ticks land while a prior analysis run is still
        // in flight; without this lock the two race and corrupt the variant array
        // (ArrayIndexOutOfBoundsException in PowSyBl's VariantManagerImpl — #360).
        val pfResult =
            synchronized(physicsSession) {
                try {
                    if (dailyLoadCurveEnabled) {
                        applyDailyLoadCurve(runtime, physicsSession, ctx.gameTimeMinutes)
                    }
                    powerFlowService.solve(physicsSession.iidmNetwork)
                } catch (e: Exception) {
                    log.error("Power flow solve failed for session {}, tick {}", runtime.sessionId, ctx.tickNumber, e)
                    // Pause (not stop) so the player can inspect the grid and potentially resume. Closes #64.
                    synchronized(runtime) { runtime.clockState = ClockState.PAUSED }
                    triggerAutoSave(runtime)
                    return
                }
            }

        physicsSession.latestSnapshot = pfResult.snapshot
        physicsSession.latestPowerFlowResult = pfResult

        // Step 5: check auto-slow conditions
        applyAutoSlow(runtime, pfResult)

        // Step 6: trigger N-1 contingency analysis every 6 ticks (1 grid-hour)
        // triggerAsync() and the background run it schedules both synchronize on
        // physicsSession internally (#360) — no extra wrapping needed here.
        if (ctx.tickNumber % CONTINGENCY_TRIGGER_INTERVAL == 0L) {
            contingencyAnalysisService.triggerAsync(physicsSession.iidmNetwork, physicsSession)
        }

        // Step 7: EventEngine.onTick()
        val firedEvents = eventEngine.onTick(ctx, pfResult.snapshot)
        if (firedEvents.isNotEmpty()) {
            log.debug(
                "EventEngine fired {} events for session {} at t={}min",
                firedEvents.size,
                runtime.sessionId,
                ctx.gameTimeMinutes,
            )
        }

        // Step 7b: TutorialEngine.onTick() — no-op for non-TUTORIAL sessions
        tutorialEngine.onTick(
            sessionId = runtime.sessionId,
            tickNumber = ctx.tickNumber,
            gameTimeMinutes = ctx.gameTimeMinutes,
            firedEvents = firedEvents,
        )

        // Step 5b: health score tracking + game-over detection (defeat path)
        val tickHealthScore = computeHealthScore(pfResult)
        val gameOver = updateHealthAndCheckGameOver(runtime, tickHealthScore)
        if (gameOver) {
            triggerGameOver(runtime)
            return
        }

        // Step 7c: ChallengeEngine.onTick() — evaluates victory condition; no-op for non-CHALLENGE
        val victory =
            challengeEngine.onTick(
                sessionId = runtime.sessionId,
                gameTimeMinutes = ctx.gameTimeMinutes,
                healthScore = tickHealthScore,
            )
        if (victory) {
            triggerVictory(runtime, tickHealthScore)
            return
        }

        // Step 8: WebSocket broadcast
        gameStatePublisher?.publishTick(
            sessionId = runtime.sessionId,
            tickNumber = ctx.tickNumber,
            gameTimeMinutes = ctx.gameTimeMinutes,
            clockState = synchronized(runtime) { runtime.clockState },
            clockSpeedMultiplier = synchronized(runtime) { runtime.speedMultiplier },
            powerFlowResult = pfResult,
            newAlerts = emptyList(), // alerts generated by CommandHandler, not tick pipeline
            pendingCards = eventEngine.pendingCards(runtime.sessionId),
            healthScore = tickHealthScore,
            tutorialStep = tutorialEngine.currentStep(runtime.sessionId),
            challengeTimeRemainingMinutes = challengeEngine.challengeTimeRemainingMinutes(runtime.sessionId),
        )

        // Step 9: auto-save
        val shouldSave = autoSaveInterval > 0 && ctx.tickNumber % autoSaveInterval == 0L
        runtime.tickCount++
        runtime.gameTimeMinutes += GRID_MINUTES_PER_TICK

        if (shouldSave) {
            triggerAutoSave(runtime)
        }

        // Slip detection and pacing
        val elapsed = System.currentTimeMillis() - tickStart
        val slipped = elapsed > slotMs
        if (slipped) {
            runtime.consecutiveSlips++
            if (runtime.consecutiveSlips >= SLIP_PAUSE_THRESHOLD) {
                log.warn(
                    "Session {} exceeded slip threshold ({} consecutive slips) — pausing",
                    runtime.sessionId,
                    SLIP_PAUSE_THRESHOLD,
                )
                synchronized(runtime) { runtime.clockState = ClockState.PAUSED }
                triggerAutoSave(runtime)
                return
            }
        } else {
            runtime.consecutiveSlips = 0
            delay(slotMs - elapsed)
        }

        log.trace(
            "Tick {} session {} game-time={}min elapsed={}ms slot={}ms slipped={}",
            ctx.tickNumber,
            runtime.sessionId,
            runtime.gameTimeMinutes,
            elapsed,
            slotMs,
            slipped,
        )
    }

    // -------------------------------------------------------------------------
    // Daily load curve (issue #383)
    // -------------------------------------------------------------------------

    /**
     * Scale every load in [physicsSession] by the current time-of-day multiplier
     * from [DailyLoadCurve]. The first call for a session captures each load's
     * original (flat) active power in [SessionRuntime.baseLoadMw] so scaling is
     * always relative to the network's baseline rather than compounding across
     * ticks. [SessionRuntime] is constructed fresh in [start] every time a session
     * (re)starts, so [SessionRuntime.baseLoadMw] can never carry over stale values
     * from a previous run — there is no separate reset path to maintain. Must be
     * called while holding the lock on [physicsSession] — it mutates the live IIDM
     * network via [networkMapper], same as any other NetworkMutation application.
     *
     * A per-load mutation failure is logged at error level but does not abort the
     * tick: [loadId] values come directly from the session's own network snapshot,
     * so a failure here indicates a mapper bug rather than a transient condition,
     * and letting the remaining loads scale normally is preferable to stalling the
     * whole simulation over one bad load.
     */
    private fun applyDailyLoadCurve(
        runtime: SessionRuntime,
        physicsSession: com.gridmaster.api.PhysicsSession,
        gameTimeMinutes: Long,
    ) {
        var baseLoads = runtime.baseLoadMw
        if (baseLoads == null) {
            baseLoads = physicsSession.latestSnapshot.loads.associate { it.id to it.activePowerMw }
            runtime.baseLoadMw = baseLoads
        }
        val multiplier = DailyLoadCurve.multiplierForGameTimeMinutes(gameTimeMinutes)
        runtime.currentLoadMultiplier = multiplier
        for ((loadId, baseMw) in baseLoads) {
            val mutation = NetworkMutation.SetLoadPower(loadId = loadId, activePowerMw = baseMw * multiplier)
            networkMapper.applyMutation(physicsSession.iidmNetwork, mutation).onFailure {
                log.error(
                    "Daily load curve: failed to scale load {} for session {}: {}",
                    loadId,
                    runtime.sessionId,
                    it.message,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Auto-slow
    // -------------------------------------------------------------------------

    private fun applyAutoSlow(
        runtime: SessionRuntime,
        pfResult: PowerFlowResult,
    ) {
        val needsSlow =
            pfResult.status == ConvergenceStatus.NETWORK_FAILURE ||
                pfResult.violations.any { it.severity == ViolationSeverity.CRITICAL }

        synchronized(runtime) {
            if (needsSlow && !runtime.autoSlowed && !runtime.playerSpeedOverride && runtime.clockState == ClockState.RUNNING) {
                log.info(
                    "Auto-slow activated for session {} (status={}, critical violations={})",
                    runtime.sessionId,
                    pfResult.status,
                    pfResult.violations.count { it.severity == ViolationSeverity.CRITICAL },
                )
                runtime.autoSlowPreviousSpeed = runtime.speedMultiplier
                runtime.speedMultiplier = 1
                runtime.clockState = ClockState.SLOW
                runtime.autoSlowed = true
            } else if (!needsSlow) {
                // Condition resolved — clear override flag so auto-slow can engage again next time
                runtime.playerSpeedOverride = false
                if (runtime.autoSlowed && runtime.clockState == ClockState.SLOW) {
                    log.info("Auto-slow cleared for session {} — restoring speed", runtime.sessionId)
                    runtime.speedMultiplier = runtime.autoSlowPreviousSpeed ?: 1
                    runtime.autoSlowPreviousSpeed = null
                    runtime.clockState = ClockState.RUNNING
                    runtime.autoSlowed = false
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Health score + game-over
    // -------------------------------------------------------------------------

    /**
     * Compute a 0-100 health score for a single tick.
     *
     * - [ConvergenceStatus.NETWORK_FAILURE] → 0 (grid is unsolvable).
     * - Otherwise: 100 minus per-violation penalties, floored at 0.
     *   - CRITICAL: -20 per violation
     *   - ALARM:    -10 per violation
     *   - WARNING:  -4  per violation
     */
    private fun computeHealthScore(pfResult: PowerFlowResult): Int {
        if (pfResult.status == ConvergenceStatus.NETWORK_FAILURE) return 0
        var score = 100
        for (v in pfResult.violations) {
            score -=
                when (v.severity) {
                    ViolationSeverity.CRITICAL -> 20
                    ViolationSeverity.ALARM -> 10
                    ViolationSeverity.WARNING -> 4
                }
        }
        return score.coerceAtLeast(0)
    }

    /**
     * Update health tracking in [runtime] and return true if game-over should be triggered.
     *
     * Game-over condition: [GAME_OVER_CONSECUTIVE_LOW_HEALTH] consecutive ticks with
     * health score below [GAME_OVER_HEALTH_THRESHOLD].
     */
    private fun updateHealthAndCheckGameOver(
        runtime: SessionRuntime,
        healthScore: Int,
    ): Boolean {
        synchronized(runtime) {
            runtime.totalHealthSum += healthScore
            runtime.totalHealthSamples++
            if (healthScore < GAME_OVER_HEALTH_THRESHOLD) {
                runtime.consecutiveLowHealthTicks++
            } else {
                runtime.consecutiveLowHealthTicks = 0
            }
            return !runtime.gameOverTriggered &&
                runtime.consecutiveLowHealthTicks >= GAME_OVER_CONSECUTIVE_LOW_HEALTH
        }
    }

    /**
     * Persist the game-over state, publish the WS message, and clean up the session.
     */
    private fun triggerGameOver(runtime: SessionRuntime) {
        val avgScore: Int
        val finalScore: Int
        synchronized(runtime) {
            runtime.gameOverTriggered = true
            avgScore =
                if (runtime.totalHealthSamples > 0) {
                    (runtime.totalHealthSum / runtime.totalHealthSamples).toInt()
                } else {
                    0
                }
            finalScore = avgScore
        }

        log.warn(
            "Game over for session {} — {} consecutive ticks health < {} (averageHealth={})",
            runtime.sessionId,
            GAME_OVER_CONSECUTIVE_LOW_HEALTH,
            GAME_OVER_HEALTH_THRESHOLD,
            avgScore,
        )

        val dto =
            GameOverDto(
                finalHealthScore = finalScore,
                gridTimeManagedMinutes = runtime.gameTimeMinutes,
                averageHealthScore = avgScore,
                eventsHandledCount = 0, // TODO: wire through EventEngine when resolved-card tracking is added
            )

        // Publish WS notification before tearing down so the client gets the message.
        gameStatePublisher?.publishGameOver(runtime.sessionId, dto)

        // Persist game-over state.
        engineScope.launch(Dispatchers.IO) {
            try {
                gameSessionService.markGameOver(
                    sessionId = runtime.sessionId,
                    userId = runtime.userId,
                    finalScore = finalScore,
                )
            } catch (e: Exception) {
                log.error("Failed to persist game-over for session {}", runtime.sessionId, e)
            }
        }

        // Tear down — same as stop().
        synchronized(runtime) { runtime.clockState = ClockState.STOPPED }
        runtime.job?.cancel()
        sessions.remove(runtime.sessionId)
        eventEngine.unregister(runtime.sessionId)
        tutorialEngine.unregister(runtime.sessionId)
        challengeEngine.unregister(runtime.sessionId)
        networkRepository.evictSession(runtime.sessionId)
    }

    /**
     * Publish a GAME_OVER/won message, persist the result, and tear down the session.
     *
     * Called when [ChallengeEngine.onTick] returns true (victory condition met).
     */
    private fun triggerVictory(
        runtime: SessionRuntime,
        finalHealthScore: Int,
    ) {
        val avgScore: Int
        synchronized(runtime) {
            runtime.gameOverTriggered = true
            avgScore =
                if (runtime.totalHealthSamples > 0) {
                    (runtime.totalHealthSum / runtime.totalHealthSamples).toInt()
                } else {
                    finalHealthScore
                }
        }

        log.info(
            "Challenge victory for session {} — health={} averageHealth={}",
            runtime.sessionId,
            finalHealthScore,
            avgScore,
        )

        val dto =
            GameOverDto(
                finalHealthScore = finalHealthScore,
                gridTimeManagedMinutes = runtime.gameTimeMinutes,
                averageHealthScore = avgScore,
                eventsHandledCount = 0,
                won = true,
            )

        gameStatePublisher?.publishGameOver(runtime.sessionId, dto)

        engineScope.launch(Dispatchers.IO) {
            try {
                gameSessionService.markGameOver(
                    sessionId = runtime.sessionId,
                    userId = runtime.userId,
                    finalScore = avgScore,
                )
            } catch (e: Exception) {
                log.error("Failed to persist victory for session {}", runtime.sessionId, e)
            }
        }

        synchronized(runtime) { runtime.clockState = ClockState.STOPPED }
        runtime.job?.cancel()
        sessions.remove(runtime.sessionId)
        eventEngine.unregister(runtime.sessionId)
        tutorialEngine.unregister(runtime.sessionId)
        challengeEngine.unregister(runtime.sessionId)
        networkRepository.evictSession(runtime.sessionId)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun findAndCheckOwner(
        sessionId: String,
        userId: String,
    ): SessionRuntime {
        val runtime = sessions[sessionId] ?: throw SessionNotFoundException(sessionId)
        if (runtime.userId != userId) throw SessionNotFoundException(sessionId) // 404 — do not leak ownership
        return runtime
    }

    private fun triggerAutoSave(runtime: SessionRuntime) {
        val snapshot = runtime.toStatus()
        engineScope.launch(Dispatchers.IO) {
            saveSnapshot(
                sessionId = runtime.sessionId,
                userId = runtime.userId,
                gameTimeMinutes = snapshot.gameTimeMinutes,
                clockState = snapshot.clockState,
                speedMultiplier = snapshot.speedMultiplier,
            )
        }
    }

    private fun saveSnapshot(
        sessionId: String,
        userId: String,
        gameTimeMinutes: Long,
        clockState: ClockState,
        speedMultiplier: Int,
    ) {
        try {
            gameSessionService.save(
                sessionId = sessionId,
                userId = userId,
                gameTimeEpochMinutes = gameTimeMinutes,
                clockState = clockState,
                clockSpeedMultiplier = speedMultiplier,
            )
        } catch (e: Exception) {
            log.error("Auto-save failed for session {}", sessionId, e)
        }
    }

    companion object {
        /** Polling interval while paused, in milliseconds. */
        private const val PAUSE_POLL_INTERVAL_MS = 50L

        /** Trigger N-1 contingency analysis every N ticks (= 1 grid-hour at 10 min/tick). */
        private const val CONTINGENCY_TRIGGER_INTERVAL = 6L

        /** Health score below which a tick counts towards game-over. */
        internal const val GAME_OVER_HEALTH_THRESHOLD = 20

        /** Number of consecutive low-health ticks required to trigger game-over. */
        internal const val GAME_OVER_CONSECUTIVE_LOW_HEALTH = 3

        /** Wall-clock slot for a given speed multiplier in milliseconds. */
        fun slotMillis(speedMultiplier: Int): Long = (1_000L / speedMultiplier).coerceAtLeast(1L)
    }
}

/**
 * Mutable runtime state for one active session.
 *
 * Fields are declared `@Volatile` for single-field visibility across threads.
 * Compound state transitions (check-then-set) use `synchronized(this)` blocks
 * to ensure atomicity — `@Volatile` alone is insufficient for those operations.
 */
internal class SessionRuntime(
    val sessionId: String,
    val userId: String,
    @Volatile var clockState: ClockState,
    @Volatile var speedMultiplier: Int,
    @Volatile var gameTimeMinutes: Long,
) {
    /** Coroutine job running the tick loop. Assigned immediately after creation. */
    @Volatile
    var job: Job? = null

    /** Number of ticks executed since the loop started. */
    @Volatile
    var tickCount: Long = 0L

    /** Number of consecutive ticks that exceeded their wall-clock slot. */
    @Volatile
    var consecutiveSlips: Int = 0

    /** Whether auto-slow is currently active. */
    @Volatile
    var autoSlowed: Boolean = false

    /** Speed multiplier to restore when auto-slow clears. */
    @Volatile
    var autoSlowPreviousSpeed: Int? = null

    /** Set by pause() to signal the tick loop to save once the current tick finishes. */
    @Volatile
    var pendingSave: Boolean = false

    /**
     * Set by [TickEngineImpl.setSpeed] when the player explicitly overrides an active
     * auto-slow. Prevents [TickEngineImpl.applyAutoSlow] from immediately re-engaging
     * while the triggering condition persists. Cleared when the condition resolves.
     */
    @Volatile
    var playerSpeedOverride: Boolean = false

    // ── Health-score tracking ────────────────────────────────────────────────

    /** Consecutive tick count where the health score was below the game-over threshold. */
    @Volatile
    var consecutiveLowHealthTicks: Int = 0

    /** Running sum of per-tick health scores for average computation. */
    @Volatile
    var totalHealthSum: Long = 0L

    /** Total number of ticks sampled (denominator for the running average). */
    @Volatile
    var totalHealthSamples: Long = 0L

    /** Set to true after game-over is triggered to prevent double-firing. */
    @Volatile
    var gameOverTriggered: Boolean = false

    // ── Daily load curve (issue #383) ───────────────────────────────────────

    /**
     * Each load's original (flat) active power in MW, captured on the first
     * tick that applies the daily load curve. Null until then. Scaling is
     * always `baseLoadMw[id] * multiplier`, so it never compounds across ticks.
     */
    @Volatile
    var baseLoadMw: Map<String, Double>? = null

    /** Most recently applied daily-load-curve multiplier; 1.0 until the first tick. */
    @Volatile
    var currentLoadMultiplier: Double = 1.0

    /** Snapshot of the current state as an immutable [TickClockStatus]. Synchronized for consistency. */
    fun toStatus(): TickClockStatus =
        synchronized(this) {
            TickClockStatus(
                clockState = clockState,
                speedMultiplier = speedMultiplier,
                gameTimeMinutes = gameTimeMinutes,
                tickCount = tickCount,
                autoSlowed = autoSlowed,
            )
        }
}
