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

data class CreateSessionRequest(
    @field:NotBlank
    val displayName: String,
    val mode: GameMode = GameMode.TUTORIAL,
    @field:NotBlank
    @field:Pattern(
        regexp = "^[a-z0-9_]+$",
        message = "networkPreset must be lowercase alphanumeric with underscores",
    )
    @field:Size(max = 64)
    val networkPreset: String = "tutorial",
)

data class IssueTokenRequest(
    /**
     * Optional stable player UUID supplied by the client on re-issue.
     * If omitted or blank, the server mints a new UUID for the first launch.
     */
    @field:Size(max = 64)
    val userId: String? = null,
)

// -------------------------------------------------------------------------
// Responses
// -------------------------------------------------------------------------

/** Lightweight summary returned in list responses. */
data class SessionSummaryDto(
    val id: String,
    val mode: GameMode,
    val displayName: String,
    val gameTimeEpochMinutes: Long,
    val clockState: ClockState,
    val updatedAt: Instant,
)

/** Full session detail returned on create and GET-by-id. Omits IIDM XML (large). */
data class SessionDetailDto(
    val id: String,
    val userId: String,
    val mode: GameMode,
    val displayName: String,
    val gameTimeEpochMinutes: Long,
    val clockState: ClockState,
    val clockSpeedMultiplier: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val availablePresets: Set<String> = PresetNetworkFactory.knownPresets,
)

data class TokenResponse(
    val token: String,
    val userId: String,
    val expiresInDays: Long,
)

// -------------------------------------------------------------------------
// Domain → DTO mappers
// -------------------------------------------------------------------------

fun GameSession.toSummaryDto() =
    SessionSummaryDto(
        id = id,
        mode = mode,
        displayName = displayName,
        gameTimeEpochMinutes = gameTimeEpochMinutes,
        clockState = clockState,
        updatedAt = updatedAt,
    )

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
