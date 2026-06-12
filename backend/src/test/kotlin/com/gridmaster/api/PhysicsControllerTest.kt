package com.gridmaster.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.gridmaster.api.security.JwtAuthFilter
import com.gridmaster.api.security.JwtService
import com.gridmaster.api.security.SecurityConfig
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.dispatch.DispatchResult
import com.gridmaster.engine.dispatch.DispatchService
import com.gridmaster.engine.dispatch.GeneratorTarget
import com.gridmaster.engine.dispatch.MeritOrderEntry
import com.gridmaster.engine.dispatch.UcHourSchedule
import com.gridmaster.engine.dispatch.UcResult
import com.gridmaster.engine.dispatch.UnitCommitmentService
import com.gridmaster.engine.model.FuelType
import com.gridmaster.engine.model.Generator
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.NetworkMutation
import com.gridmaster.engine.network.IidmNetworkMapper
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.PowerFlowParameters
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.SolveMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath as jJsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status as jStatus
import java.time.Instant

private const val SESSION_ID = "test-session-1"
private val BASE = "/api/sessions/$SESSION_ID"

/**
 * Unit tests for [PhysicsController] — all service dependencies are mocked.
 * Uses [WebMvcTest] for the Spring MVC layer only (no application context).
 */
@WebMvcTest(PhysicsController::class)
@Import(SecurityConfig::class, JwtAuthFilter::class)
@WithMockUser
class PhysicsControllerTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var om: ObjectMapper

    @Autowired lateinit var sessionStore: PhysicsSessionStore

    @Autowired lateinit var networkMapper: IidmNetworkMapper

    @Autowired lateinit var powerFlowService: PowerFlowService

    @Autowired lateinit var contingencyService: ContingencyAnalysisService

    @Autowired lateinit var dispatchService: DispatchService

    @Autowired lateinit var unitCommitmentService: UnitCommitmentService

    private lateinit var mockSession: PhysicsSession
    private lateinit var mockSnapshot: GridNetwork

    @BeforeEach
    fun setUp() {
        mockSnapshot =
            GridNetwork(
                id = SESSION_ID,
                name = "Test Network",
                buses = emptyList(),
                lines = emptyList(),
                twoWindingsTransformers = emptyList(),
                threeWindingsTransformers = emptyList(),
                generators =
                    listOf(
                        Generator(
                            id = "G1",
                            name = "Gas Gen",
                            busId = "B1",
                            minActivePowerMw = 20.0,
                            maxActivePowerMw = 100.0,
                            targetActivePowerMw = 80.0,
                            targetReactivePowerMvar = 10.0,
                            targetVoltagePu = 1.0,
                            connected = true,
                            fuelType = FuelType.GAS,
                            marginalCostPerMwh = 50.0,
                        ),
                    ),
                loads = emptyList(),
                shuntCompensators = emptyList(),
            )

        // Use a real PowSyBl Network so NetworkSerDe.write() works in triggerContingencies.
        // Mapper and service stubs still match via any() matchers, so other tests are unaffected.
        val iidmNetwork = com.gridmaster.engine.network.TestNetworkFactory.create()
        mockSession = PhysicsSession(SESSION_ID, iidmNetwork, mockSnapshot)
        every { sessionStore.get(SESSION_ID) } returns mockSession
        every { sessionStore.get(neq(SESSION_ID)) } throws SessionNotFoundException("unknown")
    }

    // -----------------------------------------------------------------------
    // GET /network
    // -----------------------------------------------------------------------

    @Test
    fun `GET network returns snapshot`() {
        mvc.get("$BASE/network")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(SESSION_ID) }
                jsonPath("$.name") { value("Test Network") }
            }
    }

    @Test
    fun `GET network returns 404 for unknown session`() {
        mvc.get("/api/sessions/unknown/network")
            .andExpect { status { isNotFound() } }
    }

    // -----------------------------------------------------------------------
    // POST /network/mutations
    // -----------------------------------------------------------------------

    @Test
    fun `POST mutations applies SET_GENERATOR_OUTPUT and returns updated snapshot`() {
        val iidmNetwork = mockSession.iidmNetwork
        every {
            networkMapper.applyMutation(any(), any<NetworkMutation.SetGeneratorOutput>())
        } returns Result.success(iidmNetwork)
        every { networkMapper.toGridNetwork(any()) } returns mockSnapshot

        val body = """{"mutations":[{"type":"SET_GENERATOR_OUTPUT","targetId":"G1","parameters":{"targetPMw":90.0}}]}"""

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(SESSION_ID) }
        }

        verify { networkMapper.applyMutation(any(), NetworkMutation.SetGeneratorOutput("G1", 90.0)) }
    }

    @Test
    fun `POST mutations returns 400 for unknown mutation type`() {
        val body = """{"mutations":[{"type":"UNKNOWN_TYPE","targetId":"G1"}]}"""

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("INVALID_MUTATION") }
        }
    }

    @Test
    fun `POST mutations returns 400 when mutations list is empty`() {
        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"mutations":[]}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `POST mutations rolls back network when second mutation fails`() {
        val iidmNetwork = mockSession.iidmNetwork
        val originalNetworkRef = mockSession.iidmNetwork

        // First mutation succeeds, second fails
        every {
            networkMapper.applyMutation(any(), any<NetworkMutation.SetGeneratorOutput>())
        } returns Result.success(iidmNetwork)
        every {
            networkMapper.applyMutation(any(), any<NetworkMutation.TripLine>())
        } returns Result.failure(RuntimeException("Line not found"))

        val body = """{"mutations":[
            {"type":"SET_GENERATOR_OUTPUT","targetId":"G1","parameters":{"targetPMw":90.0}},
            {"type":"TRIP_LINE","targetId":"nonexistent-line"}
        ]}"""

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isBadRequest() }
        }

        // Network was restored from snapshot — iidmNetwork is now a deserialized replacement,
        // not the original object. The snapshot was taken before any mutations ran.
        assertThat(mockSession.iidmNetwork).isNotSameAs(originalNetworkRef)

        // Session snapshot was not updated — latestSnapshot still points to the pre-request value
        assertThat(mockSession.latestSnapshot).isSameAs(mockSnapshot)
    }

    // -----------------------------------------------------------------------
    // GET /powerflow
    // -----------------------------------------------------------------------

    @Test
    fun `GET powerflow returns 204 when no result cached`() {
        mockSession.latestPowerFlowResult = null
        mvc.get("$BASE/powerflow")
            .andExpect { status { isNoContent() } }
    }

    @Test
    fun `GET powerflow returns cached result`() {
        mockSession.latestPowerFlowResult = minimalPowerFlowResult()
        mvc.get("$BASE/powerflow")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("CONVERGED") }
            }
    }

    // -----------------------------------------------------------------------
    // POST /powerflow/run
    // -----------------------------------------------------------------------

    @Test
    fun `POST powerflow run solves and caches result`() {
        val result = minimalPowerFlowResult()
        every { powerFlowService.solve(any(), any()) } returns result

        val pfResult = mvc.post("$BASE/powerflow/run").andReturn()
        mvc.perform(asyncDispatch(pfResult))
            .andExpect(jStatus().isOk())
            .andExpect(jJsonPath("$.status").value("CONVERGED"))

        verify { powerFlowService.solve(any(), PowerFlowParameters()) }
        assert(mockSession.latestPowerFlowResult == result)
    }

    // -----------------------------------------------------------------------
    // GET /violations
    // -----------------------------------------------------------------------

    @Test
    fun `GET violations returns empty list when no power flow run`() {
        mockSession.latestPowerFlowResult = null
        mvc.get("$BASE/violations")
            .andExpect {
                status { isOk() }
                jsonPath("$") { isArray() }
            }
    }

    // -----------------------------------------------------------------------
    // GET /contingencies
    // -----------------------------------------------------------------------

    @Test
    fun `GET contingencies returns 204 when no result cached`() {
        mockSession.latestContingencyResult = null
        mvc.get("$BASE/contingencies")
            .andExpect { status { isNoContent() } }
    }

    // -----------------------------------------------------------------------
    // POST /contingencies/trigger
    // -----------------------------------------------------------------------

    @Test
    fun `POST contingencies trigger returns 202`() {
        every { contingencyService.triggerAsync(any(), any()) } returns Unit

        val ctgResult = mvc.post("$BASE/contingencies/trigger").andReturn()
        mvc.perform(asyncDispatch(ctgResult)).andExpect(jStatus().isAccepted())

        verify { contingencyService.triggerAsync(any(), any()) }
    }

    // -----------------------------------------------------------------------
    // POST /dispatch
    // -----------------------------------------------------------------------

    @Test
    fun `POST dispatch runs economic dispatch and caches result`() {
        val result = minimalDispatchResult()
        every { dispatchService.economicDispatch(any(), any(), any()) } returns result

        val body = """{"totalLoadMw":180.0,"mode":"MERIT_ORDER"}"""
        val dspResult =
            mvc.post("$BASE/dispatch") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
        mvc.perform(asyncDispatch(dspResult))
            .andExpect(jStatus().isOk())
            .andExpect(jJsonPath("$.totalLoadMw").value(180.0))

        assert(mockSession.latestDispatchResult == result)
    }

    @Test
    fun `POST dispatch returns 400 for unknown mode`() {
        val body = """{"totalLoadMw":100.0,"mode":"UNKNOWN_MODE"}"""
        val badModeResult =
            mvc.post("$BASE/dispatch") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
        mvc.perform(asyncDispatch(badModeResult)).andExpect(jStatus().isBadRequest())
    }

    // -----------------------------------------------------------------------
    // POST /unitcommitment
    // -----------------------------------------------------------------------

    @Test
    fun `POST unitcommitment returns 400 when forecast has wrong size`() {
        val body = """{"hourlyForecastMw":[100.0,200.0]}""" // only 2 values
        mvc.post("$BASE/unitcommitment") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `POST unitcommitment runs UC and caches result`() {
        val ucResult = minimalUcResult()
        every { unitCommitmentService.commit(any(), any(), any()) } returns ucResult

        val forecast = (1..24).map { 100.0 + it }
        val body = om.writeValueAsString(mapOf("hourlyForecastMw" to forecast))

        val ucMvcResult =
            mvc.post("$BASE/unitcommitment") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
        mvc.perform(asyncDispatch(ucMvcResult))
            .andExpect(jStatus().isOk())
            .andExpect(jJsonPath("$.feasible").value(true))

        assert(mockSession.latestUcResult == ucResult)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun minimalPowerFlowResult() =
        PowerFlowResult(
            status = ConvergenceStatus.CONVERGED,
            solveMode = SolveMode.AC,
            iterationCount = 4,
            snapshot = mockSnapshot,
            slackBusIds = listOf("B1"),
            violations = emptyList(),
            solveTimeMs = 12L,
        )

    private fun minimalDispatchResult() =
        DispatchResult(
            targets = listOf(GeneratorTarget("G1", 80.0)),
            meritOrder =
                listOf(
                    MeritOrderEntry("G1", 50.0, 20.0, 100.0, 80.0, true),
                ),
            totalLoadMw = 180.0,
            totalDispatchedMw = 180.0,
            systemMarginalCostPerMwh = 50.0,
            unservedLoadMw = 0.0,
            dispatchedAt = Instant.now(),
        )

    private fun minimalUcResult() =
        UcResult(
            hourlySchedule =
                listOf(
                    UcHourSchedule(0, setOf("G1"), listOf(GeneratorTarget("G1", 80.0)), 100.0, 20.0),
                ),
            totalStartupCostGbp = 0.0,
            totalOperatingCostGbp = 4000.0,
            feasible = true,
            solveTimeMs = 5L,
        )

    @Test
    fun `POST mutations returns 400 when integer parameter is a fractional float`() {
        // 12.9 should be rejected — toInt() would silently truncate to 12
        val body = """{"mutations":[{"type":"SET_TAP_POSITION","targetId":"T1","parameters":{"tapPosition":12.9}}]}"""

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("INVALID_MUTATION") }
        }
    }

    @Test
    fun `POST mutations accepts integer parameter supplied as whole-number float`() {
        every {
            networkMapper.applyMutation(any(), any<NetworkMutation.SetTapPosition>())
        } returns Result.success(mockSession.iidmNetwork)

        // 12.0 is a valid whole-number float for tapPosition
        val body = """{"mutations":[{"type":"SET_TAP_POSITION","targetId":"T1","parameters":{"tapPosition":12.0}}]}"""

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isOk() }
        }
    }

    // Provides mocks to the Spring context
    @TestConfiguration
    class Mocks {
        @Bean fun sessionStore() = mockk<PhysicsSessionStore>(relaxed = true)

        @Bean fun networkMapper() = mockk<IidmNetworkMapper>(relaxed = true)

        @Bean fun powerFlowService() = mockk<PowerFlowService>(relaxed = true)

        @Bean fun contingencyService() = mockk<ContingencyAnalysisService>(relaxed = true)

        @Bean fun dispatchService() = mockk<DispatchService>(relaxed = true)

        @Bean fun unitCommitmentService() = mockk<UnitCommitmentService>(relaxed = true)

        @Bean fun jwtService() = mockk<JwtService>(relaxed = true)
    }
}
