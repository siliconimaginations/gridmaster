# Economic Dispatch and Unit Commitment

**Stage**: 1
**Status**: Draft — v2, addressing review comments
**Branch**: `stage/1/dispatch-design`
**Depends on**: [01-network-model.md](01-network-model.md), [02-power-flow.md](02-power-flow.md), [03-contingency-analysis.md](03-contingency-analysis.md)

---

## Purpose

This module implements two related but distinct optimisation problems:

- **Economic Dispatch (ED)**: given the set of currently committed generators,
  find the minimum-cost active power output for each that meets total system
  load, respects generator limits, and — after a power flow solve — relieves
  any transmission congestion via sensitivity-based redispatch.

- **Unit Commitment (UC)**: given a load forecast for the next 24 hours,
  decide which generators to commit (start up) or decommit (shut down) to
  meet forecast demand plus a reserve margin at minimum total cost, subject
  to minimum up/down time constraints.

Both are player-facing operations: the player can run the optimiser as an
assistant, accept or override its recommendations, and observe the cost and
security consequences. This is central to Tutorial missions 6 (economic
dispatch) and 7 (unit commitment).

---

## Scope

**In scope**
- `DispatchService`: economic dispatch via merit order + sensitivity-based
  congestion redispatch
- `UnitCommitmentService`: MIP-based UC for the 24-hour day-ahead horizon (OR-Tools + SCIP/CBC)
- `DispatchResult`, `UcResult`, and supporting types
- `LoadForecast`: hourly load forecast consumed by UC
- Reserve margin enforcement
- Integration points with `PowerFlowService` (post-dispatch verification)
  and `ContingencyAnalysisService` (security-constrained dispatch flag)

**Out of scope**
- Full AC OPF (PowSyBl does not ship a production OPF solver; merit order
  + LP redispatch covers the game's educational scope)
- Reactive power optimisation (voltage scheduling) — deferred to Stage 5
  (already listed below)
- Reactive power optimisation (voltage scheduling) — deferred to Stage 5
- Real-time AGC (Automatic Generation Control) — separate module in Stage 5
- Market clearing / locational marginal pricing — Free Play policy layer

---

## Key Concepts

### Merit order dispatch (default)

Generators are sorted by `marginalCostPerMwh` (ascending). Load is served
by dispatching the cheapest available capacity first, up to each generator's
`maxActivePowerMw`, until total load is met. The last dispatched unit (the
marginal unit) sets the system marginal cost.

```
Sort committed generators by marginalCostPerMwh ↑
For each generator in order:
    dispatch = min(remaining_load, maxMw - minMw) + minMw
    remaining_load -= (dispatch - minMw)
    if remaining_load <= 0: break
```

Generators at minimum loading (`minActivePowerMw`) are always dispatched
at minimum before the merit order is applied (must-run constraint).

### LP-based economic dispatch (optimised mode)

As an alternative to merit order, `DispatchMode.LP` solves the dispatch
as a linear programme via OR-Tools, which handles must-run constraints,
ramp limits (if added later), and per-generator cost curves more robustly:

```
Decision variables:
  p[g] ∈ [minMw[g], maxMw[g]]   dispatch MW for each committed generator

Objective (minimise):
  Σ_g marginalCost[g] * p[g]

Constraints:
  Σ_g p[g] = totalLoadMw          (power balance)
  p[g] >= minMw[g] * committed[g] (must-run at minimum)
  p[g] <= maxMw[g] * committed[g] (capacity limit)
```

For unconstrained dispatch, merit order and LP give identical results.
LP mode is useful when additional constraints (ramp limits, cost curves)
are introduced. `DispatchMode` is added to `DispatchParameters`.

### Sensitivity-based congestion redispatch

After merit order dispatch, a power flow solve (Module 02) checks for
thermal violations. If overloads exist, **Generation Shift Keys (GSKs)**
— computed via PowSyBl's `SensitivityAnalysis` — identify how much relief
a 1 MW increase on generator G and corresponding decrease on generator G'
provides on overloaded branch B. The redispatch finds the minimum-cost
pair of generators to shift to relieve each overload.

This teaches the player the core concept that grid constraints (not just
cost) drive real dispatch decisions.

### Unit commitment

The UC problem selects which generators to have online for each hour of
the next 24 hours. This is a **Mixed-Integer Programme (MIP)** solved via
**OR-Tools** with the open-source **SCIP** (or CBC) backend — greedy
algorithms are not guaranteed to find feasible schedules under tight
minimum up/down time and reserve constraints.

**MIP formulation** (24-hour horizon, G generators, T=24 hours):

```
Decision variables:
  x[g,t] ∈ {0,1}   committed in hour t
  p[g,t] ≥ 0       dispatch MW in hour t
  y[g,t] ∈ {0,1}   startup indicator  (y = max(0, x[t] - x[t-1]))
  z[g,t] ∈ {0,1}   shutdown indicator (z = max(0, x[t-1] - x[t]))

Objective (minimise):
  Σ_t Σ_g [ marginalCost[g] * p[g,t] + startupCost[g] * y[g,t] ]

Constraints:
  Σ_g p[g,t] >= forecast[t] * (1 + reserveMargin)   ∀t  (load + reserve)
  p[g,t] >= minMw[g] * x[g,t]                       ∀g,t
  p[g,t] <= maxMw[g] * x[g,t]                       ∀g,t
  Σ_{τ=t}^{t+minUp[g]-1} x[g,τ] >= minUp[g]*y[g,t] ∀g,t  (min up time)
  Σ_{τ=t}^{t+minDown[g]-1}(1-x[g,τ]) >= minDown[g]*z[g,t] ∀g,t  (min down)
  y[g,t] - z[g,t] = x[g,t] - x[g,t-1]              ∀g,t  (transition link)
```

OR-Tools dependency: `com.google.ortools:ortools-java` added to
`build.gradle.kts` in the implementation PR.

### Reserve margin

The reserve margin ensures committed capacity exceeds forecast load by a
configurable percentage (default: 20%). This covers forecast error and
N-1 generator loss. The game teaches the player the cost of holding reserve
vs the risk of under-commitment.

---

## Domain Model

### Economic Dispatch

```kotlin
interface DispatchService {
    /**
     * Run merit order economic dispatch on the current committed generators.
     * Returns optimal target MW per generator and system marginal cost.
     * Does not mutate the network — caller applies via NetworkMutation.
     */
    fun economicDispatch(
        network: Network,
        parameters: DispatchParameters,
    ): DispatchResult

    /**
     * Given an overloaded network (post-dispatch power flow), compute the
     * minimum-cost redispatch to relieve all thermal violations.
     */
    fun congestionRedispatch(
        network: Network,
        violations: List<NetworkViolation>,
        parameters: DispatchParameters,
    ): RedispatchResult
}

data class DispatchParameters(
    val totalLoadMw: Double,             // target active power balance
    val mode: DispatchMode = DispatchMode.MERIT_ORDER,
    val reserveMarginFraction: Double = 0.20,
    val securityConstrained: Boolean = false,
        // if true: run contingency analysis after dispatch and redispatch
        // until N-1 secure or no further improvement possible
)

enum class DispatchMode {
    MERIT_ORDER, // fast, transparent, educational default
    LP,          // OR-Tools LP; identical result for simple cases, more robust with extra constraints
}

data class DispatchResult(
    val generatorTargets: List<GeneratorTarget>,
    val systemMarginalCostPerMwh: Double,
    val totalGenerationMw: Double,
    val totalCostPerHour: Double,
    val unservedLoadMw: Double,          // > 0 if committed capacity insufficient
    val meritOrder: List<String>,        // generator IDs in dispatch order
)

data class GeneratorTarget(
    val generatorId: String,
    val targetActivePowerMw: Double,
    val marginalCostPerMwh: Double,
    val atMinimum: Boolean,
    val atMaximum: Boolean,
)

data class RedispatchResult(
    val upwardGeneratorId: String,       // generator increased
    val downwardGeneratorId: String,     // generator decreased
    val shiftMw: Double,
    val relievedViolations: List<String>, // equipment IDs no longer overloaded
    val remainingViolations: List<String>,
    val additionalCostPerHour: Double,   // cost of redispatch vs merit order
)
```

### Unit Commitment

```kotlin
interface UnitCommitmentService {
    /**
     * Compute a 24-hour commitment schedule for all available generators.
     * Returns the recommended on/off schedule and associated startup actions.
     */
    fun computeSchedule(
        network: Network,
        forecast: LoadForecast,
        parameters: UcParameters,
    ): UcResult
}

data class LoadForecast(
    val hourlyLoadMw: List<Double>,      // 24 values, index = hour offset from now
    val peakLoadMw: Double = hourlyLoadMw.max(),
)

data class UcParameters(
    val reserveMarginFraction: Double = 0.20,
    val includeStartupCosts: Boolean = true,
)

data class UcResult(
    val schedule: List<GeneratorSchedule>,
    val hourlySystemCost: List<Double>,  // total dispatch cost per hour
    val totalDailyStartupCost: Double,
    val totalDailyDispatchCost: Double,
    val peakReserveMarginFraction: Double, // actual reserve at peak hour
    val commitmentActions: List<CommitmentAction>,
)

data class GeneratorSchedule(
    val generatorId: String,
    val fuelType: FuelType,
    val committedHours: List<Int>,       // hours [0..23] this generator is online
    val hourlyTargetMw: List<Double?>,   // null when decommitted
)

data class CommitmentAction(
    val generatorId: String,
    val action: CommitmentActionType,
    val atHour: Int,
    val startupCost: Double,
)

enum class CommitmentActionType { COMMIT, DECOMMIT }
```

---

## API / Interface

| Component | Caller |
|-----------|--------|
| `DispatchService.economicDispatch()` | Game engine (Module 07) — on player request or auto-dispatch |
| `DispatchService.congestionRedispatch()` | Game engine — after power flow detects overloads |
| `UnitCommitmentService.computeSchedule()` | Game engine — on day-ahead planning trigger |
| `DispatchResult.generatorTargets` | Converted to `SetGeneratorOutput` mutations via Module 09 |
| `UcResult.commitmentActions` | Converted to `CommitGenerator` / `DecommitGenerator` mutations |

---

## Implementation Notes

### Sensitivity analysis for redispatch

PowSyBl's `SensitivityAnalysis` computes the linearised sensitivity of branch
flow to generator injections (Generation Shift Keys):

```
GSK[branch B, generator G] = dFlow(B) / dInjection(G)   [MW/MW]
```

For a thermal violation on branch B (loading > ratingA):
1. Find all generator pairs (G_up, G_down) that can shift generation
2. Score each pair: `relief = GSK[B, G_up] - GSK[B, G_down]`
3. Select the pair with highest relief per additional cost
4. Apply shift until overload is relieved or no further feasible pair exists

This is a simplified version of industry **Security-Constrained Economic
Dispatch (SCED)**, appropriate for the game's educational scope.

### Generator minimum loading

Generators with a physical minimum loading (`minActivePowerMw > 0`) are
must-run at minimum whenever committed. This represents base-load units
(nuclear, some coal) that cannot be ramped to zero. The game teaches this
constraint as a motivation for unit commitment (commitment decisions are
made partly to avoid committing must-run units unnecessarily).

### Minimum up/down times

In the UC MIP formulation, a committed generator cannot be decommitted
before `minUpTimeHours` have passed (and vice versa for `minDownTimeHours`).
These are modelled as fields on `Generator` added in the implementation PR
(small addition to Module 01 domain model).

### Security-constrained dispatch

When `DispatchParameters.securityConstrained = true`, the game engine:
1. Runs economic dispatch
2. Applies `GeneratorTarget` as `NetworkMutation`s
3. Runs power flow (Module 02)
4. If thermal violations exist, runs `congestionRedispatch`
5. Repeats steps 2–4 until no violations or iteration limit reached
6. Optionally runs N-1 contingency analysis (Module 03) and checks for
   post-contingency violations

This teaches the player how security constraints drive up dispatch cost
compared to pure economic merit order.

---

## Design Decisions & Rationale

1. **Merit order rather than full AC OPF.**
   Merit order dispatch is the foundational concept taught in Tutorial mission 6.
   It is simple, transparent (the player can see the merit order table), and
   closely mirrors real-world practice for the operational timescale.
   Full AC OPF (minimise cost subject to power flow equations) is more accurate
   but opaque and requires a solver PowSyBl does not include. The combination
   of merit order + sensitivity redispatch covers the congestion concept
   without a full OPF.
   *Alternative*: DC OPF. Rejected — DC OPF shares the opacity of AC OPF
   while losing voltage information; sensitivity redispatch is more
   pedagogically transparent.

2. **MIP for UC via OR-Tools (SCIP/CBC).**
   A greedy algorithm is not guaranteed to find a feasible UC schedule when
   minimum up/down time constraints are tight. OR-Tools with SCIP or CBC
   solves the MIP to optimality for a 24-hour, <100 generator problem in
   seconds — well within the game's day-ahead planning timescale.
   The MIP formulation is also transparent enough to show the player
   (commitment schedule table, startup costs, marginal units per hour).
   *Alternative*: greedy. Rejected as the long-term approach — not
   guaranteed feasible; educational value is higher when the solution is
   known to be optimal. **Implementation note**: OR-Tools MIP is deferred
   to issue #28. The current implementation uses a greedy heuristic
   (`GreedyUnitCommitmentService`) which is sufficient for small tutorial
   networks (<20 generators). LP dispatch similarly falls back to merit-order
   until the OR-Tools dependency is integrated.

3. **Redispatch as a separate call from economic dispatch.**
   Separating `economicDispatch` from `congestionRedispatch` makes the
   two-step process explicit to both the player (they see pure merit order
   first, then congestion cost on top) and the code (clear responsibility
   boundary). Security-constrained dispatch is the composition of both.

4. **`unservedLoadMw` in `DispatchResult`.**
   If total committed capacity is insufficient to meet load, the shortfall
   is reported explicitly rather than silently allowing an infeasible dispatch.
   The game engine uses this to trigger a load-shedding event (a significant
   game mechanic and learning moment).

5. **`minUpTimeHours` / `minDownTimeHours` on `Generator`.**
   These fields are added to the Module 01 domain model in the implementation
   PR. They are essential for UC to be realistic and educational (teaching
   why base-load units cannot be rapidly cycled).

---

## Error Handling

| Failure | Handling |
|---------|----------|
| Load exceeds total committed capacity | `unservedLoadMw > 0`; game engine triggers load-shedding alert |
| No feasible redispatch pair found for overload | `remainingViolations` non-empty; game engine raises persistent overload alert |
| Sensitivity analysis throws | Log and skip redispatch; return base merit order result with a warning flag |
| UC forecast has fewer than 24 values | Throw `IllegalArgumentException` at service boundary |

---

## Testing Strategy

**Unit tests** (`@Tag("unit")`):
- `MeritOrderTest`: given 5 generators with known costs and limits, assert
  correct dispatch targets and system marginal cost for a given load
- `MeritOrderTest`: assert must-run (minMw > 0) generators always dispatched
  at minimum before merit order applied
- `UcGreedyTest`: given a 24-hour sine-wave forecast, assert commitment
  schedule covers peak + 20% reserve at every hour
- `UcGreedyTest`: assert minimum up/down time constraints enforced
- `RedispatchTest`: given mocked GSK values, assert correct generator pair
  selected and shift magnitude computed

**Integration tests** (`@Tag("integration")`, real PowSyBl solver):
- IEEE 14-bus economic dispatch: dispatch 5 generators to meet 259 MW total
  load; assert total cost minimised and all generator limits respected
- Congestion redispatch: set a line rating artificially low, run dispatch,
  assert overload is relieved and additional cost reported
- Security-constrained dispatch: assert N-1 secure result after SCED loop
- UC 24-hour: assert schedule meets reserve margin at peak hour and respects
  startup costs

**Edge cases:**
- Single generator network (dispatch trivial; must meet load exactly)
- Load below sum of all `minActivePowerMw` (must-run exceeds demand — spill)
- All generators at maximum (system short of load)
- Redispatch with no feasible pair (all generators at limits)

---

## Resolved Design Points (from review)

1. **Generator additions**: `startupCostGbp`, `minUpTimeHours`,
   `minDownTimeHours` added to Module 01 `Generator` in the implementation
   PR. Agreed.

2. **Merit order table in UI**: `meritOrder` list in `DispatchResult`
   confirmed as needed for the player-facing dispatch panel. OK.

3. **Reserve margin default**: 20% as a simple global `application.yml`
   setting. Agreed.

4. **OR-Tools for UC and LP dispatch**: MIP (SCIP/CBC via OR-Tools) for
   unit commitment; LP mode as an option for economic dispatch. Agreed as
   the target design. **Current implementation status**: OR-Tools
   integration is deferred (tracked in issue #28). The shipped
   implementation uses `GreedyUnitCommitmentService` for UC and
   `MeritOrderDispatchService` for economic dispatch. The `DispatchMode.LP`
   path exists as a stub and will be wired to OR-Tools in issue #28.
