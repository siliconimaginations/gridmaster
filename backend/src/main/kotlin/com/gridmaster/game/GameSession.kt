package com.gridmaster.game

import java.time.Instant

/**
 * Immutable domain model for a game session.
 *
 * Decoupled from [com.gridmaster.persistence.GameSessionEntity] so that the game
 * engine and API layer never import JPA annotations directly.
 *
 * Updated copies are produced by [GameSessionService] and written back to the
 * database via [com.gridmaster.persistence.GameSessionJpaRepository].
 */
data class GameSession(
    /** Stable UUID assigned at creation time. */
    val id: String,
    /** UUID of the owning player, derived from the JWT `sub` claim. */
    val userId: String,
    /** Scenario mode this session is running under. */
    val mode: GameMode,
    /** Human-readable label chosen by the player at creation. */
    val displayName: String,
    /**
     * XIIDM serialisation of the live PowSyBl network.
     * Round-trips losslessly via [com.powsybl.iidm.serde.NetworkSerDe].
     */
    val iidmXml: String,
    /** Accumulated game-time minutes since the session epoch. Incremented each tick by Module 07. */
    val gameTimeEpochMinutes: Long = 0L,
    /** Current run-state of the game clock. */
    val clockState: ClockState = ClockState.PAUSED,
    /** Speed multiplier applied to the real-time tick interval (1–100). */
    val clockSpeedMultiplier: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    /** Set when the session reaches a terminal state (mission complete, game over). */
    val completedAt: Instant? = null,
)
