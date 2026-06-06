package com.gridmaster.game

import java.time.Instant

/**
 * Domain model for a game session. Decoupled from the JPA entity so the game engine
 * and API layer never import persistence classes directly.
 *
 * This is an immutable snapshot — updated copies are produced by [GameSessionService]
 * and written back to [com.gridmaster.persistence.GameSessionJpaRepository].
 */
data class GameSession(
    val id: String,
    val userId: String,
    val mode: GameMode,
    val displayName: String,
    /** XIIDM serialisation of the live PowSyBl network. Round-trips via NetworkSerDe. */
    val iidmXml: String,
    val gameTimeEpochMinutes: Long = 0L,
    val clockState: ClockState = ClockState.PAUSED,
    val clockSpeedMultiplier: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
)
