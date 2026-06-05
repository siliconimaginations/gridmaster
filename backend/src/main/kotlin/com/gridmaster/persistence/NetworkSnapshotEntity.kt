package com.gridmaster.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * JPA entity that stores the full network state for one game session.
 *
 * [iidmXml]      — PowSyBl XIIDM serialisation of the live Network object.
 *                  This is the authoritative source for session resume;
 *                  it round-trips through PowSyBl's NetworkSerDe without loss.
 *
 * [snapshotJson] — Jackson-serialised GridNetwork (derived view, fast reads).
 *                  Used by the WebSocket push path; re-derived from IIDM after each tick.
 */
@Entity
@Table(name = "network_snapshots")
class NetworkSnapshotEntity(
    @Id
    val sessionId: String,
    /** Full XIIDM network XML. May be several hundred KB for large networks. */
    @Column(columnDefinition = "TEXT", nullable = false)
    val iidmXml: String,
    /** JSON representation of the last GridNetwork snapshot. */
    @Column(columnDefinition = "TEXT", nullable = false)
    val snapshotJson: String,
    val updatedAt: Instant,
)
