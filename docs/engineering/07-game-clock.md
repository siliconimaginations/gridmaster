# Game Clock

**Stage**: 1
**Status**: Draft — v2, addressing review comments
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

| Speed | Slot (ms) | Grid-time/tick | Grid-time/real-min | Grid-time/real-hour |
|-------|-----------|---------------|--------------------|--------------------|
| 1×    | 1 000     | 10 min        | 10 hours           | 25 days            |
| 10×   | 100       | 10 min        | 100 hours (~4 days)| ~8 months          |
| 60×   | 17        | 10 min        | 600 hours (~25 d)  | ~4 years           |
| 100×  | 10        | 10 min        | 1 000 hours (~42 d)| ~7 years           |

Grid-minutes per tick is fixed at 10 (confirmed). Faster speeds compress
the real-time slot, not the grid-time step.

**Justification for the speed range.** The power flow solve time governs
effective playable speed. PowSyBl open-loadflow benchmarks:

| Network size    | Est. AC solve time | Slip-free max speed |
|-----------------|--------------------|---------------------|
| 14-bus (tutorial) | ~50 ms           | ~20×                |
| 100-bus         | ~150 ms            | ~6×                 |
| 500-bus         | ~500 ms            | ~1× (slips at 2×+)  |

At higher nominal speeds the clock **slips** — the next tick starts immediately
after the solver finishes rather than waiting for the wall-clock slot. Slip is
safe: grid-time advances correctly and the player sees slower real-time pacing.
The 100× setting is a ceiling, not a guarantee; slip is the natural governor.
The UI should display actual grid-time advance rate so the player sees true pace.

### Long-run time scale (Free Play)

A session must support multi-year grid evolution. Calculations:

| Real play time    | Speed | Grid-time covered  |
|-------------------|-------|--------------------|
| 30 min            | 1×    | ~12 grid-days      |
| 1 hour            | 1×    | ~25 grid-days      |
| 1 hour            | 10×   | ~8 grid-months     |
| 1 hour            | 60×   | ~4 grid-years      |
| 2 hours           | 60×   | ~8 grid-years      |
| 1 week (casual)   | 10×   | ~5 grid-years      |

At ~60× for 1–2 hours of real play, the player covers 4–8 grid-years —
sufficient for meaningful expansion, technology transitions, and policy arcs.

**Event content implication**: the event catalogue (Module 08) must have
enough template variety that no event repeats within a single grid-year.
Tracked in Module 08 OQ 1.

**Data range**: `gameTimeEpochMinutes` as `Long`. At 10 min/tick × 5×10⁶
ticks for a decade of grid-time ≈ 5×10⁷ minutes — well within `Long` range.
No overflow concern.

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

4. **Coroutine-based pause via `CompletableDeferred` (implemented in PR #191).**
   The tick loop suspends on a `CompletableDeferred<Unit>` while paused — zero
   CPU cost. `pause()` creates a fresh deferred and stores it in
   `SessionRuntime.pauseSignal`; `resume()` and `stop()` call
   `CompletableDeferred.complete(Unit)` to wake the coroutine.

   `CompletableDeferred` was chosen over `Mutex(locked=true)` because any
   coroutine may call `complete()` regardless of which coroutine created the
   deferred, avoiding lock-ownership ambiguity. Both `clockState` and
   `pauseSignal` are updated atomically inside `synchronized(runtime)` in
   `pause()` to prevent the tick loop from observing `PAUSED` with a `null`
   signal between the two writes. The signal reference is captured in a local
   `val` before `await()` to guard against `resume()` nulling the field after
   `complete()` but before the `await()` returns.

   Earlier drafts considered `Mutex(locked=true)`, but `CompletableDeferred`
   gives cleaner ownership semantics for "wait for an external signal" patterns.

---

## Error Handling

| Failure | Handling |
|---------|----------|
| Power flow returns `NETWORK_FAILURE` | Clock auto-slows to 1×; alert raised; tick result marked failed; game continues at slow speed waiting for player intervention |
| Tick slip > 10 consecutive ticks | Clock pauses; alert "simulation is overloaded — reduce speed" raised |
| Coroutine cancelled unexpectedly | Clock state set to STOPPED; session persisted; error logged |

---

## Testing Strategy

**Unit tests** (`TickEngineImplTest`): all dependencies mocked with MockK.

- Lifecycle tests (start/pause/resume/stop/setSpeed) are synchronous — no
  coroutine involvement beyond verifying state transitions.
- Timing-sensitive tests (auto-slow activation, auto-slow recovery, auto-save
  interval, slip detection) use `runTest(UnconfinedTestDispatcher())` from
  `kotlinx-coroutines-test`. The test injects a `TestScope` as
  `TickEngineImpl.engineScope` before calling `start()`, so `delay()` calls
  inside the tick loop advance virtual time rather than blocking real
  wall-clock time. Each test calls `advanceTimeBy(N)` to fast-forward
  virtual milliseconds, then asserts the expected state. The engine is
  explicitly stopped inside the `runTest` block to avoid
  `UncompletedCoroutinesError` from the suspended tick coroutine.
- The slip-detection test retains `Thread.sleep(20)` in the mock to simulate
  a slow power-flow solve — real elapsed time drives slip detection via
  `System.currentTimeMillis()`. With `UnconfinedTestDispatcher`, the tick
  coroutine runs on the test thread, so 10 slips complete in ~200 ms of real
  wall-clock time (previously ~1 s with `delay(1000)`).

**Integration tests**: run 10 ticks on IEEE 14-bus; assert `gameTimeMinutes`
advances by 100; assert power flow called 10 times; assert auto-save called
at correct interval.

---

## Resolved Design Points (from review)

1. **`gridMinutesPerTick`**: fixed at 10 per tick across all modes. Agreed.

2. **Speed table**: calculations added. Effective max speed is governed by
   power flow solve time via the slip mechanism. 100× is a ceiling; slip-free
   operation is typically 6–20× depending on network size.

3. **Long-run time scale**: calculation table added. ~60× for 1–2 hours covers
   4–8 grid-years. Event catalogue depth (Module 08 OQ 1) must match.
