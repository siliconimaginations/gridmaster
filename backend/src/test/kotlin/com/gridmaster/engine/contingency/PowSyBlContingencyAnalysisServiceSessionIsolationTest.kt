package com.gridmaster.engine.contingency

import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.network.IidmNetworkMapper
import com.gridmaster.engine.network.TestNetworkFactory
import com.gridmaster.engine.powerflow.ConvergenceStatus
import com.gridmaster.engine.powerflow.PowerFlowResult
import com.gridmaster.engine.powerflow.PowerFlowService
import com.gridmaster.engine.powerflow.SolveMode
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Regression tests for #347: [PowSyBlContingencyAnalysisService] is a singleton
 * Spring bean shared by every concurrent game session. Before this fix, it held
 * a single [ContingencyAnalysisCache], so one session's N-1 results were visible
 * (leaked) to every other session's [PowSyBlContingencyAnalysisService.latestResult]
 * call. These tests assert results are now isolated per sessionId.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class PowSyBlContingencyAnalysisServiceSessionIsolationTest {
    private fun newService(): PowSyBlContingencyAnalysisService {
        val mapper = mockk<IidmNetworkMapper>()
        every { mapper.toGridNetwork(any()) } returns emptyGridNetwork()

        val powerFlowService = mockk<PowerFlowService>()
        every { powerFlowService.solve(any(), any()) } returns
            PowerFlowResult(
                status = ConvergenceStatus.CONVERGED,
                solveMode = SolveMode.DC,
                iterationCount = 1,
                snapshot = emptyGridNetwork(),
                slackBusIds = emptyList(),
                violations = emptyList(),
                solveTimeMs = 1L,
            )

        return PowSyBlContingencyAnalysisService(mapper = mapper, powerFlowService = powerFlowService)
    }

    @Test
    fun `a completed analysis for one session is not visible via another session's latestResult`() {
        val service = newService()
        val network = TestNetworkFactory.create()
        val contingency =
            Contingency(
                id = "L12-outage",
                description = "Line L12 outage",
                elements = listOf(ContingencyElement.LineOutage(TestNetworkFactory.LINE_12)),
            )
        val params = ContingencyAnalysisParameters(contingencies = listOf(contingency))
        val lock = Any()

        try {
            service.triggerAsync(network, "session-A", lock, params)
            awaitResult(service, "session-A")

            assertThat(service.latestResult("session-A")).isNotNull()
            assertThat(service.latestResult("session-B"))
                .describedAs("session-B never triggered an analysis — must not see session-A's cached result (#347)")
                .isNull()
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `clearSession discards only that session's cached result`() {
        val service = newService()
        val network = TestNetworkFactory.create()
        val contingency =
            Contingency(
                id = "L12-outage",
                description = "Line L12 outage",
                elements = listOf(ContingencyElement.LineOutage(TestNetworkFactory.LINE_12)),
            )
        val params = ContingencyAnalysisParameters(contingencies = listOf(contingency))
        val lockA = Any()
        val lockB = Any()

        try {
            service.triggerAsync(network, "session-A", lockA, params)
            awaitResult(service, "session-A")
            service.triggerAsync(network, "session-B", lockB, params)
            awaitResult(service, "session-B")

            service.clearSession("session-A")

            assertThat(service.latestResult("session-A")).isNull()
            assertThat(service.latestResult("session-B")).isNotNull()
        } finally {
            service.shutdown()
        }
    }

    /** Polls [PowSyBlContingencyAnalysisService.latestResult] until the background run completes. */
    private fun awaitResult(
        service: PowSyBlContingencyAnalysisService,
        sessionId: String,
        timeoutMs: Long = 5_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (service.latestResult(sessionId) == null) {
            if (System.currentTimeMillis() > deadline) {
                error("Timed out waiting for contingency analysis to complete for $sessionId")
            }
            Thread.sleep(20)
        }
    }

    private fun emptyGridNetwork(): GridNetwork =
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
}
