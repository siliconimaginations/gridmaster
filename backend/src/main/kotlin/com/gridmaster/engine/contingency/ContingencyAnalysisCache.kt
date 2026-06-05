package com.gridmaster.engine.contingency

import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe in-memory cache for the latest [ContingencyAnalysisResult].
 *
 * The cache is updated atomically after each background analysis run completes.
 * The game engine and alert system read from the cache on every tick without
 * blocking on the analysis thread.
 */
class ContingencyAnalysisCache {
    private val cached = AtomicReference<ContingencyAnalysisResult?>(null)

    /** Store [result] as the latest result. Thread-safe. */
    fun update(result: ContingencyAnalysisResult) {
        cached.set(result)
    }

    /** Return the latest result, or null if no run has completed yet. */
    fun latest(): ContingencyAnalysisResult? = cached.get()

    /** Clear the cache (called on session end or topology reset). */
    fun clear() {
        cached.set(null)
    }
}
