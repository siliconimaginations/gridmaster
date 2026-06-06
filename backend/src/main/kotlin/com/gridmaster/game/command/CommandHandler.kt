package com.gridmaster.game.command

import com.gridmaster.engine.model.NetworkMutation

/**
 * Single entry point for all state-changing operations on the live network.
 *
 * Whether initiated by the player (via REST or WebSocket), the dispatch service,
 * or the event engine, every mutation routes through this interface so that
 * validation, physics, and alerting are applied consistently.
 *
 * ### Single command
 * [handle] validates the command, translates it to one or more [NetworkMutation]s,
 * applies them, runs power flow, scans for violations, and optionally triggers
 * async N-1 analysis for topology changes. Returns a [CommandResult] with one
 * [CommandOutcome].
 *
 * ### Batch command
 * [handleBatch] validates ALL commands first (all-or-nothing). If any fail, zero
 * mutations are applied. If all pass, mutations are applied in order and a single
 * power flow is run — N commands → 1 solve instead of N.
 *
 * ### Internal callers
 * [applyMutations] skips player-level validation and applies [NetworkMutation]s
 * directly. Used by the event engine and dispatch service.
 */
interface CommandHandler {
    /**
     * Validate and apply a single player command.
     * Runs power flow after mutation.
     *
     * @param userId Authenticated player ID — used for clock ownership checks.
     * @return [CommandResult] with a single [CommandOutcome].
     */
    fun handle(
        command: PlayerCommand,
        userId: String,
    ): CommandResult

    /**
     * Validate and apply a batch of player commands atomically.
     *
     * All commands are validated first. If any fail validation the entire batch is
     * rejected and zero mutations are applied. If all pass, mutations are applied in
     * order and a single power flow is run.
     *
     * @param userId Authenticated player ID.
     * @return [CommandResult] with one [CommandOutcome] per command.
     */
    fun handleBatch(
        commands: List<PlayerCommand>,
        userId: String,
    ): CommandResult

    /**
     * Apply [NetworkMutation]s directly — skips player-level validation.
     *
     * Used by the event engine and dispatch service which have already validated
     * their outputs. Still runs the full physics pipeline after applying.
     *
     * @return [CommandResult] with a single synthetic [CommandOutcome].
     */
    fun applyMutations(
        mutations: List<NetworkMutation>,
        sessionId: String,
    ): CommandResult
}
