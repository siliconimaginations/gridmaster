package com.gridmaster.game

import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.NetworkViolation
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.ViolationSeverity
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
    @Value("\${gridmaster.clock.auto-save-interval:$DEFAULT_AUTO_SAVE_INTERVAL}")
    private val autoSaveInterval: Long,
) : TickEngine {
    private val log = LoggerFactory.getLogger(TickEngineImpl::class.java)

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
    ) {
        val gameSession = gameSessionService.load(sessionId, userId)
        check(!sessions.containsKey(sessionId)) {
            "Session $sessionId is already active. Use /resume if paused, or /stop then re-create if stopped."
        }

        val runtime =
            SessionRuntime(
                sessionId = sessionId,
                userId = userId,
                clockState = ClockState.RUNNING,
                speedMultiplier = gameSession.clockSpeedMultiplier.coerceIn(1, MAX_SPEED_MULTIPLIER),
                gameTimeMinutes = gameSession.gameTimeEpochMinutes,
            )
        // Atomic check-and-register; see TODO #69 for remaining TOCTOU note.
        val displaced = sessions.putIfAbsent(sessionId, runtime)
        check(displaced == null) {
            "Session $sessionId is already active. Use /resume if paused, or /stop then re-create if stopped."
        }
        runtime.job =
            engineScope.launch {
                runTickLoop(runtime)
            }
        log.info("Started tick loop for session {} at {}×", sessionId, runtime.speedMultiplier)
        return runtime.toStatus()
    }

    override fun pause(
        sessionId: String,
        userId: String,
    ) {
        val runtime = requireRuntime(sessionId)
        check(runtime.userId == userId) { "User $userId does not own session $sessionId" }
        check(runtime.clockState in setOf(ClockState.RUNNING, ClockState.SLOW)) {
            "Cannot pause session $sessionId in state ${runtime.clockState}"
        }
        runtime.clockState = ClockState.PAUSED
        // Auto-save on pause — fire-and-forget on IO dispatcher
        engineScope.launch(Dispatchers.IO) {
            saveRuntime(runtime)
        }
        log.info("Paused session {}", sessionId)
        return runtime.toStatus()
    }

    override fun resume(
        sessionId: String,
        userId: String,
    ) {
        val runtime = requireRuntime(sessionId)
        check(runtime.userId == userId) { "User $userId does not own session $sessionId" }
        check(runtime.clockState == ClockState.PAUSED) {
            "Cannot resume session $sessionId in state ${runtime.clockState}"
        }
        runtime.clockState = ClockState.RUNNING
        log.info("Resumed session {}", sessionId)
        return runtime.toStatus()
    }

    override fun setSpeed(
        sessionId: String,
        userId: String,
        multiplier: Int,
    ) {
        require(multiplier in 1..MAX_SPEED_MULTIPLIER) {
            "Speed multiplier must be in 1–$MAX_SPEED_MULTIPLIER, got $multiplier"
        }
        val runtime = requireRuntime(sessionId)
        check(runtime.userId == userId) { "User $userId does not own session $sessionId" }
        check(runtime.clockState != ClockState.STOPPED) {
            "Cannot change speed of stopped session $sessionId"
        }
        synchronized(runtime) {
            runtime.speedMultiplier = multiplier
            // If the player manually changes speed, clear any active auto-slow so
            // applyAutoSlow() cannot overwrite the player's explicit choice.
            if (runtime.autoSlowed) {
                runtime.autoSlowed = false
                runtime.autoSlowPreviousSpeed = null
                if (runtime.clockState == ClockState.SLOW) {
                    runtime.clockState = ClockState.RUNNING
                }
            }
        }
        log.info("Set speed for session {} to {}×", sessionId, multiplier)
        return runtime.toStatus()
    }

    override fun stop(
        sessionId: String,
        userId: String,
    ) {
        val runtime = requireRuntime(sessionId)
        check(runtime.userId == userId) { "User $userId does not own session $sessionId" }
        runtime.clockState = ClockState.STOPPED
        runtime.job?.cancel()
        sessions.remove(sessionId)
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
            runtime.clockState = ClockState.STOPPED
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
                runtime.clockState = ClockState.STOPPED
                return
            }

        // Step 1–4: power flow solve + snapshot update
        val pfResult =
            try {
                powerFlowService.solve(physicsSession.iidmNetwork)
            } catch (e: Exception) {
                log.error("Power flow solve failed for session {}, tick {}", runtime.sessionId, ctx.tickNumber, e)
                // TODO: #64 — transition to PAUSED instead of STOPPED so the player can inspect and resume
                runtime.clockState = ClockState.STOPPED
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

        // Step 7: EventEngine.onTick() — wired in Module 08
        // Step 8: WebSocket broadcast — wired in Module 10

        // Step 9: auto-save
        val shouldSave = autoSaveInterval > 0 && ctx.tickNumber % autoSaveInterval == 0L
        runtime.tickCount++
        runtime.gameTimeMinutes += GRID_MINUTES_PER_TICK

        if (shouldSave) {
            engineScope.launch(Dispatchers.IO) { saveRuntime(runtime) }
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
                runtime.clockState = ClockState.PAUSED
                engineScope.launch(Dispatchers.IO) { saveRuntime(runtime) }
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
            if (needsSlow && !runtime.autoSlowed && runtime.clockState == ClockState.RUNNING) {
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
            } else if (!needsSlow && runtime.autoSlowed && runtime.clockState == ClockState.SLOW) {
                log.info("Auto-slow cleared for session {} — restoring speed", runtime.sessionId)
                runtime.speedMultiplier = runtime.autoSlowPreviousSpeed ?: 1
                runtime.autoSlowPreviousSpeed = null
                runtime.clockState = ClockState.RUNNING
                runtime.autoSlowed = false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun requireRuntime(sessionId: String): SessionRuntime =
        sessions[sessionId] ?: throw IllegalStateException("Session $sessionId is not registered in TickEngine")

    private fun saveRuntime(runtime: SessionRuntime) {
        try {
            gameSessionService.save(
                sessionId = runtime.sessionId,
                userId = runtime.userId,
                gameTimeEpochMinutes = runtime.gameTimeMinutes,
                clockState = runtime.clockState,
                clockSpeedMultiplier = runtime.speedMultiplier,
            )
        } catch (e: Exception) {
            log.error("Auto-save failed for session {}", runtime.sessionId, e)
        }
    }

    companion object {
        /** Polling interval while paused, in milliseconds. */
        private const val PAUSE_POLL_INTERVAL_MS = 50L

        /** Trigger N-1 contingency analysis every N ticks (= 1 grid-hour at 10 min/tick). */
        private const val CONTINGENCY_TRIGGER_INTERVAL = 6L

        /** Wall-clock slot for a given speed multiplier in milliseconds. */
        fun slotMillis(speedMultiplier: Int): Long = (1_000L / speedMultiplier).coerceAtLeast(1L)
    }
}

/**
 * Mutable runtime state for one active session.
 *
 * Fields written by control commands (pause/resume/setSpeed) and read by the
 * tick loop are declared `@Volatile` so that changes from the calling thread
 * are visible to the coroutine thread without additional synchronisation.
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

    /** Snapshot of the current state as an immutable [TickClockStatus]. */
    fun toStatus() =
        TickClockStatus(
            clockState = clockState,
            speedMultiplier = speedMultiplier,
            gameTimeMinutes = gameTimeMinutes,
            tickCount = tickCount,
            autoSlowed = autoSlowed,
        )
}
