package com.gridmaster.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.gridmaster.api.security.JwtAuthFilter
import com.gridmaster.api.security.JwtService
import com.gridmaster.api.security.SecurityConfig
import com.gridmaster.engine.contingency.Contingency
import com.gridmaster.engine.contingency.ContingencyAnalysisResult
import com.gridmaster.engine.contingency.ContingencyAnalysisService
import com.gridmaster.engine.contingency.ContingencyElement
import com.gridmaster.engine.contingency.ContingencyResult
import com.gridmaster.engine.contingency.PostContingencyStatus
import com.gridmaster.engine.contingency.PostContingencyViolation
import com.gridmaster.engine.contingency.ViolationType
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
import com.gridmaster.engine.powerflow.EquipmentType
import com.gridmaster.engine.powerflow.PowerFlowParameters
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.SolveMode
import com.gridmaster.engine.powerflow.ViolationSeverity
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
import java.time.Instant
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath as jJsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status as jStatus

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

        // Use a real PowSyBl Network so variantManager.cloneVariant() works in triggerContingencies.
        // Mapper and service stubs still match via any() matchers, so other tests are unaffected.
        val iidmNetwork = com.gridmaster.engine.network.TestNetworkFactory.create()
        mockSession = PhysicsSession(SESSION_ID, iidmNetwork, mockSnapshot)
        every { sessionStore.get(SESSION_ID) } returns mockSession
        every { sessionStore.get(neq(SESSION_ID)) } throws SessionNotFoundException("unknown")
        // Default: the analysis service cache is empty; individual tests override.
        every { contingencyService.latestResult() } returns null
    }

    // -----------------------------------------------------------------------
    // GET /network
    // -----------------------------------------------------------------------

    @Test
    fun `GET network returns snapshot as GridNetworkWsDto`() {
        // latestDispatchResult is null by default → smc=null, systemMarginalCostPerMwh absent
        // Test setup has exactly one generator (G1); assert its id first so the index assumption
        // is explicit and not implicitly relying on PowSyBl's internal ordering.
        mvc.get("$BASE/network")
            .andExpect {
                status { isOk() }
                jsonPath("$.generators[0].id") { value("G1") }
                // WS-DTO field names (not domain GridNetwork names — targetActivePowerMw/connected)
                jsonPath("$.generators[0].activePowerMw") { value(80.0) }
                jsonPath("$.generators[0].committed") { value(true) }
                jsonPath("$.totalGenerationMw") { value(80.0) }
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

        // Network was rolled back via variant clone — iidmNetwork remains the same object
        // reference (it is a val), but its internal state was restored to pre-mutation values.
        assertThat(mockSession.iidmNetwork).isSameAs(originalNetworkRef)

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
    // GET /contingency/{branchId}
    // -----------------------------------------------------------------------

    @Test
    fun `GET contingency branchId returns 204 when no result cached`() {
        mockSession.latestContingencyResult = null
        mvc.get("$BASE/contingency/L1")
            .andExpect { status { isNoContent() } }
    }

    @Test
    fun `GET contingency branchId falls back to the analysis service cache`() {
        // The tick engine writes results to the service cache, not the session
        // field — the endpoint must serve them from there (#347).
        mockSession.latestContingencyResult = null
        every { contingencyService.latestResult() } returns
            contingencyAnalysisResult(secureLineResult("L1"))

        mvc.get("$BASE/contingency/L1")
            .andExpect {
                status { isOk() }
                jsonPath("$.contingencyId") { value("N1-LINE-L1") }
                jsonPath("$.status") { value("SECURE") }
            }
    }

    @Test
    fun `GET contingencies falls back to the analysis service cache`() {
        mockSession.latestContingencyResult = null
        every { contingencyService.latestResult() } returns
            contingencyAnalysisResult(secureLineResult("L1"))

        mvc.get("$BASE/contingencies")
            .andExpect {
                status { isOk() }
                jsonPath("$.contingencyResults[0].contingency.id") { value("N1-LINE-L1") }
            }
    }

    @Test
    fun `GET contingency branchId returns 404 when branch has no contingency`() {
        mockSession.latestContingencyResult =
            contingencyAnalysisResult(
                secureLineResult("L2"),
            )
        mvc.get("$BASE/contingency/L1")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `GET contingency branchId returns mapped result for matching line`() {
        val violation =
            PostContingencyViolation(
                equipmentId = "L5",
                equipmentType = EquipmentType.LINE,
                violationType = ViolationType.THERMAL,
                value = 1250.0,
                limit = 1000.0,
                loadingPercent = 125.0,
                severity = ViolationSeverity.CRITICAL,
            )
        mockSession.latestContingencyResult =
            contingencyAnalysisResult(
                ContingencyResult(
                    contingency =
                        Contingency(
                            id = "N1-LINE-L1",
                            description = "Loss of line L1",
                            elements = listOf(ContingencyElement.LineOutage("L1")),
                        ),
                    status = PostContingencyStatus.VIOLATION,
                    violations = listOf(violation),
                ),
                secureLineResult("L2"),
            )

        mvc.get("$BASE/contingency/L1")
            .andExpect {
                status { isOk() }
                jsonPath("$.contingencyId") { value("N1-LINE-L1") }
                jsonPath("$.status") { value("VIOLATION") }
                jsonPath("$.violations[0].equipmentId") { value("L5") }
                jsonPath("$.violations[0].equipmentType") { value("LINE") }
                jsonPath("$.violations[0].violationType") { value("THERMAL") }
                jsonPath("$.violations[0].value") { value(1250.0) }
                jsonPath("$.violations[0].limit") { value(1000.0) }
                jsonPath("$.violations[0].loadingPercent") { value(125.0) }
                jsonPath("$.violations[0].severity") { value("CRITICAL") }
            }
    }

    /** Builds a secure (no-violation) [ContingencyResult] for the line outage of [lineId]. */
    private fun secureLineResult(lineId: String): ContingencyResult =
        ContingencyResult(
            contingency =
                Contingency(
                    id = "N1-LINE-$lineId",
                    description = "Loss of line $lineId",
                    elements = listOf(ContingencyElement.LineOutage(lineId)),
                ),
            status = PostContingencyStatus.SECURE,
            violations = emptyList(),
        )

    /** Wraps [results] in a [ContingencyAnalysisResult] with neutral run metadata. */
    private fun contingencyAnalysisResult(vararg results: ContingencyResult): ContingencyAnalysisResult =
        ContingencyAnalysisResult(
            baseCaseSecure = results.all { it.status == PostContingencyStatus.SECURE },
            contingencyResults = results.toList(),
            criticalContingencies = emptyList(),
            analysisTimeMs = 0L,
            completedAt = Instant.now(),
            preScreenedContingenciesCount = results.size,
            fullAcContingenciesCount = results.size,
        )

    // -----------------------------------------------------------------------
    // POST /contingencies/trigger
    // -----------------------------------------------------------------------

    @Test
    fun `POST contingencies trigger returns 202`() {
        every { contingencyService.triggerAsync(any(), any(), any()) } returns Unit

        val ctgResult = mvc.post("$BASE/contingencies/trigger").andReturn()
        mvc.perform(asyncDispatch(ctgResult)).andExpect(jStatus().isAccepted())

        verify { contingencyService.triggerAsync(any(), any(), any()) }
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

    @Test
    fun `POST mutations applies SET_GENERATOR_VOLTAGE`() {
        val iidmNetwork = mockSession.iidmNetwork
        every { networkMapper.applyMutation(any(), any<NetworkMutation.SetGeneratorVoltage>()) } returns Result.success(iidmNetwork)
        every { networkMapper.toGridNetwork(any()) } returns mockSnapshot

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"mutations":[{"type":"SET_GENERATOR_VOLTAGE","targetId":"G1","parameters":{"targetVoltagePu":1.02}}]}"""
        }.andExpect { status { isOk() } }

        verify { networkMapper.applyMutation(any(), NetworkMutation.SetGeneratorVoltage("G1", 1.02)) }
    }

    @Test
    fun `POST mutations applies TRIP_LINE`() {
        val iidmNetwork = mockSession.iidmNetwork
        every { networkMapper.applyMutation(any(), any<NetworkMutation.TripLine>()) } returns Result.success(iidmNetwork)
        every { networkMapper.toGridNetwork(any()) } returns mockSnapshot

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"mutations":[{"type":"TRIP_LINE","targetId":"L1","parameters":{}}]}"""
        }.andExpect { status { isOk() } }

        verify { networkMapper.applyMutation(any(), NetworkMutation.TripLine("L1")) }
    }

    @Test
    fun `POST mutations applies CONNECT_LINE`() {
        val iidmNetwork = mockSession.iidmNetwork
        every { networkMapper.applyMutation(any(), any<NetworkMutation.ConnectLine>()) } returns Result.success(iidmNetwork)
        every { networkMapper.toGridNetwork(any()) } returns mockSnapshot

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"mutations":[{"type":"CONNECT_LINE","targetId":"L1","parameters":{}}]}"""
        }.andExpect { status { isOk() } }

        verify { networkMapper.applyMutation(any(), NetworkMutation.ConnectLine("L1")) }
    }

    @Test
    fun `POST mutations applies TRIP_GENERATOR`() {
        val iidmNetwork = mockSession.iidmNetwork
        every { networkMapper.applyMutation(any(), any<NetworkMutation.TripGenerator>()) } returns Result.success(iidmNetwork)
        every { networkMapper.toGridNetwork(any()) } returns mockSnapshot

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"mutations":[{"type":"TRIP_GENERATOR","targetId":"G1","parameters":{}}]}"""
        }.andExpect { status { isOk() } }

        verify { networkMapper.applyMutation(any(), NetworkMutation.TripGenerator("G1")) }
    }

    @Test
    fun `POST mutations applies CONNECT_GENERATOR`() {
        val iidmNetwork = mockSession.iidmNetwork
        every { networkMapper.applyMutation(any(), any<NetworkMutation.ConnectGenerator>()) } returns Result.success(iidmNetwork)
        every { networkMapper.toGridNetwork(any()) } returns mockSnapshot

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"mutations":[{"type":"CONNECT_GENERATOR","targetId":"G1","parameters":{}}]}"""
        }.andExpect { status { isOk() } }

        verify { networkMapper.applyMutation(any(), NetworkMutation.ConnectGenerator("G1")) }
    }

    @Test
    fun `POST mutations applies SET_LOAD_ACTIVE_POWER without reactive power`() {
        val iidmNetwork = mockSession.iidmNetwork
        every { networkMapper.applyMutation(any(), any<NetworkMutation.SetLoadPower>()) } returns Result.success(iidmNetwork)
        every { networkMapper.toGridNetwork(any()) } returns mockSnapshot

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"mutations":[{"type":"SET_LOAD_ACTIVE_POWER","targetId":"LD1","parameters":{"activePowerMw":75.0}}]}"""
        }.andExpect { status { isOk() } }

        verify { networkMapper.applyMutation(any(), NetworkMutation.SetLoadPower("LD1", 75.0, null)) }
    }

    @Test
    fun `POST mutations applies SET_LOAD_ACTIVE_POWER with reactive power`() {
        val iidmNetwork = mockSession.iidmNetwork
        every { networkMapper.applyMutation(any(), any<NetworkMutation.SetLoadPower>()) } returns Result.success(iidmNetwork)
        every { networkMapper.toGridNetwork(any()) } returns mockSnapshot

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"mutations":[{"type":"SET_LOAD_ACTIVE_POWER","targetId":"LD1",""" +
                """"parameters":{"activePowerMw":75.0,"reactivePowerMvar":15.0}}]}"""
        }.andExpect { status { isOk() } }

        verify { networkMapper.applyMutation(any(), NetworkMutation.SetLoadPower("LD1", 75.0, 15.0)) }
    }

    @Test
    fun `POST mutations applies CONNECT_LOAD`() {
        val iidmNetwork = mockSession.iidmNetwork
        every { networkMapper.applyMutation(any(), any<NetworkMutation.ConnectLoad>()) } returns Result.success(iidmNetwork)
        every { networkMapper.toGridNetwork(any()) } returns mockSnapshot

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"mutations":[{"type":"CONNECT_LOAD","targetId":"LD1","parameters":{}}]}"""
        }.andExpect { status { isOk() } }

        verify { networkMapper.applyMutation(any(), NetworkMutation.ConnectLoad("LD1")) }
    }

    @Test
    fun `POST mutations applies DISCONNECT_LOAD`() {
        val iidmNetwork = mockSession.iidmNetwork
        every { networkMapper.applyMutation(any(), any<NetworkMutation.DisconnectLoad>()) } returns Result.success(iidmNetwork)
        every { networkMapper.toGridNetwork(any()) } returns mockSnapshot

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"mutations":[{"type":"DISCONNECT_LOAD","targetId":"LD1","parameters":{}}]}"""
        }.andExpect { status { isOk() } }

        verify { networkMapper.applyMutation(any(), NetworkMutation.DisconnectLoad("LD1")) }
    }

    @Test
    fun `POST mutations applies SET_SHUNT_SECTION_COUNT`() {
        val iidmNetwork = mockSession.iidmNetwork
        every { networkMapper.applyMutation(any(), any<NetworkMutation.SetShuntSections>()) } returns Result.success(iidmNetwork)
        every { networkMapper.toGridNetwork(any()) } returns mockSnapshot

        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"mutations":[{"type":"SET_SHUNT_SECTION_COUNT","targetId":"SH1","parameters":{"sectionCount":3}}]}"""
        }.andExpect { status { isOk() } }

        verify { networkMapper.applyMutation(any(), NetworkMutation.SetShuntSections("SH1", 3)) }
    }

    @Test
    fun `POST mutations returns 400 INVALID_MUTATION when required parameter is missing`() {
        mvc.post("$BASE/network/mutations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"mutations":[{"type":"SET_GENERATOR_OUTPUT","targetId":"G1","parameters":{}}]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("INVALID_MUTATION") }
        }
    }

    @Test
    fun `POST powerflow run with DC mode and PROPORTIONAL_TO_LOAD balance type`() {
        val pfResult =
            PowerFlowResult(
                status = ConvergenceStatus.CONVERGED,
                solveMode = SolveMode.DC,
                iterationCount = 0,
                snapshot = mockSnapshot,
                slackBusIds = emptyList(),
                violations = emptyList(),
                solveTimeMs = 1,
            )
        every { powerFlowService.solve(any(), any()) } returns pfResult

        val asyncResult =
            mvc.post("$BASE/powerflow/run") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"mode":"DC","distributedSlack":false,"balanceType":"PROPORTIONAL_TO_LOAD"}"""
            }.andReturn()
        mvc.perform(asyncDispatch(asyncResult))
            .andExpect(jStatus().isOk())
            .andExpect(jJsonPath("$.status").value("CONVERGED"))

        verify { powerFlowService.solve(any(), match { it.mode == SolveMode.DC }) }
    }

    @Test
    fun `POST powerflow run returns 400 for unknown solve mode`() {
        val asyncResult =
            mvc.post("$BASE/powerflow/run") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"mode":"QUANTUM","distributedSlack":true,""" +
                    """"balanceType":"PROPORTIONAL_TO_GENERATION_P_MAX"}"""
            }.andReturn()
        mvc.perform(asyncDispatch(asyncResult))
            .andExpect(jStatus().isBadRequest())
    }

    @Test
    fun `POST powerflow run with PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN balance type`() {
        every { powerFlowService.solve(any(), any()) } returns
            PowerFlowResult(
                status = ConvergenceStatus.CONVERGED,
                solveMode = SolveMode.AC,
                iterationCount = 3,
                snapshot = mockSnapshot,
                slackBusIds = listOf("B1"),
                violations = emptyList(),
                solveTimeMs = 2,
            )

        val asyncResult =
            mvc.post("$BASE/powerflow/run") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"mode":"AC","distributedSlack":true,""" +
                    """"balanceType":"PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN"}"""
            }.andReturn()
        mvc.perform(asyncDispatch(asyncResult))
            .andExpect(jStatus().isOk())
    }

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
