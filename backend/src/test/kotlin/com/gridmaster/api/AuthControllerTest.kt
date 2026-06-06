package com.gridmaster.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.gridmaster.api.security.JwtService
import com.gridmaster.api.security.SecurityConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * Unit tests for [AuthController] — JWT issuance without credentials.
 *
 * [SecurityConfig] is imported so the /api/auth/ permit-all rule is active
 * and these tests hit an unauthenticated endpoint (as in production).
 */
@WebMvcTest(AuthController::class)
@Import(SecurityConfig::class)
class AuthControllerTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var om: ObjectMapper

    @Autowired lateinit var jwtService: JwtService

    @Test
    fun `POST auth token with no body mints new userId and returns token`() {
        every { jwtService.issue(any()) } returns "signed.jwt.token"

        mvc.post("/api/auth/token") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { value("signed.jwt.token") }
            jsonPath("$.userId") { isNotEmpty() }
            jsonPath("$.expiresInDays") { isNumber() }
        }
    }

    @Test
    fun `POST auth token with existing userId returns token for that user`() {
        val userId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        every { jwtService.issue(userId) } returns "reissued.jwt"

        val body = """{"userId":"$userId"}"""
        mvc.post("/api/auth/token") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isOk() }
            jsonPath("$.userId") { value(userId) }
            jsonPath("$.token") { value("reissued.jwt") }
        }
    }

    @Test
    fun `POST auth token with no body at all also succeeds`() {
        every { jwtService.issue(any()) } returns "new.token"

        mvc.post("/api/auth/token")
            .andExpect { status { isOk() } }
    }

    @Test
    fun `POST auth token with non-UUID userId returns 400`() {
        mvc.post("/api/auth/token") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":"not-a-uuid"}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `POST auth token with empty string userId returns 400`() {
        // Clients must pass null or omit the field entirely; empty string is not a valid UUID
        mvc.post("/api/auth/token") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":""}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @TestConfiguration
    class Mocks {
        @Bean fun jwtService() = mockk<JwtService>(relaxed = true)
    }
}
