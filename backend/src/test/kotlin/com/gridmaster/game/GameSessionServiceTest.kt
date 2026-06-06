package com.gridmaster.game

import com.gridmaster.api.PhysicsSession
import com.gridmaster.api.PhysicsSessionStore
import com.gridmaster.api.SessionNotFoundException
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.network.IidmNetworkMapper
import com.gridmaster.persistence.GameSessionEntity
import com.gridmaster.persistence.GameSessionJpaRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

private const val USER_ID = "user-abc"
private const val SESSION_ID = "sess-xyz"

/**
 * Unit tests for [GameSessionService].
 * All external dependencies are mocked — no DB, no PowSyBl solver.
 */
class GameSessionServiceTest {
    private val jpaRepository = mockk<GameSessionJpaRepository>()
    private val physicsSessionStore = mockk<PhysicsSessionStore>()
    private val networkMapper = mockk<IidmNetworkMapper>()

    private val service =
        GameSessionService(jpaRepository, physicsSessionStore, networkMapper)

    private fun stubEntity(sessionId: String = SESSION_ID) =
        GameSessionEntity(
            id = sessionId,
            userId = USER_ID,
            mode = GameMode.TUTORIAL,
            displayName = "My Session",
            iidmXml = buildMinimalIidmXml(),
            gameTimeEpochMinutes = 0L,
            clockState = ClockState.PAUSED,
            clockSpeedMultiplier = 1,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    @BeforeEach
    fun setUp() {
        every { networkMapper.toGridNetwork(any()) } returns
            GridNetwork(
                id = "net",
                name = "net",
                buses = emptyList(),
                lines = emptyList(),
                twoWindingsTransformers = emptyList(),
                threeWindingsTransformers = emptyList(),
                generators = emptyList(),
                loads = emptyList(),
                shuntCompensators = emptyList(),
            )
        every { physicsSessionStore.create(any(), any(), any()) } returns
            PhysicsSession(SESSION_ID, mockk(relaxed = true), mockk(relaxed = true))
        every { jpaRepository.save(any()) } returnsArgument 0
    }

    // -----------------------------------------------------------------------
    // create
    // -----------------------------------------------------------------------

    @Test
    fun `create persists session and registers in PhysicsSessionStore`() {
        val session = service.create(USER_ID, GameMode.TUTORIAL, "My Session", "tutorial")

        assertThat(session.userId).isEqualTo(USER_ID)
        assertThat(session.mode).isEqualTo(GameMode.TUTORIAL)
        assertThat(session.displayName).isEqualTo("My Session")
        assertThat(session.clockState).isEqualTo(ClockState.PAUSED)

        verify { jpaRepository.save(any()) }
        verify { physicsSessionStore.create(any(), any(), any()) }
    }

    @Test
    fun `create throws for unknown preset`() {
        assertThatThrownBy {
            service.create(USER_ID, GameMode.TUTORIAL, "Bad", "not_a_preset")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not_a_preset")
    }

    // -----------------------------------------------------------------------
    // listForUser
    // -----------------------------------------------------------------------

    @Test
    fun `listForUser returns sessions sorted by updatedAt descending`() {
        val older = stubEntity("s1").copy(updatedAt = Instant.ofEpochSecond(100))
        val newer = stubEntity("s2").copy(updatedAt = Instant.ofEpochSecond(200))
        // DB returns newest first; service must preserve that order without re-sorting
        every { jpaRepository.findAllByUserIdOrderByUpdatedAtDesc(USER_ID) } returns listOf(newer, older)

        val result = service.listForUser(USER_ID)

        assertThat(result.map { it.id }).containsExactly("s2", "s1")
    }

    // -----------------------------------------------------------------------
    // load
    // -----------------------------------------------------------------------

    @Test
    fun `load returns session and skips re-hydration when already live`() {
        every { jpaRepository.findByIdAndUserId(SESSION_ID, USER_ID) } returns stubEntity()
        every { physicsSessionStore.find(SESSION_ID) } returns
            PhysicsSession(SESSION_ID, mockk(relaxed = true), mockk(relaxed = true))

        val session = service.load(SESSION_ID, USER_ID)

        assertThat(session.id).isEqualTo(SESSION_ID)
        verify(exactly = 0) { physicsSessionStore.create(any(), any(), any()) }
    }

    @Test
    fun `load re-hydrates PhysicsSessionStore when session not live`() {
        every { jpaRepository.findByIdAndUserId(SESSION_ID, USER_ID) } returns stubEntity()
        every { physicsSessionStore.find(SESSION_ID) } returns null

        service.load(SESSION_ID, USER_ID)

        verify { physicsSessionStore.create(eq(SESSION_ID), any(), any()) }
    }

    @Test
    fun `load throws SessionNotFoundException for unknown sessionId`() {
        every { jpaRepository.findByIdAndUserId("bad-id", USER_ID) } returns null

        assertThatThrownBy { service.load("bad-id", USER_ID) }
            .isInstanceOf(SessionNotFoundException::class.java)
    }

    @Test
    fun `load throws SessionNotFoundException when session belongs to another user`() {
        val entity = stubEntity().copy(userId = "other-user")
        every { jpaRepository.findByIdAndUserId(SESSION_ID, USER_ID) } returns null

        assertThatThrownBy { service.load(SESSION_ID, USER_ID) }
            .isInstanceOf(SessionNotFoundException::class.java)
    }

    // -----------------------------------------------------------------------
    // delete
    // -----------------------------------------------------------------------

    @Test
    fun `delete removes session from DB and PhysicsSessionStore`() {
        every { jpaRepository.findByIdAndUserId(SESSION_ID, USER_ID) } returns stubEntity()
        every { jpaRepository.deleteById(SESSION_ID) } just runs
        every { physicsSessionStore.remove(SESSION_ID) } returns null

        service.delete(SESSION_ID, USER_ID)

        verify { jpaRepository.deleteById(SESSION_ID) }
        verify { physicsSessionStore.remove(SESSION_ID) }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Produce a valid (but tiny) IIDM XML that PowSyBl can deserialise. */
    private fun buildMinimalIidmXml(): String {
        // Use PresetNetworkFactory to build a real IIDM so load() round-trips.
        val baos = java.io.ByteArrayOutputStream()
        com.powsybl.iidm.serde.NetworkSerDe.write(PresetNetworkFactory.create("tutorial"), baos)
        return baos.toString(Charsets.UTF_8)
    }
}

// Allow copy() on data class workaround for non-data class entity
private fun GameSessionEntity.copy(
    userId: String = this.userId,
    updatedAt: Instant = this.updatedAt,
) = GameSessionEntity(
    id = id,
    userId = userId,
    mode = mode,
    displayName = displayName,
    iidmXml = iidmXml,
    gameTimeEpochMinutes = gameTimeEpochMinutes,
    clockState = clockState,
    clockSpeedMultiplier = clockSpeedMultiplier,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
)
