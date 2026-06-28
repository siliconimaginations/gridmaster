package com.gridmaster.api.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Unit tests for [JwtAuthFilter].
 *
 * Verifies that the filter populates (or leaves empty) the [SecurityContextHolder]
 * depending on the presence and validity of a Bearer JWT in the Authorization header.
 * Uses Spring Test's [MockHttpServletRequest] / [MockHttpServletResponse] to avoid
 * proxy-related interactions with [org.springframework.web.filter.OncePerRequestFilter].
 * No Spring application context is required.
 */
class JwtAuthFilterTest {
    private val secret = "gridmaster-test-secret-key-32bytes!!"
    private val jwtService = JwtService(rawSecret = secret, expiryDays = 30L)
    private val filter = JwtAuthFilter(jwtService)

    @BeforeEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun request(authHeader: String? = null) =
        MockHttpServletRequest().apply {
            if (authHeader != null) addHeader("Authorization", authHeader)
        }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `valid Bearer token populates SecurityContextHolder with authenticated principal`() {
        val token = jwtService.issue("user-123")

        filter.doFilter(request("Bearer $token"), MockHttpServletResponse(), MockFilterChain())

        val auth = SecurityContextHolder.getContext().authentication
        assertThat(auth).isNotNull()
        assertThat(auth.principal).isEqualTo("user-123")
    }

    @Test
    fun `missing Authorization header passes request through without setting auth`() {
        filter.doFilter(request(), MockHttpServletResponse(), MockFilterChain())

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `non-Bearer Authorization header passes request through without setting auth`() {
        filter.doFilter(request("Basic dXNlcjpwYXNz"), MockHttpServletResponse(), MockFilterChain())

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `invalid Bearer token passes request through without setting auth`() {
        filter.doFilter(request("Bearer not.a.real.token"), MockHttpServletResponse(), MockFilterChain())

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `valid token does not overwrite an existing authentication in the context`() {
        val existing =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "already-authed",
                null,
            )
        SecurityContextHolder.getContext().authentication = existing

        val token = jwtService.issue("user-456")
        filter.doFilter(request("Bearer $token"), MockHttpServletResponse(), MockFilterChain())

        assertThat(SecurityContextHolder.getContext().authentication.principal).isEqualTo("already-authed")
    }
}
