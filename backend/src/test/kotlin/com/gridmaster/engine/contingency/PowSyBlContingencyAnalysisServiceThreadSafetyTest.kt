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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression tests for #360.
 *
 * PowSyBl's `Network.variantManager` is not safe under concurrent mutation.
 * Before the fix, nothing serialized the background analysis run (which
 * clones/sets/removes variants throughout [PowSyBlContingencyAnalysisService.dcPreScreen])
 * against other concurrent touches of the same live [com.powsybl.iidm.network.Network] —
 * at 100x game speed the tick loop's per-tick calls overlapped a still-running
 * analysis almost every time, corrupting the shared variant array
 * (`ArrayIndexOutOfBoundsException` in `TDoubleArrayList`/`VariantManagerImpl`).
 *
 * These tests exercise the *real* PowSyBl `VariantManagerImpl` via
 * [TestNetworkFactory] rather than a mock, since the bug is specifically about
 * PowSyBl's own internal thread-unsafety — a mocked variant manager wouldn't
 * reproduce it.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class PowSyBlContingencyAnalysisServiceThreadSafetyTest {
    @Test
    fun `concurrent triggerAsync calls sharing one lock never corrupt the network's variant manager`() {
        val network = TestNetworkFactory.create()

        val mapper = mockk<IidmNetworkMapper>()
        every { mapper.toGridNetwork(any()) } returns emptyGridNetwork()

        val powerFlowService = mockk<PowerFlowService>()
        every { powerFlowService.solve(any(), any()) } answers {
            // A small delay widens the window in which a second, unsynchronized
            // caller could interleave variant mutations — this is what made the
            // race in #360 almost certain at 100x speed (10 ms/tick).
            Thread.sleep(5)
            PowerFlowResult(
                status = ConvergenceStatus.CONVERGED,
                solveMode = SolveMode.DC,
                iterationCount = 1,
                snapshot = emptyGridNetwork(),
                slackBusIds = emptyList(),
                violations = emptyList(),
                solveTimeMs = 5L,
            )
        }

        val service = PowSyBlContingencyAnalysisService(mapper = mapper, powerFlowService = powerFlowService)
        val lock = Any()
        val contingency =
            Contingency(
                id = "L12-outage",
                description = "Line L12 outage",
                elements = listOf(ContingencyElement.LineOutage(TestNetworkFactory.LINE_12)),
            )
        val params = ContingencyAnalysisParameters(contingencies = listOf(contingency))

        val failure = AtomicReference<Throwable?>(null)
        val threadCount = 8
        val iterationsPerThread = 15
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        // Simulates the real trigger sources that all shared one live network in
        // production: tick loop, REST endpoint, and CommandHandlerImpl all calling
        // triggerAsync concurrently. With the #360 fix, all of them pass the same
        // lock (the owning session in production; a shared Any() here).
        repeat(threadCount) {
            Thread {
                try {
                    startLatch.await()
                    repeat(iterationsPerThread) {
                        service.triggerAsync(network, "thread-safety-test-session", lock, params)
                        Thread.sleep(1)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }

        startLatch.countDown()
        assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue()

        // Give the background consumer a moment to drain any remaining queued runs.
        Thread.sleep(500)

        assertThat(failure.get())
            .withFailMessage { "Concurrent triggerAsync under a shared lock threw: ${failure.get()}" }
            .isNull()

        service.shutdown()
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
