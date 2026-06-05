package com.gridmaster.engine.contingency

import com.gridmaster.engine.model.GridNetwork
import com.powsybl.iidm.network.Network

/**
 * Runs N-1 security analysis on the current network state asynchronously.
 *
 * Unlike the power flow solver (Module 02) which runs every tick, contingency
 * analysis runs in the background — triggered by topology mutations or on a
 * periodic schedule — and serves cached results to the game engine.
 *
 * Runs are debounced: if a run is already in progress when [triggerAsync] is called,
 * the new request is queued and starts immediately after the current one completes.
 * At most one queued run is held (newer replaces older).
 */
interface ContingencyAnalysisService {
    /**
     * Trigger an async N-1 run. Returns immediately.
     * Results are available via [latestResult] once the run completes.
     * If [ContingencyAnalysisParameters.contingencies] is empty, the service
     * auto-builds the N-1 list from the network via [buildN1Contingencies].
     */
    fun triggerAsync(
        network: Network,
        parameters: ContingencyAnalysisParameters = ContingencyAnalysisParameters(),
    )

    /** Latest cached result, or null if no run has completed yet. */
    fun latestResult(): ContingencyAnalysisResult?

    /**
     * Build the default N-1 contingency list from the current [GridNetwork] snapshot.
     * Produces one [Contingency] per line, transformer, and online generator.
     */
    fun buildN1Contingencies(network: GridNetwork): List<Contingency>
}
