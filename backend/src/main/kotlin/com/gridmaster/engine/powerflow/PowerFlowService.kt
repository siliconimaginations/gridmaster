package com.gridmaster.engine.powerflow

import com.powsybl.iidm.network.Network

/**
 * Executes power flow on the IIDM [Network] and returns a [PowerFlowResult].
 *
 * Called once per game tick, after all [NetworkMutation]s have been applied.
 * The result's [PowerFlowResult.snapshot] replaces the current [GridNetwork]
 * and is broadcast to WebSocket clients.
 *
 * PowSyBl's LoadFlow mutates the [Network] in-place (writes V, θ, I onto
 * terminals). The [Network] must be owned exclusively by the calling thread.
 */
interface PowerFlowService {
    fun solve(
        network: Network,
        parameters: PowerFlowParameters = PowerFlowParameters(),
    ): PowerFlowResult
}
