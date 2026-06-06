# Contingency Analysis

**Stage**: 1
**Status**: Draft — awaiting review
**Branch**: `stage/1/contingency-analysis-design`
**Depends on**: [01-network-model.md](01-network-model.md), [02-power-flow.md](02-power-flow.md)

---

## Purpose

This module runs N-1 (and optionally N-2) security analysis on the current
network state: for each credible single-element outage (line trip, transformer
loss, generator loss), it checks whether the post-contingency network remains
within thermal and voltage limits.

Results feed the game engine's alert system (e.g. "Line X is critical — its
loss would overload Line Y") and drive the tutorial missions on contingency
analysis and security-constrained dispatch. In Challenge mode, pre-selected
contingencies form the crisis scenario the player must resolve.

Unlike the power flow solver (Module 02) which runs every tick, contingency
analysis runs **asynchronously in the background** — it is triggered by
significant network changes and its cached results are served to the game
engine between runs.

---

## Scope

**In scope**
- `ContingencyAnalysisService` interface and PowSyBl `SecurityAnalysis` implementation
- Automatic contingency list generation from the network (all N-1 elements)
- DC pre-screening to filter non-critical contingencies before full AC analysis
- `ContingencyAnalysisResult` and sub-types
- Async execution model: background coroutine, cached results, trigger conditions
- Post-contingency violation detection (thermal and voltage)

**Out of scope**
- N-2 contingency analysis (two simultaneous outages) — deferred; computationally expensive
- Corrective actions / remedial action schemes (Module to be added in Stage 5)
- Real-time contingency re-evaluation on every tick (too slow; see async model)
- Contingency scenario loading for Challenge mode (Module 16)

---

## Key Concepts

### PowSyBl SecurityAnalysis API

PowSyBl's `SecurityAnalysis.runAsync(network, contingencies, parameters)`
evaluates a list of contingencies against the current network state. For each
contingency it internally trips the specified elements, runs a power flow
(AC or DC), and records limit violations. The original `Network` is not
mutated — PowSyBl works on internal copies.

```
SecurityAnalysis.runAsync(network, contingencies, params)
        │
        ├─ for each Contingency:
        │   ├─ clone network, trip elements
        │   ├─ run power flow (AC or DC pre-screen)
        │   └─ collect LimitViolations (current, voltage)
        └─ returns SecurityAnalysisResult
```

### N-1 principle

A network is **N-1 secure** if the loss of any single element (line,
transformer, generator) does not cause any remaining elements to exceed their
emergency ratings. The game teaches this as a core planning and operational
concept.

### DC pre-screening

For networks approaching 500 buses, running a full AC power flow per
contingency is slow. DC pre-screening runs a fast linear DC solve for each
contingency first, and only escalates contingencies with DC violations to
full AC analysis. This reduces the AC solve count by typically 80–90% while
missing very few real violations.

### Async execution and caching

```
Network change detected (mutation, new tick with load shift)
        │
        ▼
ContingencyAnalysisService enqueues a run (debounced — CONFLATED channel, latest trigger wins;
        game clock triggers every 6 ticks / 1 grid-hour during free play)
        │
        ▼ (background coroutine — does not block game tick)
Run DC pre-screen → filter → run AC on flagged contingencies
        │
        ▼
ContingencyAnalysisCache updated atomically
        │
        ▼
Game engine reads latest cached result on next tick
Alert system notified if new critical contingencies appeared
```

---

## Domain Model

```kotlin
interface ContingencyAnalysisService {
    /** Trigger an async N-1 run. Returns immediately; result available via cache. */
    fun triggerAsync(network: Network, parameters: ContingencyAnalysisParameters)

    /** Latest cached result, or null if no run has completed yet. */
    fun latestResult(): ContingencyAnalysisResult?

    /** Build the default N-1 contingency list from the network (all single elements). */
    fun buildN1Contingencies(network: Network): List<Contingency>
}

data class ContingencyAnalysisParameters(
    val contingencies: List<Contingency> = emptyList(),
        // empty = auto-build N-1 from network via buildN1Contingencies()
    val dcPreScreening: Boolean = true,
    val postContingencyRatingMultiplier: Double = 1.0,
        // multiplier applied to normal branch ratings for post-contingency checks.
        // 1.0 = normal ratings enforced (conservative).
        // 1.1 = 110% of normal rating allowed post-contingency (emergency rating).
        // Must be >= 1.0; values < 1.0 (stricter than normal) are rejected.
)

// ── Contingency definition ───────────────────────────────────────────────────

data class Contingency(
    val id: String,
    val description: String,
    val elements: List<ContingencyElement>,
)

sealed class ContingencyElement {
    data class LineOutage(val lineId: String) : ContingencyElement()
    data class TwoWindingsTransformerOutage(val transformerId: String) : ContingencyElement()
    data class ThreeWindingsTransformerOutage(val transformerId: String) : ContingencyElement()
    data class GeneratorOutage(val generatorId: String) : ContingencyElement()
}

// ── Results ──────────────────────────────────────────────────────────────────

data class ContingencyAnalysisResult(
    val baseCaseSecure: Boolean,                    // pre-contingency violations present?
    val contingencyResults: List<ContingencyResult>,
    val criticalContingencies: List<String>,        // IDs of contingencies with CRITICAL violations
    val analysisTimeMs: Long,
    val completedAt: Instant,
    val preScreenedContingenciesCount: Int,         // contingencies filtered out by DC pre-screen (no AC needed)
    val fullAcContingenciesCount: Int,              // contingencies evaluated with full AC power flow
)

data class ContingencyResult(
    val contingency: Contingency,
    val status: PostContingencyStatus,
    val violations: List<PostContingencyViolation>,
    val worstViolationSeverity: ViolationSeverity?, // null if no violations
)

enum class PostContingencyStatus {
    SECURE,           // no violations post-contingency
    VIOLATION,        // one or more limit violations
    NETWORK_FAILURE,  // post-contingency power flow did not converge (severe outage)
}

data class PostContingencyViolation(
    val equipmentId: String,
    val equipmentType: EquipmentType,           // reuses EquipmentType from Module 02
    val violationType: ViolationType,
    val value: Double,                          // actual current (A) or voltage (pu)
    val limit: Double,                          // applicable limit
    val loadingPercent: Double,                 // value / limit * 100
    val severity: ViolationSeverity,            // reuses ViolationSeverity from Module 02
)

enum class ViolationType { THERMAL, VOLTAGE_LOW, VOLTAGE_HIGH }
```

---

## API / Interface

| Component | Caller |
|-----------|--------|
| `ContingencyAnalysisService.triggerAsync()` | Game engine (Module 07) — on mutation events and periodically |
| `ContingencyAnalysisService.latestResult()` | Alert system (Module 08), game state stream (Module 10) |
| `ContingencyAnalysisService.buildN1Contingencies()` | Game engine on session start and after topology changes |
| `ContingencyAnalysisResult.criticalContingencies` | Alert system — raised as high-priority alarms |

---

## Implementation Notes

### Trigger conditions

The game engine triggers a new contingency analysis run when:
1. A `NetworkMutation` changes topology (line trip, transformer switching, generator connection/disconnection)
2. Every `N` game ticks during free play (configurable; default: every 6 ticks = 1 hour grid-time)
3. Player manually requests it via the "Run Security Analysis" UI button
4. On session load (initial analysis of the restored network)

Runs are **debounced** — if a trigger fires while a run is already in progress,
the new run is queued and starts immediately after the current one completes.
At most one queued run is kept (newer replaces older).

### DC pre-screening detail

```
1. For each contingency C:
   a. Run DC power flow on (network + C tripped)
   b. Check all branch flows against DC thermal limits
2. Contingencies with no DC violations → mark SECURE (skip AC)
3. Remaining contingencies → run full AC power flow
4. Union of AC results forms the final ContingencyAnalysisResult
```

The `preScreenedContingenciesCount` and `fullAcContingenciesCount` counts in `ContingencyAnalysisResult`
expose this split for monitoring and tutorial display
("X of Y contingencies required full AC analysis").

### Post-contingency limit factor

Real-world practice allows higher branch loading post-contingency (e.g. 110%
for short durations, 120% for emergency). `postContingencyRatingMultiplier` is
named from the perspective of the limit check: a value of `1.1` means
violations are only flagged at 1/1.1 ≈ 91% of the post-contingency allowed
rating, equivalent to applying a 110% emergency rating. The game exposes this
as a configurable operational parameter (tutorial: teach N-1 with different
emergency rating assumptions).

---

## Design Decisions & Rationale

1. **Asynchronous execution, not per-tick.**
   For a 500-bus network, N-1 analysis over ~500 contingencies could take
   10–60 seconds. Running this every tick (1 second real-time) is infeasible.
   Async execution with a cached result allows the game to always have a
   recent N-1 picture without stalling the game clock.
   *Alternative*: run only on player request. Rejected — the game should
   proactively warn the player of emerging N-1 violations as load grows or
   after an outage.

2. **DC pre-screening before full AC.**
   DC pre-screening reduces the AC solve count dramatically with acceptable
   miss rate for thermal violations. AC is still used for all flagged
   contingencies and for voltage violations (which DC cannot detect).
   *Alternative*: AC-only. Rejected for large networks — too slow.
   *Alternative*: DC-only. Rejected — misses voltage violations entirely;
   inconsistent with the AC-first philosophy from Module 02.

3. **`postContingencyRatingMultiplier` parameter.**
   Post-contingency emergency ratings are a real operational concept. Exposing
   this in the API makes it a learnable game parameter (tutorial mission 5 will
   demonstrate the difference between N-1 security with normal vs emergency
   ratings). Default = 1.0 (normal ratings apply post-contingency — conservative).

4. **`criticalContingencies` list in the result.**
   The alert system needs a fast path to the most important contingencies
   without scanning the full result list. The `criticalContingencies` field
   is a pre-filtered list of IDs with at least one `CRITICAL` violation,
   computed once during result construction.

5. **`baseCaseSecure` flag.**
   If the pre-contingency base case already has violations (from Module 02),
   N-1 results should be interpreted in that context. The `baseCaseSecure`
   flag lets the UI and alert system clearly distinguish "already insecure
   before any contingency" from "secure but vulnerable to outages".

---

## Error Handling

| Failure | Handling |
|---------|----------|
| PowSyBl throws during a contingency AC solve | Mark that `ContingencyResult.status = NETWORK_FAILURE`; continue remaining contingencies |
| PowSyBl throws during DC pre-screen | Escalate the affected contingency to full AC; log warning |
| Background coroutine cancelled (session end) | Discard in-progress result; clear cache on next session load |
| `triggerAsync` called while previous run in progress | Queue one replacement run; discard the queued run if another arrives |

---

## Testing Strategy

**Unit tests** (`@Tag("unit")`):
- `ContingencyBuilderTest`: given a 14-bus `GridNetwork`, assert `buildN1Contingencies`
  produces correct count (one per line + transformer + generator)
- `ContingencyResultTest`: given mock violations, assert `worstViolationSeverity`
  and `criticalContingencies` list computed correctly
- Debounce logic test: trigger three rapid calls, assert only one queued run

**Integration tests** (`@Tag("integration")`, real PowSyBl solver):
- IEEE 14-bus N-1: trip each line, assert known critical contingencies match
  published reference results (e.g. line 2–3 loss)
- DC pre-screening validation: run with `dcPreScreening=true` vs `false`,
  assert same violations found, compare `fullAcContingenciesCount` counts
- `postContingencyRatingMultiplier=1.1` test: same contingency produces no
  violation at 1.1 factor but ALARM at 1.0 factor
- `NETWORK_FAILURE` contingency: trip both lines feeding a radial load bus,
  assert `PostContingencyStatus.NETWORK_FAILURE`

**Edge cases:**
- Network with no lines (only transformers) — contingency list contains only transformers
- Contingency that trips the only generator in an island — `NETWORK_FAILURE`
- All contingencies pass DC pre-screen — `fullAcContingenciesCount = 0`

---

## Resolved Design Points (from review)

1. **N-1 run frequency**: fixed at every 6 game ticks (1 hour grid-time).
   Adaptive frequency adds complexity without clear benefit at this stage.
   Agreed.

2. **Contingency UI display**: show violated/critical contingencies only
   (not the full N-1 table) to avoid cluttering the UI. UX design (Module 11)
   will define the panel layout. Agreed.

3. **N-2 scope**: deferred — very low priority. N-2 will be revisited only
   if a specific Challenge scenario requires it. Agreed.

4. **KDoc on public API**: all public interfaces, classes, and functions in
   the implementation will carry KDoc comments (covered by
   `ENGINEERING_PRINCIPLES.md §8`). Noted for implementation PRs.
