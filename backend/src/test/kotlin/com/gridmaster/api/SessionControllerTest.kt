package com.gridmaster.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.gridmaster.api.security.JwtAuthFilter
import com.gridmaster.api.security.JwtService
import com.gridmaster.api.security.SecurityConfig
import com.gridmaster.game.ClockState
import com.gridmaster.game.GameMode
import com.gridmaster.game.GameSession
import com.gridmaster.game.GameSessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant

private const val USER_ID = "player-uuid-001"

/**
 * Unit tests for [SessionController].
 * [SecurityConfig] is imported so the JWT filter chain is active.
 * [@WithMockUser] satisfies authentication on protected endpoints.
 */
@WebMvcTest(SessionController::class)
@Import(SecurityConfig::class, JwtAuthFilter::class)
class SessionControllerTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var om: ObjectMapper

    @Autowired lateinit var gameSessionService: GameSessionService

    private fun stubSession(id: String = "sess-1") =
        GameSession(
            id = id,
            userId = USER_ID,
            mode = GameMode.TUTORIAL,
            displayName = "Test Session",
            iidmXml = "<network/>",
            gameTimeEpochMinutes = 0L,
            clockState = ClockState.PAUSED,
            clockSpeedMultiplier = 1,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    // -----------------------------------------------------------------------
    // POST /api/sessions
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = USER_ID)
    fun `POST sessions creates session and returns 201`() {
        every {
            gameSessionService.create(any(), any(), any(), any())
        } returns stubSession()

        val body = """{"displayName":"Test Session","mode":"TUTORIAL","networkPreset":"tutorial"}"""
        mvc.post("/api/sessions") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value("sess-1") }
            jsonPath("$.mode") { value("TUTORIAL") }
        }
    }

    @Test
    @WithMockUser(username = USER_ID)
    fun `POST sessions returns 400 when displayName is blank`() {
        val body = """{"displayName":"","mode":"TUTORIAL","networkPreset":"tutorial"}"""
        mvc.post("/api/sessions") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `POST sessions returns 401 without auth`() {
        val body = """{"displayName":"Test","mode":"TUTORIAL","networkPreset":"tutorial"}"""
        mvc.post("/api/sessions") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isUnauthorized() } }
    }

    // -----------------------------------------------------------------------
    // GET /api/sessions
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = USER_ID)
    fun `GET sessions returns list of sessions for user`() {
        every { gameSessionService.listForUser(USER_ID) } returns listOf(stubSession())

        mvc.get("/api/sessions")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value("sess-1") }
                jsonPath("$[0].displayName") { value("Test Session") }
            }
    }

    @Test
    @WithMockUser(username = USER_ID)
    fun `GET sessions returns empty list when user has no sessions`() {
        every { gameSessionService.listForUser(USER_ID) } returns emptyList()

        mvc.get("/api/sessions")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
    }

    // -----------------------------------------------------------------------
    // GET /api/sessions/{sessionId}
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = USER_ID)
    fun `GET sessions by id returns session detail`() {
        every { gameSessionService.load("sess-1", USER_ID) } returns stubSession("sess-1")

        mvc.get("/api/sessions/sess-1")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value("sess-1") }
                jsonPath("$.userId") { value(USER_ID) }
            }
    }

    @Test
    @WithMockUser(username = USER_ID)
    fun `GET sessions by id returns 404 for unknown session`() {
        every {
            gameSessionService.load("missing", USER_ID)
        } throws SessionNotFoundException("missing")

        mvc.get("/api/sessions/missing")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error") { value("SESSION_NOT_FOUND") }
            }
    }

    // -----------------------------------------------------------------------
    // DELETE /api/sessions/{sessionId}
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = USER_ID)
    fun `DELETE sessions removes session and returns 204`() {
        every { gameSessionService.delete("sess-1", USER_ID) } returns Unit

        mvc.delete("/api/sessions/sess-1")
            .andExpect { status { isNoContent() } }

        verify { gameSessionService.delete("sess-1", USER_ID) }
    }

    @TestConfiguration
    class Mocks {
        @Bean fun gameSessionService() = mockk<GameSessionService>(relaxed = true)

        @Bean fun jwtService() = mockk<JwtService>(relaxed = true)
    }
}
