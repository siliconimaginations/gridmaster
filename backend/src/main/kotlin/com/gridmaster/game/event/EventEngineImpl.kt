package com.gridmaster.game.event

import com.gridmaster.engine.model.FuelType
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.NetworkMutation
import com.gridmaster.game.TickContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.random.Random

/**
 * Default [EventEngine] implementation.
 *
 * ### Session state
 * Each session gets an isolated [SessionEventState] stored in [sessions].
 * All state mutations are guarded by [SessionEventState]-level locks so
 * sessions do not interfere with each other.
 *
 * ### Stochastic scheduling
 * Each event category uses an independent exponential inter-arrival draw:
 *   nextArrivalMinutes = -mean * ln(Uniform(0,1))
 * This produces a Poisson process with the configured mean inter-arrival time.
 *
 * ### Effect → Mutation conversion
 * [EventEffect.TripElement] → [NetworkMutation.TripLine] / [NetworkMutation.TripGenerator]
 * (element type detected from the [GridNetwork] snapshot).
 * [EventEffect.ScaleLoad] → [NetworkMutation.SetLoadPower] for every affected load.
 * [EventEffect.DerateElement] and [EventEffect.ScaleGeneratorCost] are stored as
 * active modifiers — they do not produce network mutations but are tracked for expiry.
 *
 * ### Event catalogue
 * A minimal built-in catalogue is used for stochastic draws. A richer YAML catalogue
 * (deferred to Stage 5) will replace this.
 */
@Component
class EventEngineImpl : EventEngine {
    private val log = LoggerFactory.getLogger(EventEngineImpl::class.java)

    /** Live event state per session, keyed by sessionId. */
    private val sessions = ConcurrentHashMap<String, SessionEventState>()

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    override fun register(
        sessionId: String,
        config: EventConfig,
    ) {
        val existing = sessions.putIfAbsent(sessionId, SessionEventState(config))
        check(existing == null) { "Session $sessionId is already registered in EventEngine" }
        log.info("EventEngine: registered session {} (seed={})", sessionId, config.randomSeed)
    }

    override fun onTick(
        context: TickContext,
        snapshot: GridNetwork,
    ): List<FiredEvent> {
        val state = sessions[context.sessionId] ?: return emptyList()
        return synchronized(state) {
            val fired = mutableListOf<FiredEvent>()

            // 1. Fire all deterministic events due this tick
            fired += drainDeterministic(state, context, snapshot)

            // 2. Advance stochastic schedulers and fire any due events
            fired += advanceStochastic(state, context, snapshot)

            // 3. Expire duration-based active effects
            expireEffects(state, context.gameTimeMinutes)

            // 4. Apply deferred card-choice effects from previous tick
            fired += applyDeferredCardEffects(state, context, snapshot)

            // Log all fired events
            for (fe in fired) {
                state.log.add(fe)
                log.info(
                    "EventEngine: session {} fired {} '{}' at t={}min ({} mutations)",
                    context.sessionId,
                    fe.event.category,
                    fe.event.description,
                    fe.firedAtGameTimeMinutes,
                    fe.mutations.size,
                )
            }
            fired
        }
    }

    override fun schedule(
        sessionId: String,
        event: GameEvent,
        atGameTimeMinutes: Long,
    ) {
        val state =
            sessions[sessionId]
                ?: throw IllegalStateException("Session $sessionId is not registered in EventEngine")
        synchronized(state) {
            state.deterministicQueue.add(atGameTimeMinutes to event)
        }
        log.debug("EventEngine: scheduled {} for session {} at t={}min", event.id, sessionId, atGameTimeMinutes)
    }

    override fun unregister(sessionId: String) {
        sessions.remove(sessionId)
        log.debug("EventEngine: unregistered session {}", sessionId)
    }

    override fun eventLog(sessionId: String): List<FiredEvent>? {
        val state = sessions[sessionId] ?: return null
        return synchronized(state) { state.log.toList() }
    }

    override fun pendingCards(sessionId: String): List<EventCard> {
        val state = sessions[sessionId] ?: return emptyList()
        return synchronized(state) { state.pendingCards.toList() }
    }

    override fun resolveCard(
        sessionId: String,
        cardId: String,
        optionIndex: Int,
    ) {
        val state =
            sessions[sessionId]
                ?: throw IllegalStateException("Session $sessionId has no pending event cards")
        synchronized(state) {
            val card =
                state.pendingCards.find { it.cardId == cardId }
                    ?: throw IllegalStateException("No pending card with id: $cardId")
            require(optionIndex in card.options.indices) {
                "Option index $optionIndex out of range for card with ${card.options.size} options"
            }
            state.pendingCards.remove(card)
            // Queue the chosen option (not just its effects) so durationMinutes is preserved
            state.deferredCardEffects.add(card.options[optionIndex])
            log.info(
                "EventEngine: session {} resolved card '{}' (id={}) → option [{}] '{}'",
                sessionId,
                card.prompt,
                cardId,
                optionIndex,
                card.options[optionIndex].label,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tick helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun drainDeterministic(
        state: SessionEventState,
        context: TickContext,
        snapshot: GridNetwork,
    ): List<FiredEvent> {
        val fired = mutableListOf<FiredEvent>()
        while (state.deterministicQueue.isNotEmpty() &&
            state.deterministicQueue.peek().first <= context.gameTimeMinutes
        ) {
            val (scheduledTime, event) = state.deterministicQueue.poll()
            val fe = fireEvent(event, scheduledTime, snapshot, context.gameTimeMinutes, state)
            fired += fe
        }
        return fired
    }

    private fun advanceStochastic(
        state: SessionEventState,
        context: TickContext,
        snapshot: GridNetwork,
    ): List<FiredEvent> {
        val fired = mutableListOf<FiredEvent>()
        for (category in EventCategory.values()) {
            val next = state.nextStochasticTime[category] ?: continue
            if (context.gameTimeMinutes >= next) {
                // Draw next inter-arrival time
                val mean = state.config.meanFor(category).toLong()
                val interArrival = drawExponential(mean, state.random)
                state.nextStochasticTime[category] = context.gameTimeMinutes + interArrival

                // Pick a random event template from the built-in catalogue
                val event = BuiltInCatalogue.randomFor(category, state.random) ?: continue
                fired += fireEvent(event, context.gameTimeMinutes, snapshot, context.gameTimeMinutes, state)
            }
        }
        return fired
    }

    private fun expireEffects(
        state: SessionEventState,
        currentGameTimeMinutes: Long,
    ) {
        val expired = state.activeModifiers.filter { it.expiresAt <= currentGameTimeMinutes }
        if (expired.isNotEmpty()) {
            state.activeModifiers.removeAll(expired)
            log.debug("EventEngine: expired {} active effect modifiers", expired.size)
        }
    }

    private fun applyDeferredCardEffects(
        state: SessionEventState,
        context: TickContext,
        snapshot: GridNetwork,
    ): List<FiredEvent> {
        if (state.deferredCardEffects.isEmpty()) return emptyList()
        val options = state.deferredCardEffects.toList()
        state.deferredCardEffects.clear()

        val allMutations = mutableListOf<NetworkMutation>()
        for (option in options) {
            val expiresAt = option.durationMinutes?.let { context.gameTimeMinutes + it }
            allMutations += option.effects.flatMap { convertEffect(it, snapshot, state, context.gameTimeMinutes, expiresAt) }
            // Store duration-based modifiers as active effects so they expire correctly
            if (expiresAt != null) {
                val modifiers =
                    option.effects.filterIsInstance<EventEffect.ScaleGeneratorCost>() +
                        option.effects.filterIsInstance<EventEffect.DerateElement>()
                if (modifiers.isNotEmpty()) {
                    state.activeModifiers.add(
                        ActiveEffectModifier(
                            eventId = "card-choice-${context.gameTimeMinutes}",
                            effects = modifiers,
                            expiresAt = expiresAt,
                        ),
                    )
                }
            }
        }

        // Always log the card-choice event so the player decision appears in the event log,
        // even when effects are modifier-only (no network mutations produced).
        return listOf(
            FiredEvent(
                event =
                    PolicyEvent(
                        id = "card-choice-${context.gameTimeMinutes}",
                        description = "Player card choice applied",
                        severity = EventSeverity.INFO,
                        card =
                            EventCard(
                                prompt = "Card choice",
                                options = emptyList(),
                            ),
                    ),
                firedAtGameTimeMinutes = context.gameTimeMinutes,
                mutations = allMutations,
                card = null,
                expiresAtGameTimeMinutes = null,
            ),
        )
    }

    private fun fireEvent(
        event: GameEvent,
        scheduledTime: Long,
        snapshot: GridNetwork,
        currentTime: Long,
        state: SessionEventState,
    ): FiredEvent {
        val durationMinutes =
            when (event) {
                is WeatherEvent -> event.durationMinutes.toLong()
                is EconomicEvent -> event.durationMinutes.toLong()
                is FuelEvent -> event.durationMinutes.toLong()
                is PolicyEvent -> null
            }
        val expiresAt = durationMinutes?.let { currentTime + it }

        // TODO: #72 — unify effect derivation; EconomicEvent/FuelEvent should use effects list like WeatherEvent
        val effects =
            when (event) {
                is WeatherEvent -> event.effects
                is EconomicEvent ->
                    event.loadScaleFactor?.let {
                        listOf(EventEffect.ScaleLoad(regionIds = null, factor = it))
                    } ?: emptyList()
                is PolicyEvent -> emptyList() // Card options applied on resolution
                is FuelEvent ->
                    listOf(
                        EventEffect.ScaleGeneratorCost(
                            fuelType = event.affectedFuelType,
                            factor = event.costMultiplier,
                        ),
                    )
            }

        val mutations =
            effects.flatMap { convertEffect(it, snapshot, state, currentTime, expiresAt) }

        // Track cost modifiers as active effects
        if (expiresAt != null) {
            val modifiers =
                effects.filterIsInstance<EventEffect.ScaleGeneratorCost>() +
                    effects.filterIsInstance<EventEffect.DerateElement>()
            if (modifiers.isNotEmpty()) {
                state.activeModifiers.add(
                    ActiveEffectModifier(
                        eventId = event.id,
                        effects = modifiers,
                        expiresAt = expiresAt,
                    ),
                )
            }
        }

        // Policy events enqueue a pending card
        val card = (event as? PolicyEvent)?.card
        if (card != null) {
            state.pendingCards.add(card)
        }

        return FiredEvent(
            event = event,
            firedAtGameTimeMinutes = scheduledTime,
            mutations = mutations,
            card = card,
            expiresAtGameTimeMinutes = expiresAt,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Effect → Mutation conversion
    // ─────────────────────────────────────────────────────────────────────────

    private fun convertEffect(
        effect: EventEffect,
        snapshot: GridNetwork,
        state: SessionEventState,
        currentTime: Long,
        expiresAt: Long?,
    ): List<NetworkMutation> =
        when (effect) {
            is EventEffect.TripElement -> tripElementMutations(effect, snapshot)
            is EventEffect.ScaleLoad -> scaleLoadMutations(effect, snapshot)
            // DerateElement and ScaleGeneratorCost are stored as active modifiers, not mutations
            is EventEffect.DerateElement -> emptyList()
            is EventEffect.ScaleGeneratorCost -> emptyList()
        }

    private fun tripElementMutations(
        effect: EventEffect.TripElement,
        snapshot: GridNetwork,
    ): List<NetworkMutation> {
        val elementId = effect.elementId
        return when {
            snapshot.lines.any { it.id == elementId } ->
                listOf(NetworkMutation.TripLine(elementId))
            snapshot.generators.any { it.id == elementId } ->
                listOf(NetworkMutation.TripGenerator(elementId))
            else -> {
                log.warn("EventEngine: TripElement — element '{}' not found in snapshot", elementId)
                emptyList()
            }
        }
    }

    private fun scaleLoadMutations(
        effect: EventEffect.ScaleLoad,
        snapshot: GridNetwork,
    ): List<NetworkMutation> {
        val affectedLoads =
            if (effect.regionIds.isNullOrEmpty()) {
                snapshot.loads
            } else {
                // Pre-compute O(M) map; load lookup is then O(1) per load. Closes #71.
                val busRegion: Map<String, String?> = snapshot.buses.associate { it.id to it.regionId }
                snapshot.loads.filter { load ->
                    busRegion[load.busId]?.let { it in effect.regionIds } ?: false
                }
            }
        return affectedLoads.map { load ->
            NetworkMutation.SetLoadPower(
                loadId = load.id,
                activePowerMw = (load.activePowerMw * effect.factor).coerceAtLeast(0.0),
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Draw an exponential random variable with the given [mean] in game-minutes. */
    private fun drawExponential(
        mean: Long,
        random: Random,
    ): Long = (-mean * ln(random.nextDouble().coerceAtLeast(1e-9))).toLong().coerceAtLeast(1L)

    companion object {
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-session event state
// ─────────────────────────────────────────────────────────────────────────────

/** Minimum game-time offset before the first stochastic event fires (30 grid-minutes). */
private const val INITIAL_OFFSET_MINUTES = 30L

internal class SessionEventState(val config: EventConfig) {
    val random: Random =
        if (config.randomSeed != null) Random(config.randomSeed) else Random.Default

    /** Deterministic queue sorted by scheduled game-time. */
    val deterministicQueue: PriorityQueue<Pair<Long, GameEvent>> =
        PriorityQueue(compareBy { it.first })

    /** Next scheduled fire time per category (game-minutes from session epoch). */
    val nextStochasticTime: MutableMap<EventCategory, Long> =
        EventCategory.values().associateWith { cat ->
            INITIAL_OFFSET_MINUTES +
                (-config.meanFor(cat) * ln(random.nextDouble().coerceAtLeast(1e-9))).toLong()
        }.toMutableMap()

    /** Card options from resolved player decisions, applied on the next tick. */
    val deferredCardEffects: MutableList<CardOption> = mutableListOf()

    /** Cards awaiting player response. */
    val pendingCards: MutableList<EventCard> = mutableListOf()

    /** Active cost/rating modifiers that expire at a future game time. */
    val activeModifiers: MutableList<ActiveEffectModifier> = mutableListOf()

    /** Ordered history of all fired events. */
    val log: MutableList<FiredEvent> = mutableListOf()
}

private fun EventConfig.meanFor(category: EventCategory): Int =
    when (category) {
        EventCategory.WEATHER -> weatherMeanInterArrivalMinutes
        EventCategory.ECONOMIC -> economicMeanInterArrivalMinutes
        EventCategory.POLICY -> policyMeanInterArrivalMinutes
        EventCategory.FUEL -> fuelMeanInterArrivalMinutes
    }

/**
 * An active effect modifier tracked for time-based expiry.
 * Effects of type [EventEffect.DerateElement] and [EventEffect.ScaleGeneratorCost]
 * are not applied as mutations but stored here so they can be reversed when
 * [expiresAt] is reached.
 */
internal data class ActiveEffectModifier(
    val eventId: String,
    val effects: List<EventEffect>,
    val expiresAt: Long,
)

// ─────────────────────────────────────────────────────────────────────────────
// Built-in event catalogue (minimal; replaced by YAML catalogue in Stage 5)
// ─────────────────────────────────────────────────────────────────────────────

private object BuiltInCatalogue {
    private val weatherEvents =
        listOf(
            WeatherEvent(
                id = "evt-storm-001",
                description = "Severe storm — line trips expected",
                severity = EventSeverity.CRITICAL,
                type = WeatherEventType.STORM,
                affectedRegionIds = null,
                durationMinutes = 120,
                // Load reduction models customer outages during severe storm.
                // Fine-grained line trips are resolved at runtime in the YAML catalogue (Stage 5).
                effects = listOf(EventEffect.ScaleLoad(regionIds = null, factor = 0.92)),
            ),
            WeatherEvent(
                id = "evt-heatwave-001",
                description = "Heat wave — demand spike",
                severity = EventSeverity.WARNING,
                type = WeatherEventType.HEAT_WAVE,
                affectedRegionIds = null,
                durationMinutes = 360,
                effects = listOf(EventEffect.ScaleLoad(regionIds = null, factor = 1.10)),
            ),
            WeatherEvent(
                id = "evt-highwind-001",
                description = "High winds — renewable output surge",
                severity = EventSeverity.INFO,
                type = WeatherEventType.HIGH_WIND,
                affectedRegionIds = null,
                durationMinutes = 180,
                effects = emptyList(),
            ),
            WeatherEvent(
                id = "evt-coldsnap-001",
                description = "Cold snap — demand surge and gas constraint",
                severity = EventSeverity.WARNING,
                type = WeatherEventType.COLD_SNAP,
                affectedRegionIds = null,
                durationMinutes = 240,
                effects = listOf(EventEffect.ScaleLoad(regionIds = null, factor = 1.15)),
            ),
        )

    private val economicEvents =
        listOf(
            EconomicEvent(
                id = "evt-holiday-001",
                description = "Public holiday — demand reduction",
                severity = EventSeverity.INFO,
                type = EconomicEventType.HOLIDAY,
                loadScaleFactor = 0.85,
                durationMinutes = 1440,
            ),
            EconomicEvent(
                id = "evt-gdp-001",
                description = "Economic growth — industrial demand increase",
                severity = EventSeverity.INFO,
                type = EconomicEventType.GDP_GROWTH,
                loadScaleFactor = 1.05,
                durationMinutes = 2880,
            ),
            EconomicEvent(
                id = "evt-ev-surge-001",
                description = "EV adoption surge — evening charging peak",
                severity = EventSeverity.WARNING,
                type = EconomicEventType.EV_ADOPTION_SURGE,
                loadScaleFactor = 1.08,
                durationMinutes = 120,
            ),
        )

    private val policyEvents =
        listOf(
            PolicyEvent(
                id = "evt-policy-subsidy-001",
                description = "Government offers renewable energy subsidy",
                severity = EventSeverity.INFO,
                card =
                    EventCard(
                        prompt = "Accept renewable subsidy?",
                        options =
                            listOf(
                                CardOption(
                                    label = "Accept — +20% wind capacity",
                                    effects =
                                        listOf(
                                            EventEffect.ScaleGeneratorCost(
                                                fuelType = FuelType.WIND,
                                                factor = 0.7,
                                            ),
                                        ),
                                    costGbp = 0.0,
                                ),
                                CardOption(
                                    label = "Decline",
                                    effects = emptyList(),
                                    costGbp = 0.0,
                                ),
                            ),
                    ),
            ),
            PolicyEvent(
                id = "evt-policy-carbon-001",
                description = "Carbon tax increase proposed",
                severity = EventSeverity.WARNING,
                card =
                    EventCard(
                        prompt = "Support carbon tax increase?",
                        options =
                            listOf(
                                CardOption(
                                    label = "Support — higher coal/gas costs",
                                    effects =
                                        listOf(
                                            EventEffect.ScaleGeneratorCost(
                                                fuelType = FuelType.COAL,
                                                factor = 1.3,
                                            ),
                                            EventEffect.ScaleGeneratorCost(
                                                fuelType = FuelType.GAS,
                                                factor = 1.15,
                                            ),
                                        ),
                                    costGbp = 0.0,
                                ),
                                CardOption(
                                    label = "Oppose — status quo",
                                    effects = emptyList(),
                                    costGbp = 0.0,
                                ),
                            ),
                    ),
            ),
        )

    private val fuelEvents =
        listOf(
            FuelEvent(
                id = "evt-gas-spike-001",
                description = "Gas supply disruption — price spike",
                severity = EventSeverity.WARNING,
                type = FuelEventType.PRICE_SPIKE,
                affectedFuelType = FuelType.GAS,
                costMultiplier = 1.5,
                durationMinutes = 720,
            ),
            FuelEvent(
                id = "evt-coal-collapse-001",
                description = "Coal price collapse",
                severity = EventSeverity.INFO,
                type = FuelEventType.PRICE_COLLAPSE,
                affectedFuelType = FuelType.COAL,
                costMultiplier = 0.6,
                durationMinutes = 1440,
            ),
        )

    private val catalogue: Map<EventCategory, List<GameEvent>> =
        mapOf(
            EventCategory.WEATHER to weatherEvents,
            EventCategory.ECONOMIC to economicEvents,
            EventCategory.POLICY to policyEvents,
            EventCategory.FUEL to fuelEvents,
        )

    fun randomFor(
        category: EventCategory,
        random: Random,
    ): GameEvent? = catalogue[category]?.randomOrNull(random)
}
