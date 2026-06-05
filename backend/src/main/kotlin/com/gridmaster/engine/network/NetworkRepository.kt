package com.gridmaster.engine.network

import com.gridmaster.engine.model.GridNetwork
import com.powsybl.iidm.network.Network

/**
 * Persistence contract for network state per game session.
 *
 * Two representations are stored together:
 * - IIDM XML (via PowSyBl NetworkSerDe) — authoritative for session resume
 * - GridNetwork JSON (Jackson) — fast-read derived view for the WebSocket push path
 */
interface NetworkRepository {

    /** Persist both the live IIDM [network] and its derived [snapshot] for [sessionId]. */
    fun save(sessionId: String, network: Network, snapshot: GridNetwork)

    /** Load the IIDM [Network] from stored XML, or null if the session has no saved state. */
    fun loadIidm(sessionId: String): Network?

    /** Return the last stored [GridNetwork] snapshot, or null if none exists. */
    fun latestSnapshot(sessionId: String): GridNetwork?
}
