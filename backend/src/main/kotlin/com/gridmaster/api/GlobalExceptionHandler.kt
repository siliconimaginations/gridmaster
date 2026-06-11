package com.gridmaster.api

import com.gridmaster.api.dto.ApiErrorResponse
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps domain and validation exceptions to the [ApiErrorResponse] envelope.
 *
 * Error codes are kept stable — do not rename them; the frontend and tests depend on them.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(SessionNotFoundException::class)
    fun handleSessionNotFound(ex: SessionNotFoundException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(
                ApiErrorResponse(
                    status = 404,
                    error = "SESSION_NOT_FOUND",
                    message = ex.message ?: "Session not found",
                    sessionId = ex.sessionId,
                ),
            )

    @ExceptionHandler(InvalidMutationException::class)
    fun handleInvalidMutation(ex: InvalidMutationException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                ApiErrorResponse(
                    status = 400,
                    error = "INVALID_MUTATION",
                    message = ex.message ?: "Invalid mutation",
                ),
            )

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(
                ApiErrorResponse(
                    status = 409,
                    error = "CONFLICT",
                    message = ex.message ?: "Conflicting state",
                ),
            )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                ApiErrorResponse(
                    status = 400,
                    error = "VALIDATION_ERROR",
                    message = ex.message ?: "Invalid argument",
                ),
            )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
        val msg =
            ex.bindingResult.fieldErrors
                .joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
                .ifBlank { ex.message }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiErrorResponse(status = 400, error = "VALIDATION_ERROR", message = msg))
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                ApiErrorResponse(
                    status = 400,
                    error = "VALIDATION_ERROR",
                    message = ex.constraintViolations.joinToString("; ") { it.message },
                ),
            )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiErrorResponse(status = 400, error = "BAD_REQUEST", message = "Malformed JSON body"))

    @ExceptionHandler(PhysicsServiceException::class)
    fun handlePhysicsError(ex: PhysicsServiceException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(
                ApiErrorResponse(
                    status = 500,
                    error = "PHYSICS_ERROR",
                    message = ex.message ?: "Physics service error",
                    sessionId = ex.sessionId,
                ),
            )
}

/** Wraps unexpected exceptions from physics services (power flow, contingency, dispatch). */
class PhysicsServiceException(
    val sessionId: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
