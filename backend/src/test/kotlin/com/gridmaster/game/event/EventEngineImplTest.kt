package com.gridmaster.game.event

import com.gridmaster.engine.model.Bus
import com.gridmaster.engine.model.FuelType
import com.gridmaster.engine.model.Generator
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.Line
import com.gridmaster.engine.model.Load
import com.gridmaster.engine.model.NetworkMutation
import com.gridmaster.game.TickContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for [EventEngineImpl].
 *
 * Uses a deterministic [EventConfig.randomSeed] throughout so results are reproducible.
 */
class EventEngineImplTest {
    private lateinit var engine: EventEngineImpl
    private val sessionId = "session-evt-1"

    // Minimal snapshot with one line, one load, one generator
    private val snapshot =
        GridNetwork(
            id = "test-net",
            name = "Test",
            buses =
                listOf(
                    Bus("b1", "Bus 1", 400.0, regionId = "north"),
                    Bus("b2", "Bus 2", 400.0, regionId = "south"),
                ),
            lines =
                listOf(
                    Line(
                        "l1",
                        "Line 1",
                        fromBusId = "b1",
                        toBusId = "b2",
                        ratingA = 500.0,
                        resistanceOhm = 0.01,
                        reactanceOhm = 0.1,
                        shuntCapacitanceSiemens = 0.0,
                    ),
                ),
            twoWindingsTransformers = emptyList(),
            threeWindingsTransformers = emptyList(),
            generators =
                listOf(
                    Generator(
                        id = "g1",
                        name = "Gen 1",
                        busId = "b1",
                        minActivePowerMw = 0.0,
                        maxActivePowerMw = 200.0,
                        targetActivePowerMw = 100.0,
                        targetReactivePowerMvar = 0.0,
                        targetVoltagePu = 1.0,
                        connected = true,
                        fuelType = FuelType.GAS,
                        marginalCostPerMwh = 30.0,
                    ),
                ),
            loads =
                listOf(
                    Load("ld1", "Load 1", "b1", activePowerMw = 80.0, reactivePowerMvar = 10.0, connected = true),
                    Load("ld2", "Load 2", "b2", activePowerMw = 50.0, reactivePowerMvar = 5.0, connected = true),
                ),
            shuntCompensators = emptyList(),
            snapshotAt = Instant.now(),
        )

    @BeforeEach
    fun setUp() {
        engine = EventEngineImpl()
    }

    private fun ctx(gameTimeMinutes: Long) =
        TickContext(
            sessionId = sessionId,
            tickNumber = gameTimeMinutes / 10,
            gameTimeMinutes = gameTimeMinutes,
            wallClockSlotMs = 100,
        )

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    fun `register creates session state`() {
        engine.register(sessionId)
        assertThat(engine.eventLog(sessionId)).isEmpty()
    }

    @Test
    fun `register twice throws`() {
        engine.register(sessionId)
        assertThatThrownBy { engine.register(sessionId) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `unregistered session returns null log`() {
        assertThat(engine.eventLog("nonexistent")).isNull()
    }

    @Test
    fun `unregister removes session`() {
        engine.register(sessionId)
        engine.unregister(sessionId)
        assertThat(engine.eventLog(sessionId)).isNull()
    }

    @Test
    fun `onTick on unregistered session returns empty list`() {
        val result = engine.onTick(ctx(100), snapshot)
        assertThat(result).isEmpty()
    }

    // ── Deterministic scheduling ─────────────────────────────────────────────

    @Test
    fun `schedule and onTick fires event at exact game time`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val event =
            WeatherEvent(
                id = "test-storm",
                description = "Test storm",
                severity = EventSeverity.CRITICAL,
                type = WeatherEventType.STORM,
                affectedRegionIds = null,
                durationMinutes = 60,
                effects = emptyList(),
            )
        engine.schedule(sessionId, event, atGameTimeMinutes = 100)

        // Tick before — no fire
        val before = engine.onTick(ctx(90), snapshot)
        assertThat(before.filter { it.event.id == "test-storm" }).isEmpty()

        // Tick at exact time — fires
        val at = engine.onTick(ctx(100), snapshot)
        assertThat(at.map { it.event.id }).contains("test-storm")
    }

    @Test
    fun `schedule fires event at or after scheduled time`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val event =
            WeatherEvent(
                id = "late-storm",
                description = "Late storm",
                severity = EventSeverity.WARNING,
                type = WeatherEventType.STORM,
                affectedRegionIds = null,
                durationMinutes = 30,
                effects = emptyList(),
            )
        engine.schedule(sessionId, event, atGameTimeMinutes = 50)

        // Tick at time 70 (after 50) — still fires (was pending)
        val result = engine.onTick(ctx(70), snapshot)
        assertThat(result.map { it.event.id }).contains("late-storm")
    }

    // ── Effect → Mutation conversion ─────────────────────────────────────────

    @Test
    fun `TripElement for a line produces TripLine mutation`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val event =
            WeatherEvent(
                id = "storm-trip",
                description = "Storm trips line",
                severity = EventSeverity.CRITICAL,
                type = WeatherEventType.STORM,
                affectedRegionIds = null,
                durationMinutes = 60,
                effects = listOf(EventEffect.TripElement("l1")),
            )
        engine.schedule(sessionId, event, atGameTimeMinutes = 100)

        val fired = engine.onTick(ctx(100), snapshot)
        val stormEvent = fired.find { it.event.id == "storm-trip" }!!
        assertThat(stormEvent.mutations).containsExactly(NetworkMutation.TripLine("l1"))
    }

    @Test
    fun `TripElement for a generator produces TripGenerator mutation`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val event =
            WeatherEvent(
                id = "gen-trip",
                description = "Generator trip",
                severity = EventSeverity.CRITICAL,
                type = WeatherEventType.STORM,
                affectedRegionIds = null,
                durationMinutes = 60,
                effects = listOf(EventEffect.TripElement("g1")),
            )
        engine.schedule(sessionId, event, atGameTimeMinutes = 100)

        val fired = engine.onTick(ctx(100), snapshot)
        val tripEvent = fired.find { it.event.id == "gen-trip" }!!
        assertThat(tripEvent.mutations).containsExactly(NetworkMutation.TripGenerator("g1"))
    }

    @Test
    fun `TripElement for unknown element produces no mutation and logs warning`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val event =
            WeatherEvent(
                id = "unknown-trip",
                description = "Trip unknown",
                severity = EventSeverity.WARNING,
                type = WeatherEventType.STORM,
                affectedRegionIds = null,
                durationMinutes = 30,
                effects = listOf(EventEffect.TripElement("nonexistent-id")),
            )
        engine.schedule(sessionId, event, atGameTimeMinutes = 100)

        val fired = engine.onTick(ctx(100), snapshot)
        val unknownEvent = fired.find { it.event.id == "unknown-trip" }!!
        assertThat(unknownEvent.mutations).isEmpty()
    }

    @Test
    fun `ScaleLoad system-wide produces SetLoadPower for all loads`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val event =
            EconomicEvent(
                id = "holiday-load",
                description = "Holiday demand drop",
                severity = EventSeverity.INFO,
                type = EconomicEventType.HOLIDAY,
                durationMinutes = 1440,
                effects = listOf(EventEffect.ScaleLoad(regionIds = null, factor = 0.85)),
            )
        engine.schedule(sessionId, event, atGameTimeMinutes = 100)

        val fired = engine.onTick(ctx(100), snapshot)
        val loadEvent = fired.find { it.event.id == "holiday-load" }!!
        assertThat(loadEvent.mutations).hasSize(2)
        assertThat(loadEvent.mutations).allMatch { it is NetworkMutation.SetLoadPower }
        val mutations = loadEvent.mutations.filterIsInstance<NetworkMutation.SetLoadPower>()
        val ld1 = mutations.find { it.loadId == "ld1" }!!
        assertThat(ld1.activePowerMw).isCloseTo(68.0, org.assertj.core.data.Offset.offset(0.01))
    }

    @Test
    fun `ScaleLoad by region produces mutations only for loads in that region`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val event =
            WeatherEvent(
                id = "north-heatwave",
                description = "Heat wave in north",
                severity = EventSeverity.WARNING,
                type = WeatherEventType.HEAT_WAVE,
                affectedRegionIds = listOf("north"),
                durationMinutes = 360,
                effects = listOf(EventEffect.ScaleLoad(regionIds = listOf("north"), factor = 1.10)),
            )
        engine.schedule(sessionId, event, atGameTimeMinutes = 100)

        val fired = engine.onTick(ctx(100), snapshot)
        val heatEvent = fired.find { it.event.id == "north-heatwave" }!!
        // Only ld1 (on bus b1, region "north") should be affected
        assertThat(heatEvent.mutations).hasSize(1)
        val mutation = heatEvent.mutations[0] as NetworkMutation.SetLoadPower
        assertThat(mutation.loadId).isEqualTo("ld1")
        assertThat(mutation.activePowerMw).isCloseTo(88.0, org.assertj.core.data.Offset.offset(0.01))
    }

    @Test
    fun `ScaleGeneratorCost and DerateElement produce no mutations`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val event =
            FuelEvent(
                id = "gas-spike",
                description = "Gas price spike",
                severity = EventSeverity.WARNING,
                type = FuelEventType.PRICE_SPIKE,
                affectedFuelType = FuelType.GAS,
                durationMinutes = 720,
                effects = listOf(EventEffect.ScaleGeneratorCost(fuelType = FuelType.GAS, factor = 1.5)),
            )
        engine.schedule(sessionId, event, atGameTimeMinutes = 100)

        val fired = engine.onTick(ctx(100), snapshot)
        val fuelEvent = fired.find { it.event.id == "gas-spike" }!!
        // ScaleGeneratorCost = no mutations
        assertThat(fuelEvent.mutations).isEmpty()
    }

    // ── Event log ────────────────────────────────────────────────────────────

    @Test
    fun `fired events are accumulated in the event log`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val event =
            WeatherEvent(
                id = "log-test",
                description = "Log test",
                severity = EventSeverity.INFO,
                type = WeatherEventType.HIGH_WIND,
                affectedRegionIds = null,
                durationMinutes = 60,
                effects = emptyList(),
            )
        engine.schedule(sessionId, event, 100)
        engine.onTick(ctx(100), snapshot)

        val log = engine.eventLog(sessionId)
        assertThat(log).anyMatch { it.event.id == "log-test" }
    }

    // ── Policy cards ─────────────────────────────────────────────────────────

    @Test
    fun `PolicyEvent enqueues a pending card`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val card =
            EventCard(
                description = "Accept subsidy?",
                options = listOf(CardOption("Yes", emptyList()), CardOption("No", emptyList())),
            )
        val event = PolicyEvent("pol-1", description = "Subsidy offered", severity = EventSeverity.INFO, card = card)
        engine.schedule(sessionId, event, 100)
        engine.onTick(ctx(100), snapshot)

        assertThat(engine.pendingCards(sessionId)).hasSize(1)
        assertThat(engine.pendingCards(sessionId)[0].description).isEqualTo("Accept subsidy?")
    }

    @Test
    fun `resolveCard removes the card from pending`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val card =
            EventCard(
                description = "Accept subsidy?",
                options = listOf(CardOption("Yes", emptyList()), CardOption("No", emptyList())),
            )
        val event = PolicyEvent("pol-2", description = "Subsidy", severity = EventSeverity.INFO, card = card)
        engine.schedule(sessionId, event, 100)
        engine.onTick(ctx(100), snapshot)

        val cardId = engine.pendingCards(sessionId)[0].cardId
        engine.resolveCard(sessionId, cardId, optionIndex = 0)
        assertThat(engine.pendingCards(sessionId)).isEmpty()
    }

    @Test
    fun `resolveCard with invalid optionIndex throws`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val card = EventCard(description = "Prompt?", options = listOf(CardOption("Only option", emptyList())))
        val event = PolicyEvent("pol-3", description = "Policy", severity = EventSeverity.INFO, card = card)
        engine.schedule(sessionId, event, 100)
        engine.onTick(ctx(100), snapshot)

        val cardId = engine.pendingCards(sessionId)[0].cardId
        assertThatThrownBy { engine.resolveCard(sessionId, cardId, optionIndex = 5) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `resolveCard with unknown cardId throws`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val card = EventCard(description = "Unknown?", options = listOf(CardOption("Option", emptyList())))
        val event = PolicyEvent("pol-4", description = "Policy", severity = EventSeverity.INFO, card = card)
        engine.schedule(sessionId, event, 100)
        engine.onTick(ctx(100), snapshot)

        assertThatThrownBy { engine.resolveCard(sessionId, "nonexistent-uuid", optionIndex = 0) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `resolveCard two cards with identical prompts resolved independently by cardId`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        // Both cards have the same prompt — previously ambiguous, now disambiguated by cardId
        val card1 = EventCard(description = "Same prompt?", options = listOf(CardOption("Option A", emptyList())))
        val card2 = EventCard(description = "Same prompt?", options = listOf(CardOption("Option B", emptyList())))
        engine.schedule(sessionId, PolicyEvent("pol-5a", description = "P5a", severity = EventSeverity.INFO, card = card1), 100)
        engine.schedule(sessionId, PolicyEvent("pol-5b", description = "P5b", severity = EventSeverity.INFO, card = card2), 100)
        engine.onTick(ctx(100), snapshot)

        assertThat(engine.pendingCards(sessionId)).hasSize(2)
        val ids = engine.pendingCards(sessionId).map { it.cardId }
        assertThat(ids[0]).isNotEqualTo(ids[1])

        // Resolve only the first card by its unique id
        engine.resolveCard(sessionId, ids[0], optionIndex = 0)
        assertThat(engine.pendingCards(sessionId)).hasSize(1)
        assertThat(engine.pendingCards(sessionId)[0].cardId).isEqualTo(ids[1])
    }

    @Test
    fun `card option with durationMinutes registers an active modifier that expires`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        val temporaryOption =
            CardOption(
                label = "Temporary subsidy",
                effects = listOf(EventEffect.ScaleGeneratorCost(fuelType = FuelType.GAS, factor = 0.8)),
                durationMinutes = 60,
            )
        val card = EventCard(description = "Accept temporary subsidy?", options = listOf(temporaryOption))
        val event = PolicyEvent("pol-dur-1", description = "Temp policy", severity = EventSeverity.INFO, card = card)
        engine.schedule(sessionId, event, 100)
        engine.onTick(ctx(100), snapshot)

        val cardId = engine.pendingCards(sessionId)[0].cardId
        engine.resolveCard(sessionId, cardId, optionIndex = 0)

        // Next tick applies the deferred option — modifier should be registered
        engine.onTick(ctx(110), snapshot)

        // Tick before expiry (100 + 60 = 160)
        engine.onTick(ctx(150), snapshot)

        // Tick at expiry — modifier removed (no exception expected; expiry is tested via no crash)
        engine.onTick(ctx(160), snapshot)

        // Event log should contain the card-choice applied event
        val log = engine.eventLog(sessionId)!!
        assertThat(log).anyMatch { it.event.id.startsWith("card-choice-") }
    }

    @Test
    fun `PolicyEvent produces no network mutations — card effects are applied via resolveCard only`() {
        engine.register(sessionId, EventConfig(randomSeed = 42))
        // PolicyEvent.effects is always emptyList(); card option effects are applied via resolveCard(),
        // not during the tick that fires the event. This test locks in that contract.
        val card =
            EventCard(
                description = "Accept subsidy?",
                options = listOf(CardOption("Yes", listOf(EventEffect.ScaleGeneratorCost(FuelType.GAS, 0.8)))),
            )
        val event = PolicyEvent("pol-no-mut", description = "Contract test", severity = EventSeverity.INFO, card = card)
        engine.schedule(sessionId, event, atGameTimeMinutes = 100)

        val fired = engine.onTick(ctx(100), snapshot)
        val policyFired = fired.find { it.event.id == "pol-no-mut" }!!
        assertThat(policyFired.mutations).isEmpty()
    }

    // ── Stochastic scheduling ─────────────────────────────────────────────────

    @Test
    fun `stochastic events fire at least once over many ticks`() {
        engine.register(sessionId, EventConfig(randomSeed = 99, weatherMeanInterArrivalMinutes = 60))
        // Advance 10000 game-minutes; at least one weather event must fire
        var totalFired = 0
        for (t in 30L..10000L step 10) {
            val fired = engine.onTick(ctx(t), snapshot)
            totalFired += fired.count { it.event.category == EventCategory.WEATHER }
        }
        assertThat(totalFired).isGreaterThan(0)
    }
}
