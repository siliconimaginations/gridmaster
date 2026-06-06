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
        val userId = "existing-user-uuid"
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

    @TestConfiguration
    class Mocks {
        @Bean fun jwtService() = mockk<JwtService>(relaxed = true)
    }
}
