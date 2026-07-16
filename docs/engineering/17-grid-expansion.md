# Grid Expansion (Investment System)

**Stage**: 6
**Status**: Draft — v2
**Branch**: `stage/6/17-grid-expansion`
**Depends on**: [08-event-engine.md](08-event-engine.md), [09-command-handler.md](09-command-handler.md), [07-game-clock.md](07-game-clock.md), [04-dispatch.md](04-dispatch.md)
**UX reference**: [docs/ux/07-planning-panel.md](../ux/07-planning-panel.md)

---

## Purpose

As a Free Play session runs, organic load growth (daily/weekly/seasonal/annual
curves, #383/#388) and weather-driven renewable variability (#391) push parts
of the network toward its thermal and voltage limits. This module lets the
player respond by building new capacity — generation, a new substation, a
new line, a second circuit on an existing line corridor, or reactive/voltage
support — closing the loop between "the grid is stressed" and "the player
fixes it," which is the game's core teaching loop for expansion planning.

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
- **Rule-driven** option derivation: the specific remedy(ies) offered are
  computed from the observed violation (type, location, persistence), not
  authored 1:1 ahead of time — see
  [Rule-driven proposal derivation](#rule-driven-proposal-derivation)
- Build kinds: new generator, new substation, a new line, a second circuit
  on an existing line corridor ("double line"), and a shunt compensator
  (reactive/voltage support). Building a new generator or substation may
  require an accompanying new line to connect it back into the energized
  network — see [Rule-driven proposal derivation](#rule-driven-proposal-derivation)
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
levels, buses, lines, and (for generator sites) a generator — that exists in
the network from session start, but whose elements are **disconnected** and
whose generator/line has no meaningful rating until "built."

This reframes "build a new generator" as "connect a pre-existing, currently
disconnected generator" — which is exactly the `ConnectGenerator`/
`ConnectLine` mutation semantics already implemented and already proven to
correctly exclude disconnected elements from power flow, dispatch, and (per
the fix landed in #407) contingency analysis. See
[Design Decisions](#design-decisions--rationale) #1.

Pre-building the topology is an engineering necessity for the
disconnect-until-built trick — it is **not** the same thing as deciding
ahead of time which remedy gets offered for which problem. That mapping is
computed at runtime; see the next section.

```kotlin
/**
 * A dormant site pre-built into an expansion-capable preset's IIDM network.
 * Not part of the domain GridNetwork model exposed to the client directly —
 * surfaced only through ExpansionOption (below) once a rule matches it to
 * an observed violation.
 */
data class ExpansionSite(
    val id: String,
    val kind: ExpansionSiteKind,
    /** Bus/substation this site would connect into once built. */
    val anchorBusId: String,
    /**
     * The existing network element this site is a remedy *for*, used by the
     * rule engine to match a site to a violation. A DOUBLE_LINE site's target
     * is the line it duplicates; a GENERATOR/SHUNT_COMPENSATOR site's target
     * is the bus whose voltage it supports; a SUBSTATION/NEW_LINE site's
     * target is the congested corridor/area it relieves. See
     * [Rule-driven proposal derivation](#rule-driven-proposal-derivation).
     */
    val remediesElementId: String,
    /**
     * Set only when this site requires a separate connecting line to reach
     * the energized network (e.g. a GENERATOR or SUBSTATION site sitting on
     * its own new bus). References another dormant ExpansionSite of kind
     * NEW_LINE that must be built as part of the same BuildProject.
     */
    val connectingLineSiteId: String?,
    /** Approximate map coordinates for the preview render (frontend concern). */
    val locationHint: LocationHint,
)

enum class ExpansionSiteKind { GENERATOR, SUBSTATION, NEW_LINE, DOUBLE_LINE, SHUNT_COMPENSATOR }

data class LocationHint(val x: Double, val y: Double)
```

`DOUBLE_LINE` sites are different from the others: there is no new bus —
the "site" is an existing line corridor (`fromBusId`/`toBusId` already in the
network) that has a second, currently-disconnected `Line` IIDM object
pre-modeled in parallel with the same terminals (same corridor, doubling
thermal capacity once connected). This avoids ever having to compute a new
line's impedance/rating from scratch: the second circuit is a new `Line`
object with parameters (impedance, rating) identical to the first, not a
shared/aliased definition — the two are independent IIDM objects that
happen to have matching electrical characteristics because they share a
corridor. Its rating is derived the same N-1-margin way
`PresetNetworkFactory` already does for the corridor overall.

`NEW_LINE` sites exist to connect a `GENERATOR` or `SUBSTATION` site's new
bus back into the energized network when that site isn't directly adjacent
to an existing bus — see `connectingLineSiteId` above. A `GENERATOR` or
`SUBSTATION` `BuildProject` that has a non-null `connectingLineSiteId` builds
both elements together (see Build Projects below); a site close enough to
tap directly into an existing bus can omit it.

`SHUNT_COMPENSATOR` sites address sustained voltage violations directly —
a dormant shunt/capacitor bank at a bus, connected the same
disconnected-until-built way as a generator.

### Build projects: the active queue

```kotlin
/**
 * A committed, in-progress (or completed) build, persisted per session.
 * Once created, always runs to completion — no cancel (Resolved Design Points).
 * siteIds is plural because a GENERATOR/SUBSTATION build with a
 * non-null connectingLineSiteId commits both sites as one project — they
 * complete together (the generator is useless without its connecting line).
 */
data class BuildProject(
    val id: String,
    val sessionId: String,
    val siteIds: List<String>,
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
fires the existing `ConnectGenerator`/`ConnectLine` mutation for each of its
sites and flips to `COMPLETE`. **No new "commissioning" mutation type is
needed** — this is the same insight as above: completion *is* one or more
existing connect mutations.

### Rule-driven proposal derivation

Sites are pre-built, but *which* site gets offered for *which* problem is
not authored ahead of time as a fixed per-preset catalog — a level designer
cannot know in advance exactly where load/generation/weather growth will
create stress over the course of a session. Instead, a small, explicit set
of rules maps an observed, sustained violation to a compatible remedy kind,
then looks up the nearest not-yet-built `ExpansionSite` whose
`remediesElementId` matches the violating element:

| Sustained violation | Remedy kind(s) considered | Match on `remediesElementId` |
|---|---|---|
| Thermal violation (baseline or N-1) on line/transformer `L` | `DOUBLE_LINE` on `L`; if none available, `NEW_LINE`/`SUBSTATION` reinforcing the same corridor | `L`'s ID |
| Voltage violation at bus `B`, persisting after existing reactive/voltage-control dispatch has been applied | `SHUNT_COMPENSATOR` at `B`; `GENERATOR` (with voltage/reactive capability) near `B` | `B`'s ID |
| Sustained thermal *and* voltage stress across a wider area with no single-element remedy available | `SUBSTATION` (new injection point) nearest the affected area | area's representative bus ID |

This table is deliberately not exhaustive — it is the starting set of
principles, expected to grow as playtesting surfaces cases it doesn't
cover — but it establishes the pattern: **the violation observed at runtime
picks the remedy type and location; authoring only decides where compatible
dormant sites physically exist in the preset, not which violation each one
answers.** A preset with no matching dormant site for an observed violation
simply doesn't prompt (see Error Handling) — a signal that `freeplay50`
needs more sites authored near that area, not a bug.

### Stress detection → prompted event card

Congestion/voltage stress detection reuses the existing violation machinery
(`ViolationScanner`, `NetworkViolation.ThermalViolation`/`VoltageViolation`)
rather than inventing a new signal. A new `EventCategory.EXPANSION` (or a
non-stochastic trigger path — see Open Questions) watches for **sustained**
violations (mirroring `TickEngineImpl`'s existing
`GAME_OVER_CONSECUTIVE_LOW_HEALTH`-style consecutive-tick pattern, not a
single-tick blip), runs the rule table above against each sustained
violation, and — if at least one compatible dormant site is found — fires
an `EventCard` offering 2–3 `ExpansionOption`s.

```kotlin
data class ExpansionOption(
    val siteIds: List<String>,        // >1 only when a connectingLineSiteId is bundled in
    val label: String,                // "Build Gas Peaker South", "Add second circuit: L4"
    val costGbp: Double,
    val buildDurationGameMinutes: Long,
    val capacityAddedMw: Double?,     // null for DOUBLE_LINE/SHUNT_COMPENSATOR (not an MW addition)
    val previewImageUrl: String,      // or a structured preview payload — see Open Questions
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

2. **Pre-sited dormant topology + a runtime rule engine, not a fixed
   per-preset catalog and not runtime IIDM synthesis.**
   Creating a *genuinely new* substation/voltage-level/bus at runtime is
   possible in PowSyBl but touches code paths (topology creation, ID
   allocation, voltage-level compatibility, coordinate assignment) that
   don't exist anywhere in the engine today — so the *topology* is still
   pre-authored in `PresetNetworkFactory`, same as v1. What changed from the
   original draft: the original draft implied a designer would also decide,
   ahead of time, which specific violation triggers which specific site.
   Rick's review correctly flagged that this is brittle — the precise
   bottleneck that emerges depends on the full load/generation/weather
   growth trajectory over a session, which isn't fully predictable at
   authoring time. The fix is the rule table in
   [Rule-driven proposal derivation](#rule-driven-proposal-derivation): the
   *type and location* of the offered remedy is derived from the observed
   violation at runtime; only the physical existence of compatible dormant
   sites is authored in advance. Trade-off: a preset still needs "enough"
   sites of the right kinds distributed around the network for the rule
   engine to find a match — see Open Questions #5 — but that's a coverage/
   density question, not a prediction-of-the-future question.

3. **Double-circuit lines reuse the existing corridor's rating, not a new
   calculation.**
   A second circuit on the same corridor has (approximately) the same
   impedance and thermal characteristics as the first. Modeling it as a
   duplicate `Line` object between the same terminals means its rating can
   be derived identically to how `PresetNetworkFactory` already derives
   ratings — no new methodology needed, and it sidesteps the harder question
   of rating a truly novel line, which is deferred (see Open Questions on
   `GENERATOR`/`SUBSTATION`/`NEW_LINE` site build-time rating, which don't
   have this shortcut).

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
| `BuildProject` completion mutation (`ConnectGenerator`/`ConnectLine`) fails for any of its `siteIds` (e.g. site somehow already connected) | Log error; mark project `COMPLETE` anyway (data model shouldn't get stuck `BUILDING` forever on an engine bug) and alert; treat as a bug to fix, not a player-facing retry path |
| Session ends (GAME_OVER) mid-build | Project state persists with the session; no special handling needed since a completed session isn't ticking |
| Sustained violation with no matching, not-yet-built `ExpansionSite` nearby (rule table finds no candidate) | No prompt fires — same as today's behavior when a violation has no obvious fix; N-1/planning panel still surfaces the raw violation; a recurring case in playtesting is a signal to author more sites near that area, not a bug |

---

## Testing Strategy

**Unit tests**: `BuildProject.percentComplete` boundary cases; consecutive-tick
stress detector fires only after N ticks, not on a single blip; the rule
table correctly matches each violation type to its candidate
`ExpansionSiteKind`(s) and picks the site whose `remediesElementId` matches
the violating element; declining a card does not create a `BuildProject`
and does not suppress future detection; `ConnectGenerator`/`ConnectLine` on
an `ExpansionSite`'s previously-disconnected elements (including a bundled
`connectingLineSiteId`) produces a network identical in shape to one built
pre-connected (i.e., no special-casing leaks into the mapper).

**Integration tests**: full cycle — seed a `freeplay50` session, force a
sustained thermal violation on a line with a `DOUBLE_LINE` site (and
separately, a sustained voltage violation at a bus with a `SHUNT_COMPENSATOR`
or `GENERATOR` site), assert the event card fires with the expected rule-
matched options, accept one, advance the clock past
`buildDurationGameMinutes`, assert all of the project's sites are connected
and now participate in the next power-flow solve (current flows, dispatch
considers it, N-1 contingency list includes it).

---

## Resolved Design Points (per Rick's review, 2026-07-11 and 2026-07-15)

1. **Prompted options, not free placement.** Agreed — v1 offers 2–3 options
   per prompt, not a build-anywhere sandbox. **Revised 2026-07-15**: which
   options are offered is derived at runtime from the observed violation via
   the rule table (see [Rule-driven proposal derivation](#rule-driven-proposal-derivation)),
   not a fixed 1:1 authoring of "this violation always offers that site."

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
   — now fixed via #407 — contingency analysis all already ignore them the
   same way they ignore any other disconnected element) until the
   `BuildProject` completes and fires the existing connect mutation(s).
   Visual distinction (translucent/wireframe "under construction" mesh) and
   the progress bar are frontend concerns, fed by
   `BuildProject.percentComplete()` and a `status` the WS state stream would
   need to carry (new `GameStateUpdate` field — not yet designed here).

8. **Which remedy gets offered must be derived from observed grid stress,
   not pre-decided per preset.** Agreed (2026-07-15) — a fixed per-preset
   mapping of "this violation always means that site" can't anticipate the
   actual load/gen/weather trajectory of a session. Resolved via the rule
   table in [Rule-driven proposal derivation](#rule-driven-proposal-derivation):
   thermal violations point to line-capacity remedies (`DOUBLE_LINE`, then
   `NEW_LINE`/`SUBSTATION`); voltage violations that persist after existing
   voltage-control dispatch point to voltage-support remedies
   (`SHUNT_COMPENSATOR`, `GENERATOR`). Site *kinds* now include `NEW_LINE`
   (a new generator/substation may need a new line to reconnect to the
   energized grid) and `SHUNT_COMPENSATOR` (dedicated reactive/voltage
   support), in addition to `GENERATOR`, `SUBSTATION`, and `DOUBLE_LINE`.

---

## Open Questions

1. **Contingency analysis filtering — code fix landed (#407); a PowSyBl-
   native alternative worth considering for the rule engine itself.**
   `ContingencyBuilder.buildN1` was missing `.filter { it.connected }` on
   lines/transformers (generators already had it) — a dormant
   `ExpansionSite`'s disconnected elements were generating nonsensical N-1
   scenarios for lines/transformers that were never built. Fixed and merged
   via #407 (generator-style connected-filter added to the lines and
   two-winding-transformer loops; three-winding transformers left
   unfiltered pending a `connected` field on that domain type — noted as a
   smaller follow-up). Separately, per Rick's review: PowSyBl supports
   defining contingency sets via a Groovy DSL (`ContingencyList`/
   `GroovyDslContingenciesProvider`) that can filter directly on element
   state (e.g. `connectableStatus` / connected buses) at the point contingencies
   are enumerated, rather than only in application code after the fact. Worth
   evaluating whether `ContingencyBuilder` should move to a Groovy-script-
   defined contingency list (or borrow its filtering approach) as the
   long-term home for this kind of exclusion, versus the current hand-written
   Kotlin filter — not blocking for this module (the #407 fix already covers
   correctness) but worth a short follow-up spike before or during
   implementation.

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

5. **How many `ExpansionSite`s does `freeplay50` need, and where, per kind?**
   Not determined here — with rule-driven derivation, this becomes a
   coverage/density question: enough `DOUBLE_LINE` sites on lines likely to
   thermally saturate, enough `GENERATOR`/`SHUNT_COMPENSATOR` sites near
   buses likely to see voltage stress, and enough `NEW_LINE`/`SUBSTATION`
   sites for area-wide congestion. Interacts with the region-unlock pacing
   question above. Likely needs a playtesting pass to tune rather than a
   one-time authoring exercise.
