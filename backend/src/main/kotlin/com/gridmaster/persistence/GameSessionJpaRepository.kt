package com.gridmaster.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface GameSessionJpaRepository : JpaRepository<GameSessionEntity, String> {
    fun findAllByUserId(userId: String): List<GameSessionEntity>

    fun findAllByUserIdAndMode(
        userId: String,
        mode: com.gridmaster.game.GameMode,
    ): List<GameSessionEntity>
}
