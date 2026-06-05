package com.gridmaster.engine.model

/**
 * A player or event action that mutates the IIDM network.
 * Mutations are applied to the live PowSyBl Network object via [IidmNetworkMapper.applyMutation].
 * After applying, the network is re-solved and a new [GridNetwork] snapshot is produced.
 *
 * This sealed class is the single gateway for all network state changes —
 * player commands and environment events both produce [NetworkMutation] instances,
 * making them auditable, testable, and replayable.
 */
sealed class NetworkMutation {

    /** Set a generator's active power output setpoint in MW. */
    data class SetGeneratorOutput(
        val generatorId: String,
        val targetPMw: Double,
    ) : NetworkMutation()

    /** Set a generator's voltage setpoint at its terminal bus (per unit). */
    data class SetGeneratorVoltage(
        val generatorId: String,
        val targetVoltagePu: Double,
    ) : NetworkMutation()

    /** Disconnect both terminals of a line (simulates a protective trip or planned outage). */
    data class TripLine(val lineId: String) : NetworkMutation()

    /** Reconnect both terminals of a previously tripped line. */
    data class ConnectLine(val lineId: String) : NetworkMutation()

    /** Disconnect a generator's terminal (take it offline). */
    data class TripGenerator(val generatorId: String) : NetworkMutation()

    /** Reconnect a previously offline generator. */
    data class ConnectGenerator(val generatorId: String) : NetworkMutation()

    /** Move a two-winding transformer's ratio tap changer to the given step. */
    data class SetTapPosition(
        val transformerId: String,
        val tapPosition: Int,
    ) : NetworkMutation()

    /** Update a load's active power setpoint. Reactive power is optional; unchanged if null. */
    data class SetLoadPower(
        val loadId: String,
        val activePowerMw: Double,
        val reactivePowerMvar: Double? = null,
    ) : NetworkMutation()

    /** Reconnect a previously disconnected load. */
    data class ConnectLoad(val loadId: String) : NetworkMutation()

    /** Disconnect a load (demand curtailment or deliberate isolation). */
    data class DisconnectLoad(val loadId: String) : NetworkMutation()

    /** Set the number of active sections on a shunt compensator. */
    data class SetShuntSections(
        val shuntCompensatorId: String,
        val sectionCount: Int,
    ) : NetworkMutation()
}
