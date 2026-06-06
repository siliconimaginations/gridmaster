package com.gridmaster.game

import com.gridmaster.api.PhysicsSession
import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.api.SessionNotFoundException
import com.gridmaster.engine.network.IidmNetworkMapper
import com.gridmaster.persistence.GameSessionEntity
import com.gridmaster.persistence.GameSessionJpaRepository
import com.powsybl.iidm.network.Network
import com.powsybl.iidm.serde.NetworkSerDe
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

/**
 * Manages the lifecycle of [GameSession] objects — creation, persistence, resumption,
 * and deletion.
 *
 * Responsibilities:
 * - Load a seed network from [PresetNetworkFactory] on create.
 * - Persist the session (IIDM XML + metadata) via [GameSessionJpaRepository].
 * - Populate [PhysicsSessionStore] so the physics API can operate immediately.
 * - Re-hydrate [PhysicsSessionStore] from the DB when a session is resumed after a
 *   server restart.
 *
 * All methods are synchronous (blocking). The game engine (Module 07) will call
 * [save] from a coroutine via [kotlinx.coroutines.Dispatchers.IO]; that dispatch
 * lives in the caller, not here, keeping this service testable without coroutine
 * infrastructure.
 */
@Service
class GameSessionService(
    private val jpaRepository: GameSessionJpaRepository,
    private val physicsSessionStore: PhysicsSessionStore,
    private val networkMapper: IidmNetworkMapper,
) {
    private val log = LoggerFactory.getLogger(GameSessionService::class.java)

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /**
     * Create a new session for [userId] with [mode], [displayName], and seed network
     * from [networkPreset]. Persists the session and registers it in [PhysicsSessionStore].
     *
     * @throws IllegalArgumentException if [networkPreset] is unknown.
     */
    fun create(
        userId: String,
        mode: GameMode,
        displayName: String,
        networkPreset: String,
    ): GameSession {
        val network = PresetNetworkFactory.create(networkPreset)
        val iidmXml = serializeNetwork(network)
        val sessionId = UUID.randomUUID().toString()
        val now = Instant.now()

        val entity =
            GameSessionEntity(
                id = sessionId,
                userId = userId,
                mode = mode,
                displayName = displayName,
                iidmXml = iidmXml,
                createdAt = now,
                updatedAt = now,
            )
        jpaRepository.save(entity)
        log.info("Created session {} (mode={}, preset={}, user={})", sessionId, mode, networkPreset, userId)

        // Register in the in-memory physics store for immediate use.
        val snapshot = networkMapper.toGridNetwork(network)
        physicsSessionStore.create(sessionId, network, snapshot)

        return entity.toDomain()
    }

    // -------------------------------------------------------------------------
    // List / Load
    // -------------------------------------------------------------------------

    /** Return all sessions belonging to [userId], ordered by [GameSessionEntity.updatedAt] descending. */
    fun listForUser(userId: String): List<GameSession> =
        jpaRepository.findAllByUserId(userId)
            .sortedByDescending { it.updatedAt }
            .map { it.toDomain() }

    /**
     * Load a session by [sessionId], ensuring it belongs to [userId].
     *
     * If the session is not already live in [PhysicsSessionStore] (e.g. after a server
     * restart), the IIDM XML is deserialised and a new [PhysicsSession] is registered.
     *
     * @throws SessionNotFoundException if the session does not exist or belongs to another user.
     */
    fun load(
        sessionId: String,
        userId: String,
    ): GameSession {
        val entity = requireOwned(sessionId, userId)

        // Re-hydrate physics store if the session is not already live.
        if (physicsSessionStore.find(sessionId) == null) {
            val network = deserializeNetwork(entity.iidmXml)
            val snapshot = networkMapper.toGridNetwork(network)
            physicsSessionStore.create(sessionId, network, snapshot)
            log.info("Re-hydrated session {} into PhysicsSessionStore", sessionId)
        }

        return entity.toDomain()
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    /**
     * Persist the current IIDM state of a live [PhysicsSession] back to the DB.
     * Called by the game engine (Module 07) on auto-save ticks and on pause.
     *
     * @throws SessionNotFoundException if [sessionId] is not live or not owned by [userId].
     */
    fun save(
        sessionId: String,
        userId: String,
        gameTimeEpochMinutes: Long,
        clockState: ClockState,
        clockSpeedMultiplier: Int,
    ): GameSession {
        val physicsSession =
            physicsSessionStore.find(sessionId)
                ?: throw SessionNotFoundException(sessionId)
        val existingEntity = requireOwned(sessionId, userId)

        val iidmXml = serializeNetwork(physicsSession.iidmNetwork)
        val updated =
            GameSessionEntity(
                id = existingEntity.id,
                userId = existingEntity.userId,
                mode = existingEntity.mode,
                displayName = existingEntity.displayName,
                iidmXml = iidmXml,
                gameTimeEpochMinutes = gameTimeEpochMinutes,
                clockState = clockState,
                clockSpeedMultiplier = clockSpeedMultiplier,
                createdAt = existingEntity.createdAt,
                updatedAt = Instant.now(),
                completedAt = existingEntity.completedAt,
            )
        jpaRepository.save(updated)
        log.debug("Auto-saved session {} at game-time {} min", sessionId, gameTimeEpochMinutes)
        return updated.toDomain()
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /**
     * Delete the session from the DB and remove it from [PhysicsSessionStore].
     *
     * @throws SessionNotFoundException if the session does not exist or belongs to another user.
     */
    fun delete(
        sessionId: String,
        userId: String,
    ) {
        requireOwned(sessionId, userId)
        jpaRepository.deleteById(sessionId)
        physicsSessionStore.remove(sessionId)
        log.info("Deleted session {} for user {}", sessionId, userId)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun requireOwned(
        sessionId: String,
        userId: String,
    ): GameSessionEntity {
        val entity =
            jpaRepository.findById(sessionId).orElse(null)
                ?: throw SessionNotFoundException(sessionId)
        if (entity.userId != userId) throw SessionNotFoundException(sessionId)
        return entity
    }

    private fun serializeNetwork(network: Network): String {
        val baos = ByteArrayOutputStream()
        NetworkSerDe.write(network, baos)
        return baos.toString(Charsets.UTF_8)
    }

    private fun deserializeNetwork(xml: String): Network {
        val bais = ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        return NetworkSerDe.read(bais)
    }
}

// -------------------------------------------------------------------------
// Entity → domain mapping
// -------------------------------------------------------------------------

private fun GameSessionEntity.toDomain() =
    GameSession(
        id = id,
        userId = userId,
        mode = mode,
        displayName = displayName,
        iidmXml = iidmXml,
        gameTimeEpochMinutes = gameTimeEpochMinutes,
        clockState = clockState,
        clockSpeedMultiplier = clockSpeedMultiplier,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )
