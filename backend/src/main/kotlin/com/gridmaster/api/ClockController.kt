package com.gridmaster.api

import com.gridmaster.api.dto.ClockStatusResponse
import com.gridmaster.api.dto.SetSpeedRequest
import com.gridmaster.game.TickEngine
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST endpoints for controlling the game clock of a session.
 *
 * All paths are scoped under `/api/sessions/{sessionId}/clock`.
 * The authenticated user must own the session (enforced by [TickEngine]).
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/clock")
class ClockController(private val tickEngine: TickEngine) {
    /**
     * Return the current clock status — state, speed, game time, and tick count.
     *
     * Response: 200 with [ClockStatusResponse], or 404 if the session is not active.
     */
    @GetMapping
    fun status(
        @PathVariable sessionId: String,
        auth: Authentication,
    ): ResponseEntity<ClockStatusResponse> {
        val status =
            tickEngine.clockStatus(sessionId, auth.name)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ClockStatusResponse.from(status))
    }

    /**
     * Start the tick loop for a session. The session must have been loaded (live in
     * [PhysicsSessionStore]) before calling this endpoint.
     *
     * Response: 200 with current [ClockStatusResponse].
     */
    @PostMapping("/start")
    fun start(
        @PathVariable sessionId: String,
        auth: Authentication,
    ): ResponseEntity<ClockStatusResponse> {
        val startStatus = tickEngine.start(sessionId, auth.name)
        return ResponseEntity.ok(ClockStatusResponse.from(startStatus))
    }

    /**
     * Pause the running tick loop. The tick in progress completes first.
     * Triggers an auto-save.
     *
     * Response: 200 with current [ClockStatusResponse].
     */
    @PostMapping("/pause")
    fun pause(
        @PathVariable sessionId: String,
        auth: Authentication,
    ): ResponseEntity<ClockStatusResponse> {
        val pauseStatus = tickEngine.pause(sessionId, auth.name)
        return ResponseEntity.ok(ClockStatusResponse.from(pauseStatus))
    }

    /**
     * Resume a paused tick loop.
     *
     * Response: 200 with current [ClockStatusResponse].
     */
    @PostMapping("/resume")
    fun resume(
        @PathVariable sessionId: String,
        auth: Authentication,
    ): ResponseEntity<ClockStatusResponse> {
        val resumeStatus = tickEngine.resume(sessionId, auth.name)
        return ResponseEntity.ok(ClockStatusResponse.from(resumeStatus))
    }

    /**
     * Set the speed multiplier (1–100). Takes effect on the next tick.
     *
     * Response: 200 with current [ClockStatusResponse].
     */
    @PostMapping("/speed")
    fun setSpeed(
        @PathVariable sessionId: String,
        @Valid @RequestBody request: SetSpeedRequest,
        auth: Authentication,
    ): ResponseEntity<ClockStatusResponse> {
        val speedStatus = tickEngine.setSpeed(sessionId, auth.name, request.multiplier)
        return ResponseEntity.ok(ClockStatusResponse.from(speedStatus))
    }

    /**
     * Permanently stop the tick loop. The session transitions to STOPPED and is
     * unregistered from the engine.
     *
     * Response: 204 No Content.
     */
    @PostMapping("/stop")
    fun stop(
        @PathVariable sessionId: String,
        auth: Authentication,
    ): ResponseEntity<Void> {
        tickEngine.stop(sessionId, auth.name)
        return ResponseEntity.noContent().build()
    }
}
