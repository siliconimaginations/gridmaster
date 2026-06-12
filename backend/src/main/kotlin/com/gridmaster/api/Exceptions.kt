package com.gridmaster.api

/** Thrown when a requested session does not exist in [PhysicsSessionStore]. */
class SessionNotFoundException(val sessionId: String) :
    RuntimeException("Session not found: $sessionId")

/**
 * Thrown when a [com.gridmaster.api.dto.NetworkMutationDto] cannot be mapped to a domain mutation.
 */
class InvalidMutationException(message: String) : RuntimeException(message)

/** Wraps unexpected exceptions from physics services (power flow, contingency, dispatch). */
class PhysicsServiceException(
    val sessionId: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
