package com.gridmaster.engine.network

import com.gridmaster.engine.model.GridNetwork
import com.powsybl.iidm.network.Network

/**
 * Persistence contract for network state per game session.
 *
 * All methods are `suspend` — implementations must wrap blocking JPA/IO calls
 * in `withContext(Dispatchers.IO)` to avoid blocking the game engine coroutine.
 *
 * Two representations are stored together:
 * - IIDM XML (via PowSyBl NetworkSerDe) — authoritative for session resume
 * - GridNetwork JSON (Jackson) — fast-read derived view for the WebSocket push path
 *
 * ### Write-behind (#22)
 * Implementations may batch IIDM XML writes (expensive, O(network size)) while still
 * updating the JSON snapshot on every [save] call (cheap). [flush] forces an immediate
 * full write and must be called on session pause and shutdown to ensure durability.
 */
interface NetworkRepository {
    /**
     * Persist the derived [snapshot] JSON and — conditionally — the live IIDM [network] XML.
     *
     * Implementations may defer IIDM serialisation to every Nth call for performance.
     * The JSON snapshot is always written; IIDM is eventually consistent within the
     * implementation's flush interval. See [flush] to force an immediate IIDM write.
     */
    suspend fun save(
        sessionId: String,
        network: Network,
        snapshot: GridNetwork,
    )

    /**
     * Force an immediate full write (IIDM XML + JSON snapshot) for [sessionId].
     *
     * Must be called on session pause and shutdown to ensure the authoritative IIDM
     * is durable before the session is suspended or the process exits.
     */
    suspend fun flush(
        sessionId: String,
        network: Network,
        snapshot: GridNetwork,
    )

    /** Load the IIDM [Network] from stored XML, or null if the session has no saved state. */
    suspend fun loadIidm(sessionId: String): Network?

    /** Return the last stored [GridNetwork] snapshot, or null if none exists. */
    suspend fun latestSnapshot(sessionId: String): GridNetwork?

    /**
     * Release all in-memory state held for [sessionId].
     *
     * Implementations should drop any per-session counters or caches to prevent unbounded
     * growth when many sessions are created and ended over the server's lifetime.
     * The default implementation is a no-op for implementations with no in-memory state.
     */
    fun evictSession(sessionId: String) {}
}
