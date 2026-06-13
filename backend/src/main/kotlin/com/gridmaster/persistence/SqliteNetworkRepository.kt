package com.gridmaster.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.network.NetworkRepository
import com.powsybl.iidm.network.Network
import com.powsybl.iidm.serde.NetworkSerDe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
class SqliteNetworkRepository(
    private val jpaRepository: NetworkSnapshotJpaRepository,
    private val objectMapper: ObjectMapper,
) : NetworkRepository {
    // TODO #189: evict stale entries when a session ends to prevent unbounded map growth
    private val saveCounters = ConcurrentHashMap<String, AtomicLong>()

    companion object {
        /**
         * Serialise IIDM XML to the database every [IIDM_FLUSH_INTERVAL]th [save] call.
         * Intermediate ticks update [NetworkSnapshotEntity.snapshotJson] only (cheaper).
         *
         * The JSON snapshot is always current; IIDM is eventually consistent within
         * [IIDM_FLUSH_INTERVAL] ticks. Session resume always reads IIDM, so state is
         * authoritative as of the last flush. See [flush] for forced writes on pause/shutdown.
         *
         * Set to 1 to disable batching (write-through — useful for tests and debugging).
         */
        const val IIDM_FLUSH_INTERVAL = 10L
    }

    /**
     * Persist [snapshot] JSON every call; serialise and persist [network] IIDM XML every
     * [IIDM_FLUSH_INTERVAL] calls or on the first call for a given [sessionId].
     *
     * If the partial JSON update finds no existing row (e.g. first call after restart),
     * it falls back to a full write — so the IIDM is always available for session resume.
     *
     * Wraps blocking JPA call in [Dispatchers.IO] to keep the game engine coroutine free.
     */
    override suspend fun save(
        sessionId: String,
        network: Network,
        snapshot: GridNetwork,
    ) {
        val snapshotJson = objectMapper.writeValueAsString(snapshot)
        val tick = saveCounters.computeIfAbsent(sessionId) { AtomicLong(0L) }.incrementAndGet()
        val needsIidmFlush = tick == 1L || tick % IIDM_FLUSH_INTERVAL == 0L

        withContext(Dispatchers.IO) {
            if (needsIidmFlush) {
                val iidmXml = serializeNetwork(network)
                jpaRepository.save(
                    NetworkSnapshotEntity(
                        sessionId = sessionId,
                        iidmXml = iidmXml,
                        snapshotJson = snapshotJson,
                        updatedAt = Instant.now(),
                    ),
                )
            } else {
                // JSON-only update — IIDM serialisation deferred until next flush tick (#22).
                val updated = jpaRepository.updateSnapshotJson(sessionId, snapshotJson, Instant.now())
                if (updated == 0) {
                    // No existing row (e.g. first call after server restart) — full write.
                    val iidmXml = serializeNetwork(network)
                    jpaRepository.save(
                        NetworkSnapshotEntity(
                            sessionId = sessionId,
                            iidmXml = iidmXml,
                            snapshotJson = snapshotJson,
                            updatedAt = Instant.now(),
                        ),
                    )
                }
            }
            Unit
        }
    }

    /**
     * Force an immediate full write (IIDM XML + JSON) regardless of [IIDM_FLUSH_INTERVAL].
     *
     * Should be called on session pause and shutdown to ensure the authoritative IIDM state
     * is durable before the session is persisted or discarded. Resets the per-session tick
     * counter so the next [save] call after resume starts a fresh interval.
     */
    override suspend fun flush(
        sessionId: String,
        network: Network,
        snapshot: GridNetwork,
    ) {
        val iidmXml = serializeNetwork(network)
        val snapshotJson = objectMapper.writeValueAsString(snapshot)
        withContext(Dispatchers.IO) {
            jpaRepository.save(
                NetworkSnapshotEntity(
                    sessionId = sessionId,
                    iidmXml = iidmXml,
                    snapshotJson = snapshotJson,
                    updatedAt = Instant.now(),
                ),
            )
        }
        saveCounters[sessionId]?.set(0L)
    }

    /**
     * Load the IIDM [Network] for [sessionId] from its stored XIIDM XML.
     * Returns null if no snapshot exists for the session.
     */
    override suspend fun loadIidm(sessionId: String): Network? {
        val entity = findEntity(sessionId) ?: return null
        return deserializeNetwork(entity.iidmXml)
    }

    /**
     * Return the last stored [GridNetwork] snapshot for [sessionId], or null if none exists.
     */
    override suspend fun latestSnapshot(sessionId: String): GridNetwork? {
        val entity = findEntity(sessionId) ?: return null
        return objectMapper.readValue(entity.snapshotJson, GridNetwork::class.java)
    }

    private suspend fun findEntity(sessionId: String): NetworkSnapshotEntity? =
        withContext(Dispatchers.IO) { jpaRepository.findById(sessionId).orElse(null) }

    // -------------------------------------------------------------------------
    // Serialisation helpers
    // -------------------------------------------------------------------------

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
