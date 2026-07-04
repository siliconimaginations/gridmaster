package com.gridmaster.api

import com.gridmaster.api.dto.ScoreEntryDto
import com.gridmaster.api.dto.toScoreEntryDto
import com.gridmaster.game.GameSessionService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Score / leaderboard endpoints.
 *
 * `GET /api/scores` — returns the authenticated user's completed sessions ordered by
 * most recent first. Each entry includes the final average health score and the total
 * grid-time managed.
 */
@RestController
@RequestMapping("/api/scores")
class ScoreController(private val gameSessionService: GameSessionService) {
    /**
     * `GET /api/scores`
     *
     * Returns all game-over sessions for the authenticated user, newest first.
     * Sessions still active (completedAt == null) are excluded.
     */
    @GetMapping
    fun listScores(auth: Authentication): List<ScoreEntryDto> =
        gameSessionService
            .listScores(auth.name)
            .map { it.toScoreEntryDto() }
}
