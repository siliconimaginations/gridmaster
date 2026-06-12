package com.gridmaster.api

/**
 * Domain exceptions for the GridMaster API layer.
 *
 * Consolidates all API-layer exception classes in one place so they are easy
 * to find, import, and extend without hunting across unrelated source files.
 *
 * See [GlobalExceptionHandler] for the Spring @RestControllerAdvice that maps
 * these exceptions to HTTP error responses.
 */

/** Thrown when a requested session does not exist in [PhysicsSessionStore]. */
class SessionNotFoundException(val sessionId: String) :
    RuntimeException("Session not found: $sessionId")

/** Thrown when a [com.gridmaster.api.dto.NetworkMutationDto] cannot be mapped to a domain mutation. */
class InvalidMutationException(message: String) : RuntimeException(message)

/** Wraps unexpected exceptions from physics services (power flow, contingency, dispatch). */
class PhysicsServiceException(
    val sessionId: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

