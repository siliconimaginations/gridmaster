package com.gridmaster.session

import com.gridmaster.api.dto.NetworkMutationDto
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.powsybl.iidm.network.Network
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of active game sessions.
 *
 * Each [GameSession] holds the live PowSyBl [Network] and the latest cached
 * [PowerFlowResult]. Backed by [ConcurrentHashMap] for thread-safe access.
 *
 * Session lifecycle (create/destroy) is managed by Module 06 (SessionModel).
 * This component provides the lookup primitive used by the physics API layer.
 */
@Component
class SessionRegistry {
    private val log = LoggerFactory.getLogger(SessionRegistry::class.java)
    private val sessions = ConcurrentHashMap<String, GameSession>()

    fun get(sessionId: String): GameSession? = sessions[sessionId]

    fun register(session: GameSession) {
        sessions[session.sessionId] = session
        log.info("Session registered: {}", session.sessionId)
    }

    fun remove(sessionId: String) {
        sessions.remove(sessionId)?.also { log.info("Session removed: {}", sessionId) }
    }

    fun activeCount(): Int = sessions.size
}

/**
 * Represents one active game session.
 *
 * [network] — the live PowSyBl network.
 * [latestPowerFlowResult] — last power flow result; null before first solve.
 */
data class GameSession(
    val sessionId: String,
    val network: Network,
    @Volatile var latestPowerFlowResult: PowerFlowResult? = null,
) {
    private val log = LoggerFactory.getLogger(GameSession::class.java)

    fun updatePowerFlowResult(result: PowerFlowResult) {
        latestPowerFlowResult = result
    }

    /**
     * Applies [mutations] to [network] in order.
     *
     * Supported types: `TRIP_LINE`, `RECONNECT_LINE`, `SET_GENERATOR_OUTPUT`.
     * Throws [IllegalArgumentException] for unknown types or invalid targets.
     */
    fun applyMutations(mutations: List<NetworkMutationDto>) {
        mutations.forEach { mutation ->
            when (mutation.type.uppercase()) {
                "TRIP_LINE" -> {
                    val line =
                        network.getLine(mutation.targetId)
                            ?: throw IllegalArgumentException("Line '${mutation.targetId}' not found")
                    line.terminal1.disconnect()
                    line.terminal2.disconnect()
                    log.debug("TRIP_LINE: {}", mutation.targetId)
                }
                "RECONNECT_LINE" -> {
                    val line =
                        network.getLine(mutation.targetId)
                            ?: throw IllegalArgumentException("Line '${mutation.targetId}' not found")
                    line.terminal1.connect()
                    line.terminal2.connect()
                    log.debug("RECONNECT_LINE: {}", mutation.targetId)
                }
                "SET_GENERATOR_OUTPUT" -> {
                    val gen =
                        network.getGenerator(mutation.targetId)
                            ?: throw IllegalArgumentException("Generator '${mutation.targetId}' not found")
                    val targetMw =
                        (mutation.parameters["targetMw"] as? Number)?.toDouble()
                            ?: throw IllegalArgumentException("SET_GENERATOR_OUTPUT requires 'targetMw'")
                    require(targetMw >= gen.minP && targetMw <= gen.maxP) {
                        "Target $targetMw MW outside [${gen.minP}, ${gen.maxP}] for '${mutation.targetId}'"
                    }
                    gen.targetP = targetMw
                    log.debug("SET_GENERATOR_OUTPUT: {} → {} MW", mutation.targetId, targetMw)
                }
                else -> throw IllegalArgumentException("Unknown mutation type: '${mutation.type}'")
            }
        }
    }
}
