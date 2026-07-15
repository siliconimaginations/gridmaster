# Grid Expansion (Investment System)

**Stage**: 6
**Status**: Draft — v1
**Branch**: `stage/6/17-grid-expansion`
**Depends on**: [08-event-engine.md](08-event-engine.md), [09-command-handler.md](09-command-handler.md), [07-game-clock.md](07-game-clock.md), [04-dispatch.md](04-dispatch.md)
**UX reference**: [docs/ux/07-planning-panel.md](../ux/07-planning-panel.md)

---

## Purpose

As a Free Play session runs, organic load growth (daily/weekly/seasonal/annual
curves, #383/#388) and weather-driven renewable variability (#391) push parts
of the network toward its thermal and voltage limits. This module lets the
player respond by building new capacity — generation, a new substation, or a
second circuit on an existing line corridor — closing the loop between
"the grid is stressed" and "the player fixes it," which is the game's core
teaching loop for expansion planning.

This is new territory for the codebase: today, every `NetworkMutation`
operates on an element that already exists in the network built at session
creation (see [01-network-model.md](01-network-model.md), which explicitly
scopes game-mode-specific network mutation out). This module is the first to
introduce elements that do not yet exist — or exist but are not yet
energized — mid-session.

---

## Scope

**In scope**
- Congestion/stress detection that surfaces a **prompted** build decision
  (event card), not free-form placement
- A small set of pre-sited build options per prompt: new generator, new
  substation (bus + step-down transformer), or a second circuit on an
  existing line corridor ("double line")
- Decline handling: stress continues accumulating; a new (likely more urgent)
  prompt re-fires later
- Multi-tick construction latency, represented in PowSyBl without the
  in-progress element affecting power flow, dispatch, or contingency analysis
- Visual distinction + build-progress exposure for in-progress projects
  (frontend consumes; this doc defines the data, not the render)
- Budget/cost accounting hook (exact economic model TBD — see Open Questions)

**Out of scope**
- Free-form placement / player-chosen siting or routing
- "Line upgrade" (re-rating an existing line) — explicitly deferred, see
  Resolved Design Points
- Tutorial and Challenge modes (Free Play only for v1; tutorial build
  missions are plausible later but not designed here)
- Region-unlock *progression* mechanics beyond the trigger hook described
  below (full Module 13 spec deferred — see Open Questions)
- Cancel/refund of an in-progress project (not supported — see Resolved
  Design Points)
- Revenue/economy model beyond the existing `systemMarginalCostPerMwh`
  signal (see Open Questions)

---

## Domain Model

### Expansion sites: pre-built, dormant topology

Rather than synthesizing new IIDM objects at build time (new substations,
buses, voltage levels — all possible in PowSyBl, but a much larger surface
area to get right for siting/coordinates/voltage-level compatibility), each
network preset that supports expansion (initially `freeplay50`) is seeded
with **dormant expansion sites**: real IIDM topology — substations, voltage
levels, buses, and (for generator sites) a generator — that exists in the
network from session start, but whose elements are **disconnected** and
whose generator/line has no meaningful rating until "built."

This reframes "build a new generator" as "connect a pre-existing, currently
disconnected generator" — which is exactly the `ConnectGenerator`/
`ConnectLine` mutation semantics already implemented and already proven to
correctly exclude disconnected elements from power flow, dispatch, and (per
the assumption flagged in Open Questions) contingency analysis. See
[Design Decisions](#design-decisions--rationale) #1.

```kotlin
/**
 * A dormant site pre-built into an expansion-capable preset's IIDM network.
 * Not part of the domain GridNetwork model exposed to the client directly —
 * surfaced only through ExpansionOption (below) once a prompt targets it.
 */
data class ExpansionSite(
    val id: String,
    val kind: ExpansionSiteKind,
    /** Bus/substation this site would connect into once built. */
    val anchorBusId: String,
    /** Approximate map coordinates for the preview render (frontend concern). */
    val locationHint: LocationHint,
)

enum class ExpansionSiteKind { GENERATOR, SUBSTATION, DOUBLE_LINE }

data class LocationHint(val x: Double, val y: Double)
```

`DOUBLE_LINE` sites are different from the other two: there is no new bus —
the "site" is an existing line corridor (`fromBusId`/`toBusId` already in the
network) that has a second, currently-disconnected `Line` IIDM object
pre-modeled in parallel with the same terminals (same corridor, doubling
thermal capacity once connected). This avoids ever having to compute a new
line's impedance/rating from scratch: the second circuit is an exact
duplicate of the first, so its rating is derived the same N-1-margin way
`PresetNetworkFactory` already does for the corridor overall.

### Build projects: the active queue

```kotlin
/**
 * A committed, in-progress (or completed) build, persisted per session.
 * Once created, always runs to completion — no cancel (Resolved Design Points).
 */
data class BuildProject(
    val id: String,
    val sessionId: String,
    val siteId: String,
    val costGbp: Double,
    val buildDurationGameMinutes: Long,
    val startedAtGameTimeMinutes: Long,
    val status: BuildStatus,
)

enum class BuildStatus { BUILDING, COMPLETE }

/** Percent complete, purely derived — not persisted. */
fun BuildProject.percentComplete(currentGameTimeMinutes: Long): Int =
    (((currentGameTimeMinutes - startedAtGameTimeMinutes).toDouble()
        / buildDurationGameMinutes) * 100)
        .toInt()
        .coerceIn(0, 100)
```

The tick engine (Module 07) advances `BuildProject`s the same way it already
advances load curves and weather: on each tick, any `BUILDING` project whose
`startedAtGameTimeMinutes + buildDurationGameMinutes <= currentGameTimeMinutes`
fires the existing `ConnectGenerator`/`ConnectLine` mutation for its site and
flips to `COMPLETE`. **No new "commissioning" mutation type is needed** — this
is the same insight as above: completion *is* an existing connect mutation.

### Stress detection → prompted event card

Congestion/voltage stress detection reuses the existing violation machinery
(`ViolationScanner`, `NetworkViolation.ThermalViolation`/`VoltageViolation`)
rather than inventing a new signal. A new `EventCategory.EXPANSION` (or a
non-stochastic trigger path — see Open Questions) watches for **sustained**
violations (mirroring `TickEngineImpl`'s existing
`GAME_OVER_CONSECUTIVE_LOW_HEALTH`-style consecutive-tick pattern, not a
single-tick blip) near a bus with at least one dormant `ExpansionSite`, and
fires an `EventCard` offering 2–3 `ExpansionOption`s.

```kotlin
data class ExpansionOption(
    val siteId: String,
    val label: String,               // "Build Gas Peaker South", "Add second circuit: L4"
    val costGbp: Double,
    val buildDurationGameMinutes: Long,
    val capacityAddedMw: Double?,    // null for DOUBLE_LINE (capacity = rating, not MW)
    val previewImageUrl: String,     // or a structured preview payload — see Open Questions
)
```

**Decline handling**: the card's existing "no thanks" / dismiss path (Module
08's `EventCard`/`CardOption` pattern already supports a no-cost option) is
reused. Declining does **not** suppress future prompts — the underlying
violation persists or worsens, and the same consecutive-tick detector fires
again later, naturally producing a "more urgent" re-prompt as violations
compound (no separate escalation logic needed; severity is already a
property of the violation itself via `ViolationSeverity`).

---

## Design Decisions & Rationale

1. **Reuse `ConnectGenerator`/`ConnectLine`, don't invent new mutation types.**
   The single biggest risk in this feature is accidentally letting an
   unfinished project affect the simulation. Every other place in the
   codebase that needs "this element exists in the topology but must not
   participate" already solves it via terminal disconnection — `TripLine`,
   `TripGenerator`, and the underlying `PowSyBlPowerFlowService`/dispatch/
   contingency code all already correctly ignore disconnected elements. By
   modeling "under construction" as "disconnected" and "commissioned" as
   "connected," this feature inherits correctness it would otherwise have to
   re-implement and re-test from scratch.

2. **Pre-sited dormant topology, not runtime IIDM synthesis.**
   Creating a *genuinely new* substation/voltage-level/bus at runtime is
   possible in PowSyBl but touches code paths (topology creation, ID
   allocation, voltage-level compatibility, coordinate assignment) that
   don't exist anywhere in the engine today. Seeding the sites in
   `PresetNetworkFactory` at network-build time instead means "building" is
   always just a connect mutation — zero new IIDM-mutation surface area.
   Trade-off: expansion sites must be authored per preset in advance, so
   this only works for presets designed with expansion in mind
   (`freeplay50`), not arbitrary future presets, unless they're also
   authored with dormant sites.

3. **Double-circuit lines reuse the existing corridor's rating, not a new
   calculation.**
   A second circuit on the same corridor has (approximately) the same
   impedance and thermal characteristics as the first. Modeling it as a
   duplicate `Line` object between the same terminals means its rating can
   be derived identically to how `PresetNetworkFactory` already derives
   ratings — no new methodology needed, and it sidesteps the harder question
   of rating a truly novel line, which is deferred (see Open Questions on
   `GENERATOR`/`SUBSTATION` site build-time rating, which don't have this
   shortcut).

4. **Stress detection reuses `ViolationScanner`, not a new signal.**
   Violations are already computed every tick as part of the power-flow
   pipeline. A consecutive-tick counter per site (same shape as
   `TickEngineImpl.GAME_OVER_CONSECUTIVE_LOW_HEALTH`) is a small, additive
   piece of tick-engine state, not a new subsystem.

---

## Error Handling

| Failure | Handling |
|---------|----------|
| Player accepts an `ExpansionOption` but budget has changed (e.g. spent elsewhere between prompt and accept) | Reject at command-validation time (same pattern as `validateSetGeneratorOutput`'s range check); card remains open for another choice |
| `BuildProject` completion mutation (`ConnectGenerator`/`ConnectLine`) fails (e.g. site somehow already connected) | Log error; mark project `COMPLETE` anyway (data model shouldn't get stuck `BUILDING` forever on an engine bug) and alert; treat as a bug to fix, not a player-facing retry path |
| Session ends (GAME_OVER) mid-build | Project state persists with the session; no special handling needed since a completed session isn't ticking |
| Violation persists at a site with no un-built `ExpansionSite` nearby (e.g. all options already built) | No prompt fires — same as today's behavior when a violation has no obvious fix; N-1/planning panel still surfaces the raw violation |

---

## Testing Strategy

**Unit tests**: `BuildProject.percentComplete` boundary cases; consecutive-tick
stress detector fires only after N ticks, not on a single blip; declining a
card does not create a `BuildProject` and does not suppress future
detection; `ConnectGenerator`/`ConnectLine` on an `ExpansionSite`'s
previously-disconnected elements produces a network identical in shape to
one built pre-connected (i.e., no special-casing leaks into the mapper).

**Integration tests**: full cycle — seed a `freeplay50` session, force a
sustained thermal violation near an `ExpansionSite` bus (e.g. via a targeted
load increase), assert the event card fires with the expected options, accept
one, advance the clock past `buildDurationGameMinutes`, assert the site's
generator/line is connected and now participates in the next power-flow
solve (current flows, dispatch considers it, N-1 contingency list includes
it).

---

## Resolved Design Points (per Rick's review, 2026-07-11)

1. **Prompted options, not free placement.** Agreed — v1 offers 2–3 pre-sited
   options per prompt, not a build-anywhere sandbox.

2. **Preview required.** Every `ExpansionOption` must let the player see where
   the site/line actually is before committing — a `previewImageUrl` field
   (or equivalent) is part of the option payload; exact preview mechanism
   (rendered thumbnail vs. map highlight-on-hover) is a frontend/UX decision,
   not this doc's to make, but the data must be there to support it.

3. **Decline is a first-class option, not just a dismiss.** Declining leaves
   the underlying stress in place; a later, likely more urgent, prompt
   re-fires. No separate "declined" cooldown/suppression state — the natural
   consequence (worse violations) is the mechanism.

4. **Tutorial**: out of scope for now; Free Play first. Possible future
   tutorial mission on building, not designed here.

5. **Line upgrade (re-rating an existing line) is explicitly deferred.**
   Double-circuit (parallel second line) is in scope instead — different
   engineering shape (add a disconnected duplicate `Line`, vs. mutate an
   existing line's `currentLimits`), and ships independently if wanted later.

6. **No cancel/refund.** Once a `BuildProject` is created (funds committed),
   it always runs to completion. The construction-latency mechanic exists
   specifically to make the build-time cost real, so removing that cost via
   cancellation would undercut the point.

7. **In-progress builds must not affect simulation, dispatch, or scheduling,
   and must be visually distinguishable with a progress indicator.** Solved
   via the disconnected-element approach in
   [Design Decisions](#design-decisions--rationale) #1 — `ExpansionSite`
   elements are real IIDM objects from session start, but stay disconnected
   (so `PowSyBlPowerFlowService`, dispatch's `toDispatchableGenerators`, and
   — pending confirmation, see Open Questions — contingency analysis all
   already ignore them the same way they ignore any other disconnected
   element) until the `BuildProject` completes and fires the existing
   connect mutation. Visual distinction (translucent/wireframe "under
   construction" mesh) and the progress bar are frontend concerns, fed by
   `BuildProject.percentComplete()` and a `status` the WS state stream would
   need to carry (new `GameStateUpdate` field — not yet designed here).

---

## Open Questions

1. **Contingency analysis does NOT already filter disconnected lines/
   transformers — confirmed, this is a real gap, not a hypothetical.**
   Checked `ContingencyBuilder.buildN1`: generators are filtered by
   `it.connected`, but lines, two-winding, and three-winding transformers are
   not — every one in the network gets an N-1 scenario regardless of
   connection state. A dormant `ExpansionSite` `GENERATOR` is therefore
   already handled correctly (excluded), but a dormant `SUBSTATION`'s
   elements or a `DOUBLE_LINE` site's disconnected second circuit would
   generate a nonsensical "loss of line X" scenario for a line that was
   never built. **Fix needed as a prerequisite for this module**: add the
   same `.filter { it.connected }` to the lines/transformers loops in
   `ContingencyBuilder.buildN1` that generators already have. This is a
   small, independently-shippable correctness fix (arguably worth doing
   regardless of this module, since a manually-tripped line already produces
   the same nonsensical contingency today) and should land before or
   alongside the `ExpansionSite` implementation, not discovered by it.
   Filed as issue #407.

2. **Budget/economy model.** The UX doc's £480M budget figure implies a
   dedicated capital budget, separate from the operating economics
   (`systemMarginalCostPerMwh`, dispatch cost). Is this a fixed/periodic
   allowance, a fraction of simulated electricity revenue (closing a
   congestion → cost → revenue → capital loop), or something else? This
   doc assumes a `costGbp` exists to check against *some* budget number but
   doesn't define where that number comes from — needs its own small design
   note, likely as an addendum here or a short separate doc, before
   implementation.

3. **Preview mechanism.** Static thumbnail image vs. an interactive
   highlight-on-map (fly-to-location + outline) — leaning toward the latter
   for a 3D isometric game (a static image feels like a step backward from
   what the engine can already do), but that's a UX call, not an engineering
   one. Flagging so the UX pass doesn't default to the simplest option
   without discussion.

4. **Region-unlock coupling (Module 13).** `ExpansionSite`s could double as
   the mechanism for revealing previously-locked map regions (build a site
   in a dormant region → region "unlocks" visually/interactively), which
   would mean this module and Module 13 are more tightly coupled than
   "depends on" suggests — possibly the same module. Recommend writing
   Module 13 next, explicitly checking it against this doc rather than
   independently, since `WORK_PLAN.md` and issue #47 both reference Module
   13 as an unwritten blocker already.

5. **How many `ExpansionSite`s does `freeplay50` need, and where?** Not
   determined here — depends on how many distinct "this area is stressed"
   moments the game wants to manufacture across a session, and interacts
   with the region-unlock pacing question above.
