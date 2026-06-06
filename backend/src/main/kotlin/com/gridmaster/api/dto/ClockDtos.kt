package com.gridmaster.api.dto

import com.gridmaster.game.ClockState
import com.gridmaster.game.TickClockStatus
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * Response body for all `/clock` endpoints.
 */
data class ClockStatusResponse(
    /** Current run-state of the clock. */
    val clockState: ClockState,
    /** Active speed multiplier (1–100). */
    val speedMultiplier: Int,
    /** Accumulated game-time minutes since the session epoch. */
    val gameTimeMinutes: Long,
    /** Number of ticks executed since the clock was last started. */
    val tickCount: Long,
    /** True if the clock was automatically slowed due to a critical grid condition. */
    val autoSlowed: Boolean,
) {
    companion object {
        /** Map from domain [TickClockStatus] to response DTO. */
        fun from(status: TickClockStatus): ClockStatusResponse =
            ClockStatusResponse(
                clockState = status.clockState,
                speedMultiplier = status.speedMultiplier,
                gameTimeMinutes = status.gameTimeMinutes,
                tickCount = status.tickCount,
                autoSlowed = status.autoSlowed,
            )
    }
}

/**
 * Request body for `POST /clock/speed`.
 */
data class SetSpeedRequest(
    /** New speed multiplier. Must be in the range 1–100. */
    @field:Min(1, message = "Speed multiplier must be at least 1")
    @field:Max(100, message = "Speed multiplier must be at most 100")
    val multiplier: Int,
)
