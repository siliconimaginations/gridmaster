package com.gridmaster.api.history

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [HistoryRingBuffer] (issue #392). */
class HistoryModelsTest {
    @Test
    fun `snapshot returns empty list for a fresh buffer`() {
        val buffer = HistoryRingBuffer()
        assertThat(buffer.snapshot()).isEmpty()
    }

    @Test
    fun `record appends samples in order`() {
        val buffer = HistoryRingBuffer()
        buffer.record(HistorySampleDto(gameTimeMinutes = 0L, totalLoadMw = 100.0, totalGenerationMw = 100.0))
        buffer.record(HistorySampleDto(gameTimeMinutes = 10L, totalLoadMw = 105.0, totalGenerationMw = 105.0))

        val samples = buffer.snapshot()
        assertThat(samples).hasSize(2)
        assertThat(samples[0].gameTimeMinutes).isEqualTo(0L)
        assertThat(samples[1].gameTimeMinutes).isEqualTo(10L)
    }

    @Test
    fun `evicts oldest entry once capacity is exceeded`() {
        val buffer = HistoryRingBuffer(capacity = 3)
        for (i in 0..3) {
            buffer.record(HistorySampleDto(gameTimeMinutes = i * 10L, totalLoadMw = 100.0, totalGenerationMw = 100.0))
        }

        val samples = buffer.snapshot()
        assertThat(samples).hasSize(3)
        // The oldest sample (gameTimeMinutes = 0) was evicted.
        assertThat(samples.map { it.gameTimeMinutes }).containsExactly(10L, 20L, 30L)
    }

    @Test
    fun `snapshot with rangeMinutes only returns samples within range of the latest sample`() {
        val buffer = HistoryRingBuffer()
        buffer.record(HistorySampleDto(gameTimeMinutes = 0L, totalLoadMw = 100.0, totalGenerationMw = 100.0))
        buffer.record(HistorySampleDto(gameTimeMinutes = 1440L, totalLoadMw = 120.0, totalGenerationMw = 120.0))
        buffer.record(HistorySampleDto(gameTimeMinutes = 2880L, totalLoadMw = 130.0, totalGenerationMw = 130.0))

        val samples = buffer.snapshot(rangeMinutes = 1440L)
        assertThat(samples.map { it.gameTimeMinutes }).containsExactly(1440L, 2880L)
    }

    @Test
    fun `snapshot with null rangeMinutes returns the full buffer`() {
        val buffer = HistoryRingBuffer()
        buffer.record(HistorySampleDto(gameTimeMinutes = 0L, totalLoadMw = 100.0, totalGenerationMw = 100.0))
        buffer.record(HistorySampleDto(gameTimeMinutes = 2880L, totalLoadMw = 130.0, totalGenerationMw = 130.0))

        assertThat(buffer.snapshot(rangeMinutes = null)).hasSize(2)
    }

    @Test
    fun `snapshot with rangeMinutes exceeding the total time span returns the full buffer`() {
        val buffer = HistoryRingBuffer()
        buffer.record(HistorySampleDto(gameTimeMinutes = 0L, totalLoadMw = 100.0, totalGenerationMw = 100.0))
        buffer.record(HistorySampleDto(gameTimeMinutes = 100L, totalLoadMw = 110.0, totalGenerationMw = 110.0))

        // Buffer only spans 100 minutes; a much larger range should still return everything.
        val samples = buffer.snapshot(rangeMinutes = 1_000_000L)
        assertThat(samples.map { it.gameTimeMinutes }).containsExactly(0L, 100L)
    }

    @Test
    fun `snapshot on an empty buffer with a non-null rangeMinutes returns empty without throwing`() {
        val buffer = HistoryRingBuffer()
        assertThat(buffer.snapshot(rangeMinutes = 1440L)).isEmpty()
    }
}
