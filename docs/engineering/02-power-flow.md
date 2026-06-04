# Power Flow Adapter

**Stage**: 1
**Status**: Draft — awaiting review
**Branch**: `stage/1/power-flow-adapter-design`
**Depends on**: [01-network-model.md](01-network-model.md)

---

## Purpose

This module wraps PowSyBl's `LoadFlow` API to execute AC (and optionally DC)
power flow on the IIDM `Network` object each game tick. It produces a
`PowerFlowResult` containing convergence status, updated bus voltages, branch
currents, and a list of network violations (voltage and thermal) that feed the
game engine's alert system.

The game engine calls this module once per tick, immediately after applying any
`NetworkMutation`s for that tick. The result drives the updated `GridNetwork`
snapshot sent to the frontend.

---

## Scope

**In scope**
- `PowerFlowService` interface and PowSyBl implementation
- `PowerFlowParameters` configuration (AC/DC mode, slack, distributed slack)
- `PowerFlowResult` and its sub-types
- `NetworkViolation` detection: voltage violations and thermal violations
- DC fallback when AC does not converge
- Solve-time measurement (for tick budget monitoring)

**Out of scope**
- Contingency analysis (Module 03) — runs N-1 in the background separately
- OPF / economic dispatch (Module 04)
- Frequency/dynamic simulation (used in challenge mode, separate module)
- Reactive power limits enforcement (handled implicitly by PowSyBl solver)

---

## Key Concepts

### PowSyBl LoadFlow API

PowSyBl's `LoadFlow.run(network, parameters)` mutates the `Network` object
in-place, writing computed voltages and flows back onto IIDM terminals. The
call returns a `LoadFlowResult` with per-connected-component convergence
status. After the call, bus voltages and branch flows are read back out via
the `IidmNetworkMapper` to produce the updated `GridNetwork` snapshot.

```
LoadFlow.run(network, params)
        │
        ├─ writes V, θ onto each Bus
        ├─ writes P, Q, I onto each Branch terminal
        └─ returns LoadFlowResult { componentResults: List<ComponentResult> }
```

### Solve modes

| Mode | Use | Accuracy |
|------|-----|----------|
| AC | Normal operation — every tick | Full AC physics (V, θ, P, Q, I) |
| DC | Fallback on AC divergence; also used for fast contingency pre-screening | Linear approximation (P, θ only; Q and V not solved) |

DC results are clearly flagged in `PowerFlowResult.solveMode` so the frontend
can indicate reduced fidelity to the player.

### Violations

After each solve, the module scans the updated network for:
- **Voltage violations**: bus voltage magnitude outside `[vMinPu, vMaxPu]`
  operational limits defined per voltage level
- **Thermal violations**: branch current exceeding its `ratingA`
  (for `Line`) or `ratingMva`-derived limit (for transformers)

Violations are returned in `PowerFlowResult.violations` and consumed by the
game engine's alert system (Module 08).

---

## Domain Model

```kotlin
interface PowerFlowService {
    fun solve(network: Network, parameters: PowerFlowParameters): PowerFlowResult
}

data class PowerFlowParameters(
    val mode: SolveMode = SolveMode.AC,
    val dcFallback: Boolean = true,       // retry with DC if AC diverges
    val distributedSlack: Boolean = true, // distribute active power imbalance
    val balanceType: BalanceType = BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX,
)

enum class SolveMode { AC, DC }

enum class BalanceType {
    PROPORTIONAL_TO_GENERATION_P_MAX,
    PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN,
    PROPORTIONAL_TO_LOAD,
}

data class PowerFlowResult(
    val status: ConvergenceStatus,
    val solveMode: SolveMode,         // AC or DC (if fallback was triggered)
    val iterationCount: Int,          // Newton-Raphson iterations (0 for DC)
    val snapshot: GridNetwork,        // updated with voltages and currents
    val violations: List<NetworkViolation>,
    val solveTimeMs: Long,
)

enum class ConvergenceStatus {
    CONVERGED,           // all connected components converged
    PARTIAL,             // some components converged, others did not
    DIVERGED,            // no components converged (DC fallback result if enabled)
    FAILED,              // PowSyBl threw an exception; network state undefined
}
```

### NetworkViolation

```kotlin
sealed class NetworkViolation {

    data class VoltageViolation(
        val busId: String,
        val voltagePu: Double,
        val limitMinPu: Double,
        val limitMaxPu: Double,
        val severity: ViolationSeverity,
    ) : NetworkViolation()

    data class ThermalViolation(
        val equipmentId: String,
        val equipmentType: EquipmentType,
        val currentA: Double,
        val ratingA: Double,
        val loadingPercent: Double,      // currentA / ratingA * 100
        val severity: ViolationSeverity,
    ) : NetworkViolation()
}

enum class EquipmentType { LINE, TWO_WINDINGS_TRANSFORMER, THREE_WINDINGS_TRANSFORMER }

enum class ViolationSeverity {
    WARNING,   // 90–100 % of limit
    ALARM,     // 100–110 % of limit
    CRITICAL,  // > 110 % of limit
}
```

`ViolationSeverity` thresholds are configurable in `application.yml` under
`gridmaster.violations.*`.

---

## API / Interface

| Component | Caller |
|-----------|--------|
| `PowerFlowService.solve()` | Game engine (Module 07) — called once per tick after mutations |
| `PowerFlowResult.snapshot` | `IidmNetworkMapper` — returned as the tick's `GridNetwork` |
| `PowerFlowResult.violations` | Alert system (Module 08) |
| `PowerFlowResult.solveTimeMs` | Game clock (Module 07) — for tick budget monitoring |

The PowSyBl `LoadFlow` implementation is injected as a Spring `@Service`.
`PowerFlowService` is the only interface exposed outside the `engine` package.

---

## Implementation Notes

### Solve sequence per tick

```
1. Apply NetworkMutations (Module 09) → mutated Network
2. PowerFlowService.solve(network, parameters)
   a. Run AC LoadFlow via PowSyBl
   b. If DIVERGED and dcFallback=true → run DC LoadFlow
   c. Extract GridNetwork snapshot via IidmNetworkMapper
   d. Scan violations
   e. Return PowerFlowResult
3. Persist snapshot (NetworkRepository)
4. Broadcast to WebSocket clients
```

### Slack bus

PowSyBl's open-loadflow selects the slack bus automatically based on the
generator with voltage regulation enabled and highest `maxActivePowerMw`.
The slack bus ID is captured from `LoadFlowResult.componentResults` and
included in the `GridNetwork` snapshot's `slackBusId` field (added to
`GridNetwork` in the implementation PR).

### Three-winding transformer currents

PowSyBl exposes three terminals on a `ThreeWindingsTransformer`, each with
independent `I` values. All three are extracted and mapped to
`current1A / current2A / current3A`. The thermal check is applied
independently to each leg against its respective MVA rating converted to
Amperes at the leg's nominal voltage.

---

## Design Decisions & Rationale

1. **AC first, DC fallback.**
   AC power flow gives full fidelity (voltages, reactive power, losses). DC
   is fast and always converges but gives only active power and angles.
   Running AC first preserves game realism; DC fallback prevents a diverged
   grid from crashing the game tick. The UI clearly indicates DC mode so the
   player knows the results are approximate.
   *Alternative*: always DC for speed. Rejected — the game's educational
   value depends on accurate voltage behaviour.

2. **In-place mutation of the IIDM Network, then snapshot.**
   PowSyBl's `LoadFlow.run` mutates the `Network` in-place by design.
   We accept this and immediately take an immutable snapshot after the solve.
   The IIDM `Network` is owned exclusively by the game engine thread and
   never shared concurrently.

3. **`ConvergenceStatus.PARTIAL` for multi-component results.**
   Large networks can have isolated buses or islands (e.g. after a line trip).
   PowSyBl reports per-component convergence. We surface `PARTIAL` to let the
   alert system warn the player about islanded sections without treating the
   whole solve as failed.

4. **Three severity tiers for violations (WARNING / ALARM / CRITICAL).**
   Maps to real power system practice (90/100/110 % thresholds). The UI
   renders lines with different colours per severity; the alert system uses
   severity to determine notification priority. Thresholds are configurable
   to allow scenario-specific tuning (e.g. tighter limits in a challenge
   scenario).

5. **`solveTimeMs` in the result.**
   The game clock uses this to detect if a tick is running over budget and
   should slow the simulation speed. Exposing it from this module makes the
   monitoring point explicit rather than measured externally.

---

## Error Handling

| Failure | Handling |
|---------|----------|
| AC diverges, `dcFallback=true` | Retry with DC; `status = CONVERGED` (or `PARTIAL`), `solveMode = DC`, violations computed from DC flows |
| AC diverges, `dcFallback=false` | `status = DIVERGED`; snapshot contains pre-solve voltage/current values (stale) |
| PowSyBl throws exception | Catch, log stack trace, return `status = FAILED`; game engine pauses clock and raises critical alert |
| Voltage limits not defined for a voltage level | Skip voltage violation check for that level; log warning once |

---

## Testing Strategy

**Unit tests** (`@Tag("unit")`):
- `ViolationScannerTest`: given a mocked `GridNetwork` with known bus voltages
  and branch currents, assert correct `NetworkViolation` list and severities
- `PowerFlowParametersTest`: verify default values and that DC fallback flag
  propagates correctly to PowSyBl parameters

**Integration tests** (`@Tag("integration")`, real PowSyBl solver):
- IEEE 14-bus: assert AC solve converges, all bus voltages in range,
  known line flows within 1% of published reference values
- IEEE 39-bus: assert AC solve converges within tick budget (< 3 s)
- Divergence test: remove all generators except one, force a load imbalance,
  assert DC fallback activates and `solveMode = DC` is returned
- Thermal violation test: set a line's flow above its rating, assert
  `ThermalViolation` with correct `loadingPercent` and `severity = ALARM`
- Voltage violation test: set a generator's `targetVoltagePu` to an extreme
  value, assert `VoltageViolation` detected after solve

**Edge cases to cover:**
- Network with a single islanded bus after a line trip → `PARTIAL` status
- Three-winding transformer thermal check on all three legs independently
- DC solve result: verify Q and bus voltage magnitude are not reported
  (they are undefined under DC assumptions)

---

## Open Questions

1. **Voltage limits source**: PowSyBl `VoltageLevel` has `lowVoltageLimit` and
   `highVoltageLimit` fields. These are often absent in IEEE test networks.
   Should we define default per-voltage-level limits in `application.yml`
   (e.g. ±5% of nominal), or skip voltage violation checks when limits are
   absent? Proposed: configurable defaults in `application.yml`, override-able
   per network via sidecar metadata (consistent with the FuelType sidecar from
   Module 01).

2. **Slack bus annotation in GridNetwork**: the slack bus identity is useful
   for the tutorial (highlight it for the player) and for debugging. Propose
   adding `slackBusId: String?` to `GridNetwork`. Needs to be confirmed in
   the implementation PR since it touches Module 01's data class.
