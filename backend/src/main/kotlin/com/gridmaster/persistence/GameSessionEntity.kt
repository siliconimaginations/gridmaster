package com.gridmaster.persistence

import com.gridmaster.game.ClockState
import com.gridmaster.game.GameMode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * JPA entity persisting the full game session state to SQLite.
 *
 * [iidmXml] is the authoritative source for network resume: it is serialised by
 * [com.powsybl.iidm.serde.NetworkSerDe] and round-trips without loss.
 * The physics layer's [com.gridmaster.persistence.NetworkSnapshotEntity] continues to
 * serve the fast-read path during an active session; [GameSessionEntity] owns the
 * durable save-point used on create and auto-save.
 */
@Entity
@Table(name = "game_sessions")
data class GameSessionEntity(
    @Id
    val id: String,
    val userId: String,
    @Enumerated(EnumType.STRING)
    val mode: GameMode,
    val displayName: String,
    /** Full XIIDM network XML — authoritative for session resume. */
    @Column(columnDefinition = "TEXT", nullable = false)
    val iidmXml: String,
    /** Accumulated game-time minutes since session epoch. Incremented by the clock (Module 07). */
    val gameTimeEpochMinutes: Long = 0L,
    @Enumerated(EnumType.STRING)
    val clockState: ClockState = ClockState.PAUSED,
    val clockSpeedMultiplier: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
)
