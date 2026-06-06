package com.gridmaster.game.event

import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.game.TickContext

/**
 * Tick-driven scheduler that fires [GameEvent]s at their target game-time and
 * converts their effects into [FiredEvent]s containing ready-to-apply
 * [com.gridmaster.engine.model.NetworkMutation]s.
 *
 * Each session has independent event state. The engine is a singleton
 * managing all active sessions (like [com.gridmaster.game.TickEngine]).
 *
 * ### Lifecycle
 * 1. [register] — called when a session starts; initialises stochastic generators.
 * 2. [onTick] — called every tick from [com.gridmaster.game.TickEngineImpl].
 * 3. [unregister] — called when a session stops or is deleted.
 *
 * ### Determinism
 * Set [EventConfig.randomSeed] to make the event sequence fully reproducible
 * for Challenge scenarios and tutorial missions.
 */
interface EventEngine {
    /**
     * Register [sessionId] with the given [config].
     * Must be called before [onTick] for the session.
     *
     * @throws IllegalStateException if the session is already registered.
     */
    fun register(
        sessionId: String,
        config: EventConfig = EventConfig(),
    )

    /**
     * Called every tick. Fires any events scheduled at or before [context.gameTimeMinutes],
     * advances stochastic schedulers, and expires duration-based effect modifiers.
     *
     * @param snapshot Current network snapshot used to resolve load/generator IDs for
     *   effect-to-mutation conversion.
     * @return All events that fired this tick (empty list if none).
     */
    fun onTick(
        context: TickContext,
        snapshot: GridNetwork,
    ): List<FiredEvent>

    /**
     * Schedule a deterministic event to fire at exactly [atGameTimeMinutes].
     * Used by Tutorial and Challenge scenario scripts.
     *
     * @throws IllegalStateException if [sessionId] is not registered.
     */
    fun schedule(
        sessionId: String,
        event: GameEvent,
        atGameTimeMinutes: Long,
    )

    /**
     * Remove all event state for [sessionId].
     * No-op if the session is not registered.
     */
    fun unregister(sessionId: String)

    /**
     * Return the full event log for [sessionId] in chronological order,
     * or null if the session is not registered.
     */
    fun eventLog(sessionId: String): List<FiredEvent>?

    /**
     * Return any pending [EventCard]s awaiting player response,
     * or an empty list if none (or session not registered).
     */
    fun pendingCards(sessionId: String): List<EventCard>

    /**
     * Resolve a pending [EventCard] by selecting [optionIndex].
     * The chosen option's effects are applied on the next tick.
     *
     * @throws IllegalArgumentException if [optionIndex] is out of range.
     * @throws IllegalStateException if [sessionId] has no pending cards.
     */
    fun resolveCard(
        sessionId: String,
        cardPrompt: String,
        optionIndex: Int,
    )
}
