package com.gridmaster.engine.network

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gridmaster.engine.model.FuelType
import com.gridmaster.engine.model.Region
import com.gridmaster.persistence.NetworkSnapshotJpaRepository
import com.gridmaster.persistence.SqliteNetworkRepository
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

/**
 * Integration test for [SqliteNetworkRepository].
 * Uses @DataJpaTest which auto-configures an embedded H2 database.
 * [SqliteNetworkRepository] is instantiated manually — [runTest] is used
 * to call the suspend repository methods from test code.
 */
@Tag("integration")
@DataJpaTest
class NetworkRepositoryIntegrationTest {
    @Autowired
    lateinit var jpaRepository: NetworkSnapshotJpaRepository

    @Test
    fun `save and loadIidm round-trips the IIDM network`() =
        runTest {
            val repo = repo()
            val mapper = IidmNetworkMapperImpl()
            val network = TestNetworkFactory.create()
            val snapshot = mapper.toGridNetwork(network)

            repo.save("session-1", network, snapshot)

            val loaded = repo.loadIidm("session-1")
            assertThat(loaded).isNotNull()
            assertThat(loaded!!.id).isEqualTo(network.id)
            assertThat(loaded.lineCount).isEqualTo(network.lineCount)
            assertThat(loaded.twoWindingsTransformerCount).isEqualTo(network.twoWindingsTransformerCount)
            assertThat(loaded.generatorCount).isEqualTo(network.generatorCount)
            assertThat(loaded.loadCount).isEqualTo(network.loadCount)
        }

    @Test
    fun `save and latestSnapshot round-trips the GridNetwork`() =
        runTest {
            val metadata =
                mapOf(
                    TestNetworkFactory.GENERATOR_1 to GeneratorMetadata(FuelType.GAS, 48.0),
                )
            val repo = repo()
            val mapper = IidmNetworkMapperImpl(MapGeneratorMetadataProvider(metadata))
            val network = TestNetworkFactory.create()
            val regions = listOf(Region("R1", "North", setOf(TestNetworkFactory.BUS_1)))
            val snapshot = mapper.toGridNetwork(network, regions)

            repo.save("session-2", network, snapshot)

            val loaded = repo.latestSnapshot("session-2")
            assertThat(loaded).isNotNull()
            assertThat(loaded!!.id).isEqualTo(snapshot.id)
            assertThat(loaded.buses).hasSize(snapshot.buses.size)
            assertThat(loaded.lines).hasSize(snapshot.lines.size)
            assertThat(loaded.generators).hasSize(snapshot.generators.size)
            assertThat(loaded.regions).hasSize(1)
            assertThat(loaded.regions.first().id).isEqualTo("R1")

            val g1 = loaded.generators.first { it.id == TestNetworkFactory.GENERATOR_1 }
            assertThat(g1.fuelType).isEqualTo(FuelType.GAS)
            assertThat(g1.marginalCostPerMwh).isEqualTo(48.0)
        }

    @Test
    fun `loadIidm returns null for unknown session`() =
        runTest {
            assertThat(repo().loadIidm("no-such-session")).isNull()
        }

    @Test
    fun `latestSnapshot returns null for unknown session`() =
        runTest {
            assertThat(repo().latestSnapshot("no-such-session")).isNull()
        }

    @Test
    fun `save overwrites existing snapshot for same session`() =
        runTest {
            val repo = repo()
            val mapper = IidmNetworkMapperImpl()
            val network = TestNetworkFactory.create()

            repo.save("session-3", network, mapper.toGridNetwork(network))

            network.getLoad(TestNetworkFactory.LOAD_1).p0 = 999.0
            repo.save("session-3", network, mapper.toGridNetwork(network))

            val loaded = repo.latestSnapshot("session-3")
            assertThat(loaded).isNotNull()
            val load1 = loaded!!.loads.first { it.id == TestNetworkFactory.LOAD_1 }
            assertThat(load1.activePowerMw).isEqualTo(999.0)
        }

    private fun repo() = SqliteNetworkRepository(jpaRepository, objectMapper())

    private fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
}
