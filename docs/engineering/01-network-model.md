# Network Model

**Stage**: 1
**Status**: Draft — v2, addressing review comments
**Branch**: `stage/1/network-model-design`

---

## Purpose

This module defines how GridMaster represents a power network internally and
how it wraps PowSyBl's IIDM (Internal Interconnected Data Model) data
structures into domain objects that the rest of the game engine can use
without importing PowSyBl types directly.

PowSyBl IIDM is the canonical in-memory representation for the physics solver.
GridMaster's domain model is a stable, serialisable view of that same network
— used by the game engine, the WebSocket state stream, and the frontend renderer.
The two representations co-exist on the server; the frontend only ever sees the
GridMaster domain model.

---

## Scope

**In scope**
- Domain entities: `GridNetwork`, `Bus`, `Line`, `TwoWindingsTransformer`,
  `ThreeWindingsTransformer`, `Generator`, `Load`, `ShuntCompensator`
- Mapping layer: converting a PowSyBl `Network` object into GridMaster entities
  and back
- `NetworkRepository`: persistence of network snapshots (per game session)
- IEEE 14-bus and IEEE 39-bus seed network loaders (used by tests and tutorial
  mode)
- XIIDM file loader (PowSyBl's XML-based network format)

**Out of scope**
- Power flow execution (Module 02)
- Contingency analysis (Module 03)
- Dispatch / OPF (Module 04)
- REST/WebSocket API exposure (Module 05)
- Any game-mode-specific network mutation (handled by the game engine modules)

---

## Key Concepts / Domain Model

### PowSyBl IIDM concepts (internal only)

| IIDM type | Role |
|-----------|------|
| `Network` | Top-level container; holds all equipment |
| `Substation` | Groups voltage levels by geographic location |
| `VoltageLevel` | A section of the network at a given nominal voltage |
| `BusbarSection` | Physical bus in a busbar-topology voltage level |
| `Line` | AC transmission line between two voltage levels |
| `TwoWindingsTransformer` | Transformer between two voltage levels in the same substation |
| `ThreeWindingsTransformer` | Three-winding transformer connecting three voltage levels |
| `ShuntCompensator` | Shunt element providing reactive power compensation (capacitor or reactor bank) |
| `Generator` | Active/reactive power source |
| `Load` | Active/reactive power sink |

### GridMaster domain entities

These are Kotlin data classes that the game engine and API layer work with.
They are immutable snapshots — created fresh each tick from the live IIDM
`Network` object.

```kotlin
data class GridNetwork(
    val id: String,
    val name: String,
    val buses: List<Bus>,
    val lines: List<Line>,
    val twoWindingsTransformers: List<TwoWindingsTransformer>,
    val threeWindingsTransformers: List<ThreeWindingsTransformer>,
    val generators: List<Generator>,
    val loads: List<Load>,
    val shuntCompensators: List<ShuntCompensator>,
    val regions: List<Region>,          // annotation layer; network stays fully connected
    val snapshotAt: Instant,
)

/** Logical region — an annotation on a set of buses, not a physical split. */
data class Region(
    val id: String,
    val name: String,
    val busIds: Set<String>,
)

data class Bus(
    val id: String,
    val name: String,
    val nominalVoltageKv: Double,
    val voltageMagnitudePu: Double?,    // null before first power flow
    val voltageAngleDeg: Double?,
    val regionId: String?,              // which Region this bus belongs to, if any
)

data class Line(
    val id: String,
    val name: String,
    val fromBusId: String,
    val toBusId: String,
    val ratingA: Double,                // thermal current rating (Amperes)
    val currentFromA: Double?,          // null before first power flow
    val currentToA: Double?,
    val resistanceOhm: Double,
    val reactanceOhm: Double,
    val shuntCapacitanceSiemens: Double, // line charging susceptance
)

data class TwoWindingsTransformer(
    val id: String,
    val name: String,
    val fromBusId: String,              // HV side
    val toBusId: String,                // LV side
    val ratingMva: Double,
    val currentFromA: Double?,          // null before first power flow
    val currentToA: Double?,
    val resistanceOhm: Double,
    val reactanceOhm: Double,
    // Shunt resistance/reactance (magnetising branch) noted but excluded for now
    val ratioTapPosition: Int,
    val nominalVoltageHvKv: Double,
    val nominalVoltageLvKv: Double,
)

data class ThreeWindingsTransformer(
    val id: String,
    val name: String,
    val bus1Id: String,                 // HV winding
    val bus2Id: String,                 // MV winding
    val bus3Id: String,                 // LV winding
    val ratingMva1: Double,
    val ratingMva2: Double,
    val ratingMva3: Double,
    val current1A: Double?,             // null before first power flow
    val current2A: Double?,
    val current3A: Double?,
    val resistanceOhm1: Double,         // series resistance, HV leg
    val reactanceOhm1: Double,
    val resistanceOhm2: Double,         // series resistance, MV leg
    val reactanceOhm2: Double,
    val resistanceOhm3: Double,         // series resistance, LV leg
    val reactanceOhm3: Double,
)

data class Generator(
    val id: String,
    val name: String,
    val busId: String,
    val minActivePowerMw: Double,
    val maxActivePowerMw: Double,
    val targetActivePowerMw: Double,
    val targetReactivePowerMvar: Double,
    val targetVoltagePu: Double,        // voltage setpoint at the terminal bus
    val connected: Boolean,
    val fuelType: FuelType,
    val marginalCostPerMwh: Double,
)

enum class FuelType { COAL, GAS, NUCLEAR, HYDRO, WIND, SOLAR, OIL, OTHER }

data class Load(
    val id: String,
    val name: String,
    val busId: String,
    val activePowerMw: Double,
    val reactivePowerMvar: Double,
    val connected: Boolean,
)

data class ShuntCompensator(
    val id: String,
    val name: String,
    val busId: String,
    val susceptanceSiemensPerSection: Double,
    val maximumSectionCount: Int,
    val currentSectionCount: Int,
    val connected: Boolean,
)
```

### Mapping layer

The `IidmNetworkMapper` converts between PowSyBl's `Network` and
`GridNetwork`. It runs after every power flow solve so that the snapshot
reflects the latest computed state.

```kotlin
interface IidmNetworkMapper {
    fun toGridNetwork(network: Network): GridNetwork
    fun applyMutation(network: Network, mutation: NetworkMutation): Network
}
```

`NetworkMutation` is a sealed class representing player or event actions that
mutate the network (e.g. `SetGeneratorOutput`, `TripLine`, `ConnectLoad`,
`SetTapPosition`). Mutations are applied to the IIDM `Network` directly;
the updated `Network` is then solved and snapshotted.

### NetworkRepository

Persists `GridNetwork` snapshots and the raw IIDM `Network` XML per session.
The IIDM XML is the source of truth for resuming a session; the domain
snapshot is a derived view used for fast reads.

```kotlin
interface NetworkRepository {
    fun save(sessionId: String, network: Network)
    fun loadIidm(sessionId: String): Network
    fun latestSnapshot(sessionId: String): GridNetwork?
}
```

Storage: IIDM XML serialised to a `TEXT` column in SQLite via PowSyBl's
built-in `NetworkSerDe`. Snapshots stored as JSON (Jackson).

---

## API / Interface

This module exposes no REST endpoints directly — that is Module 05's
responsibility. The public boundary is:

| Interface | Used by |
|-----------|---------|
| `IidmNetworkMapper` | Modules 02, 03, 04 (power flow, contingency, dispatch) |
| `NetworkRepository` | Module 06 (game session) |
| `GridNetwork` and sub-types | All modules; WebSocket state stream; frontend |
| `NetworkMutation` | Module 09 (command handler) |

---

## Design Decisions & Rationale

1. **Immutable domain snapshots rather than live-binding to IIDM objects.**
   IIDM objects are mutable and solver-owned. Exposing them across module
   boundaries would couple the game engine to PowSyBl internals. Immutable
   snapshots are safe to share, serialise, and diff between ticks.
   *Alternative considered*: expose IIDM objects directly with defensive
   copies. Rejected — coupling risk outweighs the copy cost at ≤1000 buses.

2. **`Line`, `TwoWindingsTransformer`, and `ThreeWindingsTransformer` as
   separate flat classes rather than a sealed hierarchy.**
   Each type has a meaningfully different structure — especially
   `ThreeWindingsTransformer` with three terminal buses and three sets of
   ratings/currents. A shared sealed supertype would require either an
   awkward common interface or nullable fields. Separate classes keep each
   type self-contained and easy to render and operate on independently.

3. **Current (Amperes) rather than active power (MW) for branch flow.**
   Thermal loading — the primary operational constraint on lines and
   transformers — is determined by current, not active power. Using current
   directly maps to how operators assess line loading percentage
   (`currentFromA / ratingA × 100`). Active power is derivable from current
   and voltage for display purposes.

4. **Region as an annotation layer on a unified network.**
   The grid is always a single fully-connected IIDM `Network`. Regions
   are sets of bus IDs tagged with a region name — they carry no topological
   meaning to the solver. This allows the Free Play map to show geographic
   regions and track unlock status without splitting the IIDM model or
   maintaining multiple solver instances. A bus may belong to at most one
   region; transmission ties between regions are ordinary lines.

5. **IIDM XML as the session persistence format.**
   PowSyBl provides `NetworkSerDe` for round-trip XML serialisation at no
   extra cost. It preserves all solver parameters and topology exactly.
   *Alternative*: custom JSON schema. Rejected — would require re-implementing
   PowSyBl's serialisation logic and risk divergence on edge-case equipment.

6. **`FuelType` enum and `marginalCostPerMwh` on `Generator`.**
   Fuel type drives game mechanics (merit order, policy events, environmental
   scoring) and the renderer (icon, colour). Cost drives economic dispatch.
   Keeping both on the generator entity avoids a separate market model at
   this scope.

7. **`targetVoltagePu` on `Generator`.**
   Generators participate in voltage regulation by holding their terminal
   bus voltage at a setpoint. This field is essential for the power flow
   solver (PQ vs PV bus classification) and will be exposed in the dispatch
   UI as a voltage setpoint control.

---

## Error Handling

| Failure | Handling |
|---------|----------|
| IIDM file missing or corrupt on session load | Throw `NetworkLoadException`; game engine surfaces to player as "session cannot be restored" |
| Mapper encounters unknown equipment type | Log warning, skip element, include in `GridNetwork.warnings` list |
| `applyMutation` results in topologically invalid network | Return `Result.failure(InvalidMutationException)`; network unchanged |
| SQLite write failure during `save` | Propagate `IOException`; game clock pauses, alert raised |

---

## Testing Strategy

**Unit tests** (`@Tag("unit")`, no PowSyBl solver invoked):
- `IidmNetworkMapperTest`: build a small IIDM `Network` in code, assert
  `toGridNetwork` produces correct bus/line/transformer counts and field values
- `NetworkMutationTest`: apply each `NetworkMutation` subtype to a test
  network, assert IIDM state changes correctly
- Round-trip test: `toGridNetwork` → serialise to JSON → deserialise →
  assert equality
- `ShuntCompensatorTest`: verify section count and susceptance mapping

**Integration tests** (`@Tag("integration")`, real IIDM files):
- Load IEEE 14-bus XIIDM → assert bus count = 14, line count, transformer count
- Load IEEE 39-bus XIIDM → assert generator count and fuel type defaults
- `NetworkRepository` save/load round-trip with SQLite
- `ThreeWindingsTransformer` round-trip if present in test network

**Edge cases to cover:**
- Network with isolated bus (no connected branches)
- Generator at min/max output limit
- Disconnected branch (open switch)
- Transformer at non-nominal tap position
- ShuntCompensator at zero sections (fully disconnected)
- Three-winding transformer with one winding disconnected
- Three-winding transformer R/X round-trip (all three legs)

---

## Open Questions

1. **`FuelType` source in real IIDM files**: IEEE test networks carry no fuel
   type metadata. For tutorial/test networks generators will be annotated
   manually in the loader via a sidecar JSON metadata file. Need a convention
   for production/Free Play networks — same sidecar approach is proposed.

2. **Snapshot granularity**: currently one snapshot per tick. For challenge
   mode replay, per-mutation snapshots may be needed. Deferred to Stage 6.

3. **Three-winding transformer tap control**: `ThreeWindingsTransformer` omits
   tap position for now. Each winding can have an independent tap changer in
   IIDM. To be added when transformer control is implemented in the dispatch
   module (Module 04).
