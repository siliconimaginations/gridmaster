package com.gridmaster.game

import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.api.SessionNotFoundException
import com.gridmaster.api.websocket.GameStatePublisher
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.NetworkViolation
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.ViolationSeverity
import com.gridmaster.game.event.EventEngine
import kotlinx.coroutines.CompletableDeferred
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
 * ### Pause/resume
 * The tick loop suspends on [SessionRuntime.pauseSignal] while paused — zero CPU cost.
 * [pause] creates a fresh [CompletableDeferred] signal; [resume] and [stop] complete it
 * to wake the loop. `CompletableDeferred.await()` is idiomatic for "wait for an external
 * signal" and avoids any lock-ownership concerns that arise with [kotlinx.coroutines.sync.Mutex].
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
    @Value("\${gridmaster.clock.auto-save-interval:$DEFAULT_AUTO_SAVE_INTERVAL}")
    private val autoSaveInterval: Long,
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
                // Set inside synchronized so clockState and pauseSignal are always updated atomically —
                // prevents the tick loop from seeing PAUSED with a null signal between the two writes.
                runtime.pauseSignal = CompletableDeferred()
                runtime.pendingSave = true
                runtime.toStatus()
            }
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
        // Complete the signal — wakes the tick loop coroutine suspended in await().
        val signal = runtime.pauseSignal
        runtime.pauseSignal = null
        signal?.complete(Unit)
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
        // Complete the pause signal (if any) before cancelling — the CancellationException
        // from job.cancel() propagates through the re-awakened await() suspension point.
        val signal = runtime.pauseSignal
        runtime.pauseSignal = null
        signal?.complete(Unit)
        runtime.job?.cancel()
        sessions.remove(sessionId)
        eventEngine.unregister(sessionId)
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
                        // Capture the reference before suspending so resume() nulling the field
                        // after complete() does not affect this await() call.
                        val signal = runtime.pauseSignal
                        signal?.await()
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
        val pfResult =
            try {
                powerFlowService.solve(physicsSession.iidmNetwork)
            } catch (e: Exception) {
                log.error("Power flow solve failed for session {}, tick {}", runtime.sessionId, ctx.tickNumber, e)
                // Pause (not stop) so the player can inspect the grid and potentially resume. Closes #64.
                synchronized(runtime) {
                    runtime.clockState = ClockState.PAUSED
                    runtime.pauseSignal = CompletableDeferred()
                }
                triggerAutoSave(runtime)
                return
            }

        physicsSession.latestSnapshot = pfResult.snapshot
        physicsSession.latestPowerFlowResult = pfResult

        // Step 5: check auto-slow conditions
        applyAutoSlow(runtime, pfResult)

        // Step 6: trigger N-1 contingency analysis every 6 ticks (1 grid-hour)
        if (ctx.tickNumber % CONTINGENCY_TRIGGER_INTERVAL == 0L) {
            contingencyAnalysisService.triggerAsync(physicsSession.iidmNetwork)
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
                synchronized(runtime) {
                    runtime.clockState = ClockState.PAUSED
                    runtime.pauseSignal = CompletableDeferred()
                }
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
        /** Trigger N-1 contingency analysis every N ticks (= 1 grid-hour at 10 min/tick). */
        private const val CONTINGENCY_TRIGGER_INTERVAL = 6L

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

    /**
     * A [CompletableDeferred] that the tick loop suspends on via [CompletableDeferred.await]
     * while this session is paused — zero CPU cost.
     * A fresh deferred is created on each [TickEngineImpl.pause] call; [TickEngineImpl.resume]
     * and [TickEngineImpl.stop] call [CompletableDeferred.complete] to wake the coroutine.
     * Set to null when not paused.
     *
     * Using [CompletableDeferred] rather than [kotlinx.coroutines.sync.Mutex] avoids any
     * lock-ownership ambiguity: any coroutine may call [CompletableDeferred.complete] to
     * signal the waiting loop, with no requirement to be the original lock holder.
     */
    @Volatile
    var pauseSignal: CompletableDeferred<Unit>? = null

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
