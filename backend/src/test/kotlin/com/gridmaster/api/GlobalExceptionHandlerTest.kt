package com.gridmaster.api

import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException

/**
 * Pure unit tests for [GlobalExceptionHandler] — no Spring context required.
 * Each test calls a handler method directly and asserts the [ResponseEntity] returned.
 */
class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()

    @Test
    fun `handleSessionNotFound returns 404 SESSION_NOT_FOUND with sessionId`() {
        val ex = SessionNotFoundException("sess-1")
        val response = handler.handleSessionNotFound(ex)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = response.body!!
        assertThat(body.status).isEqualTo(404)
        assertThat(body.error).isEqualTo("SESSION_NOT_FOUND")
        assertThat(body.sessionId).isEqualTo("sess-1")
    }

    @Test
    fun `handleInvalidMutation returns 400 INVALID_MUTATION`() {
        val ex = InvalidMutationException("bad mutation request")
        val response = handler.handleInvalidMutation(ex)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = response.body!!
        assertThat(body.status).isEqualTo(400)
        assertThat(body.error).isEqualTo("INVALID_MUTATION")
        assertThat(body.message).isEqualTo("bad mutation request")
    }

    @Test
    fun `handleIllegalState returns 409 CONFLICT with message`() {
        val ex = IllegalStateException("session already terminated")
        val response = handler.handleIllegalState(ex)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        val body = response.body!!
        assertThat(body.status).isEqualTo(409)
        assertThat(body.error).isEqualTo("CONFLICT")
        assertThat(body.message).isEqualTo("session already terminated")
    }

    @Test
    fun `handleIllegalArgument returns 400 VALIDATION_ERROR`() {
        val ex = IllegalArgumentException("unknown solve mode: XYZ")
        val response = handler.handleIllegalArgument(ex)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = response.body!!
        assertThat(body.status).isEqualTo(400)
        assertThat(body.error).isEqualTo("VALIDATION_ERROR")
        assertThat(body.message).isEqualTo("unknown solve mode: XYZ")
    }

    @Test
    fun `handleValidation returns 400 VALIDATION_ERROR with field error message`() {
        val fieldError = FieldError("request", "targetPMw", "must be positive")
        val bindingResult =
            mockk<BindingResult> {
                every { fieldErrors } returns listOf(fieldError)
            }
        val ex =
            mockk<MethodArgumentNotValidException>(relaxed = true) {
                every { this@mockk.bindingResult } returns bindingResult
            }
        val response = handler.handleValidation(ex)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = response.body!!
        assertThat(body.error).isEqualTo("VALIDATION_ERROR")
        assertThat(body.message).contains("targetPMw")
        assertThat(body.message).contains("must be positive")
    }

    @Test
    fun `handleConstraintViolation returns 400 VALIDATION_ERROR with violation message`() {
        val violation = mockk<ConstraintViolation<Any>>(relaxed = true)
        every { violation.message } returns "size must be between 1 and 24"
        val ex = ConstraintViolationException(setOf(violation))
        val response = handler.handleConstraintViolation(ex)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = response.body!!
        assertThat(body.status).isEqualTo(400)
        assertThat(body.error).isEqualTo("VALIDATION_ERROR")
        assertThat(body.message).contains("size must be between 1 and 24")
    }

    @Test
    fun `handleUnreadable returns 400 BAD_REQUEST with Malformed JSON body`() {
        val ex = mockk<HttpMessageNotReadableException>(relaxed = true)
        val response = handler.handleUnreadable(ex)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = response.body!!
        assertThat(body.status).isEqualTo(400)
        assertThat(body.error).isEqualTo("BAD_REQUEST")
        assertThat(body.message).isEqualTo("Malformed JSON body")
    }

    @Test
    fun `handlePhysicsError returns 500 PHYSICS_ERROR with sessionId`() {
        val ex = PhysicsServiceException("sess-99", "solver diverged")
        val response = handler.handlePhysicsError(ex)
        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        val body = response.body!!
        assertThat(body.status).isEqualTo(500)
        assertThat(body.error).isEqualTo("PHYSICS_ERROR")
        assertThat(body.message).isEqualTo("solver diverged")
        assertThat(body.sessionId).isEqualTo("sess-99")
    }
}
