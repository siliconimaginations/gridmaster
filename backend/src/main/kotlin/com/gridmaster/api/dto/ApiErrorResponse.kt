package com.gridmaster.api.dto

import java.time.Instant

/**
 * Uniform error envelope returned by [com.gridmaster.api.GlobalExceptionHandler]
 * for all 4xx / 5xx responses.
 *
 * [sessionId] is null for session-agnostic errors (e.g. 404 on unknown session).
 */
data class ApiErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val sessionId: String? = null,
    val timestamp: Instant = Instant.now(),
)
