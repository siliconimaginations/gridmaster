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

@Component
class SqliteNetworkRepository(
    private val jpaRepository: NetworkSnapshotJpaRepository,
    private val objectMapper: ObjectMapper,
) : NetworkRepository {
    /**
     * Serialise [network] to XIIDM XML and [snapshot] to JSON and upsert into SQLite.
     * Wraps blocking JPA call in [Dispatchers.IO] to keep the game engine coroutine free.
     */
    override suspend fun save(
        sessionId: String,
        network: Network,
        snapshot: GridNetwork,
    ) {
        val iidmXml = serializeNetwork(network)
        val snapshotJson = objectMapper.writeValueAsString(snapshot)
        val entity =
            NetworkSnapshotEntity(
                sessionId = sessionId,
                iidmXml = iidmXml,
                snapshotJson = snapshotJson,
                updatedAt = Instant.now(),
            )
        withContext(Dispatchers.IO) { jpaRepository.save(entity) }
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
