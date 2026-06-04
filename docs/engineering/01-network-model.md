# Network Model

**Stage**: 1
**Status**: Draft — awaiting review
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
- Domain entities: `GridNetwork`, `Bus`, `Branch` (lines and transformers),
  `Generator`, `Load`
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
    val branches: List<Branch>,
    val generators: List<Generator>,
    val loads: List<Load>,
    val snapshotAt: Instant,
)

data class Bus(
    val id: String,
    val name: String,
    val nominalVoltageKv: Double,
    val voltageMagnitudePu: Double?,   // null before first power flow
    val voltageAngleDeg: Double?,
)

sealed class Branch {
    abstract val id: String
    abstract val name: String
    abstract val fromBusId: String
    abstract val toBusId: String
    abstract val ratingMva: Double
    abstract val activePowerFromMw: Double?   // null before first power flow
    abstract val activePowerToMw: Double?

    data class Line(
        override val id: String,
        override val name: String,
        override val fromBusId: String,
        override val toBusId: String,
        override val ratingMva: Double,
        override val activePowerFromMw: Double?,
        override val activePowerToMw: Double?,
        val resistanceOhm: Double,
        val reactanceOhm: Double,
    ) : Branch()

    data class Transformer(
        override val id: String,
        override val name: String,
        override val fromBusId: String,
        override val toBusId: String,
        override val ratingMva: Double,
        override val activePowerFromMw: Double?,
        override val activePowerToMw: Double?,
        val ratioTapPosition: Int,
        val nominalVoltageHvKv: Double,
        val nominalVoltageLvKv: Double,
    ) : Branch()
}

data class Generator(
    val id: String,
    val name: String,
    val busId: String,
    val minActivePowerMw: Double,
    val maxActivePowerMw: Double,
    val targetActivePowerMw: Double,
    val targetReactivePowerMvar: Double,
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
```

### Mapping layer

The `IidmNetworkMapper` converts between PowSyBl's `Network` and
`GridNetwork`. It runs after every power flow solve so that the snapshot
reflects the latest computed state.

```kotlin
interface IidmNetworkMapper {
    fun toGridNetwork(network: Network): GridNetwork
    fun applyCommand(network: Network, command: NetworkCommand): Network
}
```

`NetworkCommand` is a sealed class representing player or event actions that
mutate the network (e.g. `SetGeneratorOutput`, `TripBranch`, `ConnectLoad`).
Commands are applied to the IIDM `Network` directly; the updated `Network` is
then solved and snapshotted.

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
| `NetworkCommand` | Module 09 (command handler) |

---

## Design Decisions & Rationale

1. **Immutable domain snapshots rather than live-binding to IIDM objects.**
   IIDM objects are mutable and solver-owned. Exposing them across module
   boundaries would couple the game engine to PowSyBl internals. Immutable
   snapshots are safe to share, serialise, and diff between ticks.
   *Alternative considered*: expose IIDM objects directly with defensive
   copies. Rejected — coupling risk outweighs the copy cost, which is
   negligible for ≤1000 buses.

2. **`Branch` as a sealed class rather than separate `Line` / `Transformer`
   top-level types.**
   Both share identical operational properties (flow, rating, connectivity)
   used by the renderer and alert system. The sealed class lets callers treat
   them uniformly while preserving type-specific fields. *Alternative*:
   separate flat classes. Rejected — renderer and alert code would duplicate
   the common-case handling.

3. **IIDM XML as the session persistence format.**
   PowSyBl provides `NetworkSerDe` for round-trip XML serialisation at no
   extra cost. It preserves all solver parameters and topology exactly.
   *Alternative*: custom JSON schema. Rejected — would require re-implementing
   PowSyBl's serialisation logic and risk divergence.

4. **`FuelType` enum on `Generator`.**
   Fuel type drives game mechanics (merit order, policy events, environmental
   scoring) and the renderer (icon, colour). Encoding it in the domain model
   rather than deriving it from IIDM metadata keeps game logic clean.
   *Alternative*: tag-based. Rejected — too loosely typed for game logic.

5. **`marginalCostPerMwh` on `Generator`.**
   Economic dispatch in Module 04 needs cost data. Storing it on the
   generator rather than a separate market model keeps the domain cohesive
   for the game's scope. This can be split later if a full market model
   is introduced.

---

## Error Handling

| Failure | Handling |
|---------|----------|
| IIDM file missing or corrupt on session load | Throw `NetworkLoadException`; game engine surfaces to player as "session cannot be restored" |
| Mapper encounters unknown equipment type | Log warning, skip element, include in `GridNetwork.warnings` list |
| `applyCommand` results in topologically invalid network | Return `Result.failure(InvalidCommandException)`; network unchanged |
| SQLite write failure during `save` | Propagate `IOException`; game clock pauses, alert raised |

---

## Testing Strategy

**Unit tests** (`@Tag("unit")`, no PowSyBl solver invoked):
- `IidmNetworkMapperTest`: build a small IIDM `Network` in code, assert
  `toGridNetwork` produces correct bus count, branch flows, generator fields
- `NetworkCommandTest`: apply each `NetworkCommand` subtype to a test network,
  assert IIDM state changes correctly
- Round-trip test: `toGridNetwork` → serialise to JSON → deserialise →
  assert equality

**Integration tests** (`@Tag("integration")`, real IIDM files):
- Load IEEE 14-bus XIIDM file → map to `GridNetwork` → assert bus/branch counts
- Load IEEE 39-bus XIIDM file → assert correct generator count and fuel types
- `NetworkRepository` save/load round-trip with SQLite

**Edge cases to cover:**
- Network with isolated bus (no connected branches)
- Generator at min/max output limit
- Disconnected branch (open switch)
- Transformer at non-nominal tap position

---

## Open Questions

1. **Multi-region networks (Free Play)**: when the grid grows to 500 buses
   across regions, should `GridNetwork` be split into sub-networks per region,
   or remain a single flat structure? Likely deferred to Stage 5 when the
   region unlock system is designed.

2. **`FuelType` source in real IIDM files**: IEEE test networks don't carry
   fuel type metadata. For tutorial/test networks we'll annotate generators
   manually in the loader. Need a convention for production networks — possibly
   a sidecar JSON metadata file.

3. **Snapshot granularity**: currently one snapshot per tick. For challenge
   mode replay, per-command snapshots may be needed. Defer to Stage 6.
