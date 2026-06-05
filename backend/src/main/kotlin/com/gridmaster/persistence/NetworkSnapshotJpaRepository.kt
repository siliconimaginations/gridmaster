package com.gridmaster.persistence

import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data JPA repository for [NetworkSnapshotEntity].
 *
 * Provides standard CRUD operations keyed on [sessionId].
 * Used by [SqliteNetworkRepository] to persist and retrieve per-session
 * IIDM XML and [com.gridmaster.engine.model.GridNetwork] JSON snapshots.
 */
interface NetworkSnapshotJpaRepository : JpaRepository<NetworkSnapshotEntity, String>
