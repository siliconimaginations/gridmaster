package com.gridmaster.api

import com.gridmaster.api.security.JwtAuthFilter
import com.gridmaster.api.security.JwtService
import com.gridmaster.api.security.SecurityConfig
import com.gridmaster.game.ClockState
import com.gridmaster.game.TickClockStatus
import com.gridmaster.game.TickEngine
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

private const val CLOCK_SESSION_ID = "clock-sess-1"
private const val CLOCK_USER_ID = "clock-user-1"
private val CLOCK_BASE = "/api/sessions/$CLOCK_SESSION_ID/clock"

/**
 * Unit tests for [ClockController].
 * Security layer included via [SecurityConfig] + [JwtAuthFilter].
 */
@WebMvcTest(ClockController::class)
@Import(SecurityConfig::class, JwtAuthFilter::class)
class ClockControllerTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var tickEngine: TickEngine

    private fun stubStatus(state: ClockState = ClockState.RUNNING) =
        TickClockStatus(
            clockState = state,
            speedMultiplier = 1,
            gameTimeMinutes = 60L,
            tickCount = 6L,
            autoSlowed = false,
        )

    // ── GET /clock ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = CLOCK_USER_ID)
    fun `GET clock returns 200 with all clock fields when session is active`() {
        every { tickEngine.clockStatus(CLOCK_SESSION_ID, CLOCK_USER_ID) } returns stubStatus()

        mvc.get(CLOCK_BASE).andExpect {
            status { isOk() }
            jsonPath("$.clockState") { value("RUNNING") }
            jsonPath("$.speedMultiplier") { value(1) }
            jsonPath("$.gameTimeMinutes") { value(60) }
            jsonPath("$.tickCount") { value(6) }
            jsonPath("$.autoSlowed") { value(false) }
        }
    }

    @Test
    @WithMockUser(username = CLOCK_USER_ID)
    fun `GET clock returns 404 when session is not registered in engine`() {
        every { tickEngine.clockStatus(CLOCK_SESSION_ID, CLOCK_USER_ID) } returns null

        mvc.get(CLOCK_BASE).andExpect { status { isNotFound() } }
    }

    @Test
    fun `GET clock returns 401 when unauthenticated`() {
        mvc.get(CLOCK_BASE).andExpect { status { isUnauthorized() } }
    }

    // ── POST /clock/start ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = CLOCK_USER_ID)
    fun `POST clock start returns 200 with RUNNING state`() {
        every { tickEngine.start(CLOCK_SESSION_ID, CLOCK_USER_ID) } returns stubStatus(ClockState.RUNNING)

        mvc.post("$CLOCK_BASE/start").andExpect {
            status { isOk() }
            jsonPath("$.clockState") { value("RUNNING") }
        }
    }

    // ── POST /clock/pause ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = CLOCK_USER_ID)
    fun `POST clock pause returns 200 with PAUSED state`() {
        every { tickEngine.pause(CLOCK_SESSION_ID, CLOCK_USER_ID) } returns stubStatus(ClockState.PAUSED)

        mvc.post("$CLOCK_BASE/pause").andExpect {
            status { isOk() }
            jsonPath("$.clockState") { value("PAUSED") }
        }
    }

    // ── POST /clock/resume ────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = CLOCK_USER_ID)
    fun `POST clock resume returns 200 with RUNNING state`() {
        every { tickEngine.resume(CLOCK_SESSION_ID, CLOCK_USER_ID) } returns stubStatus(ClockState.RUNNING)

        mvc.post("$CLOCK_BASE/resume").andExpect {
            status { isOk() }
            jsonPath("$.clockState") { value("RUNNING") }
        }
    }

    // ── POST /clock/speed ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = CLOCK_USER_ID)
    fun `POST clock speed with valid multiplier returns updated speed`() {
        val updated = TickClockStatus(ClockState.RUNNING, speedMultiplier = 10, gameTimeMinutes = 60L, tickCount = 6L, autoSlowed = false)
        every { tickEngine.setSpeed(CLOCK_SESSION_ID, CLOCK_USER_ID, 10) } returns updated

        mvc.post("$CLOCK_BASE/speed") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"multiplier":10}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.speedMultiplier") { value(10) }
        }
    }

    @Test
    @WithMockUser(username = CLOCK_USER_ID)
    fun `POST clock speed with multiplier above 100 returns 400`() {
        mvc.post("$CLOCK_BASE/speed") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"multiplier":200}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    @WithMockUser(username = CLOCK_USER_ID)
    fun `POST clock speed with zero multiplier returns 400`() {
        mvc.post("$CLOCK_BASE/speed") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"multiplier":0}"""
        }.andExpect { status { isBadRequest() } }
    }

    // ── POST /clock/stop ──────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = CLOCK_USER_ID)
    fun `POST clock stop returns 204 and delegates to tickEngine`() {
        every { tickEngine.stop(CLOCK_SESSION_ID, CLOCK_USER_ID) } returns Unit

        mvc.post("$CLOCK_BASE/stop").andExpect { status { isNoContent() } }
        verify { tickEngine.stop(CLOCK_SESSION_ID, CLOCK_USER_ID) }
    }

    @TestConfiguration
    class Mocks {
        @Bean fun tickEngine() = mockk<TickEngine>(relaxed = true)

        @Bean fun jwtService() = mockk<JwtService>(relaxed = true)
    }
}
