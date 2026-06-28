package com.gridmaster.api.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Unit tests for [JwtAuthFilter].
 *
 * Verifies that the filter populates (or leaves empty) the [SecurityContextHolder]
 * depending on the presence and validity of a Bearer JWT in the Authorization header.
 * No Spring context is needed — the filter is instantiated directly with a real
 * [JwtService] and mock servlet objects.
 */
class JwtAuthFilterTest {
    private val secret = "gridmaster-test-secret-key-32bytes!!"
    private val jwtService = JwtService(rawSecret = secret, expiryDays = 30L)
    private val filter = JwtAuthFilter(jwtService)

    private val request = mockk<HttpServletRequest>(relaxed = true)
    private val response = mockk<HttpServletResponse>(relaxed = true)
    private val chain = mockk<FilterChain>(relaxed = true)

    @BeforeEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `shouldNotFilterAsyncDispatch returns false so filter runs on async re-dispatches`() {
        assertThat(filter.shouldNotFilterAsyncDispatch()).isFalse()
    }

    @Test
    fun `valid Bearer token populates SecurityContextHolder with authenticated principal`() {
        val token = jwtService.issue("user-123")
        every { request.getHeader("Authorization") } returns "Bearer $token"

        filter.doFilter(request, response, chain)

        val auth = SecurityContextHolder.getContext().authentication
        assertThat(auth).isNotNull()
        assertThat(auth.principal).isEqualTo("user-123")
        verify { chain.doFilter(request, response) }
    }

    @Test
    fun `missing Authorization header passes request through without setting auth`() {
        every { request.getHeader("Authorization") } returns null

        filter.doFilter(request, response, chain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify { chain.doFilter(request, response) }
    }

    @Test
    fun `non-Bearer Authorization header passes request through without setting auth`() {
        every { request.getHeader("Authorization") } returns "Basic dXNlcjpwYXNz"

        filter.doFilter(request, response, chain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify { chain.doFilter(request, response) }
    }

    @Test
    fun `invalid Bearer token passes request through without setting auth`() {
        every { request.getHeader("Authorization") } returns "Bearer not.a.real.token"

        filter.doFilter(request, response, chain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify { chain.doFilter(request, response) }
    }

    @Test
    fun `valid token does not overwrite an existing authentication in the context`() {
        val token = jwtService.issue("user-456")
        every { request.getHeader("Authorization") } returns "Bearer $token"

        // Pre-populate the context with a different authentication
        val existingToken =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "already-authed",
                null,
            )
        SecurityContextHolder.getContext().authentication = existingToken

        filter.doFilter(request, response, chain)

        // Must not overwrite the existing auth
        assertThat(SecurityContextHolder.getContext().authentication.principal).isEqualTo("already-authed")
    }
}
