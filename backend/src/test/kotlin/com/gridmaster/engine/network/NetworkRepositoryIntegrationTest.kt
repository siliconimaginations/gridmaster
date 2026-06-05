package com.gridmaster.engine.network

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gridmaster.engine.model.FuelType
import com.gridmaster.engine.model.Region
import com.gridmaster.persistence.NetworkSnapshotJpaRepository
import com.gridmaster.persistence.SqliteNetworkRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * Integration test for [SqliteNetworkRepository].
 * Spins up an in-memory H2 database (via @DataJpaTest) and verifies the full
 * save → loadIidm → latestSnapshot round-trip.
 *
 * Tagged [Tag("integration")] — excluded from the default test run,
 * included when running `./gradlew test -Pintegration`.
 */
@Tag("integration")
@DataJpaTest
@Import(SqliteNetworkRepository::class)
@TestPropertySource(
    properties = [
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
    ],
)
class NetworkRepositoryIntegrationTest {
    @Autowired
    lateinit var jpaRepository: NetworkSnapshotJpaRepository

    @Test
    fun `save and loadIidm round-trips the IIDM network`() {
        val objectMapper = buildObjectMapper()
        val repo = SqliteNetworkRepository(jpaRepository, objectMapper)
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
    fun `save and latestSnapshot round-trips the GridNetwork`() {
        val objectMapper = buildObjectMapper()
        val repo = SqliteNetworkRepository(jpaRepository, objectMapper)
        val metadata =
            mapOf(
                TestNetworkFactory.GENERATOR_1 to GeneratorMetadata(FuelType.GAS, 48.0),
            )
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
    fun `loadIidm returns null for unknown session`() {
        val repo = SqliteNetworkRepository(jpaRepository, buildObjectMapper())
        assertThat(repo.loadIidm("no-such-session")).isNull()
    }

    @Test
    fun `latestSnapshot returns null for unknown session`() {
        val repo = SqliteNetworkRepository(jpaRepository, buildObjectMapper())
        assertThat(repo.latestSnapshot("no-such-session")).isNull()
    }

    @Test
    fun `save overwrites existing snapshot for same session`() {
        val objectMapper = buildObjectMapper()
        val repo = SqliteNetworkRepository(jpaRepository, objectMapper)
        val mapper = IidmNetworkMapperImpl()

        val network = TestNetworkFactory.create()
        val snap1 = mapper.toGridNetwork(network)
        repo.save("session-3", network, snap1)

        // Mutate and save again
        network.getLoad(TestNetworkFactory.LOAD_1).p0 = 999.0
        val snap2 = mapper.toGridNetwork(network)
        repo.save("session-3", network, snap2)

        val loaded = repo.latestSnapshot("session-3")
        assertThat(loaded).isNotNull()
        val load1 = loaded!!.loads.first { it.id == TestNetworkFactory.LOAD_1 }
        assertThat(load1.activePowerMw).isEqualTo(999.0)
    }

    private fun buildObjectMapper(): ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
}
