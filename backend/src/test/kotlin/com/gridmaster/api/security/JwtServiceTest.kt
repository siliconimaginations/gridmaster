package com.gridmaster.api.security

import io.jsonwebtoken.JwtException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for [JwtService].
 *
 * Covers token issuance, validation, subject extraction, and init-time secret validation.
 * Tests are pure unit tests — no Spring context is required.
 */
class JwtServiceTest {
    // Must be ≥ 32 bytes for HMAC-SHA256
    private val secret = "gridmaster-test-secret-key-32bytes!!"
    private val service = JwtService(rawSecret = secret, expiryDays = 30L)

    @Test
    fun `issue produces a JWT that round-trips through parse with the correct subject`() {
        val token = service.issue("player-uuid-001")
        val claims = service.parse(token)
        assertThat(claims.subject).isEqualTo("player-uuid-001")
    }

    @Test
    fun `parse throws JwtException on a tampered token signature`() {
        val token = service.issue("player-uuid-001")
        val tampered = token.dropLast(5) + "XXXXX"
        assertThatThrownBy { service.parse(tampered) }
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `userIdOrNull returns the subject for a valid token`() {
        val token = service.issue("user-abc")
        assertThat(service.userIdOrNull(token)).isEqualTo("user-abc")
    }

    @Test
    fun `userIdOrNull returns null for a malformed token`() {
        assertThat(service.userIdOrNull("not.a.valid.jwt")).isNull()
    }

    @Test
    fun `userIdOrNull returns null for an empty string`() {
        assertThat(service.userIdOrNull("")).isNull()
    }

    @Test
    fun `init throws IllegalArgumentException when secret is shorter than 32 bytes`() {
        assertThatThrownBy { JwtService(rawSecret = "tooshort", expiryDays = 30L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("256 bits")
    }
}
