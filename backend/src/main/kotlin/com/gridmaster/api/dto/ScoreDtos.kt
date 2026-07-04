package com.gridmaster.api.dto

import com.gridmaster.game.GameSession
import java.time.Instant

/**
 * A single completed-session score entry returned by `GET /api/scores`.
 *
 * Only sessions with a non-null [GameSession.completedAt] are included.
 */
data class ScoreEntryDto(
    val sessionId: String,
    val displayName: String,
    /** Average health score (0-100) recorded at game-over. */
    val finalScore: Int,
    /** Total simulated grid-minutes managed before the session ended. */
    val gameTimeEpochMinutes: Long,
    val completedAt: Instant,
)

fun GameSession.toScoreEntryDto(): ScoreEntryDto =
    ScoreEntryDto(
        sessionId = id,
        displayName = displayName,
        finalScore = finalScore ?: 0,
        gameTimeEpochMinutes = gameTimeEpochMinutes,
        completedAt = completedAt!!,
    )
