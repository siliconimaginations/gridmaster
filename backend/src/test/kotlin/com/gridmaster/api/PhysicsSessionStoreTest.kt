package com.gridmaster.api

import com.gridmaster.engine.model.GridNetwork
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PhysicsSessionStore].
 *
 * Covers all CRUD operations: create, find (hit and miss), get (hit and throw),
 * remove (hit and miss), and sessionIds.  No Spring context required.
 */
class PhysicsSessionStoreTest {
    private lateinit var store: PhysicsSessionStore
    private val mockNetwork = mockk<com.powsybl.iidm.network.Network>(relaxed = true)
    private val mockSnapshot = mockk<GridNetwork>(relaxed = true)

    @BeforeEach
    fun setUp() {
        store = PhysicsSessionStore()
    }

    @Test
    fun `create stores session and returns it`() {
        val session = store.create("s1", mockNetwork, mockSnapshot)

        assertThat(session.sessionId).isEqualTo("s1")
        assertThat(store.find("s1")).isSameAs(session)
    }

    @Test
    fun `find returns null for unknown session`() {
        assertThat(store.find("unknown")).isNull()
    }

    @Test
    fun `get returns session when it exists`() {
        store.create("s2", mockNetwork, mockSnapshot)
        assertThat(store.get("s2").sessionId).isEqualTo("s2")
    }

    @Test
    fun `get throws SessionNotFoundException for unknown session`() {
        assertThatThrownBy { store.get("missing") }
            .isInstanceOf(SessionNotFoundException::class.java)
    }

    @Test
    fun `remove returns and deletes the session`() {
        store.create("s3", mockNetwork, mockSnapshot)

        val removed = store.remove("s3")

        assertThat(removed).isNotNull()
        assertThat(removed!!.sessionId).isEqualTo("s3")
        assertThat(store.find("s3")).isNull()
    }

    @Test
    fun `remove returns null for non-existent session`() {
        assertThat(store.remove("ghost")).isNull()
    }

    @Test
    fun `sessionIds returns set of all stored session IDs`() {
        store.create("a", mockNetwork, mockSnapshot)
        store.create("b", mockNetwork, mockSnapshot)

        assertThat(store.sessionIds()).containsExactlyInAnyOrder("a", "b")
    }

    @Test
    fun `sessionIds is empty when store is empty`() {
        assertThat(store.sessionIds()).isEmpty()
    }
}
