package com.gridmaster.engine.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Tests for [BuildProject.percentComplete] boundary cases (#414). */
class ExpansionModelsTest {
    private fun project(
        startedAt: Long = 0L,
        durationMinutes: Long = 1_000L,
    ) = BuildProject(
        id = "BP-1",
        sessionId = "session-1",
        siteIds = listOf("EXP-1"),
        costGbp = 100_000.0,
        buildDurationGameMinutes = durationMinutes,
        startedAtGameTimeMinutes = startedAt,
        status = BuildStatus.BUILDING,
    )

    @Test
    fun `percentComplete is 0 exactly at the start time`() {
        val bp = project(startedAt = 500L, durationMinutes = 1_000L)
        assertThat(bp.percentComplete(currentGameTimeMinutes = 500L)).isEqualTo(0)
    }

    @Test
    fun `percentComplete is 50 at the halfway point`() {
        val bp = project(startedAt = 0L, durationMinutes = 1_000L)
        assertThat(bp.percentComplete(currentGameTimeMinutes = 500L)).isEqualTo(50)
    }

    @Test
    fun `percentComplete is 100 exactly at the duration boundary`() {
        val bp = project(startedAt = 0L, durationMinutes = 1_000L)
        assertThat(bp.percentComplete(currentGameTimeMinutes = 1_000L)).isEqualTo(100)
    }

    @Test
    fun `percentComplete clamps at 100 past the duration boundary`() {
        val bp = project(startedAt = 0L, durationMinutes = 1_000L)
        assertThat(bp.percentComplete(currentGameTimeMinutes = 5_000L)).isEqualTo(100)
    }

    @Test
    fun `percentComplete clamps at 0 before the start time`() {
        // Defensive case -- shouldn't happen in practice, but a query before
        // startedAtGameTimeMinutes must not report a negative percentage.
        val bp = project(startedAt = 1_000L, durationMinutes = 1_000L)
        assertThat(bp.percentComplete(currentGameTimeMinutes = 0L)).isEqualTo(0)
    }
}
