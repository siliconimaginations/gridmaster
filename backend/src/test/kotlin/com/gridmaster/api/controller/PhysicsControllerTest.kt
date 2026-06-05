package com.gridmaster.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.gridmaster.api.dto.DispatchRequest
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.dispatch.DispatchResult
import com.gridmaster.engine.dispatch.DispatchService
import com.gridmaster.engine.dispatch.UnitCommitmentService
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.network.IidmNetworkMapper
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.SolveMode
import com.gridmaster.session.GameSession
import com.gridmaster.session.SessionRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@Tag("unit")
@WebMvcTest(PhysicsController::class)
class PhysicsControllerTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockBean lateinit var sessionRegistry: SessionRegistry

    @MockBean lateinit var powerFlowService: PowerFlowService

    @MockBean lateinit var contingencyService: ContingencyAnalysisService

    @MockBean lateinit var dispatchService: DispatchService

    @MockBean lateinit var unitCommitmentService: UnitCommitmentService

    @MockBean lateinit var mapper: IidmNetworkMapper

    private val sessionId = "test-session"

    private fun setupSession(): GameSession {
        val network = mockk<com.powsybl.iidm.network.Network>(relaxed = true)
        val session = GameSession(sessionId = sessionId, network = network)
        whenever(sessionRegistry.get(sessionId)).thenReturn(session)
        whenever(mapper.toGridNetwork(any(), any())).thenReturn(emptyGridNetwork())
        return session
    }

    @Test
    fun `GET network returns 404 for unknown session`() {
        whenever(sessionRegistry.get("unknown")).thenReturn(null)
        mockMvc.perform(get("/api/sessions/unknown/network"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("SESSION_NOT_FOUND"))
    }

    @Test
    fun `GET network returns 200`() {
        setupSession()
        mockMvc.perform(get("/api/sessions/$sessionId/network"))
            .andExpect(status().isOk)
    }

    @Test
    fun `POST mutations rejects empty list`() {
        setupSession()
        mockMvc.perform(
            post("/api/sessions/$sessionId/network/mutations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mutations":[]}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `GET powerflow returns 204 when no result yet`() {
        setupSession()
        mockMvc.perform(get("/api/sessions/$sessionId/powerflow"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `GET powerflow returns 200 when cached`() {
        val session = setupSession()
        session.updatePowerFlowResult(fakePowerFlowResult())
        mockMvc.perform(get("/api/sessions/$sessionId/powerflow"))
            .andExpect(status().isOk)
    }

    @Test
    fun `POST powerflow run returns 200 with status`() {
        setupSession()
        whenever(powerFlowService.solve(any(), any())).thenReturn(fakePowerFlowResult())
        mockMvc.perform(
            post("/api/sessions/$sessionId/powerflow/run")
                .contentType(MediaType.APPLICATION_JSON),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONVERGED"))
    }

    @Test
    fun `GET violations returns 204 when no power flow`() {
        setupSession()
        mockMvc.perform(get("/api/sessions/$sessionId/violations"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `GET violations returns array when power flow cached`() {
        val session = setupSession()
        session.updatePowerFlowResult(fakePowerFlowResult())
        mockMvc.perform(get("/api/sessions/$sessionId/violations"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.violations").isArray)
    }

    @Test
    fun `POST dispatch returns 200`() {
        setupSession()
        whenever(dispatchService.economicDispatch(any(), any(), any()))
            .thenReturn(fakeDispatchResult())
        val req = DispatchRequest(totalLoadMw = 100.0)
        mockMvc.perform(
            post("/api/sessions/$sessionId/dispatch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        ).andExpect(status().isOk)
    }

    @Test
    fun `GET contingencies returns 204 when no result`() {
        setupSession()
        whenever(contingencyService.latestResult()).thenReturn(null)
        mockMvc.perform(get("/api/sessions/$sessionId/contingencies"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `POST contingencies trigger returns 202`() {
        setupSession()
        mockMvc.perform(
            post("/api/sessions/$sessionId/contingencies/trigger")
                .contentType(MediaType.APPLICATION_JSON),
        ).andExpect(status().isAccepted)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun emptyGridNetwork() =
        GridNetwork(
            id = "test",
            name = "test",
            buses = emptyList(),
            lines = emptyList(),
            twoWindingsTransformers = emptyList(),
            threeWindingsTransformers = emptyList(),
            generators = emptyList(),
            loads = emptyList(),
            shuntCompensators = emptyList(),
        )

    private fun fakePowerFlowResult() =
        PowerFlowResult(
            status = ConvergenceStatus.CONVERGED,
            solveMode = SolveMode.AC,
            iterationCount = 5,
            snapshot = emptyGridNetwork(),
            slackBusIds = listOf("B1"),
            violations = emptyList(),
            solveTimeMs = 50L,
        )

    private fun fakeDispatchResult() =
        DispatchResult(
            targets = emptyList(),
            meritOrder = emptyList(),
            totalLoadMw = 100.0,
            totalDispatchedMw = 100.0,
            systemMarginalCostPerMwh = 30.0,
            unservedLoadMw = 0.0,
            dispatchedAt = Instant.now(),
        )
}
