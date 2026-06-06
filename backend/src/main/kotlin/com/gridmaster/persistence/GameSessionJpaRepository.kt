package com.gridmaster.persistence

import com.gridmaster.game.GameMode
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data repository for [GameSessionEntity].
 *
 * All finder methods operate on [GameSessionEntity.userId] so that one user
 * never sees another user's sessions. Callers in [com.gridmaster.game.GameSessionService]
 * must always scope queries to the authenticated userId.
 */
interface GameSessionJpaRepository : JpaRepository<GameSessionEntity, String> {
    /**
     * Return all sessions owned by [userId], ordered by most recently updated first.
     * Sorting is delegated to the database to avoid in-memory sorting on large lists.
     */
    fun findAllByUserIdOrderByUpdatedAtDesc(userId: String): List<GameSessionEntity>

    /** Return sessions owned by [userId] filtered by [mode]. */
    fun findAllByUserIdAndMode(
        userId: String,
        mode: GameMode,
    ): List<GameSessionEntity>
}
