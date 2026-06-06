package com.gridmaster.api

import com.gridmaster.api.dto.CreateSessionRequest
import com.gridmaster.api.dto.SessionDetailDto
import com.gridmaster.api.dto.SessionSummaryDto
import com.gridmaster.api.dto.toDetailDto
import com.gridmaster.api.dto.toSummaryDto
import com.gridmaster.game.GameSessionService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Session lifecycle endpoints: create, list, load, delete.
 *
 * All paths require a valid Bearer JWT; the [Authentication.getName] principal
 * provides the userId scoped to the token.
 *
 * Physics operations on a live session (power flow, dispatch, etc.) are handled
 * by [PhysicsController] at /api/sessions/{sessionId}/... (PhysicsController).
 */
@RestController
@RequestMapping("/api/sessions")
class SessionController(private val gameSessionService: GameSessionService) {
    /**
     * `POST /api/sessions` — create a new game session.
     *
     * Loads the seed network from [CreateSessionRequest.networkPreset], persists the
     * session, and registers it in the in-memory [PhysicsSessionStore].
     * Returns 201 Created with the session detail.
     */
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateSessionRequest,
        auth: Authentication,
    ): ResponseEntity<SessionDetailDto> {
        val session =
            gameSessionService.create(
                userId = auth.name,
                mode = request.mode,
                displayName = request.displayName,
                networkPreset = request.networkPreset,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(session.toDetailDto())
    }

    /**
     * `GET /api/sessions` — list all sessions for the authenticated user.
     *
     * Returns summaries ordered by `updatedAt` descending.
     */
    @GetMapping
    fun list(auth: Authentication): ResponseEntity<List<SessionSummaryDto>> {
        val sessions = gameSessionService.listForUser(auth.name).map { it.toSummaryDto() }
        return ResponseEntity.ok(sessions)
    }

    /**
     * `GET /api/sessions/{sessionId}` — load (or resume) a specific session.
     *
     * If the session is not currently live in the [PhysicsSessionStore] (e.g. after a
     * server restart), the IIDM is deserialised and the physics store is re-hydrated.
     *
     * Returns 404 if the session does not exist or belongs to another user.
     */
    @GetMapping("/{sessionId}")
    fun get(
        @PathVariable sessionId: String,
        auth: Authentication,
    ): ResponseEntity<SessionDetailDto> {
        val session = gameSessionService.load(sessionId, auth.name)
        return ResponseEntity.ok(session.toDetailDto())
    }

    /**
     * `DELETE /api/sessions/{sessionId}` — delete a session permanently.
     *
     * Removes from DB and from the in-memory [PhysicsSessionStore].
     * Returns 204 No Content.
     */
    @DeleteMapping("/{sessionId}")
    fun delete(
        @PathVariable sessionId: String,
        auth: Authentication,
    ): ResponseEntity<Void> {
        gameSessionService.delete(sessionId, auth.name)
        return ResponseEntity.noContent().build()
    }
}
