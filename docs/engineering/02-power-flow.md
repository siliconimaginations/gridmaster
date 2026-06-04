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
| DC | Explicit pre-screening only (e.g. contingency analysis in Module 03); never auto-triggered | Linear approximation (P, θ only; Q and V not solved) |

**DC is not used as an automatic fallback.** When AC diverges the network is
marked as failed (`ConvergenceStatus.NETWORK_FAILURE`). Silently falling back
to DC would produce results inconsistent with the AC model, misleading the
player and the alert system. DC mode remains available explicitly via
`mode = SolveMode.DC` (e.g. for fast contingency pre-screening in Module 03).

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
    val distributedSlack: Boolean = true, // distribute active power imbalance across participating generators
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
    val solveMode: SolveMode,         // always reflects the mode in PowerFlowParameters
    val iterationCount: Int,          // Newton-Raphson iterations (0 for DC)
    val snapshot: GridNetwork,        // updated with voltages and currents
    val slackBusIds: List<String>,    // buses acting as slack (>1 when distributedSlack=true)
    val violations: List<NetworkViolation>,
    val solveTimeMs: Long,
)

enum class ConvergenceStatus {
    CONVERGED,          // all connected components converged
    PARTIAL,            // some components converged; others islanded/failed
    NETWORK_FAILURE,    // AC solve did not converge — grid failure event raised
    FAILED,             // PowSyBl threw an unexpected exception; state undefined
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
   b. If NETWORK_FAILURE → raise grid failure event; skip steps c–e; return stale snapshot
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

1. **AC only in normal operation; no automatic DC fallback.**
   AC power flow gives full fidelity (voltages, reactive power, losses).
   When AC diverges, the network is declared failed and the game raises a
   grid failure event — this is physically correct (a non-converging grid
   means a collapse is occurring). Silently switching to DC would produce
   results inconsistent with the AC model and mislead the player.
   DC mode remains explicitly available for Module 03 contingency
   pre-screening, where approximate results are acceptable.
   *Alternative considered*: DC fallback by default. Rejected — results
   not consistent with AC model; undesirable in an educational simulation.

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

5. **Distributed slack with multiple slack buses.**
   When `distributedSlack = true`, PowSyBl distributes the active power
   imbalance across multiple generators per `balanceType`. All participating
   buses are tracked in `slackBusIds` (from `LoadFlowResult.componentResults`).
   This is surfaced in the UI to show the player which generators are
   balancing the system — an important operational concept for the tutorial.

6. **`solveTimeMs` in the result.**
   The game clock uses this to detect if a tick is running over budget and
   should slow the simulation speed. Exposing it from this module makes the
   monitoring point explicit rather than measured externally.

---

## Error Handling

| Failure | Handling |
|---------|----------|
| AC solve does not converge | `status = NETWORK_FAILURE`; snapshot contains last-known values (stale, clearly flagged); game engine raises a grid failure event; clock pauses for player intervention |
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

## Resolved Design Points (from review)

1. **Voltage limits defaults**: configurable in `application.yml` (e.g. +/-5%
   of nominal), override-able per network via sidecar metadata. Agreed.

2. **Slack bus tracking**: `slackBusIds: List<String>` added to
   `PowerFlowResult`; `GridNetwork` will gain the same field in the
   implementation PR (minor addition to Module 01 data class). Supports
   distributed slack with multiple participating buses. Agreed.

3. **No DC fallback**: AC non-convergence raises `NETWORK_FAILURE` rather
   than falling back to DC. DC remains available explicitly for Module 03.
   Agreed.
