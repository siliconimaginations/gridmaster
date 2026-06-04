# Event Engine

**Stage**: 1
**Status**: Draft — awaiting review
**Branch**: `stage/1/08-event-engine`
**Depends on**: [07-game-clock.md](07-game-clock.md), [09-command-handler.md](09-command-handler.md)

---

## Purpose

The event engine generates dynamic environment events during Free Play and
Challenge modes: adverse weather, economic shifts, fuel supply disruptions,
and policy changes. Events fire at scheduled or stochastic game-time moments,
produce `NetworkMutation`s and/or player-facing decision cards, and drive the
long-running narrative of the grid's operating environment.

---

## Scope

**In scope**
- `EventEngine`: tick-driven scheduler; fires events at their target game-time
- Event type hierarchy: `WeatherEvent`, `EconomicEvent`, `PolicyEvent`, `FuelEvent`
- Stochastic scheduling: Poisson-distributed inter-arrival times per event category
- Deterministic scheduling: fixed-time events for Tutorial and Challenge missions
- Event effects: `NetworkMutation`s (e.g. trip a line, increase load), load profile
  modifiers, generator cost modifiers
- `EventCard`: player-facing decision prompt (e.g. "Accept renewable subsidy?")
- Event log: ordered history surfaced in the alert feed

**Out of scope**
- UI rendering of event cards (Module 11 UX)
- Region unlock events (Module 13 — Free Play progression)
- Dynamic simulation / fault cascade events (separate module, Stage 5)

---

## Domain Model

```kotlin
interface EventEngine {
    /** Called each tick; fires any events scheduled at or before currentGameTime. */
    fun onTick(context: TickContext): List<FiredEvent>

    /** Schedule a deterministic event (Tutorial/Challenge use). */
    fun schedule(event: GameEvent, atGameTimeMinutes: Long)

    /** Seed stochastic event generators for a Free Play session. */
    fun seedStochastic(sessionId: String, config: EventConfig)
}

// ── Event definitions ────────────────────────────────────────────────────────

sealed class GameEvent {
    abstract val id: String
    abstract val category: EventCategory
    abstract val description: String
    abstract val severity: EventSeverity
}

enum class EventCategory { WEATHER, ECONOMIC, POLICY, FUEL }
enum class EventSeverity { INFO, WARNING, CRITICAL }

data class WeatherEvent(
    override val id: String,
    override val category: EventCategory = EventCategory.WEATHER,
    override val description: String,
    override val severity: EventSeverity,
    val type: WeatherEventType,
    val affectedRegionId: String?,      // null = system-wide
    val durationMinutes: Int,
    val effects: List<EventEffect>,
) : GameEvent()

enum class WeatherEventType {
    STORM,        // line trips, ice loading (derated line ratings)
    HEAT_WAVE,    // demand spike, generator derating
    HIGH_WIND,    // variable wind generation boost
    COLD_SNAP,    // demand spike, gas supply constraint
}

data class EconomicEvent(
    override val id: String,
    override val category: EventCategory = EventCategory.ECONOMIC,
    override val description: String,
    override val severity: EventSeverity,
    val type: EconomicEventType,
    val loadScaleFactor: Double?,       // e.g. 1.15 = 15% demand increase
    val durationMinutes: Int,
) : GameEvent()

enum class EconomicEventType {
    GDP_GROWTH, RECESSION, HOLIDAY, INDUSTRIAL_BOOM, EV_ADOPTION_SURGE
}

data class PolicyEvent(
    override val id: String,
    override val category: EventCategory = EventCategory.POLICY,
    override val description: String,
    override val severity: EventSeverity,
    val card: EventCard,                // requires player decision
) : GameEvent()

data class FuelEvent(
    override val id: String,
    override val category: EventCategory = EventCategory.FUEL,
    override val description: String,
    override val severity: EventSeverity,
    val type: FuelEventType,
    val affectedFuelType: FuelType,
    val costMultiplier: Double,         // applied to marginalCostPerMwh
    val durationMinutes: Int,
) : GameEvent()

enum class FuelEventType { PRICE_SPIKE, SUPPLY_DISRUPTION, PRICE_COLLAPSE }

// ── Event effects ─────────────────────────────────────────────────────────────

sealed class EventEffect {
    data class TripElement(val elementId: String) : EventEffect()
    data class DerateElement(val elementId: String, val ratingFactor: Double) : EventEffect()
    data class ScaleLoad(val regionId: String?, val factor: Double) : EventEffect()
    data class ScaleGeneratorCost(val fuelType: FuelType, val factor: Double) : EventEffect()
}

// ── Player decision card ──────────────────────────────────────────────────────

data class EventCard(
    val prompt: String,
    val options: List<CardOption>,
)

data class CardOption(
    val label: String,
    val effects: List<EventEffect>,     // applied if player chooses this option
    val costGbp: Double = 0.0,
)

// ── Fired event (result of onTick) ────────────────────────────────────────────

data class FiredEvent(
    val event: GameEvent,
    val firedAtGameTimeMinutes: Long,
    val mutations: List<NetworkMutation>,   // immediate network mutations
    val card: EventCard?,                   // non-null if player decision needed
    val expiresAtGameTimeMinutes: Long?,    // when to reverse duration-based effects
)
```

### Stochastic scheduling

Each event category has an independent Poisson process with a configurable
mean inter-arrival time (`meanInterArrivalMinutes`). On each tick the engine
draws from an exponential distribution to advance the next scheduled time.

```kotlin
data class EventConfig(
    val weatherMeanInterArrivalMinutes: Int = 720,   // ~12 hours
    val economicMeanInterArrivalMinutes: Int = 4320, // ~3 days
    val policyMeanInterArrivalMinutes: Int = 14400,  // ~10 days
    val fuelMeanInterArrivalMinutes: Int = 7200,     // ~5 days
    val randomSeed: Long? = null,                    // null = random; set for deterministic replay
)
```

---

## Design Decisions & Rationale

1. **Sealed `GameEvent` hierarchy per category.**
   Each category has meaningfully different fields (weather has region +
   duration + effects; policy has a decision card). A flat event type with
   nullable fields would be unreadable. The sealed hierarchy makes event
   handling exhaustive and type-safe.

2. **Effects as data, not callbacks.**
   `EventEffect` is a sealed data class, not a lambda. Effects are serialisable
   (persisted to the event log), replayable, and testable in isolation.
   The command handler (Module 09) converts effects to `NetworkMutation`s.

3. **`randomSeed` for deterministic replay.**
   Setting `randomSeed` in `EventConfig` makes the event sequence fully
   reproducible — essential for Challenge scenarios and tutorial missions
   where specific events must fire at specific times.

4. **Duration-based effect expiry.**
   Events with a `durationMinutes` are tracked in the engine and their effects
   reversed when `expiresAtGameTimeMinutes` is reached. This is preferable to
   "undo" mutations because some effects (e.g. line trip) should not be
   automatically reversed — only cost/rating modifiers are auto-reversed.

---

## Error Handling

| Failure | Handling |
|---------|----------|
| Event effect mutation rejected by command handler | Log warning; fire event without that effect; alert player |
| Stochastic engine produces event with no available target (e.g. no lines in region) | Skip event; reschedule next occurrence |
| Player ignores an event card | Card remains pending; clock auto-slows; card re-displayed on next tick |

---

## Testing Strategy

**Unit tests**: mock `TickContext`; assert events fire at correct game-time;
assert effects converted to correct `NetworkMutation`s; assert Poisson
scheduling produces correct inter-arrival distribution over 1000 ticks.

**Integration tests**: deterministic seed; run Free Play session for simulated
30 days; assert at least one event of each category fires; assert load scale
effect applies correctly to network loads.

---

## Open Questions

1. **Event catalogue**: the specific events (storm names, policy descriptions,
   fuel price magnitudes) are game content rather than architecture. Propose
   defining them in a YAML catalogue file (`resources/events/catalogue.yml`)
   loaded at startup. Content authoring deferred to Stage 5.

2. **Event card UI blocking**: when a `PolicyEvent` fires, should the clock
   pause until the player responds, or only slow to 1×? Propose: slow to 1×
   and display a persistent banner; full pause only if the card has a time
   limit. UX design (Module 11) to decide.
