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
 *
 * ### Thread safety (#360)
 * PowSyBl's `Network.variantManager` is not safe for concurrent mutation. The
 * background run this service performs touches the same live [Network] the
 * caller keeps solving power flow against every tick, so both the synchronous
 * trigger-time variant clone *and* the entire background analysis run must be
 * serialized against every other touch of that network (tick-loop power flow
 * solves, REST-triggered solves, other trigger calls). Callers pass the same
 * lock object they already hold (or would hold) around their own network
 * access — typically the owning session — so this service can synchronize on
 * it for the full duration of the run, not just the initial clone.
 */
interface ContingencyAnalysisService {
    /**
     * Trigger an async N-1 run. Returns immediately.
     * Results are available via [latestResult] once the run completes.
     * If [ContingencyAnalysisParameters.contingencies] is empty, the service
     * auto-builds the N-1 list from the network via [buildN1Contingencies].
     *
     * @param lock Object to synchronize on for every touch of [network] this
     *   service makes — both the immediate trigger-time clone and the full
     *   background run. Pass the same lock guarding all other access to this
     *   network (e.g. the owning session) to prevent concurrent variant
     *   mutation (#360).
     */
    fun triggerAsync(
        network: Network,
        lock: Any,
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
