package com.gridmaster.api

import com.gridmaster.engine.contingency.ContingencyAnalysisResult
import com.gridmaster.engine.dispatch.DispatchResult
import com.gridmaster.engine.dispatch.UcResult
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.powsybl.iidm.network.Network
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight in-memory store that associates a session ID with the live
 * PowSyBl [Network] object and the most recent results from each physics service.
 *
 * This is a deliberately thin stub scoped to Stage 3 / Module 05.
 * Module 06 (Session Model) will replace it with a richer session lifecycle
 * (persistence, save/resume, multi-player isolation).
 *
 * Thread safety: all mutable state is guarded by the [PhysicsSession] lock or
 * [ConcurrentHashMap] structural operations.
 */
@Component
class PhysicsSessionStore {
    private val sessions = ConcurrentHashMap<String, PhysicsSession>()

    /** Create a new session from a live PowSyBl [network]. Overwrites any existing session with the same ID. */
    fun create(
        sessionId: String,
        network: Network,
        initialSnapshot: GridNetwork,
    ): PhysicsSession {
        val session = PhysicsSession(sessionId, network, initialSnapshot)
        sessions[sessionId] = session
        return session
    }

    /** Return the session or null if not found. */
    fun find(sessionId: String): PhysicsSession? = sessions[sessionId]

    /** Return the session or throw [SessionNotFoundException]. */
    fun get(sessionId: String): PhysicsSession = sessions[sessionId] ?: throw SessionNotFoundException(sessionId)

    /** Remove and return the session, or null if it didn't exist. */
    fun remove(sessionId: String): PhysicsSession? = sessions.remove(sessionId)

    fun sessionIds(): Set<String> = sessions.keys.toSet()
}

/**
 * Mutable container for one game session's physics state.
 * Mutated by [com.gridmaster.api.PhysicsController] and the game engine (Module 07).
 */
data class PhysicsSession(
    val sessionId: String,
    /** Live PowSyBl Network — mutated by applying [com.gridmaster.engine.model.NetworkMutation]s. */
    @Volatile var iidmNetwork: Network,
    /** Latest GridNetwork snapshot produced after the most recent power flow solve. */
    @Volatile var latestSnapshot: GridNetwork,
    @Volatile var latestPowerFlowResult: PowerFlowResult? = null,
    @Volatile var latestContingencyResult: ContingencyAnalysisResult? = null,
    @Volatile var latestDispatchResult: DispatchResult? = null,
    @Volatile var latestUcResult: UcResult? = null,
)

/** Thrown when a requested session does not exist in [PhysicsSessionStore]. */
class SessionNotFoundException(val sessionId: String) :
    RuntimeException("Session not found: $sessionId")

/** Thrown when a [com.gridmaster.api.dto.NetworkMutationDto] cannot be mapped to a domain mutation. */
class InvalidMutationException(message: String) : RuntimeException(message)
