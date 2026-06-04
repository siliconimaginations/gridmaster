# Game Clock

**Stage**: 1
**Status**: Draft — awaiting review
**Branch**: `stage/1/07-game-clock`
**Depends on**: [06-session-model.md](06-session-model.md), [02-power-flow.md](02-power-flow.md)

---

## Purpose

The game clock drives the simulation forward in discrete ticks. Each tick
represents a fixed interval of in-game time (default: 10 grid-minutes per
tick at 1× speed). The clock controls tick pacing, speed multipliers,
pause/resume, and automatic slowdown when events require player attention
or when the physics solver runs long.

---

## Scope

**In scope**
- `TickEngine`: coroutine-based loop that fires ticks at configurable wall-clock intervals
- Speed control: 1×–100× real-time multiplier, pause, auto-slow
- Tick budget monitoring: if a tick's work exceeds its wall-clock slot, the engine slips (no drops)
- Per-tick pipeline: apply mutations → power flow → contingency trigger → broadcast → auto-save
- `ClockState` transitions and persistence to `GameSession`

**Out of scope**
- Event scheduling (Module 08 consumes tick notifications)
- WebSocket broadcasting (Module 10)
- Auto-save mechanics (Module 06)

---

## Domain Model

```kotlin
interface TickEngine {
    fun start(sessionId: String)
    fun pause(sessionId: String)
    fun resume(sessionId: String)
    fun setSpeed(sessionId: String, multiplier: Int)
    fun stop(sessionId: String)
    val clockState: ClockState
    val currentGameTimeMinutes: Long
}

data class TickContext(
    val sessionId: String,
    val tickNumber: Long,
    val gameTimeMinutes: Long,      // game time at start of this tick
    val wallClockSlotMs: Long,      // allowed wall-clock duration for this tick
)

data class TickResult(
    val tickContext: TickContext,
    val powerFlowResult: PowerFlowResult,
    val pendingMutations: List<NetworkMutation>,
    val newAlerts: List<Alert>,
    val actualDurationMs: Long,
    val slipped: Boolean,           // true if actualDurationMs > wallClockSlotMs
)
```

### Speed and wall-clock slot

| Speed | Real-time interval per tick | Grid-minutes per tick |
|-------|----------------------------|-----------------------|
| 1×    | 1 000 ms                   | 10 min                |
| 10×   | 100 ms                     | 10 min                |
| 100×  | 10 ms                      | 10 min                |

Grid-minutes per tick is fixed at 10. Faster speeds compress the real-time
interval, not the grid-time step.

### Auto-slow triggers

The clock automatically reduces to 1× when:
- A `NETWORK_FAILURE` power flow result is received
- A `CRITICAL` violation or N-1 violation appears
- An event requiring player decision fires (e.g. a policy card)
- Tick slip ratio exceeds 50% over 5 consecutive ticks (solver is saturated)

Auto-slow is restored to the previous speed when the triggering condition clears.

---

## Per-Tick Pipeline

```
1. Dequeue pending NetworkMutations (from command handler + event engine)
2. Apply mutations to IIDM Network
3. PowerFlowService.solve() — mandatory each tick
4. Update GridNetwork snapshot
5. Check violations → generate Alerts
6. Notify ContingencyAnalysisService (triggers async if conditions met)
7. Notify EventEngine.onTick() (event scheduler advances)
8. Publish TickResult to WebSocket broadcast queue (Module 10)
9. Auto-save if tick % autoSaveInterval == 0
10. Advance gameTimeMinutes += gridMinutesPerTick
11. Update ClockState + persist to GameSession
```

Steps 3 and 4 are on the critical path (block the tick). Steps 6–9 are
non-blocking (fire-and-forget to separate coroutines).

---

## Design Decisions & Rationale

1. **Fixed grid-time step, variable real-time interval.**
   The power flow solve is always AC on a full 10-minute snapshot. Changing
   the grid-time step with speed would change the physics (load interpolation,
   event timing). Compressing the real-time interval is a pure UI concern.

2. **Slip rather than drop.**
   If a tick takes longer than its wall-clock slot (e.g. power flow is slow),
   the next tick starts immediately rather than dropping a tick. Grid-time
   advances correctly; only real-time pacing suffers. This preserves simulation
   integrity at the cost of apparent slow-down.

3. **Auto-slow on critical conditions.**
   Matching the game's pacing to the operational urgency teaches the player
   that some situations require more careful attention. It also prevents the
   clock from racing past important events while the player is not watching.

4. **Coroutine-based, not thread-pool.**
   Spring Boot coroutines (kotlinx.coroutines) on a single `CoroutineScope`
   tied to the session lifecycle. Structured concurrency ensures cleanup on
   session stop.

---

## Error Handling

| Failure | Handling |
|---------|----------|
| Power flow returns `NETWORK_FAILURE` | Clock auto-slows to 1×; alert raised; tick result marked failed; game continues at slow speed waiting for player intervention |
| Tick slip > 10 consecutive ticks | Clock pauses; alert "simulation is overloaded — reduce speed" raised |
| Coroutine cancelled unexpectedly | Clock state set to STOPPED; session persisted; error logged |

---

## Testing Strategy

**Unit tests**: mock all dependencies; assert tick pipeline calls services
in correct order; assert auto-slow triggers activate/deactivate correctly;
assert slip detection logic.

**Integration tests**: run 10 ticks on IEEE 14-bus; assert `gameTimeMinutes`
advances by 100; assert power flow called 10 times; assert auto-save called
at correct interval.

---

## Open Questions

1. **`gridMinutesPerTick` configurability**: currently fixed at 10 per tick.
   Should this be configurable per game mode? (e.g. tutorial: 10 min/tick;
   free play: 10–60 min/tick depending on planning horizon selected by player)
