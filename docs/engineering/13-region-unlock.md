# Region Unlock (Free Play Progression)

**Stage**: 6
**Status**: Draft — v1
**Branch**: `stage/6/13-region-unlock`
**Depends on**: [17-grid-expansion.md](17-grid-expansion.md), [08-event-engine.md](08-event-engine.md), [07-game-clock.md](07-game-clock.md)
**Blocks**: #47 (freeplay50 seed network — region definitions), Stage 6 exit criteria ("regions unlock organically")

---

## Purpose

Free Play starts on a fixed ~50-bus, three-region network (`freeplay50`:
North/East/South, see `PresetNetworkFactory.buildFreePlay50Network`) and is
meant to grow toward ~500 buses over a long session (`WORK_PLAN.md` Stage 6).
Module 17 (grid expansion) already lets a player build capacity *within* the
currently-active network in response to local stress. This module answers
the question Module 17 explicitly deferred (Open Question #4): what makes a
*new region* — a whole additional cluster of substations, generators, and
load, not yet visible or interactable — become available at all?

This doc resolves Module 17's Open Question #4: **region-unlock is not a
standalone trigger system.** It is a single additional row in Module 17's
rule-driven proposal table, and region completion reuses the exact same
`BuildProject` → connect-mutation mechanism Module 17 already defines for
individual sites, just applied to a larger, pre-authored batch of dormant
topology. `WORK_PLAN.md`'s framing — "demand pressure accumulates → unlock
prompt → player invests" — is exactly Module 17's stress-detection-to-prompt
loop; this doc only adds what happens when that loop runs out of *local*
remedies.

---

## Scope

**In scope**
- `Region.locked` — the domain-model meaning of "locked"
- How a locked region's topology is authored and seeded (extends Module 17's
  dormant-site pattern to region scale)
- The rule-table extension that offers a region-unlock `ExpansionOption` when
  local remedies are exhausted
- Per-session lock state (locked/unlocked is dynamic, not static preset data)
- Frontend-facing data contract for fog/greyed-out rendering (data only, not
  the render itself)
- Pacing: what gates one region-unlock prompt from firing right after another

**Out of scope**
- The frontend fog-of-war / map-reveal visual treatment itself (UX concern,
  consumes the data this doc defines)
- Which specific regions `freeplay50` ships with beyond the existing
  North/East/South (authoring exercise, tracked under #47)
- Tutorial/Challenge modes — Free Play only, same restriction Module 17 has
- Re-locking a region (not supported; see Resolved Design Points)

---

## Domain Model

### `Region.locked`: the domain-model meaning of "locked"

`Region` already exists (`GridNetworkModels.kt`) as a game-mode annotation —
`id`, `name`, `busIds` — with no topological meaning to the solver. This doc
adds one field:

```kotlin
data class Region(
    val id: String,
    val name: String,
    val busIds: Set<String>,
    /**
     * True until this region's gateway BuildProject completes. While locked,
     * every IIDM element whose bus is in busIds is disconnected (same
     * disconnected-until-built trick as an ExpansionSite, applied to an
     * entire region's pre-built topology at once — see "Locked region
     * topology" below). The frontend should treat a locked region as
     * fog/unavailable, not as "zero-valued" — no meaningful voltage/loading
     * data exists for a disconnected element.
     */
    val locked: Boolean = false,
)
```

This directly answers issue #412's domain-model question: locked is **both**
a per-region flag (`Region.locked`, read by the frontend and by the rule
engine) **and** mechanically realized as a batch of gated `ExpansionSite`-
style disconnected topology (read by the solver/mapper) — the flag is a
summary of the underlying disconnected-element state, not a separate source
of truth. The two cannot drift apart: `locked` is computed, per snapshot,
from whether the region's gateway `BuildProject` (below) has completed —
never set independently.

### Locked region topology: pre-built, same trick as Module 17, larger batch

A locked region is authored into the preset's IIDM network from session
start, exactly like a Module 17 `ExpansionSite` — real substations, voltage
levels, buses, generators, loads, and internal lines, all with disconnected
terminals — just scoped to a whole region's worth of elements instead of one
site's. This preserves Module 17's Design Decision #1 (correctness by
construction: disconnected elements are already provably excluded from power
flow, dispatch, and — since #407 — contingency analysis) at the larger
scale, and avoids ever synthesizing new IIDM topology at runtime, same as
Module 17's rejected alternative (Design Decision #2).

A locked region connects to the currently-unlocked network through exactly
one **gateway**: a `NEW_LINE` `ExpansionSite` plus a `SUBSTATION`
`ExpansionSite` at the entry point, using the field Module 17 already
defines for this purpose:

```kotlin
// Addition to ExpansionSite (17-grid-expansion.md):
data class ExpansionSite(
    // ...existing fields (id, kind, anchorBusId, remediesElementId,
    // connectingLineSiteId, locationHint)...
    /**
     * Set only on the gateway SUBSTATION site of a locked Region. Building
     * this project's siteIds (the gateway SUBSTATION + its connectingLineSiteId)
     * additionally connects every other disconnected element whose bus is in
     * the target Region.busIds -- see "Region-unlock BuildProject completion"
     * below. Exactly one ExpansionSite per session may carry a given
     * unlocksRegionId (the gateway is the only entry point; internal region
     * topology has no unlocksRegionId of its own).
     */
    val unlocksRegionId: String? = null,
)
```

A region has exactly one gateway in v1 — multiple entry points is a
plausible future extension (not designed here; would need a rule for which
gateway "wins" if two are offered concurrently) but adds complexity v1
doesn't need since `freeplay50`'s three starting regions are already fully
connected and no region-growth playtesting data exists yet to justify it.

### Region-unlock `BuildProject` completion

Module 17's tick-engine logic (any `BUILDING` project whose duration has
elapsed fires its sites' connect mutations and flips to `COMPLETE`) is
extended, not replaced:

```kotlin
// Tick-engine build-completion handling (extends Module 17's):
fun completeBuildProject(project: BuildProject, sites: Map<String, ExpansionSite>) {
    for (siteId in project.siteIds) {
        connectSite(sites.getValue(siteId)) // existing ConnectGenerator/ConnectLine, unchanged
    }
    val gatewaySite = project.siteIds.map { sites.getValue(it) }.firstOrNull { it.unlocksRegionId != null }
    if (gatewaySite != null) {
        connectAllDisconnectedElementsInRegion(gatewaySite.unlocksRegionId!!) // NEW
        regionLockState.unlock(project.sessionId, gatewaySite.unlocksRegionId) // NEW, see below
    }
}
```

`connectAllDisconnectedElementsInRegion` is the one genuinely new piece of
mutation logic this module adds — but it is a loop over the *same*
`ConnectGenerator`/`ConnectLine` mutations Module 17 already implements and
already has correctness coverage for (Module 17 Design Decision #1), applied
to every element in `Region.busIds` instead of one site's elements. No new
`NetworkMutation` type is introduced, consistent with Module 17's core
architectural bet.

### Per-session lock state

`locked` is dynamic — it changes mid-session — so it cannot be static preset
metadata (unlike region *membership*, `busIds`, which is fixed at
authoring time and supplied via the same sidecar-metadata pattern already
used for generator fuel type: `GeneratorMetadataProvider` /
`MapGeneratorMetadataProvider`, since IIDM has no native "region" concept).
This doc adds an analogous `RegionMetadataProvider` for the static part
(preset → `List<Region>` with `busIds`, but no lock state) plus session-
scoped mutable lock state:

```kotlin
/** Tracks which of a preset's regions are still locked, per session. */
class RegionLockState {
    private val locked = ConcurrentHashMap.newKeySet<String>() // regionIds

    fun initialize(regionIds: Collection<String>) = locked.addAll(regionIds)
    fun isLocked(regionId: String): Boolean = regionId in locked
    fun unlock(regionId: String) { locked.remove(regionId) }
}
```

This is deliberately the same shape as the per-session `ContingencyAnalysisCache`
map that issue #347 just introduced (`ConcurrentHashMap<String, T>`, keyed by
sessionId, created lazily) — same problem (per-session state living on a
service that is itself a session-spanning singleton), same fix. Whichever
session store ends up owning the (not-yet-implemented, #414) `BuildProject`
queue should own `RegionLockState` alongside it; they are both per-session,
mutated by the same tick-engine build-completion path, and read together
when building each tick's `GridNetwork` snapshot (`IidmNetworkMapperImpl`
consults `RegionLockState.isLocked(region.id)` to populate `Region.locked`
on the snapshot it returns — this is the one new read in the mapper this
module requires).

### Rule table extension

Module 17's rule table maps a sustained violation to a remedy kind and a
specific matching `ExpansionSite`. This module adds a fourth row, evaluated
**only when rows 1–3 find no candidate** (i.e., exactly the existing "no
prompt fires" case in Module 17's Error Handling table) **and** at least one
locked region remains:

| Sustained violation | Remedy kind(s) considered | Match on `remediesElementId` |
|---|---|---|
| *(existing rows 1–3, unchanged — thermal, voltage, area-wide stress within the currently-unlocked network)* | | |
| Rows 1–3 found no matching not-yet-built site anywhere in the unlocked network, **and** a locked region exists | Gateway `SUBSTATION` + `NEW_LINE` bundle for the nearest locked `Region` | sentinel `region:<lockedRegionId>` |

This ordering is the pacing mechanism the issue asks about: a region-unlock
prompt cannot fire while any local remedy is still available, because rows
1–3 always win first. Concretely, for `freeplay50`'s starting regions
(already unlocked, so irrelevant to this) and any future locked region: the
player must exhaust — build out — every in-network `ExpansionSite` Module 17
would otherwise offer before the game proposes expanding into new territory.
This is a *structural* gate (site availability), not a counter or timer, so
it needs no new state of its own and scales naturally with how many sites
`freeplay50` (or a future larger preset) authors per region — the same
density question Module 17 Open Question #5 already flags, now shared by
this module.

**Interaction with #402**: none. #402's early-`GAME_OVER` finding is on the
`ieee14` preset in whatever mode exercises it (baseline overvoltage in the
classic solved case, compounded by two generators mislabeled `WIND`/`SOLAR`)
— a Tutorial/diagnostic network, not `freeplay50`. Region-unlock pacing only
applies to Free Play's `freeplay50`-family presets, which don't share
`ieee14`'s topology or metadata. Once #402 is fixed, it's worth re-verifying
`freeplay50`'s own baseline is clean, but that is an authoring-correctness
check on a different preset, not a design dependency of this module.

---

## Frontend Data Contract

- `GridNetwork.regions[].locked: Boolean` (new field, above) — the frontend
  renders fog/greyed-out treatment over `locked == true` regions' map
  footprint and suppresses per-element detail (no useful voltage/loading
  data exists for disconnected elements — do not render zeros).
- Region-unlock fires as a normal Module 17 `EventCard` with an
  `ExpansionOption` — no new WS message type. The card's `label` should read
  distinctly from an in-region build (e.g. "Expand into East Region" vs.
  "Add second circuit: L4") so the frontend/UX pass can style it as a bigger
  moment; the underlying data shape is identical.
- On the tick a region-unlock `BuildProject` completes, every element in
  that region transitions from absent-in-practice (disconnected, no
  meaningful values) to fully populated in the very next `GameStateUpdate` —
  same as any other Module 17 build completion, just a larger batch of
  elements changing state in one tick. No incremental/partial reveal is
  planned for v1.

---

## Error Handling

| Failure | Handling |
|---------|----------|
| Rule table's row 4 (region-unlock) has a locked region but its gateway `ExpansionSite` doesn't exist in the preset (authoring gap) | No prompt fires — same "author more sites" signal as Module 17's existing Error Handling row, applied to gateways |
| Region-unlock `BuildProject` completes but `connectAllDisconnectedElementsInRegion` fails partway (some elements connect, some don't) | Same as Module 17's completion-failure handling: log + alert, mark `COMPLETE` and `RegionLockState.unlock()` anyway (a region stuck permanently "locked" on an engine bug is worse than a partially-connected one flagged for a fix) |
| Two locked regions both qualify for row 4 in the same tick | Deterministic tie-break: lowest `Region.id` (stable, testable); not expected to matter in practice since `freeplay50` v1 ships with a single unlock path, but avoids nondeterminism if a future preset has more than one |

---

## Testing Strategy

**Unit tests**: `Region.locked` defaults false; rule table only proposes row
4 when rows 1–3 return no candidate; `RegionLockState.unlock` is idempotent
and reflected on the next `isLocked` read; `connectAllDisconnectedElementsInRegion`
connects every element in `busIds` and none outside it (a region-boundary
test using two adjacent regions sharing a tie line, verifying only the
target region's internal elements flip).

**Integration tests**: seed a preset with one locked region reachable by
exactly one gateway, exhaust local remedies (force sustained violations with
no matching in-region `ExpansionSite` left), assert the region-unlock
`EventCard` fires, accept it, advance the clock past
`buildDurationGameMinutes`, assert the entire region's elements now
participate in power flow/dispatch/contingency analysis and
`GridNetwork.regions` reports `locked = false`.

---

## Design Decisions & Rationale

1. **Region-unlock is a rule-table row, not a separate subsystem.** The
   alternative — a standalone timer/counter-based unlock trigger — would
   duplicate Module 17's stress-detection, event-card, and build-completion
   machinery for no benefit; framing it as "the remedy for area-wide stress
   with no local site left" makes it fall out of the existing pipeline for
   free and inherits the same correctness guarantees (Module 17 Design
   Decision #1).
2. **Locked region topology is pre-built and disconnected, at region scale,
   same trick as an `ExpansionSite`.** Consistent with Module 17 Design
   Decision #2's rejection of runtime IIDM synthesis — the topology
   authoring cost scales with how many regions/buses `freeplay50` (or a
   larger preset) ships with, not with new engine capability.
3. **Lock state is per-session, not preset-static — mirrors the #347 fix.**
   `Region.busIds`/membership is static (authored once); `locked` changes
   during play and must live in session-scoped state, not on the immutable
   preset. Using the same `ConcurrentHashMap<sessionId, T>` shape #347 just
   introduced for contingency-result caching keeps the pattern consistent
   rather than inventing a second per-session-state idiom.
4. **No re-locking.** Once unlocked, a region stays unlocked for the rest of
   the session — matches Module 17's "no cancel/refund" stance (Resolved
   Design Point #6): the point of construction latency and gating is to make
   forward progress feel earned, not to be reversible.

---

## Resolved Design Points

1. **Module 17 Open Question #4 (region-unlock coupling) — RESOLVED.**
   Region-unlock and grid-expansion are the same module's machinery, applied
   at two scales (single site vs. whole region), not two independently
   coupled modules. See [Domain Model](#domain-model) and
   [Design Decisions](#design-decisions--rationale) #1 above.

---

## Open Questions

1. **How many locked regions does `freeplay50` ship with, and where?** Not
   determined here — an authoring/playtesting question tracked under #47,
   same category as Module 17 Open Question #5 (site density). A reasonable
   first pass: one or two locked regions beyond the three starting ones,
   enough to validate the mechanic without a large up-front authoring cost.
2. **Gateway cost/duration scale.** This doc assumes a region-unlock
   `BuildProject`'s `costGbp`/`buildDurationGameMinutes` are simply larger
   than a typical in-region site's, reflecting the bigger scope, but doesn't
   propose numbers — depends on #413's budget model landing first (a region
   gateway needs to be a meaningful but reachable fraction of whatever
   budget model #413 defines).
3. **Multi-gateway regions.** Explicitly deferred to a future revision if
   playtesting shows a single entry point feels too linear — see Domain
   Model note on `unlocksRegionId`.

---

[[17-grid-expansion.md]] Open Question #4 is resolved by this doc — see
`17-grid-expansion.md`'s Resolved Design Points for the cross-link update
landing in the same PR.
