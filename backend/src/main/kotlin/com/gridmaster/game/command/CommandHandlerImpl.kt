package com.gridmaster.game.command

import com.gridmaster.api.InvalidMutationException
import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.engine.contingency.ContingencyAnalysisParameters
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.dispatch.DispatchParameters
import com.gridmaster.engine.dispatch.DispatchService
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.NetworkMutation
import com.gridmaster.engine.network.IidmNetworkMapper
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.EquipmentType
import com.gridmaster.engine.powerflow.NetworkViolation
import com.gridmaster.engine.powerflow.PowerFlowParameters
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.game.TickEngine
import com.gridmaster.game.event.EventEngine
import com.gridmaster.game.tutorial.TutorialEngine
import com.powsybl.iidm.network.VariantManagerConstants
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Default [CommandHandler] implementation.
 *
 * ### Validation
 * Each [PlayerCommand] subtype has a [validate] function that checks domain
 * constraints against the current [GridNetwork] snapshot. Validation failures
 * short-circuit to a [CommandResult] with [CommandResult.success] = false
 * and zero mutations applied.
 *
 * ### Atomicity
 * A PowSyBl variant clone is taken before the mutation loop. If any mutation
 * fails (or validation rejects in batch mode), the INITIAL variant is restored
 * from the rollback clone and the error is surfaced without partial application.
 *
 * ### Power flow
 * A single power flow solve follows all mutations. The result feeds violation
 * scanning and alert generation. `NETWORK_FAILURE` convergence is not an error —
 * the mutation was applied and the grid failure is a valid (and educational) game state.
 *
 * ### Contingency analysis
 * Triggered asynchronously when any mutation changes network topology
 * (trip/connect line, generator, or load).
 */
@Component
class CommandHandlerImpl(
    private val sessionStore: PhysicsSessionStore,
    private val networkMapper: IidmNetworkMapper,
    private val powerFlowService: PowerFlowService,
    private val contingencyService: ContingencyAnalysisService,
    private val dispatchService: DispatchService,
    private val tickEngine: TickEngine,
    private val eventEngine: EventEngine,
    private val tutorialEngine: TutorialEngine,
) : CommandHandler {
    private val log = LoggerFactory.getLogger(CommandHandlerImpl::class.java)

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    override fun handle(
        command: PlayerCommand,
        userId: String,
    ): CommandResult {
        val sessionId = command.sessionId
        val session = sessionStore.get(sessionId)

        // Clock and event card commands do not mutate the network — handle separately.
        when (command) {
            is PlayerCommand.PauseClock -> {
                tickEngine.pause(sessionId, userId)
                return noMutationResult(session.latestSnapshot, session.latestPowerFlowResult, command)
            }
            is PlayerCommand.ResumeClock -> {
                tickEngine.resume(sessionId, userId)
                return noMutationResult(session.latestSnapshot, session.latestPowerFlowResult, command)
            }
            is PlayerCommand.SetClockSpeed -> {
                val validation = validateSetClockSpeed(command)
                if (validation != null) return rejectedResult(session, command, validation)
                tickEngine.setSpeed(sessionId, userId, command.multiplier)
                return noMutationResult(session.latestSnapshot, session.latestPowerFlowResult, command)
            }
            is PlayerCommand.RespondToEventCard -> {
                val optionIndex =
                    command.optionId.toIntOrNull()
                        ?: return rejectedResult(
                            session,
                            command,
                            "optionId must be a numeric string, got: '${command.optionId}'",
                        )
                eventEngine.resolveCard(sessionId, command.cardId, optionIndex)
                return noMutationResult(session.latestSnapshot, session.latestPowerFlowResult, command)
            }
            else -> { /* falls through to mutation pipeline below */ }
        }

        // Validate → translate → apply → power flow pipeline
        val snapshot = session.latestSnapshot
        val validationError = validate(command, snapshot)
        if (validationError != null) return rejectedResult(session, command, validationError)

        val mutations = translate(command, snapshot)
        val result = runMutationPipeline(sessionId, mutations, listOf(command), session.latestPowerFlowResult)
        if (result.success) {
            tutorialEngine.onCommand(sessionId, command.commandType())
        }
        return result
    }

    override fun handleBatch(
        commands: List<PlayerCommand>,
        userId: String,
    ): CommandResult {
        require(commands.isNotEmpty()) { "Batch must contain at least one command" }
        val sessionId = commands.first().sessionId
        require(commands.all { it.sessionId == sessionId }) {
            "All commands in a batch must target the same session"
        }
        val session = sessionStore.get(sessionId)
        val snapshot = session.latestSnapshot

        // Validate ALL commands first — all-or-nothing
        val outcomes = mutableListOf<CommandOutcome>()
        var anyRejected = false
        for (command in commands) {
            val error = validate(command, snapshot)
            if (error != null) {
                outcomes.add(
                    CommandOutcome(
                        commandType = command.commandType(),
                        success = false,
                        rejectionReason = error,
                    ),
                )
                anyRejected = true
            } else {
                outcomes.add(CommandOutcome(commandType = command.commandType(), success = true))
            }
        }

        if (anyRejected) {
            log.info(
                "CommandHandler: batch for session {} rejected — {} of {} commands failed validation",
                sessionId,
                outcomes.count { !it.success },
                commands.size,
            )
            return CommandResult(
                success = false,
                snapshot = snapshot,
                powerFlowResult = session.latestPowerFlowResult ?: emptyPowerFlowResult(snapshot),
                newAlerts = emptyList(),
                commandOutcomes = outcomes,
            )
        }

        // All valid — collect mutations and run a single pipeline pass
        val allMutations = commands.flatMap { translate(it, snapshot) }
        return runMutationPipeline(sessionId, allMutations, commands, session.latestPowerFlowResult)
    }

    override fun applyMutations(
        mutations: List<NetworkMutation>,
        sessionId: String,
    ): CommandResult {
        val session = sessionStore.get(sessionId)
        return runMutationPipeline(sessionId, mutations, emptyList(), session.latestPowerFlowResult)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Apply [mutations] atomically, run power flow, scan for violations, and
     * optionally trigger async N-1 if topology changed.
     *
     * [sourceCommands] is used to build per-command [CommandOutcome]s; it may be
     * empty for [applyMutations] (internal callers).
     */
    private fun runMutationPipeline(
        sessionId: String,
        mutations: List<NetworkMutation>,
        sourceCommands: List<PlayerCommand>,
        previousPowerFlowResult: PowerFlowResult?,
    ): CommandResult {
        val session = sessionStore.get(sessionId)

        return synchronized(session) {
            val network = session.iidmNetwork

            // Clone current state before mutations. On failure we restore INITIAL from the
            // rollback variant rather than reassigning session.iidmNetwork, keeping it a val.
            // cloneVariant is cheaper than an XML round-trip: it copies in-memory field data.
            val rollbackId = "rollback-${System.nanoTime()}"
            network.variantManager.cloneVariant(
                VariantManagerConstants.INITIAL_VARIANT_ID,
                rollbackId,
                false,
            )

            fun rollback() {
                runCatching {
                    network.variantManager.cloneVariant(
                        rollbackId,
                        VariantManagerConstants.INITIAL_VARIANT_ID,
                        true,
                    )
                }.onFailure {
                    log.warn("CommandHandler: rollback variant restore failed for session {}: {}", sessionId, it.message)
                }
                runCatching { network.variantManager.removeVariant(rollbackId) }
            }

            // Apply all mutations; roll back and surface error on first failure.
            try {
                for (mutation in mutations) {
                    networkMapper.applyMutation(network, mutation)
                        .getOrElse { ex ->
                            throw InvalidMutationException(ex.message ?: "Mutation failed: $mutation")
                        }
                }
            } catch (ex: InvalidMutationException) {
                rollback()
                log.warn("CommandHandler: mutation failed for session {}: {}", sessionId, ex.message)
                val currentSnapshot = session.latestSnapshot
                val outcomes =
                    if (sourceCommands.isEmpty()) {
                        listOf(
                            CommandOutcome(
                                commandType = "applyMutations",
                                success = false,
                                rejectionReason = ex.message,
                            ),
                        )
                    } else {
                        sourceCommands.map {
                            CommandOutcome(
                                commandType = it.commandType(),
                                success = false,
                                rejectionReason = ex.message,
                            )
                        }
                    }
                return@synchronized CommandResult(
                    success = false,
                    snapshot = currentSnapshot,
                    powerFlowResult = previousPowerFlowResult ?: emptyPowerFlowResult(currentSnapshot),
                    newAlerts = emptyList(),
                    commandOutcomes = outcomes,
                )
            }

            // Run power flow — NETWORK_FAILURE is a valid game state, not an exception.
            val powerFlowResult =
                try {
                    powerFlowService.solve(network, PowerFlowParameters())
                } catch (ex: Exception) {
                    log.error("CommandHandler: power flow threw for session {}: {}", sessionId, ex.message, ex)
                    rollback()
                    val currentSnapshot = session.latestSnapshot
                    val outcomes =
                        if (sourceCommands.isEmpty()) {
                            listOf(
                                CommandOutcome(
                                    commandType = "applyMutations",
                                    success = false,
                                    rejectionReason = "Power flow error: ${ex.message}",
                                ),
                            )
                        } else {
                            sourceCommands.map {
                                CommandOutcome(
                                    it.commandType(),
                                    success = false,
                                    rejectionReason = "Power flow error: ${ex.message}",
                                )
                            }
                        }
                    return@synchronized CommandResult(
                        success = false,
                        snapshot = currentSnapshot,
                        powerFlowResult = previousPowerFlowResult ?: emptyPowerFlowResult(currentSnapshot),
                        newAlerts = emptyList(),
                        commandOutcomes = outcomes,
                    )
                }

            // Success: discard rollback variant.
            runCatching { network.variantManager.removeVariant(rollbackId) }

            // Update session state.
            session.latestPowerFlowResult = powerFlowResult
            session.latestSnapshot = powerFlowResult.snapshot

            // Generate alerts from violations.
            val alerts = toAlerts(powerFlowResult)

            log.info(
                "CommandHandler: session {} — {} mutations applied, pf={}, {} alerts",
                sessionId,
                mutations.size,
                powerFlowResult.status,
                alerts.size,
            )

            // Trigger async N-1 if topology changed. triggerAsync() calls cloneVariant()
            // internally, so no separate copy is needed here (#38). Pass `session` as the
            // lock — triggerAsync() and the background run it schedules both synchronize
            // on it, matching the `synchronized(session)` this whole pipeline already
            // runs under (#360).
            if (mutations.any { it.isTopologyChange() }) {
                contingencyService.triggerAsync(network, session, ContingencyAnalysisParameters())
                log.debug("CommandHandler: triggered async N-1 for session {}", sessionId)
            }

            val outcomes =
                if (sourceCommands.isEmpty()) {
                    listOf(CommandOutcome(commandType = "applyMutations", success = true))
                } else {
                    sourceCommands.map {
                        CommandOutcome(commandType = it.commandType(), success = true)
                    }
                }

            CommandResult(
                success = true,
                snapshot = powerFlowResult.snapshot,
                powerFlowResult = powerFlowResult,
                newAlerts = alerts,
                commandOutcomes = outcomes,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validate a command against the current [snapshot].
     * Returns null on success, or a rejection reason string on failure.
     */
    private fun validate(
        command: PlayerCommand,
        snapshot: GridNetwork,
    ): String? =
        when (command) {
            is PlayerCommand.SetGeneratorOutput -> validateSetGeneratorOutput(command, snapshot)
            is PlayerCommand.SetGeneratorVoltage -> validateSetGeneratorVoltage(command, snapshot)
            is PlayerCommand.TripElement -> validateTripElement(command, snapshot)
            is PlayerCommand.ConnectElement -> validateConnectElement(command, snapshot)
            is PlayerCommand.SetTapPosition -> validateSetTapPosition(command, snapshot)
            is PlayerCommand.ShedLoad -> validateShedLoad(command, snapshot)
            is PlayerCommand.RunEconomicDispatch -> validateRunEconomicDispatch(command)
            is PlayerCommand.CommitGenerator -> validateCommitGenerator(command, snapshot)
            is PlayerCommand.DecommitGenerator -> validateDecommitGenerator(command, snapshot)
            is PlayerCommand.ApplyUcSchedule -> validateApplyUcSchedule(command, snapshot)
            is PlayerCommand.SetClockSpeed -> validateSetClockSpeed(command)
            // Clock / card commands validated / handled inline in handle()
            is PlayerCommand.PauseClock,
            is PlayerCommand.ResumeClock,
            is PlayerCommand.RespondToEventCard,
            -> null
        }

    private fun validateSetGeneratorOutput(
        cmd: PlayerCommand.SetGeneratorOutput,
        snapshot: GridNetwork,
    ): String? {
        val gen =
            snapshot.generators.find { it.id == cmd.generatorId }
                ?: return "Generator '${cmd.generatorId}' not found in session network"
        if (!gen.connected) return "Generator '${cmd.generatorId}' is not committed"
        if (cmd.targetMw < gen.minActivePowerMw || cmd.targetMw > gen.maxActivePowerMw) {
            val range = "[${gen.minActivePowerMw}, ${gen.maxActivePowerMw}]"
            return "targetMw ${cmd.targetMw} out of range $range for generator '${cmd.generatorId}'"
        }
        return null
    }

    private fun validateSetGeneratorVoltage(
        cmd: PlayerCommand.SetGeneratorVoltage,
        snapshot: GridNetwork,
    ): String? {
        snapshot.generators.find { it.id == cmd.generatorId }
            ?: return "Generator '${cmd.generatorId}' not found in session network"
        if (cmd.targetVoltagePu < 0.9 || cmd.targetVoltagePu > 1.1) {
            return "targetVoltagePu ${cmd.targetVoltagePu} out of range [0.9, 1.1]"
        }
        return null
    }

    private fun validateTripElement(
        cmd: PlayerCommand.TripElement,
        snapshot: GridNetwork,
    ): String? {
        return when (cmd.elementType) {
            EquipmentType.LINE -> {
                if (snapshot.lines.none { it.id == cmd.elementId }) "Line '${cmd.elementId}' not found" else null
            }
            EquipmentType.TWO_WINDINGS_TRANSFORMER,
            EquipmentType.THREE_WINDINGS_TRANSFORMER,
            -> "Transformer trips not supported via TripElement — use SetTapPosition"
            EquipmentType.BUS -> "Bus trips not supported"
        }
    }

    private fun validateConnectElement(
        cmd: PlayerCommand.ConnectElement,
        snapshot: GridNetwork,
    ): String? {
        return when (cmd.elementType) {
            EquipmentType.LINE -> {
                if (snapshot.lines.none { it.id == cmd.elementId }) "Line '${cmd.elementId}' not found" else null
            }
            else -> null
        }
    }

    private fun validateSetTapPosition(
        cmd: PlayerCommand.SetTapPosition,
        snapshot: GridNetwork,
    ): String? {
        snapshot.twoWindingsTransformers.find { it.id == cmd.transformerId }
            ?: return "Transformer '${cmd.transformerId}' not found"
        // Tap range validation deferred to IidmNetworkMapper.applyMutation which has IIDM context
        return null
    }

    private fun validateShedLoad(
        cmd: PlayerCommand.ShedLoad,
        snapshot: GridNetwork,
    ): String? {
        val load =
            snapshot.loads.find { it.id == cmd.loadId }
                ?: return "Load '${cmd.loadId}' not found"
        if (!load.connected) return "Load '${cmd.loadId}' is not connected"
        if (cmd.fractionToShed < 0.0 || cmd.fractionToShed > 1.0) {
            return "fractionToShed ${cmd.fractionToShed} out of range [0.0, 1.0]"
        }
        return null
    }

    private fun validateRunEconomicDispatch(cmd: PlayerCommand.RunEconomicDispatch): String? {
        if (cmd.totalLoadMw <= 0.0) return "totalLoadMw must be positive"
        return null
    }

    private fun validateCommitGenerator(
        cmd: PlayerCommand.CommitGenerator,
        snapshot: GridNetwork,
    ): String? {
        val gen =
            snapshot.generators.find { it.id == cmd.generatorId }
                ?: return "Generator '${cmd.generatorId}' not found"
        if (gen.connected) return "Generator '${cmd.generatorId}' is already committed"
        return null
    }

    private fun validateDecommitGenerator(
        cmd: PlayerCommand.DecommitGenerator,
        snapshot: GridNetwork,
    ): String? {
        val gen =
            snapshot.generators.find { it.id == cmd.generatorId }
                ?: return "Generator '${cmd.generatorId}' not found"
        if (!gen.connected) return "Generator '${cmd.generatorId}' is already decommitted"
        // Safety: disallow decommit if it would leave no connected generators
        val connectedCount = snapshot.generators.count { it.connected }
        if (connectedCount <= 1) {
            return "Cannot decommit '${cmd.generatorId}': it is the only connected generator"
        }
        return null
    }

    private fun validateApplyUcSchedule(
        cmd: PlayerCommand.ApplyUcSchedule,
        snapshot: GridNetwork,
    ): String? {
        for (entry in cmd.schedule) {
            snapshot.generators.find { it.id == entry.generatorId }
                ?: return "Generator '${entry.generatorId}' not found in UC schedule"
        }
        return null
    }

    private fun validateSetClockSpeed(cmd: PlayerCommand.SetClockSpeed): String? {
        if (cmd.multiplier < 1 || cmd.multiplier > 100) {
            return "multiplier ${cmd.multiplier} out of range [1, 100]"
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command → Mutation translation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Translate a validated [PlayerCommand] into a list of [NetworkMutation]s.
     * Clock, card, and dispatch commands are handled inline in [handle] / [handleBatch];
     * this function covers all network-mutating commands.
     */
    private fun translate(
        command: PlayerCommand,
        snapshot: GridNetwork,
    ): List<NetworkMutation> =
        when (command) {
            is PlayerCommand.SetGeneratorOutput ->
                listOf(NetworkMutation.SetGeneratorOutput(command.generatorId, command.targetMw))

            is PlayerCommand.SetGeneratorVoltage ->
                listOf(NetworkMutation.SetGeneratorVoltage(command.generatorId, command.targetVoltagePu))

            is PlayerCommand.TripElement ->
                when (command.elementType) {
                    EquipmentType.LINE -> listOf(NetworkMutation.TripLine(command.elementId))
                    else -> emptyList()
                }

            is PlayerCommand.ConnectElement ->
                when (command.elementType) {
                    EquipmentType.LINE -> listOf(NetworkMutation.ConnectLine(command.elementId))
                    // Generators are connected via CommitGenerator; buses and transformers
                    // do not have a direct connect mutation in the current domain model.
                    else ->
                        throw IllegalArgumentException(
                            "ConnectElement is not supported for equipment type ${command.elementType}. " +
                                "Use CommitGenerator to connect a generator.",
                        )
                }

            is PlayerCommand.SetTapPosition ->
                listOf(NetworkMutation.SetTapPosition(command.transformerId, command.tapPosition))

            is PlayerCommand.ShedLoad -> {
                val load = snapshot.loads.find { it.id == command.loadId }!!
                val newPower = load.activePowerMw * (1.0 - command.fractionToShed)
                listOf(NetworkMutation.SetLoadPower(command.loadId, newPower.coerceAtLeast(0.0)))
            }

            is PlayerCommand.CommitGenerator ->
                listOf(NetworkMutation.ConnectGenerator(command.generatorId))

            is PlayerCommand.DecommitGenerator ->
                listOf(NetworkMutation.TripGenerator(command.generatorId))

            is PlayerCommand.ApplyUcSchedule ->
                command.schedule.flatMap { entry ->
                    buildList {
                        add(
                            if (entry.committed) {
                                NetworkMutation.ConnectGenerator(entry.generatorId)
                            } else {
                                NetworkMutation.TripGenerator(entry.generatorId)
                            },
                        )
                        if (entry.committed && entry.targetMw != null) {
                            add(NetworkMutation.SetGeneratorOutput(entry.generatorId, entry.targetMw))
                        }
                    }
                }

            is PlayerCommand.RunEconomicDispatch -> {
                val generators = snapshot.toDispatchableGenerators()
                val params =
                    DispatchParameters(
                        mode = command.mode,
                        securityConstrained = command.securityConstrained,
                    )
                val result = dispatchService.economicDispatch(generators, command.totalLoadMw, params)
                result.targets.map { target ->
                    NetworkMutation.SetGeneratorOutput(target.generatorId, target.targetMw)
                }
            }

            // These are handled inline before translate() is called
            is PlayerCommand.PauseClock,
            is PlayerCommand.ResumeClock,
            is PlayerCommand.SetClockSpeed,
            is PlayerCommand.RespondToEventCard,
            -> emptyList()
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Alert generation
    // ─────────────────────────────────────────────────────────────────────────

    /** Convert [PowerFlowResult.violations] to [Alert]s. */
    private fun toAlerts(result: PowerFlowResult): List<Alert> {
        val alerts = mutableListOf<Alert>()

        if (result.status == ConvergenceStatus.NETWORK_FAILURE) {
            alerts.add(Alert.ConvergenceAlert())
        }

        for (violation in result.violations) {
            when (violation) {
                is NetworkViolation.VoltageViolation -> {
                    val severity =
                        if (violation.voltagePu < violation.limitMinPu - 0.05 ||
                            violation.voltagePu > violation.limitMaxPu + 0.05
                        ) {
                            AlertSeverity.CRITICAL
                        } else {
                            AlertSeverity.WARNING
                        }
                    alerts.add(
                        Alert.VoltageAlert(
                            severity = severity,
                            elementId = violation.busId,
                            voltagePu = violation.voltagePu,
                            limitMinPu = violation.limitMinPu,
                            limitMaxPu = violation.limitMaxPu,
                        ),
                    )
                }
                is NetworkViolation.ThermalViolation -> {
                    val severity = if (violation.loadingPercent >= 120.0) AlertSeverity.CRITICAL else AlertSeverity.WARNING
                    alerts.add(
                        Alert.ThermalAlert(
                            severity = severity,
                            elementId = violation.equipmentId,
                            loadingPercent = violation.loadingPercent,
                            equipmentType = violation.equipmentType,
                        ),
                    )
                }
            }
        }

        return alerts
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun noMutationResult(
        snapshot: GridNetwork,
        cachedPfResult: PowerFlowResult?,
        command: PlayerCommand,
    ): CommandResult =
        CommandResult(
            success = true,
            snapshot = snapshot,
            powerFlowResult = cachedPfResult ?: emptyPowerFlowResult(snapshot),
            newAlerts = emptyList(),
            commandOutcomes = listOf(CommandOutcome(commandType = command.commandType(), success = true)),
        )

    private fun rejectedResult(
        session: com.gridmaster.api.PhysicsSession,
        command: PlayerCommand,
        reason: String,
    ): CommandResult =
        CommandResult(
            success = false,
            snapshot = session.latestSnapshot,
            powerFlowResult = session.latestPowerFlowResult ?: emptyPowerFlowResult(session.latestSnapshot),
            newAlerts = emptyList(),
            commandOutcomes =
                listOf(
                    CommandOutcome(
                        commandType = command.commandType(),
                        success = false,
                        rejectionReason = reason,
                    ),
                ),
        )

    private fun emptyPowerFlowResult(snapshot: GridNetwork): PowerFlowResult =
        PowerFlowResult(
            status = ConvergenceStatus.CONVERGED,
            solveMode = com.gridmaster.engine.powerflow.SolveMode.AC,
            iterationCount = 0,
            snapshot = snapshot,
            slackBusIds = emptyList(),
            violations = emptyList(),
            solveTimeMs = 0,
        )

    private fun PlayerCommand.commandType(): String = this::class.simpleName ?: "Unknown"

    companion object {
        /** True for mutations that change network topology (require re-triggering N-1). */
        private fun NetworkMutation.isTopologyChange(): Boolean =
            this is NetworkMutation.TripLine ||
                this is NetworkMutation.ConnectLine ||
                this is NetworkMutation.TripGenerator ||
                this is NetworkMutation.ConnectGenerator ||
                this is NetworkMutation.ConnectLoad ||
                this is NetworkMutation.DisconnectLoad
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private mapping helper
// ─────────────────────────────────────────────────────────────────────────────

private fun GridNetwork.toDispatchableGenerators() =
    generators.map { g ->
        com.gridmaster.engine.dispatch.DispatchableGenerator(
            id = g.id,
            name = g.name,
            committed = g.connected,
            minActivePowerMw = g.minActivePowerMw,
            maxActivePowerMw = g.maxActivePowerMw,
            currentActivePowerMw = g.targetActivePowerMw,
            marginalCostPerMwh = g.marginalCostPerMwh,
        )
    }
