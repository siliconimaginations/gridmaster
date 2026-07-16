# Power Grid Game — Work Plan

## Overview

Educational power grid game with client-server architecture.
- **Backend**: Kotlin + Spring Boot + PowSyBl
- **Frontend**: Vite + React + TypeScript + Babylon.js 7
- **Repo**: `github.com/siliconimaginations/gridmaster` (public, AGPL 3 + commercial license)
- **Dev process**: UX doc → Engineering spec → Implementation → PR → CI → review → merge
- **Work queue**: [GitHub Projects board](https://github.com/users/siliconimaginations/projects/2) is authoritative; check it after every merge

---

## Progress Summary

| Stage | Name | Status |
|-------|------|--------|
| 0 | Foundation & Tooling | ✅ Complete |
| 1 | Physics Engine — design docs | ✅ Complete |
| 2 | UX Design Docs | ✅ Complete |
| 3 | Game Engine Core — implementation | ✅ Complete |
| 4 | 3D Visualization — implementation | 🔄 In Progress (Babylon.js scene essentially done; parallel PixiJS renderer implemented behind a flag, migration decision still open) |
| 5 | Tutorial Mode | ✅ Complete (shipped as a 5-step guided flow, scoped down from the original 8-mission plan — see below) |
| 6 | Free Play Mode | 🔄 In Progress (event engine, panels, and seed network shipped; region-unlock and grid-expansion systems still in design/early implementation) |
| 7 | Challenge Mode | 🔄 In Progress (1 scripted scenario shipped; scenario framework and scoring screen not yet built) |
| 8 | Hardening & CI/CD Maturity | 🔄 In Progress |

*(Last reconciled against closed-issue history: 2026-07-16, issue #416.)*

---

## Repository Structure

```
gridmaster/
├── backend/                    # Kotlin + Spring Boot
│   ├── src/main/kotlin/
│   │   ├── engine/             # PowSyBl physics wrappers
│   │   ├── game/               # Game loop, clock, events
│   │   ├── api/                # REST + WebSocket controllers
│   │   └── persistence/        # Session storage
│   └── src/test/kotlin/
├── frontend/                   # Vite + React + Babylon.js
│   ├── src/
│   │   ├── scene/              # Babylon.js 3D scene (default renderer)
│   │   ├── renderer/           # PixiJS isometric renderer (behind VITE_USE_PIXI flag)
│   │   ├── ui/                 # React HUD panels, toasts, overlays
│   │   ├── state/              # Client game state (Zustand)
│   │   └── api/                # WebSocket + REST client
│   └── public/
│       └── assets/             # 3D meshes (.glb), textures
├── docs/
│   ├── engineering/            # Per-module engineering specs (.md)
│   └── ux/                     # UX design documents
├── .github/
│   ├── badges/                 # Auto-generated coverage badge SVG
│   └── workflows/
│       ├── ci.yml              # 5 parallel jobs: lint + test + coverage
│       ├── e2e.yml             # Playwright E2E suite (runs on main push)
│       └── gemini-review.yml   # AI code review on every PR
├── docker-compose.yml
└── scripts/lint.sh             # ktlint + ESLint, run before every push
```

---

## Stage 0 — Foundation & Tooling ✅

Repo live, CI running, local dev works end-to-end.

- GitHub repo + branch protection (main requires PR + CI green)
- Spring Boot skeleton, health endpoint, PowSyBl dependency (BOM `2025.0.2`)
- Vite + React + TS + Babylon.js skeleton, blank 3D canvas loads
- SQLite persistence for local dev (designed to swap to Postgres)
- GitHub Actions CI: 5 parallel jobs — `backend-lint`, `backend-test`, `backend-integration`, `frontend-lint`, `frontend-test`; target wall-clock < 3.5 min
- Gradle build cache + parallel execution + configuration cache (`backend/gradle.properties`)
- JaCoCo coverage: 60% overall / 70% changed-files thresholds; badge auto-committed on push to main
- Gemini AI code review on every PR; blocks merge on 🔴 Critical / 🟠 Major issues
- `ENGINEERING_PRINCIPLES.md`: shared process rules for all contributors

---

## Stage 1 — Engineering Design Docs ✅

All 10 backend engineering specs written and merged.

| Doc | Module |
|-----|--------|
| `docs/engineering/01-network-model.md` | IIDM network builder, bus/line/gen/load entities |
| `docs/engineering/02-power-flow.md` | AC load flow wrapper, result DTOs |
| `docs/engineering/03-contingency-analysis.md` | N-1 analysis, async background runner |
| `docs/engineering/04-dispatch.md` | Economic dispatch (LP) + Unit commitment (MIP/OR-Tools) |
| `docs/engineering/05-physics-api.md` | REST endpoints: network state, commands |
| `docs/engineering/06-session-model.md` | Session entity, JWT auth, SQLite persistence |
| `docs/engineering/07-game-clock.md` | Tick engine, speed 1×–100×, pause/resume |
| `docs/engineering/08-event-engine.md` | Weather/economic/policy events, effect handlers |
| `docs/engineering/09-command-handler.md` | Player action → validate → physics → new state |
| `docs/engineering/10-websocket-protocol.md` | Server-push GameStateUpdate each tick |

Key decisions baked into the specs:
- PowSyBl BOM `com.powsybl:powsybl-dependencies:2025.0.2` (core 6.7.2, open-loadflow 1.15.2)
- OR-Tools for UC MIP (SCIP/CBC) and LP dispatch
- No DC fallback on AC divergence — `NETWORK_FAILURE` event raised
- Distributed slack bus; `gridMinutesPerTick = 10` (fixed)
- N-1 runs async every 6 ticks, debounced
- `CommandResult` with `commandOutcomes` list (unified, no batch variant)
- Region = annotation on unified IIDM network (not a physical network split)

---

## Stage 2 — UX Design Docs ✅

All 9 UX documents written and merged.

| Doc | Covers |
|-----|--------|
| `docs/ux/01-main-layout.md` | Full-screen layout, HUD structure, map composition rules |
| `docs/ux/02-component-inspector.md` | Per-element popup cards (generator, line, bus, city, transformer) |
| `docs/ux/03-alert-toasts.md` | Toast notification system, severity levels, auto-dismiss |
| `docs/ux/04-time-axis.md` | Clock controls, speed multipliers, day-ahead timeline strip |
| `docs/ux/05-tutorial-overlay.md` | Spotlight, instruction card, 8-mission flow |
| `docs/ux/06-dispatch-panel.md` | Real-time merit order table + 24 h UC schedule grid |
| `docs/ux/07-planning-panel.md` | Investment queue, N-1 table, 7-day demand/renewable forecast |
| `docs/ux/08-event-card.md` | Shared panel pattern: N-1, network failure, weather, fuel, policy |
| `docs/ux/09-scene-visual-spec.md` | Babylon.js art direction, toon shader, per-element visual spec |

Approved design direction:
- Township-style cartoon game, **not** industrial SCADA
- Full-screen Babylon.js isometric 3D scene; all UI as floating HUD overlays
- Top pill HUD (clock, load, price, health) + bottom HUD (speed controls, action buttons)
- Toasts stacked bottom-right; inspector popup per map element click
- Shared event panel pattern: coloured header + causal flow strip + 3 metric cards + option cards

---

## Stage 3 — Backend Implementation ✅

All backend modules complete. Frontend (Stage 4) is in progress.

### 3a — Physics Engine

| Submodule | Design Doc | PR | Status |
|-----------|-----------|-----|--------|
| Network model | `01-network-model.md` | #15 | ✅ Done |
| Power flow adapter | `02-power-flow.md` | #21 | ✅ Done |
| Contingency runner | `03-contingency-analysis.md` | #24 | ✅ Done |
| Economic dispatch + UC | `04-dispatch.md` | #29 | ✅ Done |
| Physics REST API | `05-physics-api.md` | #33 | ✅ Done |

### 3b — Game Engine Core

| Submodule | Design Doc | Status |
|-----------|-----------|--------|
| Session model + JWT auth | `06-session-model.md` | ✅ Done (PR #41) |
| Game clock | `07-game-clock.md` | ✅ Done |
| Event engine | `08-event-engine.md` | ✅ Done |
| Command handler | `09-command-handler.md` | ✅ Done |
| WebSocket state stream | `10-websocket-protocol.md` | ✅ Done |

Stage exit criteria met: Game clock ticks at configured speed; events fire on schedule; player dispatch command updates grid state; new state streams to client over WebSocket within one tick.

### Resolved tech-debt items

| Issue | Title | Status |
|-------|-------|--------|
| #37 | fix(api): async contingency analysis races with concurrent mutations | ✅ Done |
| #38 | perf(api): replace NetworkSerDe round-trip with PowSyBl-native deep copy | P2 — next sprint |
| #40 | fix(api): stricter integer parsing in NetworkMutationDto.toDomain() | ✅ Done (PR #169) |
| #43 | feat(auth): validate userId format as UUID in IssueTokenRequest | ✅ Done |
| #46 | feat(game): replace 'ieee14' preset with real IEEE 14-bus XIIDM | ✅ Done |

---

## Stage 4 — Frontend Implementation 🔄

3D scene, HUD, and all panels wired to live backend state.

### 4a — Babylon.js Scene

| Submodule | UX Reference | Status |
|-----------|-------------|--------|
| Scene foundation | `09-scene-visual-spec.md` | ✅ Done |
| Toon shader (cel-shade + outline pass) | `09-scene-visual-spec.md` | ✅ Done |
| Isometric camera (pan + zoom) | `01-main-layout.md` | ✅ Done |
| Terrain (heightmap, grass, river) | `09-scene-visual-spec.md` | ✅ Done |
| Grid element meshes (procedural) | `09-scene-visual-spec.md` | ✅ Done (PR #168) |
| Power flow particle animation | `01-main-layout.md` | ✅ Done |
| LOD (far = icon sprite, near = mesh) + city/town mesh LOD tiers | `09-scene-visual-spec.md` | ✅ Done (#80) |

### 4b — React UI

| Submodule | UX Reference | Status |
|-----------|-------------|--------|
| Top HUD — pill badges | `01-main-layout.md` | ✅ Done (PR #168, closes #115) |
| Bottom HUD — speed controls | `04-time-axis.md` | ✅ Done |
| Bottom HUD — contextual action buttons | `01-main-layout.md` | ✅ Done |
| Day-ahead timeline strip | `04-time-axis.md` | ✅ Done (#84) |
| Alert toast system | `03-alert-toasts.md` | ✅ Done (#85) |
| Component inspector popup | `02-component-inspector.md` | ✅ Done (#86) |
| Event card panel (shared pattern) | `08-event-card.md` | ✅ Done (#87) |
| Dispatch panel (merit order + UC grid) | `06-dispatch-panel.md` | ✅ Done (PR #155, closes #88) |
| Planning panel (invest + N-1 + forecast) | `07-planning-panel.md` | ✅ Done (#89) |
| Player action log (scrollable command history) | — (not in original UX doc set) | ✅ Done (#196) |

### 4c — State & API Layer

| Submodule | Notes | Status |
|-----------|-------|--------|
| Zustand store — game state | Mirrors server `GameStateUpdate` shape | ✅ Done |
| WebSocket client (STOMP) | Subscribe to tick updates, merge into store | ✅ Done |
| REST client | Session create/resume, one-off commands | ✅ Done |
| Command dispatch | Player action → REST POST → optimistic UI update | ✅ Done |

### 4d — Frontend Art Strategy

No external artists needed for MVP.

| Phase | What | How |
|-------|------|-----|
| MVP | All grid element meshes (pylons, generators, substations) | Procedural Babylon.js geometry; toon shader makes them look intentional |
| Polish | Building variety for cities and towns | Free CC0 models from **Kenney.nl** (City Kit, Tiny Town packs) — drop-in `.glb` files |
| Textures | Terrain grass/dirt, water normal map | AI-generated using prompts written alongside code |

Icons: written as SVG directly. UI animations: Framer Motion (React) + Babylon.js animation groups (scene).

### 4e — PixiJS Isometric Renderer (parallel effort)

The UX direction later shifted from real-3D (Babylon.js meshes) toward a
pseudo-3D isometric 2D renderer; a full PixiJS-based renderer was built as an
alternative rather than folded into 4a. See
`docs/engineering/15-pixi-renderer.md` for the architecture and rationale.

| Submodule | Issue | Status |
|-----------|-------|--------|
| Renderer core + `GridCanvas` | #311 | ✅ Done |
| Data model (`GridGraph`) | #312 | ✅ Done |
| Auto-layout algorithm | #313 | ✅ Done |
| LOD controller | #314 | ✅ Done |
| Particle flow layer | #315 | ✅ Done |

**Not yet decided**: PixiJS is implemented and functional behind the
`VITE_USE_PIXI` flag, but Babylon.js remains the *default* production
renderer — the migration/cutover has not happened. Treat Stage 4 as complete
for Babylon.js and complete-but-flagged for PixiJS; the follow-up decision
(migrate, keep both, or drop one) is tracked in #432.

Exit criteria: IEEE 14-bus renders on screen; lines animate power flow; clicking any element opens its inspector; HUD updates every tick; dispatch panel opens and submits a command. **Met** — all Stage 4 UX-doc-scoped submodules above are shipped; the PixiJS-vs-Babylon.js renderer decision (4e) is the one open loose end.

---

## Stage 5 — Tutorial Mode ✅

**Shipped scope differs from the original plan below.** Tutorial mode landed
(#193) as a **5-step guided flow** on the existing `tutorial` preset network,
not the originally-planned 8-mission YAML framework on a dedicated 14–20 bus
teaching network. This was a deliberate scope reduction, not an oversight —
the 5 steps below cover the same core loop (observe → dispatch → react to an
event → pause/resume → complete) with a fraction of the authoring cost of 8
discrete missions. The original 8-mission table is kept below for reference
in case the fuller curriculum is revisited later.

**Shipped (5-step flow, `TutorialStep` enum, backend/src/main/kotlin/com/gridmaster/game/tutorial/):**

| Step | What it teaches |
|------|------------------|
| 1. Observe | Read health score and load balance in the top bar |
| 2. Dispatch | Open the Dispatch panel and change a generator's output |
| 3. Demand spike | React to a scheduled event via alerts + dispatch |
| 4. Pause/resume | Clock control: pause, inspect, resume |
| 5. Complete | Wrap-up, hand-off to Free Play |

Tutorial overlay (spotlight, instruction card) shipped per
`docs/ux/05-tutorial-overlay.md` as part of the same effort.

Exit criteria (revised): all 5 steps completable; each teaches its stated
concept; progress persists. **Met.**

<details>
<summary>Original 8-mission plan (superseded, kept for reference)</summary>

| Submodule | Notes |
|-----------|-------|
| Mission framework | YAML-defined missions; objective tracker; pass/fail conditions |
| Tutorial network | Custom 14–20 bus network designed for pedagogy |
| Mission 1 — Grid anatomy | Click every element type |
| Mission 2 — Power flow | Trace generation to load |
| Mission 3 — Operating limits | Trigger overload, restore |
| Mission 4 — Generator dispatch | Manually meet demand |
| Mission 5 — Contingency analysis | Read N-1 risk table |
| Mission 6 — Economic dispatch | Merit order, minimize cost |
| Mission 7 — Unit commitment | Fill 24 h day-ahead schedule |
| Mission 8 — Dynamics & stability | Respond to sudden generator trip |

</details>

---

## Stage 6 — Free Play Mode 🔄

Long-running campaign with organic grid growth (~50 → ~500 buses).

| Submodule | Notes | Status |
|-----------|-------|--------|
| Environment event engine | Randomized weather/economic/policy events; expanded catalogue (fuel shortage, equipment failure) | ✅ Done (#195) |
| Planning panel | Per `docs/ux/07-planning-panel.md` — invest, N-1, forecast | ✅ Done (#89) |
| Dispatch panel | Per `docs/ux/06-dispatch-panel.md` — day-ahead UC workflow | ✅ Done (#88) |
| Free play seed network | Multi-regional ~50 bus network with expansion zones | ✅ Done (#47) |
| Game over / scoring | Health-score-based end condition | ✅ Done (#194) |
| Grid expansion (Module 17) | `ExpansionSite`/`BuildProject` domain model + dormant-site seeding | ✅ Done (#414); design doc merged (PR #408) |
| Grid expansion — budget model (Module 17 addendum) | Design doc | 🔍 In review (#413) |
| Region unlock system (Module 13) | Design doc — resolved as a Module 17 rule-table extension, not a separate system | 🔍 In review (#412) |
| Grid expansion — stress detection → EventCard, tick-engine integration, REST/WS surface, frontend visuals | Player-facing build flow | 🔲 Planned (#418, #419, #420, #421, #422) |
| Policy event cards | "Accept renewable subsidy?" — card-based decisions | ✅ Done (part of #195's event catalogue) |

Exit criteria: Session runs 1 simulated year without crash; ≥3 environment
event types fire; regions unlock organically. **Partially met** — the
long-running loop, events, and panels work today; region-unlock and the
player-facing side of grid expansion (build → invest → unlock) are designed
but not yet implemented, so "regions unlock organically" is not yet
achievable in a live session.

---

## Stage 7 — Challenge Mode 🔄

Pre-loaded crisis scenarios, scored resolution.

| Submodule | Notes | Status |
|-----------|-------|--------|
| Scripted scenario v1 | Storm trips a line, then a demand surge; health-threshold victory condition (`ChallengeEngineImpl`) | ✅ Done (#330) |
| Scenario framework | YAML definitions; entry state loader — scripted in code today, not data-driven | 🔲 Planned |
| Additional scenarios (2 more) | Broader scenario variety | 🔲 Planned (#425) |
| Score & results screen | Time-to-resolve, cost, reliability metrics — today only a boolean victory flag exists | 🔲 Planned |

Exit criteria: ≥3 scenarios completable with correct scoring. **Not yet
met** — 1 scripted scenario ships with a simple victory condition; the
data-driven framework, additional scenarios, and a real scoring/results
screen are still ahead.

---

## Stage 8 — Hardening & CI/CD Maturity 🔄

| Submodule | Notes | Status |
|-----------|-------|--------|
| Integration tests | Full game loop: clock → event → physics → WebSocket state | ✅ Done (extensive `-Pintegration` suite + e2e coverage tooling, #170/#184) |
| Coverage gates | JaCoCo overall threshold raised 60% → 80% | ✅ Done (#338) |
| Frontend E2E | Playwright E2E suite promoted to required PR gate (currently runs on main push only) | 🔲 Planned (#424) |
| Performance profiling | <1000-bus power flow within tick budget | 🔲 Planned (#423) |
| Cloud deploy guide | Docker Compose → cloud-ready config | 🔲 Planned (not yet started) |
| Codecov integration | Historical coverage trending | 🔲 Planned (#417) |

---

## Development Process Per Feature

```
1. UX design doc (if visual/interaction change)    → PR → review → merge
2. Engineering design doc                          → PR → review → merge
3. Implementation (backend or frontend)            → PR → CI green → review → merge
4. Integration test coverage                       → PR → CI green → merge
```

**Branch naming**: `stage/<n>/<short-description>`  
**PR rules**: linked design doc, lint green, tests added; non-critical PRs merge autonomously after CI + Gemini green  
**Work queue**: always check the [Projects board](https://github.com/users/siliconimaginations/projects/2) after each merge — it is authoritative over this file  
**Lint**: run `bash scripts/lint.sh` before every push  
