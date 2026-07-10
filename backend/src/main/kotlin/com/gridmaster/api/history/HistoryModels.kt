package com.gridmaster.api.history

/**
 * One rolling-history sample recorded at the end of a tick (issue #392).
 *
 * [gameTimeMinutes] is the simulated game clock value (see
 * [com.gridmaster.game.GRID_MINUTES_PER_TICK]), not wall-clock time — the
 * frontend's 24h/48h/72h/week/month range selector all operate on this field.
 */
data class HistorySampleDto(
    val gameTimeMinutes: Long,
    val totalLoadMw: Double,
    val totalGenerationMw: Double,
)

/**
 * Bounded, thread-safe, in-memory ring buffer of [HistorySampleDto] for one session.
 *
 * Sized to hold roughly one simulated month of ticks (~[DEFAULT_CAPACITY_TICKS]
 * entries at 10 simulated minutes per tick). Three numbers per tick is a trivial
 * memory footprint at this scale, so no downsampling or multi-resolution scheme
 * is used for v1 — the frontend (or the optional [snapshot] rangeMinutes
 * parameter) slices the tail for shorter ranges. See issue #392.
 *
 * In-memory only: history is lost on backend restart, matching the "start
 * in-memory-only, file a follow-up if reconnect-durability matters" decision
 * from #392's open questions.
 *
 * Not a Spring bean — one instance is owned per
 * [com.gridmaster.api.PhysicsSession], matching the lifetime of that session's
 * live network. [record] is called once per tick from
 * [com.gridmaster.game.TickEngineImpl]; [snapshot] is read from HTTP request
 * threads via `GET /api/sessions/{sessionId}/history` — both are synchronized
 * so concurrent tick-append and HTTP-read never race.
 */
class HistoryRingBuffer(
    private val capacity: Int = DEFAULT_CAPACITY_TICKS,
) {
    private val buffer = ArrayDeque<HistorySampleDto>(capacity)

    /** Appends a new sample, evicting the oldest entry once [capacity] is exceeded. */
    @Synchronized
    fun record(sample: HistorySampleDto) {
        buffer.addLast(sample)
        while (buffer.size > capacity) {
            buffer.removeFirst()
        }
    }

    /**
     * Returns a snapshot of the buffer, oldest first.
     *
     * When [rangeMinutes] is non-null, only samples within the last
     * [rangeMinutes] of simulated game time (relative to the most recent
     * sample) are returned — this lets the server slice for a range selector
     * (24h/48h/72h/week/month) without shipping the full month every time.
     * Null or an empty buffer returns the full buffer as-is.
     */
    @Synchronized
    fun snapshot(rangeMinutes: Long? = null): List<HistorySampleDto> {
        if (rangeMinutes == null || buffer.isEmpty()) return buffer.toList()
        val latest = buffer.last().gameTimeMinutes
        val cutoff = latest - rangeMinutes
        return buffer.filter { it.gameTimeMinutes >= cutoff }
    }

    companion object {
        /** ~1 simulated month at 10 minutes/tick (30 days x 24h x 6 ticks/hour). */
        const val DEFAULT_CAPACITY_TICKS = 4_320
    }
}
