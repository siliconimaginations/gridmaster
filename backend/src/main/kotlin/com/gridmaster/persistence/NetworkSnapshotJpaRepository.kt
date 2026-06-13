package com.gridmaster.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Spring Data JPA repository for [NetworkSnapshotEntity].
 *
 * Provides standard CRUD operations keyed on [sessionId].
 * Used by [SqliteNetworkRepository] to persist and retrieve per-session
 * IIDM XML and [com.gridmaster.engine.model.GridNetwork] JSON snapshots.
 */
interface NetworkSnapshotJpaRepository : JpaRepository<NetworkSnapshotEntity, String> {
    /**
     * Partial update — overwrites [snapshotJson] and [updatedAt] without
     * touching [NetworkSnapshotEntity.iidmXml].
     *
     * Used by [SqliteNetworkRepository] on ticks where IIDM serialisation
     * is deferred (see [SqliteNetworkRepository.IIDM_FLUSH_INTERVAL]).
     * Returns the number of rows updated (0 if session not yet persisted).
     */
    @Modifying
    @Transactional
    @Query(
        "UPDATE NetworkSnapshotEntity e " +
            "SET e.snapshotJson = :json, e.updatedAt = :ts " +
            "WHERE e.sessionId = :id",
    )
    fun updateSnapshotJson(
        @Param("id") id: String,
        @Param("json") json: String,
        @Param("ts") ts: Instant,
    ): Int
}
