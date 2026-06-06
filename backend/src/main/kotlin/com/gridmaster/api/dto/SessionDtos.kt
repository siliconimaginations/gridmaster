package com.gridmaster.api.dto

import com.gridmaster.game.ClockState
import com.gridmaster.game.GameMode
import com.gridmaster.game.GameSession
import com.gridmaster.game.PresetNetworkFactory
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

// -------------------------------------------------------------------------
// Requests
// -------------------------------------------------------------------------

/**
 * Request body for `POST /api/sessions`.
 *
 * [networkPreset] must be one of the keys in [PresetNetworkFactory.knownPresets].
 */
data class CreateSessionRequest(
    /** Human-readable label for the session; must be non-blank. */
    @field:NotBlank
    val displayName: String,
    /** Game mode the session will run under. Defaults to [GameMode.TUTORIAL]. */
    val mode: GameMode = GameMode.TUTORIAL,
    /**
     * Name of the bundled seed network to load.
     * Must be lowercase alphanumeric with underscores, max 64 characters.
     */
    @field:NotBlank
    @field:Pattern(
        regexp = "^[a-z0-9_]+$",
        message = "networkPreset must be lowercase alphanumeric with underscores",
    )
    @field:Size(max = 64)
    val networkPreset: String = "tutorial",
)

/**
 * Request body for `POST /api/auth/token`.
 *
 * Supplying a non-blank [userId] re-issues a token for an existing player (used
 * when a previous token expires). Omitting it triggers first-launch UUID minting.
 */
data class IssueTokenRequest(
    /**
     * Stable player UUID from a previous token. Leave blank on first launch.
     * Must be a valid UUID (8-4-4-4-12 hex) when provided.
     */
    @field:Pattern(
        regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        message = "userId must be a valid UUID",
    )
    @field:Size(max = 36)
    val userId: String? = null,
)

// -------------------------------------------------------------------------
// Responses
// -------------------------------------------------------------------------

/** Lightweight session summary returned in list responses (`GET /api/sessions`). */
data class SessionSummaryDto(
    val id: String,
    val mode: GameMode,
    val displayName: String,
    /** Accumulated game-time minutes; useful for displaying session age in the lobby. */
    val gameTimeEpochMinutes: Long,
    val clockState: ClockState,
    val updatedAt: Instant,
)

/**
 * Full session detail returned on create (`POST /api/sessions`) and load
 * (`GET /api/sessions/{id}`). Omits IIDM XML — callers use the physics API
 * to query live network state.
 */
data class SessionDetailDto(
    val id: String,
    /** Owning player UUID; matches the `sub` claim in the caller's JWT. */
    val userId: String,
    val mode: GameMode,
    val displayName: String,
    val gameTimeEpochMinutes: Long,
    val clockState: ClockState,
    /** Speed multiplier last applied to the clock (1–100). */
    val clockSpeedMultiplier: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Non-null when the session has reached a terminal state. */
    val completedAt: Instant?,
    /** Preset names accepted by `POST /api/sessions`. */
    val availablePresets: Set<String> = PresetNetworkFactory.knownPresets,
)

/** Response body for `POST /api/auth/token`. */
data class TokenResponse(
    /** Signed HMAC-SHA256 JWT to be sent as a Bearer token on subsequent requests. */
    val token: String,
    /** Stable player UUID encoded in the token's `sub` claim. */
    val userId: String,
    /** Token validity window in days from issuance. */
    val expiresInDays: Long,
)

// -------------------------------------------------------------------------
// Domain → DTO mappers
// -------------------------------------------------------------------------

/** Map a [GameSession] to a [SessionSummaryDto] suitable for list responses. */
fun GameSession.toSummaryDto() =
    SessionSummaryDto(
        id = id,
        mode = mode,
        displayName = displayName,
        gameTimeEpochMinutes = gameTimeEpochMinutes,
        clockState = clockState,
        updatedAt = updatedAt,
    )

/** Map a [GameSession] to a [SessionDetailDto], omitting the raw IIDM XML. */
fun GameSession.toDetailDto() =
    SessionDetailDto(
        id = id,
        userId = userId,
        mode = mode,
        displayName = displayName,
        gameTimeEpochMinutes = gameTimeEpochMinutes,
        clockState = clockState,
        clockSpeedMultiplier = clockSpeedMultiplier,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )
